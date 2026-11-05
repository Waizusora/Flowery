package com.waizusora.flowery

import android.hardware.usb.UsbDeviceConnection

/**
 * JNI bridge to the native libusb UAC2 transport.
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
        controlInterface: Int
    ): Long

    external fun nativeWriteIsochronous(handle: Long, pcm: ByteArray, frameCount: Int): Int
    external fun nativeClose(handle: Long)
}

/**
 * Owns the Android USB connection for the full lifetime of a claimed UAC2
 * streaming interface. Writes are synchronous and must be called off the UI
 * thread. Closing the session releases the interface and native resources.
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
