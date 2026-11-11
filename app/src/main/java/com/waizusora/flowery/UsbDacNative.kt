package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection
import java.util.Collections
import java.util.IdentityHashMap

/**
 * JNI bridge to the native libusb USB Audio Class transport.
 *
 * PCM input must already be interleaved little-endian samples in the exact
 * encoding, subslot width, channel layout, and sample rate selected for the DAC.
 * Integer and IEEE Float32 payloads are passed to libusb without sample conversion.
 */
internal object UsbDacNative {
    init {
        System.loadLibrary("flowery_usb")
    }

    external fun nativeOpen(
        fileDescriptor: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpoint: Int,
        maxPacketBytes: Int,
        interval: Int,
        feedbackEndpoint: Int,
        feedbackMaxPacketBytes: Int,
        channels: Int,
        subslotBytes: Int,
        sampleRate: Int,
        clockId: Int,
        controlInterface: Int,
        protocol: Int,
        samplingFrequencyControl: Boolean
    ): Long

    external fun nativeOpenEncoded(
        fileDescriptor: Int,
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
    ): Long
    external fun nativeWriteEncodedPacket(handle: Long, encoded: ByteArray, payloadOffset: Int, payloadLength: Int, durationUs: Long): Int
    external fun nativeWriteIsochronous(handle: Long, pcm: ByteArray, frameCount: Int): Int
    external fun nativeDrain(handle: Long): Int
    external fun nativeFlush(handle: Long): Int
    external fun nativeRequestPause(handle: Long): Boolean
    external fun nativeRequestResume(handle: Long): Boolean
    external fun nativeRequestStop(handle: Long): Boolean
    external fun nativeClose(handle: Long): Boolean
}

/**
 * Owns the Android USB connection for the full lifetime of a claimed USB Audio Class
 * streaming interface. Calls must be made off the UI thread. A write returns
 * once the PCM has been staged and the bounded isochronous window has accepted
 * it, so up to roughly 256 ms of previously written audio may still be queued
 * on the endpoint. Use [drain] at natural end of playback and [flush] only when changing playback position.
 * Closing the session cancels queued transfers and releases native resources.
 */
internal class UsbDacSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val nativeHandle: Long,
    val profile: UsbDacPlaybackProfile,
    val sampleRate: Int,
    private val volumeUnit: UsbFeatureUnit? = null
) : AutoCloseable {
    companion object {
        const val WRITE_STOPPED = -2

        private val pendingCloseLock = Any()
        private val pendingCloseSessions: MutableSet<UsbDacSession> =
            Collections.newSetFromMap(IdentityHashMap())

        /** Retries sessions retained after native or Android teardown failed. */
        internal fun retryPendingCloseSessions(): Boolean {
            val pending = synchronized(pendingCloseLock) { pendingCloseSessions.toList() }
            var allClosed = true
            pending.forEach { session ->
                if (runCatching { session.close() }.isFailure) allClosed = false
            }
            return allClosed
        }
    }

    private fun rememberPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.add(this) }
    }

    private fun forgetPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.remove(this) }
    }

    val hardwareVolumeAvailable: Boolean get() = volumeUnit != null

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
            // UAC2 returns wNumSubRanges followed by signed MIN/MAX/RES triplets.
            // Read all advertised subranges instead of silently using only the first.
            val range = ByteArray(2 + 6 * 16)
            val transferred = volumeControl(0x82, range)
            UsbFeatureUnitVolumeRange.parseUac2(range, transferred)
        } else {
            if (volumeControl(0x82, minimum) != 2 || volumeControl(0x83, maximum) != 2) {
                null
            } else {
                UsbFeatureUnitVolumeRange.parseUac1(minimum, maximum)
            }
        }
        volumeRangeLoaded = true
        cachedVolumeRange = parsed
        return parsed
    }

    @Synchronized
    fun readHardwareVolumeFraction(): Float? {
        check(!closed) { "USB DAC session is closed" }
        if (volumeUnit == null) return null
        val range = volumeRange() ?: return null
        val current = ByteArray(2)
        if (volumeControl(0x81, current) != 2) return null
        val raw = java.nio.ByteBuffer.wrap(current).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        return ((raw - range.first).toFloat() / (range.second - range.first).toFloat()).coerceIn(0f, 1f)
    }

    @Synchronized
    fun setHardwareVolumeFraction(fraction: Float): Boolean {
        check(!closed) { "USB DAC session is closed" }
        val range = volumeRange() ?: return false
        val raw = (range.first + (range.second - range.first) * fraction.coerceIn(0f, 1f)).toInt()
        val data = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(raw.toShort()).array()
        return volumeControl(0x01, data) == 2
    }
    @Volatile
    private var nativeClosed = false

    @Volatile
    private var connectionClosed = false

    @Volatile
    private var closed = false

    private var volumeRangeLoaded = false
    private var cachedVolumeRange: Pair<Int, Int>? = null

    @Synchronized
    fun writePcm(pcmLittleEndian: ByteArray, frameCount: Int): Int {
        check(!closed) { "USB DAC session is closed" }
        require(frameCount > 0) { "Frame count must be positive" }
        val expectedBytes = frameCount.toLong() * profile.channels * profile.subslotBytes
        require(expectedBytes == pcmLittleEndian.size.toLong()) {
            "PCM data must match the selected DAC channel count and subslot size"
        }
        return UsbDacNative.nativeWriteIsochronous(nativeHandle, pcmLittleEndian, frameCount)
    }

    // Deliberately unsynchronized: these requests must wake a writer blocked inside JNI.
    fun requestPause(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestPause(nativeHandle)
    fun requestResume(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestResume(nativeHandle)
    fun requestStop(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestStop(nativeHandle)

    /** Blocks until all queued audio, including a trailing partial packet, has been transmitted. */
    @Synchronized
    fun drain() {
        check(!closed) { "USB DAC session is closed" }
        requireUsbPcmDrainResult(UsbDacNative.nativeDrain(nativeHandle))
    }

    /** Discards staged and queued audio so playback can resume from a new position. */
    @Synchronized
    fun flush() {
        check(!closed) { "USB DAC session is closed" }
        check(UsbDacNative.nativeFlush(nativeHandle) == 0) {
            "USB transport could not cancel and reap all in-flight transfers for seeking"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return

        // Native and Android ownership have separate completion points. Once
        // nativeClose succeeds, never retry it with the released native handle.
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
                            "Interrupted while waiting for USB transfers to finish teardown",
                            interrupted
                        )
                    }
                }
            }
            if (!nativeCloseConfirmed) {
                rememberPendingClose()
                throw IllegalStateException(
                    "libusb could not confirm that all in-flight transfers were reaped; " +
                        "the USB connection remains open for a safe retry"
                )
            }
        }

        if (!connectionClosed) try {
            connection.close()
            connectionClosed = true
        } catch (error: Throwable) {
            rememberPendingClose()
            throw error
        }
        closed = true
        forgetPendingClose()
    }
}


/** Rejects native drain failures while allowing an intentional concurrent stop. */
internal fun requireUsbPcmDrainResult(result: Int) {
    check(result == 0 || result == UsbDacSession.WRITE_STOPPED) {
        "USB transport drain failed (status=$result)"
    }
}
