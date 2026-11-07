package com.waizusora.flowery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Kotlin worker adapter for the Rust-owned bounded USB PCM producer.
 *
 * Rust owns decoding, seeking, DSP, packing, epochs and queue publication. This
 * adapter only forwards cancellation/seek/gain state and reports producer errors.
 */
internal class UsbPcmProducerAdapter(
    private val decoderHandle: Long,
    private val producerHandle: Long,
    private val pendingSeekMs: AtomicReference<Long?>,
    private val playbackActive: () -> Boolean,
    private val equalizerRevision: () -> Int,
    private val equalizerGains: () -> FloatArray,
    private val currentGain: () -> Float,
    private val diagnostics: UsbPlaybackDiagnostics
) {
    fun run(
        endReached: AtomicBoolean,
        stop: AtomicBoolean,
        failure: AtomicReference<Throwable?>
    ) {
        fun active() = !stop.get() && playbackActive()
        try {
            var appliedEqualizerRevision = equalizerRevision()
            while (active()) {
                pendingSeekMs.getAndSet(null)?.let { requested ->
                    NativeAudio.nativeRequestUsbPcmProducerSeek(producerHandle, requested.coerceAtLeast(0L))
                    endReached.set(false)
                }
                if (appliedEqualizerRevision != equalizerRevision()) {
                    check(NativeAudio.nativeUpdateStreamGains(decoderHandle, equalizerGains()) == 0) {
                        "Unable to update the direct USB equalizer"
                    }
                    appliedEqualizerRevision = equalizerRevision()
                }
                when (NativeAudio.nativePumpUsbPcmProducer(producerHandle, currentGain())) {
                    1 -> Unit
                    0 -> Thread.sleep(2)
                    -1 -> {
                        endReached.set(true)
                        Thread.sleep(20)
                    }
                    else -> throw IllegalStateException("The Rust direct USB producer failed.")
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (active()) {
                diagnostics.record(
                    "USB_PRODUCER_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}"
                )
                failure.compareAndSet(null, error)
            }
        }
    }

    companion object {
        fun decodeBlock(encoded: ByteArray): UsbPcmBlock {
            require(encoded.size >= 17) { "The native USB PCM queue returned an invalid block header." }
            val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
            val endOfStream = buffer.get().toInt() != 0
            val epoch = buffer.int
            val frames = buffer.int
            val startFrames = buffer.long
            val pcm = ByteArray(buffer.remaining())
            buffer.get(pcm)
            require(frames >= 0 && (endOfStream || frames > 0)) {
                "The native USB PCM queue returned invalid frame metadata."
            }
            return UsbPcmBlock(pcm, frames, epoch, startFrames, endOfStream)
        }
    }
}

internal data class UsbPcmBlock(
    val pcm: ByteArray,
    val frames: Int,
    val epoch: Int,
    val startFrames: Long,
    val endOfStream: Boolean = false
)
