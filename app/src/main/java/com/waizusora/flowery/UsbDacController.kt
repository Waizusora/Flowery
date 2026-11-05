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
            val clockReports = UsbAudioDescriptorParser.clockSources(descriptors).mapNotNull { (clockId, controlInterface) ->
                val response = ByteArray(2 + 12 * 16)
                val transferred = connection.controlTransfer(
                    0xA1,
                    0x82,
                    0x0100,
                    (clockId shl 8) or controlInterface,
                    response,
                    response.size,
                    1_000
                )
                if (transferred < 2) {
                    "Clock source $clockId on control interface $controlInterface: GET_RANGE unavailable (USB result $transferred)."
                } else {
                    val count = (response[0].toInt() and 0xff) or
                        ((response[1].toInt() and 0xff) shl 8)
                    val ranges = (0 until minOf(count, (transferred - 2) / 12)).map { index ->
                        val start = 2 + index * 12
                        val minimum = readLe32(response, start)
                        val maximum = readLe32(response, start + 4)
                        val resolution = readLe32(response, start + 8)
                        "$minimum-$maximum Hz (step $resolution)"
                    }
                    if (ranges.isEmpty()) {
                        "Clock source $clockId on control interface $controlInterface: no valid ranges returned."
                    } else {
                        "Clock source $clockId on control interface $controlInterface: ${ranges.joinToString()}."
                    }
                }
            }
            val descriptorReport = UsbAudioDescriptorParser.report(
                raw = descriptors,
                vid = device.vendorId,
                pid = device.productId,
                product = device.productName,
                manufacturer = device.manufacturerName,
                clockRangeReports = clockReports
            )
            val playbackProfiles = UsbDacPlaybackProfile.findStereoUac2PlaybackProfiles(descriptors)
            val profileReport = if (playbackProfiles.isEmpty()) {
                "\nStereo UAC2 OUT playback profiles: none could be validated from the descriptors."
            } else {
                "\nDescriptor-derived stereo UAC2 OUT playback profiles:" +
                    playbackProfiles.joinToString(separator = "") { profile ->
                        "\n  Interface ${profile.interfaceNumber}, alternate ${profile.alternateSetting}: " +
                            "${profile.channels} channels, ${profile.bitResolution}-bit PCM " +
                            "(${profile.subslotBytes}-byte subslot), endpoint ${profile.endpointHex}, " +
                            "${profile.maxPacketBytes}-byte max packet, interval ${profile.interval}, ${profile.synchronization} sync."
                    } +
                    "\n  Sample rates remain unverified until UAC2 clock negotiation succeeds."
            }
            descriptorReport + profileReport
        } catch (error: Throwable) {
            "USB descriptor inspection failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            connection.close()
        }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    /**
     * Opens the descriptor-selected UAC2 stereo output using native libusb.
     *
     * This claims the streaming interface, verifies the requested UAC2 clock
     * frequency, and returns a session for exact-format integer PCM writes.
     * The current player supplies raw stereo PCM WAV data; other source formats
     * are rejected while USB bit-perfect mode is enabled.
     */
    internal fun openExclusivePlaybackSession(
        device: UsbDevice,
        bitResolution: Int,
        sampleRate: Int
    ): UsbDacSession {
        require(manager.hasPermission(device)) { "USB permission is required before opening the DAC." }
        require(bitResolution in setOf(16, 24, 32)) { "Only 16-, 24-, and 32-bit PCM are supported." }
        require(sampleRate > 0) { "Sample rate must be positive." }

        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Android could not open the USB DAC connection.")
        try {
            val descriptors = connection.rawDescriptors
            val profiles = UsbDacPlaybackProfile.findStereoUac2PlaybackProfiles(
                descriptors,
                requestedBitResolution = bitResolution
            )
            val profile = profiles.firstOrNull()
                ?: throw IllegalStateException(
                    "No descriptor-validated stereo UAC2 OUT profile supports " +
                        bitResolution + "-bit PCM."
                )
            val clocks = UsbAudioDescriptorParser.playbackClockSources(descriptors, profile.interfaceNumber)
            if (clocks.isEmpty()) {
                throw IllegalStateException("No UAC2 clock source descriptor was found.")
            }

            var lastError: Throwable? = null
            for ((clockId, controlInterface) in clocks) {
                try {
                    val nativeHandle = UsbDacNative.nativeOpen(
                        connection.fileDescriptor,
                        profile.interfaceNumber,
                        profile.alternateSetting,
                        profile.endpointAddress,
                        profile.maxPacketBytes,
                        profile.interval,
                        profile.channels,
                        profile.subslotBytes,
                        sampleRate,
                        clockId,
                        controlInterface
                    )
                    if (nativeHandle != 0L) {
                        return UsbDacSession(connection, nativeHandle, profile, sampleRate)
                    }
                    lastError = IllegalStateException("Native USB transport returned an invalid session.")
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IllegalStateException(
                "The DAC did not accept and confirm the requested UAC2 clock rate. " +
                    "No USB playback session was started.",
                lastError
            )
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    fun permissionGranted(device: UsbDevice): Boolean = manager.hasPermission(device)
}