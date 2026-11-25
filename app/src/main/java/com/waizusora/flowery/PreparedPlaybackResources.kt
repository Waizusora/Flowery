package com.waizusora.flowery

/**
 * Owns every resource created while preparing one track for playback.
 *
 * Close order is significant: stop/release the native playback session first,
 * release its decoder handle second, and close the SAF descriptor last.
 * The descriptor must outlive the decoder because native decoding may retain
 * file access for the entire decoder lifetime.
 *
 * This wrapper does not start output and does not transfer ownership of a
 * running AAudio or USB output stream.
 */
internal class PreparedPlaybackResources(
    val decoderHandle: Long,
    val sessionHandle: Long,
    val sourceSampleRateHz: Int,
    val channels: Int,
    val sourceValidBits: Int,
    val sourceDataFormat: Int,
    private val closeSource: AutoCloseable,
    private val releaseSession: (Long) -> Unit,
    private val releaseDecoder: (Long) -> Unit,
) : AutoCloseable {
    private var closed = false

    init {
        require(decoderHandle != 0L) { "Prepared decoder handle must be valid." }
        require(sessionHandle != 0L) { "Prepared playback session handle must be valid." }
        require(sourceSampleRateHz > 0) { "Prepared source sample rate must be positive." }
        require(channels in 1..8) { "Prepared source channel count must be between 1 and 8." }
        require(sourceValidBits in 1..32) { "Prepared source bit depth must be between 1 and 32." }
        require(sourceDataFormat in 1..3) { "Prepared source data format is unsupported." }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        // Continue teardown even if an earlier native release reports an error.
        runCatching { releaseSession(sessionHandle) }
        runCatching { releaseDecoder(decoderHandle) }
        runCatching { closeSource.close() }
    }
}
