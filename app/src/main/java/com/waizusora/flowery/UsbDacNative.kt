package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection

/**
 * JNI bridge to the native libusb USB Audio Class transport.
 *
 * PCM input must already be interleaved signed integer little-endian samples
 * in the exact bit depth and sample rate selected for the DAC.
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
        encodedBytesPerSecond: Long
    ): Long
    external fun nativeWriteEncodedPacket(handle: Long, encoded: ByteArray): Int
    external fun nativeWriteIsochronous(handle: Long, pcm: ByteArray, frameCount: Int): Int
    external fun nativeDrain(handle: Long): Int
    external fun nativeFlush(handle: Long)
    external fun nativeClose(handle: Long)
}

/**
 * Owns the Android USB connection for the full lifetime of a claimed USB Audio Class
 * streaming interface. Calls must be made off the UI thread. A write returns
 * once the PCM has been staged and the bounded isochronous window has accepted
 * it, so up to roughly 256 ms of previously written audio may still be queued
 * on the endpoint. Use [drain] at natural end of playback and [flush] on seek.
 * Closing the session cancels queued transfers and releases native resources.
 */
internal class UsbDacSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val nativeHandle: Long,
    val profile: UsbDacPlaybackProfile,
    val sampleRate: Int,
    private val volumeUnit: UsbFeatureUnit? = null
) : AutoCloseable {
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
        if (unit.protocol == 0x20) {
            val range = ByteArray(8)
            val transferred = volumeControl(0x82, range)
            if (transferred < 8) { volumeRangeLoaded = true; return null }
            val buffer = java.nio.ByteBuffer.wrap(range).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (buffer.short.toInt() < 1) { volumeRangeLoaded = true; return null }
            return (buffer.short.toInt() to buffer.short.toInt()).also { cachedVolumeRange = it; volumeRangeLoaded = true }
        }
        if (volumeControl(0x82, minimum) != 2 || volumeControl(0x83, maximum) != 2) { volumeRangeLoaded = true; return null }
        val min = java.nio.ByteBuffer.wrap(minimum).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        val max = java.nio.ByteBuffer.wrap(maximum).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        return (if (max > min) min to max else null).also { cachedVolumeRange = it; volumeRangeLoaded = true }
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

    /** Blocks until all queued audio, including a trailing partial packet, has been transmitted. */
    @Synchronized
    fun drain() {
        check(!closed) { "USB DAC session is closed" }
        UsbDacNative.nativeDrain(nativeHandle)
    }

    /** Discards staged and queued audio so playback can resume from a new position. */
    @Synchronized
    fun flush() {
        check(!closed) { "USB DAC session is closed" }
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
}
