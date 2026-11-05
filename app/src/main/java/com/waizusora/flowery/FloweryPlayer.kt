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
        private const val OUTPUT_RING_CAPACITY_FRAMES = 16_384
        private const val DECODE_CHUNK_FRAMES = 2_048
        private const val LOW_WATERMARK_FRAMES = 4_096
    }

    private var worker: Thread? = null
    private val volumeController = OutputVolumeController(context)
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
            onState("USB exclusive bit-perfect is active; digital volume is fixed at unity. Use the DAC hardware volume control.")
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

    fun play(uri: Uri) {
        stop()
        stopped.set(false)
        val generation = playbackGeneration.incrementAndGet()
        worker = thread(name = "FloweryStreamPlayer") {
            var descriptor: ParcelFileDescriptor? = null
            var handle = 0L
            try {
                onState("Opening audio stream…")
                descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Unable to open the selected audio document")
                val bitPerfectRequested = context.getSharedPreferences("flowery_preferences", Context.MODE_PRIVATE)
                    .getBoolean("usb_bit_perfect_enabled", false)
                if (bitPerfectRequested) {
                    usbPlaybackMode = true
                    playUsbBitPerfect(descriptor, generation)
                    return@thread
                }

                val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
                handle = NativeAudio.nativeCreateStreamDecoder(descriptor.fd, extension)
                decoderHandle = handle
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(handle != 0L) { "The native decoder could not be created" }

                val streamInfo = NativeAudio.nativeGetStreamInfo(handle)
                val sourceRate = (streamInfo ushr 32).toInt()
                val channels = (streamInfo and 0xffff_ffffL).toInt()
                check(sourceRate > 0 && channels in 1..2) { "The decoder returned an invalid audio format" }

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
                val preferredRate = if (sourceRate in supportedRates) sourceRate
                    else supportedRates.minByOrNull { abs(it - sourceRate) } ?: sourceRate
                var deviceId = selectedDevice?.id ?: 0
                var targetRate = NativeAudio.nativeSelectOutputSampleRate(preferredRate, channels, deviceId)
                if (targetRate <= 0 && deviceId != 0) {
                    deviceId = 0
                    targetRate = NativeAudio.nativeSelectOutputSampleRate(sourceRate, channels, 0)
                }
                check(targetRate > 0) { "Unable to negotiate an AAudio output sample rate" }

                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                check(NativeAudio.nativeConfigureStreamDecoder(handle, targetRate, bandGainsDb) == 0) {
                    "Unable to configure the streaming resampler and equalizer"
                }
                if (stopped.get() || playbackGeneration.get() != generation) return@thread
                val started = NativeAudio.nativeStartStreamOutput(
                    targetRate, channels, softwareVolume, deviceId, OUTPUT_RING_CAPACITY_FRAMES
                )
                check(started == 0) { "AAudio could not start the bounded output stream (error $started)" }
                outputSampleRate = targetRate
                outputDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)

                val routeName = if (deviceId != 0) "USB DAC" else "system output"
                onState("Playing · streaming · ${targetRate} Hz · ${channels} ch · $routeName")

                var reachedEnd = false
                var eofSignalled = false
                var appliedEqualizerRevision = equalizerRevision
                while (!stopped.get() && playbackGeneration.get() == generation) {
                    pendingSeekMs.getAndSet(null)?.let { requested ->
                        val target = requested.coerceAtLeast(0L).let { if (outputDurationMs > 0L) it.coerceAtMost(outputDurationMs) else it }
                        val targetFrame = framesAtMilliseconds(target, targetRate)
                        check(NativeAudio.nativeFlushStreamOutput(targetFrame, !paused) == 0) { "Unable to flush or restart the AAudio output stream" }
                        val actual = NativeAudio.nativeSeekStreamDecoder(handle, target)
                        check(actual >= 0L) { "The decoder rejected the requested seek position" }
                        reachedEnd = false
                        eofSignalled = false
                        appliedEqualizerRevision = equalizerRevision
                        onState("Seeking · ${actual / 1000}s")
                    }
                    if (reachedEnd) {
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
                    val frames = chunk.size / channels
                    var offsetFrames = 0
                    while (!stopped.get() && playbackGeneration.get() == generation && offsetFrames < frames && pendingSeekMs.get() == null) {
                        val written = NativeAudio.nativeWriteStreamFrames(
                            chunk, offsetFrames, frames - offsetFrames
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
                    outputDurationMs = NativeAudio.nativeGetStreamDurationMs(handle)
                    while (!stopped.get() && playbackGeneration.get() == generation && pendingSeekMs.get() == null && NativeAudio.nativeGetStreamBufferedFrames() > LOW_WATERMARK_FRAMES) {
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
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (handle != 0L) {
                    if (decoderHandle == handle) decoderHandle = 0L
                    NativeAudio.nativeCloseStreamDecoder(handle)
                }
                if (playbackGeneration.get() == generation) NativeAudio.nativeStopOutput()
                if (playbackGeneration.get() == generation) activeUsbDeviceKey = null
                descriptor?.close()
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
