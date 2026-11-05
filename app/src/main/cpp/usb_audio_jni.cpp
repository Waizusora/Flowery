#include <jni.h>
#include <libusb.h>

#include <algorithm>
#include <cstdio>
#include <sys/time.h>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

namespace {

struct UsbAudioSession {
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    int interface_number = -1;
    uint8_t endpoint = 0;
    uint16_t max_packet_bytes = 0;
    uint8_t interval = 1;
    int channels = 2;
    int subslot_bytes = 0;
    int sample_rate = 0;
    uint64_t frame_phase = 0;
    uint32_t interval_us = 1000;
    std::vector<unsigned char> pending_pcm;
    bool claimed = false;
};

struct TransferCompletion {
    bool completed = false;
};

void LIBUSB_CALL on_transfer_complete(libusb_transfer* transfer) {
    auto* completion = static_cast<TransferCompletion*>(transfer->user_data);
    if (completion != nullptr) completion->completed = true;
}

void close_session(UsbAudioSession* session) {
    if (session == nullptr) return;
    if (session->handle != nullptr) {
        if (session->claimed) {
            // Restore the zero-bandwidth alternate setting before releasing the interface.
            libusb_set_interface_alt_setting(session->handle, session->interface_number, 0);
            libusb_release_interface(session->handle, session->interface_number);
        }
        libusb_close(session->handle);
    }
    if (session->context != nullptr) libusb_exit(session->context);
    delete session;
}

jlong throw_usb_error(JNIEnv* env, const char* operation, int code) {
    char message[256];
    std::snprintf(message, sizeof(message), "%s failed: %s (%d)",
                  operation, libusb_error_name(code), code);
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
    return 0;
}

uint32_t service_interval_us(int speed, uint8_t b_interval) {
    const uint8_t exponent = static_cast<uint8_t>(std::clamp<int>(b_interval, 1, 16) - 1);
    if (speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER ||
        speed == LIBUSB_SPEED_SUPER_PLUS) {
        return 125U << exponent;
    }
    return 1000U << exponent;
}

bool set_and_verify_clock(UsbAudioSession* session, int clock_id,
                          int control_interface, int sample_rate, int* error) {
    if (clock_id <= 0 || control_interface < 0) {
        *error = LIBUSB_ERROR_INVALID_PARAM;
        return false;
    }
    unsigned char rate_bytes[4] = {
        static_cast<unsigned char>(sample_rate & 0xff),
        static_cast<unsigned char>((sample_rate >> 8) & 0xff),
        static_cast<unsigned char>((sample_rate >> 16) & 0xff),
        static_cast<unsigned char>((sample_rate >> 24) & 0xff)
    };
    const uint16_t index = static_cast<uint16_t>((clock_id << 8) | control_interface);
    int result = libusb_control_transfer(
        session->handle, 0x21, 0x01, 0x0100, index, rate_bytes, sizeof(rate_bytes), 1500);
    if (result != 4) {
        *error = result < 0 ? result : LIBUSB_ERROR_IO;
        return false;
    }

    unsigned char current_rate[4] = {};
    result = libusb_control_transfer(
        session->handle, 0xA1, 0x01, 0x0100, index, current_rate, sizeof(current_rate), 1500);
    if (result != 4) {
        *error = result < 0 ? result : LIBUSB_ERROR_IO;
        return false;
    }
    const uint32_t confirmed =
        static_cast<uint32_t>(current_rate[0]) |
        (static_cast<uint32_t>(current_rate[1]) << 8) |
        (static_cast<uint32_t>(current_rate[2]) << 16) |
        (static_cast<uint32_t>(current_rate[3]) << 24);
    if (confirmed != static_cast<uint32_t>(sample_rate)) {
        *error = LIBUSB_ERROR_NOT_SUPPORTED;
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeOpen(
    JNIEnv* env, jobject, jint file_descriptor, jint interface_number,
    jint alternate_setting, jint endpoint, jint max_packet_bytes, jint interval,
    jint channels, jint subslot_bytes, jint sample_rate, jint clock_id,
    jint control_interface) {
    if (file_descriptor < 0 || interface_number < 0 || alternate_setting <= 0 ||
        endpoint <= 0 || (endpoint & 0x80) != 0 || max_packet_bytes <= 0 ||
        interval <= 0 || channels != 2 || subslot_bytes <= 0 || sample_rate <= 0) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(exception, "Invalid USB Audio Class playback parameters");
        return 0;
    }

    auto* session = new UsbAudioSession();
    libusb_init_option option{};
    option.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    int result = libusb_init_context(&session->context, &option, 1);
    if (result != LIBUSB_SUCCESS) {
        delete session;
        return throw_usb_error(env, "libusb initialization", result);
    }

    result = libusb_wrap_sys_device(
        session->context, static_cast<intptr_t>(file_descriptor), &session->handle);
    if (result != LIBUSB_SUCCESS) {
        close_session(session);
        return throw_usb_error(env, "Wrapping Android USB file descriptor", result);
    }

    session->interface_number = interface_number;
    session->endpoint = static_cast<uint8_t>(endpoint);
    session->max_packet_bytes = static_cast<uint16_t>(max_packet_bytes);
    session->interval = static_cast<uint8_t>(interval);
    session->channels = channels;
    session->subslot_bytes = subslot_bytes;
    session->sample_rate = sample_rate;

    const int speed = libusb_get_device_speed(libusb_get_device(session->handle));
    session->interval_us = service_interval_us(speed, session->interval);
    const uint64_t maximum_interval_frames =
        (static_cast<uint64_t>(sample_rate) * session->interval_us + 999999ULL) / 1000000ULL;
    const uint64_t maximum_interval_bytes =
        maximum_interval_frames * static_cast<uint64_t>(channels) *
        static_cast<uint64_t>(subslot_bytes);
    if (maximum_interval_bytes > session->max_packet_bytes) {
        close_session(session);
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(
            exception, "Requested sample rate and PCM format exceed the endpoint packet capacity");
        return 0;
    }

    result = libusb_set_auto_detach_kernel_driver(session->handle, 1);
    if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_NOT_SUPPORTED) {
        close_session(session);
        return throw_usb_error(env, "Detaching the system USB audio driver", result);
    }

    result = libusb_claim_interface(session->handle, interface_number);
    if (result != LIBUSB_SUCCESS) {
        close_session(session);
        return throw_usb_error(env, "Claiming USB Audio Streaming interface", result);
    }
    session->claimed = true;

    int clock_error = LIBUSB_SUCCESS;
    if (!set_and_verify_clock(session, clock_id, control_interface, sample_rate, &clock_error)) {
        close_session(session);
        return throw_usb_error(env, "UAC2 clock SET_CUR/GET_CUR verification", clock_error);
    }

    result = libusb_set_interface_alt_setting(
        session->handle, interface_number, alternate_setting);
    if (result != LIBUSB_SUCCESS) {
        close_session(session);
        return throw_usb_error(env, "Selecting USB Audio alternate setting", result);
    }

    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeWriteIsochronous(
    JNIEnv* env, jobject, jlong handle, jbyteArray pcm, jint frame_count) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr || pcm == nullptr || frame_count <= 0) return -1;

    const int bytes_per_frame = session->channels * session->subslot_bytes;
    const jsize byte_count = env->GetArrayLength(pcm);
    if (byte_count != frame_count * bytes_per_frame) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(exception, "PCM byte count does not match frame count and DAC subslot format");
        return -1;
    }

    const size_t maximum_pending_bytes =
        static_cast<size_t>(session->sample_rate) * 2U * static_cast<size_t>(bytes_per_frame);
    if (session->pending_pcm.size() + static_cast<size_t>(byte_count) > maximum_pending_bytes) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, "USB PCM queue is full; the producer is faster than the DAC transport");
        return -1;
    }

    const size_t previous_size = session->pending_pcm.size();
    session->pending_pcm.resize(previous_size + static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(
        pcm, 0, byte_count,
        reinterpret_cast<jbyte*>(session->pending_pcm.data() + previous_size));
    if (env->ExceptionCheck()) {
        session->pending_pcm.resize(previous_size);
        return -1;
    }

    uint64_t next_phase = session->frame_phase;
    size_t available_frames = session->pending_pcm.size() / static_cast<size_t>(bytes_per_frame);
    size_t consumed_frames = 0;
    std::vector<unsigned int> packet_frames;
    packet_frames.reserve(1024);

    while (packet_frames.size() < 1024) {
        const uint64_t accumulated =
            next_phase + static_cast<uint64_t>(session->sample_rate) * session->interval_us;
        const unsigned int frames = static_cast<unsigned int>(accumulated / 1000000ULL);
        if (frames == 0 || consumed_frames + frames > available_frames) break;
        const unsigned int packet_bytes = frames * static_cast<unsigned int>(bytes_per_frame);
        if (packet_bytes > session->max_packet_bytes) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "Calculated isochronous packet exceeds the endpoint maximum packet size");
            return -1;
        }
        packet_frames.push_back(frames);
        consumed_frames += frames;
        next_phase = accumulated % 1000000ULL;
    }

    if (packet_frames.empty()) return frame_count;

    const int packet_count = static_cast<int>(packet_frames.size());
    const int transfer_bytes = static_cast<int>(consumed_frames * bytes_per_frame);
    std::vector<unsigned char> transfer_buffer(
        session->pending_pcm.begin(),
        session->pending_pcm.begin() + transfer_bytes);

    auto* transfer = libusb_alloc_transfer(packet_count);
    if (transfer == nullptr) return LIBUSB_ERROR_NO_MEM;

    TransferCompletion completion;
    libusb_fill_iso_transfer(
        transfer, session->handle, session->endpoint, transfer_buffer.data(), transfer_bytes,
        packet_count, on_transfer_complete, &completion, 3000);

    for (int i = 0; i < packet_count; ++i) {
        transfer->iso_packet_desc[i].length =
            packet_frames[static_cast<size_t>(i)] * static_cast<unsigned int>(bytes_per_frame);
    }

    int result = libusb_submit_transfer(transfer);
    const bool submitted = result == LIBUSB_SUCCESS;
    if (submitted) {
        while (!completion.completed) {
            timeval timeout{0, 50000};
            result = libusb_handle_events_timeout_completed(session->context, &timeout, nullptr);
            if (result != LIBUSB_SUCCESS) break;
        }
        if (result == LIBUSB_SUCCESS) {
            if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
                result = LIBUSB_ERROR_IO;
            } else {
                for (int i = 0; i < packet_count; ++i) {
                    if (transfer->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED ||
                        transfer->iso_packet_desc[i].actual_length != transfer->iso_packet_desc[i].length) {
                        result = LIBUSB_ERROR_IO;
                        break;
                    }
                }
            }
        }
    }

    // A transfer rejected by libusb_submit_transfer has no completion callback.
    // Only submitted transfers may be cancelled and awaited.
    if (submitted && result != LIBUSB_SUCCESS && !completion.completed) {
        const int cancel_result = libusb_cancel_transfer(transfer);
        if (cancel_result == LIBUSB_SUCCESS) {
            while (!completion.completed) {
                timeval timeout{0, 50000};
                libusb_handle_events_timeout_completed(session->context, &timeout, nullptr);
            }
        } else if (cancel_result != LIBUSB_ERROR_NOT_FOUND) {
            result = cancel_result;
        }
    }
    libusb_free_transfer(transfer);

    if (result != LIBUSB_SUCCESS) {
        throw_usb_error(env, "USB isochronous PCM transfer", result);
        return result;
    }

    session->pending_pcm.erase(
        session->pending_pcm.begin(),
        session->pending_pcm.begin() + transfer_bytes);
    session->frame_phase = next_phase;
    return frame_count;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    close_session(reinterpret_cast<UsbAudioSession*>(handle));
}
