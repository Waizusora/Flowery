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
        channels: Int,
        subslotBytes: Int,
        sampleRate: Int,
        clockId: Int,
        controlInterface: Int,
        protocol: Int,
        samplingFrequencyControl: Boolean
    ): Long

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
    val sampleRate: Int
) : AutoCloseable {
    @Volatile
    private var closed = false

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
