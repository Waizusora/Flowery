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

    /**
     * Opens the first descriptor-validated UAC1 AC-3 encoded alternate setting.
     * Type-III IEC 61937 is preferred; Type-II raw AC-3 is the fallback.
     */
    internal fun openFirstCompatibleEncodedPlaybackPipeline(
        devices: List<Device>,
        decoderHandle: Long,
        sessionHandle: Long,
        sourceRate: Int
    ): Pair<Device, UsbEncodedPlaybackPipeline> {
        require(decoderHandle != 0L && sessionHandle != 0L && sourceRate > 0)
        var lastError: Throwable? = null
        for (device in devices) {
            if (!manager.hasPermission(device.usbDevice)) continue
            val inspection = manager.openDevice(device.usbDevice) ?: continue
            val descriptors = try {
                inspection.rawDescriptors
            } catch (error: Throwable) {
                lastError = error
                inspection.close()
                continue
            }
            inspection.close()

            val alternates = (0 until device.usbDevice.interfaceCount)
                .map { device.usbDevice.getInterface(it) }
                .filter {
                    it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.interfaceProtocol == 0
                }
                .distinctBy { it.id to it.alternateSetting }

            // Prefer IEC 61937 Type-III, then the codec-matched Type-II bitstream.
            val formats = listOf(
                Triple(3, UsbEncodedPlaybackPipeline.UAC1_FORMAT_TAG_IEC61937, UsbEncodedPlaybackPipeline.FRAMING_IEC61937_TYPE_III),
                Triple(2, UsbEncodedPlaybackPipeline.UAC1_FORMAT_TAG_AC3, UsbEncodedPlaybackPipeline.FRAMING_UAC1_TYPE_II)
            )
            for ((formatType, formatTag, framingKind) in formats) {
                val formatCode = if (framingKind == UsbEncodedPlaybackPipeline.FRAMING_IEC61937_TYPE_III)
                    UsbEncodedPlaybackPipeline.IEC61937_DATATYPE_AC3 else UsbEncodedPlaybackPipeline.UAC1_FORMAT_TAG_AC3
                for (alternate in alternates) {
                    val validation = try {
                        org.json.JSONObject(
                            NativeAudio.nativeValidateUsbEncodedDescriptor(
                                descriptors, alternate.id, alternate.alternateSetting,
                                formatType, formatTag, sourceRate
                            )
                        )
                    } catch (error: Throwable) {
                        lastError = error
                        continue
                    }
                    if (!validation.optBoolean("valid")) continue
                    val connection = manager.openDevice(device.usbDevice) ?: continue
                    try {
                        val pipeline = UsbEncodedPlaybackPipeline.open(
                            connection, decoderHandle, sessionHandle, descriptors,
                            alternate.id, alternate.alternateSetting,
                            formatType, formatTag, framingKind, formatCode
                        )
                        return device to pipeline
                    } catch (error: Throwable) {
                        lastError = error
                    }
                }
            }
        }
        throw IllegalStateException(
            "No permitted UAC1 Type-II AC-3 or Type-III IEC 61937 AC-3 profile matches the source sample rate.",
            lastError
        )
    }

    /** Reads the current master-channel Feature Unit volume when the DAC exposes it. */
    fun readHardwareVolumeFraction(device: UsbDevice): Float? {
        if (!manager.hasPermission(device)) return null
        val connection = manager.openDevice(device) ?: return null
        return try {
            val unit = UsbFeatureUnitParser.find(connection.rawDescriptors) ?: return null
            fun request(code: Int, data: ByteArray): Int = connection.controlTransfer(
                if (code == 0x01) 0x21 else 0xA1,
                code,
                0x0200,
                (unit.unitId shl 8) or unit.controlInterface,
                data,
                data.size,
                500
            )
            val range = if (unit.protocol == 0x20) {
                val response = ByteArray(2 + 6 * 16)
                val transferred = request(0x82, response)
                UsbFeatureUnitVolumeRange.parseUac2(response, transferred) ?: return null
            } else {
                val minimum = ByteArray(2)
                val maximum = ByteArray(2)
                if (request(0x82, minimum) != 2 || request(0x83, maximum) != 2) return null
                UsbFeatureUnitVolumeRange.parseUac1(minimum, maximum) ?: return null
            }
            val current = ByteArray(2)
            if (request(0x81, current) != 2) return null
            val value = java.nio.ByteBuffer.wrap(current).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            ((value - range.first).toFloat() / (range.second - range.first).toFloat()).coerceIn(0f, 1f)
        } catch (_: Throwable) {
            null
        } finally {
            connection.close()
        }
    }

    fun hasCompatibleUac2PlaybackProfile(device: UsbDevice): Boolean {
        if (!manager.hasPermission(device)) return false
        val connection = manager.openDevice(device) ?: return false
        return try {
            val descriptors = connection.rawDescriptors
            parseNativePlaybackProfiles(descriptors, null).any { profile ->
                profile.protocol == 0x00 ||
                    parseNativePlaybackClocks(descriptors, profile.interfaceNumber).isNotEmpty()
            }
        } catch (_: Throwable) {
            false
        } finally {
            connection.close()
        }
    }

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
                "\nUAC1 Type-I/UAC2 OUT PCM playback profiles: none could be validated from the descriptors."
            } else {
                "\nDescriptor-derived UAC1 Type-I/UAC2 OUT PCM playback profiles:" +
                    playbackProfiles.joinToString(separator = "") { profile ->
                        "\n  UAC${if (profile.protocol == 0x20) 2 else 1}, interface ${profile.interfaceNumber}, alternate ${profile.alternateSetting}: " +
                            "${profile.channels} channels, ${profile.bitResolution}-bit ${if (profile.encoding == "ieee_float32") "IEEE Float32" else "signed integer PCM"} " +
                            "(${profile.subslotBytes}-byte subslot), endpoint ${profile.endpointHex}, " +
                            "${profile.maxPacketBytes}-byte max packet, interval ${profile.interval}, ${profile.synchronization} sync, " +
                            "rates=${profile.sampleRates.joinToString().ifEmpty { profile.sampleRateRange?.let { "${it.first}-${it.second}" } ?: "not advertised" }}."
                    }
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
            val featureUnit = UsbFeatureUnitParser.find(descriptors)
            val dacCapabilitySummary = buildString {
                appendLine("DAC identity and controls")
                appendLine("Manufacturer: ${device.manufacturerName?.takeIf { it.isNotBlank() } ?: "Not reported by USB descriptors"}")
                appendLine("Product: ${device.productName?.takeIf { it.isNotBlank() } ?: "Not reported by USB descriptors"}")
                appendLine("VID/PID: ${device.vendorId.toString(16).uppercase().padStart(4, '0')}:${device.productId.toString(16).uppercase().padStart(4, '0')}")
                appendLine(
                    featureUnit?.let {
                        "Feature Unit master-channel volume control: advertised (unit ${it.unitId}, control interface ${it.controlInterface}, UAC protocol 0x${it.protocol.toString(16)})"
                    } ?: "Feature Unit master-channel volume control: not advertised in the standard descriptors"
                )
                appendLine("DAC silicon/chipset: not identified; standard USB descriptors do not reliably expose silicon identity.")
            }
            val completeReport = dacCapabilitySummary + "\n" + deviceSummary + "\n\n" + descriptorReport + profileReport
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
     * Opens a descriptor-selected UAC1 or UAC2 stereo output using native libusb.
     *
     * The session validates the requested sample rate against the relevant UAC
     * descriptors and performs the class-specific rate or clock negotiation that
     * the device advertises. The shared PCM producer supplies descriptor-negotiated signed integer or IEEE Float32 PCM bytes.
     */
    internal fun openExclusivePlaybackSession(
        device: UsbDevice,
        bitResolution: Int,
        sampleRate: Int,
        requiredSubslotBytes: Int? = null,
        requiredChannels: Int? = null,
        requiredEncoding: String = "signed_integer"
    ): UsbDacSession {
        check(UsbDacSession.retryPendingCloseSessions()) {
            "A previous PCM USB session is still awaiting confirmed teardown"
        }
        require(manager.hasPermission(device)) { "USB permission is required before opening the DAC." }
        require(bitResolution == 0 || bitResolution in 8..32) { "PCM resolution must be automatic or between 8 and 32 bits." }
        require(sampleRate > 0) { "Sample rate must be positive." }
        require(requiredEncoding == "signed_integer" || requiredEncoding == "ieee_float32") {
            "Unsupported USB PCM encoding."
        }

        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("Android could not open the USB DAC connection.")
        try {
            val descriptors = connection.rawDescriptors
            val volumeUnit = UsbFeatureUnitParser.find(descriptors)
            val discoveredProfiles = parseNativePlaybackProfiles(descriptors, null)
            val clockRangeReports = discoveredProfiles.flatMap { profile ->
                parseNativePlaybackClocks(descriptors, profile.interfaceNumber)
            }.distinct().map { (clockId, controlInterface) ->
                val response = ByteArray(2 + 12 * 16)
                val transferred = connection.controlTransfer(
                    0xA1, 0x82, 0x0100, (clockId shl 8) or controlInterface,
                    response, response.size, 1_000
                )
                val ranges = if (transferred >= 2)
                    JSONArray(NativeAudio.nativeParseUsbClockRanges(response, transferred))
                else JSONArray()
                val values = (0 until ranges.length()).map { index ->
                    val range = ranges.getJSONObject(index)
                    "${range.optLong("minimum")}-${range.optLong("maximum")} Hz step=${range.optLong("resolution")}"
                }
                "Clock source $clockId (control interface $controlInterface): " +
                    if (values.isEmpty()) "GET_RANGE unavailable (USB result $transferred)"
                    else values.joinToString()
            }
            val descriptorReport = UsbAudioDescriptorParser.report(
                raw = descriptors,
                vid = device.vendorId,
                pid = device.productId,
                product = device.productName,
                manufacturer = device.manufacturerName,
                clockRangeReports = clockRangeReports
            ) + "\n\nDescriptor-derived UAC1 Type-I/UAC2 OUT PCM profiles:" +
                discoveredProfiles.joinToString(separator = "") { profile ->
                    "\n  Interface ${profile.interfaceNumber}, alternate ${profile.alternateSetting}: " +
                        "${profile.channels} channels, ${profile.bitResolution}-bit ${if (profile.encoding == "ieee_float32") "IEEE Float32" else "signed integer PCM"} " +
                        "(${profile.subslotBytes}-byte subslot), endpoint ${profile.endpointHex}, " +
                        "${profile.maxPacketBytes}-byte max packet, interval ${profile.interval}, " +
                        "${profile.synchronization} sync."
                }
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
                .filter { (requiredSubslotBytes == null || it.subslotBytes == requiredSubslotBytes) &&
                    (requiredChannels == null || it.channels == requiredChannels) &&
                    it.encoding == requiredEncoding &&
                    (requiredEncoding != "ieee_float32" || (it.bitResolution == 32 && it.subslotBytes == 4)) }
            if (profiles.isEmpty()) {
                throw IllegalStateException(
                    "No descriptor-validated UAC1 Type-I/UAC2 OUT PCM profile supports " +
                        bitResolution + "-bit PCM."
                )
            }

            var lastError: Throwable? = null
            for (profile in profiles) {
                if (profile.protocol == 0x00) {
                    val rateSupported = sampleRate in profile.sampleRates ||
                        profile.sampleRateRange?.let { sampleRate in it.first..it.second } == true
                    if (!rateSupported) {
                        lastError = IllegalStateException(
                            "UAC1 interface ${profile.interfaceNumber} does not advertise ${sampleRate} Hz."
                        )
                        continue
                    }
                    try {
                        val nativeHandle = UsbDacNative.nativeOpen(
                            connection.fileDescriptor,
                            profile.interfaceNumber,
                            profile.alternateSetting,
                            profile.endpointAddress,
                            profile.maxPacketBytes,
                            profile.interval,
                            profile.feedbackEndpoint ?: 0,
                            profile.feedbackMaxPacketBytes,
                            profile.channels,
                            profile.subslotBytes,
                            sampleRate,
                            0,
                            -1,
                            profile.protocol,
                            profile.samplingFrequencyControl
                        )
                        if (nativeHandle != 0L) {
                            return UsbDacSession(connection, device, nativeHandle, profile, sampleRate, volumeUnit)
                        }
                        lastError = IllegalStateException("Native USB transport rejected the UAC1 playback profile.")
                    } catch (error: Throwable) {
                        lastError = error
                    }
                    continue
                }

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
                            profile.feedbackEndpoint ?: 0,
                            profile.feedbackMaxPacketBytes,
                            profile.channels,
                            profile.subslotBytes,
                            sampleRate,
                            clockId,
                            controlInterface,
                            profile.protocol,
                            false
                        )
                        if (nativeHandle != 0L) {
                            return UsbDacSession(connection, device, nativeHandle, profile, sampleRate, volumeUnit)
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
                "No descriptor-derived UAC1/UAC2 playback profile accepted ${sampleRate} Hz. " +
                    "No USB playback session was started.",
                lastError
            )

        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }



    /**
     * Opens the first permission-granted UAC2 DAC whose descriptors and clock
     * negotiation support the requested PCM channel layout. Automatic resolution
     * selection prefers the highest descriptor-advertised valid-bit depth; decoded
     * playback may select a supported clock rate and resample to it.
     */
    internal fun openFirstCompatiblePlaybackSession(
        devices: List<Device>,
        bitResolution: Int,
        sampleRate: Int,
        allowSampleRateConversion: Boolean = false,
        requiredSubslotBytes: Int? = null,
        channels: Int = 2,
        encoding: String = "signed_integer"
    ): Pair<Device, UsbDacSession> {
        val failures = mutableListOf<String>()
        for (device in devices) {
            if (!hasPermission(device.usbDevice)) {
                failures += "${device.identity}: USB permission not granted"
                continue
            }
            val discovery = runCatching {
                val connection = manager.openDevice(device.usbDevice)
                    ?: throw IllegalStateException("Android could not open the USB device.")
                try {
                    val descriptors = connection.rawDescriptors
                    val profiles = parseNativePlaybackProfiles(descriptors, null)
                    val compatibleProfiles = profiles.filter {
                        (requiredSubslotBytes == null || it.subslotBytes == requiredSubslotBytes) &&
                            it.channels == channels && it.encoding == encoding &&
                            (encoding != "ieee_float32" || (it.bitResolution == 32 && it.subslotBytes == 4))
                    }
                    val resolutions = if (bitResolution > 0) listOf(bitResolution)
                        else compatibleProfiles.map { it.bitResolution }.distinct().sortedDescending()
                    val rates = if (!allowSampleRateConversion) {
                        listOf(sampleRate)
                    } else {
                        val uac2 = compatibleProfiles.filter { it.protocol == 0x20 }
                        val uac2Rates = if (uac2.isNotEmpty()) {
                            discoverClockRates(connection, descriptors, uac2, sampleRate)
                        } else emptyList()
                        val uac1Rates = compatibleProfiles.filter { it.protocol == 0x00 }.flatMap { profile ->
                            profile.sampleRates + (profile.sampleRateRange?.let { range ->
                                sampleRate.coerceIn(range.first, range.second).let(::listOf)
                            } ?: emptyList())
                        }
                        (uac2Rates + uac1Rates).distinct().sortedBy { kotlin.math.abs(it - sampleRate) }
                    }
                    resolutions to rates
                } finally {
                    connection.close()
                }
            }.getOrElse { error ->
                failures += "${device.identity}: descriptor discovery failed: ${error.message ?: error.javaClass.simpleName}"
                emptyList<Int>() to emptyList()
            }
            val (candidateResolutions, candidateRates) = discovery
            for (candidateBits in candidateResolutions) {
                for (candidateRate in candidateRates) {
                    try {
                        val session = openExclusivePlaybackSession(
                            device.usbDevice, candidateBits, candidateRate, requiredSubslotBytes, channels, encoding
                        )
                        if (session.profile.channels == channels &&
                            session.profile.bitResolution == candidateBits &&
                            session.sampleRate == candidateRate) {
                            return device to session
                        }
                        session.close()
                        failures += "${device.identity}: negotiated profile mismatch for ${candidateBits}-bit/${candidateRate} Hz"
                    } catch (error: Throwable) {
                        failures += "${device.identity} ${candidateBits}-bit/${candidateRate} Hz: " +
                            (error.message ?: error.javaClass.simpleName)
                    }
                }
            }
            if (candidateResolutions.isEmpty()) {
                failures += "${device.identity}: no descriptor-validated requested-channel UAC1/UAC2 PCM profiles"
            }
            if (candidateRates.isEmpty()) {
                failures += "${device.identity}: no usable UAC1/UAC2 sample rates were reported"
            }
        }
        throw IllegalStateException(
            "No connected UAC1/UAC2 DAC accepted requested-channel PCM at source rate ${sampleRate} Hz. " +
                failures.joinToString("; ")
        )
    }

    private fun discoverClockRates(
        connection: android.hardware.usb.UsbDeviceConnection,
        descriptors: ByteArray,
        profiles: List<UsbDacPlaybackProfile>,
        sourceRate: Int
    ): List<Int> {
        val discoveredRanges = JSONArray()
        val clocks = profiles.flatMap { profile ->
            parseNativePlaybackClocks(descriptors, profile.interfaceNumber)
        }.distinct()
        for ((clockId, controlInterface) in clocks) {
            val response = ByteArray(2 + 12 * 16)
            val transferred = connection.controlTransfer(
                0xA1, 0x82, 0x0100, (clockId shl 8) or controlInterface,
                response, response.size, 1_000
            )
            if (transferred < 2) continue
            val ranges = JSONArray(NativeAudio.nativeParseUsbClockRanges(response, transferred))
            for (index in 0 until ranges.length()) {
                discoveredRanges.put(ranges.getJSONObject(index))
            }
        }
        val candidates = JSONArray(
            NativeAudio.nativeSelectUsbSampleRates(sourceRate, discoveredRanges.toString())
        )
        return (0 until candidates.length()).map { candidates.getInt(it) }
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