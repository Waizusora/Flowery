package com.waizusora.flowery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.Locale
import org.json.JSONArray

/**
 * Discovers USB Audio Class devices and requests Android USB host permission.
 *
 * Permission only grants access to the USB device. It does not claim an audio
 * streaming interface or establish an exclusive/bit-perfect playback route.
 */
class UsbDacController(context: Context) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.waizusora.flowery.action.USB_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Device(
        val usbDevice: UsbDevice,
        val displayName: String,
        val identity: String,
        val hasStreamingInterface: Boolean,
    )

    fun audioDevices(): List<Device> = manager.deviceList.values
        .filter(::hasAudioStreamingInterface)
        .map { device ->
            Device(
                usbDevice = device,
                displayName = describeDevice(device),
                identity = deviceIdentity(device),
                hasStreamingInterface = hasAudioStreamingInterface(device)
            )
        }

    fun hasAudioStreamingInterface(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val intf = device.getInterface(index)
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == 2
        }

    fun deviceIdentity(device: UsbDevice): String =
        "%04X:%04X".format(Locale.ROOT, device.vendorId, device.productId)

    fun describeDevice(device: UsbDevice): String {
        val identity = deviceIdentity(device)
        val product = device.productName?.takeIf { it.isNotBlank() } ?: "USB Audio Device"
        return "$product (VID/PID $identity)"
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
            val clockReports = UsbAudioDescriptorParser.clockSources(descriptors).map { (clockId, controlInterface) ->
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
                val ranges = if (transferred >= 2) {
                    JSONArray(NativeAudio.nativeParseUsbClockRanges(response, transferred))
                } else {
                    JSONArray()
                }
                val formattedRanges = (0 until ranges.length()).map { index ->
                    val range = ranges.getJSONObject(index)
                    "${range.getLong("minimum")}-${range.getLong("maximum")} Hz " +
                        "(step ${range.getLong("resolution")})"
                }
                val rawResponseHex = response.take(transferred.coerceIn(0, response.size))
                    .joinToString("") { "%02X".format(it.toInt() and 0xff) }
                when {
                    transferred < 2 ->
                        "Clock source $clockId on control interface $controlInterface: GET_RANGE unavailable " +
                            "(USB result $transferred, responseHex=$rawResponseHex)."
                    formattedRanges.isEmpty() ->
                        "Clock source $clockId on control interface $controlInterface: no valid ranges returned " +
                            "(transferred=$transferred, responseHex=$rawResponseHex)."
                    else ->
                        "Clock source $clockId on control interface $controlInterface: ${formattedRanges.joinToString()} " +
                            "(transferred=$transferred, responseHex=$rawResponseHex)."
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
            val playbackProfiles = parseNativePlaybackProfiles(descriptors, null)
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
            val interfaceSummary = (0 until device.interfaceCount).joinToString(separator = "\n") { index ->
                val intf = device.getInterface(index)
                "USB interface ${intf.id}: alternate=${intf.alternateSetting}, class=0x${intf.interfaceClass.toString(16)}, " +
                    "subclass=0x${intf.interfaceSubclass.toString(16)}, protocol=0x${intf.interfaceProtocol.toString(16)}, " +
                    "endpoints=${intf.endpointCount}"
            }
            val deviceSummary = "Android USB device ID: ${device.deviceId}\nUSB device class: 0x${device.deviceClass.toString(16)}\n" +
                "USB device subclass: 0x${device.deviceSubclass.toString(16)}\nUSB device protocol: 0x${device.deviceProtocol.toString(16)}\n" +
                "USB interface count: ${device.interfaceCount}\n" + interfaceSummary
            val completeReport = deviceSummary + "\n\n" + descriptorReport + profileReport
            val databaseSummary = runCatching {
                val profileDatabase = UsbDacProfileDatabase(appContext)
                try {
                    profileDatabase.record(
                        vendorId = device.vendorId,
                        productId = device.productId,
                        manufacturer = device.manufacturerName,
                        product = device.productName,
                        rawDescriptors = descriptors,
                        descriptorReport = completeReport
                    ).diagnosticSummary()
                } finally {
                    profileDatabase.close()
                }
            }.getOrElse { error ->
                "DAC profile database write failed: ${error.message ?: error.javaClass.simpleName}"
            }
            completeReport + "\n\n" + databaseSummary
        } catch (error: Throwable) {
            "USB descriptor inspection failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            connection.close()
        }
    }

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
        require(bitResolution == 0 || bitResolution in setOf(16, 24, 32)) { "PCM resolution must be automatic or 16, 24, or 32 bits." }
        require(sampleRate > 0) { "Sample rate must be positive." }

        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Android could not open the USB DAC connection.")
        try {
            val descriptors = connection.rawDescriptors
            val descriptorReport = UsbAudioDescriptorParser.report(
                raw = descriptors,
                manufacturer = device.manufacturerName,
                product = device.productName
            )
            runCatching {
                val database = UsbDacProfileDatabase(appContext)
                try {
                    database.record(device.vendorId, device.productId, device.manufacturerName,
                        device.productName, descriptors, descriptorReport)
                } finally {
                    database.close()
                }
            }
            val profiles = parseNativePlaybackProfiles(descriptors, bitResolution.takeIf { it > 0 })
            if (profiles.isEmpty()) {
                throw IllegalStateException(
                    "No descriptor-validated stereo UAC2 OUT profile supports " +
                        bitResolution + "-bit PCM."
                )
            }

            var lastError: Throwable? = null
            for (profile in profiles) {
                val clocks = parseNativePlaybackClocks(descriptors, profile.interfaceNumber)
                if (clocks.isEmpty()) {
                    lastError = IllegalStateException(
                        "No UAC2 clock source descriptor was linked to playback interface " +
                            "${profile.interfaceNumber}. Descriptor trace:\\n" +
                            UsbAudioDescriptorParser.playbackDescriptorTrace(descriptors, profile.interfaceNumber)
                    )
                    continue
                }
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
                        lastError = IllegalStateException(
                            "Native USB transport rejected alternate setting ${profile.alternateSetting} " +
                                "and clock source $clockId."
                        )
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
            }
            throw IllegalStateException(
                "No descriptor-derived UAC2 playback profile accepted and confirmed " +
                    "${sampleRate} Hz. No USB playback session was started.",
                lastError
            )
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }



    /**
     * Opens the first connected, permission-granted UAC2 DAC whose descriptors and clock
     * negotiation support the exact stereo PCM format requested by the active source.
     */
    internal fun openFirstCompatiblePlaybackSession(
        devices: List<Device>,
        bitResolution: Int,
        sampleRate: Int
    ): Pair<Device, UsbDacSession> {
        val failures = mutableListOf<String>()
        for (device in devices) {
            if (!hasPermission(device.usbDevice)) {
                failures += "${device.identity}: USB permission not granted"
                continue
            }
            val candidateResolutions = if (bitResolution > 0) {
                listOf(bitResolution)
            } else {
                runCatching {
                    val connection = manager.openDevice(device.usbDevice)
                        ?: throw IllegalStateException("Android could not open the USB device.")
                    try {
                        parseNativePlaybackProfiles(connection.rawDescriptors, null)
                            .map { it.bitResolution }.distinct().sortedDescending()
                    } finally {
                        connection.close()
                    }
                }.getOrElse { error ->
                    failures += "${device.identity}: descriptor discovery failed: ${error.message}"
                    emptyList()
                }
            }
            for (candidateBits in candidateResolutions) {
                try {
                    val session = openExclusivePlaybackSession(device.usbDevice, candidateBits, sampleRate)
                    if (session.profile.channels == 2 &&
                        session.profile.bitResolution == candidateBits &&
                        session.sampleRate == sampleRate) {
                        return device to session
                    }
                    session.close()
                    failures += "${device.identity}: negotiated profile did not match ${candidateBits}-bit PCM"
                } catch (error: Throwable) {
                    failures += "${device.identity} ${candidateBits}-bit: ${error.message ?: error.javaClass.simpleName}"
                }
                }
            if (candidateResolutions.isEmpty()) {
                failures += "${device.identity}: no descriptor-validated stereo UAC2 PCM profiles"
            }
        }
        throw IllegalStateException(
            "No connected UAC2 DAC accepted stereo ${if (bitResolution > 0) "$bitResolution-bit" else "supported"} PCM at ${sampleRate} Hz. " +
                failures.joinToString("; ")
        )
    }

    private fun parseNativePlaybackProfiles(
        descriptors: ByteArray,
        requestedBitResolution: Int?
    ): List<UsbDacPlaybackProfile> {
        val values = JSONArray(
            NativeAudio.nativeParseUsbDacProfiles(descriptors, requestedBitResolution ?: 0)
        )
        return (0 until values.length()).map { index ->
            UsbDacPlaybackProfile.fromNativeJson(values.getJSONObject(index))
        }
    }

    private fun parseNativePlaybackClocks(
        descriptors: ByteArray,
        streamingInterface: Int
    ): List<Pair<Int, Int>> {
        val values = JSONArray(
            NativeAudio.nativeParseUsbDacPlaybackClocks(descriptors, streamingInterface)
        )
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            value.getInt("clockId") to value.getInt("controlInterface")
        }
    }

    fun permissionGranted(device: UsbDevice): Boolean = manager.hasPermission(device)
}