package com.waizusora.flowery

/**
 * Exact identity of an Android-managed output that may be reused across tracks.
 * Any difference requires a drain/reopen path instead of same-stream handoff.
 */
internal data class AAudioOutputSignature(
    val sampleRateHz: Int,
    val channels: Int,
    val outputFormat: Int,
    val deviceId: Int,
    val sharingMode: Int,
) {
    fun isValid(): Boolean =
        sampleRateHz > 0 && channels in 1..8 && outputFormat in 1..4 &&
            (outputFormat != 2 || channels <= 2) &&
            deviceId >= 0 && sharingMode in SHARING_EXCLUSIVE..SHARING_SHARED

    companion object {
        const val SHARING_EXCLUSIVE = 0
        const val SHARING_SHARED = 1
    }
}

/** Status 3 is an early, non-terminal boundary; status 4 is the drained boundary. */
internal fun shouldAdvanceAaudioQueue(status: Int, hasNextTrack: Boolean): Boolean =
    status == 4 || (status == 3 && hasNextTrack)
internal interface AAudioQueueOutputNative {
    fun setPersistence(enabled: Boolean)
    fun attachNextTrack(signature: AAudioOutputSignature): Int
    fun stopOutput()
}

internal class JniAAudioQueueOutputNative : AAudioQueueOutputNative {
    override fun setPersistence(enabled: Boolean) = NativeAudio.nativeSetOutputPersistence(enabled)

    override fun attachNextTrack(signature: AAudioOutputSignature): Int =
        NativeAudio.nativeAttachStreamProducerForNextTrack(
            signature.sampleRateHz,
            signature.channels,
            signature.outputFormat,
            signature.deviceId,
            signature.sharingMode,
        )

    override fun stopOutput() = NativeAudio.nativeStopOutput()
}

/**
 * Queue-scoped lease coordinator for one already-open AAudio stream.
 *
 * FloweryPlayer owns one instance for its active queue. It retains a compatible
 * AAudio stream after track EOF and releases it on queue EOF, cancellation, or
 * a PCM/device signature change.
 */
internal class AAudioQueueOutputOwner(
    private val native: AAudioQueueOutputNative,
) : AutoCloseable {
    enum class Phase { CLOSED, OPEN, RETAINED, PREPARED }

    private var phase = Phase.CLOSED
    private var signature: AAudioOutputSignature? = null

    @Synchronized
    fun phase(): Phase = phase

    @Synchronized
    fun currentSignature(): AAudioOutputSignature? = signature

    @Synchronized
    fun adoptOpenedOutput(opened: AAudioOutputSignature): Boolean {
        if (phase != Phase.CLOSED || !opened.isValid()) return false
        signature = opened
        phase = Phase.OPEN
        return true
    }

    /** Retains the native device stream after the current decoder reaches EOF. */
    @Synchronized
    fun retainForNextTrack(): Boolean {
        if (phase != Phase.OPEN) return false
        // The lease is not retained unless the native persistence flag was
        // actually accepted. Keep OPEN so the caller can take the normal close path.
        if (runCatching { native.setPersistence(true) }.isFailure) return false
        phase = Phase.RETAINED
        return true
    }

    /**
     * Attaches a new producer epoch only when the next track has an identical
     * PCM and device signature. The existing ring and buffered tail are preserved.
     */
    @Synchronized
    fun prepareNextTrack(next: AAudioOutputSignature): Boolean {
        if (phase != Phase.RETAINED || signature != next || !next.isValid()) return false
        val result = runCatching { native.attachNextTrack(next) }.getOrElse { return false }
        if (result != 0) return false
        phase = Phase.PREPARED
        return true
    }

    /** Called only after the next decoder producer has successfully started. */
    @Synchronized
    fun confirmProducerAttached(): Boolean {
        if (phase != Phase.PREPARED) return false
        phase = Phase.OPEN
        return true
    }

    /** Queue EOF, cancellation, route loss, or failed reconfiguration releases the lease. */
    @Synchronized
    override fun close() {
        if (phase == Phase.CLOSED) return
        try {
            native.setPersistence(false)
        } finally {
            try {
                native.stopOutput()
            } finally {
                phase = Phase.CLOSED
                signature = null
            }
        }
    }
}


/** Cumulative callback/XRun counters and queue depth captured from one AAudio snapshot. */
internal data class AAudioHandoffCounterSnapshot(
    val callbackUnderruns: Long,
    val xrunCount: Long,
    val queuedFrames: Long,
    val sampleRateHz: Int,
)

internal data class AAudioHandoffCounterDelta(
    val callbackUnderruns: Long,
    val xrunCount: Long,
    val queuedFrames: Long,
    val queuedDurationMs: Long?,
) {
    val status: String
        get() = when {
            callbackUnderruns > 0L || xrunCount > 0L -> "COUNTER_INCREASED"
            queuedFrames == 0L -> "QUEUE_EMPTY"
            else -> "NO_COUNTER_INCREASE"
        }
}

/**
 * Compares cumulative counters from the same retained AAudio stream.
 * A reset/decrease is UNKNOWN, never silently treated as a clean handoff.
 */
internal fun compareAaudioHandoffCounters(
    boundary: AAudioHandoffCounterSnapshot?,
    current: AAudioHandoffCounterSnapshot?,
): AAudioHandoffCounterDelta? {
    if (boundary == null || current == null ||
        boundary.callbackUnderruns < 0L || boundary.xrunCount < 0L || boundary.queuedFrames < 0L ||
        boundary.sampleRateHz <= 0 || current.callbackUnderruns < boundary.callbackUnderruns ||
        current.xrunCount < boundary.xrunCount || current.queuedFrames < 0L ||
        current.sampleRateHz <= 0
    ) return null
    return AAudioHandoffCounterDelta(
        callbackUnderruns = current.callbackUnderruns - boundary.callbackUnderruns,
        xrunCount = current.xrunCount - boundary.xrunCount,
        queuedFrames = current.queuedFrames,
        queuedDurationMs = queuedPcmDurationMs(current.queuedFrames, current.sampleRateHz),
    )
}

/** Classifies whether handoff startup finished before the previous track's queued tail budget. */
internal enum class HandoffTailBudgetStatus { UNKNOWN, WITHIN_BUDGET, EXCEEDED }

internal fun classifyHandoffTailBudget(boundaryQueuedDurationMs: Long?, handoffElapsedMs: Long?): HandoffTailBudgetStatus {
    if (boundaryQueuedDurationMs == null || boundaryQueuedDurationMs < 0L || handoffElapsedMs == null || handoffElapsedMs < 0L) {
        return HandoffTailBudgetStatus.UNKNOWN
    }
    return if (handoffElapsedMs <= boundaryQueuedDurationMs) HandoffTailBudgetStatus.WITHIN_BUDGET
    else HandoffTailBudgetStatus.EXCEEDED
}

/** Converts queued output frames into a conservative duration budget. */
internal fun queuedPcmDurationMs(queuedFrames: Long, sampleRateHz: Int): Long? {
    if (queuedFrames < 0L || sampleRateHz <= 0 || queuedFrames > Long.MAX_VALUE / 1_000L) return null
    return queuedFrames * 1_000L / sampleRateHz.toLong()
}

/** Converts monotonic nanosecond timestamps into a non-negative handoff duration. */
internal fun elapsedHandoffMs(boundaryAtNanos: Long, nowNanos: Long): Long? {
    if (boundaryAtNanos <= 0L || nowNanos < boundaryAtNanos) return null
    return (nowNanos - boundaryAtNanos) / 1_000_000L
}
