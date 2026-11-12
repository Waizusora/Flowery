package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio") }
    external fun nativeEngineVersion(): String
    external fun nativeParseUsbAudioDescriptors(descriptors: ByteArray): String
    external fun nativePlanUsbAudioPlayback(descriptors: ByteArray, sampleRate: Int, channels: Int, bitResolution: Int): String
    external fun nativeOpenUsbAudioSession(fileDescriptor: Int, interfaceNumber: Int, alternateSetting: Int): Long
    external fun nativeCloseUsbAudioSession(handle: Long)
    external fun nativeGetUsbBusSpeed(handle: Long): Int
    external fun nativeStartUsbIsochronousPlayback(session: Long, decoder: Long, endpoint: Int, rawMaxPacketSize: Int, interval: Int, sampleRate: Int, channels: Int, validBits: Int, subslotBytes: Int): Int
    external fun nativeSeekUsbPlayback(session: Long, decoder: Long, positionMs: Long): Long
    external fun nativePauseUsbPlayback(session: Long): Int
    external fun nativeResumeUsbPlayback(session: Long): Int
    external fun nativeStopUsbPlayback(session: Long)
    external fun nativeGetUsbPlaybackStatus(session: Long): String
    external fun nativeReadUsbPcmChunk(decoderHandle: Long, maxFrames: Int, validBits: Int, subslotBytes: Int): ByteArray
    external fun nativeParseLrc(content: String): String
    external fun nativeParsePlainLyrics(content: String): String
    external fun nativeParseLyricsFile(content: String): String
    external fun nativeSearchLyrics(query: String): String
    external fun nativeGetLyrics(id: Long): String
    external fun nativeFindCurrentLyricIndex(timestamps: LongArray, positionMs: Long): Int
    external fun nativeFindCurrentLyricWordIndex(timestamps: LongArray, positionMs: Long): Int
    external fun nativeParseCue(content: String): String
    external fun nativeParseM3u(content: String): String
    external fun nativeParsePls(content: String): String
    external fun nativeIsSupportedAudioExtension(extension: String): Boolean
    external fun nativeBuildLibraryCatalog(snapshot: String): String
    external fun nativeMatchSidecarLyrics(audioNames: Array<String>, lyricNames: Array<String>): IntArray
    external fun nativeNormalizePlaylistPath(path: String): String?
    external fun nativeQueueTransition(currentIndex: Int, queueSize: Int, shuffle: Boolean, repeatMode: Int, action: Int): Int
    external fun nativeCreatePlaybackQueueState(): Long
    external fun nativeDestroyPlaybackQueueState(handle: Long)
    external fun nativePlaybackQueueSelect(handle: Long, index: Int, size: Int): Int
    external fun nativePlaybackQueueSetRepeatMode(handle: Long, mode: Int): Int
    external fun nativePlaybackQueueCycleRepeatMode(handle: Long): Int
    external fun nativePlaybackQueueSetShuffle(handle: Long, enabled: Boolean): Boolean
    external fun nativePlaybackQueueToggleShuffle(handle: Long): Boolean
    external fun nativePlaybackQueueTransition(handle: Long, size: Int, action: Int): Int
    external fun nativeGetPlaybackQueueSnapshot(handle: Long): LongArray
    external fun nativeCreatePlaybackController(): Long
    external fun nativeTransitionPlaybackController(handle: Long, event: Int): Int
    external fun nativeGetPlaybackControllerState(handle: Long): Int
    external fun nativeDestroyPlaybackController(handle: Long)
    external fun nativeRingCapacityFrames(sampleRate: Int): Int
    external fun nativeFramesAtMilliseconds(milliseconds: Long, sampleRate: Int): Long
    external fun nativePlaybackLowWatermarkFrames(sampleRate: Int, capacityFrames: Int): Int
    external fun nativeCreateStreamDecoder(fileDescriptor: Int, extension: String): Long
    external fun nativePreparePlaybackSession(decoder: Long): LongArray
    external fun nativeCreatePlaybackSession(decoder: Long, route: Int): Long
    external fun nativePlaybackSessionBeginSeek(session: Long): Long
    external fun nativePlaybackSessionSetPhase(session: Long, phase: Int): Int
    external fun nativePlaybackSessionCommand(session: Long, command: Int): Long
    external fun nativeRequestPlaybackSessionSeek(session: Long, positionMs: Long, maxFrames: Int, segmentStartMs: Long, segmentEndMs: Long): Long
    external fun nativeGetPlaybackSessionSeekPosition(session: Long): Long
    external fun nativePlaybackSessionEpoch(session: Long): Long
    external fun nativePlaybackSessionIsCancelled(session: Long): Boolean
    external fun nativeGetPlaybackSessionError(session: Long): Int
    external fun nativeGetPlaybackSessionSeekError(session: Long): String
    external fun nativeCancelPlaybackSession(session: Long)
    external fun nativeClosePlaybackSession(session: Long)
    external fun nativeGetStreamInfo(handle: Long): Long
    external fun nativeGetStreamDurationMs(handle: Long): Long
    external fun nativeStartStreamPump(handle: Long, session: Long, maxFrames: Int, totalFrames: Long): Int
    external fun nativeStartSegmentedStreamPump(handle: Long, session: Long, maxFrames: Int, segmentStartMs: Long, segmentEndMs: Long): Int
    external fun nativeGetStreamPumpStatus(handle: Long): Int
    external fun nativeGetStreamPumpError(handle: Long): String
    external fun nativeStopStreamPump(handle: Long)
    external fun nativeSeekStreamDecoder(handle: Long, positionMs: Long): Long
    external fun nativeCloseStreamDecoder(handle: Long)
    external fun nativeStartStreamOutput(sampleRate: Int, channels: Int, volume: Float, deviceId: Int, requireExclusive: Boolean, failOnShared: Boolean, outputFormat: Int, capacityFrames: Int): Int
    external fun nativeStartStreamPlayback(): Int
    external fun nativeStartOutputScheduler(session: Long, lowWatermarkFrames: Int): Int
    external fun nativeGetOutputSchedulerStatus(session: Long): Int
    external fun nativeStopOutputScheduler(session: Long)
    external fun nativeFlushStreamOutput(positionFrames: Long, shouldStart: Boolean): Int
    external fun nativeSetStreamEof()
    external fun nativeGetStreamBufferedFrames(): Int
    external fun nativeGetOutputError(): Int
    external fun nativeGetOutputUnderrunCount(): Int
    external fun nativeGetOutputDiagnostics(): LongArray
    external fun nativeGetLastOutputOpenDiagnostics(): LongArray
    external fun nativeTuneOutputBuffer(): Int
    external fun nativeReadTags(encodedAudio: ByteArray, extension: String, tempDirectory: String): String
    external fun nativeReadTagsFromFd(fileDescriptor: Int, extension: String, tempDirectory: String): String
    external fun nativeWriteTagsFromFd(fileDescriptor: Int, extension: String, tempDirectory: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String, disc: String, comment: String, composer: String, lyrics: String): String
    external fun nativeWriteTags(encodedAudio: ByteArray, extension: String, tempDirectory: String, title: String, artist: String, album: String, albumArtist: String, genre: String, year: String, track: String, disc: String, comment: String, composer: String, lyrics: String): ByteArray
    external fun nativeSetOutputVolume(volume: Float)
    external fun nativePauseOutput(): Int
    external fun nativeResumeOutput(): Int
    external fun nativeStopOutput()
    external fun nativeIsOutputActive(): Boolean
    external fun nativeGetOutputPositionFrames(): Long
}
