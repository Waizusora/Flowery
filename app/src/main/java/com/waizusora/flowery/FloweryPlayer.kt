package com.waizusora.flowery

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs

class FloweryPlayer(private val context: Context, private val onState: (String) -> Unit) {
    companion object {
        private const val DECODE_CHUNK_FRAMES = 8_192
        private const val DUCK_GAIN = 0.25f
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)
    private val sourceResolver = AudioSourceResolver(context)

    private val stopped = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val outputLifecycleLock = Any()
    @Volatile private var outputReady = false
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var softwareVolume = 1f
    @Volatile private var outputDucked = false
    @Volatile private var outputSampleRate = 0
    @Volatile private var outputDurationMs = 0L
    @Volatile private var decoderHandle = 0L
    @Volatile private var nativeSessionHandle = 0L
    @Volatile private var usbPlaybackSession: UsbNativePlaybackSession? = null
    @Volatile private var usbRouteActive = false
    @Volatile private var paused = false
    private val pendingSeekMs = AtomicLong(-1L)
    @Volatile private var activeSegmentStartMs = 0L
    @Volatile private var activeSegmentEndMs = -1L

    fun setVolume(value: Float) {
        volumeController.setSystemVolume(value.coerceIn(0f, 1f))
        softwareVolume = 1f
    }



    fun setDucked(ducked: Boolean) {
        outputDucked = ducked
        applyOutputVolume()
    }

    private fun applyOutputVolume() {
        val duckGain = if (outputDucked) DUCK_GAIN else 1f
        NativeAudio.nativeSetOutputVolume(softwareVolume * duckGain)
    }

    fun play(uri: Uri, segmentStartMs: Long = 0L, segmentEndMs: Long? = null, startPaused: Boolean = false, initialPositionMs: Long = 0L) {
        val previousWorker = worker
        stop()
        stopped.set(false)
        pauseRequested.set(startPaused)
        outputReady = false
        pendingSeekMs.set(initialPositionMs.coerceAtLeast(0L).takeIf { it > 0L } ?: -1L)
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
                onState("Opening audio stream…")
                val openedSourceDescriptor = PlaybackSourceDescriptor.open(context, uri) { sourceUri ->
                    sourceResolver.resolveExtension(sourceUri)
                }
                sourceDescriptor = openedSourceDescriptor
                val extension = openedSourceDescriptor.extension

                handle = NativeAudio.nativeCreateStreamDecoder(openedSourceDescriptor.fileDescriptor, extension)
                decoderHandle = handle
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(handle != 0L) { "The native decoder could not be created" }
                usbRouteActive = AndroidOutputRouteResolver(context).detectCurrent() == AndroidOutputTarget.USB

                val preparedSession = NativeAudio.nativePreparePlaybackSession(handle)
                check(preparedSession.size == 5) { "Rust returned an invalid prepared playback session" }
                sessionHandle = preparedSession[0]
                val sourceRate = preparedSession[2].toInt()
                val channels = preparedSession[3].toInt()
                val sourceBits = preparedSession[4].toInt()
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
                val routeResolver = AndroidOutputRouteResolver(context)
                // Select the connected Android output automatically. Do not use a stale
                // preference here: the playback stream must follow the currently attached route.
                val selectedTarget = routeResolver.detectCurrent()
                usbRouteActive = selectedTarget == AndroidOutputTarget.USB
                if (selectedTarget == AndroidOutputTarget.USB) {
                    check(segmentEndMs == null) {
                        "Native USB playback currently does not support bounded CUE segments; refusing to play past the selected segment."
                    }
                    usbRouteActive = true
                    val queuedSeek = pendingSeekMs.getAndSet(-1L)
                    val relativeStartMs = if (queuedSeek >= 0L) queuedSeek else initialPositionMs
                    val targetPositionMs = segmentStartMs + relativeStartMs.coerceAtLeast(0L)
                    var actualStartPositionMs = targetPositionMs
                    if (targetPositionMs > 0L) {
                        actualStartPositionMs = NativeAudio.nativeSeekStreamDecoder(handle, targetPositionMs)
                        check(actualStartPositionMs >= 0L) {
                            "Unable to seek the decoder to the requested USB playback start position"
                        }
                    }
                    val directUsb = UsbNativePlaybackSession.open(
                        context, handle, sourceRate, channels, sourceBits
                    ).getOrThrow()
                    usbPlaybackSession = directUsb
                    directUsb.start(actualStartPositionMs).getOrThrow()
                    pendingSeekMs.getAndSet(-1L).takeIf { it >= 0L }?.let { queued ->
                        directUsb.seek(segmentStartMs + queued.coerceAtLeast(0L)).getOrThrow()
                    }
                    outputSampleRate = sourceRate
                    val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
                    val segmentLengthMs = segmentEndMs?.let { (it - segmentStartMs).coerceAtLeast(0L) }
                    outputDurationMs = segmentLengthMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)
                    val shouldStartPaused = synchronized(outputLifecycleLock) {
                        outputReady = true
                        pauseRequested.getAndSet(false)
                    }
                    if (startPaused || shouldStartPaused) {
                        directUsb.pause().getOrThrow()
                        paused = true
                        onState("Paused · native USB audio")
                    } else {
                        paused = false
                        onState("Playing · native USB audio · ${sourceRate} Hz · ${channels} ch")
                    }
                    while (!stopped.get() && playbackGeneration.get() == generation) {
                        pendingSeekMs.getAndSet(-1L).takeIf { it >= 0L }?.let { requested ->
                            val actual = directUsb.seek(segmentStartMs + requested.coerceAtLeast(0L)).getOrThrow()
                            onState("Seeking · ${actual / 1000}s")
                        }
                        val usbStatus = directUsb.status()
                        when (usbStatus.optInt("status", -1)) {
                            0 -> Thread.sleep(8)
                            1 -> break
                            2 -> if (stopped.get()) break else Thread.sleep(8)
                            else -> throw IllegalStateException(
                                usbStatus.optString("error").ifBlank { "Native USB isochronous playback failed" }
                            )
                        }
                    }
                    if (!stopped.get() && playbackGeneration.get() == generation) onState("Playback finished")
                    return@thread
                }
                val outputRoute = routeResolver.resolve(selectedTarget)
                check(selectedTarget == AndroidOutputTarget.SPEAKER || outputRoute.deviceId > 0) {
                    "The selected ${selectedTarget.displayName} output is not currently available."
                }
                // AUX, speaker, and Bluetooth use Android-managed AAudio routing; USB returned above to libusb.
                softwareVolume = 1f
                val deviceId = outputRoute.deviceId
                // AAudio receives integer PCM for every Android-managed output target.
                // USB exclusive mode is still subject to Android device and system audio policy.
                // Select integer output width from the detected source bit depth.
                val outputFormat = if (sourceBits <= 16 || Build.VERSION.SDK_INT < 31) 1 else 3 // PCM_I16 / PCM_I24
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                val outputRingCapacityFrames = NativeAudio.nativeRingCapacityFrames(sourceRate)
                val opened = NativeAudio.nativeStartStreamOutput(
                    sourceRate, channels,
                    softwareVolume,
                    deviceId,
                    requireExclusive = true,
                    failOnShared = selectedTarget == AndroidOutputTarget.USB,
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
                        5 -> "device_selection_api_unavailable"
                        6 -> "requested_device_not_opened"
                        7 -> "exclusive_required_shared_fallback_blocked"
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
                    val device = "target=${outputRoute.target}, product=${outputRoute.deviceName ?: "system default"}, id=${outputRoute.deviceId}, type=${outputRoute.deviceType}"
                    val reason = if (selectedTarget == AndroidOutputTarget.USB) {
                        "USB DAC output requires AAudio EXCLUSIVE mode; shared fallback is disabled."
                    } else if (opened == -10001) {
                        "AAudio could not open the requested Android output in EXCLUSIVE mode."
                    } else {
                        "Unable to open the selected Android AAudio output (error $opened)."
                    }
                    throw IllegalStateException("$reason $device; $details")
                }
                outputSampleRate = sourceRate
                val sourceDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
                val segmentLengthMs = segmentEndMs?.let { (it - segmentStartMs).coerceAtLeast(0L) }
                outputDurationMs = segmentLengthMs ?: (sourceDurationMs - segmentStartMs).coerceAtLeast(0L)

                val routeName = outputRoute.target.name
                val outputInfo = NativeAudio.nativeGetOutputDiagnostics()
                val sharingName = when (outputInfo.getOrNull(9)?.toInt()) {
                    0 -> "EXCLUSIVE"
                    1 -> "SHARED"
                    else -> "unknown sharing mode"
                }
                val actualDeviceId = outputInfo.getOrNull(8)?.toInt() ?: 0

                // Start after a short prebuffer; the native producer caps queued PCM to limit control latency.
                val lowWatermarkFrames = NativeAudio.nativePlaybackLowWatermarkFrames(
                    sourceRate, outputRingCapacityFrames
                )
                var lastReportedSeekPositionMs = -1L
                check(NativeAudio.nativeStartSegmentedStreamPump(
                    handle, sessionHandle, DECODE_CHUNK_FRAMES, segmentStartMs, segmentEndMs ?: -1L
                ) == 0) {
                    "Unable to start the Rust-owned segmented AAudio decode scheduler"
                }
                // Forward a seek requested while the decoder/session was still opening.
                pendingSeekMs.getAndSet(-1L).takeIf { it >= 0L }?.let { requested ->
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
                val shouldStartPaused = synchronized(outputLifecycleLock) {
                    outputReady = true
                    pauseRequested.getAndSet(false)
                }
                if (startPaused || shouldStartPaused) {
                    check(NativeAudio.nativePlaybackSessionCommand(sessionHandle, 3).toInt() == 0) {
                        "The native playback session could not enter the paused state after output routing"
                    }
                    paused = true
                    onState("Paused · audio output changed")
                } else {
                    onState("Playing · Rust AAudio $sharingName · ${sourceRate} Hz · ${channels} ch · $routeName (device $actualDeviceId)")
                }
                while (!stopped.get() && playbackGeneration.get() == generation) {
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
                Log.e("FloweryDiagnostics", "PLAYBACK_ENGINE_FAILURE generation=$generation", error)
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                // Closing the native session stops and joins its AAudio decode/output workers.
                usbPlaybackSession?.let { usb ->
                    runCatching { usb.close() }
                    if (usbPlaybackSession === usb) usbPlaybackSession = null
                }
                usbRouteActive = false
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
                sourceDescriptor?.close()
            }
        }
    }

    fun currentVolumeFraction(): Float = volumeController.systemVolumeFraction()

    fun positionMs(): Long {
        usbPlaybackSession?.let { usb ->
            return (usb.positionMs() - activeSegmentStartMs).coerceAtLeast(0L)
        }
        val sampleRate = outputSampleRate
        if (sampleRate <= 0) return 0L
        return NativeAudio.nativeGetOutputPositionFrames() * 1000L / sampleRate
    }

    fun durationMs(): Long = outputDurationMs

    fun seekTo(positionMs: Long) {
        val boundedPosition = positionMs.coerceAtLeast(0L)
        if (decoderHandle == 0L || (usbRouteActive && !outputReady)) {
            pendingSeekMs.set(boundedPosition)
            return
        }
        usbPlaybackSession?.let {
            pendingSeekMs.set(boundedPosition)
            onState("Seeking…")
            return
        }
        val session = nativeSessionHandle
        if (session != 0L) {
            val epoch = NativeAudio.nativeRequestPlaybackSessionSeek(
                session, activeSegmentStartMs + boundedPosition, DECODE_CHUNK_FRAMES,
                activeSegmentStartMs, activeSegmentEndMs,
            )
            check(epoch >= 0L) { "The native AAudio session rejected the seek request" }
            onState("Seeking…")
        } else {
            pendingSeekMs.set(boundedPosition)
            onState("Seeking…")
        }
    }

    fun pause(): Boolean {
        val accepted = synchronized(outputLifecycleLock) {
            if (!outputReady) {
                if (stopped.get() || worker?.isAlive != true) return false
                pauseRequested.set(true)
                return@synchronized true
            }
            usbPlaybackSession?.let { usb ->
                if (usb.pause().isFailure) return@synchronized false
                paused = true
                return@synchronized true
            }
            val session = nativeSessionHandle
            if (session == 0L || NativeAudio.nativePlaybackSessionCommand(session, 3).toInt() != 0) {
                return@synchronized false
            }
            paused = true
            true
        }
        if (accepted && outputReady) onState("Paused")
        return accepted
    }

    fun resume(): Boolean {
        if (usbRouteActive && !outputReady) {
            pauseRequested.set(false)
            paused = false
            onState("Playing · native USB audio")
            return true
        }
        usbPlaybackSession?.let { usb ->
            if (usb.resume().isFailure) return false
            paused = false
            onState("Playing · native USB audio")
            return true
        }
        val session = nativeSessionHandle
        if (session == 0L || NativeAudio.nativePlaybackSessionCommand(session, 2).toInt() != 0) return false
        paused = false
        onState("Playing · AAudio streaming")
        return true
    }

    fun stopAndJoin(timeoutMs: Long = 4_000L): Boolean {
        val activeWorker = worker
        stop()
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            return try {
                activeWorker.join(timeoutMs.coerceAtLeast(0L))
                !activeWorker.isAlive
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
        return activeWorker !== Thread.currentThread()
    }

    fun stop() {
        playbackGeneration.incrementAndGet()
        stopped.set(true)
        pauseRequested.set(false)
        synchronized(outputLifecycleLock) { outputReady = false }
        usbPlaybackSession?.let { usb -> runCatching { usb.stopPlayback() } }
        if (nativeSessionHandle != 0L) NativeAudio.nativePlaybackSessionCommand(nativeSessionHandle, 6)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        outputDurationMs = 0L
        outputSampleRate = 0
        pendingSeekMs.set(-1L)
        usbRouteActive = false
        activeSegmentStartMs = 0L
        activeSegmentEndMs = -1L
        paused = false
    }
}