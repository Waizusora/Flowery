package com.waizusora.flowery

/**
 * Tracks call-related playback interruption independently from audio-focus recovery.
 * A call ending clears the latch but never requests playback to resume.
 */
internal class CallInterruptionPolicy {
    enum class Transition { NONE, CALL_STARTED, CALL_ENDED }

    @Volatile
    var isInterrupted: Boolean = false
        private set

    fun restoreInterrupted(interrupted: Boolean) {
        isInterrupted = interrupted
    }

    fun update(callAudioModeActive: Boolean): Transition {
        return when {
            callAudioModeActive && !isInterrupted -> {
                isInterrupted = true
                Transition.CALL_STARTED
            }
            !callAudioModeActive && isInterrupted -> {
                isInterrupted = false
                Transition.CALL_ENDED
            }
            else -> Transition.NONE
        }
    }

    fun blocksPlayback(callAudioModeActive: Boolean): Boolean {
        update(callAudioModeActive)
        return callAudioModeActive || isInterrupted
    }
}
