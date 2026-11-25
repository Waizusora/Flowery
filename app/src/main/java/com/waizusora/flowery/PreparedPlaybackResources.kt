package com.waizusora.flowery

import android.content.Context
import android.net.Uri

/**
 * Owns every resource created while preparing one track for playback.
 *
 * Close order is significant: release the native playback session first,
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
    val route: Int,
    val sourceFileDescriptor: Int,
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
        require(route in 0..3) { "Prepared playback route is unsupported." }
        require(sourceFileDescriptor >= 0) { "Prepared source file descriptor is invalid." }
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

    companion object {
        /**
         * Opens and primes a decoder without starting any output worker.
         * The returned native session is in Prepared phase and can be handed
         * to the playback owner when its queue boundary is reached.
         */
        fun prepare(
            context: Context,
            uri: Uri,
            resolveExtension: (Uri) -> String,
        ): PreparedPlaybackResources {
            check(ReplayGainPreferences.apply(context)) { "ReplayGain preferences could not be applied to the native audio engine." }
            val source = PlaybackSourceDescriptor.open(context, uri, resolveExtension)
            var decoderHandle = 0L
            var sessionHandle = 0L
            try {
                decoderHandle = NativeAudio.nativeCreateStreamDecoder(source.fileDescriptor, source.extension)
                check(decoderHandle != 0L) { "The prefetched native decoder could not be created." }

                val session = NativeAudio.nativePreparePlaybackSession(decoderHandle)
                check(session.size == 6) { "Rust returned an invalid prepared playback session." }
                sessionHandle = session[0]
                check(sessionHandle != 0L) { "The prefetched native playback session could not be created." }

                val route = session[1].toInt()
                val sampleRate = session[2].toInt()
                val channels = session[3].toInt()
                val validBits = session[4].toInt()
                val dataFormat = session[5].toInt()
                check(route in 0..3) { "The native planner returned an unsupported output route." }
                check(sampleRate > 0 && channels in 1..8) {
                    "The prefetched decoder returned an unsupported PCM format."
                }
                check(validBits in 1..32 && dataFormat in 1..3) {
                    "The prefetched decoder returned an unsupported sample representation."
                }
                check(NativeAudio.nativePlaybackSessionCommand(sessionHandle, 1).toInt() == 0) {
                    "The prefetched native session could not enter the prepared state."
                }

                return PreparedPlaybackResources(
                    decoderHandle = decoderHandle,
                    sessionHandle = sessionHandle,
                    route = route,
                    sourceFileDescriptor = source.fileDescriptor,
                    sourceSampleRateHz = sampleRate,
                    channels = channels,
                    sourceValidBits = validBits,
                    sourceDataFormat = dataFormat,
                    closeSource = source,
                    releaseSession = NativeAudio::nativeClosePlaybackSession,
                    releaseDecoder = NativeAudio::nativeCloseStreamDecoder,
                )
            } catch (error: Throwable) {
                if (sessionHandle != 0L) runCatching { NativeAudio.nativeClosePlaybackSession(sessionHandle) }
                if (decoderHandle != 0L) runCatching { NativeAudio.nativeCloseStreamDecoder(decoderHandle) }
                runCatching { source.close() }
                throw error
            }
        }
    }
}
