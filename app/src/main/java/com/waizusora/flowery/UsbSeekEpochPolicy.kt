package com.waizusora.flowery

/**
 * Determines whether a direct USB seek must invalidate the active PCM producer epoch.
 *
 * Encoded playback owns its epoch transition inside the transport-aware transactional
 * seek operation. Startup seeks have no active PCM producer to invalidate.
 */
internal fun shouldAdvanceUsbSessionEpochBeforeSeek(
    encodedPipelineActive: Boolean,
    pcmProducerHandle: Long,
): Boolean = !encodedPipelineActive && pcmProducerHandle != 0L
