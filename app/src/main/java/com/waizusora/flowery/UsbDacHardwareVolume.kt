package com.waizusora.flowery

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Standalone USB Audio Class Feature Unit volume controller.
 *
 * Opens a short-lived [UsbDeviceConnection] solely for Audio Control transfers.
 * It does not claim an AudioStreaming interface and does not require the
 * experimental USB Direct / libusb playback path. Suitable for DACs that
 * advertise a host-writable Feature Unit volume control, whether or not the
 * device also exposes physical volume keys.
 *
 * Control semantics follow the UAC1 / UAC2 descriptors already parsed by
 * [UsbAudioVolumeDescriptorParser]. No VID/PID heuristics are applied.
 */
internal class UsbDacHardwareVolume(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Detection(
        val deviceName: String,
        val productName: String,
        val vendorId: Int,
        val productId: Int,
        val control: UacVolumeControl,
        val snapshot: UacVolumeSnapshot?
    ) {
        val detected: Boolean get() = true
        val writable: Boolean get() = control.supportsWrite
        val verified: Boolean get() = snapshot?.writable == true
        val currentDb: Float
            get() = snapshot?.let { it.currentDb256 / 256f } ?: Float.NaN
        val currentFraction: Float
            get() = snapshot?.asLinearFraction() ?: 1f
    }

    /**
     * Scans permitted USB Audio Class devices and returns the first device that
     * advertises a readable Feature Unit volume control associated with a
     * physical playback Output Terminal.
     */
    fun detect(): Detection? {
        for (device in usbManager.deviceList.values) {
            if (!isUsbAudioClass(device)) continue
            if (!usbManager.hasPermission(device)) continue
            val detection = openAndDetect(device) ?: continue
            return detection
        }
        return null
    }

    /**
     * Reads the current Feature Unit volume for [deviceName], if the device is
     * still connected, permitted, and advertises a readable control.
     */
    fun read(deviceName: String): Result<Detection> {
        val device = usbManager.deviceList[deviceName]
            ?: return Result.failure(IllegalStateException("USB device is no longer connected"))
        if (!usbManager.hasPermission(device)) {
            return Result.failure(IllegalStateException("USB permission is required for hardware volume"))
        }
        val detection = openAndDetect(device)
            ?: return Result.failure(IllegalStateException("No readable Feature Unit volume control"))
        return Result.success(detection)
    }

    /**
     * Sets Feature Unit volume to [fraction] (linear 0..1) on the detected DAC.
     * Verifies each SET_CUR with GET_CUR. Partial multi-channel writes are not
     * rolled back here; callers that need transactional semantics should use the
     * libusb session path while USB Direct playback is active.
     */
    fun setVolumeFraction(deviceName: String, fraction: Float): Result<Detection> {
        val requested = fraction.coerceIn(0f, 1f)
        if (!requested.isFinite()) {
            return Result.failure(IllegalArgumentException("Volume fraction must be finite"))
        }
        val device = usbManager.deviceList[deviceName]
            ?: return Result.failure(IllegalStateException("USB device is no longer connected"))
        if (!usbManager.hasPermission(device)) {
            return Result.failure(IllegalStateException("USB permission is required for hardware volume"))
        }
        val connection = try {
            openConnection(device)
        } catch (error: Exception) {
            return Result.failure(error)
        }
        return try {
            val control = parseVolumeControl(connection)
                ?: return Result.failure(IllegalStateException("No readable Feature Unit volume control"))
            if (!control.supportsWrite) {
                return Result.failure(IllegalStateException("Feature Unit volume is read-only"))
            }
            if (!claimControlInterface(connection, device, control.controlInterface)) {
                return Result.failure(IllegalStateException("Unable to claim Audio Control interface"))
            }
            try {
                val transfer = connectionAsTransfer(connection)
                var lastSnapshot: UacVolumeSnapshot? = null
                for (channel in control.writableChannels.distinct()) {
                    val range = readVolumeRange(transfer, control, channel)
                        ?: return Result.failure(
                            IllegalStateException("Feature Unit volume range unavailable for channel $channel")
                        )
                    val oldValue = readVolumeValue(transfer, control, channel, getCurRequest(control))
                        ?: return Result.failure(
                            IllegalStateException("Feature Unit GET_CUR failed for channel $channel")
                        )
                    val target = fractionToDb256(requested, range)
                    if (target != oldValue) {
                        val index = (control.unitId shl 8) or control.controlInterface
                        val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel
                        val payload = byteArrayOf(target.toByte(), (target ushr 8).toByte())
                        val written = transfer.transfer(
                            USB_INTERFACE_OUT_CLASS,
                            UAC_SET_CUR,
                            value,
                            index,
                            payload,
                            2,
                            CONTROL_TIMEOUT_MS
                        )
                        val verified = readVolumeValue(transfer, control, channel, getCurRequest(control))
                        if (written != 2 || verified != target) {
                            return Result.failure(
                                IllegalStateException(
                                    "Feature Unit volume SET_CUR verification failed for channel $channel"
                                )
                            )
                        }
                    }
                    if (channel == control.readableChannels.firstOrNull() || lastSnapshot == null) {
                        lastSnapshot = UacVolumeSnapshot(
                            currentDb256 = target,
                            minimumDb256 = range.minimum,
                            maximumDb256 = range.maximum,
                            resolutionDb256 = range.resolution,
                            writable = true,
                            channel = channel
                        )
                    }
                }
                val snapshot = lastSnapshot
                    ?: return Result.failure(IllegalStateException("No writable volume channel was updated"))
                Log.i(
                    TAG,
                    "USB_DAC_VOLUME_SET device=${device.deviceName} unit=${control.unitId} " +
                        "fraction=$requested currentDb256=${snapshot.currentDb256}"
                )
                Result.success(
                    Detection(
                        deviceName = device.deviceName,
                        productName = device.productName ?: device.deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        control = control,
                        snapshot = snapshot
                    )
                )
            } finally {
                releaseControlInterface(connection, device, control.controlInterface)
            }
        } finally {
            connection.close()
        }
    }

    private fun openAndDetect(device: UsbDevice): Detection? {
        val connection = try {
            openConnection(device)
        } catch (error: Exception) {
            Log.w(TAG, "USB_DAC_VOLUME_DETECT_FAILED device=${device.deviceName}: ${error.message}")
            return null
        }
        return try {
            val control = parseVolumeControl(connection) ?: return null
            if (!claimControlInterface(connection, device, control.controlInterface)) {
                return Detection(
                    deviceName = device.deviceName,
                    productName = device.productName ?: device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    control = control,
                    snapshot = null
                )
            }
            try {
                val transfer = connectionAsTransfer(connection)
                val channel = control.readableChannels.firstOrNull() ?: return null
                val range = readVolumeRange(transfer, control, channel)
                val current = readVolumeValue(transfer, control, channel, getCurRequest(control))
                val snapshot = if (range != null && current != null) {
                    UacVolumeSnapshot(
                        currentDb256 = current,
                        minimumDb256 = range.minimum,
                        maximumDb256 = range.maximum,
                        resolutionDb256 = range.resolution,
                        writable = control.supportsWrite,
                        channel = channel
                    )
                } else {
                    null
                }
                Detection(
                    deviceName = device.deviceName,
                    productName = device.productName ?: device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    control = control,
                    snapshot = snapshot
                )
            } finally {
                releaseControlInterface(connection, device, control.controlInterface)
            }
        } catch (error: Exception) {
            Log.w(TAG, "USB_DAC_VOLUME_DETECT_FAILED device=${device.deviceName}: ${error.message}")
            null
        } finally {
            connection.close()
        }
    }

    private fun parseVolumeControl(connection: UsbDeviceConnection): UacVolumeControl? {
        val descriptors = connection.rawDescriptors ?: return null
        return UsbAudioVolumeDescriptorParser.parse(descriptors)
    }

    private fun openConnection(device: UsbDevice): UsbDeviceConnection {
        return usbManager.openDevice(device)
            ?: throw IllegalStateException("Android could not open the USB device")
    }

    private fun claimControlInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        interfaceNumber: Int
    ): Boolean {
        val usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber }
            ?: return false
        return connection.claimInterface(usbInterface, true)
    }

    private fun releaseControlInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        interfaceNumber: Int
    ) {
        val usbInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber }
            ?: return
        runCatching { connection.releaseInterface(usbInterface) }
    }

    private fun connectionAsTransfer(connection: UsbDeviceConnection): UsbControlTransfer =
        UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
            connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
        }

    private fun readVolumeValue(
        transfer: UsbControlTransfer,
        control: UacVolumeControl,
        channel: Int,
        request: Int
    ): Int? {
        val buffer = ByteArray(2)
        val index = (control.unitId shl 8) or control.controlInterface
        val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel
        val transferred = transfer.transfer(
            USB_INTERFACE_IN_CLASS,
            request,
            value,
            index,
            buffer,
            2,
            CONTROL_TIMEOUT_MS
        )
        if (transferred != 2) return null
        val raw = (buffer[0].toInt() and 0xff) or ((buffer[1].toInt() and 0xff) shl 8)
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    private fun getCurRequest(control: UacVolumeControl): Int =
        if (control.protocol == 0x20) UAC2_CUR else UAC1_GET_CUR

    private fun readVolumeRange(
        transfer: UsbControlTransfer,
        control: UacVolumeControl,
        channel: Int
    ): UacVolumeRange? {
        if (control.protocol == 0x20) {
            // UAC2 RANGE (bRequest=0x02), not UAC1 GET_MIN (0x82).
            val rangeBuffer = ByteArray(8)
            val index = (control.unitId shl 8) or control.controlInterface
            val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel
            val rangeLen = transfer.transfer(
                USB_INTERFACE_IN_CLASS,
                UAC2_RANGE,
                value,
                index,
                rangeBuffer,
                rangeBuffer.size,
                CONTROL_TIMEOUT_MS
            )
            if (rangeLen >= 8) {
                fun signedAt(offset: Int): Int {
                    val raw = (rangeBuffer[offset].toInt() and 0xff) or
                        ((rangeBuffer[offset + 1].toInt() and 0xff) shl 8)
                    return if (raw and 0x8000 != 0) raw - 0x10000 else raw
                }
                val min = signedAt(2)
                val max = signedAt(4)
                val res = signedAt(6).coerceAtLeast(1)
                if (min <= max) return UacVolumeRange(min, max, res)
            }
            return null
        }
        val minimum = readVolumeValue(transfer, control, channel, UAC1_GET_MIN) ?: return null
        val maximum = readVolumeValue(transfer, control, channel, UAC1_GET_MAX) ?: return null
        val resolution = readVolumeValue(transfer, control, channel, UAC1_GET_RES) ?: 1
        if (minimum > maximum) return null
        return UacVolumeRange(minimum, maximum, resolution.coerceAtLeast(1))
    }

    private fun fractionToDb256(fraction: Float, range: UacVolumeRange): Int {
        val targetDb = if (fraction <= 0f) {
            range.minimum / 256.0
        } else {
            20.0 * ln(fraction.toDouble()) / ln(10.0)
        }
        var target = (targetDb * 256.0).roundToInt().coerceIn(range.minimum, range.maximum)
        if (range.resolution > 0) {
            target = (range.minimum +
                ((target - range.minimum).toDouble() / range.resolution).roundToInt() * range.resolution)
                .coerceIn(range.minimum, range.maximum)
        }
        return target
    }

    private fun isUsbAudioClass(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    companion object {
        private const val TAG = "FloweryUsbVolume"
        private const val USB_INTERFACE_IN_CLASS = 0xA1
        private const val USB_INTERFACE_OUT_CLASS = 0x21
        private const val UAC_SET_CUR = 0x01
        private const val UAC1_GET_CUR = 0x81
        private const val UAC1_GET_MIN = 0x82
        private const val UAC1_GET_MAX = 0x83
        private const val UAC1_GET_RES = 0x84
        private const val UAC2_CUR = 0x01
        private const val UAC2_RANGE = 0x02
        private const val UAC_FEATURE_VOLUME_CONTROL = 0x02
        private const val CONTROL_TIMEOUT_MS = 1_000
    }
}

