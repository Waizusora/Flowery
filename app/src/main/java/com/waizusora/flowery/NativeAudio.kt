package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio"); System.loadLibrary("flowery_aaudio") }
    external fun nativeEngineVersion(): String
    external fun nativeDecode(encodedAudio: ByteArray): ByteArray
    external fun nativeApplyEqualizer(samples: FloatArray, sampleRate: Int, channels: Int, bassDb: Float, midDb: Float, trebleDb: Float): FloatArray
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
