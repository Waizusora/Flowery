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

    /**
     * Pumps source frames into the bounded Rust queue and submits available
     * complete packets to the encoded endpoint. Returns the number submitted.
     */
    @Synchronized
    fun pump(maxPackets: Int = 32): Int {
        check(!closed) { "Encoded playback pipeline is closed" }
        require(maxPackets > 0)
        repeat(maxPackets) {
            when (NativeAudio.nativePumpUsbEncodedProducer(producerHandle)) {
                1 -> Unit
                0 -> return@repeat
                -1 -> { ended = true; return@repeat }
                else -> throw IllegalStateException("Rust encoded packet producer failed")
            }
        }

        var submitted = 0
        while (true) {
            val envelope = NativeAudio.nativePollUsbEncodedPacket(queueHandle, 0) ?: break
            require(envelope.size >= HEADER_BYTES) { "Invalid encoded packet envelope" }
            val header = ByteBuffer.wrap(envelope, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val kind = header.get().toInt() and 0xff
            val code = header.short.toInt() and 0xffff
            val sequence = header.long
            header.long // presentation timestamp in microseconds
            header.long // packet/burst duration in microseconds
            check(kind == framingKind && code == formatCode) {
                "Encoded packet framing does not match the opened USB profile"
            }
            check(sequence == expectedSequence) { "Encoded packet sequence discontinuity" }
            expectedSequence++
            val payload = envelope.copyOfRange(HEADER_BYTES, envelope.size)
            check(payload.isNotEmpty()) { "Encoded packet payload is empty" }
            check(session.writePacket(payload) == payload.size) {
                "Encoded USB transport did not accept the complete packet"
            }
            submitted++
        }
        return submitted
    }

    /** Seeks the demuxer to a packet boundary and flushes old queued USB data. */
    @Synchronized
    fun seekTo(positionMs: Long): Long {
        check(!closed) { "Encoded playback pipeline is closed" }
        require(positionMs >= 0)
        session.flush()
        val actual = NativeAudio.nativeSeekUsbEncodedProducer(producerHandle, positionMs)
        check(actual >= 0) { "Encoded stream seek failed" }
        expectedSequence = 0L
        ended = false
        return actual
    }

    /** Pumps remaining source packets, then waits for the encoded endpoint to drain. */
    @Synchronized
    fun drain() {
        check(!closed) { "Encoded playback pipeline is closed" }
        while (!ended) {
            val submitted = pump()
            if (submitted == 0 && !ended) Thread.yield()
        }
        while (NativeAudio.nativePollUsbEncodedPacket(queueHandle, 0) != null) {
            throw IllegalStateException("Encoded queue changed while draining; call pump() before drain()")
        }
        session.drain()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
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
        const val IEC61937_DATATYPE_AC3 = 0x0001

        /**
         * Opens only after exact descriptor validation. Type-II currently
         * accepts AC-3 (wFormatTag 0x1002); Type-III accepts AC-3 IEC 61937
         * (data type 0x0001). Caller owns decoderHandle.
         */
        fun open(
            connection: UsbDeviceConnection,
            decoderHandle: Long,
            descriptors: ByteArray,
            interfaceNumber: Int,
            alternateSetting: Int,
            formatType: Int,
            formatTag: Int,
            framingKind: Int,
            formatCode: Int,
            encodedBytesPerSecond: Long,
            queuePackets: Int = 32,
            queueBytes: Int = 256 * 1024
        ): UsbEncodedPlaybackPipeline {
            require(decoderHandle != 0L)
            val validation = JSONObject(
                NativeAudio.nativeValidateUsbEncodedDescriptor(
                    descriptors, interfaceNumber, alternateSetting, formatType, formatTag
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
                    formatCode == IEC61937_DATATYPE_AC3)
            ) { "Unsupported encoded codec/descriptor combination" }

            val queue = NativeAudio.nativeCreateUsbEncodedQueue(queuePackets, queueBytes)
            check(queue != 0L) { "Unable to create bounded encoded packet queue" }
            var producer = 0L
            var session: UsbEncodedDacSession? = null
            try {
                producer = NativeAudio.nativeCreateUsbEncodedProducer(
                    decoderHandle, queue, framingKind, formatCode
                )
                check(producer != 0L) { "Source codec is incompatible with the selected encoded USB format" }
                session = UsbEncodedDacSession.open(
                    connection,
                    interfaceNumber,
                    alternateSetting,
                    validation.getInt("endpoint"),
                    validation.getInt("maxPacketBytes"),
                    validation.getInt("interval"),
                    encodedBytesPerSecond
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
