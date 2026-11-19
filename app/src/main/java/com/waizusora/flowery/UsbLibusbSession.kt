package com.waizusora.flowery

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.Closeable
import kotlin.math.roundToInt

// Android's mockable android.jar throws from Log methods in local JVM tests.
// Keep diagnostics best-effort so clock negotiation and rollback remain testable.
private fun safeUsbLogI(tag: String, message: String) {
    try { Log.i(tag, message) } catch (_: RuntimeException) { }
}

private fun safeUsbLogE(tag: String, message: String) {
    try { Log.e(tag, message) } catch (_: RuntimeException) { }
}

private fun safeUsbLogE(tag: String, message: String, error: Throwable) {
    try { Log.e(tag, message, error) } catch (_: RuntimeException) { }
}

private const val UAC_FEATURE_VOLUME_CONTROL = 0x02

/** Assumed floor (-90 dB in 1/256 dB units) used only when GET_RANGE is refused. */
private const val ASSUMED_UAC_VOLUME_MIN_DB256 = -90 * 256

/** UAC1 class request codes (Audio 1.0). */
private const val UAC1_SET_CUR = 0x01
private const val UAC1_GET_CUR = 0x81
private const val UAC1_GET_MIN = 0x82
private const val UAC1_GET_MAX = 0x83
private const val UAC1_GET_RES = 0x84

/**
 * UAC2 class request codes (Audio 2.0). CUR/RANGE replace the UAC1 MIN/MAX/RES
 * family. Clock negotiation in this file already uses these values; Feature Unit
 * volume must match or UAC2 DACs STALL every volume transfer.
 */
private const val UAC2_CUR = 0x01
private const val UAC2_RANGE = 0x02

/** GET_CUR request code for the Feature Unit protocol of [control]. */
private fun volumeGetCurRequest(control: UacVolumeControl): Int =
    if (control.protocol == 0x20) UAC2_CUR else UAC1_GET_CUR

/** GET_RANGE (UAC2) request code. UAC1 uses discrete MIN/MAX/RES instead. */
private fun volumeGetRangeRequest(): Int = UAC2_RANGE

/**
 * Owns the Android USB connection for the lifetime of a native libusb session.
 *
 * Do not open UsbAudioControlSession for the same interface while this object is
 * active: libusb owns interface claiming and alternate-setting selection.
 */
/**
 * UAC2 Clock Multiplier numerator and denominator controls must both support
 * GET_CUR. Read-only (1) and host-programmable (2) controls are readable.
 */
/**
 * UAC2 bmControls bit-pair semantics: 0b00 absent, 0b01 present and read-only,
 * 0b11 present and host-programmable. 0b10 is not allowed by the specification;
 * it is treated as writable here, matching the Linux USB audio driver, which
 * tests only the high bit of the pair.
 */
internal fun isUac2ControlWritable(controls: Int): Boolean = (controls and 0x02) != 0

/** True when a UAC2 bit pair is present (read-only or programmable). */
internal fun isUac2ControlPresent(controls: Int): Boolean = (controls and 0x03) != 0

internal fun isValidUac2ClockMultiplierControls(controls: Int): Boolean {
    val numeratorControl = controls and 0x03
    val denominatorControl = (controls shr 2) and 0x03
    return numeratorControl in 1..2 && denominatorControl in 1..2
}

internal fun interface UsbControlTransfer {
    fun transfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int
    ): Int
}

internal data class Uac2ClockPath(
    val sourceEntityId: Int,
    val sourceControls: Int,
    val selectors: List<Uac2ClockSelectorStep>,
    val multipliers: List<Uac2ClockMultiplierStep>,
    val clockEntities: List<Int> = emptyList()
) {
    fun hasConsistentTopology(): Boolean {
        if (clockEntities.size != selectors.size + multipliers.size + 1 ||
            clockEntities.lastOrNull() != sourceEntityId ||
            clockEntities.any { it !in 1..255 } ||
            clockEntities.toSet().size != clockEntities.size
        ) return false
        val selectorById = selectors.associateBy { it.entityId }
        val multiplierById = multipliers.associateBy { it.entityId }
        if (selectorById.size != selectors.size || multiplierById.size != multipliers.size ||
            selectors.any { it.entityId in multiplierById } ||
            clockEntities.dropLast(1).any { it !in selectorById && it !in multiplierById }
        ) return false
        for (index in 0 until clockEntities.lastIndex) {
            val entityId = clockEntities[index]
            val nextEntityId = clockEntities[index + 1]
            val selector = selectorById[entityId]
            if (selector != null) {
                if (selector.selectedPin !in 1..selector.inputSources.size ||
                    selector.inputSources[selector.selectedPin - 1] != nextEntityId
                ) return false
            } else {
                val multiplier = multiplierById[entityId] ?: return false
                if (multiplier.inputClockId != nextEntityId) return false
            }
        }
        return true
    }
}
internal data class Uac2ClockSelectorStep(
    val entityId: Int,
    val controls: Int,
    val selectedPin: Int,
    val inputSources: List<Int> = emptyList()
)
internal data class Uac2ClockMultiplierStep(val entityId: Int, val controls: Int, val inputClockId: Int = -1)

internal class UsbLibusbSession internal constructor(
    private val connection: UsbDeviceConnection?,
    private val nativeHandle: Long,
    transferOverride: UsbControlTransfer? = null,
    private val controlInterfaceClaimer: ((Int) -> Boolean)? = null
) : Closeable {
    private val controlTransfer: UsbControlTransfer = transferOverride ?: UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
        val usbConnection = connection ?: throw IllegalStateException("USB control connection is unavailable")
        usbConnection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
    }
    private var closed = false
    private val claimedControlInterfaces = mutableSetOf<Int>()
    private var pendingClockRollback: (() -> List<String>)? = null

    private fun ensureControlInterfaceClaimed(interfaceNumber: Int): Boolean {
        if (interfaceNumber !in 0..255) return false
        if (interfaceNumber in claimedControlInterfaces) return true
        val claimer = controlInterfaceClaimer ?: return true
        if (!claimer(interfaceNumber)) return false
        claimedControlInterfaces += interfaceNumber
        return true
    }

    private fun volumeControlTransfer(
        operation: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int
    ): Int {
        val transferred = controlTransfer.transfer(
            requestType, request, value, index, buffer, length, 1_000
        )
        val dataLength = transferred.coerceIn(0, minOf(length, buffer.size))
        val data = buffer.copyOfRange(0, dataLength)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        safeUsbLogI(
            "FloweryUsbNative",
            "USB_VOLUME_CONTROL_TRANSFER op=$operation bmRequestType=0x${requestType.toString(16)} " +
                "bRequest=0x${request.toString(16)} wValue=0x${value.toString(16)} " +
                "wIndex=0x${index.toString(16)} requested=$length transferred=$transferred data=$data"
        )
        return transferred
    }

    private fun readVolumeValue(control: UacVolumeControl, channel: Int, request: Int): Int? {
        val index = (control.unitId shl 8) or control.controlInterface
        val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel
        val bytes = ByteArray(2)
        val op = when (request) {
            UAC1_GET_CUR, UAC2_CUR -> "GET_CUR_ch$channel"
            UAC1_GET_MIN -> "GET_MIN_ch$channel"
            UAC1_GET_MAX -> "GET_MAX_ch$channel"
            UAC1_GET_RES -> "GET_RES_ch$channel"
            else -> "GET_0x${request.toString(16)}_ch$channel"
        }
        val length = volumeControlTransfer(op, 0xA1, request, value, index, bytes, 2)
        if (length != 2) return null
        return ((bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8))
            .let { if (it and 0x8000 != 0) it - 0x10000 else it }
    }

    private fun readCurrentVolume(control: UacVolumeControl, channel: Int): Int? =
        readVolumeValue(control, channel, volumeGetCurRequest(control))

    /**
     * Channels whose GET_RANGE request has already been refused by the device in this
     * session. The negative result is cached so that periodic volume polling does not
     * repeat a request that is known to STALL.
     */
    private val volumeRangeRefusedChannels = mutableSetOf<Int>()

    /**
     * Resolves the volume range for [channel].
     *
     * GET_RANGE is authoritative. Some DAC firmware advertises a programmable
     * Feature Unit volume control but STALLs GET_RANGE while still honouring
     * GET_CUR/SET_CUR. For that case only, an attenuation-only range is assumed:
     * the maximum is the greater of 0 dB and the current value, so the host never
     * boosts above unity gain, and the minimum is [ASSUMED_UAC_VOLUME_MIN_DB256].
     * Every write remains verified with GET_CUR and rolled back on mismatch, so a
     * wrong assumption surfaces as a failed command rather than an unverified change.
     */
    private fun readVolumeRange(control: UacVolumeControl, channel: Int): UacVolumeRange? {
        if (channel !in volumeRangeRefusedChannels) {
            val advertised = readAdvertisedVolumeRange(control, channel)
            if (advertised != null) return advertised
            if (control.protocol == 0x00) return null
            volumeRangeRefusedChannels += channel
        }
        val current = readCurrentVolume(control, channel) ?: return null
        val maximum = maxOf(current, 0)
        safeUsbLogI(
            "FloweryUsbNative",
            "USB_VOLUME_RANGE_ASSUMED unit=${control.unitId} channel=$channel currentDb256=$current " +
                "minimumDb256=${minOf(ASSUMED_UAC_VOLUME_MIN_DB256, current)} maximumDb256=$maximum reason=GET_RANGE-refused"
        )
        return UacVolumeRange(minOf(ASSUMED_UAC_VOLUME_MIN_DB256, current), maximum, 0)
    }

    private fun readAdvertisedVolumeRange(control: UacVolumeControl, channel: Int): UacVolumeRange? {
        if (control.protocol == 0x00) {
            val minimum = readVolumeValue(control, channel, UAC1_GET_MIN) ?: return null
            val maximum = readVolumeValue(control, channel, UAC1_GET_MAX) ?: return null
            val resolution = readVolumeValue(control, channel, UAC1_GET_RES) ?: return null
            if (minimum > maximum || resolution < 0) return null
            return UacVolumeRange(minimum, maximum, resolution)
        }
        val index = (control.unitId shl 8) or control.controlInterface
        val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel

        // UAC2 volume RANGE uses bRequest=RANGE (0x02), not the UAC1 GET_MIN (0x82).
        // Layout: two-byte subrange count followed by 16-bit MIN/MAX/RES triplets.
        // Request one complete triplet first (8 bytes); some DAC firmware STALLs an
        // otherwise valid class request when wLength exceeds its range response.
        val firstRange = ByteArray(8)
        val firstLength = volumeControlTransfer(
            "GET_RANGE_ch$channel", 0xA1, UAC2_RANGE, value, index, firstRange, firstRange.size
        )
        if (firstLength < firstRange.size) return null
        val count = (firstRange[0].toInt() and 0xff) or
            ((firstRange[1].toInt() and 0xff) shl 8)
        if (count !in 1..16) return null

        val bytes = if (count == 1) {
            firstRange
        } else {
            val fullRange = ByteArray(2 + count * 6)
            val fullLength = volumeControlTransfer(
                "GET_RANGE_FULL_ch$channel", 0xA1, UAC2_RANGE, value, index, fullRange, fullRange.size
            )
            if (fullLength < fullRange.size) return null
            fullRange
        }

        // Multiple disjoint ranges are unusual for volume. Use the range containing
        // the current value; otherwise the first descriptor-advertised range.
        val current = readCurrentVolume(control, channel)
        val ranges = (0 until count).mapNotNull { rangeIndex ->
            val offset = 2 + rangeIndex * 6
            fun signed16(at: Int): Int {
                val raw = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
                return if (raw and 0x8000 != 0) raw - 0x10000 else raw
            }
            val minimum = signed16(offset)
            val maximum = signed16(offset + 2)
            val resolution = signed16(offset + 4)
            if (minimum <= maximum && resolution >= 0) UacVolumeRange(minimum, maximum, resolution) else null
        }
        return ranges.firstOrNull { current != null && current in it.minimum..it.maximum }
            ?: ranges.firstOrNull()
    }

    /** Reads descriptor-advertised Feature Unit volume in signed 1/256 dB units. */
    @Synchronized
    fun readUacVolume(control: UacVolumeControl): Result<UacVolumeSnapshot> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (!control.supportsRead || !ensureControlInterfaceClaimed(control.controlInterface)) {
            return Result.failure(IllegalStateException("USB Audio Feature Unit volume is not readable"))
        }
        return try {
            val channel = (control.writableChannels.firstOrNull() ?: control.readableChannels.firstOrNull()) ?: return Result.failure(
                IllegalStateException("Feature Unit has no readable volume channel")
            )
            val range = readVolumeRange(control, channel)
                ?: return Result.failure(IllegalStateException("Feature Unit GET_RANGE/GET_MIN failed"))
            val current = readCurrentVolume(control, channel)
                ?: return Result.failure(IllegalStateException("Feature Unit GET_CUR failed"))
            if (current !in range.minimum..range.maximum) {
                return Result.failure(IllegalStateException("Feature Unit returned volume outside its advertised range"))
            }
            Result.success(
                UacVolumeSnapshot(
                    currentDb256 = current,
                    minimumDb256 = range.minimum,
                    maximumDb256 = range.maximum,
                    resolutionDb256 = range.resolution,
                    writable = control.supportsWrite,
                    channel = channel
                )
            )
        } catch (error: Exception) {
            safeUsbLogE("FloweryUsbNative", "USB_VOLUME_READ_FAILED unit=${control.unitId}", error)
            Result.failure(error)
        }
    }

    /**
     * Sets Feature Unit volume for every advertised writable channel and verifies
     * each SET_CUR with GET_CUR. Partial writes are rolled back on failure.
     */
    @Synchronized
    fun setUacVolume(control: UacVolumeControl, fraction: Float): Result<UacVolumeSnapshot> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (!fraction.isFinite() || fraction !in 0f..1f) {
            return Result.failure(IllegalArgumentException("USB volume fraction must be finite and within 0..1"))
        }
        if (!control.supportsWrite || !ensureControlInterfaceClaimed(control.controlInterface)) {
            return Result.failure(IllegalStateException("USB DAC does not expose a writable Feature Unit volume control"))
        }
        data class Changed(val channel: Int, val oldValue: Int)
        val changed = mutableListOf<Changed>()
        return try {
            var resultSnapshot: UacVolumeSnapshot? = null
            for (channel in control.writableChannels.distinct()) {
                val range = readVolumeRange(control, channel)
                    ?: throw IllegalStateException("Feature Unit volume range unavailable for channel $channel")
                val oldValue = readCurrentVolume(control, channel)
                    ?: throw IllegalStateException("Feature Unit GET_CUR failed for channel $channel")
                val targetDb = if (fraction <= 0f) range.minimum / 256.0
                    else (20.0 * kotlin.math.ln(fraction.toDouble()) / kotlin.math.ln(10.0))
                var target = (targetDb * 256.0).roundToInt().coerceIn(range.minimum, range.maximum)
                if (range.resolution > 0) {
                    target = (range.minimum + ((target - range.minimum).toDouble() / range.resolution)
                        .roundToInt() * range.resolution).coerceIn(range.minimum, range.maximum)
                }
                if (target != oldValue) {
                    val index = (control.unitId shl 8) or control.controlInterface
                    val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or channel
                    val payload = byteArrayOf(target.toByte(), (target ushr 8).toByte())
                    // Record before the request: a transport exception may occur after
                    // the device accepted the control transfer.
                    changed += Changed(channel, oldValue)
                    val written = volumeControlTransfer("SET_CUR_ch$channel", 0x21, UAC2_CUR, value, index, payload, 2)
                    val verified = readCurrentVolume(control, channel)
                    if (written != 2 || verified != target) {
                        throw IllegalStateException("Feature Unit volume SET_CUR verification failed for channel $channel")
                    }
                }
                if (channel == control.readableChannels.firstOrNull()) {
                    resultSnapshot = UacVolumeSnapshot(
                        currentDb256 = target,
                        minimumDb256 = range.minimum,
                        maximumDb256 = range.maximum,
                        resolutionDb256 = range.resolution,
                        writable = true,
                        channel = channel
                    )
                }
            }
            val snapshot = resultSnapshot ?: readUacVolume(control).getOrThrow()
            safeUsbLogI("FloweryUsbNative", "USB_VOLUME_SET unit=${control.unitId} channel=${snapshot.channel} requestedFraction=$fraction currentDb256=${snapshot.currentDb256} writable=true")
            Result.success(snapshot)
        } catch (error: Exception) {
            val rollbackErrors = mutableListOf<String>()
            for (change in changed.asReversed()) {
                try {
                    val index = (control.unitId shl 8) or control.controlInterface
                    val value = (UAC_FEATURE_VOLUME_CONTROL shl 8) or change.channel
                    val payload = byteArrayOf(change.oldValue.toByte(), (change.oldValue ushr 8).toByte())
                    val written = volumeControlTransfer("ROLLBACK_ch${change.channel}", 0x21, UAC2_CUR, value, index, payload, 2)
                    val verified = readCurrentVolume(control, change.channel)
                    if (written != 2 || verified != change.oldValue) {
                        rollbackErrors += "channel ${change.channel} restore verification failed"
                    }
                } catch (rollbackError: Exception) {
                    rollbackErrors += "channel ${change.channel}: ${rollbackError.message ?: rollbackError.javaClass.simpleName}"
                }
            }
            val detail = if (rollbackErrors.isEmpty()) error.message
                else "${error.message}; rollback: ${rollbackErrors.joinToString()}"
            safeUsbLogE("FloweryUsbNative", "USB_VOLUME_SET_FAILED unit=${control.unitId}", error)
            Result.failure(IllegalStateException(detail ?: "Feature Unit volume update failed", error))
        }
    }

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
            val transferred = controlTransfer.transfer(
                0x22, 0x01, 0x0100, endpointAddress,
                payload, payload.size, 1_000
            )
            safeUsbLogI("FloweryUsbNative", "UAC1_CLOCK_SET endpoint=0x${endpointAddress.toString(16)} requestedHz=$sampleRate transferred=$transferred")
            if (transferred == payload.size) Result.success(Unit)
            else Result.failure(IllegalStateException("UAC1 SET_CUR sampling frequency failed: controlTransfer=$transferred"))
        } catch (error: Exception) {
            safeUsbLogE("FloweryUsbNative", "UAC1_CLOCK_SET_FAILED endpoint=0x${endpointAddress.toString(16)} requestedHz=$sampleRate", error)
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
        sampleRate: Int,
        clockSelectorEntityId: Int = 0,
        clockSelectorControls: Int = 0,
        selectorClockSources: IntArray = intArrayOf(),
        selectorSourceControls: IntArray = intArrayOf(),
        clockMultiplierEntityId: Int = 0,
        clockMultiplierControls: Int = 0,
        clockPaths: List<Uac2ClockPath> = emptyList()
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        if (controlInterfaceNumber !in 0..255 || clockSourceEntityId !in 0..255 ||
            clockControls !in 0..255 || sampleRate <= 0 ||
            clockSelectorEntityId !in 0..255 || clockSelectorControls !in 0..255 ||
            clockMultiplierEntityId !in 0..255 || clockMultiplierControls !in 0..255) {
            return Result.failure(IllegalArgumentException("Invalid UAC2 clock negotiation parameters"))
        }
        // UAC2 clock requests are addressed to the AudioControl interface. usbfs
        // requires the calling file to own the addressed interface, so the kernel
        // audio driver must be detached from it as well; otherwise every request
        // fails and no sample rate appears to be supported.
        val claimer = controlInterfaceClaimer
        if (claimer != null && claimedControlInterfaces.add(controlInterfaceNumber) &&
            !claimer(controlInterfaceNumber)) {
            claimedControlInterfaces.remove(controlInterfaceNumber)
            return Result.failure(
                IllegalStateException("Unable to claim USB AudioControl interface $controlInterfaceNumber")
            )
        }
        return try {
            var lastRangeLength = 0
            fun supportsRate(sourceId: Int, rate: Int): Boolean {
                val entityIndex = (sourceId shl 8) or controlInterfaceNumber
                val range = ByteArray(2 + 12 * 32)
                val length = controlTransfer.transfer(0xA1, 0x02, 0x0100, entityIndex, range, range.size, 1_000)
                lastRangeLength = length
                if (length < 2) return false
                val count = (range[0].toInt() and 0xFF) or ((range[1].toInt() and 0xFF) shl 8)
                if (count !in 1..32 || length < 2 + count * 12) return false
                for (i in 0 until count) {
                    val offset = 2 + i * 12
                    val minimum = readU32(range, offset)
                    val maximum = readU32(range, offset + 4)
                    val resolution = readU32(range, offset + 8)
                    if (minimum <= rate.toLong() && rate.toLong() <= maximum &&
                        (resolution == 0L || (rate.toLong() - minimum) % resolution == 0L)) return true
                }
                return false
            }

            fun canNegotiateSource(sourceId: Int, controls: Int, rate: Int): Boolean {
                // GET_RANGE is authoritative for both host-programmable and read-only
                // Clock Sources. Rate selection for read-only sources is expected to
                // follow the chosen AudioStreaming alternate setting.
                return supportsRate(sourceId, rate)
            }
            if (clockPaths.isNotEmpty()) {
                val failures = mutableListOf<String>()
                for (path in clockPaths) {
                    if (path.sourceEntityId !in 1..255 || path.sourceControls !in 0..255 ||
                        !path.hasConsistentTopology()) {
                        failures += "invalid source controls"
                        continue
                    }
                    var sourceRate = sampleRate.toLong()
                    var valid = true
                    for (multiplier in path.multipliers) {
                        if (multiplier.entityId !in 1..255 || multiplier.inputClockId !in 1..255 ||
                            !isValidUac2ClockMultiplierControls(multiplier.controls)) {
                            failures += "invalid multiplier controls"; valid = false; break
                        }
                        val index = (multiplier.entityId shl 8) or controlInterfaceNumber
                        fun readRatio(control: Int): Long? {
                            val bytes = ByteArray(4)
                            val length = controlTransfer.transfer(0xA1, 0x01, control shl 8, index, bytes, 4, 1_000)
                            return if (length == 4) readU32(bytes, 0) else null
                        }
                        val numerator = readRatio(1)
                        val denominator = readRatio(2)
                        if (numerator == null || denominator == null || numerator == 0L || denominator == 0L) {
                            failures += "unable to read a valid multiplier ratio"; valid = false; break
                        }
                        val scaled = runCatching { Math.multiplyExact(sourceRate, denominator) }.getOrNull()
                        if (scaled == null || scaled % numerator != 0L) {
                            failures += "multiplier requires a non-integral source rate"; valid = false; break
                        }
                        sourceRate = scaled / numerator
                    }
                    if (!valid || sourceRate !in 1..Int.MAX_VALUE.toLong()) continue
                    if (!supportsRate(path.sourceEntityId, sourceRate.toInt())) {
                        failures += "source " + path.sourceEntityId + " does not advertise " + sourceRate +
                            " Hz (GET_RANGE transfer result " + lastRangeLength + ")"
                        continue
                    }
                    val sourceIndex = (path.sourceEntityId shl 8) or controlInterfaceNumber
                    val sourceCurrentBytes = ByteArray(4)
                    if (controlTransfer.transfer(0xA1, 0x01, 0x0100, sourceIndex, sourceCurrentBytes, 4, 1_000) != 4) {
                        failures += "source GET_CUR failed"; continue
                    }
                    val oldSourceRate = readU32(sourceCurrentBytes, 0)
                    if (oldSourceRate !in 1..Int.MAX_VALUE.toLong()) {
                        failures += "source returned an invalid current rate"; continue
                    }
                    // bmControls frequency field (bits 1:0), per UAC2 bit-pair semantics:
                    //   0b11 = present and host-programmable; SET_CUR is mandatory when
                    //         GET_CUR differs, and a failed write or verification rejects
                    //         the clock path. (0b10 is not allowed by the specification and
                    //         is treated identically, matching the Linux USB audio driver.)
                    //   0b00 / 0b01 = absent or read-only. Many class-compliant DACs leave
                    //         Clock Source GET_CUR at a nominal or previously negotiated rate
                    //         and select the active rate via the AudioStreaming alternate
                    //         setting. GET_RANGE (checked above) is the capability gate; do not
                    //         reject a read-only source solely because GET_CUR does not match.
                    val sourceHostProgrammable = isUac2ControlWritable(path.sourceControls)
                    var sourceActiveRate = oldSourceRate

                    data class SelectorChange(val index: Int, val oldPin: Int, val newPin: Int)
                    val selectorChanges = mutableListOf<SelectorChange>()
                    for (selector in path.selectors) {
                        if (selector.entityId !in 1..255 || selector.inputSources.isEmpty() ||
                            selector.inputSources.any { it !in 1..255 } ||
                            selector.selectedPin !in 1..selector.inputSources.size ||
                            !isUac2ControlPresent(selector.controls)) {
                            failures += "invalid selector descriptor controls"; valid = false; break
                        }
                        val selectorIndex = (selector.entityId shl 8) or controlInterfaceNumber
                        val active = ByteArray(1)
                        if (controlTransfer.transfer(0xA1, 0x01, 0x0100, selectorIndex, active, 1, 1_000) != 1) {
                            failures += "selector GET_CUR failed"; valid = false; break
                        }
                        val oldPin = active[0].toInt() and 0xFF
                        if (oldPin !in 1..selector.inputSources.size) {
                            failures += "selector returned an invalid pin"; valid = false; break
                        }
                        if (oldPin != selector.selectedPin) {
                            if (!isUac2ControlWritable(selector.controls)) {
                                failures += "selector is read-only"; valid = false; break
                            }
                            selectorChanges += SelectorChange(selectorIndex, oldPin, selector.selectedPin)
                        }
                    }
                    if (!valid) continue

                    val attemptedSelectors = mutableListOf<SelectorChange>()
                    var sourceWriteAttempted = false
                    fun restoreState(): List<String> {
                        val rollbackErrors = mutableListOf<String>()
                        if (sourceWriteAttempted && oldSourceRate != sourceRate) {
                            try {
                                val oldRate = oldSourceRate.toInt()
                                val payload = byteArrayOf(oldRate.toByte(), (oldRate ushr 8).toByte(),
                                    (oldRate ushr 16).toByte(), (oldRate ushr 24).toByte())
                                val written = controlTransfer.transfer(0x21, 0x01, 0x0100, sourceIndex, payload, 4, 1_000)
                                val verify = ByteArray(4)
                                val read = controlTransfer.transfer(0xA1, 0x01, 0x0100, sourceIndex, verify, 4, 1_000)
                                if (written != 4 || read != 4 || readU32(verify, 0) != oldSourceRate) {
                                    rollbackErrors += "clock source restore failed"
                                }
                            } catch (error: Exception) {
                                rollbackErrors += "clock source restore threw: " + (error.message ?: error.javaClass.simpleName)
                            }
                        }
                        for (change in attemptedSelectors.asReversed()) {
                            try {
                                val payload = byteArrayOf(change.oldPin.toByte())
                                val written = controlTransfer.transfer(0x21, 0x01, 0x0100, change.index, payload, 1, 1_000)
                                val verify = ByteArray(1)
                                val read = controlTransfer.transfer(0xA1, 0x01, 0x0100, change.index, verify, 1, 1_000)
                                if (written != 1 || read != 1 || (verify[0].toInt() and 0xFF) != change.oldPin) {
                                    rollbackErrors += "clock selector " + change.index + " restore failed"
                                }
                            } catch (error: Exception) {
                                rollbackErrors += "clock selector " + change.index + " restore threw: " +
                                    (error.message ?: error.javaClass.simpleName)
                            }
                        }
                        return rollbackErrors
                    }

                    var mutationFailure: String? = null
                    try {
                        for (change in selectorChanges) {
                            attemptedSelectors += change
                            val payload = byteArrayOf(change.newPin.toByte())
                            val written = controlTransfer.transfer(0x21, 0x01, 0x0100, change.index, payload, 1, 1_000)
                            val verify = ByteArray(1)
                            val read = controlTransfer.transfer(0xA1, 0x01, 0x0100, change.index, verify, 1, 1_000)
                            if (written != 1 || read != 1 || (verify[0].toInt() and 0xFF) != change.newPin) {
                                mutationFailure = "clock selector SET_CUR/verification failed"; break
                            }
                        }
                        if (mutationFailure == null && sourceHostProgrammable && oldSourceRate != sourceRate) {
                            sourceWriteAttempted = true
                            val rate = sourceRate.toInt()
                            val payload = byteArrayOf(rate.toByte(), (rate ushr 8).toByte(),
                                (rate ushr 16).toByte(), (rate ushr 24).toByte())
                            val written = controlTransfer.transfer(0x21, 0x01, 0x0100, sourceIndex, payload, 4, 1_000)
                            val verify = ByteArray(4)
                            val read = controlTransfer.transfer(0xA1, 0x01, 0x0100, sourceIndex, verify, 4, 1_000)
                            if (written != 4 || read != 4 || readU32(verify, 0) != sourceRate) {
                                mutationFailure = "clock source SET_CUR/verification failed"
                            } else {
                                sourceActiveRate = sourceRate
                            }
                        }
    
                    } catch (error: Exception) {
                        mutationFailure = error.message ?: error.javaClass.simpleName
                    }
                    if (mutationFailure != null) {
                        val rollbackErrors = restoreState()
                        if (rollbackErrors.isNotEmpty()) {
                            return Result.failure(IllegalStateException(
                                "FATAL: UAC2 clock negotiation failed (" + mutationFailure +
                                    "); rollback could not restore device state: " + rollbackErrors.joinToString()
                            ))
                        }
                        failures += mutationFailure
                        continue
                    }
                    safeUsbLogI("FloweryUsbNative", "UAC2_CLOCK_READY controlInterface=$controlInterfaceNumber sourceEntity=${path.sourceEntityId} requestedOutputHz=$sampleRate sourceRequiredHz=$sourceRate sourceCurrentHz=$oldSourceRate sourceActiveHz=$sourceActiveRate sourceWritable=$sourceHostProgrammable selectors=${path.selectors.size} multipliers=${path.multipliers.size}")
                    pendingClockRollback = { restoreState() }
                    return Result.success(Unit)
                }
                return Result.failure(IllegalStateException(
                    "No UAC2 clock path can produce " + sampleRate + " Hz: " + failures.joinToString()
                ))
            }

            var selectedClockSource = clockSourceEntityId
            var selectedClockControls = clockControls
            if (clockSelectorEntityId != 0) {
                if (selectorClockSources.isEmpty() ||
                    selectorClockSources.size != selectorSourceControls.size ||
                    selectorClockSources.any { it !in 1..255 } ||
                    !isUac2ControlPresent(clockSelectorControls)) {
                    return Result.failure(IllegalStateException("UAC2 Clock Selector descriptor has invalid inputs or controls"))
                }
                val selectorIndex = (clockSelectorEntityId shl 8) or controlInterfaceNumber
                val currentPin = ByteArray(1)
                val currentLength = controlTransfer.transfer(
                    0xA1, 0x01, 0x0100, selectorIndex, currentPin, 1, 1_000
                )
                if (currentLength != 1) {
                    return Result.failure(IllegalStateException("UAC2 Clock Selector GET_CUR failed: controlTransfer=$currentLength"))
                }
                val activePin = currentPin[0].toInt() and 0xFF
                if (activePin !in 1..selectorClockSources.size) {
                    return Result.failure(IllegalStateException("UAC2 Clock Selector returned invalid pin $activePin"))
                }
                val currentSource = selectorClockSources[activePin - 1]
                val chosenIndex = if (canNegotiateSource(currentSource, selectorSourceControls[activePin - 1], sampleRate)) {
                    activePin - 1
                } else {
                    selectorClockSources.indices.firstOrNull {
                        canNegotiateSource(selectorClockSources[it], selectorSourceControls[it], sampleRate)
                    }
                        ?: return Result.failure(IllegalArgumentException("No input of UAC2 Clock Selector $clockSelectorEntityId supports $sampleRate Hz"))
                }
                if (chosenIndex != activePin - 1) {
                    if (!isUac2ControlWritable(clockSelectorControls)) {
                        return Result.failure(IllegalStateException("UAC2 Clock Selector is read-only and its active clock does not support $sampleRate Hz"))
                    }
                    val requestedPin = byteArrayOf((chosenIndex + 1).toByte())
                    val written = controlTransfer.transfer(
                        0x21, 0x01, 0x0100, selectorIndex, requestedPin, 1, 1_000
                    )
                    if (written != 1) {
                        return Result.failure(IllegalStateException("UAC2 Clock Selector SET_CUR failed: controlTransfer=$written"))
                    }
                    val verified = ByteArray(1)
                    val verifiedLength = controlTransfer.transfer(
                        0xA1, 0x01, 0x0100, selectorIndex, verified, 1, 1_000
                    )
                    if (verifiedLength != 1 || (verified[0].toInt() and 0xFF) != chosenIndex + 1) {
                        return Result.failure(IllegalStateException("UAC2 Clock Selector did not confirm the requested input"))
                    }
                }
                selectedClockSource = selectorClockSources[chosenIndex]
                selectedClockControls = selectorSourceControls[chosenIndex]
            }
            if (selectedClockSource !in 1..255 || selectedClockControls !in 0..255) {
                return Result.failure(IllegalStateException("UAC2 Clock Source has no usable frequency control"))
            }

            var sourceSampleRate = sampleRate
            if (clockMultiplierEntityId != 0) {
                if ((clockMultiplierControls and 0x01) == 0 || (clockMultiplierControls and 0x04) == 0) {
                    return Result.failure(IllegalStateException("UAC2 Clock Multiplier does not expose readable numerator and denominator controls"))
                }
                val multiplierIndex = (clockMultiplierEntityId shl 8) or controlInterfaceNumber
                fun readMultiplierControl(selector: Int): Long? {
                    val value = ByteArray(4)
                    val length = controlTransfer.transfer(
                        0xA1, 0x01, selector shl 8, multiplierIndex, value, value.size, 1_000
                    )
                    return if (length == 4) readU32(value, 0) else null
                }
                val numerator = readMultiplierControl(0x01)
                    ?: return Result.failure(IllegalStateException("UAC2 Clock Multiplier numerator GET_CUR failed"))
                val denominator = readMultiplierControl(0x02)
                    ?: return Result.failure(IllegalStateException("UAC2 Clock Multiplier denominator GET_CUR failed"))
                if (numerator == 0L || denominator == 0L) {
                    return Result.failure(IllegalStateException("UAC2 Clock Multiplier returned a zero numerator or denominator"))
                }
                val requiredSourceRate = sampleRate.toLong() * denominator
                if (requiredSourceRate % numerator != 0L) {
                    return Result.failure(IllegalArgumentException("Requested $sampleRate Hz cannot be represented exactly by Clock Multiplier $clockMultiplierEntityId ratio $numerator/$denominator"))
                }
                val calculatedSourceRate = requiredSourceRate / numerator
                if (calculatedSourceRate !in 1..Int.MAX_VALUE.toLong()) {
                    return Result.failure(IllegalArgumentException("UAC2 Clock Multiplier requires an unsupported source frequency"))
                }
                sourceSampleRate = calculatedSourceRate.toInt()
            }

            if (!supportsRate(selectedClockSource, sourceSampleRate)) {
                return Result.failure(IllegalArgumentException("USB DAC Clock Source $selectedClockSource does not advertise required source frequency $sourceSampleRate Hz for output $sampleRate Hz"))
            }

            val index = (selectedClockSource shl 8) or controlInterfaceNumber
            val current = ByteArray(4)
            val currentLength = controlTransfer.transfer(
                0xA1, 0x01, 0x0100, index, current, current.size, 1_000
            )
            if (currentLength != 4) {
                return Result.failure(IllegalStateException("UAC2 Clock Source GET_CUR failed: controlTransfer=$currentLength"))
            }
            val currentRate = readU32(current, 0)
            if (currentRate == sourceSampleRate.toLong()) {
                safeUsbLogI("FloweryUsbNative", "UAC2_CLOCK_READY controlInterface=$controlInterfaceNumber sourceEntity=$selectedClockSource requestedOutputHz=$sampleRate requiredSourceHz=$sourceSampleRate sourceCurrentHz=$currentRate sourceWritable=false")
                return Result.success(Unit)
            }
            if (!isUac2ControlWritable(selectedClockControls)) {
                return Result.failure(IllegalStateException("UAC2 Clock Source is read-only at $currentRate Hz; requested $sourceSampleRate Hz"))
            }

            val payload = byteArrayOf(
                (sourceSampleRate and 0xFF).toByte(),
                ((sourceSampleRate ushr 8) and 0xFF).toByte(),
                ((sourceSampleRate ushr 16) and 0xFF).toByte(),
                ((sourceSampleRate ushr 24) and 0xFF).toByte()
            )
            val written = controlTransfer.transfer(0x21, 0x01, 0x0100, index, payload, payload.size, 1_000)
            if (written != payload.size) {
                return Result.failure(IllegalStateException("UAC2 Clock Source SET_CUR failed: controlTransfer=$written"))
            }
            val verified = ByteArray(4)
            val verifiedLength = controlTransfer.transfer(0xA1, 0x01, 0x0100, index, verified, verified.size, 1_000)
            if (verifiedLength != 4 || readU32(verified, 0) != sourceSampleRate.toLong()) {
                val actual = if (verifiedLength == 4) readU32(verified, 0).toString() else "unavailable"
                return Result.failure(IllegalStateException("UAC2 Clock Source did not confirm $sourceSampleRate Hz after SET_CUR (current=$actual)"))
            }
            safeUsbLogI("FloweryUsbNative", "UAC2_CLOCK_READY controlInterface=$controlInterfaceNumber sourceEntity=$selectedClockSource requestedOutputHz=$sampleRate requiredSourceHz=$sourceSampleRate previousSourceHz=$currentRate verifiedSourceHz=${readU32(verified, 0)} sourceWritable=true")
            Result.success(Unit)
        } catch (error: Exception) {
            safeUsbLogE("FloweryUsbNative", "UAC2_CLOCK_NEGOTIATION_FAILED requestedOutputHz=$sampleRate", error)
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
        dataFormat: Int,
        feedbackEndpointAddress: Int,
        feedbackMaxPacketSize: Int,
        initialPositionMs: Long = 0L
    ): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB session is closed"))
        return try {
            val result = NativeAudio.nativeStartUsbIsochronousPlayback(
                nativeHandle, decoderHandle, endpointAddress, rawMaxPacketSize,
                interval, sampleRate, channels,
                validBits, subslotBytes, dataFormat, feedbackEndpointAddress,
                feedbackMaxPacketSize, initialPositionMs
            )
            safeUsbLogI("FloweryUsbNative", "NATIVE_START result=$result sampleRate=$sampleRate channels=$channels validBits=$validBits subslotBytes=$subslotBytes dataFormat=$dataFormat endpoint=0x${endpointAddress.toString(16)} rawMaxPacketSize=$rawMaxPacketSize interval=$interval feedbackEndpoint=0x${feedbackEndpointAddress.toString(16)} feedbackPacketBytes=$feedbackMaxPacketSize initialPositionMs=$initialPositionMs")
            if (result == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("Native USB playback failed to start (result=$result)"))
        } catch (error: Exception) {
            safeUsbLogE("FloweryUsbNative", "NATIVE_START_EXCEPTION sampleRate=$sampleRate endpoint=0x${endpointAddress.toString(16)}", error)
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

    /** Discard the pre-start clock snapshot once native playback has started. */
    @Synchronized
    fun commitClockNegotiation() {
        pendingClockRollback = null
    }

    /** Restore the previous UAC2 clock state if native stream startup failed. */
    @Synchronized
    fun rollbackClockNegotiation(): Result<Unit> {
        val rollback = pendingClockRollback ?: return Result.success(Unit)
        pendingClockRollback = null
        return try {
            val errors = rollback()
            if (errors.isEmpty()) Result.success(Unit)
            else Result.failure(IllegalStateException(
                "FATAL: unable to restore UAC2 clock state after playback startup failure: " +
                    errors.joinToString()
            ))
        } catch (error: Exception) {
            Result.failure(IllegalStateException(
                "FATAL: UAC2 clock rollback threw after playback startup failure", error
            ))
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
    fun stopPlayback(): Result<Unit> {
        if (closed) return Result.success(Unit)
        return try {
            val result = NativeAudio.nativeStopUsbPlayback(nativeHandle)
            if (result == 0) Result.success(Unit)
            else Result.failure(IllegalStateException("Native USB playback stop failed"))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        safeUsbLogI("FloweryUsbNative", "SESSION_CLOSE_BEGIN nativeHandle=$nativeHandle")
        val safelyClosed = try {
            NativeAudio.nativeCloseUsbAudioSession(nativeHandle) == 0
        } catch (error: Throwable) {
            connection?.let(unsafeConnections::add)
            throw IllegalStateException(
                "Native USB transfers could not be drained; retaining the Android USB connection to prevent closing an fd still used by libusb",
                error
            )
        }
        if (safelyClosed) {
            // libusb_wrap_sys_device does not own or close this descriptor.
            safeUsbLogI("FloweryUsbNative", "SESSION_CLOSE_NATIVE_DRAINED nativeHandle=$nativeHandle; closing retained Android USB connection")
            connection?.close()
        } else {
            safeUsbLogE("FloweryUsbNative", "SESSION_CLOSE_UNSAFE nativeHandle=$nativeHandle; retaining Android USB connection")
            connection?.let(unsafeConnections::add)
            throw IllegalStateException(
                "Native USB transfers could not be drained; retaining the Android USB connection to prevent closing an fd still used by libusb"
            )
        }
    }

    companion object {
        // Deliberately retain connections whose native transfer teardown was unsafe.
        // Closing their fd could invalidate a still-submitted libusb transfer.
        private val unsafeConnections = java.util.concurrent.CopyOnWriteArrayList<UsbDeviceConnection>()

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            interfaceNumber: Int,
            alternateSetting: Int
        ): Result<UsbLibusbSession> {
            if (!manager.hasPermission(device)) {
                return Result.failure(SecurityException("USB permission has not been granted"))
            }
            safeUsbLogI("FloweryUsbNative", "SESSION_OPEN_BEGIN device=${device.deviceName} vendorId=${device.vendorId} productId=${device.productId} interface=$interfaceNumber alternate=$alternateSetting")
            val connection = manager.openDevice(device)
                ?: return Result.failure<UsbLibusbSession>(IllegalStateException("Unable to open USB device")).also { _: Result<UsbLibusbSession> -> safeUsbLogE("FloweryUsbNative", "ANDROID_OPEN_DEVICE_FAILED device=${device.deviceName}") }
            val fd = connection.fileDescriptor
            safeUsbLogI("FloweryUsbNative", "ANDROID_OPEN_DEVICE_OK device=${device.deviceName} fdValid=${fd >= 0}")
            if (fd < 0) {
                connection.close()
                return Result.failure(IllegalStateException("Android returned an invalid USB file descriptor"))
            }
            // Detach the kernel audio driver (snd-usb-audio) from the selected
            // AudioStreaming interface. The same file descriptor is later wrapped by
            // libusb, so this claim is shared with the native session and avoids
            // LIBUSB_ERROR_BUSY. Native code additionally requests auto-detach.
            val usbInterface = (0 until device.interfaceCount)
                .asSequence()
                .map(device::getInterface)
                .firstOrNull { it.id == interfaceNumber && it.alternateSetting == alternateSetting }
            if (usbInterface == null) {
                safeUsbLogE("FloweryUsbNative", "USB_INTERFACE_NOT_FOUND interface=$interfaceNumber alternate=$alternateSetting")
            } else {
                val claimed = connection.claimInterface(usbInterface, true)
                safeUsbLogI("FloweryUsbNative", "USB_INTERFACE_CLAIM interface=$interfaceNumber alternate=$alternateSetting claimed=$claimed")
                if (!claimed) {
                    safeUsbLogE("FloweryUsbNative", "ANDROID_CONNECTION_CLOSE reason=claim-interface-failed device=${device.deviceName}")
                    connection.close()
                    return Result.failure(IllegalStateException("Unable to claim USB AudioStreaming interface $interfaceNumber"))
                }
            }
            return try {
                val handle = NativeAudio.nativeOpenUsbAudioSession(fd, interfaceNumber, alternateSetting)
                safeUsbLogI("FloweryUsbNative", "NATIVE_SESSION_OPEN_RESULT handle=$handle interface=$interfaceNumber alternate=$alternateSetting")
                if (handle <= 0) {
                    safeUsbLogE("FloweryUsbNative", "ANDROID_CONNECTION_CLOSE reason=native-open-invalid-handle device=${device.deviceName}")
                    connection.close()
                    Result.failure(IllegalStateException("Native libusb returned an invalid session (handle=$handle)"))
                } else {
                    Result.success(
                        UsbLibusbSession(
                            connection,
                            handle,
                            controlInterfaceClaimer = { number ->
                                val control = (0 until device.interfaceCount)
                                    .asSequence()
                                    .map(device::getInterface)
                                    .filter { it.id == number }
                                    .minByOrNull { it.alternateSetting }
                                val claimed = control != null && connection.claimInterface(control, true)
                                safeUsbLogI("FloweryUsbNative", "USB_CONTROL_INTERFACE_CLAIM interface=$number found=${control != null} claimed=$claimed")
                                claimed
                            }
                        )
                    )
                }
            } catch (error: Exception) {
                safeUsbLogE("FloweryUsbNative", "NATIVE_SESSION_OPEN_EXCEPTION device=${device.deviceName}", error)
                safeUsbLogE("FloweryUsbNative", "ANDROID_CONNECTION_CLOSE reason=native-open-exception device=${device.deviceName}")
                connection.close()
                Result.failure(error)
            }
        }
    }
}
