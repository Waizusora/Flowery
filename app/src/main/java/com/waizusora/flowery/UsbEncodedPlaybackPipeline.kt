package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Connects the Rust packet producer to the dedicated encoded libusb writer.
 *
 * The 27-byte JNI envelope is transport metadata only. Only the payload after
 * the envelope is written to the encoded endpoint. The PCM JNI writer is never
 * called by this pipeline.
 */
internal class UsbEncodedPlaybackPipeline private constructor(
    private val decoderHandle: Long,
    private val queueHandle: Long,
    private val producerHandle: Long,
    private val session: UsbEncodedDacSession,
    private val framingKind: Int,
    private val formatCode: Int
) : AutoCloseable {
    private var ended = false
    private var closed = false
    private var expectedSequence = 0L
    @Volatile private var lastPositionMs = 0L

    init {
        check(NativeAudio.nativeStartUsbEncodedProducerWorker(producerHandle) == 0) {
            "Unable to start the Rust-owned encoded packet producer"
        }
    }

    val isEnded: Boolean get() = ended
    fun positionMs(): Long = lastPositionMs

    /**
     * Pumps source frames into the bounded Rust queue and submits available
     * complete packets to the encoded endpoint. Returns the number submitted.
     */
    @Synchronized
    fun pump(maxPackets: Int = 32): Int {
        check(!closed) { "Encoded playback pipeline is closed" }
        require(maxPackets > 0)
        when (NativeAudio.nativeGetUsbEncodedProducerWorkerStatus(producerHandle)) {
            0, 1 -> Unit
            2 -> throw IllegalStateException("Rust encoded packet producer was cancelled unexpectedly")
            else -> throw IllegalStateException("Rust encoded packet producer failed")
        }

        var submitted = 0
        while (submitted < maxPackets) {
            val envelope = NativeAudio.nativePollUsbEncodedPacket(queueHandle, 0) ?: break
            require(envelope.size >= HEADER_BYTES) { "Invalid encoded packet envelope" }
            val header = ByteBuffer.wrap(envelope, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val kind = header.get().toInt() and 0xff
            val code = header.short.toInt() and 0xffff
            val sequence = header.long
            val presentationTimeUs = header.long // presentation timestamp in microseconds
            if (presentationTimeUs > 0L) lastPositionMs = presentationTimeUs / 1000L
            val durationUs = header.long // packet/burst duration in microseconds
            check(kind == framingKind && code == formatCode) {
                "Encoded packet framing does not match the opened USB profile"
            }
            check(sequence == expectedSequence) { "Encoded packet sequence discontinuity" }
            expectedSequence++
            val payloadLength = envelope.size - HEADER_BYTES
            check(payloadLength > 0) { "Encoded packet payload is empty" }
            check(session.writePacket(envelope, HEADER_BYTES, payloadLength, durationUs) == payloadLength) {
                "Encoded USB transport did not accept the complete packet"
            }
            submitted++
        }
        if (submitted < maxPackets &&
            NativeAudio.nativeGetUsbEncodedProducerWorkerStatus(producerHandle) == 1) {
            ended = true
        }
        return submitted
    }

    /** Seeks the demuxer to a packet boundary and flushes old queued USB data. */
    @Synchronized
    fun seekTo(positionMs: Long): Long {
        check(!closed) { "Encoded playback pipeline is closed" }
        require(positionMs >= 0)
        NativeAudio.nativeStopUsbEncodedProducerWorker(producerHandle)
        session.flush()
        val actual = NativeAudio.nativeSeekUsbEncodedProducer(producerHandle, positionMs)
        check(actual >= 0) { "Encoded stream seek failed" }
        ended = false
        lastPositionMs = actual
        check(NativeAudio.nativeStartUsbEncodedProducerWorker(producerHandle) == 0) {
            "Unable to restart the Rust-owned encoded packet producer after seeking"
        }
        return actual
    }

    /** Waits for currently submitted encoded data without advancing the source decoder. */
    @Synchronized
    fun drainTransport() {
        check(!closed) { "Encoded playback pipeline is closed" }
        session.drain()
    }

    /** Pumps remaining source packets, then waits for the encoded endpoint to drain. */
    @Synchronized
    fun drain() {
        check(!closed) { "Encoded playback pipeline is closed" }
        while (!ended) {
            val submitted = pump()
            if (submitted == 0 && !ended) Thread.yield()
        }
        drainTransport()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeAudio.nativeStopUsbEncodedProducerWorker(producerHandle)
            NativeAudio.nativeDestroyUsbEncodedProducer(producerHandle)
            NativeAudio.nativeCloseUsbEncodedQueue(queueHandle)
            NativeAudio.nativeDestroyUsbEncodedQueue(queueHandle)
        } finally {
            session.close()
        }
    }

    companion object {
        private const val HEADER_BYTES = 27
        const val FRAMING_UAC1_TYPE_II = 1
        const val FRAMING_IEC61937_TYPE_III = 2
        const val UAC1_FORMAT_TYPE_II = 2
        const val UAC1_FORMAT_TYPE_III = 3
        const val UAC1_FORMAT_TAG_AC3 = 0x1002
        const val UAC1_FORMAT_TAG_IEC61937 = 0x2001
        const val IEC61937_DATATYPE_AC3 = 0x0001

        /**
         * Opens only after exact descriptor validation. Type-II currently
         * accepts AC-3 (wFormatTag 0x1002); Type-III accepts AC-3 IEC 61937
         * (data type 0x0001). Caller owns decoderHandle.
         */
        fun open(
            connection: UsbDeviceConnection,
            decoderHandle: Long,
            sessionHandle: Long,
            descriptors: ByteArray,
            interfaceNumber: Int,
            alternateSetting: Int,
            formatType: Int,
            formatTag: Int,
            framingKind: Int,
            formatCode: Int,
            queuePackets: Int = 32,
            queueBytes: Int = 256 * 1024
        ): UsbEncodedPlaybackPipeline {
            require(decoderHandle != 0L && sessionHandle != 0L)
            require(queuePackets > 0 && queueBytes >= 6144) {
                "Encoded queue must hold at least one complete IEC 61937 AC-3 burst"
            }
            val streamInfo = NativeAudio.nativeGetStreamInfo(decoderHandle)
            val sourceRate = (streamInfo ushr 32).toInt()
            require(sourceRate > 0) { "Source sample rate is unavailable" }
            val validation = JSONObject(
                NativeAudio.nativeValidateUsbEncodedDescriptor(
                    descriptors, interfaceNumber, alternateSetting, formatType, formatTag, sourceRate
                )
            )
            check(validation.optBoolean("valid")) {
                validation.optString("reason", "UAC1 encoded descriptor validation failed")
            }
            require(
                (framingKind == FRAMING_UAC1_TYPE_II &&
                    formatType == UAC1_FORMAT_TYPE_II &&
                    formatTag == UAC1_FORMAT_TAG_AC3 &&
                    formatCode == UAC1_FORMAT_TAG_AC3) ||
                (framingKind == FRAMING_IEC61937_TYPE_III &&
                    formatType == UAC1_FORMAT_TYPE_III &&
                    formatTag == UAC1_FORMAT_TAG_IEC61937 &&
                    formatCode == IEC61937_DATATYPE_AC3)
            ) { "Unsupported encoded codec/descriptor combination" }

            val queue = NativeAudio.nativeCreateUsbEncodedQueue(queuePackets, queueBytes)
            check(queue != 0L) { "Unable to create bounded encoded packet queue" }
            var producer = 0L
            var session: UsbEncodedDacSession? = null
            try {
                producer = NativeAudio.nativeCreateUsbEncodedProducer(
                    decoderHandle, queue, sessionHandle, framingKind, formatCode,
                    if (framingKind == FRAMING_UAC1_TYPE_II) validation.getInt("maximumBitrate") else 0
                )
                check(producer != 0L) { "Source codec is incompatible with the selected encoded USB format" }
                val maximumBitrateKbps = validation.optInt("maximumBitrate")
                val encodedBytesPerSecond = runCatching {
                    NativeAudio.nativeGetUsbEncodedTransportRate(decoderHandle, framingKind)
                }.getOrElse { error ->
                    if (framingKind == FRAMING_UAC1_TYPE_II && maximumBitrateKbps > 0) {
                        // If container metadata omits AC-3 bitrate, pace capacity checks
                        // against the descriptor's advertised maximum, not a guess.
                        maximumBitrateKbps.toLong() * 125L
                    } else {
                        throw error
                    }
                }
                check(encodedBytesPerSecond > 0L) { "Unable to derive a safe encoded transport rate" }
                if (framingKind == FRAMING_UAC1_TYPE_II) {
                    val sourceBitrateKbps = (encodedBytesPerSecond * 8L + 999L) / 1000L
                    check(maximumBitrateKbps > 0 && sourceBitrateKbps <= maximumBitrateKbps) {
                        "AC-3 source bitrate exceeds the selected UAC1 Type-II descriptor limit"
                    }
                }
                session = UsbEncodedDacSession.open(
                    connection,
                    interfaceNumber,
                    alternateSetting,
                    validation.getInt("endpoint"),
                    validation.getInt("maxPacketBytes"),
                    validation.getInt("interval"),
                    framingKind,
                    encodedBytesPerSecond,
                    if (framingKind == FRAMING_IEC61937_TYPE_III) sourceRate else 0,
                    if (framingKind == FRAMING_IEC61937_TYPE_III) 4 else 0,
                    sourceRate,
                    validation.optBoolean("samplingFrequencyControl"),
                    validation.optInt("feedbackEndpoint"),
                    validation.optInt("feedbackMaxPacketBytes")
                )
                return UsbEncodedPlaybackPipeline(
                    decoderHandle, queue, producer, session, framingKind, formatCode
                )
            } catch (error: Throwable) {
                if (producer != 0L) NativeAudio.nativeDestroyUsbEncodedProducer(producer)
                NativeAudio.nativeCloseUsbEncodedQueue(queue)
                NativeAudio.nativeDestroyUsbEncodedQueue(queue)
                session?.close() ?: connection.close()
                throw error
            }
        }
    }
}
