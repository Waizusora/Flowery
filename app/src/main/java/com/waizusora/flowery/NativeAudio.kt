package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio"); System.loadLibrary("flowery_aaudio") }
    external fun nativeEngineVersion(): String
    external fun nativeCreateStreamDecoder(fileDescriptor: Int, extension: String): Long
    external fun nativeGetStreamInfo(handle: Long): Long
    external fun nativeGetStreamDurationMs(handle: Long): Long
    external fun nativeConfigureStreamDecoder(handle: Long, outputRate: Int, bandGainsDb: FloatArray): Int
    external fun nativeUpdateStreamGains(handle: Long, bandGainsDb: FloatArray): Int
    external fun nativeReadStreamChunk(handle: Long, maxFrames: Int): FloatArray
    external fun nativeSeekStreamDecoder(handle: Long, positionMs: Long): Long
    external fun nativeCloseStreamDecoder(handle: Long)
    external fun nativeStartStreamOutput(sampleRate: Int, channels: Int, volume: Float, deviceId: Int, capacityFrames: Int): Int
    external fun nativeWriteStreamFrames(samples: FloatArray, sourceFrameOffset: Int, frameCount: Int): Int
    external fun nativeFlushStreamOutput(positionFrames: Long): Int
    external fun nativeSetStreamEof()
    external fun nativeGetStreamBufferedFrames(): Int

    external fun nativeDecode(encodedAudio: ByteArray): ByteArray
    external fun nativeSelectOutputSampleRate(requestedRate: Int, channels: Int, deviceId: Int): Int
    external fun nativeReadTags(encodedAudio: ByteArray, extension: String, tempDirectory: String): String
    external fun nativeWriteTags(encodedAudio: ByteArray, extension: String, tempDirectory: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String, disc: String, comment: String, composer: String, lyrics: String): ByteArray
    external fun nativeResample(samples: FloatArray, inputRate: Int, outputRate: Int, channels: Int): FloatArray
    external fun nativeApplyEqualizer(samples: FloatArray, sampleRate: Int, channels: Int, bandGainsDb: FloatArray): FloatArray
    external fun nativeStartOutput(samples: FloatArray, sampleRate: Int, channels: Int, volume: Float, deviceId: Int): Int
    external fun nativeSetOutputVolume(volume: Float)
    external fun nativePauseOutput(): Int
    external fun nativeResumeOutput(): Int
    external fun nativeStopOutput()
    external fun nativeIsOutputActive(): Boolean
    external fun nativeGetOutputPositionFrames(): Long
    external fun nativeGetOutputDurationFrames(): Long
    external fun nativeSeekOutputFrames(frame: Long)
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
