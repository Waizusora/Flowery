package com.waizusora.flowery

/**
 * Android-facing adapter for native playback queue state.
 * Queue index, repeat mode, shuffle state, and transition policy are owned by Rust.
 */
internal class PlaybackQueueState : AutoCloseable {
    private val handle = NativeAudio.nativeCreatePlaybackQueueState().also {
        check(it != 0L) { "Unable to create the native playback queue state." }
    }

    private fun snapshot(): LongArray = NativeAudio.nativeGetPlaybackQueueSnapshot(handle).also {
        check(it.size == 3) { "Rust returned an invalid playback queue snapshot." }
    }

    val currentIndex: Int get() = snapshot()[0].toInt()
    val repeatMode: Int get() = snapshot()[1].toInt()
    val shuffleEnabled: Boolean get() = snapshot()[2] != 0L

    fun select(index: Int, queueSize: Int) {
        NativeAudio.nativePlaybackQueueSelect(handle, index, queueSize)
    }

    fun setRepeatMode(mode: Int) {
        require(mode in 0..2) { "Repeat mode must be Off (0), All (1), or One (2)." }
        check(NativeAudio.nativePlaybackQueueSetRepeatMode(handle, mode) == mode) {
            "Rust rejected the repeat mode."
        }
    }

    fun cycleRepeatMode(): Int = NativeAudio.nativePlaybackQueueCycleRepeatMode(handle).also {
        check(it in 0..2) { "Rust could not cycle the repeat mode." }
    }

    fun toggleShuffle(): Boolean = NativeAudio.nativePlaybackQueueToggleShuffle(handle)

    fun setShuffle(enabled: Boolean) {
        NativeAudio.nativePlaybackQueueSetShuffle(handle, enabled)
    }

    /** Returns an index, -1 to stop, or -2 to replay the current item on completion. */
    fun transition(queueSize: Int, action: Action): Int =
        NativeAudio.nativePlaybackQueueTransition(handle, queueSize, action.nativeValue)

    override fun close() = NativeAudio.nativeDestroyPlaybackQueueState(handle)

    enum class Action(val nativeValue: Int) {
        NEXT(0), PREVIOUS(1), COMPLETION(2)
    }
}
