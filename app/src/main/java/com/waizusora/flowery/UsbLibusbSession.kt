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

    /**
     * Applies the descriptor-advertised UAC1 endpoint sampling frequency.
     * UAC2 clock controls are deliberately not inferred from endpoint data.
     */
    @Synchronized
    fun setUac1SampleRate(endpointAddress: Int, sampleRate: Int): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (endpointAddress !in 1..127 || sampleRate !in 1..0xFFFFFF) {
            return Result.failure(IllegalArgumentException("Invalid UAC1 endpoint sampling frequency"))
        }
        val payload = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte()
        )
        return try {
            val transferred = connection.controlTransfer(
                0x22, 0x01, 0x0100, endpointAddress,
                payload, payload.size, 1_000
            )
            if (transferred == payload.size) Result.success(Unit)
            else Result.failure(IllegalStateException("UAC1 SET_CUR sampling frequency failed: controlTransfer=$transferred"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Negotiates a UAC2 Clock Source frequency using the entity ID resolved
     * from AudioControl descriptors. The advertised GET_RANGE is authoritative.
     */
    @Synchronized
    fun negotiateUac2Clock(
        controlInterfaceNumber: Int,
        clockSourceEntityId: Int,
        clockControls: Int,
        sampleRate: Int
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (controlInterfaceNumber !in 0..255 || clockSourceEntityId !in 1..255 ||
            clockControls !in 0..255 || sampleRate <= 0) {
            return Result.failure(IllegalArgumentException("Invalid UAC2 clock negotiation parameters"))
        }
        val frequencyControl = clockControls and 0x03
        if (frequencyControl != 0x01 && frequencyControl != 0x02) {
            return Result.failure(IllegalStateException("UAC2 Clock Source does not expose a usable frequency control"))
        }
        return try {
            val index = (clockSourceEntityId shl 8) or controlInterfaceNumber
            val range = ByteArray(2 + 12 * 32)
            val rangeLength = connection.controlTransfer(
                0xA1, 0x02, 0x0100, index, range, range.size, 1_000
            )
            if (rangeLength < 2) {
                return Result.failure(IllegalStateException("UAC2 Clock Source GET_RANGE failed: controlTransfer=$rangeLength"))
            }
            val subRanges = (range[0].toInt() and 0xFF) or ((range[1].toInt() and 0xFF) shl 8)
            if (subRanges !in 1..32 || rangeLength < 2 + subRanges * 12) {
                return Result.failure(IllegalStateException("UAC2 Clock Source returned an invalid frequency range"))
            }
            var supported = false
            for (i in 0 until subRanges) {
                val offset = 2 + i * 12
                val minimum = readU32(range, offset)
                val maximum = readU32(range, offset + 4)
                val resolution = readU32(range, offset + 8)
                if (minimum <= sampleRate.toLong() && sampleRate.toLong() <= maximum &&
                    (resolution == 0L || (sampleRate.toLong() - minimum) % resolution == 0L)) {
                    supported = true
                    break
                }
            }
            if (!supported) {
                return Result.failure(IllegalArgumentException("USB DAC Clock Source does not advertise $sampleRate Hz"))
            }

            val current = ByteArray(4)
            val currentLength = connection.controlTransfer(
                0xA1, 0x01, 0x0100, index, current, current.size, 1_000
            )
            if (currentLength != 4) {
                return Result.failure(IllegalStateException("UAC2 Clock Source GET_CUR failed: controlTransfer=$currentLength"))
            }
            val currentRate = readU32(current, 0)
            if (currentRate == sampleRate.toLong()) return Result.success(Unit)
            if (frequencyControl != 0x02) {
                return Result.failure(IllegalStateException("UAC2 Clock Source is read-only at $currentRate Hz; requested $sampleRate Hz"))
            }

            val payload = byteArrayOf(
                (sampleRate and 0xFF).toByte(),
                ((sampleRate ushr 8) and 0xFF).toByte(),
                ((sampleRate ushr 16) and 0xFF).toByte(),
                ((sampleRate ushr 24) and 0xFF).toByte()
            )
            val written = connection.controlTransfer(
                0x21, 0x01, 0x0100, index, payload, payload.size, 1_000
            )
            if (written != payload.size) {
                return Result.failure(IllegalStateException("UAC2 Clock Source SET_CUR failed: controlTransfer=$written"))
            }
            val verified = ByteArray(4)
            val verifiedLength = connection.controlTransfer(
                0xA1, 0x01, 0x0100, index, verified, verified.size, 1_000
            )
            if (verifiedLength != 4 || readU32(verified, 0) != sampleRate.toLong()) {
                val actual = if (verifiedLength == 4) readU32(verified, 0).toString() else "unavailable"
                return Result.failure(IllegalStateException("UAC2 Clock Source did not confirm $sampleRate Hz after SET_CUR (current=$actual)"))
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    @Synchronized
    fun startPlayback(
        decoderHandle: Long,
        endpointAddress: Int,
        rawMaxPacketSize: Int,
        interval: Int,
        sampleRate: Int,
        channels: Int,
        validBits: Int,
        subslotBytes: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        initialPositionMs: Long = 0L
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        return try {
            val result = NativeAudio.nativeStartUsbIsochronousPlayback(
                nativeHandle, decoderHandle, endpointAddress, rawMaxPacketSize,
                interval, sampleRate, channels,
                validBits, subslotBytes, feedbackEndpointAddress,
                feedbackMaxPacketSize, initialPositionMs
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
