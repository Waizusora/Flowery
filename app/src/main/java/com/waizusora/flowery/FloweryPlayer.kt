package com.waizusora.flowery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

class FloweryPlayer(private val context: Context, private val onState: (String) -> Unit) {
    companion object {
        private const val MIN_OUTPUT_RING_CAPACITY_FRAMES = 32_768
        private const val DECODE_CHUNK_FRAMES = 8_192
        private val USB_DIAGNOSTIC_LOCK = Any()
    }

    private fun recordUsbDiagnostic(event: String) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
            .format(java.util.Date())
        val line = "${timestamp} ${event}".replace('\n', ' ').replace('\r', ' ')
        synchronized(USB_DIAGNOSTIC_LOCK) {
            val preferences = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
            val lines = preferences.getString("usb_direct_diagnostics", "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toMutableList()
            lines.add(line)
            preferences.edit().putString("usb_direct_diagnostics", lines.takeLast(300).joinToString("\n")).apply()
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

    private fun ringCapacityFrames(sampleRate: Int): Int {
        val required = maxOf(sampleRate.toLong() * 2L, MIN_OUTPUT_RING_CAPACITY_FRAMES.toLong())
        var capacity = 1L
        while (capacity < required) capacity = capacity shl 1
        return capacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)
    @Volatile private var activeBitPerfectDevice: AudioDeviceInfo? = null
    @Volatile private var activeBitPerfectAudioAttributes: AudioAttributes? = null

    private data class UsbMixerSelection(
        val sampleRate: Int,
        val aaudioFormat: Int,
        val mixerAttributes: AudioMixerAttributes,
        val audioAttributes: AudioAttributes,
        val formatDescription: String
    )

    private fun configureAndroidUsbBitPerfect(
        audioManager: AudioManager,
        device: AudioDeviceInfo,
        sourceRate: Int,
        channels: Int
    ): UsbMixerSelection {
        check(android.os.Build.VERSION.SDK_INT >= 34) {
            "Android USB BIT_PERFECT mixer mode requires Android 14 (API 34) or later. " +
                "This device cannot use the Android mixer-bypass route."
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val supported = runCatching { audioManager.getSupportedMixerAttributes(device) }
            .getOrElse { failure ->
                throw IllegalStateException(
                    "Unable to query USB DAC mixer formats for Android BIT_PERFECT: ${failure.message}"
                )
            }
        val encodingOptions = mapOf(
            AudioFormat.ENCODING_PCM_24BIT_PACKED to (3 to 0),
            AudioFormat.ENCODING_PCM_32BIT to (4 to 1),
            AudioFormat.ENCODING_PCM_16BIT to (1 to 2),
            AudioFormat.ENCODING_PCM_FLOAT to (2 to 3)
        )
        val candidates = supported.mapNotNull { mixer ->
            val format = mixer.format
            val encoding = encodingOptions[format.encoding] ?: return@mapNotNull null
            if (mixer.mixerBehavior != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT ||
                format.channelCount != channels || format.sampleRate <= 0) return@mapNotNull null
            Triple(mixer, encoding.first, encoding.second)
        }.sortedWith(
            compareBy<Triple<AudioMixerAttributes, Int, Int>>(
                { kotlin.math.abs(it.first.format.sampleRate - sourceRate) },
                { it.third }
            )
        )
        val supportedSummary = supported.joinToString(separator = "; ") { mixer ->
            "behavior=${mixer.mixerBehavior},rate=${mixer.format.sampleRate}," +
                "channels=${mixer.format.channelCount},encoding=${mixer.format.encoding}"
        }
        val selected = candidates.firstOrNull() ?: throw IllegalStateException(
            "USB DAC does not expose a compatible Android BIT_PERFECT mixer format " +
                "for $channels channel(s). Supported mixer attributes: " +
                (supportedSummary.ifBlank { "none" })
        )
        val mixerAttributes = selected.first
        check(audioManager.setPreferredMixerAttributes(audioAttributes, device, mixerAttributes)) {
            "Android rejected USB BIT_PERFECT mixer attributes. " +
                "device=${device.productName}, rate=${mixerAttributes.format.sampleRate}, " +
                "channels=$channels, encoding=${mixerAttributes.format.encoding}"
        }
        val applied = audioManager.getPreferredMixerAttributes(audioAttributes, device)
        check(applied != null &&
            applied.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            applied.format.sampleRate == mixerAttributes.format.sampleRate &&
            applied.format.channelCount == channels &&
            applied.format.encoding == mixerAttributes.format.encoding) {
            "Android did not retain the requested USB BIT_PERFECT mixer attributes. " +
                "requested=$mixerAttributes, applied=$applied"
        }
        activeBitPerfectDevice = device
        activeBitPerfectAudioAttributes = audioAttributes
        val encodingName = when (mixerAttributes.format.encoding) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24_PACKED"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM_32"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            else -> "UNKNOWN"
        }
        return UsbMixerSelection(
            sampleRate = mixerAttributes.format.sampleRate,
            aaudioFormat = selected.second,
            mixerAttributes = mixerAttributes,
            audioAttributes = audioAttributes,
            formatDescription = encodingName
        )
    }

    private fun clearAndroidUsbBitPerfect() {
        val device = activeBitPerfectDevice
        val attributes = activeBitPerfectAudioAttributes
        if (device != null && attributes != null &&
            android.os.Build.VERSION.SDK_INT >= 34) {
            runCatching {
                recordUsbDiagnostic("ROUTE_SELECTED route=ANDROID_AAUDIO reason=usbBitPerfectDisabled")
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.clearPreferredMixerAttributes(attributes, device)
            }
        }
        activeBitPerfectDevice = null
        activeBitPerfectAudioAttributes = null
    }
    @Volatile private var activeUsbDeviceKey: String? = null
    private val stopped = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var softwareVolume = 1f
    @Volatile private var outputDucked = false
    @Volatile private var bandGainsDb = FloatArray(31)
    @Volatile private var equalizerRevision = 0
    @Volatile private var outputSampleRate = 0
    @Volatile private var outputDurationMs = 0L
    @Volatile private var decoderHandle = 0L
    @Volatile private var usbPlaybackMode = false
    @Volatile private var usbPositionFrames = 0L
    @Volatile private var paused = false
    private val pendingSeekMs = AtomicReference<Long?>(null)

    fun setEqualizerGains(gains: FloatArray) {
        require(gains.size == 31) { "The ISO equalizer requires 31 bands" }
        bandGainsDb = gains.copyOf()
        equalizerRevision++
    }

    fun setVolume(value: Float) {
        if (usbPlaybackMode) {
            onState("Direct USB bit-perfect playback keeps digital gain at unity. Use the DAC hardware volume control.")
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
    }

    fun setDucked(ducked: Boolean) {
        outputDucked = ducked
        applyOutputVolume()
    }

    private fun applyOutputVolume() {
        val duckGain = if (outputDucked) 0.25f else 1f
        NativeAudio.nativeSetOutputVolume(softwareVolume * duckGain)
    }

    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null) {
        val previousWorker = worker
        stop()
        stopped.set(false)
        val generation = playbackGeneration.incrementAndGet()
        if (context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                .getBoolean("usb_bit_perfect_enabled", false)) {
            context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE).edit()
                .putString("usb_direct_diagnostics", "").apply()
        }
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
                    recordUsbDiagnostic("ROUTE_SELECTED route=LIBUSB_WAV_BIT_PERFECT")
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
                val usbKey = selectedDevice?.let {
                    "${it.productName}|${it.id}|${it.type}"
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
                val mixerSelection: UsbMixerSelection? = null
                val outputFormat = mixerSelection?.aaudioFormat ?: 2 // AAUDIO_FORMAT_PCM_FLOAT
                val preferredRate = mixerSelection?.sampleRate ?: if (sourceRate in supportedRates) sourceRate
                    else supportedRates.minByOrNull { kotlin.math.abs(it - sourceRate) } ?: sourceRate
                val targetRate = NativeAudio.nativeSelectOutputSampleRate(
                    preferredRate, channels, deviceId, outputFormat, mixerSelection != null
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
                val outputRingCapacityFrames = ringCapacityFrames(targetRate)
                val opened = NativeAudio.nativeStartStreamOutput(
                    targetRate, channels,
                    softwareVolume,
                    deviceId,
                    requireExclusive = deviceId != 0 && mixerSelection == null,
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
                    val reason = if (opened == -10001 && deviceId != 0 && mixerSelection == null) {
                        "Android did not provide the required USB DAC EXCLUSIVE mode."
                    } else if (deviceId != 0) {
                        "Unable to open the Android USB BIT_PERFECT mixer output (error $opened)."
                    } else {
                        "Unable to open Android system SHARED output (error $opened)."
                    }
                    throw IllegalStateException("$reason $device; $usbHost; $details")
                }
                outputSampleRate = targetRate
                val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
                val segmentLengthMs = segmentEndMs?.let { (it - segmentStartMs).coerceAtLeast(0L) }
                outputDurationMs = segmentLengthMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
                val segmentFrameLimit = segmentLengthMs?.let { framesAtMilliseconds(it, targetRate) }

                val routeName = if (deviceId != 0) "USB DAC" else "system output"
                val outputInfo = NativeAudio.nativeGetOutputDiagnostics()
                val sharingName = when (outputInfo.getOrNull(9)?.toInt()) {
                    0 -> "EXCLUSIVE"
                    1 -> "SHARED"
                    else -> "unknown sharing mode"
                }
                val actualDeviceId = outputInfo.getOrNull(8)?.toInt() ?: 0
                val mixerMode = mixerSelection?.let {
                    " · Android BIT_PERFECT mixer · ${it.formatDescription}"
                }.orEmpty()
                onState("Playing · AAudio $sharingName$mixerMode · ${targetRate} Hz · ${channels} ch · $routeName (device $actualDeviceId)")

                // Keep at least 1.5 seconds of decoded PCM queued to tolerate scheduler and decoder delays.
                val lowWatermarkFrames = (targetRate.toLong() * 3L / 2L)
                    .coerceAtLeast(MIN_OUTPUT_RING_CAPACITY_FRAMES.toLong() / 2L)
                    .coerceAtMost((outputRingCapacityFrames * 3L) / 4L)
                    .toInt()
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
                        val targetFrame = framesAtMilliseconds(target, targetRate)
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
                    val chunk = NativeAudio.nativeReadStreamChunk(handle, DECODE_CHUNK_FRAMES)
                    if (chunk.isEmpty()) {
                        reachedEnd = true
                        continue
                    }
                    var frames = chunk.size / channels
                    var outputChunk = chunk
                    if (segmentFrameLimit != null) {
                        val queuedFrames = NativeAudio.nativeGetOutputPositionFrames() +
                            NativeAudio.nativeGetStreamBufferedFrames().coerceAtLeast(0)
                        val remainingFrames = (segmentFrameLimit - queuedFrames).coerceAtLeast(0L)
                        if (remainingFrames == 0L) {
                            reachedEnd = true
                            continue
                        }
                        if (frames.toLong() > remainingFrames) {
                            frames = remainingFrames.toInt()
                            outputChunk = chunk.copyOf(frames * channels)
                        }
                    }
                    var offsetFrames = 0
                    while (!stopped.get() && playbackGeneration.get() == generation && offsetFrames < frames && pendingSeekMs.get() == null) {
                        val written = NativeAudio.nativeWriteStreamFrames(
                            outputChunk, offsetFrames, frames - offsetFrames
                        )
                        if (written < 0) throw IllegalStateException("AAudio ring-buffer write failed")
                        if (written == 0) {
                            check(NativeAudio.nativeIsOutputActive()) {
                                "AAudio output stopped unexpectedly (error ${NativeAudio.nativeGetOutputError()})"
                            }
                            Thread.sleep(4)
                        }
                        else offsetFrames += written
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
                    clearAndroidUsbBitPerfect()
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
     * mixer. Float samples are quantized to the DAC's 24-bit integer transport format;
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
        try {
            recordUsbDiagnostic("DIRECT_USB_ENTER sampleRate=${sourceRate} channels=${channels} segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}")
            onState("USB exclusive: validating decoded source format…")
            check(channels == 2) { "Direct USB decoded playback currently requires a stereo source." }
            recordUsbDiagnostic("SOURCE_VALIDATED format=decoded_float channels=${channels} sampleRate=${sourceRate}")
            val controller = UsbDacController(context)
            onState("USB exclusive: detecting KT Micro DAC…")
            val discovered = controller.audioDevices()
            recordUsbDiagnostic("USB_ENUMERATION audioDeviceCount=${discovered.size} devices=${discovered.joinToString { it.identity + ":" + it.displayName }}")
            val candidates = discovered.filter { it.isReferenceKtmicro }
            check(candidates.size == 1) { "Direct USB playback requires exactly one connected KT Micro KT-384/32 HIFI (found " + candidates.size + ")." }
            val device = candidates.single().usbDevice
            recordUsbDiagnostic("DAC_SELECTED vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} name=${device.productName ?: "unknown"} interfaces=${device.interfaceCount}")
            onState("USB exclusive: checking DAC permission…")
            val permissionGranted = controller.hasPermission(device)
            recordUsbDiagnostic("USB_PERMISSION granted=${permissionGranted}")
            check(permissionGranted) { "USB permission is unavailable. Grant access to the connected DAC first." }
            onState("USB exclusive: reading descriptors and negotiating " + sourceRate + " Hz clock…")
            recordUsbDiagnostic("SESSION_OPEN_BEGIN bitDepth=24 sampleRate=${sourceRate} channels=${channels}")
            val usbSession = controller.openExclusivePlaybackSession(device, 24, sourceRate)
            session = usbSession
            recordUsbDiagnostic("SESSION_OPEN_OK interface=${usbSession.profile.interfaceNumber} alternate=${usbSession.profile.alternateSetting} endpoint=${usbSession.profile.endpointHex} channels=${usbSession.profile.channels} bitResolution=${usbSession.profile.bitResolution} subslotBytes=${usbSession.profile.subslotBytes} sampleRate=${usbSession.sampleRate}")
            check(usbSession.profile.channels == channels && usbSession.profile.bitResolution == 24 &&
                usbSession.sampleRate == sourceRate) {
                "The DAC profile does not match stereo 24-bit PCM at $sourceRate Hz."
            }
            onState("USB exclusive: configuring Symphonia decoder and DSP…")
            val decoderConfigResult = NativeAudio.nativeConfigureStreamDecoder(handle, sourceRate, bandGainsDb)
            recordUsbDiagnostic("DECODER_CONFIG result=${decoderConfigResult} sampleRate=${sourceRate} eqActive=${bandGainsDb.any { abs(it) > 0.001f }}")
            check(decoderConfigResult == 0) {
                "Unable to configure the streaming decoder and DSP for direct USB output."
            }
            if (segmentStartMs > 0L) {
                val actualStart = NativeAudio.nativeSeekStreamDecoder(handle, segmentStartMs)
                check(actualStart >= 0L) { "The decoder could not seek to the CUE track start." }
            }
            recordUsbDiagnostic("DECODER_CONFIGURED outputFormat=PCM_FLOAT_TO_S24_LE")
            onState("USB exclusive: DAC session ready; decoding first PCM chunk…")
            outputSampleRate = sourceRate
            val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
            val segmentDurationMs = segmentEndMs?.let {
                (it - segmentStartMs).coerceAtLeast(0L)
            }
            outputDurationMs = segmentDurationMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
            var segmentRemainingFrames = segmentDurationMs?.let {
                framesAtMilliseconds(it, sourceRate)
            }
            usbPositionFrames = 0L
            activeUsbDeviceKey = controller.deviceIdentity(device)
            softwareVolume = volumeController.usbVolume(activeUsbDeviceKey!!)
            var firstTransfer = true
            while (!stopped.get() && playbackGeneration.get() == generation) {
                if (paused) { Thread.sleep(10); continue }
                pendingSeekMs.getAndSet(null)?.let { requested ->
                    val relativeMs = requested.coerceAtLeast(0L)
                        .coerceAtMost(segmentDurationMs ?: Long.MAX_VALUE)
                    val absoluteMs = segmentStartMs + relativeMs
                    val actual = NativeAudio.nativeSeekStreamDecoder(handle, absoluteMs)
                    check(actual >= 0L) { "The decoder could not seek to the requested position." }
                    usbPositionFrames = (actual - segmentStartMs).coerceAtLeast(0L) * sourceRate / 1_000L
                    segmentRemainingFrames = segmentEndMs?.let {
                        framesAtMilliseconds((it - absoluteMs).coerceAtLeast(0L), sourceRate)
                    }
                }
                if (segmentRemainingFrames == 0L) break
                val samples = NativeAudio.nativeReadStreamChunk(handle, DECODE_CHUNK_FRAMES)
                if (samples.isEmpty()) {
                    recordUsbDiagnostic("DECODER_EOF chunks=${chunkCount} totalFramesSent=${totalFramesSent}")
                    break
                }
                check(samples.size % channels == 0) { "The decoder returned an incomplete interleaved PCM frame." }
                val decodedFrames = samples.size / channels
                val frames = segmentRemainingFrames?.let { minOf(decodedFrames.toLong(), it).toInt() }
                    ?: decodedFrames
                if (frames <= 0) break
                segmentRemainingFrames = segmentRemainingFrames?.minus(frames)
                if (firstTransfer) onState("USB exclusive: sending first " + frames + "-frame PCM chunk…")
                val pcm = ByteArray(frames * channels * 3)
                var byteOffset = 0
                for (sampleIndex in 0 until frames * channels) {
                    val sample = samples[sampleIndex]
                    val value = if (sample <= -1f) -8_388_608 else (sample.coerceIn(-1f, 1f) * 8_388_607f).toInt()
                    pcm[byteOffset++] = value.toByte()
                    pcm[byteOffset++] = (value shr 8).toByte()
                    pcm[byteOffset++] = (value shr 16).toByte()
                }
                val acceptedFrames = usbSession.writePcm(pcm, frames)
                recordUsbDiagnostic("USB_WRITE chunk=${chunkCount + 1} requestedFrames=${frames} acceptedFrames=${acceptedFrames} pcmBytes=${pcm.size}")
                check(acceptedFrames == frames) {
                    "The USB DAC transport accepted ${acceptedFrames} of ${frames} frames."
                }
                chunkCount++
                totalFramesSent += acceptedFrames
                if (chunkCount % 32L == 0L) recordUsbDiagnostic("USB_TRANSFER_PROGRESS chunks=${chunkCount} totalFramesSent=${totalFramesSent}")
                usbPositionFrames += frames
                if (firstTransfer) {
                    firstTransfer = false
                    val dspActive = bandGainsDb.any { abs(it) > 0.001f }
                    onState("USB exclusive · decoded PCM 24-bit · " + sourceRate + " Hz" +
                        if (dspActive) " · EQ/DSP active" else " · EQ flat")
                }
            }
            recordUsbDiagnostic("DIRECT_USB_FINISHED chunks=${chunkCount} totalFramesSent=${totalFramesSent} stopped=${stopped.get()}")
            if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
        } catch (error: Throwable) {
            recordUsbDiagnostic("DIRECT_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
            throw error
        } finally {
            try { session?.close() } finally {
                usbPlaybackMode = false
                activeUsbDeviceKey = null
                outputSampleRate = 0
            }
        }
    }

    private fun playUsbBitPerfect(descriptor: ParcelFileDescriptor, generation: Int) {
        var source: WavPcmSource? = null
        var session: UsbDacSession? = null
        try {
            onState("Opening direct USB bit-perfect path…")
            val pcmSource = WavPcmSource.open(descriptor)
            source = pcmSource
            val controller = UsbDacController(context)
            val candidates = controller.audioDevices().filter { it.isReferenceKtmicro }
            check(candidates.size == 1) {
                "USB bit-perfect mode requires exactly one connected KT Micro KT-384/32 HIFI."
            }
            val device = candidates.single().usbDevice
            check(controller.hasPermission(device)) {
                "USB permission is unavailable. Tap the USB DAC control and grant access first."
            }

            val usbSession = controller.openExclusivePlaybackSession(
                device,
                pcmSource.bitDepth,
                pcmSource.sampleRate
            )
            session = usbSession
            check(usbSession.profile.channels == 2 && usbSession.profile.bitResolution == pcmSource.bitDepth) {
                "The selected DAC alternate setting does not match the source PCM format."
            }

            outputSampleRate = pcmSource.sampleRate
            outputDurationMs = pcmSource.totalFrames * 1_000L / pcmSource.sampleRate
            usbPositionFrames = 0L
            activeUsbDeviceKey = controller.deviceIdentity(device)
            var firstTransfer = true

            while (!stopped.get() && playbackGeneration.get() == generation) {
                if (paused) {
                    Thread.sleep(10)
                    continue
                }

                pendingSeekMs.getAndSet(null)?.let { requested ->
                    val targetFrame = framesAtMilliseconds(requested, pcmSource.sampleRate)
                    usbPositionFrames = pcmSource.seekToFrame(targetFrame)
                }

                val pcm = pcmSource.readFrames(DECODE_CHUNK_FRAMES)
                if (pcm.isEmpty()) break
                val frameBytes = usbSession.profile.channels * usbSession.profile.subslotBytes
                check(pcm.size % frameBytes == 0) { "PCM source returned an incomplete audio frame." }
                val frames = pcm.size / frameBytes
                check(usbSession.writePcm(pcm, frames) == frames) {
                    "The USB transport did not accept the complete PCM chunk."
                }
                usbPositionFrames += frames
                if (firstTransfer) {
                    firstTransfer = false
                    onState("USB exclusive bit-perfect · PCM ${pcmSource.bitDepth}-bit · ${pcmSource.sampleRate} Hz")
                }
            }

            if (!stopped.get() && playbackGeneration.get() == generation) {
                onState("Playback finished")
            }
        } finally {
            usbPlaybackMode = false
            try {
                session?.close()
            } finally {
                source?.close()
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

    private fun framesAtMilliseconds(milliseconds: Long, sampleRate: Int): Long {
        if (milliseconds <= 0L || sampleRate <= 0) return 0L
        val seconds = milliseconds / 1_000L
        val remainder = milliseconds % 1_000L
        if (seconds > Long.MAX_VALUE / sampleRate) return Long.MAX_VALUE
        val wholeFrames = seconds * sampleRate
        val fractionalFrames = remainder * sampleRate / 1_000L
        return if (wholeFrames > Long.MAX_VALUE - fractionalFrames) Long.MAX_VALUE
        else wholeFrames + fractionalFrames
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

    fun stop() {
        playbackGeneration.incrementAndGet()
        stopped.set(true)
        NativeAudio.nativeStopOutput()
        clearAndroidUsbBitPerfect()
        worker?.interrupt()
        worker = null
        outputDurationMs = 0L
        outputSampleRate = 0
        pendingSeekMs.set(null)
        paused = false
        usbPlaybackMode = false
        usbPositionFrames = 0L
        activeUsbDeviceKey = null
    }
}
