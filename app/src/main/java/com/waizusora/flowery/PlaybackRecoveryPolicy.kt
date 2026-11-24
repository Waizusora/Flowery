package com.waizusora.flowery

/**
 * Defines the safe startup decision for a persisted playback session.
 *
 * A restored queue is prepared only after an explicit Play command. The
 * previous playing state is diagnostic and never grants auto-play permission.
 */
internal data class PlaybackRecoveryPlan(
    val sessionAvailable: Boolean,
    val requiresPrepareOnPlay: Boolean,
    val positionMs: Long,
    val autoPlay: Boolean,
)

internal object PlaybackRecoveryPolicy {
    fun from(snapshot: PlaybackRecoverySnapshot?): PlaybackRecoveryPlan {
        val recoverableSnapshot = snapshot?.takeIf { it.queue.isNotEmpty() }
        return PlaybackRecoveryPlan(
            sessionAvailable = recoverableSnapshot != null,
            requiresPrepareOnPlay = recoverableSnapshot != null,
            positionMs = recoverableSnapshot?.positionMs?.coerceAtLeast(0L) ?: 0L,
            autoPlay = false,
        )
    }
}
