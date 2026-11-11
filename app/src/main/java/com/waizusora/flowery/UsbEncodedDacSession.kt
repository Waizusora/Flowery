package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val nativeHandle: Long,
    private val volumeUnit: UsbFeatureUnit?
) : AutoCloseable {
    private var cachedVolumeRange: Pair<Int, Int>? = null
    private var volumeRangeLoaded = false

    private fun volumeControl(request: Int, data: ByteArray): Int {
        val unit = volumeUnit ?: return -1
        val value = 0x0200
        val index = (unit.unitId shl 8) or unit.controlInterface
        val requestType = if (request == 0x01) 0x21 else 0xA1
        return connection.controlTransfer(requestType, request, value, index, data, data.size, 500)
    }

    private fun volumeRange(): Pair<Int, Int>? {
        if (volumeRangeLoaded) return cachedVolumeRange
        val unit = volumeUnit ?: return null
        val minimum = ByteArray(2)
        val maximum = ByteArray(2)
        val parsed = if (unit.protocol == 0x20) {
            val response = ByteArray(2 + 6 * 16)
            UsbFeatureUnitVolumeRange.parseUac2(response, volumeControl(0x82, response))
        } else if (volumeControl(0x82, minimum) == 2 && volumeControl(0x83, maximum) == 2) {
            UsbFeatureUnitVolumeRange.parseUac1(minimum, maximum)
        } else null
        volumeRangeLoaded = true
        cachedVolumeRange = parsed
        return parsed
    }

    @Synchronized
    fun readHardwareVolumeFraction(): Float? {
        check(!closed) { "Encoded USB session is closed" }
        val range = volumeRange() ?: return null
        val current = ByteArray(2)
        if (volumeControl(0x81, current) != 2) return null
        val raw = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        if (raw !in range.first..range.second) return null
        return ((raw - range.first).toFloat() /
            (range.second - range.first).toFloat()).coerceIn(0f, 1f)
    }

    /** Applies an encoded-route hardware level and verifies it with GET_CUR. */
    @Synchronized
    fun setHardwareVolumeFraction(fraction: Float): Float? {
        check(!closed) { "Encoded USB session is closed" }
        val range = volumeRange() ?: return null
        val requestedRaw = (range.first +
            (range.second - range.first) * fraction.coerceIn(0f, 1f)).toInt()
        val requested = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(requestedRaw.toShort()).array()
        val setAccepted = volumeControl(0x01, requested) == 2
        val current = ByteArray(2)
        val transferred = if (setAccepted) volumeControl(0x81, current) else -1
        if (transferred != 2) {
            if (!armSafeStartupVolume()) requestStop()
            return null
        }
        val readBackRaw = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        return if (readBackRaw in range.first..range.second) {
            ((readBackRaw - range.first).toFloat() /
                (range.second - range.first).toFloat()).coerceIn(0f, 1f)
        } else {
            if (!armSafeStartupVolume()) requestStop()
            null
        }
    }

    /** Must complete before the encoded producer worker can submit its first packet. */
    @Synchronized
    fun armSafeStartupVolume(): Boolean {
        check(!closed) { "Encoded USB session is closed" }
        val range = volumeRange() ?: return false
        val targetRaw = UsbStartupVolumePolicy.targetRaw(range.first, range.second) ?: return false
        val requested = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(targetRaw.toShort()).array()
        if (volumeControl(0x01, requested) != 2) return false
        val current = ByteArray(2)
        if (volumeControl(0x81, current) != 2) return false
        val readBackRaw = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        return readBackRaw in range.first..range.second &&
            UsbStartupVolumePolicy.confirmsHardwareStartup(true, targetRaw, readBackRaw)
    }
    @Volatile
    private var nativeClosed = false

    @Volatile
    private var connectionClosed = false

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
    fun requestPause(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestPause(nativeHandle)
    fun requestResume(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestResume(nativeHandle)
    fun requestStop(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestStop(nativeHandle)

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

        // Native and Android ownership have separate completion points. Once
        // nativeClose succeeds, never call it again with the now-stale handle,
        // even if releasing the Android connection subsequently fails.
        if (!nativeClosed) {
            requestStop()
            var nativeCloseConfirmed = false
            for (attempt in 0 until 3) {
                if (UsbDacNative.nativeClose(nativeHandle)) {
                    nativeClosed = true
                    nativeCloseConfirmed = true
                    break
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(50L)
                    } catch (interrupted: InterruptedException) {
                        rememberPendingClose()
                        Thread.currentThread().interrupt()
                        throw IllegalStateException(
                            "Interrupted while waiting for encoded USB transfers to finish teardown",
                            interrupted
                        )
                    }
                }
            }
            if (!nativeCloseConfirmed) {
                rememberPendingClose()
                throw IllegalStateException(
                    "libusb could not confirm that all encoded transfers were reaped; " +
                        "the USB connection remains open for a safe retry"
                )
            }
        }

        if (!connectionClosed) try {
            connection.close()
            connectionClosed = true
        } catch (error: Throwable) {
            // Native ownership is already released. Keep the session object so
            // a later retry releases only the Android connection, not the stale
            // native handle.
            rememberPendingClose()
            throw error
        }

        closed = true
        forgetPendingClose()
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
        internal fun retryPendingCloseSessions(): Boolean {
            val pending = synchronized(pendingCloseLock) { pendingCloseSessions.toList() }
            var allClosed = true
            pending.forEach { session ->
                if (runCatching { session.close() }.isFailure) allClosed = false
            }
            return allClosed
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
            feedbackMaxPacketBytes: Int,
            volumeUnit: UsbFeatureUnit?
        ): UsbEncodedDacSession {
            check(retryPendingCloseSessions()) {
                "A previous encoded USB session is still awaiting confirmed teardown"
            }
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
            return UsbEncodedDacSession(connection, handle, volumeUnit)
        }
    }
}
