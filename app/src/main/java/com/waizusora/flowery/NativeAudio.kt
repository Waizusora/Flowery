package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio") }
    external fun nativeEngineVersion(): String
    external fun nativeParseLrc(content: String): String
    external fun nativeParseCue(content: String): String
    external fun nativeParseM3u(content: String): String
    external fun nativeParsePls(content: String): String
    external fun nativeIsSupportedAudioExtension(extension: String): Boolean
    external fun nativeQueueTransition(currentIndex: Int, queueSize: Int, shuffle: Boolean, repeatMode: Int, action: Int): Int
    external fun nativeCreateStreamDecoder(fileDescriptor: Int, extension: String): Long
    external fun nativeGetStreamInfo(handle: Long): Long
    external fun nativeGetStreamDurationMs(handle: Long): Long
    external fun nativeConfigureStreamDecoder(handle: Long, outputRate: Int, bandGainsDb: FloatArray): Int
    external fun nativeUpdateStreamGains(handle: Long, bandGainsDb: FloatArray): Int
    external fun nativeReadStreamChunk(handle: Long, maxFrames: Int): FloatArray
    external fun nativeReadStreamChunkPcm24(handle: Long, maxFrames: Int, targetGain: Float): ByteArray
    external fun nativePumpStreamChunk(handle: Long, maxFrames: Int): Int
    external fun nativeSeekStreamDecoder(handle: Long, positionMs: Long): Long
    external fun nativeCloseStreamDecoder(handle: Long)
    external fun nativeOpenWavPcm(fileDescriptor: Int): Long
    external fun nativeGetWavPcmInfo(handle: Long): LongArray
    external fun nativeReadWavPcmFrames(handle: Long, maxFrames: Int, gain: Float): ByteArray
    external fun nativeSeekWavPcmFrame(handle: Long, frame: Long): Long
    external fun nativeCloseWavPcm(handle: Long)
    external fun nativeCreateUsbPcmQueue(capacityBlocks: Int): Long
    external fun nativeOfferUsbPcmBlock(queue: Long, pcm: ByteArray, frames: Int, epoch: Int, startFrames: Long, endOfStream: Boolean, timeoutMs: Int): Boolean
    external fun nativePollUsbPcmBlock(queue: Long, timeoutMs: Int): ByteArray?
    external fun nativeUsbPcmQueueSize(queue: Long): Int
    external fun nativeClearUsbPcmQueue(queue: Long)
    external fun nativeCloseUsbPcmQueue(queue: Long)
    external fun nativeDestroyUsbPcmQueue(queue: Long)
    external fun nativeStartStreamOutput(sampleRate: Int, channels: Int, volume: Float, deviceId: Int, requireExclusive: Boolean, outputFormat: Int, capacityFrames: Int): Int
    external fun nativeStartStreamPlayback(): Int
    external fun nativeFlushStreamOutput(positionFrames: Long, shouldStart: Boolean): Int
    external fun nativeSetStreamEof()
    external fun nativeGetStreamBufferedFrames(): Int
    external fun nativeGetOutputError(): Int
    external fun nativeGetOutputUnderrunCount(): Int
    external fun nativeGetOutputDiagnostics(): LongArray
    external fun nativeGetLastOutputOpenDiagnostics(): LongArray
    external fun nativeTuneOutputBuffer(): Int
    external fun nativeSelectOutputSampleRate(requestedRate: Int, channels: Int, deviceId: Int, outputFormat: Int, requireExactRate: Boolean): Int
    external fun nativeParseUsbDacProfiles(descriptors: ByteArray, requestedBitResolution: Int): String
    external fun nativeParseUsbDacPlaybackClocks(descriptors: ByteArray, streamingInterface: Int): String
    external fun nativeParseUsbDacClockSources(descriptors: ByteArray): String
    external fun nativeDescribeUsbAudioDescriptors(descriptors: ByteArray): String
    external fun nativeReadTags(encodedAudio: ByteArray, extension: String, tempDirectory: String): String
    external fun nativeWriteTags(encodedAudio: ByteArray, extension: String, tempDirectory: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String, disc: String, comment: String, composer: String, lyrics: String): ByteArray
    external fun nativeSetOutputVolume(volume: Float)
    external fun nativePauseOutput(): Int
    external fun nativeResumeOutput(): Int
    external fun nativeStopOutput()
    external fun nativeIsOutputActive(): Boolean
    external fun nativeGetOutputPositionFrames(): Long
}
