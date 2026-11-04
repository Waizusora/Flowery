package com.waizusora.flowery
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
class FloweryPlayer(private val onState: (String) -> Unit) {
    private var worker: Thread? = null
    private val stopped = AtomicBoolean(false)
    private val playbackGeneration = AtomicInteger(0)
    @Volatile private var softwareVolume = 1f
    @Volatile private var bandGainsDb = FloatArray(31)
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
                val targetRate = NativeAudio.nativeSelectOutputSampleRate(pcm.sampleRate, pcm.channels)
                check(targetRate > 0) { "Unable to negotiate an AAudio output sample rate" }
                val resampled = NativeAudio.nativeResample(pcm.samples, pcm.sampleRate, targetRate, pcm.channels)
                val processed = NativeAudio.nativeApplyEqualizer(resampled, targetRate, pcm.channels, bandGainsDb)
                val result = NativeAudio.nativeStartOutput(processed, targetRate, pcm.channels, softwareVolume)
                check(result == 0) { "AAudio could not open or start the output stream (error $result)" }
                onState("Playing · ${targetRate} Hz · ${pcm.channels} ch · AAudio · Rubato FFT resampling")
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
