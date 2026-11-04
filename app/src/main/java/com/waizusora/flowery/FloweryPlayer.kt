package com.waizusora.flowery

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
class FloweryPlayer(private val context: Context, private val onState: (String) -> Unit) {
    private var worker: Thread? = null
    private val stopped = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var softwareVolume = 1f
    @Volatile private var bandGainsDb = FloatArray(31)
    @Volatile private var outputSampleRate = 0
    fun setEqualizerGains(gains: FloatArray) {
        require(gains.size == 31) { "The ISO equalizer requires 31 bands" }
        bandGainsDb = gains.copyOf()
    }
    fun setVolume(value: Float) {
        softwareVolume = value.coerceIn(0f, 1f)
        NativeAudio.nativeSetOutputVolume(softwareVolume)
    }
    fun play(encodedAudio: ByteArray) {
        stop()
        stopped.set(false)
        val generation = playbackGeneration.incrementAndGet()
        worker = thread(name = "FloweryAAudio") {
            try {
                onState("Decoding audio with Rust / Symphonia…")
                val pcm = NativeAudio.decode(encodedAudio)
                require(pcm.channels in 1..2) { "The current AAudio output path supports mono and stereo PCM" }
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val usbDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val selectedDevice = usbDevices.firstOrNull { it.sampleRates?.isNotEmpty() == true } ?: usbDevices.firstOrNull()
                val supportedRates = selectedDevice?.sampleRates?.filter { it > 0 }.orEmpty()
                val preferredRate = if (pcm.sampleRate in supportedRates) pcm.sampleRate
                    else supportedRates.minByOrNull { abs(it - pcm.sampleRate) } ?: pcm.sampleRate
                var outputDeviceId = selectedDevice?.id ?: 0
                var targetRate = NativeAudio.nativeSelectOutputSampleRate(preferredRate, pcm.channels, outputDeviceId)
                if (targetRate <= 0 && outputDeviceId != 0) {
                    outputDeviceId = 0
                    targetRate = NativeAudio.nativeSelectOutputSampleRate(pcm.sampleRate, pcm.channels, 0)
                }
                check(targetRate > 0) { "Unable to negotiate an AAudio output sample rate" }
                outputSampleRate = targetRate
                val resampled = NativeAudio.nativeResample(pcm.samples, pcm.sampleRate, targetRate, pcm.channels)
                val processed = NativeAudio.nativeApplyEqualizer(resampled, targetRate, pcm.channels, bandGainsDb)
                val result = NativeAudio.nativeStartOutput(processed, targetRate, pcm.channels, softwareVolume, outputDeviceId)
                check(result == 0) { "AAudio could not open or start the output stream (error $result)" }
                val routeName = if (outputDeviceId != 0) "USB DAC" else "system output"
                onState("Playing · ${targetRate} Hz · ${pcm.channels} ch · $routeName · AAudio / Rubato FFT")
                while (!stopped.get() && NativeAudio.nativeIsOutputActive()) Thread.sleep(50)
                if (!stopped.get()) onState("Playback finished")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (playbackGeneration.get() == generation) NativeAudio.nativeStopOutput()
            }
        }
    }
    fun positionMs(): Long = if (outputSampleRate > 0) NativeAudio.nativeGetOutputPositionFrames() * 1000L / outputSampleRate else 0L
    fun durationMs(): Long = if (outputSampleRate > 0) NativeAudio.nativeGetOutputDurationFrames() * 1000L / outputSampleRate else 0L
    fun seekTo(positionMs: Long) {
        if (outputSampleRate > 0) NativeAudio.nativeSeekOutputFrames(positionMs.coerceAtLeast(0) * outputSampleRate / 1000L)
    }
    fun pause() { if (NativeAudio.nativePauseOutput() == 0) onState("Paused") }
    fun resume() { if (NativeAudio.nativeResumeOutput() == 0) onState("Playing · AAudio") }
    fun stop() {
        playbackGeneration.incrementAndGet()
        stopped.set(true)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        worker = null
    }
}
