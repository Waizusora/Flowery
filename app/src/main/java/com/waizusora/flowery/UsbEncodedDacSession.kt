package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection

/**
 * Owns a dedicated compressed-stream libusb session.
 *
 * Encoded bytes are submitted only through nativeWriteEncodedPacket; this
 * session never calls the PCM writer. The caller supplies the average encoded
 * transport rate in bytes per second, derived from the negotiated UAC profile.
 */
internal class UsbEncodedDacSession private constructor(
    private val connection: UsbDeviceConnection,
    private val nativeHandle: Long
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Synchronized
    fun writePacket(packet: ByteArray, durationUs: Long): Int {
        check(!closed) { "Encoded USB session is closed" }
        require(packet.isNotEmpty()) { "Encoded packet must not be empty" }
        return UsbDacNative.nativeWriteEncodedPacket(nativeHandle, packet, durationUs)
    }

    /** Waits until every staged encoded service interval has completed. */
    @Synchronized
    fun drain() {
        check(!closed) { "Encoded USB session is closed" }
        check(UsbDacNative.nativeDrain(nativeHandle) == 0) {
            "Encoded USB transport drain failed"
        }
    }

    /** Cancels queued USB transfers and drops staged encoded data for a seek. */
    @Synchronized
    fun flush() {
        check(!closed) { "Encoded USB session is closed" }
        UsbDacNative.nativeFlush(nativeHandle)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            UsbDacNative.nativeClose(nativeHandle)
        } finally {
            connection.close()
        }
    }

    companion object {
        fun open(
            connection: UsbDeviceConnection,
            interfaceNumber: Int,
            alternateSetting: Int,
            endpoint: Int,
            maxPacketBytes: Int,
            interval: Int,
            framingKind: Int,
            encodedBytesPerSecond: Long,
            encodedFrameRate: Int,
            encodedFrameBytes: Int,
            sourceSampleRate: Int,
            samplingFrequencyControl: Boolean,
            feedbackEndpoint: Int,
            feedbackMaxPacketBytes: Int
        ): UsbEncodedDacSession {
            require(interfaceNumber >= 0 && alternateSetting > 0)
            require(endpoint in 1..0x7f)
            require(maxPacketBytes > 0 && interval in 1..16)
            require(encodedBytesPerSecond > 0)
            val handle = UsbDacNative.nativeOpenEncoded(
                connection.fileDescriptor,
                interfaceNumber,
                alternateSetting,
                endpoint,
                maxPacketBytes,
                interval,
                framingKind,
                encodedBytesPerSecond,
                encodedFrameRate,
                encodedFrameBytes,
                sourceSampleRate,
                samplingFrequencyControl,
                feedbackEndpoint,
                feedbackMaxPacketBytes
            )
            check(handle != 0L) { "Unable to open encoded USB transport" }
            return UsbEncodedDacSession(connection, handle)
        }
    }
}
