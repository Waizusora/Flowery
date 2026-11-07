package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

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
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)
    private val diagnostics = UsbPlaybackDiagnostics(context)
    private val sourceResolver = AudioSourceResolver(context)

    @Volatile private var activeUsbDeviceKey: String? = null
    @Volatile private var activeUsbSession: UsbDacSession? = null
    @Volatile private var activeUsbEncodedPipeline: UsbEncodedPlaybackPipeline? = null
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
    @Volatile private var nativeSessionHandle = 0L
    @Volatile private var usbPlaybackMode = false
    @Volatile private var usbBitPerfectActive = false
    @Volatile private var usbNativeBitPerfectConfirmed = false
    @Volatile private var usbPositionFrames = 0L
    @Volatile private var paused = false
    private val pendingSeekMs = AtomicReference<Long?>(null)
    private val seekInProgress = AtomicBoolean(false)
    private val seekLock = Any()

    fun setEqualizerGains(gains: FloatArray) {
        require(gains.size == 31) { "The ISO equalizer requires 31 bands" }
        bandGainsDb = gains.copyOf()
        if (usbPlaybackMode) usbNativeBitPerfectConfirmed = false
        equalizerRevision++
    }

    fun setVolume(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        if (usbPlaybackMode) {
            val usbKey = activeUsbDeviceKey
            val session = activeUsbSession
            if (session?.hardwareVolumeAvailable == true && session.setHardwareVolumeFraction(normalized)) {
                softwareVolume = session.readHardwareVolumeFraction() ?: normalized
                if (usbKey != null) volumeController.setUsbVolume(usbKey, softwareVolume)
                usbBitPerfectActive = true
                usbSoftwareGainEnabled = false
                onState("USB DAC hardware volume synchronized · ${(softwareVolume * 100).toInt()}%")
            } else {
                if (!usbSoftwareGainEnabled) {
                    onState("USB DAC hardware volume control is unavailable; software volume is disabled for this session.")
                    return
                }
                softwareVolume = normalized
                if (usbKey != null) volumeController.setUsbVolume(usbKey, normalized)
                usbBitPerfectActive = false
                usbNativeBitPerfectConfirmed = false
                onState("USB DAC software volume · ${(normalized * 100).toInt()}%")
            }
            publishUsbPlaybackModeStatus()
            return
        }
        volumeController.setSystemVolume(normalized)
        softwareVolume = 1f
        publishUsbPlaybackModeStatus()
    }

    private fun publishUsbPlaybackModeStatus() {
        if (!usbPlaybackMode || outputSampleRate <= 0) return
        val mode = if (usbNativeBitPerfectConfirmed) "BIT PERFECT" else "PCM · float32 decoded"
        onState("USB exclusive · $mode · ${usbOutputBitDepth}-bit · ${outputSampleRate} Hz")
    }

    fun setDucked(ducked: Boolean) {
        outputDucked = ducked
        if (ducked && usbPlaybackMode) usbNativeBitPerfectConfirmed = false
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
            var sessionHandle = 0L
            var sessionFailed = false
            try {
                previousWorker?.join(4_000L)
                check(previousWorker?.isAlive != true) {
                    "The previous playback session did not release its output device in time."
                }
                onState("Opening audio stream…")
                descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Unable to open the selected audio document")
                val extension = sourceResolver.resolveExtension(uri)
                val bitPerfectRequested = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getBoolean("usb_bit_perfect_enabled", false)
                diagnostics.record(
                    "PLAY_REQUEST extension=${extension} usbBitPerfectEnabled=${bitPerfectRequested} " +
                        "segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}"
                )
                if (bitPerfectRequested) {
                    diagnostics.record("ROUTE_SELECTED route=LIBUSB_SYMPHONIA_PCM extension=${extension}")
                    onState("USB exclusive: decoding ${extension} through Symphonia into the shared PCM path…")
                }

                handle = NativeAudio.nativeCreateStreamDecoder(descriptor.fd, extension)
                decoderHandle = handle
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(handle != 0L) { "The native decoder could not be created" }

                val preparedSession = NativeAudio.nativePreparePlaybackSession(handle, bitPerfectRequested)
                check(preparedSession.size == 7) { "Rust returned an invalid prepared playback session" }
                sessionHandle = preparedSession[0]
                val playbackRoute = preparedSession[1].toInt()
                val sourceRate = preparedSession[2].toInt()
                val channels = preparedSession[3].toInt()
                val sourceBits = preparedSession[4].toInt()
                val losslessSource = preparedSession[5] != 0L
                val isAc3Source = preparedSession[6] != 0L
                check(sessionHandle != 0L) { "The native playback session could not be created" }
                check(sourceRate > 0 && channels in 1..8) {
                    "The decoder returned an unsupported audio format; Flowery accepts 1-8 channels."
                }
                nativeSessionHandle = sessionHandle
                check(NativeAudio.nativePlaybackSessionCommand(sessionHandle, 1).toInt() == 0) {
                    "The native playback session could not enter the prepared state"
                }
                check(NativeAudio.nativePlaybackSessionCommand(sessionHandle, 2).toInt() == 0) {
                    "The native playback session could not enter the running state"
                }
                when (playbackRoute) {
                    1 -> {
                        diagnostics.record("ROUTE_SELECTED route=LIBUSB_UAC1_ENCODED_AC3 extension=${extension}")
                        usbPlaybackMode = true
                        playUsbEncodedDirect(handle, sourceRate, generation, segmentStartMs, segmentEndMs, sessionHandle)
                        return@thread
                    }
                    2 -> {
                        diagnostics.record("BIT_PERFECT_SOURCE codecClassLossless=${losslessSource} sourceBits=${sourceBits} sampleRate=${sourceRate} eligible=true")
                        diagnostics.record("ROUTE_SELECTED route=LIBUSB_PCM_SOURCE_PRESERVING_CANDIDATE sourceBits=${sourceBits}")
                        usbPlaybackMode = true
                        playUsbDecodedDirect(handle, sourceRate, sourceBits, channels, generation, segmentStartMs, segmentEndMs, sessionHandle)
                        return@thread
                    }
                    3 -> {
                        diagnostics.record("BIT_PERFECT_SOURCE codecClassLossless=${losslessSource} sourceBits=${sourceBits} sampleRate=${sourceRate} eligible=false")
                        diagnostics.record("ROUTE_SELECTED route=LIBUSB_PCM_FLOAT32 sourceLossless=${losslessSource} sourceBits=${sourceBits}")
                        usbPlaybackMode = true
                        playUsbDecodedDirect(handle, sourceRate, 0, channels, generation, segmentStartMs, segmentEndMs, sessionHandle)
                        return@thread
                    }
                    0 -> Unit
                    else -> throw IllegalStateException("Rust returned an unknown playback route: $playbackRoute")
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
                // Android-managed USB, AUX, speaker, and Bluetooth routes use STREAM_MUSIC.
                // Per-DAC software gain is reserved for the direct libusb route.
                activeUsbDeviceKey = null
                softwareVolume = 1f
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
                    val usbHost = diagnostics.hostDeviceSummary()
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
                var appliedEqualizerRevision = equalizerRevision
                check(NativeAudio.nativeStartStreamPump(handle, sessionHandle, DECODE_CHUNK_FRAMES, segmentFrameLimit ?: -1L) == 0) {
                    "Unable to start the Rust-owned AAudio decode scheduler"
                }
                check(NativeAudio.nativeStartOutputScheduler(sessionHandle, lowWatermarkFrames) == 0) {
                    "Unable to start the Rust-owned AAudio output scheduler"
                }
                while (!stopped.get() && playbackGeneration.get() == generation) {
                    pendingSeekMs.getAndSet(null)?.let { requested ->
                        val target = requested.coerceAtLeast(0L).let {
                            if (outputDurationMs > 0L) it.coerceAtMost(outputDurationMs) else it
                        }
                        val targetFrame = NativeAudio.nativeFramesAtMilliseconds(target, targetRate)
                        val actual = NativeAudio.nativeSeekPlaybackSession(
                            handle, sessionHandle, segmentStartMs + target, targetFrame
                        )
                        check(actual >= 0L) { "The decoder rejected the requested seek position" }
                        val remainingFrames = segmentFrameLimit?.let {
                            (it - targetFrame).coerceAtLeast(0L)
                        } ?: -1L
                        check(NativeAudio.nativeStartStreamPump(handle, sessionHandle, DECODE_CHUNK_FRAMES, remainingFrames) == 0) {
                            "Unable to restart the Rust-owned AAudio decode scheduler after seeking"
                        }
                        synchronized(seekLock) {
                            if (pendingSeekMs.get() == null) {
                                seekInProgress.set(false)
                                if (!paused) {
                                    check(NativeAudio.nativePlaybackSessionCommand(sessionHandle, 2).toInt() == 0) {
                                        "Unable to resume the AAudio session after seeking"
                                    }
                                }
                            }
                        }
                        appliedEqualizerRevision = equalizerRevision
                        onState("Seeking · ${actual / 1000}s")
                    }
                    if (appliedEqualizerRevision != equalizerRevision) {
                        check(NativeAudio.nativeUpdateStreamGains(handle, bandGainsDb) == 0) {
                            "Unable to update streaming equalizer"
                        }
                        appliedEqualizerRevision = equalizerRevision
                    }
                    when (NativeAudio.nativeGetStreamPumpStatus(handle)) {
                        0, 1 -> Unit
                        2 -> {
                            // The session epoch changes immediately before the UI thread publishes
                            // the pending seek request. Allow that command to arrive before treating
                            // the worker's epoch exit as an unexpected stop.
                            if (pendingSeekMs.get() == null && !stopped.get()) {
                                Thread.sleep(2)
                                if (pendingSeekMs.get() == null && !stopped.get()) {
                                    throw IllegalStateException("Rust AAudio decode scheduler stopped unexpectedly")
                                }
                            }
                        }
                        else -> throw IllegalStateException("Rust AAudio decode scheduler failed")
                    }
                    when (NativeAudio.nativeGetOutputSchedulerStatus(sessionHandle)) {
                        0 -> Thread.sleep(8)
                        1 -> break
                        2 -> {
                            if (stopped.get() || NativeAudio.nativePlaybackSessionIsCancelled(sessionHandle)) break
                            throw IllegalStateException("Rust AAudio output scheduler stopped unexpectedly")
                        }
                        else -> throw IllegalStateException(
                            "Rust AAudio output scheduler failed (error ${NativeAudio.nativeGetOutputError()})"
                        )
                    }
                }

                if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
            } catch (error: InterruptedException) {
                sessionFailed = !stopped.get()
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                sessionFailed = true
                val usbEnabled = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getBoolean("usb_bit_perfect_enabled", false)
                if (usbEnabled) diagnostics.record("PLAYBACK_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (handle != 0L) NativeAudio.nativeStopStreamPump(handle)
                if (sessionHandle != 0L) NativeAudio.nativeStopOutputScheduler(sessionHandle)
                if (sessionHandle != 0L) {
                    if (!stopped.get()) NativeAudio.nativePlaybackSessionCommand(sessionHandle, if (sessionFailed) 7 else 5).toInt()
                    if (nativeSessionHandle == sessionHandle) nativeSessionHandle = 0L
                    NativeAudio.nativeClosePlaybackSession(sessionHandle)
                }
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

    /**
     * Sends original AC-3 frames through a descriptor-matched UAC1 encoded profile.
     * This path bypasses PCM decoding, EQ, gain, resampling, and the PCM USB writer.
     */
    private fun playUsbEncodedDirect(
        handle: Long,
        sourceRate: Int,
        generation: Int,
        segmentStartMs: Long,
        segmentEndMs: Long?,
        sessionHandle: Long
    ) {
        var pipeline: UsbEncodedPlaybackPipeline? = null
        try {
            val controller = UsbDacController(context)
            val devices = controller.audioDevices()
            onState("USB exclusive: finding a matching UAC1 AC-3 encoded profile…")
            val (device, openedPipeline) =
                controller.openFirstCompatibleEncodedPlaybackPipeline(devices, handle, sessionHandle, sourceRate)
            pipeline = openedPipeline
            activeUsbEncodedPipeline = openedPipeline
            activeUsbSession = null
            activeUsbDeviceKey = controller.deviceIdentity(device.usbDevice)
            usbPlaybackMode = true
            usbBitPerfectActive = true
            usbNativeBitPerfectConfirmed = false
            usbSoftwareGainEnabled = false
            outputSampleRate = sourceRate
            usbOutputBitDepth = 0
            val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
            outputDurationMs = segmentEndMs?.let { (it - segmentStartMs).coerceAtLeast(0L) }
                ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
            if (segmentStartMs > 0L) openedPipeline.seekTo(segmentStartMs)
            onState("USB exclusive · AC-3 encoded passthrough · UAC1 Type-II/IEC 61937 Type-III · ${sourceRate} Hz")

            fun playbackActive() = !stopped.get() && playbackGeneration.get() == generation &&
                !NativeAudio.nativePlaybackSessionIsCancelled(sessionHandle)
            var transportDrainedForPause = false
            while (playbackActive()) {
                if (paused) {
                    if (!transportDrainedForPause) {
                        openedPipeline.drainTransport()
                        transportDrainedForPause = true
                    }
                    Thread.sleep(10)
                    continue
                }
                transportDrainedForPause = false

                pendingSeekMs.getAndSet(null)?.let { requested ->
                    val bounded = segmentEndMs?.let { requested.coerceIn(segmentStartMs, it) }
                        ?: requested.coerceAtLeast(0L)
                    val actual = openedPipeline.seekTo(bounded)
                    diagnostics.record("ENCODED_SEEK requestedMs=${bounded} actualMs=${actual} packetBoundary=true")
                }

                val submitted = openedPipeline.pump(maxPackets = 1)
                if (segmentEndMs != null && openedPipeline.positionMs() >= segmentEndMs) break
                if (openedPipeline.isEnded) break
                if (submitted == 0) Thread.sleep(2)
            }

            if (!stopped.get() && playbackGeneration.get() == generation) {
                openedPipeline.drainTransport()
                onState("Playback finished · AC-3 encoded USB")
            }
        } catch (error: Throwable) {
            diagnostics.record("ENCODED_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
            throw error
        } finally {
            activeUsbEncodedPipeline = null
            activeUsbSession = null
            usbSoftwareGainEnabled = false
            try {
                pipeline?.close()
            } finally {
                usbPlaybackMode = false
                usbBitPerfectActive = false
                usbNativeBitPerfectConfirmed = false
                activeUsbDeviceKey = null
                outputSampleRate = 0
                usbOutputBitDepth = 0
            }
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
        sourceBits: Int,
        channels: Int,
        generation: Int,
        segmentStartMs: Long,
        segmentEndMs: Long?,
        sessionHandle: Long
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
            diagnostics.record("DIRECT_USB_ENTER sampleRate=${sourceRate} channels=${channels} segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}")
            onState("USB exclusive: validating decoded source format…")
            check(channels in 1..8) { "Direct USB decoded playback supports one through eight channels." }
            diagnostics.record("SOURCE_VALIDATED format=symphonia_decoded_pcm sourceBits=${sourceBits} channels=${channels} sampleRate=${sourceRate}")
            val controller = UsbDacController(context)
            onState("USB exclusive: discovering compatible UAC2 DACs…")
            val discovered = controller.audioDevices()
            diagnostics.record("USB_ENUMERATION audioDeviceCount=${discovered.size} devices=${discovered.joinToString { it.identity + ":" + it.displayName }}")
            val ieeeFloatSource = NativeAudio.nativeIsIeeeFloat32Source(handle)
            var ieeeFloatCandidate = ieeeFloatSource && bandGainsDb.all { it == 0f }
            var nativeCandidate = !ieeeFloatSource && sourceBits in 8..32 && bandGainsDb.all { it == 0f }
            var outputEncoding = "signed_integer"
            onState(if (ieeeFloatCandidate) "USB exclusive: requiring exact IEEE Float32/${sourceRate} Hz ${channels}-channel DAC profile…" else if (nativeCandidate) "USB exclusive: requiring exact ${sourceBits}-bit/${sourceRate} Hz ${channels}-channel PCM match…" else "USB exclusive: selecting a ${channels}-channel DAC profile for float32 decoding…")
            diagnostics.record("SESSION_OPEN_BEGIN bitDepth=${if (ieeeFloatCandidate) 32 else if (nativeCandidate) sourceBits else "automatic"} encoding=${if (ieeeFloatCandidate) "ieee_float32" else "signed_integer"} sampleRate=${sourceRate} channels=${channels} nativeCandidate=${nativeCandidate} ieeeFloatCandidate=${ieeeFloatCandidate}")
            val selected = if (ieeeFloatCandidate) {
                try {
                    controller.openFirstCompatiblePlaybackSession(
                        discovered, 32, sourceRate, allowSampleRateConversion = false,
                        requiredSubslotBytes = 4, channels = channels, encoding = "ieee_float32"
                    ).also { outputEncoding = "ieee_float32" }
                } catch (floatError: Throwable) {
                    ieeeFloatCandidate = false
                    diagnostics.record("IEEE_FLOAT_PROFILE_UNAVAILABLE reason=${floatError.message ?: floatError.javaClass.simpleName} fallback=signed_integer_pcm")
                    controller.openFirstCompatiblePlaybackSession(
                        discovered, 0, sourceRate, allowSampleRateConversion = true,
                        requiredSubslotBytes = null, channels = channels
                    )
                }
            } else if (nativeCandidate) {
                try {
                    controller.openFirstCompatiblePlaybackSession(
                        discovered, sourceBits, sourceRate, allowSampleRateConversion = false,
                        requiredSubslotBytes = null, channels = channels
                    )
                } catch (nativeError: Throwable) {
                    nativeCandidate = false
                    diagnostics.record("NATIVE_PROFILE_UNAVAILABLE reason=${nativeError.message ?: nativeError.javaClass.simpleName} fallback=float32")
                    controller.openFirstCompatiblePlaybackSession(
                        discovered, 0, sourceRate, allowSampleRateConversion = true,
                        requiredSubslotBytes = null, channels = channels
                    )
                }
            } else {
                controller.openFirstCompatiblePlaybackSession(
                    discovered, 0, sourceRate, allowSampleRateConversion = true,
                    requiredSubslotBytes = null, channels = channels
                )
            }
            val (selectedDevice, usbSession) = selected
            val device = selectedDevice.usbDevice
            session = usbSession
            diagnostics.record("DAC_SELECTED vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} name=${device.productName ?: "unknown"} interfaces=${device.interfaceCount} identity=${selectedDevice.identity}")
            diagnostics.record("SESSION_OPEN_OK interface=${usbSession.profile.interfaceNumber} alternate=${usbSession.profile.alternateSetting} endpoint=${usbSession.profile.endpointHex} channels=${usbSession.profile.channels} bitResolution=${usbSession.profile.bitResolution} subslotBytes=${usbSession.profile.subslotBytes} maxPacketBytes=${usbSession.profile.maxPacketBytes} interval=${usbSession.profile.interval} synchronization=${usbSession.profile.synchronization} sampleRate=${usbSession.sampleRate}")
            check(usbSession.profile.channels == channels && usbSession.sampleRate > 0) {
                "The negotiated DAC profile does not match the decoded PCM stream."
            }
            if (nativeCandidate) {
                check(usbSession.sampleRate == sourceRate &&
                    usbSession.profile.bitResolution == sourceBits &&
                    usbSession.profile.subslotBytes >= (sourceBits + 7) / 8
                ) {
                    "Bit Perfect requires an exact source/DAC sample-rate, valid-bit, and PCM subslot match."
                }
            }
            val outputRate = usbSession.sampleRate
            val outputBits = usbSession.profile.bitResolution
            onState(if (nativeCandidate) "USB exclusive: preparing native integer PCM…" else "USB exclusive: preparing float32 PCM decoding…")
            val initialGains = if (nativeCandidate || ieeeFloatCandidate) FloatArray(31) { 0f } else bandGainsDb.copyOf()
            val decoderConfigResult = NativeAudio.nativeConfigureStreamDecoder(handle, outputRate, initialGains)
            diagnostics.record("DECODER_CONFIG result=${decoderConfigResult} sourceRate=${sourceRate} outputRate=${outputRate} eqActive=${initialGains.any { it != 0f }} gain=1.0 ducking=false resampling=${outputRate != sourceRate}")
            check(decoderConfigResult == 0) {
                "Unable to configure the streaming decoder and DSP for direct USB output."
            }
            if (segmentStartMs > 0L) {
                val actualStart = NativeAudio.nativeSeekStreamDecoder(handle, segmentStartMs)
                check(actualStart >= 0L) { "The decoder could not seek to the CUE track start." }
            }
            diagnostics.record("DECODER_CONFIGURED outputFormat=${if (outputEncoding == "ieee_float32") "IEEE_FLOAT32_LE" else "PCM_INTEGER_S${outputBits}_LE"} decoderPath=automatic nativeCandidate=${nativeCandidate} ieeeFloatCandidate=${ieeeFloatCandidate} sourceSamplePreservation=conditional")
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
            activeUsbSession = usbSession
            usbNativeBitPerfectConfirmed = false
            val hardwareVolume = usbSession.readHardwareVolumeFraction()
            volumeController.usbProfile(activeUsbDeviceKey!!, hardwareVolume != null)
            if (hardwareVolume != null) {
                softwareVolume = hardwareVolume
                usbBitPerfectActive = true
                usbSoftwareGainEnabled = false
            } else {
                softwareVolume = volumeController.usbVolume(activeUsbDeviceKey!!)
                usbBitPerfectActive = false
                usbSoftwareGainEnabled = true
            }
            diagnostics.record("USB_VOLUME_PROFILE profile=${if (hardwareVolume != null) "hardware_synchronized" else "software_only"} initialVolume=${softwareVolume} physicalVolumeControl=${hardwareVolume != null}")
            diagnostics.record("USB_PCM_GAIN gainBypass=${usbBitPerfectActive} producerGain=${if (usbBitPerfectActive) 1.0 else softwareVolume} softwareVolumeEnabled=${usbSoftwareGainEnabled} ducking=${!usbBitPerfectActive} nativeBitPerfect=awaiting_producer queueDepthBlocks=${USB_QUEUE_DEPTH_BLOCKS} prebufferBlocks=${USB_PREBUFFER_BLOCKS} blockFrames=${USB_DECODE_CHUNK_FRAMES}")

            // The writer (this thread) must never wait on decoding. Rust produces packed PCM blocks
            // into its bounded queue; this Kotlin thread only drains blocks into the libusb
            // session so the isochronous transfer window can be refilled promptly.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
            queueHandle = NativeAudio.nativeCreateUsbPcmQueue(USB_QUEUE_DEPTH_BLOCKS)
            check(queueHandle != 0L) { "Unable to create the native USB PCM queue." }
            producerHandle = NativeAudio.nativeCreateUsbPcmProducer(
                handle, queueHandle, sessionHandle, outputRate, channels, outputBits,
                usbSession.profile.subslotBytes, segmentStartMs, segmentEndMs ?: -1L,
                if (outputEncoding == "ieee_float32") 1 else 0
            )
            check(producerHandle != 0L) { "Unable to create the Rust direct USB producer." }
            check(NativeAudio.nativeStartUsbPcmProducerWorker(producerHandle) == 0) {
                "Unable to start the Rust direct USB producer worker."
            }
            val endReached = AtomicBoolean(false)
            val producerAdapter = UsbPcmProducerAdapter(
                decoderHandle = handle,
                producerHandle = producerHandle,
                pendingSeekMs = pendingSeekMs,
                playbackActive = { !stopped.get() && playbackGeneration.get() == generation },
                equalizerRevision = { equalizerRevision },
                equalizerGains = { bandGainsDb },
                currentGain = { currentUsbGain() },
                diagnostics = diagnostics
            )
            producerThread = thread(name = "FloweryUsbProducerControl") {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                }
                producerAdapter.run(endReached, producerStop, producerFailure)
            }

            fun playbackActive() = !stopped.get() && playbackGeneration.get() == generation &&
                !NativeAudio.nativePlaybackSessionIsCancelled(sessionHandle)

            // Prebuffer: do not prime the USB window until enough decoded audio is ready.
            val prebufferStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
            while (playbackActive() && producerFailure.get() == null && !endReached.get() &&
                NativeAudio.nativeUsbPcmQueueSize(queueHandle) < USB_PREBUFFER_BLOCKS) {
                Thread.sleep(5)
            }
            diagnostics.record(
                "USB_PREBUFFER_READY queuedBlocks=${NativeAudio.nativeUsbPcmQueueSize(queueHandle)} endReached=${endReached.get()} " +
                    "waitMs=${"%.2f".format(java.util.Locale.US,
                        (android.os.SystemClock.elapsedRealtimeNanos() - prebufferStartedNs) / 1_000_000.0)}"
            )

            var firstTransfer = true
            var lastHardwareVolumePollNs = 0L
            var activeEpoch = 0
            var starvationEvents = 0L
            while (playbackActive()) {
                if (paused) { Thread.sleep(10); continue }
                producerFailure.get()?.let { throw it }
                val waitStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val encodedBlock = NativeAudio.nativePollUsbPcmBlock(queueHandle, USB_QUEUE_POLL_MS.toInt()) ?: continue
                val block = UsbPcmProducerAdapter.decodeBlock(encodedBlock)
                val requestedEpoch = NativeAudio.nativePlaybackSessionEpoch(sessionHandle).toInt()
                if (requestedEpoch >= 0 && block.epoch != requestedEpoch) {
                    usbSession.flush()
                    activeEpoch = requestedEpoch
                    continue
                }
                val queueWaitMs = (android.os.SystemClock.elapsedRealtimeNanos() - waitStartedNs) / 1_000_000.0
                if (!firstTransfer && queueWaitMs > USB_STARVATION_REPORT_MS) {
                    starvationEvents++
                    diagnostics.record(
                        "USB_PRODUCER_STARVED waitMs=${"%.2f".format(java.util.Locale.US, queueWaitMs)} " +
                            "events=${starvationEvents}"
                    )
                }
                if (block.endOfStream) {
                    diagnostics.record("DECODER_EOF chunks=${chunkCount} totalFramesSent=${totalFramesSent}")
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
                diagnostics.record(
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
                if (usbSession.hardwareVolumeAvailable) {
                    val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
                    if (nowNs - lastHardwareVolumePollNs >= 500_000_000L) {
                        usbSession.readHardwareVolumeFraction()?.let { softwareVolume = it }
                        lastHardwareVolumePollNs = nowNs
                    }
                }
                chunkCount++
                totalFramesSent += acceptedFrames
                if (chunkCount % 32L == 0L) diagnostics.record("USB_TRANSFER_PROGRESS chunks=${chunkCount} totalFramesSent=${totalFramesSent} starvationEvents=${starvationEvents}")
                usbPositionFrames += frames
                val nativeBitPerfectNow = NativeAudio.nativeIsUsbPcmProducerBitPerfect(producerHandle)
                if (firstTransfer || nativeBitPerfectNow != usbNativeBitPerfectConfirmed) {
                    firstTransfer = false
                    usbNativeBitPerfectConfirmed = nativeBitPerfectNow
                    val decoderPath = when {
                        outputEncoding == "ieee_float32" && usbNativeBitPerfectConfirmed -> "ieee_float32"
                        usbNativeBitPerfectConfirmed -> "integer"
                        else -> "float32"
                    }
                    diagnostics.record("USB_NATIVE_PCM bitPerfect=${usbNativeBitPerfectConfirmed} decoderPath=${decoderPath}")
                    onState(if (usbNativeBitPerfectConfirmed) {
                        "USB exclusive · BIT PERFECT · " + outputBits + "-bit · " + outputRate + " Hz · " +
                            if (outputEncoding == "ieee_float32") "IEEE Float32" else "native integer PCM"
                    } else "USB exclusive · PCM · float32 decoded · " + outputBits + "-bit · " + outputRate + " Hz")
                }
            }
            producerFailure.get()?.let { if (playbackActive()) throw it }
            if (!stopped.get() && playbackGeneration.get() == generation) usbSession.drain()
            diagnostics.record("DIRECT_USB_FINISHED chunks=${chunkCount} totalFramesSent=${totalFramesSent} stopped=${stopped.get()}")
            if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
        } catch (error: Throwable) {
            diagnostics.record("DIRECT_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"} causeType=${error.cause?.javaClass?.simpleName ?: "none"} cause=${error.cause?.message ?: "none"}")
            throw error
        } finally {
            // The producer owns the native decoder handle until it has exited; the caller
            // closes that handle immediately after this method returns.
            producerStop.set(true)
            if (producerHandle != 0L) NativeAudio.nativeStopUsbPcmProducerWorker(producerHandle)
            joinUninterruptibly(producerThread)
            if (queueHandle != 0L) NativeAudio.nativeCloseUsbPcmQueue(queueHandle)
            if (producerHandle != 0L) NativeAudio.nativeDestroyUsbPcmProducer(producerHandle)
            if (queueHandle != 0L) NativeAudio.nativeDestroyUsbPcmQueue(queueHandle)
            usbSoftwareGainEnabled = false
            activeUsbSession = null
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

    /** Software gain for decoded USB output; unity is required for source-preserving output. */
    private fun currentUsbGain(): Float =
        if (usbBitPerfectActive) 1f
        else (softwareVolume * if (outputDucked) DUCK_GAIN else 1f).coerceIn(0f, 1f)

    /** Returns the latest cached volume; USB control transfers run on the playback worker. */
    fun currentVolumeFraction(): Float =
        if (usbPlaybackMode) softwareVolume.coerceIn(0f, 1f) else volumeController.systemVolumeFraction()

    fun positionMs(): Long =
        if (usbPlaybackMode && activeUsbEncodedPipeline != null) activeUsbEncodedPipeline?.positionMs() ?: 0L
        else if (usbPlaybackMode && outputSampleRate > 0) usbPositionFrames * 1000L / outputSampleRate
        else if (outputSampleRate > 0) NativeAudio.nativeGetOutputPositionFrames() * 1000L / outputSampleRate
        else 0L

    fun durationMs(): Long = outputDurationMs

    fun seekTo(positionMs: Long) {
        if (decoderHandle == 0L && !usbPlaybackMode) return
        val session = nativeSessionHandle
        if (session != 0L && !usbPlaybackMode) {
            synchronized(seekLock) {
                seekInProgress.set(true)
                check(NativeAudio.nativePlaybackSessionCommand(session, 3).toInt() == 0) {
                    "Unable to pause the AAudio session for seeking"
                }
                NativeAudio.nativePlaybackSessionCommand(session, 8)
                pendingSeekMs.set(positionMs.coerceAtLeast(0L))
            }
        } else {
            if (session != 0L) NativeAudio.nativePlaybackSessionCommand(session, 8)
            pendingSeekMs.set(positionMs.coerceAtLeast(0L))
        }
    }

    fun pause() {
        if (usbPlaybackMode) {
            paused = true
            if (nativeSessionHandle != 0L) NativeAudio.nativePlaybackSessionCommand(nativeSessionHandle, 3).toInt()
            onState("Paused · USB exclusive")
        } else {
            val session = nativeSessionHandle
            if (session != 0L && NativeAudio.nativePlaybackSessionCommand(session, 3).toInt() == 0) {
                paused = true
                onState("Paused")
            }
        }
    }

    fun resume() {
        if (usbPlaybackMode) {
            paused = false
            if (nativeSessionHandle != 0L) NativeAudio.nativePlaybackSessionCommand(nativeSessionHandle, 2).toInt()
            onState("Playing · USB exclusive")
        } else {
            val session = nativeSessionHandle
            synchronized(seekLock) {
                if (seekInProgress.get()) {
                    paused = false
                    onState("Seeking · resume queued")
                } else if (session != 0L && NativeAudio.nativePlaybackSessionCommand(session, 2).toInt() == 0) {
                    paused = false
                    onState("Playing · AAudio streaming")
                }
            }
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
        if (nativeSessionHandle != 0L) NativeAudio.nativePlaybackSessionCommand(nativeSessionHandle, 6)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        // Keep the worker reference until a later start or shutdown can join it.
        outputDurationMs = 0L
        outputSampleRate = 0
        usbOutputBitDepth = 0
        pendingSeekMs.set(null)
        seekInProgress.set(false)
        paused = false
        usbPlaybackMode = false
        usbPositionFrames = 0L
        activeUsbDeviceKey = null
    }
}
