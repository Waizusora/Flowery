package com.waizusora.flowery

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.util.Collections
import java.util.IdentityHashMap

/**
 * JNI bridge to the native libusb USB Audio Class transport.
 *
 * PCM input must already be interleaved little-endian samples in the exact
 * encoding, subslot width, channel layout, and sample rate selected for the DAC.
 * Integer and IEEE Float32 payloads are passed to libusb without sample conversion.
 */
internal object UsbDacNative {
    init {
        System.loadLibrary("flowery_usb")
    }

    external fun nativeOpen(
        fileDescriptor: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpoint: Int,
        maxPacketBytes: Int,
        interval: Int,
        feedbackEndpoint: Int,
        feedbackMaxPacketBytes: Int,
        channels: Int,
        subslotBytes: Int,
        sampleRate: Int,
        clockId: Int,
        controlInterface: Int,
        protocol: Int,
        samplingFrequencyControl: Boolean
    ): Long

    external fun nativeOpenEncoded(
        fileDescriptor: Int,
        interfaceNumber: Int,
        alternateSetting: Int,
        endpoint: Int,
        maxPacketBytes: Int,
        interval: Int,
        framingKind: Int,
        encodedBytesPerSecond: Long,
        encodedFrameRate: Int,
        encodedFrameBytes: Int,
        sourceSampleRate: Int,
        samplingFrequencyControl: Boolean,
        feedbackEndpoint: Int,
        feedbackMaxPacketBytes: Int
    ): Long
    external fun nativeWriteEncodedPacket(handle: Long, encoded: ByteArray, payloadOffset: Int, payloadLength: Int, durationUs: Long): Int
    external fun nativeWriteIsochronous(handle: Long, pcm: ByteArray, frameCount: Int): Int
    external fun nativeDrain(handle: Long): Int
    external fun nativeFlush(handle: Long): Int
    external fun nativeRequestPause(handle: Long): Boolean
    external fun nativeRequestResume(handle: Long): Boolean
    external fun nativeRequestStop(handle: Long): Boolean
    external fun nativeClose(handle: Long): Boolean
}

/**
 * Owns the Android USB connection for the full lifetime of a claimed USB Audio Class
 * streaming interface. Calls must be made off the UI thread. A write returns
 * once the PCM has been staged and the bounded isochronous window has accepted
 * it, so up to roughly 256 ms of previously written audio may still be queued
 * on the endpoint. Use [drain] at natural end of playback and [flush] only when changing playback position.
 * Closing the session cancels queued transfers and releases native resources.
 */
internal class UsbDacSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice,
    private val nativeHandle: Long,
    val profile: UsbDacPlaybackProfile,
    val sampleRate: Int,
    private val volumeUnit: UsbFeatureUnit? = null
) : AutoCloseable {
    companion object {
        const val WRITE_STOPPED = -2

        private val pendingCloseLock = Any()
        private val pendingCloseSessions: MutableSet<UsbDacSession> =
            Collections.newSetFromMap(IdentityHashMap())

        /** Retries sessions retained after native or Android teardown failed. */
        internal fun retryPendingCloseSessions(): Boolean {
            val pending = synchronized(pendingCloseLock) { pendingCloseSessions.toList() }
            var allClosed = true
            pending.forEach { session ->
                if (runCatching { session.close() }.isFailure) allClosed = false
            }
            return allClosed
        }
    }

    private fun rememberPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.add(this) }
    }

    private fun forgetPendingClose() {
        synchronized(pendingCloseLock) { pendingCloseSessions.remove(this) }
    }

    val hardwareVolumeAvailable: Boolean get() = volumeUnit != null

    @Volatile
    private var lastVolumeControlDiagnostic: String = "not_run"

    @Volatile
    private var controlInterfaceClaimedBySession = false

    /** Last raw UAC control-transfer result, including setup packet and response bytes. */
    @Synchronized
    fun hardwareVolumeDiagnostic(): String {
        val unit = volumeUnit
        return buildString {
            append("feature_unit=")
            append(unit?.unitId ?: "none")
            append(" control_interface=")
            append(unit?.controlInterface ?: "none")
            append(" protocol=")
            append(unit?.protocol?.let { "0x%02X".format(it) } ?: "none")
            append(" range=")
            append(cachedVolumeRange?.let { "${it.first}..${it.second}" } ?: "unavailable")
            append(" control_claimed=")
            append(controlInterfaceClaimedBySession)
            append(" last_control={")
            append(lastVolumeControlDiagnostic)
            append("}")
        }
    }

    /**
     * Resolves the Audio Control [UsbInterface] by descriptor interface number
     * (bInterfaceNumber), not by the Android 0-based interface index.
     */
    private fun controlUsbInterface(): UsbInterface? {
        val unit = volumeUnit ?: return null
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            if (intf.id == unit.controlInterface) return intf
        }
        return null
    }

    /**
     * Ensures the Audio Control interface is claimed before class-specific
     * Feature Unit requests. Some UAC2 DACs stall GET_RANGE/GET_CUR until the
     * control interface is explicitly claimed by the host process.
     */
    private fun ensureControlInterfaceClaimed(): Boolean {
        if (controlInterfaceClaimedBySession) return true
        val intf = controlUsbInterface() ?: run {
            lastVolumeControlDiagnostic = "claim_control_interface failed: interface_not_found"
            return false
        }
        return try {
            val claimed = connection.claimInterface(intf, true)
            if (claimed) {
                controlInterfaceClaimedBySession = true
            }
            claimed
        } catch (error: Throwable) {
            lastVolumeControlDiagnostic =
                "claim_control_interface failed: ${error.javaClass.simpleName}:${error.message}"
            false
        }
    }

    private fun releaseControlInterfaceIfClaimed() {
        if (!controlInterfaceClaimedBySession) return
        val intf = controlUsbInterface()
        if (intf != null) {
            runCatching { connection.releaseInterface(intf) }
        }
        controlInterfaceClaimedBySession = false
    }

    private fun volumeControl(request: Int, data: ByteArray): Int {
        val unit = volumeUnit ?: run {
            lastVolumeControlDiagnostic = "error=no_feature_unit"
            return -1
        }
        ensureControlInterfaceClaimed()
        val value = 0x0200
        val index = (unit.unitId shl 8) or unit.controlInterface
        val requestType = if (request == 0x01) 0x21 else 0xA1
        val payloadBefore = data.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val transferred = try {
            connection.controlTransfer(requestType, request, value, index, data, data.size, 500)
        } catch (error: Throwable) {
            lastVolumeControlDiagnostic =
                ("bmRequestType=0x%02X bRequest=0x%02X wValue=0x%04X wIndex=0x%04X " +
                    "requestedLength=${data.size} timeoutMs=500 payloadBefore=$payloadBefore " +
                    "exception=${error.javaClass.simpleName}:${error.message}")
                    .format(requestType, request, value, index)
            throw error
        }
        val response = data.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        lastVolumeControlDiagnostic =
            ("bmRequestType=0x%02X bRequest=0x%02X wValue=0x%04X wIndex=0x%04X " +
                "requestedLength=${data.size} transferred=$transferred timeoutMs=500 " +
                "payloadBefore=$payloadBefore responseHex=$response")
                .format(requestType, request, value, index)
        return transferred
    }

    /**
     * Discovers the Feature Unit volume range using several strategies.
     *
     * Order of attempts:
     * 1. UAC2 GET_RANGE with a buffer large enough for 16 subranges (standard path).
     * 2. UAC2 GET_RANGE with a single-subrange buffer (some devices stall oversized reads).
     * 3. UAC1-style GET_MIN + GET_MAX even when the descriptor reports UAC2 protocol.
     *
     * A null result means no readable range was obtained; the session must not
     * treat the Feature Unit as a working hardware volume control.
     */
    private fun volumeRange(): Pair<Int, Int>? {
        if (volumeRangeLoaded) return cachedVolumeRange
        val unit = volumeUnit ?: return null

        val attempts = mutableListOf<String>()
        var parsed: Pair<Int, Int>? = null

        if (unit.protocol == 0x20) {
            // Strategy 1: full UAC2 GET_RANGE buffer (wNumSubRanges + up to 16 triplets).
            val fullRange = ByteArray(2 + 6 * 16)
            val fullTransferred = volumeControl(0x82, fullRange)
            attempts += "uac2_full(transferred=$fullTransferred)"
            parsed = UsbFeatureUnitVolumeRange.parseUac2(fullRange, fullTransferred)

            // Strategy 2: single-subrange buffer. Some firmware stalls large reads.
            if (parsed == null) {
                val singleRange = ByteArray(2 + 6)
                val singleTransferred = volumeControl(0x82, singleRange)
                attempts += "uac2_single(transferred=$singleTransferred)"
                parsed = UsbFeatureUnitVolumeRange.parseUac2(singleRange, singleTransferred)
            }
        }

        // Strategy 3: UAC1-style GET_MIN (0x82) / GET_MAX (0x83). Applied for UAC1
        // and as a fallback when UAC2 GET_RANGE is non-responsive.
        if (parsed == null) {
            val minimum = ByteArray(2)
            val maximum = ByteArray(2)
            val minTransferred = volumeControl(0x82, minimum)
            val maxTransferred = volumeControl(0x83, maximum)
            attempts += "uac1_minmax(min=$minTransferred,max=$maxTransferred)"
            if (minTransferred == 2 && maxTransferred == 2) {
                parsed = UsbFeatureUnitVolumeRange.parseUac1(minimum, maximum)
            }
        }

        // Optional liveness probe: record GET_CUR outcome for diagnostics even if
        // range discovery failed. Does not affect the cached range result.
        if (parsed == null) {
            val current = ByteArray(2)
            val curTransferred = volumeControl(0x81, current)
            attempts += "get_cur_probe(transferred=$curTransferred)"
        }

        volumeRangeLoaded = true
        cachedVolumeRange = parsed
        if (parsed == null) {
            lastVolumeControlDiagnostic =
                lastVolumeControlDiagnostic + "; range_attempts=${attempts.joinToString("|")}"
        }
        return parsed
    }

    @Synchronized
    fun readHardwareVolumeFraction(): Float? {
        check(!closed) { "USB DAC session is closed" }
        if (volumeUnit == null) return null
        val range = volumeRange() ?: return null
        val current = ByteArray(2)
        if (volumeControl(0x81, current) != 2) return null
        val raw = java.nio.ByteBuffer.wrap(current).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        return ((raw - range.first).toFloat() / (range.second - range.first).toFloat()).coerceIn(0f, 1f)
    }

    @Synchronized
    fun setHardwareVolumeFraction(fraction: Float): Boolean {
        check(!closed) { "USB DAC session is closed" }
        val range = volumeRange() ?: return false
        val raw = (range.first + (range.second - range.first) * fraction.coerceIn(0f, 1f)).toInt()
        val data = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(raw.toShort()).array()
        return volumeControl(0x01, data) == 2
    }
    @Volatile
    private var nativeClosed = false

    @Volatile
    private var connectionClosed = false

    @Volatile
    private var closed = false

    private var volumeRangeLoaded = false
    private var cachedVolumeRange: Pair<Int, Int>? = null

    @Synchronized
    fun writePcm(pcmLittleEndian: ByteArray, frameCount: Int): Int {
        check(!closed) { "USB DAC session is closed" }
        require(frameCount > 0) { "Frame count must be positive" }
        val expectedBytes = frameCount.toLong() * profile.channels * profile.subslotBytes
        require(expectedBytes == pcmLittleEndian.size.toLong()) {
            "PCM data must match the selected DAC channel count and subslot size"
        }
        return UsbDacNative.nativeWriteIsochronous(nativeHandle, pcmLittleEndian, frameCount)
    }

    // Deliberately unsynchronized: these requests must wake a writer blocked inside JNI.
    fun requestPause(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestPause(nativeHandle)
    fun requestResume(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestResume(nativeHandle)
    fun requestStop(): Boolean = !closed && !nativeClosed && UsbDacNative.nativeRequestStop(nativeHandle)

    /** Blocks until all queued audio, including a trailing partial packet, has been transmitted. */
    @Synchronized
    fun drain() {
        check(!closed) { "USB DAC session is closed" }
        requireUsbPcmDrainResult(UsbDacNative.nativeDrain(nativeHandle))
    }

    /** Discards staged and queued audio so playback can resume from a new position. */
    @Synchronized
    fun flush() {
        check(!closed) { "USB DAC session is closed" }
        check(UsbDacNative.nativeFlush(nativeHandle) == 0) {
            "USB transport could not cancel and reap all in-flight transfers for seeking"
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return

        // Native and Android ownership have separate completion points. Once
        // nativeClose succeeds, never retry it with the released native handle.
        if (!nativeClosed) {
            requestStop()
            var nativeCloseConfirmed = false
            for (attempt in 0 until 3) {
                if (UsbDacNative.nativeClose(nativeHandle)) {
                    nativeClosed = true
                    nativeCloseConfirmed = true
                    break
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(50L)
                    } catch (interrupted: InterruptedException) {
                        rememberPendingClose()
                        Thread.currentThread().interrupt()
                        throw IllegalStateException(
                            "Interrupted while waiting for USB transfers to finish teardown",
                            interrupted
                        )
                    }
                }
            }
            if (!nativeCloseConfirmed) {
                rememberPendingClose()
                throw IllegalStateException(
                    "libusb could not confirm that all in-flight transfers were reaped; " +
                        "the USB connection remains open for a safe retry"
                )
            }
        }

        releaseControlInterfaceIfClaimed()

        if (!connectionClosed) try {
            connection.close()
            connectionClosed = true
        } catch (error: Throwable) {
            rememberPendingClose()
            throw error
        }
        closed = true
        forgetPendingClose()
    }
}


/** Rejects native drain failures while allowing an intentional concurrent stop. */
internal fun requireUsbPcmDrainResult(result: Int) {
    check(result == 0 || result == UsbDacSession.WRITE_STOPPED) {
        "USB transport drain failed (status=$result)"
    }
}
