package com.waizusora.flowery

/**
 * Queue navigation state independent from Android service and UI lifecycle.
 * Native Rust remains the single source of truth for shuffle/repeat transition rules.
 */
internal class PlaybackQueueState(
    private val nativeTransition: (Int, Int, Boolean, Int, Int) -> Int =
        { current, size, shuffle, repeat, action ->
            NativeAudio.nativeQueueTransition(current, size, shuffle, repeat, action)
        }
) {
    var currentIndex: Int = -1
        private set
    var repeatMode: Int = 0
        private set
    var shuffleEnabled: Boolean = false
        private set

    fun select(index: Int, queueSize: Int) {
        currentIndex = if (queueSize <= 0) -1 else index.coerceIn(0, queueSize - 1)
    }

    fun setRepeatMode(mode: Int) {
        require(mode in 0..2) { "Repeat mode must be Off (0), All (1), or One (2)." }
        repeatMode = mode
    }

    fun cycleRepeatMode(): Int {
        repeatMode = (repeatMode + 1) % 3
        return repeatMode
    }

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        return shuffleEnabled
    }

    fun setShuffle(enabled: Boolean) { shuffleEnabled = enabled }

    /** Returns an index, -1 to stop, or -2 to replay the current item on completion. */
    fun transition(queueSize: Int, action: Action): Int {
        if (queueSize <= 0 || currentIndex !in 0 until queueSize) return -1
        return nativeTransition(
            currentIndex,
            queueSize,
            shuffleEnabled,
            repeatMode,
            action.nativeValue
        )
    }

    enum class Action(val nativeValue: Int) {
        NEXT(0), PREVIOUS(1), COMPLETION(2)
    }
}
