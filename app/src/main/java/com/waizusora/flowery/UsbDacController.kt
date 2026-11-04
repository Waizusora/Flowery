package com.waizusora.flowery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.Locale

/**
 * Discovers USB Audio Class devices and requests Android USB host permission.
 *
 * Permission only grants access to the USB device. It does not claim an audio
 * streaming interface or establish an exclusive/bit-perfect playback route.
 */
class UsbDacController(context: Context) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.waizusora.flowery.action.USB_PERMISSION"
        const val KTMICRO_VENDOR_ID = 0x31B2
        const val KTMICRO_KT384_32_PRODUCT_ID = 0x0111
        const val KTMICRO_KT384_32_NAME = "KT Micro KT-384/32 HIFI"
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Device(
        val usbDevice: UsbDevice,
        val displayName: String,
        val identity: String,
        val hasStreamingInterface: Boolean,
        val isReferenceKtmicro: Boolean
    )

    fun audioDevices(): List<Device> = manager.deviceList.values
        .filter(::hasAudioStreamingInterface)
        .map { device ->
            Device(
                usbDevice = device,
                displayName = describeDevice(device),
                identity = deviceIdentity(device),
                hasStreamingInterface = hasAudioStreamingInterface(device),
                isReferenceKtmicro = isReferenceKtmicro(device)
            )
        }

    fun hasAudioStreamingInterface(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val intf = device.getInterface(index)
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == 2
        }

    fun isReferenceKtmicro(device: UsbDevice): Boolean =
        device.vendorId == KTMICRO_VENDOR_ID &&
            device.productId == KTMICRO_KT384_32_PRODUCT_ID

    fun deviceIdentity(device: UsbDevice): String =
        "%04X:%04X".format(Locale.ROOT, device.vendorId, device.productId)

    fun describeDevice(device: UsbDevice): String {
        val identity = deviceIdentity(device)
        return if (isReferenceKtmicro(device)) {
            "$KTMICRO_KT384_32_NAME (VID/PID $identity)"
        } else {
            val product = device.productName?.takeIf { it.isNotBlank() } ?: "USB Audio Device"
            "$product (VID/PID $identity)"
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)
        manager.requestPermission(device, permissionIntent)
    }

    fun inspectDescriptors(device: UsbDevice): String {
        if (!manager.hasPermission(device)) {
            return "USB permission is required before Flowery can inspect this device's descriptors."
        }
        val connection = manager.openDevice(device)
            ?: return "Android could not open the USB device connection."
        return try {
            val descriptors = connection.rawDescriptors
            UsbAudioDescriptorParser.report(
                raw = descriptors,
                vid = device.vendorId,
                pid = device.productId,
                product = device.productName,
                manufacturer = device.manufacturerName
            )
        } catch (error: Throwable) {
            "USB descriptor inspection failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            connection.close()
        }
    }

    fun permissionGranted(device: UsbDevice): Boolean = manager.hasPermission(device)
}