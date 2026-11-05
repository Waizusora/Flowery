#include <jni.h>
#include <libusb.h>

#include <algorithm>
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

uint32_t service_interval_us(libusb_speed speed, uint8_t b_interval) {
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

    const libusb_speed speed = libusb_get_device_speed(libusb_get_device(session->handle));
    session->interval_us = service_interval_us(speed, session->interval);

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

    std::vector<unsigned char> input(static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(pcm, 0, byte_count, reinterpret_cast<jbyte*>(input.data()));
    if (env->ExceptionCheck()) return -1;

    std::vector<unsigned int> packet_frames;
    uint64_t phase = session->frame_phase;
    int remaining_frames = frame_count;
    while (remaining_frames > 0) {
        phase += static_cast<uint64_t>(session->sample_rate) * session->interval_us;
        unsigned int frames = static_cast<unsigned int>(phase / 1000000ULL);
        phase %= 1000000ULL;
        if (frames == 0) continue;
        frames = std::min<unsigned int>(frames, static_cast<unsigned int>(remaining_frames));
        const unsigned int packet_bytes = frames * bytes_per_frame;
        if (packet_bytes > session->max_packet_bytes) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "Calculated isochronous packet exceeds the endpoint maximum packet size");
            return -1;
        }
        packet_frames.push_back(frames);
        remaining_frames -= static_cast<int>(frames);
        if (packet_frames.size() > 1024) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "PCM write exceeds the supported isochronous transfer packet count");
            return -1;
        }
    }

    const int packet_count = static_cast<int>(packet_frames.size());
    const int total_bytes = byte_count;
    auto* transfer = libusb_alloc_transfer(packet_count);
    if (transfer == nullptr) return LIBUSB_ERROR_NO_MEM;

    TransferCompletion completion;
    std::vector<unsigned char> transfer_buffer = std::move(input);
    libusb_fill_iso_transfer(
        transfer, session->handle, session->endpoint, transfer_buffer.data(), total_bytes,
        packet_count, on_transfer_complete, &completion, 3000);

    int offset = 0;
    for (int i = 0; i < packet_count; ++i) {
        const unsigned int packet_bytes = packet_frames[i] * bytes_per_frame;
        transfer->iso_packet_desc[i].length = packet_bytes;
        offset += static_cast<int>(packet_bytes);
    }

    int result = libusb_submit_transfer(transfer);
    if (result == LIBUSB_SUCCESS) {
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

    if (result != LIBUSB_SUCCESS && !completion.completed) {
        libusb_cancel_transfer(transfer);
        while (!completion.completed) {
            timeval timeout{0, 50000};
            libusb_handle_events_timeout_completed(session->context, &timeout, nullptr);
        }
    }
    libusb_free_transfer(transfer);

    if (result != LIBUSB_SUCCESS) {
        throw_usb_error(env, "USB isochronous PCM transfer", result);
        return result;
    }
    session->frame_phase = phase;
    return frame_count;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    close_session(reinterpret_cast<UsbAudioSession*>(handle));
}
