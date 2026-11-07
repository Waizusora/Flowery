package com.waizusora.flowery
object NativeAudio {
    init { System.loadLibrary("flowery_audio") }
    external fun nativeEngineVersion(): String
    external fun nativeParseLrc(content: String): String
    external fun nativeFindCurrentLyricIndex(timestamps: LongArray, positionMs: Long): Int
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
    external fun nativeSelectPreferredSampleRate(sourceRate: Int, supportedRates: IntArray): Int
    external fun nativeSelectPlaybackRoute(bitPerfectRequested: Boolean, isAc3: Boolean, losslessSource: Boolean, sourceBits: Int, channels: Int): Int
    external fun nativeRingCapacityFrames(sampleRate: Int): Int
    external fun nativeFramesAtMilliseconds(milliseconds: Long, sampleRate: Int): Long
    external fun nativePlaybackLowWatermarkFrames(sampleRate: Int, capacityFrames: Int): Int
    external fun nativeCreateStreamDecoder(fileDescriptor: Int, extension: String): Long
    external fun nativeCreatePlaybackSession(decoder: Long, route: Int): Long
    external fun nativePreparePlaybackSession(decoder: Long, bitPerfectRequested: Boolean): LongArray
    external fun nativePlaybackSessionBeginSeek(session: Long): Long
    external fun nativePlaybackSessionSetPhase(session: Long, phase: Int): Int
    external fun nativePlaybackSessionCommand(session: Long, command: Int): Long
    external fun nativePlaybackSessionEpoch(session: Long): Long
    external fun nativePlaybackSessionIsCancelled(session: Long): Boolean
    external fun nativeCancelPlaybackSession(session: Long)
    external fun nativeClosePlaybackSession(session: Long)
    external fun nativeGetStreamInfo(handle: Long): Long
    external fun nativeIsAc3Source(handle: Long): Boolean
    external fun nativeIsIeeeFloat32Source(handle: Long): Boolean
    external fun nativeGetBitPerfectSourceInfo(handle: Long): Long
    external fun nativeGetStreamDurationMs(handle: Long): Long
    external fun nativeConfigureStreamDecoder(handle: Long, outputRate: Int, bandGainsDb: FloatArray): Int
    external fun nativeUpdateStreamGains(handle: Long, bandGainsDb: FloatArray): Int
    external fun nativeStartStreamPump(handle: Long, session: Long, maxFrames: Int, totalFrames: Long): Int
    external fun nativeGetStreamPumpStatus(handle: Long): Int
    external fun nativeStopStreamPump(handle: Long)
    external fun nativeSeekStreamDecoder(handle: Long, positionMs: Long): Long
    external fun nativeSeekPlaybackSession(decoder: Long, session: Long, positionMs: Long, outputFrame: Long): Long
    external fun nativeCloseStreamDecoder(handle: Long)
    external fun nativeCreateUsbPcmQueue(capacityBlocks: Int): Long
    external fun nativePollUsbPcmBlock(queue: Long, timeoutMs: Int): ByteArray?
    external fun nativeUsbPcmQueueSize(queue: Long): Int
    external fun nativeCloseUsbPcmQueue(queue: Long)
    external fun nativeDestroyUsbPcmQueue(queue: Long)
    external fun nativeCreateUsbPcmProducer(decoder: Long, queue: Long, session: Long, sourceRate: Int, channels: Int, bitResolution: Int, subslotBytes: Int, segmentStartMs: Long, segmentEndMs: Long, encoding: Int): Long
    external fun nativeRequestUsbPcmProducerSeek(producer: Long, positionMs: Long)
    external fun nativePumpUsbPcmProducer(producer: Long, targetGain: Float): Int
    external fun nativeStartUsbPcmProducerWorker(producer: Long): Int
    external fun nativeSetUsbPcmProducerGain(producer: Long, gain: Float): Int
    external fun nativeUsbPcmProducerWorkerError(producer: Long): String
    external fun nativeStopUsbPcmProducerWorker(producer: Long): Int
    external fun nativeIsUsbPcmProducerBitPerfect(producer: Long): Boolean
    external fun nativeIsUsbPcmProducerAtEnd(producer: Long): Boolean
    external fun nativeDestroyUsbPcmProducer(producer: Long)
    external fun nativeValidateUsbEncodedDescriptor(descriptors: ByteArray, interfaceNumber: Int, alternateSetting: Int, formatType: Int, formatTag: Int, sampleRate: Int): String
    external fun nativeSeekUsbEncodedProducer(producer: Long, positionMs: Long): Long
    external fun nativeGetUsbEncodedTransportRate(decoder: Long, framingKind: Int): Long
    external fun nativeCreateUsbEncodedQueue(maxPackets: Int, maxBytes: Int): Long
    external fun nativePollUsbEncodedPacket(queue: Long, timeoutMs: Int): ByteArray?
    external fun nativeCloseUsbEncodedQueue(queue: Long)
    external fun nativeClearUsbEncodedQueue(queue: Long)
    external fun nativeDestroyUsbEncodedQueue(queue: Long)
    external fun nativeCreateUsbEncodedProducer(decoder: Long, queue: Long, session: Long, framingKind: Int, formatCode: Int, maximumBitrateKbps: Int): Long
    external fun nativeStartUsbEncodedProducerWorker(producer: Long): Int
    external fun nativeGetUsbEncodedProducerWorkerStatus(producer: Long): Int
    external fun nativeStopUsbEncodedProducerWorker(producer: Long)
    external fun nativeDestroyUsbEncodedProducer(producer: Long)
    external fun nativeStartStreamOutput(sampleRate: Int, channels: Int, volume: Float, deviceId: Int, requireExclusive: Boolean, outputFormat: Int, capacityFrames: Int): Int
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
    external fun nativeSelectOutputSampleRate(requestedRate: Int, channels: Int, deviceId: Int, outputFormat: Int, requireExactRate: Boolean): Int
    external fun nativeParseUsbDacProfiles(descriptors: ByteArray, requestedBitResolution: Int): String
    external fun nativeSelectUsbSampleRates(sourceRate: Int, rangesJson: String): String
    external fun nativeParseUsbDacPlaybackClocks(descriptors: ByteArray, streamingInterface: Int): String
    external fun nativeParseUsbDacClockSources(descriptors: ByteArray): String
    external fun nativeFindUsbFeatureUnitVolumeControl(descriptors: ByteArray): String
    external fun nativeDescribeUsbAudioDescriptors(descriptors: ByteArray): String
    external fun nativeParseUsbClockRanges(response: ByteArray, transferred: Int): String
    external fun nativeUsbPlaybackDescriptorTrace(descriptors: ByteArray, streamingInterface: Int): String
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
