package com.waizusora.flowery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns one class-compliant USB Audio playback stream.
 *
 * UAC1 endpoint frequency and UAC2 Clock Source frequency are negotiated
 * before the native isochronous stream is started.
 */
internal fun startUsbPlaybackTransaction(
    negotiateClock: () -> Result<Unit>,
    startNativePlayback: () -> Result<Unit>,
    rollbackClock: () -> Result<Unit>,
    commitClock: () -> Unit,
    rollbackOnStartFailure: Boolean
): Result<Unit> {
    fun invoke(operation: () -> Result<Unit>, fallbackMessage: String): Result<Unit> =
        try {
            operation()
        } catch (error: Throwable) {
            Result.failure(error)
        }

    val negotiation = invoke(negotiateClock, "USB clock negotiation failed")
    if (negotiation.isFailure) return negotiation

    val started = invoke(startNativePlayback, "Native USB playback failed to start")
    if (started.isSuccess) {
        if (rollbackOnStartFailure) {
            try {
                commitClock()
            } catch (error: Throwable) {
                return Result.failure(IllegalStateException(
                    "Native USB playback started but clock transaction commit failed",
                    error
                ))
            }
        }
        return started
    }

    if (!rollbackOnStartFailure) return started

    val rollback = invoke(rollbackClock, "UAC2 clock rollback failed")
    if (rollback.isFailure) {
        return Result.failure(IllegalStateException(
            (started.exceptionOrNull()?.message ?: "Native USB playback failed to start") +
                "; " + (rollback.exceptionOrNull()?.message ?: "UAC2 clock rollback failed"),
            rollback.exceptionOrNull()
        ))
    }
    return started
}

internal class UsbNativePlaybackSession private constructor(
    private val usb: UsbLibusbSession,
    private val decoderHandle: Long,
    private val sampleRate: Int,
    private val endpoint: Int,
    private val maxPacketSize: Int,
    private val interval: Int,
    private val channels: Int,
    private val validBits: Int,
    private val subslotBytes: Int,
    private val feedbackEndpoint: Int,
    private val feedbackMaxPacketSize: Int,
    private val uac2ControlInterface: Int?,
    private val uac2ClockSourceEntity: Int?,
    private val uac2ClockControls: Int?,
    private val uac2ClockSelectorEntity: Int?,
    private val uac2ClockSelectorControls: Int?,
    private val uac2SelectorClockSources: IntArray,
    private val uac2SelectorSourceControls: IntArray,
    private val uac2ClockMultiplierEntity: Int?,
    private val uac2ClockMultiplierControls: Int?,
    private val uac2ClockPaths: List<Uac2ClockPath>
) : Closeable {
    private var closed = false

    fun start(initialPositionMs: Long = 0L): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB playback session is closed"))
        val negotiateClock: () -> Result<Unit> = if (uac2ClockSourceEntity != null) {
            val controlInterface = uac2ControlInterface
                ?: return Result.failure(IllegalStateException("UAC2 control interface is missing"))
            {
                usb.negotiateUac2Clock(
                    controlInterface,
                    uac2ClockSourceEntity,
                    uac2ClockControls ?: 0,
                    sampleRate,
                    uac2ClockSelectorEntity ?: 0,
                    uac2ClockSelectorControls ?: 0,
                    uac2SelectorClockSources,
                    uac2SelectorSourceControls,
                    uac2ClockMultiplierEntity ?: 0,
                    uac2ClockMultiplierControls ?: 0,
                    uac2ClockPaths
                )
            }
        } else {
            { usb.setUac1SampleRate(endpoint, sampleRate) }
        }
        return startUsbPlaybackTransaction(
            negotiateClock = negotiateClock,
            startNativePlayback = {
                usb.startPlayback(
                    decoderHandle, endpoint, maxPacketSize, interval,
                    sampleRate, channels, validBits, subslotBytes, feedbackEndpoint,
                    feedbackMaxPacketSize, initialPositionMs
                )
            },
            rollbackClock = { usb.rollbackClockNegotiation() },
            commitClock = { usb.commitClockNegotiation() },
            rollbackOnStartFailure = uac2ClockSourceEntity != null
        )
    }

    fun pause(): Result<Unit> = usb.pausePlayback()
    fun resume(): Result<Unit> = usb.resumePlayback()
    fun seek(positionMs: Long): Result<Long> = usb.seekPlayback(decoderHandle, positionMs)

    fun positionMs(): Long {
        if (closed || sampleRate <= 0) return 0L
        return runCatching {
            val status = JSONObject(usb.playbackStatus())
            status.optLong("positionFrames", 0L).coerceAtLeast(0L) * 1_000L / sampleRate
        }.getOrDefault(0L)
    }

    fun status(): JSONObject = JSONObject(usb.playbackStatus())

    fun stopPlayback() {
        if (closed) return
        usb.stopPlayback()
    }

    override fun close() {
        if (closed) return
        closed = true
        usb.stopPlayback()
        usb.close()
    }

    companion object {
        private fun requestUsbPermission(
            context: Context,
            manager: UsbManager,
            device: UsbDevice
        ): Boolean {
            val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
            val latch = CountDownLatch(1)
            var granted = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != action) return
                    val returnedDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (returnedDevice?.deviceName == device.deviceName) {
                        granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        latch.countDown()
                    }
                }
            }
            val filter = IntentFilter(action)
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            try {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val permissionIntent = PendingIntent.getBroadcast(
                    context, device.deviceId, Intent(action).setPackage(context.packageName), flags
                )
                manager.requestPermission(device, permissionIntent)
                latch.await(30, TimeUnit.SECONDS)
                return granted && manager.hasPermission(device)
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        fun open(
            context: Context,
            decoderHandle: Long,
            sampleRate: Int,
            channels: Int,
            sourceBits: Int
        ): Result<UsbNativePlaybackSession> {
            if (decoderHandle <= 0L || sampleRate <= 0 || channels !in 1..8 || sourceBits !in 1..32) {
                return Result.failure(IllegalArgumentException("Invalid USB playback source format"))
            }
            val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val candidates = manager.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any { index ->
                    val iface = device.getInterface(index)
                    iface.interfaceClass == 1 && iface.interfaceSubclass == 2 &&
                        (0 until iface.endpointCount).any { ep ->
                            val endpoint = iface.getEndpoint(ep)
                            endpoint.type == 1 && endpoint.direction == 0
                        }
                }
            }
            if (candidates.isEmpty()) {
                return Result.failure(IllegalStateException("No USB Audio playback device with an isochronous OUT endpoint is connected"))
            }

            var lastError: Throwable? = null
            for (device in candidates) {
                if (!manager.hasPermission(device)) {
                    val granted = requestUsbPermission(context, manager, device)
                    if (!granted) {
                        lastError = SecurityException("USB permission was not granted for ${device.productName ?: device.deviceName}")
                        continue
                    }
                }
                val probe = manager.openDevice(device)
                if (probe == null) {
                    lastError = IllegalStateException("Unable to open USB device ${device.deviceName}")
                    continue
                }
                val planJson = try {
                    JSONObject(
                        NativeAudio.nativePlanUsbAudioPlayback(
                            probe.rawDescriptors, sampleRate, channels, sourceBits
                        )
                    )
                } catch (error: Throwable) {
                    probe.close()
                    lastError = error
                    continue
                }
                probe.close()

                if (!planJson.optBoolean("ok")) {
                    lastError = IllegalStateException(planJson.optString("error", "USB DAC does not advertise the source PCM format"))
                    continue
                }
                val plan = planJson.optJSONObject("plan")
                if (plan == null) {
                    lastError = IllegalStateException("Native USB planner returned an incomplete playback plan")
                    continue
                }
                val interfaceNumber = plan.optInt("interface_number", -1)
                val alternate = plan.optInt("alternate_setting", -1)
                val endpoint = plan.optInt("endpoint_address", -1)
                val packet = plan.optInt("max_packet_size", -1)
                val interval = plan.optInt("interval", -1)
                val subslot = plan.optInt("subslot_bytes", -1)
                val bits = plan.optInt("bit_resolution", -1)
                val feedbackEndpoint = plan.optInt("feedback_endpoint_address", 0)
                val feedbackPacket = plan.optInt("feedback_max_packet_size", 0)
                val isUac2 = plan.optBoolean("requires_uac2_clock_negotiation")
                val clockControlInterface = if (isUac2) plan.optInt("uac2_control_interface_number", -1) else -1
                val clockSourceEntity = if (isUac2) plan.optInt("uac2_clock_source_entity_id", -1) else -1
                val clockControls = if (isUac2) plan.optInt("uac2_clock_controls", -1) else -1
                val clockSelectorEntity = if (isUac2) plan.optInt("uac2_clock_selector_entity_id", 0) else 0
                val clockSelectorControls = if (isUac2) plan.optInt("uac2_clock_selector_controls", 0) else 0
                val selectorSourcesJson = if (isUac2) plan.optJSONArray("uac2_selector_clock_sources") else null
                val selectorControlsJson = if (isUac2) plan.optJSONArray("uac2_selector_source_controls") else null
                val clockMultiplierEntity = if (isUac2) plan.optInt("uac2_clock_multiplier_entity_id", 0) else 0
                val clockMultiplierControls = if (isUac2) plan.optInt("uac2_clock_multiplier_controls", 0) else 0
                val clockPathsJson = if (isUac2) plan.optJSONArray("uac2_clock_paths") else null
                val clockPaths = mutableListOf<Uac2ClockPath>()
                if (clockPathsJson != null) for (pathIndex in 0 until clockPathsJson.length()) {
                    val pathObject = clockPathsJson.optJSONObject(pathIndex) ?: continue
                    val selectorsJson = pathObject.optJSONArray("selectors") ?: JSONArray()
                    val multipliersJson = pathObject.optJSONArray("multipliers") ?: JSONArray()
                    val selectors = (0 until selectorsJson.length()).mapNotNull { index ->
                        selectorsJson.optJSONObject(index)?.let { item ->
                            Uac2ClockSelectorStep(
                                item.optInt("entity_id", -1),
                                item.optInt("controls", -1),
                                item.optInt("selected_pin", -1),
                                (0 until (item.optJSONArray("input_sources")?.length() ?: 0)).map { sourceIndex ->
                                    item.optJSONArray("input_sources")!!.optInt(sourceIndex, -1)
                                }
                            )
                        }
                    }
                    val multipliers = (0 until multipliersJson.length()).mapNotNull { index ->
                        multipliersJson.optJSONObject(index)?.let { item ->
                            Uac2ClockMultiplierStep(
                                item.optInt("entity_id", -1),
                                item.optInt("controls", -1),
                                item.optInt("input_clock_id", -1)
                            )
                        }
                    }
                    clockPaths += Uac2ClockPath(
                        pathObject.optInt("source_entity_id", -1),
                        pathObject.optInt("source_controls", -1),
                        selectors,
                        multipliers,
                        (0 until (pathObject.optJSONArray("clock_entities")?.length() ?: 0)).map { entityIndex ->
                            pathObject.optJSONArray("clock_entities")!!.optInt(entityIndex, -1)
                        }
                    )
                }
                val selectorSources = IntArray(selectorSourcesJson?.length() ?: 0) { selectorSourcesJson!!.optInt(it, -1) }
                val selectorSourceControls = IntArray(selectorControlsJson?.length() ?: 0) { selectorControlsJson!!.optInt(it, -1) }
                val invalidClockPaths = isUac2 && (clockPaths.isEmpty() || clockPaths.any { path ->
                    path.sourceEntityId !in 1..255 || (path.sourceControls and 0x03) !in 1..2 ||
                        !path.hasConsistentTopology() ||
                        path.selectors.any {
                            it.entityId !in 1..255 ||
                                it.inputSources.isEmpty() ||
                                it.inputSources.any { sourceId -> sourceId !in 1..255 } ||
                                it.selectedPin !in 1..it.inputSources.size ||
                                (it.controls and 0x03) !in 1..2
                        } ||
                        path.multipliers.any {
                            it.entityId !in 1..255 || it.inputClockId !in 1..255 ||
                                (it.controls and 0x03) != 0x01 || (it.controls and 0x0C) != 0x04
                        }
                })
                val invalidUac2ClockPlan = isUac2 && (
                    clockControlInterface !in 0..255 ||
                        clockPaths.isEmpty() ||
                        invalidClockPaths
                    )
                if (interfaceNumber !in 0..255 || alternate !in 1..255 ||
                    endpoint !in 1..127 || packet <= 0 || interval !in 1..16 ||
                    subslot !in 1..4 || bits !in 1..32 ||
                    (feedbackEndpoint != 0 && (feedbackEndpoint !in 0x81..0x8F || feedbackPacket !in 3..4)) ||
                    invalidUac2ClockPlan
                ) {
                    lastError = IllegalStateException("Native USB planner returned invalid endpoint parameters")
                    continue
                }

                val opened = UsbLibusbSession.open(
                    manager, device, interfaceNumber, alternate
                )
                val native = opened.getOrNull()
                if (native == null) {
                    lastError = opened.exceptionOrNull()
                    continue
                }
                return Result.success(
                    UsbNativePlaybackSession(
                        native, decoderHandle, sampleRate, endpoint, packet,
                        interval, channels, bits, subslot, feedbackEndpoint, feedbackPacket,
                        if (isUac2) clockControlInterface else null,
                        if (isUac2) clockSourceEntity else null,
                        if (isUac2) clockControls else null,
                        if (isUac2 && clockSelectorEntity != 0) clockSelectorEntity else null,
                        if (isUac2) clockSelectorControls else null,
                        selectorSources,
                        selectorSourceControls,
                        if (isUac2 && clockMultiplierEntity != 0) clockMultiplierEntity else null,
                        if (isUac2) clockMultiplierControls else null,
                        clockPaths
                    )
                )
            }
            return Result.failure(lastError ?: IllegalStateException("No compatible USB DAC could be opened"))
        }
    }
}
