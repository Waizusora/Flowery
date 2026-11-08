package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android service adapter for the native playback lifecycle controller.
 * The controller state is stored in Rust; this class retains Android command forwarding.
 */
internal class PlaybackCoordinator(
    context: Context,
    private val onState: (String) -> Unit
) {
    enum class State { IDLE, PREPARING, PLAYING, PAUSED, STOPPING, FAILED }

    private companion object {
        const val EVENT_PREPARE = 0
        const val EVENT_START = 1
        const val EVENT_PAUSE = 2
        const val EVENT_RESUME = 3
        const val EVENT_STOP = 4
        const val EVENT_FINISH = 5
        const val EVENT_FAIL = 6
    }

    private val closed = AtomicBoolean(false)
    private val nativeController = NativeAudio.nativeCreatePlaybackController().also {
        check(it != 0L) { "Unable to create the native playback controller." }
    }
    private val engine = FloweryPlayer(context) { message ->
        if (!closed.get()) when {
            message.startsWith("Playing") -> transition(EVENT_START)
            message.startsWith("Paused") -> transition(EVENT_PAUSE)
            message.startsWith("Unable") || message.startsWith("Error") || message.startsWith("Playback error") ->
                transition(EVENT_FAIL)
            message == "Playback finished" -> transition(EVENT_FINISH)
        }
        if (!closed.get()) onState(message)
    }

    private fun transition(event: Int) {
        check(NativeAudio.nativeTransitionPlaybackController(nativeController, event) >= 0) {
            "Rust rejected the playback lifecycle event."
        }
    }

    fun state(): State = State.values().getOrElse(
        NativeAudio.nativeGetPlaybackControllerState(nativeController)
    ) { State.FAILED }

    @Synchronized
    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null) {
        transition(EVENT_PREPARE)
        engine.play(uri, segmentStartMs, segmentEndMs)
    }

    @Synchronized
    fun pause() {
        if (state() == State.PLAYING) {
            engine.pause()
            transition(EVENT_PAUSE)
        }
    }

    @Synchronized
    fun resume() {
        if (state() == State.PAUSED) {
            engine.resume()
            transition(EVENT_RESUME)
        }
    }

    @Synchronized fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun currentVolumeFraction(): Float = engine.currentVolumeFraction()

    fun positionMs(): Long = engine.positionMs()
    fun durationMs(): Long = engine.durationMs()
    @Synchronized fun setVolume(value: Float) = engine.setVolume(value)
    @Synchronized fun setDucked(ducked: Boolean) = engine.setDucked(ducked)
    @Synchronized fun setEqualizerGains(gains: FloatArray) = engine.setEqualizerGains(gains)

    @Synchronized
    fun stop() {
        transition(EVENT_STOP)
        engine.stop()
        transition(EVENT_FINISH)
    }

    /**
     * Stops playback and waits for the USB worker to close its session and release
     * claimed interfaces before notifying the caller.
     */
    @Synchronized
    fun releaseUsb(onReleased: () -> Unit) {
        transition(EVENT_STOP)
        thread(name = "FloweryUsbRelease") {
            engine.stopAndJoin(Long.MAX_VALUE)
            transition(EVENT_FINISH)
            onReleased()
        }
    }

    fun close(onClosed: () -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        transition(EVENT_STOP)
        // Do not block Android's service lifecycle thread while the worker releases native handles.
        // stopAndJoin captures the active worker before interrupting it, then waits for native cleanup.
        thread(name = "FloweryPlaybackControllerCleanup") {
            engine.stopAndJoin(Long.MAX_VALUE)
            transition(EVENT_FINISH)
            NativeAudio.nativeDestroyPlaybackController(nativeController)
            onClosed()
        }
    }
}
