package com.waizusora.flowery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.json.JSONObject
import java.io.Closeable

/**
 * Owns one class-compliant USB Audio playback stream.
 *
 * The first transport iteration intentionally enables UAC1 only. UAC2 is
 * rejected until the clock-source entity is parsed and negotiated correctly.
 */
internal class UsbNativePlaybackSession private constructor(
    private val usb: UsbLibusbSession,
    private val decoderHandle: Long,
    private val sampleRate: Int,
    private val endpoint: Int,
    private val maxPacketSize: Int,
    private val interval: Int,
    private val channels: Int,
    private val validBits: Int,
    private val subslotBytes: Int
) : Closeable {
    private var closed = false

    fun start(): Result<Unit> {
        if (closed) return Result.failure(IllegalStateException("USB playback session is closed"))
        return usb.setUac1SampleRate(endpoint, sampleRate).fold(
            onSuccess = {
                usb.startPlayback(
                    decoderHandle, endpoint, maxPacketSize, interval,
                    sampleRate, channels, validBits, subslotBytes
                )
            },
            onFailure = { Result.failure(it) }
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

    override fun close() {
        if (closed) return
        closed = true
        usb.stopPlayback()
        usb.close()
    }

    companion object {
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
                    lastError = SecurityException("USB permission is required for ${device.productName ?: device.deviceName}")
                    continue
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
                if (plan.optBoolean("requires_uac2_clock_negotiation")) {
                    lastError = IllegalStateException("This DAC uses UAC2. Native playback is held back until its clock-source entity can be negotiated; no guessed clock ID is used.")
                    continue
                }

                val interfaceNumber = plan.optInt("interface_number", -1)
                val alternate = plan.optInt("alternate_setting", -1)
                val endpoint = plan.optInt("endpoint_address", -1)
                val packet = plan.optInt("max_packet_size", -1)
                val interval = plan.optInt("interval", -1)
                val subslot = plan.optInt("subslot_bytes", -1)
                val bits = plan.optInt("bit_resolution", -1)
                if (interfaceNumber !in 0..255 || alternate !in 1..255 ||
                    endpoint !in 1..127 || packet <= 0 || interval !in 1..16 ||
                    subslot !in 1..4 || bits !in 1..32
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
                        interval, channels, bits, subslot
                    )
                )
            }
            return Result.failure(lastError ?: IllegalStateException("No compatible USB DAC could be opened"))
        }
    }
}
