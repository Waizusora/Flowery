package com.waizusora.flowery

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class UsbPcmBlock(
    val endOfStream: Boolean,
    val epoch: Int,
    val frames: Int,
    val startFrames: Long,
    val pcm: ByteArray,
)

internal object UsbPcmBlockDecoder {
    fun decode(encoded: ByteArray): UsbPcmBlock {
        // Keep this layout in sync with native/src/usb_pcm.rs: flag + epoch + frames + start frame.
        require(encoded.size >= 17) { "Rust USB PCM block header is truncated." }
        val header = ByteBuffer.wrap(encoded, 0, 17).order(ByteOrder.LITTLE_ENDIAN)
        val endOfStream = header.get().toInt() != 0
        val epoch = header.int
        val frames = header.int
        val startFrames = header.long
        require(frames >= 0 && startFrames >= 0L) { "Rust USB PCM block contains invalid frame metadata." }
        val payload = encoded.copyOfRange(17, encoded.size)
        require(!endOfStream || (frames == 0 && payload.isEmpty())) {
            "Rust USB PCM end-of-stream block must not contain audio payload."
        }
        require(endOfStream || frames > 0) { "Rust USB PCM data block must contain at least one frame." }
        return UsbPcmBlock(endOfStream, epoch, frames, startFrames, payload)
    }
}

internal data class UsbPcmWriteResult(
    val endOfStream: Boolean,
    val epochChanged: Boolean,
    val epoch: Int,
    val startFrames: Long,
    val frames: Int,
    val acceptedFrames: Int,
    val pcmBytes: Int,
    val queuedBlocks: Int,
    val queueWaitMs: Double,
    val transportDurationMs: Double,
)

internal data class UsbPcmPlaybackSummary(
    val chunks: Long,
    val totalFramesSent: Long,
    val starvationEvents: Long,
)

/**
 * Owns the Rust PCM producer/queue and the Android-to-libusb PCM writer boundary.
 *
 * Rust remains responsible for decoding, DSP, packing, bounded queue policy, and
 * producer scheduling. This adapter only polls complete blocks and submits them
 * to the already-open USB transport session.
 */
internal class UsbPcmPlaybackPipeline private constructor(
    private val queueHandle: Long,
    val producerHandle: Long,
    val dacSession: UsbDacSession,
    private val playbackSessionHandle: Long,
) : AutoCloseable {
    private var closed = false
    private var activeEpoch = 0

    val queueSize: Int
        get() = NativeAudio.nativeUsbPcmQueueSize(queueHandle)

    val workerError: String
        get() = NativeAudio.nativeUsbPcmProducerWorkerError(producerHandle)

    val isAtEnd: Boolean
        get() = NativeAudio.nativeIsUsbPcmProducerAtEnd(producerHandle)

    val isBitPerfect: Boolean
        get() = NativeAudio.nativeIsUsbPcmProducerBitPerfect(producerHandle)

    fun pollAndWrite(timeoutMs: Int): UsbPcmWriteResult? {
        check(!closed) { "USB PCM playback pipeline is closed" }
        val pollStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val encoded = NativeAudio.nativePollUsbPcmBlock(queueHandle, timeoutMs) ?: return null
        val queueWaitMs = (android.os.SystemClock.elapsedRealtimeNanos() - pollStartedNs).coerceAtLeast(0L) / 1_000_000.0
        val block = UsbPcmBlockDecoder.decode(encoded)
        val requestedEpoch = NativeAudio.nativePlaybackSessionEpoch(playbackSessionHandle).toInt()
        if (requestedEpoch >= 0 && block.epoch != requestedEpoch) {
            dacSession.flush()
            activeEpoch = requestedEpoch
            return null
        }

        var epochChanged = false
        if (block.epoch != activeEpoch) {
            // A seek happened: discard audio that is still queued on the endpoint.
            dacSession.flush()
            activeEpoch = block.epoch
            epochChanged = true
        }
        if (block.endOfStream) {
            return UsbPcmWriteResult(
                endOfStream = true,
                epochChanged = epochChanged,
                epoch = block.epoch,
                startFrames = block.startFrames,
                frames = 0,
                acceptedFrames = 0,
                pcmBytes = 0,
                queuedBlocks = queueSize,
                queueWaitMs = queueWaitMs,
                transportDurationMs = 0.0,
            )
        }

        val writeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val acceptedFrames = dacSession.writePcm(block.pcm, block.frames)
        val transportDurationMs = (android.os.SystemClock.elapsedRealtimeNanos() - writeStartedNs)
            .coerceAtLeast(0L) / 1_000_000.0
        return UsbPcmWriteResult(
            endOfStream = false,
            epochChanged = epochChanged,
            epoch = block.epoch,
            startFrames = block.startFrames,
            frames = block.frames,
            acceptedFrames = acceptedFrames,
            pcmBytes = block.pcm.size,
            queuedBlocks = queueSize,
            queueWaitMs = queueWaitMs,
            transportDurationMs = transportDurationMs,
        )
    }

    /**
     * Owns prebuffering and the direct PCM submission loop. Callbacks are
     * notifications only; queue polling, pause handling, timing, and drain
     * decisions remain inside this Android transport adapter.
     */
    fun playUntil(
        playbackActive: () -> Boolean,
        isPaused: () -> Boolean,
        onPrebufferReady: (queuedBlocks: Int, endReached: Boolean, waitMs: Double) -> Unit,
        onFirstTransfer: (frames: Int) -> Unit,
        onStarvation: (queueWaitMs: Double, events: Long) -> Unit,
        onWrite: (result: UsbPcmWriteResult, chunk: Long, totalFrames: Long, starvationEvents: Long, throughputRatio: Double, audioDurationMs: Double) -> Unit,
        onBitPerfectChanged: (bitPerfect: Boolean) -> Unit,
        onHardwareVolume: (volume: Float) -> Unit,
        onEndOfStream: (chunks: Long, totalFrames: Long) -> Unit,
    ): UsbPcmPlaybackSummary {
        val prebufferStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        while (playbackActive() && workerError.isBlank() &&
            !isAtEnd && queueSize < PREBUFFER_BLOCKS) {
            Thread.sleep(5)
        }
        onPrebufferReady(
            queueSize,
            isAtEnd,
            (android.os.SystemClock.elapsedRealtimeNanos() - prebufferStartedNs)
                .coerceAtLeast(0L) / 1_000_000.0,
        )

        var firstTransfer = true
        var lastHardwareVolumePollNs = 0L
        var starvationEvents = 0L
        var chunks = 0L
        var totalFramesSent = 0L
        var positionFrames = 0L
        var lastBitPerfect: Boolean? = null

        while (playbackActive()) {
            if (isPaused()) {
                Thread.sleep(10)
                continue
            }
            val producerFailure = workerError
            check(producerFailure.isBlank()) {
                "Rust USB PCM producer worker failed: $producerFailure"
            }
            val result = pollAndWrite(QUEUE_POLL_MS.toInt()) ?: continue
            if (!firstTransfer && result.queueWaitMs > USB_STARVATION_REPORT_MS) {
                starvationEvents++
                onStarvation(result.queueWaitMs, starvationEvents)
            }
            if (result.epochChanged) positionFrames = result.startFrames
            if (result.endOfStream) {
                onEndOfStream(chunks, totalFramesSent)
                break
            }

            if (firstTransfer) onFirstTransfer(result.frames)
            val audioDurationMs = result.frames * 1_000.0 / dacSession.sampleRate
            val throughputRatio = if (result.transportDurationMs > 0.0) {
                result.frames.toDouble() /
                    (dacSession.sampleRate * result.transportDurationMs / 1_000.0)
            } else Double.POSITIVE_INFINITY
            check(result.acceptedFrames == result.frames) {
                "The USB DAC transport accepted ${result.acceptedFrames} of ${result.frames} frames."
            }
            onWrite(result, chunks + 1L, totalFramesSent + result.acceptedFrames, starvationEvents, throughputRatio, audioDurationMs)

            val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
            if (dacSession.hardwareVolumeAvailable &&
                nowNs - lastHardwareVolumePollNs >= 500_000_000L) {
                dacSession.readHardwareVolumeFraction()?.let(onHardwareVolume)
                lastHardwareVolumePollNs = nowNs
            }

            chunks++
            totalFramesSent += result.acceptedFrames
            positionFrames += result.frames
            val currentBitPerfect = isBitPerfect
            if (firstTransfer || currentBitPerfect != lastBitPerfect) {
                firstTransfer = false
                lastBitPerfect = currentBitPerfect
                onBitPerfectChanged(currentBitPerfect)
            }
        }

        val producerFailure = workerError
        check(producerFailure.isBlank() || !playbackActive()) {
            "Rust USB PCM producer worker failed: $producerFailure"
        }
        if (playbackActive()) dacSession.drain()
        return UsbPcmPlaybackSummary(chunks, totalFramesSent, starvationEvents)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeAudio.nativeStopUsbPcmProducerWorker(producerHandle)
        } finally {
            try {
                NativeAudio.nativeCloseUsbPcmQueue(queueHandle)
            } finally {
                try {
                    NativeAudio.nativeDestroyUsbPcmProducer(producerHandle)
                } finally {
                    try {
                        NativeAudio.nativeDestroyUsbPcmQueue(queueHandle)
                    } finally {
                        dacSession.close()
                    }
                }
            }
        }
    }

    companion object {
        const val QUEUE_DEPTH_BLOCKS = 6
        const val PREBUFFER_BLOCKS = 3
        const val QUEUE_POLL_MS = 50L
        private const val USB_STARVATION_REPORT_MS = 5.0

        fun open(
            decoderHandle: Long,
            playbackSessionHandle: Long,
            dacSession: UsbDacSession,
            channels: Int,
            segmentStartMs: Long,
            segmentEndMs: Long?,
            initialGain: Float,
            encoding: Int,
        ): UsbPcmPlaybackPipeline {
            require(decoderHandle != 0L && playbackSessionHandle != 0L)
            val queue = NativeAudio.nativeCreateUsbPcmQueue(QUEUE_DEPTH_BLOCKS)
            check(queue != 0L) { "Unable to create the native USB PCM queue." }
            var producer = 0L
            try {
                producer = NativeAudio.nativeCreateUsbPcmProducer(
                    decoderHandle,
                    queue,
                    playbackSessionHandle,
                    dacSession.sampleRate,
                    channels,
                    dacSession.profile.bitResolution,
                    dacSession.profile.subslotBytes,
                    segmentStartMs,
                    segmentEndMs ?: -1L,
                    encoding,
                )
                check(producer != 0L) { "Unable to create the Rust direct USB producer." }
                check(NativeAudio.nativeSetUsbPcmProducerGain(producer, initialGain) == 0) {
                    "Unable to configure the initial Rust USB PCM producer gain."
                }
                check(NativeAudio.nativeStartUsbPcmProducerWorker(producer) == 0) {
                    "Unable to start the Rust direct USB producer worker."
                }
                return UsbPcmPlaybackPipeline(queue, producer, dacSession, playbackSessionHandle)
            } catch (error: Throwable) {
                if (producer != 0L) {
                    runCatching { NativeAudio.nativeStopUsbPcmProducerWorker(producer) }
                    runCatching { NativeAudio.nativeDestroyUsbPcmProducer(producer) }
                }
                runCatching { NativeAudio.nativeCloseUsbPcmQueue(queue) }
                runCatching { NativeAudio.nativeDestroyUsbPcmQueue(queue) }
                throw error
            }
        }
    }
}
