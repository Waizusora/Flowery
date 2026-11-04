package com.waizusora.flowery
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
class FloweryPlayer(private val onState: (String) -> Unit) {
    private var worker: Thread? = null
    private val stopped = AtomicBoolean(false)
    @Volatile private var softwareVolume = 1f
    @Volatile var bassDb = 0f
    @Volatile var midDb = 0f
    @Volatile var trebleDb = 0f
    fun setVolume(value: Float) {
        softwareVolume = value.coerceIn(0f, 1f)
        NativeAudio.nativeSetOutputVolume(softwareVolume)
    }
    fun play(encodedAudio: ByteArray) {
        stop()
        stopped.set(false)
        worker = thread(name = "FloweryAAudio") {
            try {
                onState("Decoding audio with Rust / Symphonia…")
                val pcm = NativeAudio.decode(encodedAudio)
                require(pcm.channels in 1..2) { "The current AAudio output path supports mono and stereo PCM" }
                val processed = NativeAudio.nativeApplyEqualizer(pcm.samples, pcm.sampleRate, pcm.channels, bassDb, midDb, trebleDb)
                val result = NativeAudio.nativeStartOutput(processed, pcm.sampleRate, pcm.channels, softwareVolume)
                check(result == 0) { "AAudio could not open or start the output stream (error $result)" }
                onState("Playing · ${pcm.sampleRate} Hz · ${pcm.channels} ch · AAudio")
                while (!stopped.get() && NativeAudio.nativeIsOutputActive()) Thread.sleep(50)
                if (!stopped.get()) onState("Playback finished")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                NativeAudio.nativeStopOutput()
            }
        }
    }
    fun pause() { if (NativeAudio.nativePauseOutput() == 0) onState("Paused") }
    fun resume() { if (NativeAudio.nativeResumeOutput() == 0) onState("Playing · AAudio") }
    fun stop() {
        stopped.set(true)
        NativeAudio.nativeStopOutput()
        worker?.interrupt()
        worker = null
    }
}
