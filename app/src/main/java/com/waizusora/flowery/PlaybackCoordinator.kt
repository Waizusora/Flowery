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
) : AutoCloseable {
    enum class State { IDLE, PREPARING, PLAYING, PAUSED, STOPPING, FAILED }

    private val closed = AtomicBoolean(false)
    private val nativeController = NativeAudio.nativeCreatePlaybackController().also {
        check(it != 0L) { "Unable to create the native playback controller." }
    }
    private val engine = FloweryPlayer(context) { message ->
        if (!closed.get()) when {
            message.startsWith("Playing") -> setState(State.PLAYING)
            message.startsWith("Paused") -> setState(State.PAUSED)
            message.startsWith("Unable") || message.startsWith("Error") || message.startsWith("Playback error") ->
                setState(State.FAILED)
            message == "Playback finished" -> setState(State.IDLE)
        }
        if (!closed.get()) onState(message)
    }

    private fun setState(value: State) {
        check(NativeAudio.nativeSetPlaybackControllerState(nativeController, value.ordinal) == 0) {
            "Rust rejected the playback controller state."
        }
    }

    fun state(): State = State.values().getOrElse(
        NativeAudio.nativeGetPlaybackControllerState(nativeController)
    ) { State.FAILED }

    @Synchronized
    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null) {
        setState(State.PREPARING)
        engine.play(uri, segmentStartMs, segmentEndMs)
    }

    @Synchronized
    fun pause() {
        if (state() == State.PLAYING) {
            engine.pause()
            setState(State.PAUSED)
        }
    }

    @Synchronized
    fun resume() {
        if (state() == State.PAUSED) {
            engine.resume()
            setState(State.PLAYING)
        }
    }

    @Synchronized fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    fun positionMs(): Long = engine.positionMs()
    fun durationMs(): Long = engine.durationMs()
    @Synchronized fun setVolume(value: Float) = engine.setVolume(value)
    @Synchronized fun setDucked(ducked: Boolean) = engine.setDucked(ducked)
    @Synchronized fun setEqualizerGains(gains: FloatArray) = engine.setEqualizerGains(gains)

    @Synchronized
    fun stop() {
        setState(State.STOPPING)
        engine.stop()
        setState(State.IDLE)
    }

    fun close(onClosed: () -> Unit = {}) {
        if (!closed.compareAndSet(false, true)) return
        NativeAudio.nativeSetPlaybackControllerState(nativeController, State.STOPPING.ordinal)
        // Do not block Android's service lifecycle thread while the worker releases native handles.
        // stopAndJoin captures the active worker before interrupting it, then waits for native cleanup.
        thread(name = "FloweryPlaybackControllerCleanup") {
            engine.stopAndJoin(Long.MAX_VALUE)
            NativeAudio.nativeSetPlaybackControllerState(nativeController, State.IDLE.ordinal)
            NativeAudio.nativeDestroyPlaybackController(nativeController)
            onClosed()
        }
    }
}
