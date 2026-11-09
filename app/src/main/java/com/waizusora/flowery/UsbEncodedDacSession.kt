package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection
import java.util.Collections
import java.util.IdentityHashMap

internal const val USB_ENCODED_WRITE_STOPPED = -2

/**
 * Owns a dedicated compressed-stream libusb session.
 *
 * Encoded bytes are submitted only through nativeWriteEncodedPacket; this
 * session never calls the PCM writer. The caller supplies the average encoded
 * transport rate in bytes per second, derived from the negotiated UAC profile.
 */
class UsbEncodedDacSession private constructor(
    private val connection: UsbDeviceConnection,
    private val nativeHandle: Long
) : AutoCloseable {
    @Volatile
    private var closed = false

    @Synchronized
    fun writePacket(packet: ByteArray, payloadOffset: Int, payloadLength: Int, durationUs: Long): Int {
        check(!closed) { "Encoded USB session is closed" }
        require(payloadOffset >= 0 && payloadLength > 0 && payloadOffset <= packet.size &&
            payloadLength <= packet.size - payloadOffset) { "Encoded payload range is invalid" }
        return UsbDacNative.nativeWriteEncodedPacket(
            nativeHandle, packet, payloadOffset, payloadLength, durationUs
        )
    }

    // Deliberately unsynchronized so control requests can wake a JNI writer.
    fun requestPause(): Boolean = !closed && UsbDacNative.nativeRequestPause(nativeHandle)
    fun requestResume(): Boolean = !closed && UsbDacNative.nativeRequestResume(nativeHandle)
    fun requestStop(): Boolean = !closed && UsbDacNative.nativeRequestStop(nativeHandle)

    /** Waits until every staged encoded service interval has completed. */
    @Synchronized
    fun drain() {
        check(!closed) { "Encoded USB session is closed" }
        val result = UsbDacNative.nativeDrain(nativeHandle)
        check(result == 0 || result == USB_ENCODED_WRITE_STOPPED) {
            "Encoded USB transport drain failed"
        }
    }

    /** Cancels queued USB transfers and drops staged encoded data for a seek. */
    @Synchronized
    fun flush() {
        check(!closed) { "Encoded USB session is closed" }
        check(UsbDacNative.nativeFlush(nativeHandle) == 0) {
            "Encoded USB transport could not cancel and reap all in-flight transfers for seeking"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        requestStop()
        for (attempt in 0 until 3) {
            if (UsbDacNative.nativeClose(nativeHandle)) {
                try {
                    connection.close()
                    closed = true
                    forgetPendingClose()
                    return
                } catch (error: Throwable) {
                    // The native session is already gone, but retain the Android
                    // connection so a later retry can complete its release.
                    rememberPendingClose()
                    throw error
                }
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(50L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting for encoded USB transfers to finish teardown", interrupted)
                }
            }
        }
        rememberPendingClose()
        throw IllegalStateException(
            "libusb could not confirm that all encoded transfers were reaped; the USB connection remains open for a safe retry"
        )
    }

    private fun rememberPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.add(this) }
    }

    private fun forgetPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.remove(this) }
    }

    companion object {
        private val pendingCloseLock = Any()
        private val pendingCloseSessions: MutableSet<UsbEncodedDacSession> =
            Collections.newSetFromMap(IdentityHashMap())

        /**
         * Retries sessions retained after an earlier close could not confirm
         * native transfer reclamation or Android connection release.
         */
        internal fun retryPendingCloseSessions() {
            val pending = synchronized(pendingCloseLock) { pendingCloseSessions.toList() }
            pending.forEach { session -> runCatching { session.close() } }
        }

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
