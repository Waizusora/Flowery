package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates application-level playback commands and owns the lifetime of the native player.
 * Audio decoding, DSP, and output remain in the Rust engine; this class coordinates Android
 * service requests with that engine and publishes a stable lifecycle state.
 */
internal class PlaybackCoordinator(
    context: Context,
    private val onState: (String) -> Unit
) {
    enum class State { IDLE, PREPARING, PLAYING, PAUSED, STOPPING, FAILED }

    private val state = AtomicReference(State.IDLE)
    private val engine = FloweryPlayer(context) { message ->
        state.set(
            when {
                message.startsWith("Playing") -> State.PLAYING
                message.startsWith("Paused") -> State.PAUSED
                message.startsWith("Unable") || message.startsWith("Error") -> State.FAILED
                message == "Playback finished" -> State.IDLE
                else -> state.get()
            }
        )
        onState(message)
    }

    fun state(): State = state.get()

    @Synchronized
    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null) {
        state.set(State.PREPARING)
        engine.play(uri, segmentStartMs, segmentEndMs)
    }

    @Synchronized
    fun pause() {
        if (state.get() == State.PLAYING) {
            engine.pause()
            state.set(State.PAUSED)
        }
    }

    @Synchronized
    fun resume() {
        if (state.get() == State.PAUSED) {
            engine.resume()
            state.set(State.PLAYING)
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
        state.set(State.STOPPING)
        engine.stop()
        state.set(State.IDLE)
    }
}
