package com.waizusora.flowery

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

internal data class UsbAudioEndpoint(
    val address: Int,
    val direction: Int,
    val transferType: Int,
    val maxPacketSize: Int
)

internal data class UsbAudioInterface(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<UsbAudioEndpoint>
)

internal data class UsbAudioDevice(
    val deviceName: String,
    val productName: String,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val interfaces: List<UsbAudioInterface>,
    val hasAudioControl: Boolean,
    val hasAudioStreaming: Boolean,
    val hasIsochronousOutput: Boolean,
    val hasIsochronousInput: Boolean
) {
    /** Discovery hint only; it does not prove that exclusive USB streaming is available. */
    val isPotentialExclusiveOutput: Boolean
        get() = hasAudioStreaming && hasIsochronousOutput
}

internal data class AndroidUsbAudioOutput(
    val id: Int,
    val type: Int,
    val productName: String,
    val sampleRates: List<Int>,
    val encodings: List<Int>,
    val channelCounts: List<Int>
)

internal data class UsbAudioScanResult(
    val usbDevices: List<UsbAudioDevice>,
    val androidOutputs: List<AndroidUsbAudioOutput>
)

/**
 * Enumerates USB Audio Class interfaces and separately reports Android USB outputs.
 * The lists are not joined because AudioDeviceInfo does not expose USB VID/PID.
 */
internal class UsbAudioDeviceScanner(context: Context) {
    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun scan(): UsbAudioScanResult {
        val usbDevices = usbManager.deviceList.values
            .mapNotNull(::inspectDevice)
            .sortedWith(compareBy<UsbAudioDevice>({ it.vendorId }, { it.productId }, { it.deviceName }))

        val androidOutputs = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isUsbAudioType(it.type) }
            .map { device ->
                AndroidUsbAudioOutput(
                    id = device.id,
                    type = device.type,
                    productName = device.productName?.toString().orEmpty(),
                    sampleRates = device.sampleRates?.toList().orEmpty(),
                    encodings = device.encodings?.toList().orEmpty(),
                    channelCounts = device.channelCounts?.toList().orEmpty()
                )
            }

        return UsbAudioScanResult(usbDevices, androidOutputs)
    }

    private fun inspectDevice(device: UsbDevice): UsbAudioDevice? {
        val audioInterfaces = mutableListOf<UsbAudioInterface>()
        var hasControl = false
        var hasStreaming = false
        var hasIsochronousOutput = false
        var hasIsochronousInput = false

        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != USB_CLASS_AUDIO) continue

            val endpoints = (0 until usbInterface.endpointCount).map { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                val transferType = endpoint.type
                val direction = endpoint.direction

                if (usbInterface.interfaceSubclass == USB_SUBCLASS_STREAMING &&
                    transferType == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    if (direction == UsbConstants.USB_DIR_OUT) hasIsochronousOutput = true
                    if (direction == UsbConstants.USB_DIR_IN) hasIsochronousInput = true
                }

                UsbAudioEndpoint(
                    address = endpoint.address,
                    direction = direction,
                    transferType = transferType,
                    maxPacketSize = endpoint.maxPacketSize
                )
            }

            when (usbInterface.interfaceSubclass) {
                USB_SUBCLASS_CONTROL -> hasControl = true
                USB_SUBCLASS_STREAMING -> hasStreaming = true
            }

            audioInterfaces += UsbAudioInterface(
                interfaceNumber = usbInterface.id,
                alternateSetting = usbInterface.alternateSetting,
                subclass = usbInterface.interfaceSubclass,
                protocol = usbInterface.interfaceProtocol,
                endpoints = endpoints
            )
        }

        // Retain device-level Audio Class declarations even if Android exposes no
        // AudioStreaming interface. Such a device is not considered streamable.
        if (audioInterfaces.isEmpty() && device.deviceClass != USB_CLASS_AUDIO) return null

        return UsbAudioDevice(
            deviceName = device.deviceName,
            productName = device.productName?.toString() ?: "USB Audio Device",
            manufacturerName = device.manufacturerName,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            interfaces = audioInterfaces,
            hasAudioControl = hasControl,
            hasAudioStreaming = hasStreaming,
            hasIsochronousOutput = hasIsochronousOutput,
            hasIsochronousInput = hasIsochronousInput
        )
    }

    private fun isUsbAudioType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private companion object {
        const val USB_CLASS_AUDIO = 0x01
        const val USB_SUBCLASS_CONTROL = 0x01
        const val USB_SUBCLASS_STREAMING = 0x02
    }
}
