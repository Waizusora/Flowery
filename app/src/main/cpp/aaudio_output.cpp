#include <aaudio/AAudio.h>
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

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
    std::atomic<uint64_t> flushEpoch{0};
    int32_t channels = 2;
};

std::mutex g_mutex;
AAudioStream* g_stream = nullptr;
std::unique_ptr<OutputState> g_output;

void errorCallback(AAudioStream*, void* userData, aaudio_result_t error) {
    auto* state = static_cast<OutputState*>(userData);
    state->errorCode.store(error, std::memory_order_release);
    state->active.store(false, std::memory_order_release);
}

aaudio_data_callback_result_t dataCallback(AAudioStream*, void* userData, void* audioData, int32_t frames) {
    auto* state = static_cast<OutputState*>(userData);
    auto* output = static_cast<float*>(audioData);
    const uint64_t epoch = state->flushEpoch.load(std::memory_order_acquire);
    if ((epoch & 1U) != 0) {
        std::fill(output, output + static_cast<uint64_t>(frames) * state->channels, 0.0f);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    const uint64_t read = state->readFrame.load(std::memory_order_relaxed);
    const uint64_t write = state->writeFrame.load(std::memory_order_acquire);
    const uint64_t available = write - read;
    const uint64_t count = std::min<uint64_t>(static_cast<uint64_t>(frames), available);
    const float gain = state->volume.load(std::memory_order_relaxed);

    for (uint64_t frame = 0; frame < count; ++frame) {
        const uint64_t ringFrame = (read + frame) & state->mask;
        for (int32_t channel = 0; channel < state->channels; ++channel) {
            const size_t ringIndex = static_cast<size_t>(ringFrame * state->channels + channel);
            output[frame * state->channels + channel] =
                std::clamp(state->ring[ringIndex] * gain, -1.0f, 1.0f);
        }
    }
    const uint64_t remaining = static_cast<uint64_t>(frames) - count;
    if (remaining > 0) {
        std::fill(output + count * state->channels,
                  output + static_cast<uint64_t>(frames) * state->channels, 0.0f);
    }
    if (state->flushEpoch.load(std::memory_order_acquire) != epoch) {
        std::fill(output, output + static_cast<uint64_t>(frames) * state->channels, 0.0f);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    state->readFrame.store(read + count, std::memory_order_release);

    if (remaining > 0 && state->eof.load(std::memory_order_acquire) &&
        state->writeFrame.load(std::memory_order_acquire) == read + count) {
        state->active.store(false, std::memory_order_release);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void stopLocked() {
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
    g_output.reset();
}

aaudio_result_t openStream(OutputState* state, int32_t rate, int32_t channels,
                           aaudio_sharing_mode_t sharing, int32_t deviceId) {
    AAudioStreamBuilder* builder = nullptr;
    auto result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return result;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(builder, deviceId);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setSharingMode(builder, sharing);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, state);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, state);
    result = AAudioStreamBuilder_openStream(builder, &g_stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return result;

    if (AAudioStream_getFormat(g_stream) != AAUDIO_FORMAT_PCM_FLOAT ||
        (rate > 0 && AAudioStream_getSampleRate(g_stream) != rate) ||
        AAudioStream_getChannelCount(g_stream) != channels) {
        AAudioStream_close(g_stream);
        g_stream = nullptr;
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    return AAUDIO_OK;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeStartStreamOutput(
    JNIEnv*, jobject, jint rate, jint channels, jfloat volume, jint deviceId, jint capacityFrames) {
    if (rate <= 0 || (channels != 1 && channels != 2) || capacityFrames < 2048) {
        return AAUDIO_ERROR_INVALID_FORMAT;
    }
    uint64_t capacity = 1;
    while (capacity < static_cast<uint64_t>(capacityFrames)) capacity <<= 1;

    auto state = std::make_unique<OutputState>();
    state->channels = channels;
    state->capacityFrames = capacity;
    state->mask = capacity - 1;
    state->ring.resize(static_cast<size_t>(capacity) * channels);
    state->volume.store(std::clamp(volume, 0.0f, 1.0f));

    std::lock_guard<std::mutex> lock(g_mutex);
    stopLocked();
    g_output = std::move(state);
    auto result = openStream(g_output.get(), rate, channels, AAUDIO_SHARING_MODE_EXCLUSIVE, deviceId);
    if (result != AAUDIO_OK) {
        result = openStream(g_output.get(), rate, channels, AAUDIO_SHARING_MODE_SHARED, deviceId);
    }
    if (result != AAUDIO_OK) {
        stopLocked();
        return result;
    }
    g_output->active.store(true, std::memory_order_release);
    result = AAudioStream_requestStart(g_stream);
    if (result != AAUDIO_OK) {
        g_output->active.store(false, std::memory_order_release);
        stopLocked();
    }
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
    const uint64_t freeFrames = g_output->capacityFrames - (write - read);
    const uint64_t count = std::min<uint64_t>(static_cast<uint64_t>(frameCount), freeFrames);
    if (count == 0) return 0;

    const jsize arrayLength = env->GetArrayLength(input);
    const uint64_t sourceStart = static_cast<uint64_t>(sourceFrameOffset) * g_output->channels;
    const uint64_t sampleCount = count * g_output->channels;
    if (sourceStart + sampleCount > static_cast<uint64_t>(arrayLength)) return -1;

    std::vector<float> staging(static_cast<size_t>(sampleCount));
    env->GetFloatArrayRegion(input, static_cast<jsize>(sourceStart),
                             static_cast<jsize>(sampleCount), staging.data());
    if (env->ExceptionCheck()) return -1;

    for (uint64_t frame = 0; frame < count; ++frame) {
        const uint64_t ringFrame = (write + frame) & g_output->mask;
        for (int32_t channel = 0; channel < g_output->channels; ++channel) {
            g_output->ring[ringFrame * g_output->channels + channel] =
                staging[frame * g_output->channels + channel];
        }
    }
    g_output->writeFrame.store(write + count, std::memory_order_release);
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeFlushStreamOutput(JNIEnv*, jobject, jlong positionFrames) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_output || !g_stream || positionFrames < 0) return AAUDIO_ERROR_INVALID_STATE;
    g_output->flushEpoch.fetch_add(1, std::memory_order_acq_rel);
    g_output->readFrame.store(static_cast<uint64_t>(positionFrames), std::memory_order_release);
    g_output->writeFrame.store(static_cast<uint64_t>(positionFrames), std::memory_order_release);
    g_output->eof.store(false, std::memory_order_release);
    g_output->flushEpoch.fetch_add(1, std::memory_order_release);
    g_output->active.store(true, std::memory_order_release);
    auto state = AAudioStream_getState(g_stream);
    if (state == AAUDIO_STREAM_STATE_STARTED) return AAUDIO_OK;
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        g_output->active.store(false, std::memory_order_release);
        return AAUDIO_ERROR_DISCONNECTED;
    }
    if (state == AAUDIO_STREAM_STATE_STOPPING || state == AAUDIO_STREAM_STATE_PAUSING) {
        aaudio_stream_state_t nextState = state;
        const auto waitResult = AAudioStream_waitForStateChange(g_stream, state, &nextState, 1000000000LL);
        if (waitResult != AAUDIO_OK) {
            g_output->active.store(false, std::memory_order_release);
            return waitResult;
        }
        state = nextState;
    }
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED) {
        g_output->active.store(false, std::memory_order_release);
        return AAUDIO_ERROR_DISCONNECTED;
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
    return g_output ? static_cast<jlong>(g_output->readFrame.load(std::memory_order_acquire)) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeSetOutputVolume(JNIEnv*, jobject, jfloat volume) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_output) g_output->volume.store(std::clamp(volume, 0.0f, 1.0f));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativePauseOutput(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_stream ? AAudioStream_requestPause(g_stream) : AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeResumeOutput(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_stream ? AAudioStream_requestStart(g_stream) : AAUDIO_ERROR_INVALID_STATE;
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
Java_com_waizusora_flowery_NativeAudio_nativeGetOutputError(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_output ? g_output->errorCode.load(std::memory_order_acquire) : AAUDIO_ERROR_INVALID_STATE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_NativeAudio_nativeSelectOutputSampleRate(
    JNIEnv*, jobject, jint requestedRate, jint channels, jint deviceId) {
    if (requestedRate <= 0 || (channels != 1 && channels != 2)) return AAUDIO_ERROR_INVALID_FORMAT;
    std::lock_guard<std::mutex> lock(g_mutex);
    stopLocked();
    OutputState probe;
    probe.channels = channels;
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
