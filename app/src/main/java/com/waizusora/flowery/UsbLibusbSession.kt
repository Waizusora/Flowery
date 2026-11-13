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
    transferOverride: UsbControlTransfer? = null
) : Closeable {
    private val controlTransfer: UsbControlTransfer = transferOverride ?: UsbControlTransfer { requestType, request, value, index, buffer, length, timeoutMs ->
        val usbConnection = connection ?: throw IllegalStateException("USB control connection is unavailable")
        usbConnection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
    }
    private var closed = false
    private var pendingClockRollback: (() -> List<String>)? = null

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
        return try {
            fun supportsRate(sourceId: Int, rate: Int): Boolean {
                val entityIndex = (sourceId shl 8) or controlInterfaceNumber
                val range = ByteArray(2 + 12 * 32)
                val length = controlTransfer.transfer(0xA1, 0x02, 0x0100, entityIndex, range, range.size, 1_000)
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
                if (!supportsRate(sourceId, rate)) return false
                val sourceIndex = (sourceId shl 8) or controlInterfaceNumber
                val current = ByteArray(4)
                val length = controlTransfer.transfer(0xA1, 0x01, 0x0100, sourceIndex, current, 4, 1_000)
                if (length != 4) return false
                return readU32(current, 0) == rate.toLong() || (controls and 0x03) == 0x02
            }
            if (clockPaths.isNotEmpty()) {
                val failures = mutableListOf<String>()
                for (path in clockPaths) {
                    if (path.sourceEntityId !in 1..255 || (path.sourceControls and 0x03) !in 1..2 ||
                        !path.hasConsistentTopology()) {
                        failures += "invalid source controls"
                        continue
                    }
                    var sourceRate = sampleRate.toLong()
                    var valid = true
                    for (multiplier in path.multipliers) {
                        if (multiplier.entityId !in 1..255 || multiplier.inputClockId !in 1..255 ||
                            (multiplier.controls and 0x01) == 0 ||
                            (multiplier.controls and 0x04) == 0) {
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
                        failures += "source " + path.sourceEntityId + " does not advertise " + sourceRate + " Hz"
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
                    if (oldSourceRate != sourceRate && (path.sourceControls and 0x03) != 2) {
                        failures += "source frequency is read-only"; continue
                    }

                    data class SelectorChange(val index: Int, val oldPin: Int, val newPin: Int)
                    val selectorChanges = mutableListOf<SelectorChange>()
                    for (selector in path.selectors) {
                        if (selector.entityId !in 1..255 || selector.inputSources.isEmpty() ||
                            selector.inputSources.any { it !in 1..255 } ||
                            selector.selectedPin !in 1..selector.inputSources.size ||
                            (selector.controls and 0x03) !in 1..2) {
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
                            if ((selector.controls and 0x03) != 2) {
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
                        if (mutationFailure == null && oldSourceRate != sourceRate) {
                            sourceWriteAttempted = true
                            val rate = sourceRate.toInt()
                            val payload = byteArrayOf(rate.toByte(), (rate ushr 8).toByte(),
                                (rate ushr 16).toByte(), (rate ushr 24).toByte())
                            val written = controlTransfer.transfer(0x21, 0x01, 0x0100, sourceIndex, payload, 4, 1_000)
                            val verify = ByteArray(4)
                            val read = controlTransfer.transfer(0xA1, 0x01, 0x0100, sourceIndex, verify, 4, 1_000)
                            if (written != 4 || read != 4 || readU32(verify, 0) != sourceRate) {
                                mutationFailure = "clock source SET_CUR/verification failed"
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
                    (clockSelectorControls and 0x03) !in 1..2) {
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
                    if ((clockSelectorControls and 0x03) != 0x02) {
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
            if (selectedClockSource !in 1..255 || (selectedClockControls and 0x03) !in 1..2) {
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
            if (currentRate == sourceSampleRate.toLong()) return Result.success(Unit)
            if ((selectedClockControls and 0x03) != 0x02) {
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
            connection?.close()
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
