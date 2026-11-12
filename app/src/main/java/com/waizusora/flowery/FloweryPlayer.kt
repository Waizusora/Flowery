package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

class UsbDirectRouteUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class FloweryPlayer(private val context: Context, private val onState: (String) -> Unit) {
    companion object {
        private const val DECODE_CHUNK_FRAMES = 8_192
        private const val DUCK_GAIN = 0.25f
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)
    private val usbVolumePolicy = UsbPlaybackVolumePolicy(volumeController)
    private val diagnostics = UsbPlaybackDiagnostics(context)
    private val sourceResolver = AudioSourceResolver(context)

    @Volatile private var activeUsbDeviceKey: String? = null
    @Volatile private var activeUsbSession: UsbDacSession? = null
    @Volatile private var activeUsbEncodedPipeline: UsbEncodedPlaybackPipeline? = null
    @Volatile private var activeUsbPcmPipeline: UsbPcmPlaybackPipeline? = null
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
    @Volatile private var activeUsbPcmProducerHandle = 0L
    @Volatile private var usbPlaybackMode = false
    @Volatile private var usbBitPerfectActive = false
    @Volatile private var usbNativeBitPerfectConfirmed = false
    @Volatile private var usbPositionFrames = 0L
    @Volatile private var paused = false
    private val pendingSeekMs = UsbSeekRequestMailbox()
    @Volatile private var activeSegmentStartMs = 0L
    @Volatile private var activeSegmentEndMs = -1L

    fun setEqualizerGains(gains: FloatArray) {
        require(gains.size == 31) { "The ISO equalizer requires 31 bands" }
        bandGainsDb = gains.copyOf()
        if (usbPlaybackMode) {
            usbNativeBitPerfectConfirmed = false
            diagnostics.record("USB_EQ_REQUEST ignored=true reason=USB_Direct_integer_PCM_test_mode")
            publishUsbPlaybackModeStatus()
            return
        }
        equalizerRevision++
        val producer = activeUsbPcmProducerHandle
        if (producer != 0L) {
            check(NativeAudio.nativeUpdateStreamGains(decoderHandle, bandGainsDb) == 0) {
                "Unable to update the active Rust USB PCM producer equalizer."
            }
        }
    }

    fun setVolume(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        if (usbPlaybackMode) {
            val deviceKey = activeUsbDeviceKey
            val volumeState = deviceKey?.let {
                usbVolumePolicy.setVolume(
                    deviceKey = it,
                    session = activeUsbSession,
                    fraction = normalized,
                    softwareGainEnabled = usbSoftwareGainEnabled,
                )
            }
            if (volumeState == null) {
                diagnostics.record(
                    "USB_VOLUME_REQUEST requested=${normalized} applied=false " +
                        "reason=hardware_volume_unavailable softwareGain=disabled pcmGain=1.0"
                )
                onState("USB Direct · DAC hardware volume unavailable · PCM unchanged")
                return
            }
            softwareVolume = volumeState.softwareVolume
            usbSoftwareGainEnabled = volumeState.softwareGainEnabled
            diagnostics.record(
                "USB_VOLUME_APPLIED requested=${normalized} applied=${softwareVolume} " +
                    "hardware=${volumeState.hardwareVolumeAvailable} softwareGain=${usbSoftwareGainEnabled} " +
                    "pcmGain=${currentUsbGain()}"
            )
            publishUsbPlaybackModeStatus()
            return
        }
        volumeController.setSystemVolume(normalized)
        softwareVolume = 1f
        publishUsbPlaybackModeStatus()
    }

    private fun publishUsbPlaybackModeStatus() {
        if (!usbPlaybackMode || outputSampleRate <= 0) return
        val mode = if (usbNativeBitPerfectConfirmed) "BIT PERFECT" else "PCM · bit-perfect not confirmed"
        onState("USB exclusive · $mode · ${usbOutputBitDepth}-bit · ${outputSampleRate} Hz")
    }

    fun setDucked(ducked: Boolean) {
        if (usbPlaybackMode) {
            outputDucked = false
            publishUsbPlaybackModeStatus()
            return
        }
        outputDucked = ducked
        if (ducked && usbPlaybackMode) usbNativeBitPerfectConfirmed = false
        activeUsbPcmProducerHandle.takeIf { it != 0L }?.let {
            NativeAudio.nativeSetUsbPcmProducerGain(it, currentUsbGain())
        }
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
        activeSegmentStartMs = segmentStartMs.coerceAtLeast(0L)
        activeSegmentEndMs = segmentEndMs ?: -1L
        val generation = playbackGeneration.incrementAndGet()
        worker = thread(name = "FloweryStreamPlayer") {
            runCatching {
                // Keep decoding below the real-time AAudio callback thread to reduce scheduler contention.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            }
            var sourceDescriptor: PlaybackSourceDescriptor? = null
            var handle = 0L
            var sessionHandle = 0L
            var sessionFailed = false
            try {
                previousWorker?.join(4_000L)
                check(previousWorker?.isAlive != true) {
                    "The previous playback session did not release its output device in time."
                }
                check(retryPendingUsbEncodedClose()) {
                    "A previous encoded USB session is still awaiting confirmed teardown."
                }
                check(retryPendingUsbPcmClose()) {
                    "A previous PCM USB session is still awaiting confirmed teardown."
                }
                onState("Opening audio stream…")
                val openedSourceDescriptor = PlaybackSourceDescriptor.open(context, uri) { sourceUri ->
                    sourceResolver.resolveExtension(sourceUri)
                }
                sourceDescriptor = openedSourceDescriptor
                val extension = openedSourceDescriptor.extension
                val usbController = UsbDacController(context)
                val usbDirectAvailable = usbController.audioDevices().any { device ->
                    usbController.hasPermission(device.usbDevice)
                }
                diagnostics.record(
                    "PLAY_REQUEST extension=${extension} usbDirectAvailable=${usbDirectAvailable} " +
                        "segmentStartMs=${segmentStartMs} segmentEndMs=${segmentEndMs ?: "none"}"
                )
                if (usbDirectAvailable) {
                    diagnostics.record("USB_AUTO_DETECTION result=permissioned_audio_device_found")
                    onState("USB DAC detected · evaluating source and DAC capabilities…")
                } else {
                    diagnostics.record("USB_AUTO_DETECTION result=direct_transport_unavailable fallback=android_route")
                }

                handle = NativeAudio.nativeCreateStreamDecoder(openedSourceDescriptor.fileDescriptor, extension)
                decoderHandle = handle
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(handle != 0L) { "The native decoder could not be created" }

                val preparedSession = NativeAudio.nativePreparePlaybackSession(handle, usbDirectAvailable)
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
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(usbDirectAvailable) {
                    "USB Direct is the only enabled output route. Connect and grant permission to a USB DAC."
                }
                when (playbackRoute) {
                    1 -> {
                        diagnostics.record("ROUTE_SELECTED route=LIBUSB_UAC1_ENCODED_AC3 extension=${extension}")
                        usbPlaybackMode = true
                        onState("Playing · USB Direct · preparing encoded output…")
                        playUsbEncodedDirect(handle, sourceRate, generation, segmentStartMs, segmentEndMs, sessionHandle)
                        return@thread
                    }
                    2 -> {
                        diagnostics.record("ROUTE_SELECTED route=LIBUSB_PCM_INTEGER sourceBits=${sourceBits} sampleRate=${sourceRate}")
                        usbPlaybackMode = true
                        onState("Playing · USB Direct · preparing decoded integer PCM…")
                        playUsbDecodedDirect(handle, sourceRate, sourceBits, channels, generation, segmentStartMs, segmentEndMs, sessionHandle)
                        return@thread
                    }
                    3 -> throw IllegalStateException(
                        "This source currently requires the float32 decoder path; USB Direct integer PCM cannot represent it without conversion."
                    )
                    0 -> throw IllegalStateException(
                        "No USB Direct PCM route is available for this source. AAudio fallback is disabled."
                    )
                    else -> throw IllegalStateException("Rust returned an unknown playback route: $playbackRoute")
                }

                val outputRoute = AndroidOutputRouteResolver(context).resolve()
                val supportedRates = outputRoute.supportedSampleRates
                // Android-managed USB, AUX, speaker, and Bluetooth routes use STREAM_MUSIC.
                // Per-DAC software gain is reserved for the direct libusb route.
                activeUsbDeviceKey = null
                softwareVolume = 1f
                val deviceId = outputRoute.deviceId
                // Android mixer BIT_PERFECT is not used as a fallback. When the user enables
                // USB Bit-Perfect, playback has already taken the direct USB transport path.
                // Shared Android output is handled by Rust AAudio. USB exclusive playback
                // uses the separate libusb path above and never falls back to Android mixer mode.
                // AUX, speaker, and Bluetooth use integer PCM at the AAudio boundary.
                // Preserve 16-bit sources; use packed 24-bit for higher-depth sources.
                val outputFormat = if (sourceBits <= 16) 1 else 3 // PCM_I16 / PCM_I24
                val preferredRate = NativeAudio.nativeSelectPreferredSampleRate(sourceRate, supportedRates)
                val targetRate = NativeAudio.nativeSelectOutputSampleRate(
                    preferredRate, channels, deviceId, outputFormat, false
                )
                check(targetRate > 0) {
                    "Unable to negotiate the selected USB DAC sample rate. " +
                        "device=${outputRoute.deviceName ?: "unknown"}, deviceId=$deviceId, " +
                        "sourceRate=$sourceRate, preferredRate=$preferredRate, channels=$channels. " +
                        "The app did not fall back to the phone output."
                }

                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(NativeAudio.nativeConfigureStreamDecoder(handle, targetRate, bandGainsDb) == 0) {
                    "Unable to configure the streaming resampler and equalizer"
                }
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                val outputRingCapacityFrames = NativeAudio.nativeRingCapacityFrames(targetRate)
                val opened = NativeAudio.nativeStartStreamOutput(
                    targetRate, channels,
                    softwareVolume,
                    deviceId,
                    requireExclusive = true, // Prefer AAudio EXCLUSIVE; native open retries SHARED if exclusive is unavailable.
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
                    val device = if (outputRoute.isUsbOutput) {
                        "product=${outputRoute.deviceName ?: "unknown"}, id=${outputRoute.deviceId}, " +
                            "type=${outputRoute.deviceType}, address=${outputRoute.deviceAddress ?: "unknown"}, " +
                            "rates=${outputRoute.supportedSampleRates.joinToString().ifEmpty { "unknown" }}"
                    } else "no USB output device selected"
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

                val routeName = if (outputRoute.isUsbOutput) "USB DAC" else "system output"
                val outputInfo = NativeAudio.nativeGetOutputDiagnostics()
                val sharingName = when (outputInfo.getOrNull(9)?.toInt()) {
                    0 -> "EXCLUSIVE"
                    1 -> "SHARED"
                    else -> "unknown sharing mode"
                }
                val actualDeviceId = outputInfo.getOrNull(8)?.toInt() ?: 0
                onState("Playing · Rust AAudio $sharingName · ${targetRate} Hz · ${channels} ch · $routeName (device $actualDeviceId)")

                // Start after a short prebuffer; the native producer caps queued PCM to limit control latency.
                val lowWatermarkFrames = NativeAudio.nativePlaybackLowWatermarkFrames(
                    targetRate, outputRingCapacityFrames
                )
                var appliedEqualizerRevision = equalizerRevision
                var lastReportedSeekPositionMs = -1L
                check(NativeAudio.nativeStartSegmentedStreamPump(
                    handle, sessionHandle, DECODE_CHUNK_FRAMES, segmentStartMs, segmentEndMs ?: -1L
                ) == 0) {
                    "Unable to start the Rust-owned segmented AAudio decode scheduler"
                }
                // Forward a seek requested while the decoder/session was still opening.
                pendingSeekMs.take()?.let { requested ->
                    val epoch = NativeAudio.nativeRequestPlaybackSessionSeek(
                        sessionHandle,
                        segmentStartMs + requested.coerceAtLeast(0L),
                        DECODE_CHUNK_FRAMES,
                        segmentStartMs,
                        segmentEndMs ?: -1L,
                    )
                    check(epoch >= 0L) { "The native AAudio session rejected the queued startup seek request" }
                }
                check(NativeAudio.nativeStartOutputScheduler(sessionHandle, lowWatermarkFrames) == 0) {
                    "Unable to start the Rust-owned AAudio output scheduler"
                }
                while (!stopped.get() && playbackGeneration.get() == generation) {
                    if (appliedEqualizerRevision != equalizerRevision) {
                        check(NativeAudio.nativeUpdateStreamGains(handle, bandGainsDb) == 0) {
                            "Unable to update streaming equalizer"
                        }
                        appliedEqualizerRevision = equalizerRevision
                    }
                    val completedSeekPosition = NativeAudio.nativeGetPlaybackSessionSeekPosition(sessionHandle)
                    if (completedSeekPosition >= 0L && completedSeekPosition != lastReportedSeekPositionMs) {
                        lastReportedSeekPositionMs = completedSeekPosition
                        onState("Seeking · ${completedSeekPosition / 1000}s")
                    }
                    // The native AAudio output scheduler owns decode-worker lifecycle monitoring.
                    // Do not poll the worker registry here: a seek temporarily removes and
                    // replaces its entry, so a concurrent Kotlin poll can report a false
                    // "worker unavailable" failure during that valid transition.
                    when (NativeAudio.nativeGetOutputSchedulerStatus(sessionHandle)) {
                        0 -> Thread.sleep(8)
                        1 -> break
                        2 -> {
                            if (stopped.get() || NativeAudio.nativePlaybackSessionIsCancelled(sessionHandle)) break
                            val errorCode = NativeAudio.nativeGetPlaybackSessionError(sessionHandle)
                            val seekDetail = NativeAudio.nativeGetPlaybackSessionSeekError(sessionHandle)
                            throw IllegalStateException(
                                if (seekDetail.isBlank()) {
                                    "Rust AAudio playback session failed (error $errorCode)"
                                } else {
                                    "Rust AAudio seek failed (error $errorCode): $seekDetail"
                                }
                            )
                        }
                        else -> {
                            val schedulerStatus = NativeAudio.nativeGetOutputSchedulerStatus(sessionHandle)
                            val errorCode = NativeAudio.nativeGetPlaybackSessionError(sessionHandle)
                            val seekDetail = NativeAudio.nativeGetPlaybackSessionSeekError(sessionHandle)
                            throw IllegalStateException(
                                if (schedulerStatus == -903 || seekDetail.isNotBlank()) {
                                    "Rust AAudio seek failed (error $errorCode): ${seekDetail.ifBlank { "No native seek detail was provided." }}"
                                } else {
                                    "Rust AAudio output scheduler failed (status $schedulerStatus, error $errorCode)"
                                }
                            )
                        }
                    }
                }

                if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
            } catch (error: InterruptedException) {
                sessionFailed = !stopped.get()
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                sessionFailed = true
                diagnostics.record("PLAYBACK_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                // Closing the native session stops and joins its AAudio decode/output workers.
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
                sourceDescriptor?.close()
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
            val (device, openedPipeline) = try {
                controller.openFirstCompatibleEncodedPlaybackPipeline(devices, handle, sessionHandle, sourceRate)
            } catch (error: Throwable) {
                throw UsbDirectRouteUnavailableException(
                    "USB encoded profile negotiation failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
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
            openedPipeline.playUntil(
                playbackActive = { playbackActive() },
                isPaused = { paused },
                takePendingSeekMs = { pendingSeekMs.take() },
                segmentStartMs = segmentStartMs,
                segmentEndMs = segmentEndMs,
                onSeek = { bounded, actual ->
                    diagnostics.record("ENCODED_SEEK requestedMs=${bounded} actualMs=${actual} packetBoundary=true")
                },
            )
            if (!stopped.get() && playbackGeneration.get() == generation) {
                onState("Playback finished · AC-3 encoded USB")
            }
        } catch (error: Throwable) {
            diagnostics.record("ENCODED_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"}")
            throw error
        } finally {
            activeUsbSession = null
            usbSoftwareGainEnabled = false
            try {
                pipeline?.close()
                if (activeUsbEncodedPipeline === pipeline) activeUsbEncodedPipeline = null
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
        var pipeline: UsbPcmPlaybackPipeline? = null
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
            val equalizerFlat = bandGainsDb.all { it == 0f }
            var ieeeFloatCandidate = false
            var nativeCandidate = !ieeeFloatSource && sourceBits in 8..32
            onState(
                if (ieeeFloatCandidate) "USB exclusive: requiring exact IEEE Float32/${sourceRate} Hz ${channels}-channel DAC profile…"
                else if (nativeCandidate) "USB exclusive: requiring exact ${sourceBits}-bit/${sourceRate} Hz ${channels}-channel PCM match…"
                else "USB exclusive: selecting a ${channels}-channel DAC profile for float32 decoding…"
            )
            diagnostics.record("SESSION_OPEN_BEGIN bitDepth=${if (ieeeFloatCandidate) 32 else if (nativeCandidate) sourceBits else "automatic"} encoding=${if (ieeeFloatCandidate) "ieee_float32" else "signed_integer"} sampleRate=${sourceRate} channels=${channels} nativeCandidate=${nativeCandidate} ieeeFloatCandidate=${ieeeFloatCandidate}")
            val negotiation = try {
                UsbPcmRouteNegotiator(controller).open(
                    devices = discovered,
                    sourceBits = sourceBits,
                    sourceRate = sourceRate,
                    channels = channels,
                    ieeeFloatSource = false,
                    equalizerFlat = true,
                    onFallback = { error ->
                        if (ieeeFloatCandidate) {
                            ieeeFloatCandidate = false
                            diagnostics.record("IEEE_FLOAT_PROFILE_UNAVAILABLE reason=${error.message ?: error.javaClass.simpleName} fallback=signed_integer_pcm")
                        } else if (nativeCandidate) {
                            nativeCandidate = false
                            diagnostics.record("NATIVE_PROFILE_UNAVAILABLE reason=${error.message ?: error.javaClass.simpleName} fallback=float32")
                        }
                    },
                )
            } catch (error: Throwable) {
                throw UsbDirectRouteUnavailableException(
                    "USB DAC negotiation failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
            ieeeFloatCandidate = negotiation.ieeeFloatCandidate
            nativeCandidate = negotiation.nativeCandidate
            val outputEncoding = negotiation.outputEncoding
            val selectedDevice = negotiation.device
            val usbSession = negotiation.session
            session = usbSession
            val device = selectedDevice.usbDevice
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
            val initialGains = FloatArray(31) { 0f }
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
            // Hardware-only volume: never alter decoded PCM samples in USB Direct.
            val volumeState = usbVolumePolicy.attachDirect(activeUsbDeviceKey!!, usbSession)
            softwareVolume = volumeState.softwareVolume
            usbBitPerfectActive = false
            usbSoftwareGainEnabled = false
            outputDucked = false
            diagnostics.record(
                "USB_VOLUME_PROFILE deviceKey=${activeUsbDeviceKey} policy=hardware_only " +
                    "featureUnitAdvertised=${usbSession.hardwareVolumeAvailable} " +
                    "hardwareVolumeActive=${volumeState.hardwareVolumeAvailable} " +
                    "softwareVolume=disabled diagnostic=${usbSession.hardwareVolumeDiagnostic()}"
            )
            diagnostics.record("USB_PCM_GAIN gainBypass=true producerGain=1.0 softwareVolumeEnabled=false ducking=false nativeBitPerfect=diagnostic_only queueDepthBlocks=${UsbPcmPlaybackPipeline.QUEUE_DEPTH_BLOCKS} prebufferBlocks=${UsbPcmPlaybackPipeline.PREBUFFER_BLOCKS}")

            // The pipeline owns the bounded Rust producer/queue and the libusb writer boundary.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
            val writer = UsbPcmPlaybackPipeline.open(
                decoderHandle = handle,
                playbackSessionHandle = sessionHandle,
                dacSession = usbSession,
                channels = channels,
                segmentStartMs = segmentStartMs,
                segmentEndMs = segmentEndMs,
                initialGain = 1f,
                encoding = if (outputEncoding == "ieee_float32") 1 else 0,
            )
            pipeline = writer
            activeUsbPcmPipeline = writer
            activeUsbPcmProducerHandle = writer.producerHandle

            fun playbackActive() = !stopped.get() && playbackGeneration.get() == generation &&
                !NativeAudio.nativePlaybackSessionIsCancelled(sessionHandle)

            val summary = writer.playUntil(
                playbackActive = { playbackActive() },
                isPaused = { paused },
                takePendingSeekMs = { pendingSeekMs.take() },
                onSeek = { requested ->
                    val boundedPosition = if (outputDurationMs > 0L) {
                        requested.coerceAtMost(outputDurationMs)
                    } else {
                        requested.coerceAtLeast(0L)
                    }
                    if (outputSampleRate > 0) {
                        // Publish the committed seek target immediately, including while
                        // paused; the first post-seek PCM block may not be polled until Resume.
                        usbPositionFrames = NativeAudio.nativeFramesAtMilliseconds(
                            boundedPosition, outputSampleRate
                        )
                    }
                    diagnostics.record(
                        "USB_PCM_SEEK requestedMs=${requested} positionFrames=${usbPositionFrames} " +
                            "epochAdvanced=true transportFlushed=true"
                    )
                    onState("Seeking…")
                },
                onPrebufferReady = { queuedBlocks, endReached, waitMs ->
                    diagnostics.record(
                        "USB_PREBUFFER_READY queuedBlocks=${queuedBlocks} endReached=${endReached} " +
                            "waitMs=${"%.2f".format(java.util.Locale.US, waitMs)}"
                    )
                },
                onFirstTransfer = { frames ->
                    onState("USB exclusive: sending first " + frames + "-frame PCM chunk…")
                },
                onEpochChanged = { startFrames -> usbPositionFrames = startFrames },
                onStarvation = { queueWaitMs, events ->
                    diagnostics.record(
                        "USB_PRODUCER_STARVED waitMs=${"%.2f".format(java.util.Locale.US, queueWaitMs)} " +
                            "events=${events}"
                    )
                },
                onWrite = { result, chunk, totalFrames, starvationEvents, throughputRatio, audioDurationMs ->
                    diagnostics.record(
                        "USB_WRITE chunk=${chunk} requestedFrames=${result.frames} " +
                            "acceptedFrames=${result.acceptedFrames} pcmBytes=${result.pcmBytes} " +
                            "audioMs=${"%.2f".format(java.util.Locale.US, audioDurationMs)} " +
                            "queueWaitMs=${"%.2f".format(java.util.Locale.US, result.queueWaitMs)} " +
                            "transportMs=${"%.2f".format(java.util.Locale.US, result.transportDurationMs)} " +
                            "throughputRatio=${"%.3f".format(java.util.Locale.US, throughputRatio)} " +
                            "queuedBlocks=${result.queuedBlocks}"
                    )
                    usbPositionFrames += result.frames
                    if (chunk % 32L == 0L) {
                        diagnostics.record(
                            "USB_TRANSFER_PROGRESS chunks=${chunk} totalFramesSent=${totalFrames} " +
                                "starvationEvents=${starvationEvents}"
                        )
                    }
                },
                onBitPerfectChanged = { bitPerfect ->
                    // This diagnostic no longer gates playback. USB Direct is tested as a
                    // transport path; native integer PCM status is reported independently.
                    usbNativeBitPerfectConfirmed = bitPerfect
                    // Describe the actual decoder encoding independently from the
                    // transport/bit-perfect verification result. A failed eligibility
                    // check does not convert integer PCM into IEEE float32.
                    val decoderPath = if (outputEncoding == "ieee_float32") "ieee_float32" else "integer"
                    diagnostics.record("USB_NATIVE_PCM bitPerfect=${bitPerfect} decoderPath=${decoderPath}")
                    onState(if (bitPerfect) {
                        "USB exclusive · BIT PERFECT · " + outputBits + "-bit · " + outputRate + " Hz · " +
                            if (outputEncoding == "ieee_float32") "IEEE Float32" else "native integer PCM"
                    } else "USB exclusive · PCM · bit-perfect not confirmed · " + outputBits + "-bit · " + outputRate + " Hz")
                },
                onHardwareVolume = { softwareVolume = it },
                onEndOfStream = { chunks, totalFrames ->
                    diagnostics.record("DECODER_EOF chunks=${chunks} totalFramesSent=${totalFrames}")
                },
            )
            diagnostics.record(
                "DIRECT_USB_FINISHED chunks=${summary.chunks} totalFramesSent=${summary.totalFramesSent} " +
                    "starvationEvents=${summary.starvationEvents} stopped=${stopped.get()}"
            )
            if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
        } catch (error: Throwable) {
            diagnostics.record("DIRECT_USB_ERROR type=${error.javaClass.simpleName} message=${error.message ?: "no message"} causeType=${error.cause?.javaClass?.simpleName ?: "none"} cause=${error.cause?.message ?: "none"}")
            throw error
        } finally {
            val writerHandle = pipeline?.producerHandle ?: 0L
            if (activeUsbPcmProducerHandle == writerHandle) activeUsbPcmProducerHandle = 0L
            usbSoftwareGainEnabled = false
            if (session != null) activeUsbSession = session
            try {
                if (pipeline != null) {
                    pipeline.close()
                    if (activeUsbPcmPipeline === pipeline) activeUsbPcmPipeline = null
                } else {
                    session?.close()
                }
                if (session != null && activeUsbSession === session) activeUsbSession = null
            } finally {
                usbPlaybackMode = false
                activeUsbDeviceKey = null
                outputSampleRate = 0
                usbOutputBitDepth = 0
            }
        }
    }

    /** Software gain for decoded USB output; unity is required for source-preserving output. */
    private fun currentUsbGain(): Float = 1f

    /** Returns the latest cached volume; USB control transfers run on the playback worker. */
    fun currentVolumeFraction(): Float =
        if (usbPlaybackMode) softwareVolume.coerceIn(0f, 1f) else volumeController.systemVolumeFraction()

    fun positionMs(): Long {
        // Playback teardown can reset outputSampleRate concurrently with the service's
        // progress ticker. Read it once so the zero check and division use the same value.
        if (usbPlaybackMode) {
            activeUsbEncodedPipeline?.let { return it.positionMs() }
        }
        val sampleRate = outputSampleRate
        if (sampleRate <= 0) return 0L
        val frames = if (usbPlaybackMode) {
            usbPositionFrames
        } else {
            NativeAudio.nativeGetOutputPositionFrames()
        }
        return frames * 1000L / sampleRate
    }

    fun durationMs(): Long = outputDurationMs

    fun seekTo(positionMs: Long) {
        if (decoderHandle == 0L && !usbPlaybackMode) return
        val boundedPosition = positionMs.coerceAtLeast(0L)
        val session = nativeSessionHandle
        if (session != 0L && !usbPlaybackMode) {
            val epoch = NativeAudio.nativeRequestPlaybackSessionSeek(
                session,
                activeSegmentStartMs + boundedPosition,
                DECODE_CHUNK_FRAMES,
                activeSegmentStartMs,
                activeSegmentEndMs,
            )
            check(epoch >= 0L) { "The native AAudio session rejected the seek request" }
            onState("Seeking…")
        } else {
            // PCM and encoded USB pipelines apply the request on their writer
            // thread, where epoch transition and transport flush cannot overlap
            // an in-progress packet submission.
            pendingSeekMs.request(boundedPosition)
            onState("Seeking…")
        }
    }

    fun pause(): Boolean {
        if (usbPlaybackMode) {
            paused = true
            val transportAccepted = activeUsbEncodedPipeline?.requestPause()
                ?: activeUsbSession?.requestPause()
                ?: false
            if (!transportAccepted) {
                paused = false
                return false
            }
            val session = nativeSessionHandle
            if (session != 0L && NativeAudio.nativePlaybackSessionCommand(session, 3).toInt() != 0) {
                activeUsbEncodedPipeline?.requestResume()
                activeUsbSession?.requestResume()
                paused = false
                return false
            }
            onState("Paused · USB exclusive")
            return true
        }

        val session = nativeSessionHandle
        if (session == 0L || NativeAudio.nativePlaybackSessionCommand(session, 3).toInt() != 0) {
            return false
        }
        paused = true
        onState("Paused")
        return true
    }

    fun resume(): Boolean {
        if (usbPlaybackMode) {
            val transportAccepted = activeUsbEncodedPipeline?.requestResume()
                ?: activeUsbSession?.requestResume()
                ?: false
            if (!transportAccepted) return false
            val session = nativeSessionHandle
            if (session != 0L && NativeAudio.nativePlaybackSessionCommand(session, 2).toInt() != 0) {
                activeUsbEncodedPipeline?.requestPause()
                activeUsbSession?.requestPause()
                return false
            }
            paused = false
            onState("Playing · USB exclusive")
            return true
        }

        val session = nativeSessionHandle
        if (session == 0L || NativeAudio.nativePlaybackSessionCommand(session, 2).toInt() != 0) {
            return false
        }
        paused = false
        onState("Playing · AAudio streaming")
        return true
    }

    fun stopAndJoin(timeoutMs: Long = 4_000L): Boolean {
        val activeWorker = worker
        stop()
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            val joined = try {
                activeWorker.join(timeoutMs.coerceAtLeast(0L))
                !activeWorker.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!joined) return false
        } else if (activeWorker === Thread.currentThread()) {
            return true
        }
        val encodedPipelineClosed = retryPendingUsbEncodedClose()
        val pcmPipelineClosed = retryPendingUsbPcmClose()
        val retainedEncodedSessionsClosed = UsbEncodedDacSession.retryPendingCloseSessions()
        val retainedPcmSessionsClosed = UsbDacSession.retryPendingCloseSessions()
        return encodedPipelineClosed && pcmPipelineClosed &&
            retainedEncodedSessionsClosed && retainedPcmSessionsClosed
    }

    /**
     * Retries an encoded session close that previously failed to confirm native
     * transfer reclamation. The pipeline reference is retained until close succeeds.
     */
    private fun retryPendingUsbEncodedClose(): Boolean {
        val pending = activeUsbEncodedPipeline ?: return true
        return try {
            pending.close()
            if (activeUsbEncodedPipeline === pending) activeUsbEncodedPipeline = null
            true
        } catch (error: Throwable) {
            diagnostics.record(
                "ENCODED_USB_CLOSE_RETRY_FAILED type=${error.javaClass.simpleName} " +
                    "message=${error.message ?: "no message"}"
            )
            false
        }
    }

    /**
     * Retries a PCM USB teardown that previously failed to confirm native
     * transfer reclamation or Android connection release.
     */
    private fun retryPendingUsbPcmClose(): Boolean {
        val pendingPipeline = activeUsbPcmPipeline
        if (pendingPipeline != null) {
            return try {
                pendingPipeline.close()
                if (activeUsbPcmPipeline === pendingPipeline) activeUsbPcmPipeline = null
                if (activeUsbSession === pendingPipeline.dacSession) activeUsbSession = null
                true
            } catch (error: Throwable) {
                diagnostics.record(
                    "PCM_USB_CLOSE_RETRY_FAILED type=${error.javaClass.simpleName} " +
                        "message=${error.message ?: "no message"}"
                )
                false
            }
        }

        val pendingSession = activeUsbSession ?: return true
        return try {
            pendingSession.close()
            if (activeUsbSession === pendingSession) activeUsbSession = null
            true
        } catch (error: Throwable) {
            diagnostics.record(
                "PCM_USB_SESSION_CLOSE_RETRY_FAILED type=${error.javaClass.simpleName} " +
                    "message=${error.message ?: "no message"}"
            )
            false
        }
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        stopped.set(true)
        activeUsbSession?.requestStop()
        activeUsbEncodedPipeline?.requestStop()
        if (nativeSessionHandle != 0L) NativeAudio.nativePlaybackSessionCommand(nativeSessionHandle, 6)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        // Keep the worker reference until a later start or shutdown can join it.
        outputDurationMs = 0L
        outputSampleRate = 0
        usbOutputBitDepth = 0
        pendingSeekMs.clear()
        activeSegmentStartMs = 0L
        activeSegmentEndMs = -1L
        paused = false
        usbPlaybackMode = false
        usbPositionFrames = 0L
        activeUsbDeviceKey = null
    }
}