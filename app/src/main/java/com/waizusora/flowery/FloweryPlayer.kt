package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloweryPlayer(private val context: Context, private val onState: (String) -> Unit) {
    companion object {
        private const val DECODE_CHUNK_FRAMES = 8_192
        private const val USB_DECODE_CHUNK_FRAMES = 32_768
        // Decoded blocks buffered between the decoder thread and the USB writer thread.
        private const val USB_QUEUE_DEPTH_BLOCKS = 6
        // Blocks that must be queued before the first USB write, so the isochronous
        // window is never primed from an empty producer.
        private const val USB_PREBUFFER_BLOCKS = 3
        private const val USB_QUEUE_POLL_MS = 50L
        // A writer wait longer than this after prebuffering is reported as producer starvation.
        private const val USB_STARVATION_REPORT_MS = 5L
        private const val DUCK_GAIN = 0.25f
        private val USB_DIAGNOSTIC_LOCK = Any()
    }

    private fun recordUsbDiagnostic(event: String) {
        val preferences = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("usb_diagnostic_recording", false)) return
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
            .format(java.util.Date())
        val line = "${timestamp} ${event}".replace('\n', ' ').replace('\r', ' ')
        synchronized(USB_DIAGNOSTIC_LOCK) {
            val lines = preferences.getString("usb_direct_diagnostics", "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toMutableList()
            lines.add(line)
            preferences.edit().putString("usb_direct_diagnostics", lines.takeLast(1200).joinToString("\n")).apply()
        }
        android.util.Log.i("FloweryUsbDiag", line)
    }

    private fun usbHostDeviceSummary(): String {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return "usbHostDevices=unavailable"
        val devices = runCatching { manager.deviceList.values.toList() }.getOrDefault(emptyList())
        if (devices.isEmpty()) return "usbHostDevices=none"
        return devices.joinToString(prefix = "usbHostDevices=[", postfix = "]") { device ->
            val vendor = "%04X".format(java.util.Locale.ROOT, device.vendorId)
            val product = "%04X".format(java.util.Locale.ROOT, device.productId)
            "vid=$vendor,pid=$product,name=${device.productName ?: "unknown"}," +
                "manufacturer=${device.manufacturerName ?: "unknown"}," +
                "interfaces=${device.interfaceCount},path=${device.deviceName}"
        }
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)

    /**
     * One decoded, gain-adjusted block of negotiated signed integer PCM that is
     * handed from the decoder thread to the USB writer thread. [epoch] changes on every
     * seek so that the writer flushes the transport exactly once per discontinuity.
     */
    private class UsbPcmBlock(
        val pcm: ByteArray,
        val frames: Int,
        val epoch: Int,
        val startFrames: Long,
        val endOfStream: Boolean = false
    )

    @Volatile private var activeUsbDeviceKey: String? = null
    // True while a direct USB route permits per-DAC software volume. Non-unity gain
    // changes PCM samples and is therefore not source-bit-perfect.
    @Volatile private var usbSoftwareGainEnabled = false
    private val stopped = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var softwareVolume = 1f
    @Volatile private var outputDucked = false
    @Volatile private var bandGainsDb = FloatArray(31)
    @Volatile private var equalizerRevision = 0
    @Volatile private var outputSampleRate = 0
    @Volatile private var usbOutputBitDepth = 0
    @Volatile private var outputDurationMs = 0L
    @Volatile private var decoderHandle = 0L
    @Volatile private var usbPlaybackMode = false
    @Volatile private var usbSourceBitPerfectEligible = false
    @Volatile private var usbPositionFrames = 0L
    @Volatile private var paused = false
    private val pendingSeekMs = AtomicReference<Long?>(null)

    fun setEqualizerGains(gains: FloatArray) {
        require(gains.size == 31) { "The ISO equalizer requires 31 bands" }
        bandGainsDb = gains.copyOf()
        equalizerRevision++
    }

    fun setVolume(value: Float) {
        if (usbPlaybackMode && !usbSoftwareGainEnabled) {
            onState("Software volume is unavailable for the active USB output route.")
            return
        }
        val normalized = value.coerceIn(0f, 1f)
        val usbKey = activeUsbDeviceKey
        if (usbKey == null) {
            // Speaker, wired AUX/headsets, and Bluetooth follow Android's media stream.
            volumeController.setSystemVolume(normalized)
            softwareVolume = 1f
        } else {
            softwareVolume = normalized
            volumeController.setUsbVolume(usbKey, normalized)
            applyOutputVolume()
        }
        publishUsbPlaybackModeStatus()
    }

    private fun publishUsbPlaybackModeStatus() {
        if (!usbPlaybackMode || outputSampleRate <= 0) return
        if (usbSourceBitPerfectEligible) {
            val unchanged = softwareVolume >= 0.999f && !outputDucked
            onState(
                if (unchanged) {
                    "USB exclusive · direct PCM · unity gain · EQ bypassed"
                } else {
                    "USB exclusive · direct PCM · software gain active · not bit-perfect"
                }
            )
        } else {
            onState("USB exclusive · decoded PCM (not bit-perfect) · ${usbOutputBitDepth}-bit · ${outputSampleRate} Hz")
        }
    }

    fun setDucked(ducked: Boolean) {
        outputDucked = ducked
        applyOutputVolume()
        publishUsbPlaybackModeStatus()
    }

    private fun applyOutputVolume() {
        val duckGain = if (outputDucked) DUCK_GAIN else 1f
        NativeAudio.nativeSetOutputVolume(softwareVolume * duckGain)
    }

    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null) {
        val previousWorker = worker
        stop()
        stopped.set(false)
        val generation = playbackGeneration.incrementAndGet()
        worker = thread(name = "FloweryStreamPlayer") {
            runCatching {
                // Keep decoding below the real-time AAudio callback thread to reduce scheduler contention.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            }
            var descriptor: ParcelFileDescriptor? = null
            var handle = 0L
            try {
                previousWorker?.join(4_000L)
                check(previousWorker?.isAlive != true) {
                    "The previous playback session did not release its output device in time."
                }
                onState("Opening audio stream…")
                descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Unable to open the selected audio document")
                val extension = resolveAudioExtension(uri)
                val bitPerfectRequested = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getBoolean("usb_bit_perfect_enabled", false)
                recordUsbDiagnostic(
                    "PLAY_REQUEST extension=${extension} usbBitPerfectEnabled=${bitPerfectRequested} " +
                        "segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}"
                )
                val directPcmSupported = extension == "wav" || extension == "wave"
                if (bitPerfectRequested && directPcmSupported &&
                    segmentStartMs == 0L && segmentEndMs == null) {
                    recordUsbDiagnostic("ROUTE_SELECTED route=LIBUSB_WAV_DIRECT_PCM")
                    usbPlaybackMode = true
                    playUsbBitPerfect(descriptor, generation)
                    return@thread
                }
                if (bitPerfectRequested && !directPcmSupported) {
                    recordUsbDiagnostic("ROUTE_SELECTED route=LIBUSB_DECODED_PCM extension=${extension}")
                    onState("USB exclusive: preparing decoded PCM output for .$extension…")
                }

                handle = NativeAudio.nativeCreateStreamDecoder(descriptor.fd, extension)
                decoderHandle = handle
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(handle != 0L) { "The native decoder could not be created" }

                val streamInfo = NativeAudio.nativeGetStreamInfo(handle)
                val sourceRate = (streamInfo ushr 32).toInt()
                val channels = (streamInfo and 0xffff_ffffL).toInt()
                check(sourceRate > 0 && channels in 1..2) { "The decoder returned an invalid audio format" }

                if (bitPerfectRequested) {
                    recordUsbDiagnostic("DECODER_READY sampleRate=${sourceRate} channels=${channels} handleValid=${handle != 0L}")
                    usbPlaybackMode = true
                    playUsbDecodedDirect(
                        handle, sourceRate, channels, generation, segmentStartMs, segmentEndMs
                    )
                    return@thread
                }

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val usbDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val selectedDevice = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    usbDevices.firstOrNull { it.sampleRates?.isNotEmpty() == true }
                        ?: usbDevices.firstOrNull()
                } else {
                    null
                }
                val supportedRates = selectedDevice?.sampleRates?.filter { it > 0 }.orEmpty()
                val usbKey = selectedDevice?.let { output ->
                    // Only one USB audio DAC is supported at a time; use its VID/PID key
                    // so Android-routed and direct-libusb playback share one saved gain.
                    UsbDacController(context).audioDevices().singleOrNull()?.identity
                        ?: "${output.productName}|${output.id}|${output.type}"
                }
                activeUsbDeviceKey = usbKey
                if (usbKey == null) {
                    softwareVolume = 1f
                } else {
                    // A DAC without an exposed hardware-volume interface starts at 20%.
                    softwareVolume = volumeController.usbVolume(usbKey)
                }
                val deviceId = selectedDevice?.id ?: 0
                // Android mixer BIT_PERFECT is not used as a fallback. When the user enables
                // USB Bit-Perfect, playback has already taken the direct USB transport path.
                // Shared Android output is handled by Rust AAudio. USB exclusive playback
                // uses the separate libusb path above and never falls back to Android mixer mode.
                val outputFormat = 2 // AAUDIO_FORMAT_PCM_FLOAT
                val preferredRate = NativeAudio.nativeSelectPreferredSampleRate(sourceRate, supportedRates.toIntArray())
                val targetRate = NativeAudio.nativeSelectOutputSampleRate(
                    preferredRate, channels, deviceId, outputFormat, false
                )
                check(targetRate > 0) {
                    "Unable to negotiate the selected USB DAC sample rate. " +
                        "device=${selectedDevice?.productName ?: "unknown"}, deviceId=$deviceId, " +
                        "sourceRate=$sourceRate, preferredRate=$preferredRate, channels=$channels. " +
                        "The app did not fall back to the phone output."
                }

                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(NativeAudio.nativeConfigureStreamDecoder(handle, targetRate, bandGainsDb) == 0) {
                    "Unable to configure the streaming resampler and equalizer"
                }
                if (segmentStartMs > 0L) {
                    val actualStart = NativeAudio.nativeSeekStreamDecoder(handle, segmentStartMs)
                    check(actualStart >= 0L) { "The decoder could not seek to the CUE track start." }
                }
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                val outputRingCapacityFrames = NativeAudio.nativeRingCapacityFrames(targetRate)
                val opened = NativeAudio.nativeStartStreamOutput(
                    targetRate, channels,
                    softwareVolume,
                    deviceId,
                    requireExclusive = false, // Non-bit-perfect USB playback must use Android's SHARED mixer route.
                    outputFormat = outputFormat,
                    capacityFrames = outputRingCapacityFrames
                )
                if (opened != 0) {
                    val attempt = NativeAudio.nativeGetLastOutputOpenDiagnostics()
                    val modeName: (Long) -> String = { mode ->
                        when (mode) { 0L -> "EXCLUSIVE"; 1L -> "SHARED"; else -> "UNKNOWN($mode)" }
                    }
                    val failureStage = when (attempt.getOrNull(10)?.toInt()) {
                        0 -> "none"
                        1 -> "create_stream_builder"
                        2 -> "open_stream"
                        3 -> "sharing_mode_mismatch"
                        4 -> "pcm_format_mismatch"
                        else -> "unknown"
                    }
                    val details = if (attempt.size >= 11) {
                        "requestedDeviceId=${attempt[0]}, requestedMode=${modeName(attempt[1])}, " +
                            "requestedRate=${attempt[2]}, requestedChannels=${attempt[3]}, " +
                            "openResult=${attempt[4]}, actualDeviceId=${attempt[5]}, " +
                            "actualMode=${modeName(attempt[6])}, actualRate=${attempt[7]}, " +
                            "actualChannels=${attempt[8]}, actualFormat=${attempt[9]}, " +
                            "failureStage=$failureStage"
                    } else "native open diagnostics unavailable"
                    val device = selectedDevice?.let {
                        "product=${it.productName}, id=${it.id}, type=${it.type}, " +
                            "address=${runCatching { it.address }.getOrNull() ?: "unknown"}, " +
                            "rates=${it.sampleRates?.joinToString() ?: "unknown"}"
                    } ?: "no USB output device selected"
                    val usbHost = usbHostDeviceSummary()
                    val reason = if (opened == -10001 && deviceId != 0) {
                        "Android did not provide the required USB DAC EXCLUSIVE mode."
                    } else if (deviceId != 0) {
                        "Unable to open the Android shared USB audio output (error $opened)."
                    } else {
                        "Unable to open Android system SHARED output (error $opened)."
                    }
                    throw IllegalStateException("$reason $device; $usbHost; $details")
                }
                outputSampleRate = targetRate
                val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
                val segmentLengthMs = segmentEndMs?.let { (it - segmentStartMs).coerceAtLeast(0L) }
                outputDurationMs = segmentLengthMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
                val segmentFrameLimit = segmentLengthMs?.let { NativeAudio.nativeFramesAtMilliseconds(it, targetRate) }

                val routeName = if (deviceId != 0) "USB DAC" else "system output"
                val outputInfo = NativeAudio.nativeGetOutputDiagnostics()
                val sharingName = when (outputInfo.getOrNull(9)?.toInt()) {
                    0 -> "EXCLUSIVE"
                    1 -> "SHARED"
                    else -> "unknown sharing mode"
                }
                val actualDeviceId = outputInfo.getOrNull(8)?.toInt() ?: 0
                onState("Playing · Rust AAudio $sharingName · ${targetRate} Hz · ${channels} ch · $routeName (device $actualDeviceId)")

                // Keep at least 1.5 seconds of decoded PCM queued to tolerate scheduler and decoder delays.
                val lowWatermarkFrames = NativeAudio.nativePlaybackLowWatermarkFrames(
                    targetRate, outputRingCapacityFrames
                )
                var outputStarted = false
                var reachedEnd = false
                var eofSignalled = false
                var appliedEqualizerRevision = equalizerRevision
                while (!stopped.get() && playbackGeneration.get() == generation) {
                    val tuningResult = NativeAudio.nativeTuneOutputBuffer()
                    check(tuningResult == 0) {
                        "AAudio could not tune its output buffer after an underrun (error $tuningResult)"
                    }
                    pendingSeekMs.getAndSet(null)?.let { requested ->
                        val target = requested.coerceAtLeast(0L).let { if (outputDurationMs > 0L) it.coerceAtMost(outputDurationMs) else it }
                        val targetFrame = NativeAudio.nativeFramesAtMilliseconds(target, targetRate)
                        check(NativeAudio.nativeFlushStreamOutput(targetFrame, false) == 0) { "Unable to flush the AAudio output stream for seeking" }
                        outputStarted = false
                        val actual = NativeAudio.nativeSeekStreamDecoder(handle, segmentStartMs + target)
                        check(actual >= 0L) { "The decoder rejected the requested seek position" }
                        reachedEnd = segmentFrameLimit?.let { targetFrame >= it } ?: false
                        eofSignalled = false
                        appliedEqualizerRevision = equalizerRevision
                        onState("Seeking · ${actual / 1000}s")
                    }
                    if (reachedEnd) {
                        if (!outputStarted && !paused) {
                            check(NativeAudio.nativeStartStreamPlayback() == 0) {
                                "AAudio could not start after PCM prebuffering"
                            }
                            outputStarted = true
                        }
                        if (!eofSignalled) {
                            NativeAudio.nativeSetStreamEof()
                            eofSignalled = true
                        }
                        if (!NativeAudio.nativeIsOutputActive()) {
                            val errorCode = NativeAudio.nativeGetOutputError()
                            check(errorCode == 0) { "AAudio output failed (error $errorCode)" }
                            break
                        }
                        Thread.sleep(8)
                        continue
                    }
                    if (appliedEqualizerRevision != equalizerRevision) {
                        check(NativeAudio.nativeUpdateStreamGains(handle, bandGainsDb) == 0) { "Unable to update streaming equalizer" }
                        appliedEqualizerRevision = equalizerRevision
                    }
                    var pumpFrames = DECODE_CHUNK_FRAMES
                    if (segmentFrameLimit != null) {
                        val queuedFrames = NativeAudio.nativeGetOutputPositionFrames() +
                            NativeAudio.nativeGetStreamBufferedFrames().coerceAtLeast(0)
                        val remainingFrames = (segmentFrameLimit - queuedFrames).coerceAtLeast(0L)
                        if (remainingFrames == 0L) {
                            reachedEnd = true
                            continue
                        }
                        pumpFrames = minOf(pumpFrames.toLong(), remainingFrames).toInt()
                    }
                    val pumped = NativeAudio.nativePumpStreamChunk(handle, pumpFrames)
                    when {
                        pumped == -1 -> reachedEnd = true
                        pumped == 0 -> {
                            check(NativeAudio.nativeIsOutputActive()) {
                                "AAudio output stopped unexpectedly (error ${NativeAudio.nativeGetOutputError()})"
                            }
                            Thread.sleep(4)
                        }
                        pumped < 0 -> throw IllegalStateException("Rust AAudio streaming pump failed")
                    }
                    if (!outputStarted && !paused &&
                        NativeAudio.nativeGetStreamBufferedFrames() >= lowWatermarkFrames) {
                        check(NativeAudio.nativeStartStreamPlayback() == 0) {
                            "AAudio could not start after PCM prebuffering"
                        }
                        outputStarted = true
                    }
                    if (segmentFrameLimit != null && NativeAudio.nativeGetOutputPositionFrames() +
                        NativeAudio.nativeGetStreamBufferedFrames().coerceAtLeast(0) >= segmentFrameLimit) {
                        reachedEnd = true
                    }
                    while (outputStarted && !stopped.get() && playbackGeneration.get() == generation && pendingSeekMs.get() == null && NativeAudio.nativeGetStreamBufferedFrames() > lowWatermarkFrames) {
                        val tuningResult = NativeAudio.nativeTuneOutputBuffer()
                        check(tuningResult == 0) {
                            "AAudio could not tune its output buffer after an underrun (error $tuningResult)"
                        }
                        check(NativeAudio.nativeIsOutputActive()) {
                            "AAudio output stopped unexpectedly (error ${NativeAudio.nativeGetOutputError()})"
                        }
                        Thread.sleep(4)
                    }
                }

                if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                val usbEnabled = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getBoolean("usb_bit_perfect_enabled", false)
                if (usbEnabled) recordUsbDiagnostic("PLAYBACK_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (handle != 0L) {
                    if (decoderHandle == handle) decoderHandle = 0L
                    NativeAudio.nativeCloseStreamDecoder(handle)
                }
                if (playbackGeneration.get() == generation) NativeAudio.nativeStopOutput()
                if (playbackGeneration.get() == generation) {
                    activeUsbDeviceKey = null
                }
                descriptor?.close()
            }
        }
    }

    private fun resolveAudioExtension(uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val nameExtension = displayName.substringAfterLast('.', "").lowercase()
        if (nameExtension.isNotBlank()) return nameExtension

        val uriExtension = Uri.decode(uri.lastPathSegment.orEmpty())
            .substringAfterLast('.', "").lowercase()
        if (uriExtension.isNotBlank()) return uriExtension

        return when (context.contentResolver.getType(uri)?.lowercase()) {
            "audio/mp4", "video/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/flac" -> "flac"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/mpeg" -> "mp3"
            else -> ""
        }
    }

    /**
     * Sends Symphonia-decoded PCM directly to USB without Android's AudioTrack/AAudio
     * mixer. Float samples are quantized to the DAC's negotiated integer PCM format;
     * this is not source-bit-perfect passthrough.
     */
    private fun playUsbDecodedDirect(
        handle: Long,
        sourceRate: Int,
        channels: Int,
        generation: Int,
        segmentStartMs: Long,
        segmentEndMs: Long?
    ) {
        var session: UsbDacSession? = null
        var totalFramesSent = 0L
        var chunkCount = 0L
        var producerThread: Thread? = null
        var queueHandle = 0L
        var producerHandle = 0L
        val producerStop = AtomicBoolean(false)
        val producerFailure = AtomicReference<Throwable?>(null)
        try {
            usbSourceBitPerfectEligible = false
            recordUsbDiagnostic("DIRECT_USB_ENTER sampleRate=${sourceRate} channels=${channels} segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}")
            onState("USB exclusive: validating decoded source format…")
            check(channels == 2) { "Direct USB decoded playback currently requires a stereo source." }
            recordUsbDiagnostic("SOURCE_VALIDATED format=decoded_float channels=${channels} sampleRate=${sourceRate}")
            val controller = UsbDacController(context)
            onState("USB exclusive: discovering compatible UAC2 DACs…")
            val discovered = controller.audioDevices()
            recordUsbDiagnostic("USB_ENUMERATION audioDeviceCount=${discovered.size} devices=${discovered.joinToString { it.identity + ":" + it.displayName }}")
            onState("USB exclusive: negotiating a supported stereo PCM format at ${sourceRate} Hz…")
            recordUsbDiagnostic("SESSION_OPEN_BEGIN bitDepth=auto sampleRate=${sourceRate} channels=${channels}")
            val (selectedDevice, usbSession) =
                controller.openFirstCompatiblePlaybackSession(discovered, 0, sourceRate, allowSampleRateConversion = true)
            val device = selectedDevice.usbDevice
            session = usbSession
            recordUsbDiagnostic("DAC_SELECTED vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} name=${device.productName ?: "unknown"} interfaces=${device.interfaceCount} identity=${selectedDevice.identity}")
            recordUsbDiagnostic("SESSION_OPEN_OK interface=${usbSession.profile.interfaceNumber} alternate=${usbSession.profile.alternateSetting} endpoint=${usbSession.profile.endpointHex} channels=${usbSession.profile.channels} bitResolution=${usbSession.profile.bitResolution} subslotBytes=${usbSession.profile.subslotBytes} maxPacketBytes=${usbSession.profile.maxPacketBytes} interval=${usbSession.profile.interval} synchronization=${usbSession.profile.synchronization} sampleRate=${usbSession.sampleRate}")
            check(usbSession.profile.channels == channels && usbSession.sampleRate > 0) {
                "The negotiated DAC profile does not match the decoded PCM stream."
            }
            onState("USB exclusive: decoding and processing PCM (not bit-perfect)…")
            val decoderConfigResult = NativeAudio.nativeConfigureStreamDecoder(handle, usbSession.sampleRate, bandGainsDb)
            recordUsbDiagnostic("DECODER_CONFIG result=${decoderConfigResult} sourceRate=${sourceRate} outputRate=${usbSession.sampleRate} eqActive=${bandGainsDb.any { abs(it) > 0.001f }}")
            check(decoderConfigResult == 0) {
                "Unable to configure the streaming decoder and DSP for direct USB output."
            }
            if (segmentStartMs > 0L) {
                val actualStart = NativeAudio.nativeSeekStreamDecoder(handle, segmentStartMs)
                check(actualStart >= 0L) { "The decoder could not seek to the CUE track start." }
            }
            recordUsbDiagnostic("DECODER_CONFIGURED outputFormat=PCM_FLOAT_TO_S${usbSession.profile.bitResolution}_LE")
            onState("USB exclusive: DAC session ready; decoding first PCM chunk…")
            outputSampleRate = usbSession.sampleRate
            usbOutputBitDepth = usbSession.profile.bitResolution
            val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
            val segmentDurationMs = segmentEndMs?.let {
                (it - segmentStartMs).coerceAtLeast(0L)
            }
            outputDurationMs = segmentDurationMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
            usbPositionFrames = 0L
            activeUsbDeviceKey = controller.deviceIdentity(device)
            softwareVolume = volumeController.usbVolume(activeUsbDeviceKey!!)
            usbSoftwareGainEnabled = true
            recordUsbDiagnostic(
                "USB_PCM_GAIN initialGain=${"%.3f".format(java.util.Locale.US, softwareVolume)} " +
                    "queueDepthBlocks=${USB_QUEUE_DEPTH_BLOCKS} prebufferBlocks=${USB_PREBUFFER_BLOCKS} " +
                    "blockFrames=${USB_DECODE_CHUNK_FRAMES}"
            )

            // The writer (this thread) must never wait on decoding. Rust produces packed PCM blocks
            // into its bounded queue; this Kotlin thread only drains blocks into the libusb
            // session so the isochronous transfer window can be refilled promptly.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
            queueHandle = NativeAudio.nativeCreateUsbPcmQueue(USB_QUEUE_DEPTH_BLOCKS)
            check(queueHandle != 0L) { "Unable to create the native USB PCM queue." }
            producerHandle = NativeAudio.nativeCreateUsbPcmProducer(
                handle, queueHandle, usbSession.sampleRate, channels, usbSession.profile.bitResolution,
                usbSession.profile.subslotBytes, segmentStartMs, segmentEndMs ?: -1L
            )
            check(producerHandle != 0L) { "Unable to create the Rust direct USB producer." }
            val endReached = AtomicBoolean(false)
            producerThread = thread(name = "FloweryUsbProducerControl") {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                }
                runUsbDecodeProducer(producerHandle, generation, endReached, producerStop, producerFailure)
            }

            fun playbackActive() = !stopped.get() && playbackGeneration.get() == generation

            // Prebuffer: do not prime the USB window until enough decoded audio is ready.
            val prebufferStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
            while (playbackActive() && producerFailure.get() == null && !endReached.get() &&
                NativeAudio.nativeUsbPcmQueueSize(queueHandle) < USB_PREBUFFER_BLOCKS) {
                Thread.sleep(5)
            }
            recordUsbDiagnostic(
                "USB_PREBUFFER_READY queuedBlocks=${NativeAudio.nativeUsbPcmQueueSize(queueHandle)} endReached=${endReached.get()} " +
                    "waitMs=${"%.2f".format(java.util.Locale.US,
                        (android.os.SystemClock.elapsedRealtimeNanos() - prebufferStartedNs) / 1_000_000.0)}"
            )

            var firstTransfer = true
            var activeEpoch = 0
            var starvationEvents = 0L
            while (playbackActive()) {
                if (paused) { Thread.sleep(10); continue }
                producerFailure.get()?.let { throw it }
                val waitStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val encodedBlock = NativeAudio.nativePollUsbPcmBlock(queueHandle, USB_QUEUE_POLL_MS.toInt()) ?: continue
                val block = decodeUsbPcmBlock(encodedBlock)
                val queueWaitMs = (android.os.SystemClock.elapsedRealtimeNanos() - waitStartedNs) / 1_000_000.0
                if (!firstTransfer && queueWaitMs > USB_STARVATION_REPORT_MS) {
                    starvationEvents++
                    recordUsbDiagnostic(
                        "USB_PRODUCER_STARVED waitMs=${"%.2f".format(java.util.Locale.US, queueWaitMs)} " +
                            "events=${starvationEvents}"
                    )
                }
                if (block.endOfStream) {
                    recordUsbDiagnostic("DECODER_EOF chunks=${chunkCount} totalFramesSent=${totalFramesSent}")
                    break
                }
                if (block.epoch != activeEpoch) {
                    // A seek happened: drop audio that is still queued on the endpoint.
                    usbSession.flush()
                    activeEpoch = block.epoch
                    usbPositionFrames = block.startFrames
                }
                val frames = block.frames
                if (firstTransfer) onState("USB exclusive: sending first " + frames + "-frame PCM chunk…")
                val writeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val acceptedFrames = usbSession.writePcm(block.pcm, frames)
                val transportDurationNs =
                    (android.os.SystemClock.elapsedRealtimeNanos() - writeStartedNs).coerceAtLeast(0L)
                val audioDurationMs = frames * 1_000.0 / usbSession.sampleRate
                val transportDurationMs = transportDurationNs / 1_000_000.0
                val throughputRatio = if (transportDurationNs > 0L) {
                    frames.toDouble() * 1_000_000_000.0 / (usbSession.sampleRate * transportDurationNs)
                } else {
                    Double.POSITIVE_INFINITY
                }
                recordUsbDiagnostic(
                    "USB_WRITE chunk=${chunkCount + 1} requestedFrames=${frames} " +
                        "acceptedFrames=${acceptedFrames} pcmBytes=${block.pcm.size} " +
                        "audioMs=${"%.2f".format(java.util.Locale.US, audioDurationMs)} " +
                        "transportMs=${"%.2f".format(java.util.Locale.US, transportDurationMs)} " +
                        "throughputRatio=${"%.3f".format(java.util.Locale.US, throughputRatio)} " +
                        "queuedBlocks=${NativeAudio.nativeUsbPcmQueueSize(queueHandle)}"
                )
                check(acceptedFrames == frames) {
                    "The USB DAC transport accepted ${acceptedFrames} of ${frames} frames."
                }
                chunkCount++
                totalFramesSent += acceptedFrames
                if (chunkCount % 32L == 0L) recordUsbDiagnostic("USB_TRANSFER_PROGRESS chunks=${chunkCount} totalFramesSent=${totalFramesSent} starvationEvents=${starvationEvents}")
                usbPositionFrames += frames
                if (firstTransfer) {
                    firstTransfer = false
                    val dspActive = bandGainsDb.any { abs(it) > 0.001f }
                    onState("USB exclusive · decoded PCM (not bit-perfect) · " + usbSession.profile.bitResolution + "-bit · " + sourceRate + " Hz" +
                        if (dspActive) " · EQ/DSP active" else " · EQ flat")
                }
            }
            producerFailure.get()?.let { if (playbackActive()) throw it }
            if (!stopped.get() && playbackGeneration.get() == generation) usbSession.drain()
            recordUsbDiagnostic("DIRECT_USB_FINISHED chunks=${chunkCount} totalFramesSent=${totalFramesSent} stopped=${stopped.get()}")
            if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
        } catch (error: Throwable) {
            recordUsbDiagnostic("DIRECT_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"} causeType=${error.cause?.javaClass?.simpleName ?: "none"} cause=${error.cause?.message ?: "none"}")
            throw error
        } finally {
            // The producer owns the native decoder handle until it has exited; the caller
            // closes that handle immediately after this method returns.
            producerStop.set(true)
            if (queueHandle != 0L) NativeAudio.nativeCloseUsbPcmQueue(queueHandle)
            joinUninterruptibly(producerThread)
            if (producerHandle != 0L) NativeAudio.nativeDestroyUsbPcmProducer(producerHandle)
            if (queueHandle != 0L) NativeAudio.nativeDestroyUsbPcmQueue(queueHandle)
            usbSoftwareGainEnabled = false
            try { session?.close() } finally {
                usbPlaybackMode = false
                activeUsbDeviceKey = null
                outputSampleRate = 0
                usbOutputBitDepth = 0
            }
        }
    }

    /** Waits for [thread] to exit before releasing native decoder or queue handles. */
    private fun joinUninterruptibly(thread: Thread?) {
        if (thread == null) return
        var interrupted = false
        while (thread.isAlive) {
            try { thread.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Producer coordinator for the decoded direct USB path. Rust decodes a bounded chunk,
     * applies the per-DAC gain ramp, packs the negotiated PCM format, and stores it in the native
     * bounded queue. Seek requests are serviced here because this thread is the sole user
     * of the decoder [handle] while it runs.
     *
     * Failures are reported through [failure]; the writer rethrows them on its own thread.
     */
    /**
     * Android owns this worker thread only to coordinate cancellation and seek requests.
     * Segment bounds, decoder seeking, PCM packing, block metadata, epochs, and queue
     * publication are owned by the Rust producer.
     */
    private fun runUsbDecodeProducer(
        producerHandle: Long,
        generation: Int,
        endReached: AtomicBoolean,
        stop: AtomicBoolean,
        failure: AtomicReference<Throwable?>
    ) {
        fun active() = !stop.get() && !stopped.get() && playbackGeneration.get() == generation
        try {
            while (active()) {
                pendingSeekMs.getAndSet(null)?.let { requested ->
                    NativeAudio.nativeRequestUsbPcmProducerSeek(producerHandle, requested.coerceAtLeast(0L))
                    endReached.set(false)
                }
                when (NativeAudio.nativePumpUsbPcmProducer(producerHandle, currentUsbGain())) {
                    1 -> Unit
                    0 -> Thread.sleep(2)
                    -1 -> {
                        endReached.set(true)
                        Thread.sleep(20)
                    }
                    else -> throw IllegalStateException("The Rust direct USB producer failed.")
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (active()) {
                recordUsbDiagnostic(
                    "USB_PRODUCER_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}"
                )
                failure.compareAndSet(null, error)
            }
        }
    }

    /** Software gain applied on the decoded USB path: per-DAC volume times the ducking gain. */
    private fun currentUsbGain(): Float =
        (softwareVolume * if (outputDucked) DUCK_GAIN else 1f).coerceIn(0f, 1f)

    private fun decodeUsbPcmBlock(encoded: ByteArray): UsbPcmBlock {
        require(encoded.size >= 17) { "The native USB PCM queue returned an invalid block header." }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val endOfStream = buffer.get().toInt() != 0
        val epoch = buffer.int
        val frames = buffer.int
        val startFrames = buffer.long
        val pcm = ByteArray(buffer.remaining())
        buffer.get(pcm)
        require(frames >= 0 && (endOfStream || frames > 0)) { "The native USB PCM queue returned invalid frame metadata." }
        return UsbPcmBlock(pcm, frames, epoch, startFrames, endOfStream)
    }

    private fun playUsbBitPerfect(descriptor: ParcelFileDescriptor, generation: Int) {
        var wavHandle = 0L
        var session: UsbDacSession? = null
        try {
            onState("Opening direct USB PCM path…")
            wavHandle = NativeAudio.nativeOpenWavPcm(descriptor.fd)
            check(wavHandle != 0L) { "Rust could not open the WAV PCM source." }
            val info = NativeAudio.nativeGetWavPcmInfo(wavHandle)
            check(info.size == 4) { "Rust returned invalid WAV PCM metadata." }
            val sampleRate = info[0].toInt()
            val bitDepth = info[1].toInt()
            val channels = info[2].toInt()
            val totalFrames = info[3]
            check(sampleRate > 0 && channels == 2 && bitDepth in setOf(16, 24, 32) && totalFrames >= 0L) {
                "Rust returned an unsupported WAV PCM format."
            }

            val controller = UsbDacController(context)
            val (selectedDevice, usbSession) = controller.openFirstCompatiblePlaybackSession(
                controller.audioDevices(), bitDepth, sampleRate,
                requiredSubslotBytes = bitDepth / 8
            )
            val device = selectedDevice.usbDevice
            session = usbSession
            check(usbSession.profile.channels == channels && usbSession.profile.bitResolution == bitDepth) {
                "The selected DAC alternate setting does not match the source PCM format."
            }

            outputSampleRate = sampleRate
            usbOutputBitDepth = bitDepth
            outputDurationMs = totalFrames * 1_000L / sampleRate
            usbPositionFrames = 0L
            activeUsbDeviceKey = controller.deviceIdentity(device)
            softwareVolume = volumeController.usbVolume(activeUsbDeviceKey!!)
            usbSoftwareGainEnabled = true
            usbSourceBitPerfectEligible = true
            var firstTransfer = true

            while (!stopped.get() && playbackGeneration.get() == generation) {
                if (paused) {
                    Thread.sleep(10)
                    continue
                }

                pendingSeekMs.getAndSet(null)?.let { requested ->
                    val targetFrame = NativeAudio.nativeFramesAtMilliseconds(requested, sampleRate)
                    usbPositionFrames = NativeAudio.nativeSeekWavPcmFrame(wavHandle, targetFrame)
                    check(usbPositionFrames >= 0L) { "Rust could not seek within the WAV PCM source." }
                    usbSession.flush()
                }

                val pcm = NativeAudio.nativeReadWavPcmFrames(
                    wavHandle, USB_DECODE_CHUNK_FRAMES,
                    softwareVolume * (if (outputDucked) DUCK_GAIN else 1f)
                )
                if (pcm.isEmpty()) break
                val frameBytes = channels * (bitDepth / 8)
                check(pcm.size % frameBytes == 0) { "Rust returned an incomplete WAV PCM frame." }
                val frames = pcm.size / frameBytes
                check(usbSession.writePcm(pcm, frames) == frames) {
                    "The USB transport did not accept the complete PCM chunk."
                }
                usbPositionFrames += frames
                if (firstTransfer) {
                    firstTransfer = false
                    val sourceSamplesUnchanged = softwareVolume >= 0.999f && !outputDucked
                    onState(
                        if (sourceSamplesUnchanged) {
                            "USB exclusive · direct PCM · unity gain · EQ bypassed"
                        } else {
                            "USB exclusive · direct PCM · software gain active · not bit-perfect"
                        }
                    )
                }
            }

            if (!stopped.get() && playbackGeneration.get() == generation) {
                usbSession.drain()
                onState("Playback finished")
            }
        } finally {
            usbSoftwareGainEnabled = false
            usbSourceBitPerfectEligible = false
            usbPlaybackMode = false
            try {
                session?.close()
            } finally {
                if (wavHandle != 0L) NativeAudio.nativeCloseWavPcm(wavHandle)
                activeUsbDeviceKey = null
                outputSampleRate = 0
            }
        }
    }

    fun positionMs(): Long =
        if (usbPlaybackMode && outputSampleRate > 0) usbPositionFrames * 1000L / outputSampleRate
        else if (outputSampleRate > 0) NativeAudio.nativeGetOutputPositionFrames() * 1000L / outputSampleRate
        else 0L

    fun durationMs(): Long = outputDurationMs

    fun seekTo(positionMs: Long) {
        if (decoderHandle == 0L && !usbPlaybackMode) return
        pendingSeekMs.set(positionMs.coerceAtLeast(0L))
    }

    fun pause() {
        if (usbPlaybackMode) {
            paused = true
            onState("Paused · USB exclusive")
        } else if (NativeAudio.nativePauseOutput() == 0) {
            paused = true
            onState("Paused")
        }
    }

    fun resume() {
        if (usbPlaybackMode) {
            paused = false
            onState("Playing · USB exclusive")
        } else if (NativeAudio.nativeResumeOutput() == 0) {
            paused = false
            onState("Playing · AAudio streaming")
        }
    }

    fun stopAndJoin(timeoutMs: Long = 4_000L): Boolean {
        val activeWorker = worker
        stop()
        if (activeWorker == null || activeWorker === Thread.currentThread()) return true
        return try {
            activeWorker.join(timeoutMs.coerceAtLeast(0L))
            !activeWorker.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        stopped.set(true)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        // Keep the worker reference until a later start or shutdown can join it.
        outputDurationMs = 0L
        outputSampleRate = 0
        usbOutputBitDepth = 0
        pendingSeekMs.set(null)
        paused = false
        usbPlaybackMode = false
        usbSourceBitPerfectEligible = false
        usbPositionFrames = 0L
        activeUsbDeviceKey = null
    }
}
