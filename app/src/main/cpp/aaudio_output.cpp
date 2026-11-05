#include <aaudio/AAudio.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>
#include <time.h>

namespace {
struct OutputState {
    std::vector<float> ring;
    uint64_t capacityFrames = 0;
    uint64_t mask = 0;
    std::atomic<uint64_t> writeFrame{0};
    std::atomic<uint64_t> readFrame{0};
    std::atomic<float> volume{1.0f};
    std::atomic<bool> active{false};
    std::atomic<bool> eof{false};
    std::atomic<int32_t> errorCode{0};
    std::atomic<uint64_t> playedFrames{0};
    std::atomic<uint64_t> underrunCount{0};
    std::atomic<uint64_t> callbackCount{0};
    std::atomic<uint64_t> callbackFramesRequested{0};
    std::atomic<uint64_t> callbackFramesDelivered{0};
    std::atomic<uint64_t> callbackSilenceFrames{0};
    std::atomic<uint64_t> callbackUnexpectedSilenceFrames{0};
    std::atomic<uint64_t> callbackMaxFrames{0};
    std::atomic<uint64_t> lastCallbackStartNs{0};
    std::atomic<uint64_t> lastCallbackFrames{0};
    std::atomic<uint64_t> callbackMaxLatenessNs{0};
    std::atomic<uint64_t> callbackLateIntervalCount{0};
    std::atomic<uint64_t> callbackMaxIntervalNs{0};
    std::atomic<uint64_t> callbackMaxDurationNs{0};
    std::atomic<uint64_t> callbackLastIntervalNs{0};
    std::atomic<uint64_t> callbackLastLatenessNs{0};
    std::atomic<int32_t> lastTunedXrunCount{0};
    int32_t exclusiveOpenResult = AAUDIO_ERROR_INVALID_STATE;
    int32_t sharedOpenResult = AAUDIO_ERROR_INVALID_STATE;
    int32_t successfulOpenSharingMode = -1;
    int32_t requestedDeviceId = 0;
    int32_t channels = 2;
    int32_t sampleRate = 0;
    aaudio_format_t format = AAUDIO_FORMAT_PCM_FLOAT;
};

std::mutex g_mutex;
AAudioStream* g_stream = nullptr;
std::unique_ptr<OutputState> g_output;

struct LastOpenDiagnostics {
    int32_t requestedDeviceId = 0;
    int32_t requestedSharingMode = -1;
    int32_t requestedSampleRate = 0;
    int32_t requestedChannels = 0;
    int32_t openResult = AAUDIO_ERROR_INVALID_STATE;
    int32_t actualDeviceId = 0;
    int32_t actualSharingMode = -1;
    int32_t actualSampleRate = 0;
    int32_t actualChannels = 0;
    int32_t actualFormat = -1;
    int32_t failureStage = 0;
};
LastOpenDiagnostics g_lastOpenDiagnostics;

void errorCallback(AAudioStream*, void* userData, aaudio_result_t error) {
    auto* state = static_cast<OutputState*>(userData);
    state->errorCode.store(error, std::memory_order_release);
    state->active.store(false, std::memory_order_release);
}

uint64_t monotonicTimeNs() {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<uint64_t>(value.tv_sec) * 1'000'000'000ULL +
           static_cast<uint64_t>(value.tv_nsec);
}

void resetCallbackTimingBaseline(OutputState* state) {
    state->lastCallbackStartNs.store(0, std::memory_order_relaxed);
    state->lastCallbackFrames.store(0, std::memory_order_relaxed);
}

void updateMaximum(std::atomic<uint64_t>& target, uint64_t value) {
    uint64_t previous = target.load(std::memory_order_relaxed);
    while (previous < value &&
           !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) {}
}

aaudio_data_callback_result_t dataCallback(AAudioStream*, void* userData, void* audioData, int32_t frames) {
    const uint64_t callbackStartNs = monotonicTimeNs();
    auto* state = static_cast<OutputState*>(userData);
    const uint64_t previousStartNs = state->lastCallbackStartNs.exchange(callbackStartNs, std::memory_order_relaxed);
    const uint64_t previousFrames = state->lastCallbackFrames.exchange(
        static_cast<uint64_t>(std::max(frames, 0)), std::memory_order_relaxed);
    if (previousStartNs != 0 && callbackStartNs >= previousStartNs) {
        const uint64_t intervalNs = callbackStartNs - previousStartNs;
        state->callbackLastIntervalNs.store(intervalNs, std::memory_order_relaxed);
        updateMaximum(state->callbackMaxIntervalNs, intervalNs);
        const uint64_t expectedNs = state->sampleRate > 0
            ? previousFrames * 1'000'000'000ULL / static_cast<uint64_t>(state->sampleRate)
            : 0;
        const uint64_t latenessNs = intervalNs > expectedNs ? intervalNs - expectedNs : 0;
        state->callbackLastLatenessNs.store(latenessNs, std::memory_order_relaxed);
        updateMaximum(state->callbackMaxLatenessNs, latenessNs);
        if (latenessNs > 10'000'000ULL) {
            state->callbackLateIntervalCount.fetch_add(1, std::memory_order_relaxed);
        }
    }

    state->callbackCount.fetch_add(1, std::memory_order_relaxed);
    state->callbackFramesRequested.fetch_add(static_cast<uint64_t>(frames), std::memory_order_relaxed);
    updateMaximum(state->callbackMaxFrames, static_cast<uint64_t>(frames));

    const uint64_t read = state->readFrame.load(std::memory_order_relaxed);
    const uint64_t write = state->writeFrame.load(std::memory_order_acquire);
    const uint64_t available = write >= read ? write - read : 0;
    const uint64_t count = std::min<uint64_t>(static_cast<uint64_t>(frames), available);
    const float gain = state->volume.load(std::memory_order_relaxed);

    auto sampleAt = [&](uint64_t frame, int32_t channel) -> float {
        const uint64_t ringFrame = (read + frame) & state->mask;
        const size_t ringIndex = static_cast<size_t>(ringFrame * state->channels + channel);
        return std::clamp(state->ring[ringIndex] * gain, -1.0f, 1.0f);
    };

    if (state->format == AAUDIO_FORMAT_PCM_FLOAT) {
        auto* output = static_cast<float*>(audioData);
        for (uint64_t frame = 0; frame < count; ++frame) {
            for (int32_t channel = 0; channel < state->channels; ++channel) {
                output[frame * state->channels + channel] = sampleAt(frame, channel);
            }
        }
    } else if (state->format == AAUDIO_FORMAT_PCM_I16) {
        auto* output = static_cast<int16_t*>(audioData);
        for (uint64_t frame = 0; frame < count; ++frame) {
            for (int32_t channel = 0; channel < state->channels; ++channel) {
                const float sample = sampleAt(frame, channel);
                output[frame * state->channels + channel] = static_cast<int16_t>(
                    std::lrint(sample * (sample < 0.0f ? 32768.0f : 32767.0f)));
            }
        }
    } else if (state->format == AAUDIO_FORMAT_PCM_I24_PACKED) {
        auto* output = static_cast<uint8_t*>(audioData);
        for (uint64_t frame = 0; frame < count; ++frame) {
            for (int32_t channel = 0; channel < state->channels; ++channel) {
                const float sample = sampleAt(frame, channel);
                const int32_t value = static_cast<int32_t>(
                    std::llround(sample * (sample < 0.0f ? 8388608.0 : 8388607.0)));
                const size_t index = static_cast<size_t>((frame * state->channels + channel) * 3);
                output[index] = static_cast<uint8_t>(value & 0xff);
                output[index + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
                output[index + 2] = static_cast<uint8_t>((value >> 16) & 0xff);
            }
        }
    } else if (state->format == AAUDIO_FORMAT_PCM_I32) {
        auto* output = static_cast<int32_t*>(audioData);
        for (uint64_t frame = 0; frame < count; ++frame) {
            for (int32_t channel = 0; channel < state->channels; ++channel) {
                const float sample = sampleAt(frame, channel);
                const double scaled = static_cast<double>(sample) *
                    (sample < 0.0f ? 2147483648.0 : 2147483647.0);
                output[frame * state->channels + channel] = static_cast<int32_t>(
                    std::llround(std::clamp(scaled, -2147483648.0, 2147483647.0)));
            }
        }
    }

    const uint64_t remaining = static_cast<uint64_t>(frames) - count;
    if (remaining > 0) {
        const size_t bytesPerSample =
            state->format == AAUDIO_FORMAT_PCM_I16 ? sizeof(int16_t) :
            state->format == AAUDIO_FORMAT_PCM_I24_PACKED ? 3 :
            state->format == AAUDIO_FORMAT_PCM_I32 ? sizeof(int32_t) : sizeof(float);
        std::memset(static_cast<uint8_t*>(audioData) +
                        count * state->channels * bytesPerSample,
                    0, remaining * state->channels * bytesPerSample);
        if (!state->eof.load(std::memory_order_acquire)) {
            state->underrunCount.fetch_add(1, std::memory_order_relaxed);
            state->callbackUnexpectedSilenceFrames.fetch_add(remaining, std::memory_order_relaxed);
        }
    }

    state->readFrame.store(read + count, std::memory_order_release);
    state->playedFrames.fetch_add(count, std::memory_order_relaxed);
    state->callbackFramesDelivered.fetch_add(count, std::memory_order_relaxed);
    state->callbackSilenceFrames.fetch_add(remaining, std::memory_order_relaxed);

    const auto callbackResult = (remaining > 0 && state->eof.load(std::memory_order_acquire) &&
        state->writeFrame.load(std::memory_order_acquire) == read + count)
        ? AAUDIO_CALLBACK_RESULT_STOP : AAUDIO_CALLBACK_RESULT_CONTINUE;
    const uint64_t callbackEndNs = monotonicTimeNs();
    if (callbackEndNs >= callbackStartNs) {
        updateMaximum(state->callbackMaxDurationNs, callbackEndNs - callbackStartNs);
    }
    if (callbackResult == AAUDIO_CALLBACK_RESULT_STOP) {
        state->active.store(false, std::memory_order_release);
    }
    return callbackResult;
}
void stopLocked() {
    if (g_stream) {
        const auto current = AAudioStream_getState(g_stream);
        if (current != AAUDIO_STREAM_STATE_DISCONNECTED &&
            current != AAUDIO_STREAM_STATE_STOPPED &&
            current != AAUDIO_STREAM_STATE_CLOSED) {
            if (AAudioStream_requestStop(g_stream) == AAUDIO_OK) {
                aaudio_stream_state_t observed = current;
                for (int attempt = 0; attempt < 4 &&
                     observed != AAUDIO_STREAM_STATE_STOPPED &&
                     observed != AAUDIO_STREAM_STATE_DISCONNECTED &&
                     observed != AAUDIO_STREAM_STATE_CLOSED; ++attempt) {
                    aaudio_stream_state_t next = observed;
                    if (AAudioStream_waitForStateChange(
                            g_stream, observed, &next, 1'000'000'000LL) != AAUDIO_OK) {
                        break;
                    }
                    observed = next;
                }
            }
        }
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
    g_output.reset();
}

aaudio_result_t openStream(OutputState* state, int32_t rate, int32_t channels,
                           aaudio_sharing_mode_t sharing, int32_t deviceId) {
    g_lastOpenDiagnostics.failureStage = 1; // Stream builder creation.
    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
#if __ANDROID_API__ >= 28
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);
#else
    (void)deviceId;
#endif
    AAudioStreamBuilder_setFormat(builder, state->format);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSharingMode(builder, sharing);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
#if __ANDROID_API__ >= 28
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, state);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, state);
    g_lastOpenDiagnostics.failureStage = 2; // AAudio stream open.
    result = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return result;

    // AAudio may accept the builder request but negotiate a different sharing mode.
    // Strict mode requires the actual stream mode to match the requested mode.
    const auto actualSharing = AAudioStream_getSharingMode(g_stream);
    g_lastOpenDiagnostics.actualDeviceId = AAudioStream_getDeviceId(g_stream);
    g_lastOpenDiagnostics.actualSharingMode = static_cast<int32_t>(actualSharing);
    g_lastOpenDiagnostics.actualSampleRate = AAudioStream_getSampleRate(g_stream);
    g_lastOpenDiagnostics.actualChannels = AAudioStream_getChannelCount(g_stream);
    g_lastOpenDiagnostics.actualFormat = static_cast<int32_t>(AAudioStream_getFormat(g_stream));
    if (actualSharing != sharing) {
        g_lastOpenDiagnostics.failureStage = 3; // Sharing mode negotiation mismatch.
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return -10001; // FLOWERY_ERROR_EXCLUSIVE_MODE_UNAVAILABLE
    }
    if (AAudioStream_getFormat(g_stream) != state->format ||
        (rate > 0 && AAudioStream_getSampleRate(g_stream) != rate) ||
        AAudioStream_getChannelCount(g_stream) != channels) {
        g_lastOpenDiagnostics.failureStage = 4; // Negotiated PCM format mismatch.
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    g_lastOpenDiagnostics.failureStage = 0;
    state->sampleRate = AAudioStream_getSampleRate(g_stream);
    state->successfulOpenSharingMode = static_cast<int32_t>(actualSharing);
    return AAUDIO_OK;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeStartStreamOutput(
    JNIEnv*, jobject, jint rate, jint channels, jfloat volume, jint deviceId, jboolean requireExclusive, jint outputFormat, jint capacityFrames) {
    if (rate <= 0 || (channels != 1 && channels != 2) || capacityFrames < 2048 ||
        (outputFormat != AAUDIO_FORMAT_PCM_FLOAT && outputFormat != AAUDIO_FORMAT_PCM_I16 &&
         outputFormat != AAUDIO_FORMAT_PCM_I24_PACKED && outputFormat != AAUDIO_FORMAT_PCM_I32)) {
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    uint64_t capacity = 1;
    while (capacity < static_cast<uint64_t>(capacityFrames)) capacity <<= 1;

    auto state = std::make_unique<OutputState>();
    state->channels = channels;
    state->format = static_cast<aaudio_format_t>(outputFormat);
    state->requestedDeviceId = deviceId;
    state->capacityFrames = capacity;
    state->mask = capacity - 1;
    state->ring.resize(static_cast<size_t>(capacity) * channels);
    state->volume.store(std::clamp(volume, 0.0f, 1.0f));

    std::lock_guard<std::mutex> lock(g_mutex);
    stopLocked();
    g_output = std::move(state);
    // USB DAC output requires verified EXCLUSIVE mode. Android system routes
    // use SHARED mode so speaker, wired and Bluetooth playback remain available.
    const auto requestedSharing = requireExclusive == JNI_TRUE
        ? AAUDIO_SHARING_MODE_EXCLUSIVE
        : AAUDIO_SHARING_MODE_SHARED;
    g_lastOpenDiagnostics = {};
    g_lastOpenDiagnostics.requestedDeviceId = deviceId;
    g_lastOpenDiagnostics.requestedSharingMode = static_cast<int32_t>(requestedSharing);
    g_lastOpenDiagnostics.requestedSampleRate = rate;
    g_lastOpenDiagnostics.requestedChannels = channels;
    const auto result = openStream(
        g_output.get(), rate, channels, requestedSharing, deviceId);
    g_lastOpenDiagnostics.openResult = result;
    g_output->exclusiveOpenResult = result;
    g_output->sharedOpenResult = AAUDIO_ERROR_INVALID_STATE; // Shared fallback is intentionally disabled.
    if (result != AAUDIO_OK) {
        stopLocked();
        return result;
    }
    // Keep the stream open while the producer fills the ring buffer.
    // Playback starts only after Kotlin confirms that the prebuffer target is met.
    g_output->active.store(true, std::memory_order_release);
    return AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeStartStreamPlayback(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output || !g_stream || !g_output->active.load(std::memory_order_acquire)) {
        return AAUDIO_ERROR_INVALID_STATE;
    }
    const auto state = AAudioStream_getState(g_stream);
    if (state == AAUDIO_STREAM_STATE_STARTED) return AAUDIO_OK;
    if (state != AAUDIO_STREAM_STATE_OPEN && state != AAUDIO_STREAM_STATE_PAUSED &&
        state != AAUDIO_STREAM_STATE_STOPPED) return AAUDIO_ERROR_INVALID_STATE;
    resetCallbackTimingBaseline(g_output.get());
    const auto result = AAudioStream_requestStart(g_stream);
    if (result != AAUDIO_OK) g_output->active.store(false, std::memory_order_release);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeWriteStreamFrames(
    JNIEnv* env, jobject, jfloatArray input, jint sourceFrameOffset, jint frameCount) {
    if (sourceFrameOffset < 0 || frameCount <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output || !g_output->active.load(std::memory_order_acquire)) return 0;

    const uint64_t read = g_output->readFrame.load(std::memory_order_acquire);
    const uint64_t write = g_output->writeFrame.load(std::memory_order_relaxed);
    const uint64_t queuedFrames = write >= read ? write - read : g_output->capacityFrames;
    const uint64_t freeFrames = queuedFrames >= g_output->capacityFrames
        ? 0 : g_output->capacityFrames - queuedFrames;
    const uint64_t count = std::min<uint64_t>(static_cast<uint64_t>(frameCount), freeFrames);
    if (count == 0) return 0;

    const jsize arrayLength = env->GetArrayLength(input);
    const uint64_t sourceStart = static_cast<uint64_t>(sourceFrameOffset) * g_output->channels;
    const uint64_t sampleCount = count * g_output->channels;
    if (sourceStart + sampleCount > static_cast<uint64_t>(arrayLength)) return -1;

    const uint64_t ringFrame = write & g_output->mask;
    const uint64_t firstSampleCount = std::min<uint64_t>(
        sampleCount, (g_output->capacityFrames - ringFrame) * g_output->channels);
    env->GetFloatArrayRegion(
        input,
        static_cast<jsize>(sourceStart),
        static_cast<jsize>(firstSampleCount),
        g_output->ring.data() + ringFrame * g_output->channels);
    if (env->ExceptionCheck()) return -1;

    const uint64_t remainingSampleCount = sampleCount - firstSampleCount;
    if (remainingSampleCount > 0) {
        env->GetFloatArrayRegion(
            input,
            static_cast<jsize>(sourceStart + firstSampleCount),
            static_cast<jsize>(remainingSampleCount),
            g_output->ring.data());
        if (env->ExceptionCheck()) return -1;
    }
    g_output->writeFrame.store(write + count, std::memory_order_release);
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeFlushStreamOutput(JNIEnv*, jobject, jlong positionFrames, jboolean shouldStart) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output || !g_stream || positionFrames < 0) return AAUDIO_ERROR_INVALID_STATE;
    auto state = AAudioStream_getState(g_stream);
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        g_output->active.store(false, std::memory_order_release);
        return AAUDIO_ERROR_DISCONNECTED;
    }
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        const auto pauseResult = AAudioStream_requestPause(g_stream);
        if (pauseResult != AAUDIO_OK) return pauseResult;
        aaudio_stream_state_t nextState = state;
        const auto waitResult = AAudioStream_waitForStateChange(
            g_stream, state, &nextState, 1'000'000'000LL);
        if (waitResult != AAUDIO_OK) return waitResult;
        state = nextState;
    }
    if (state == AAUDIO_STREAM_STATE_PAUSING) {
        aaudio_stream_state_t nextState = state;
        const auto waitResult = AAudioStream_waitForStateChange(
            g_stream, state, &nextState, 1'000'000'000LL);
        if (waitResult != AAUDIO_OK) return waitResult;
        state = nextState;
    }
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        g_output->active.store(false, std::memory_order_release);
        return AAUDIO_ERROR_DISCONNECTED;
    }
    if (state != AAUDIO_STREAM_STATE_PAUSED && state != AAUDIO_STREAM_STATE_OPEN &&
        state != AAUDIO_STREAM_STATE_STOPPED) return AAUDIO_ERROR_INVALID_STATE;

    // The callback is quiescent after PAUSED/OPEN/STOPPED; reset queue indices safely.
    g_output->readFrame.store(0, std::memory_order_relaxed);
    g_output->writeFrame.store(0, std::memory_order_relaxed);
    g_output->playedFrames.store(static_cast<uint64_t>(positionFrames), std::memory_order_release);
    g_output->eof.store(false, std::memory_order_release);
    resetCallbackTimingBaseline(g_output.get());
    g_output->active.store(true, std::memory_order_release);
    if (shouldStart != JNI_TRUE) {
        if (state == AAUDIO_STREAM_STATE_STARTED) {
            const auto pauseResult = AAudioStream_requestPause(g_stream);
            if (pauseResult != AAUDIO_OK) {
                g_output->active.store(false, std::memory_order_release);
                return pauseResult;
            }
            aaudio_stream_state_t nextState = state;
            const auto waitResult = AAudioStream_waitForStateChange(g_stream, state, &nextState, 1000000000LL);
            if (waitResult != AAUDIO_OK) {
                g_output->active.store(false, std::memory_order_release);
                return waitResult;
            }
        }
        return AAUDIO_OK;
    }
    const auto result = AAudioStream_requestStart(g_stream);
    if (result != AAUDIO_OK) g_output->active.store(false, std::memory_order_release);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeSetStreamEof(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_output) g_output->eof.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetStreamBufferedFrames(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output) return 0;
    const uint64_t write = g_output->writeFrame.load(std::memory_order_acquire);
    const uint64_t read = g_output->readFrame.load(std::memory_order_acquire);
    return static_cast<jint>(write - read);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetOutputPositionFrames(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_output ? static_cast<jlong>(g_output->playedFrames.load(std::memory_order_acquire)) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeSetOutputVolume(JNIEnv*, jobject, jfloat volume) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_output) g_output->volume.store(std::clamp(volume, 0.0f, 1.0f));
}

namespace {
aaudio_result_t waitForStreamState(AAudioStream* stream,
                                   aaudio_stream_state_t transientState,
                                   aaudio_stream_state_t targetState) {
    auto state = AAudioStream_getState(stream);
    for (int attempt = 0; attempt < 4; ++attempt) {
        if (state == targetState) return AAUDIO_OK;
        if (state == AAUDIO_STREAM_STATE_DISCONNECTED) return AAUDIO_ERROR_DISCONNECTED;
        if (state != transientState) return AAUDIO_ERROR_INVALID_STATE;

        aaudio_stream_state_t nextState = state;
        const auto result = AAudioStream_waitForStateChange(
            stream, state, &nextState, 1'000'000'000LL);
        if (result != AAUDIO_OK) return result;
        state = nextState;
    }
    return state == targetState ? AAUDIO_OK : AAUDIO_ERROR_TIMEOUT;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativePauseOutput(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_stream) return AAUDIO_ERROR_INVALID_STATE;

    auto state = AAudioStream_getState(g_stream);
    if (state == AAUDIO_STREAM_STATE_PAUSED) return AAUDIO_OK;
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) return AAUDIO_ERROR_DISCONNECTED;
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        const auto result = AAudioStream_requestPause(g_stream);
        if (result != AAUDIO_OK) return result;
        state = AAudioStream_getState(g_stream);
    }
    if (state == AAUDIO_STREAM_STATE_PAUSED) {
        if (g_output) resetCallbackTimingBaseline(g_output.get());
        return AAUDIO_OK;
    }
    if (state == AAUDIO_STREAM_STATE_PAUSING) {
        const auto result = waitForStreamState(
            g_stream, AAUDIO_STREAM_STATE_PAUSING, AAUDIO_STREAM_STATE_PAUSED);
        if (result == AAUDIO_OK && g_output) resetCallbackTimingBaseline(g_output.get());
        return result;
    }
    return AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeResumeOutput(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_stream) return AAUDIO_ERROR_INVALID_STATE;

    auto state = AAudioStream_getState(g_stream);
    if (state == AAUDIO_STREAM_STATE_STARTED) return AAUDIO_OK;
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) return AAUDIO_ERROR_DISCONNECTED;
    if (state == AAUDIO_STREAM_STATE_PAUSED) {
        if (g_output) resetCallbackTimingBaseline(g_output.get());
        const auto result = AAudioStream_requestStart(g_stream);
        if (result != AAUDIO_OK) return result;
        state = AAudioStream_getState(g_stream);
    }
    if (state == AAUDIO_STREAM_STATE_STARTED) return AAUDIO_OK;
    if (state == AAUDIO_STREAM_STATE_STARTING) {
        return waitForStreamState(
            g_stream, AAUDIO_STREAM_STATE_STARTING, AAUDIO_STREAM_STATE_STARTED);
    }
    return AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeStopOutput(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_output) g_output->active.store(false, std::memory_order_release);
    stopLocked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeIsOutputActive(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_output && g_output->active.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetOutputUnderrunCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output) return 0;
    const uint64_t count = g_output->underrunCount.load(std::memory_order_acquire);
    return count > static_cast<uint64_t>(INT32_MAX) ? INT32_MAX : static_cast<jint>(count);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetLastOutputOpenDiagnostics(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const jlong values[11] = {
        g_lastOpenDiagnostics.requestedDeviceId,
        g_lastOpenDiagnostics.requestedSharingMode,
        g_lastOpenDiagnostics.requestedSampleRate,
        g_lastOpenDiagnostics.requestedChannels,
        g_lastOpenDiagnostics.openResult,
        g_lastOpenDiagnostics.actualDeviceId,
        g_lastOpenDiagnostics.actualSharingMode,
        g_lastOpenDiagnostics.actualSampleRate,
        g_lastOpenDiagnostics.actualChannels,
        g_lastOpenDiagnostics.actualFormat,
        g_lastOpenDiagnostics.failureStage
    };
    jlongArray result = env->NewLongArray(11);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 11, values);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetOutputDiagnostics(JNIEnv* env, jobject) {
    jlong values[36] = {};
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_output) {
        values[0] = static_cast<jlong>(g_output->underrunCount.load(std::memory_order_acquire));
        values[2] = static_cast<jlong>(g_output->writeFrame.load(std::memory_order_acquire) -
                                      g_output->readFrame.load(std::memory_order_acquire));
        values[3] = static_cast<jlong>(g_output->capacityFrames);
        values[13] = static_cast<jlong>(g_output->callbackCount.load(std::memory_order_acquire));
        values[14] = static_cast<jlong>(g_output->callbackFramesRequested.load(std::memory_order_acquire));
        values[15] = static_cast<jlong>(g_output->callbackFramesDelivered.load(std::memory_order_acquire));
        values[16] = static_cast<jlong>(g_output->callbackSilenceFrames.load(std::memory_order_acquire));
        values[17] = static_cast<jlong>(g_output->callbackMaxFrames.load(std::memory_order_acquire));
        values[18] = static_cast<jlong>(g_output->callbackUnexpectedSilenceFrames.load(std::memory_order_acquire));
        values[19] = static_cast<jlong>(g_output->exclusiveOpenResult);
        values[20] = static_cast<jlong>(g_output->sharedOpenResult);
        values[21] = static_cast<jlong>(g_output->callbackMaxDurationNs.load(std::memory_order_acquire));
        values[22] = static_cast<jlong>(g_output->callbackMaxIntervalNs.load(std::memory_order_acquire));
        values[23] = static_cast<jlong>(g_output->sampleRate);
        values[24] = static_cast<jlong>(g_output->callbackMaxLatenessNs.load(std::memory_order_acquire));
        values[25] = static_cast<jlong>(g_output->callbackLateIntervalCount.load(std::memory_order_acquire));
        values[31] = static_cast<jlong>(AAUDIO_SHARING_MODE_EXCLUSIVE);
        values[32] = static_cast<jlong>(g_output->successfulOpenSharingMode);
        values[33] = static_cast<jlong>(g_output->requestedDeviceId);
        values[34] = static_cast<jlong>(g_output->callbackLastIntervalNs.load(std::memory_order_acquire));
        values[35] = static_cast<jlong>(g_output->callbackLastLatenessNs.load(std::memory_order_acquire));
    }
    if (g_stream) {
        const int32_t xruns = AAudioStream_getXRunCount(g_stream);
        values[1] = xruns >= 0 ? xruns : -1;
        values[4] = AAudioStream_getBufferSizeInFrames(g_stream);
        values[26] = AAudioStream_getBufferCapacityInFrames(g_stream);
        values[5] = AAudioStream_getFramesPerBurst(g_stream);
        values[6] = static_cast<jlong>(AAudioStream_getState(g_stream));
        values[7] = AAudioStream_getSampleRate(g_stream);
        values[8] = AAudioStream_getDeviceId(g_stream);
        values[9] = AAudioStream_getSharingMode(g_stream);
        values[10] = AAudioStream_getFormat(g_stream);
        values[11] = AAudioStream_getChannelCount(g_stream);
        values[12] = AAudioStream_getPerformanceMode(g_stream);
        int64_t timestampFramePosition = 0;
        int64_t timestampTimeNanoseconds = 0;
        values[27] = AAudioStream_getTimestamp(
            g_stream, CLOCK_MONOTONIC, &timestampFramePosition, &timestampTimeNanoseconds);
        values[28] = timestampFramePosition;
        values[29] = timestampTimeNanoseconds;
        values[30] = AAudioStream_getFramesWritten(g_stream);
    }
    jlongArray result = env->NewLongArray(36);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 36, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeTuneOutputBuffer(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output || !g_stream) return AAUDIO_ERROR_INVALID_STATE;
    if (!g_output->active.load(std::memory_order_acquire) ||
        AAudioStream_getState(g_stream) != AAUDIO_STREAM_STATE_STARTED) return AAUDIO_OK;
    const int32_t xrunCount = AAudioStream_getXRunCount(g_stream);
    if (xrunCount < 0) return xrunCount;
    const int32_t previous = g_output->lastTunedXrunCount.load(std::memory_order_relaxed);
    if (xrunCount <= previous) return AAUDIO_OK;

    const int32_t burst = std::max(AAudioStream_getFramesPerBurst(g_stream), 1);
    const int32_t currentSize = AAudioStream_getBufferSizeInFrames(g_stream);
    const int32_t capacity = AAudioStream_getBufferCapacityInFrames(g_stream);
    if (currentSize > 0 && capacity > currentSize) {
        const int32_t missedBursts = std::max(1, xrunCount - previous);
        const int64_t growth = static_cast<int64_t>(burst) * missedBursts;
        const int32_t requested = static_cast<int32_t>(
            std::min<int64_t>(capacity, static_cast<int64_t>(currentSize) + growth));
        const int32_t adjusted = AAudioStream_setBufferSizeInFrames(g_stream, requested);
        if (adjusted < 0) return adjusted;
    }
    g_output->lastTunedXrunCount.store(xrunCount, std::memory_order_relaxed);
    return AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeGetOutputError(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_output ? g_output->errorCode.load(std::memory_order_acquire) : AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeSelectOutputSampleRate(
    JNIEnv*, jobject, jint requestedRate, jint channels, jint deviceId, jint outputFormat) {
    if (requestedRate <= 0 || (channels != 1 && channels != 2) ||
        (outputFormat != AAUDIO_FORMAT_PCM_FLOAT && outputFormat != AAUDIO_FORMAT_PCM_I16 &&
         outputFormat != AAUDIO_FORMAT_PCM_I24_PACKED && outputFormat != AAUDIO_FORMAT_PCM_I32))
        return AAUDIO_ERROR_INVALID_FORMAT;
    std::lock_guard<std::mutex> lock(g_mutex);
    stopLocked();
    OutputState probe;
    probe.channels = channels;
    probe.format = static_cast<aaudio_format_t>(outputFormat);
    auto tryRate = [&](int32_t rate) -> int32_t {
        auto result = openStream(&probe, rate, channels, AAUDIO_SHARING_MODE_EXCLUSIVE, deviceId);
        if (result != AAUDIO_OK) result = openStream(&probe, rate, channels, AAUDIO_SHARING_MODE_SHARED, deviceId);
        if (result != AAUDIO_OK) return 0;
        const int32_t actual = AAudioStream_getSampleRate(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return actual;
    };
    int32_t selected = tryRate(requestedRate);
    if (selected == 0) selected = tryRate(0);
    return selected > 0 ? selected : AAUDIO_ERROR_UNAVAILABLE;
}
