package com.waizusora.flowery

object NativeAudio {
    init {
        System.loadLibrary("flowery_audio")
    }

    external fun nativeEngineVersion(): String
    external fun nativeDecode(encodedAudio: ByteArray): ByteArray
    external fun nativeApplyEqualizer(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        bassDb: Float,
        midDb: Float,
        trebleDb: Float
    ): FloatArray

    data class DecodedPcm(
        val sampleRate: Int,
        val channels: Int,
        val samples: FloatArray
    )

    fun decode(encodedAudio: ByteArray): DecodedPcm {
        val packet = nativeDecode(encodedAudio)
        require(packet.size >= 8) { "Native decoder returned an invalid PCM packet" }
        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buffer.int
        val channels = buffer.int
        require(sampleRate > 0 && channels in 1..8) { "Native decoder returned an invalid audio format" }
        val samples = FloatArray((packet.size - 8) / 4) { buffer.float }
        return DecodedPcm(sampleRate, channels, samples)
    }
}
