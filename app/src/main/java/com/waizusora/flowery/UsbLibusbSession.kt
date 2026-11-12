package com.waizusora.flowery

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Owns the Android USB connection for the lifetime of a native libusb session.
 *
 * Do not open UsbAudioControlSession for the same interface while this object is
 * active: libusb owns interface claiming and alternate-setting selection.
 */
internal class UsbLibusbSession private constructor(
    private val connection: UsbDeviceConnection,
    private val nativeHandle: Long
) : Closeable {
    private var closed = false

    @Synchronized
    fun startPlayback(
        decoderHandle: Long,
        endpointAddress: Int,
        rawMaxPacketSize: Int,
        interval: Int,
        sampleRate: Int,
        channels: Int,
        validBits: Int,
        subslotBytes: Int
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        return try {
            val result = NativeAudio.nativeStartUsbIsochronousPlayback(
                nativeHandle, decoderHandle, endpointAddress, rawMaxPacketSize,
                interval, sampleRate, channels,
                validBits, subslotBytes
            )
            if (result == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("Native USB playback failed to start"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Returns the negotiated USB bus speed: low, full, high, super, super-plus, or unknown. */
    @Synchronized
    fun busSpeed(): String {
        if (closed) return "unknown"
        return when (NativeAudio.nativeGetUsbBusSpeed(nativeHandle)) {
            1 -> "low"
            2 -> "full"
            3 -> "high"
            4 -> "super"
            5 -> "super-plus"
            else -> "unknown"
        }
    }

    @Synchronized
    fun playbackStatus(): String {
        if (closed) return """{"status":2,"error":"USB session is closed"}"""
        return NativeAudio.nativeGetUsbPlaybackStatus(nativeHandle)
    }

    @Synchronized
    fun pausePlayback(): Result<Unit> = setPlaybackPaused(true)

    @Synchronized
    fun resumePlayback(): Result<Unit> = setPlaybackPaused(false)

    /**
     * Stops and drains the active isochronous transfers, seeks the decoder, and
     * restarts the stream with its existing endpoint and PCM configuration.
     * Returns the decoder's resolved seek position in milliseconds.
     */
    @Synchronized
    fun seekPlayback(decoderHandle: Long, positionMs: Long): Result<Long> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (decoderHandle <= 0L || positionMs < 0L) {
            return Result.failure(IllegalArgumentException("Invalid USB seek parameters"))
        }
        return try {
            val actualPosition = NativeAudio.nativeSeekUsbPlayback(
                nativeHandle, decoderHandle, positionMs
            )
            if (actualPosition >= 0L) Result.success(actualPosition)
            else Result.failure(IllegalStateException("Native USB seek failed"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun setPlaybackPaused(paused: Boolean): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        return try {
            val result = if (paused) NativeAudio.nativePauseUsbPlayback(nativeHandle)
                else NativeAudio.nativeResumeUsbPlayback(nativeHandle)
            if (result == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("Unable to change USB playback pause state"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    @Synchronized
    fun stopPlayback() {
        if (!closed) NativeAudio.nativeStopUsbPlayback(nativeHandle)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            NativeAudio.nativeCloseUsbAudioSession(nativeHandle)
        } finally {
            // libusb_wrap_sys_device does not own or close this descriptor.
            connection.close()
        }
    }

    companion object {
        fun open(
            manager: UsbManager,
            device: UsbDevice,
            interfaceNumber: Int,
            alternateSetting: Int
        ): Result<UsbLibusbSession> {
            if (!manager.hasPermission(device)) {
                return Result.failure(SecurityException("USB permission has not been granted"))
            }
            val connection = manager.openDevice(device)
                ?: return Result.failure(IllegalStateException("Unable to open USB device"))
            val fd = connection.fileDescriptor
            if (fd < 0) {
                connection.close()
                return Result.failure(IllegalStateException("Android returned an invalid USB file descriptor"))
            }
            return try {
                val handle = NativeAudio.nativeOpenUsbAudioSession(
                    fd, interfaceNumber, alternateSetting
                )
                if (handle <= 0) {
                    connection.close()
                    Result.failure(IllegalStateException("Native libusb returned an invalid session"))
                } else {
                    Result.success(UsbLibusbSession(connection, handle))
                }
            } catch (error: Exception) {
                connection.close()
                Result.failure(error)
            }
        }
    }
}
