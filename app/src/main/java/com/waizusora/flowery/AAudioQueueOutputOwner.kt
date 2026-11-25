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
        sampleRateHz > 0 && channels in 1..2 && outputFormat in 1..4 &&
            deviceId >= 0 && sharingMode in SHARING_EXCLUSIVE..SHARING_SHARED

    companion object {
        const val SHARING_EXCLUSIVE = 0
        const val SHARING_SHARED = 1
    }
}

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
 * This class is deliberately independent of FloweryPlayer until the player can
 * transfer decoder producers without closing the queue-owned device output.
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
        native.setPersistence(true)
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
