package com.waizusora.flowery

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable

/**
 * Owns a claimed USB AudioStreaming interface while a direct USB session is prepared.
 *
 * This class deliberately does not claim to stream PCM: Android's public USB Host
 * API has no isochronous transfer submission API. A native usbfs/libusb transport
 * must take over the file descriptor before direct playback can be enabled.
 */
internal class UsbAudioControlSession private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val interfaceNumber: Int,
    val alternateSetting: Int
) : Closeable {
    private var closed = false

    /**
     * Selects the UAC1 endpoint sampling frequency (3-byte little-endian Hz).
     * The device must advertise endpoint sampling-frequency control support.
     */
    fun setUac1EndpointSampleRate(endpointAddress: Int, sampleRate: Int): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (sampleRate <= 0 || sampleRate > 0xFFFFFF) {
            return Result.failure(IllegalArgumentException("UAC1 sample rate is out of range"))
        }
        val payload = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte()
        )
        val transferred = connection.controlTransfer(
            USB_ENDPOINT_OUT_CLASS,
            UAC_SET_CUR,
            UAC1_SAMPLING_FREQUENCY_CONTROL,
            endpointAddress and 0xFF,
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS
        )
        return if (transferred == payload.size) Result.success(Unit)
        else Result.failure(IllegalStateException("UAC1 SET_CUR failed: controlTransfer=$transferred"))
    }

    /**
     * Sets a UAC2 Clock Source frequency. clockSourceEntityId must come from
     * parsed class-specific descriptors; it is never guessed from VID/PID.
     */
    fun setUac2ClockFrequency(
        clockSourceEntityId: Int,
        sampleRate: Int
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (clockSourceEntityId !in 1..255 || sampleRate <= 0) {
            return Result.failure(IllegalArgumentException("Invalid UAC2 clock parameters"))
        }
        val payload = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte(),
            ((sampleRate shr 24) and 0xFF).toByte()
        )
        val transferred = connection.controlTransfer(
            USB_INTERFACE_OUT_CLASS,
            UAC_SET_CUR,
            UAC2_CLOCK_FREQUENCY_CONTROL,
            (clockSourceEntityId shl 8) or interfaceNumber,
            payload,
            payload.size,
            CONTROL_TIMEOUT_MS
        )
        return if (transferred == payload.size) Result.success(Unit)
        else Result.failure(IllegalStateException("UAC2 clock SET_CUR failed: controlTransfer=$transferred"))
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.setInterface(usbInterface)
        } catch (_: Exception) {
            // Interface restoration is best-effort during teardown.
        }
        try {
            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }

    companion object {
        private const val USB_ENDPOINT_OUT_CLASS = 0x22
        private const val USB_INTERFACE_OUT_CLASS = 0x21
        private const val UAC_SET_CUR = 0x01
        private const val UAC1_SAMPLING_FREQUENCY_CONTROL = 0x0100
        private const val UAC2_CLOCK_FREQUENCY_CONTROL = 0x0100
        private const val CONTROL_TIMEOUT_MS = 1_000

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            interfaceNumber: Int,
            alternateSetting: Int
        ): Result<UsbAudioControlSession> {
            if (!manager.hasPermission(device)) {
                return Result.failure(SecurityException("USB permission has not been granted"))
            }
            val usbInterface = (0 until device.interfaceCount)
                .asSequence()
                .map(device::getInterface)
                .firstOrNull {
                    it.id == interfaceNumber && it.alternateSetting == alternateSetting
                } ?: return Result.failure(
                    IllegalArgumentException("USB interface alternate was not found")
                )

            val connection = manager.openDevice(device)
                ?: return Result.failure(IllegalStateException("Unable to open USB device"))

            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                return Result.failure(IllegalStateException("Unable to claim USB AudioStreaming interface"))
            }

            if (!connection.setInterface(usbInterface)) {
                connection.releaseInterface(usbInterface)
                connection.close()
                return Result.failure(IllegalStateException("Unable to select USB alternate setting"))
            }

            return Result.success(
                UsbAudioControlSession(
                    connection,
                    usbInterface,
                    interfaceNumber,
                    alternateSetting
                )
            )
        }
    }
}
