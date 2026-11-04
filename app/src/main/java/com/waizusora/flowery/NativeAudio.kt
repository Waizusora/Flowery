package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio"); System.loadLibrary("flowery_aaudio") }
    external fun nativeEngineVersion(): String
    external fun nativeDecode(encodedAudio: ByteArray): ByteArray
    external fun nativeSelectOutputSampleRate(requestedRate: Int, channels: Int): Int
    external fun nativeReadTags(encodedAudio: ByteArray, extension: String, tempDirectory: String): String
    external fun nativeWriteTags(encodedAudio: ByteArray, extension: String, tempDirectory: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String, disc: String, comment: String, composer: String, lyrics: String): ByteArray
    external fun nativeResample(samples: FloatArray, inputRate: Int, outputRate: Int, channels: Int): FloatArray
    external fun nativeApplyEqualizer(samples: FloatArray, sampleRate: Int, channels: Int, bandGainsDb: FloatArray): FloatArray
    external fun nativeStartOutput(samples: FloatArray, sampleRate: Int, channels: Int, volume: Float): Int
    external fun nativeSetOutputVolume(volume: Float)
    external fun nativePauseOutput(): Int
    external fun nativeResumeOutput(): Int
    external fun nativeStopOutput()
    external fun nativeIsOutputActive(): Boolean
    data class DecodedPcm(val sampleRate: Int, val channels: Int, val samples: FloatArray)
    fun decode(encodedAudio: ByteArray): DecodedPcm {
        val packet = nativeDecode(encodedAudio)
        require(packet.size >= 8) { "Native decoder returned an invalid PCM packet" }
        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rate = buffer.int
        val channels = buffer.int
        require(rate > 0 && channels in 1..8) { "Native decoder returned an invalid audio format" }
        return DecodedPcm(rate, channels, FloatArray((packet.size - 8) / 4) { buffer.float })
    }
}
