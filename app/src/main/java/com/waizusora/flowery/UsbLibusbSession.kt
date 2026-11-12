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
