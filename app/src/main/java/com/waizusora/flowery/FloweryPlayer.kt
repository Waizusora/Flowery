package com.waizusora.flowery

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FloweryPlayer(private val onState: (String) -> Unit) {
    private var audioTrack: AudioTrack? = null
    private var worker: Thread? = null
    private val stopped = AtomicBoolean(false)
    @Volatile private var softwareVolume = 1f
    @Volatile var bassDb = 0f
    @Volatile var midDb = 0f
    @Volatile var trebleDb = 0f

    fun setVolume(value: Float) {
        softwareVolume = value.coerceIn(0f, 1f)
        audioTrack?.setVolume(softwareVolume)
    }

    fun play(encodedAudio: ByteArray) {
        stop()
        stopped.set(false)
        worker = thread(name = "FloweryAudio") {
            try {
                onState("Decoding audio with Rust / Symphonia…")
                val pcm = NativeAudio.decode(encodedAudio)
                require(pcm.channels in 1..2) { "The current Android output path supports mono and stereo PCM" }
                val processed = NativeAudio.nativeApplyEqualizer(
                    pcm.samples, pcm.sampleRate, pcm.channels, bassDb, midDb, trebleDb
                )
                val channelMask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
                val minBuffer = AudioTrack.getMinBufferSize(
                    pcm.sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(4096)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer * 4)
                    .build()
                audioTrack = track
                track.setVolume(softwareVolume)
                track.play()
                onState("Playing · ${pcm.sampleRate} Hz · ${pcm.channels} ch")
                var offset = 0
                while (offset < processed.size && !stopped.get()) {
                    val count = track.write(processed, offset, minOf(4096, processed.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (count < 0) throw IllegalStateException("AudioTrack write failed: $count")
                    offset += count
                }
                if (!stopped.get()) onState("Playback finished")
                runCatching { track.stop() }
                track.release()
                audioTrack = null
            } catch (error: Throwable) {
                if (!stopped.get()) onState("Playback error: ${error.message ?: error.javaClass.simpleName}")
                audioTrack?.runCatching { release() }
                audioTrack = null
            }
        }
    }

    fun pause() {
        audioTrack?.pause()
        onState("Paused")
    }

    fun resume() {
        audioTrack?.play()
        onState("Playing")
    }

    fun stop() {
        stopped.set(true)
        audioTrack?.runCatching { pause(); flush(); stop(); release() }
        audioTrack = null
        worker?.interrupt()
        worker = null
    }
}
