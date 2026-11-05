#include <jni.h>
#include <libusb.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <sys/time.h>
#include <utility>
#include <vector>

namespace {

// Isochronous transport tuning.
//
// The transport keeps a bounded sliding window of small URBs queued on the
// endpoint instead of submitting a whole decoded block at once. Each URB carries
// at most kPacketsPerUrb service intervals; at a 125 us interval this is 8 ms of
// audio. Up to kMaxUrbsInFlight URBs are queued, which yields roughly 128 ms of
// look-ahead at a 125 us interval. The window is replenished as URBs complete
// and is intentionally left queued between JNI writes, so the endpoint does not
// run dry while the producer decodes the next block.
constexpr size_t kPacketsPerUrb = 64;
constexpr size_t kMaxUrbsInFlight = 16;
// No URB completion for this long while URBs are queued is treated as a stalled
// endpoint. libusb's per-transfer timeout is deliberately disabled (set to 0)
// because it cancels queued isochronous URBs and breaks the scheduling chain.
constexpr int kStallTimeoutMs = 1500;
// Upper bound on how long teardown waits for cancelled URBs to be reaped.
constexpr int kTeardownTimeoutMs = 2000;

struct InFlightTransfer {
    std::vector<unsigned char> buffer;
    libusb_transfer* transfer = nullptr;
    bool completed = false;
    uint64_t sequence = 0;
};

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
    // PCM that has been accepted from the caller but not yet submitted. The
    // region [pending_offset, size) is live; the prefix is compacted lazily.
    std::vector<unsigned char> pending_pcm;
    size_t pending_offset = 0;
    // Transfers currently queued on the endpoint, in submission order.
    std::vector<std::unique_ptr<InFlightTransfer>> in_flight;
    uint64_t next_sequence = 0;
    std::chrono::steady_clock::time_point last_progress{};
    bool claimed = false;
    bool control_claimed = false;
    int control_interface = -1;
};

// Describes the first failure observed on the transport.
struct TransportFailure {
    bool failed = false;
    const char* reason = "none";
    int code = LIBUSB_SUCCESS;
    uint64_t sequence = 0;
    unsigned int transfer_status = 0;
    int failed_packet = -1;
    unsigned int packet_status = 0;
    unsigned int actual_length = 0;
    unsigned int requested_length = 0;
};

void LIBUSB_CALL on_transfer_complete(libusb_transfer* transfer) {
    auto* entry = static_cast<InFlightTransfer*>(transfer->user_data);
    if (entry != nullptr) entry->completed = true;
}

const char* transfer_status_name(unsigned int status) {
    switch (status) {
        case LIBUSB_TRANSFER_COMPLETED: return "COMPLETED";
        case LIBUSB_TRANSFER_ERROR: return "ERROR";
        case LIBUSB_TRANSFER_TIMED_OUT: return "TIMED_OUT";
        case LIBUSB_TRANSFER_CANCELLED: return "CANCELLED";
        case LIBUSB_TRANSFER_STALL: return "STALL";
        case LIBUSB_TRANSFER_NO_DEVICE: return "NO_DEVICE";
        case LIBUSB_TRANSFER_OVERFLOW: return "OVERFLOW";
        default: return "UNKNOWN";
    }
}

void record_failure(TransportFailure* failure, const char* reason, int code) {
    if (failure->failed) return;
    failure->failed = true;
    failure->reason = reason;
    failure->code = code;
}

// Runs the libusb event loop once and reports an unrecoverable loop error.
bool pump_events(UsbAudioSession* session, long timeout_us, TransportFailure* failure) {
    timeval timeout{0, timeout_us};
    const int result = libusb_handle_events_timeout_completed(session->context, &timeout, nullptr);
    if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_INTERRUPTED) {
        record_failure(failure, "event_loop_error", result);
        return false;
    }
    return true;
}

// Releases every completed transfer and records the first transport failure.
void reap_completed(UsbAudioSession* session, TransportFailure* failure) {
    auto& queue = session->in_flight;
    size_t write_index = 0;
    for (size_t read_index = 0; read_index < queue.size(); ++read_index) {
        std::unique_ptr<InFlightTransfer>& entry = queue[read_index];
        if (!entry->completed) {
            if (write_index != read_index) queue[write_index] = std::move(entry);
            ++write_index;
            continue;
        }
        session->last_progress = std::chrono::steady_clock::now();
        const libusb_transfer* transfer = entry->transfer;
        if (!failure->failed) {
            if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
                record_failure(failure, "transfer_error", LIBUSB_ERROR_IO);
                failure->sequence = entry->sequence;
                failure->transfer_status = static_cast<unsigned int>(transfer->status);
            } else {
                for (int i = 0; i < transfer->num_iso_packets; ++i) {
                    const auto& packet = transfer->iso_packet_desc[i];
                    if (packet.status != LIBUSB_TRANSFER_COMPLETED ||
                        packet.actual_length != packet.length) {
                        record_failure(failure, "packet_error", LIBUSB_ERROR_IO);
                        failure->sequence = entry->sequence;
                        failure->transfer_status = static_cast<unsigned int>(transfer->status);
                        failure->failed_packet = i;
                        failure->packet_status = static_cast<unsigned int>(packet.status);
                        failure->actual_length = packet.actual_length;
                        failure->requested_length = packet.length;
                        break;
                    }
                }
            }
        }
        libusb_free_transfer(entry->transfer);
        entry.reset();
    }
    queue.resize(write_index);
}

// Cancels all queued transfers and waits (bounded) for them to be reaped. A
// transfer that cannot be reaped within the bound is intentionally leaked rather
// than freed, because the kernel may still reference its buffer.
void abort_in_flight(UsbAudioSession* session) {
    for (auto& entry : session->in_flight) {
        if (!entry->completed) libusb_cancel_transfer(entry->transfer);
    }
    TransportFailure ignored;
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(kTeardownTimeoutMs);
    while (!session->in_flight.empty() && std::chrono::steady_clock::now() < deadline) {
        reap_completed(session, &ignored);
        if (session->in_flight.empty()) break;
        if (!pump_events(session, 20000, &ignored)) break;
    }
    reap_completed(session, &ignored);
    for (auto& entry : session->in_flight) {
        (void)entry.release();
    }
    session->in_flight.clear();
}

void close_session(UsbAudioSession* session) {
    if (session == nullptr) return;
    if (session->handle != nullptr) {
        abort_in_flight(session);
        if (session->claimed) {
            // Restore the zero-bandwidth alternate setting before releasing the streaming interface.
            libusb_set_interface_alt_setting(session->handle, session->interface_number, 0);
            libusb_release_interface(session->handle, session->interface_number);
        }
        if (session->control_claimed) {
            libusb_release_interface(session->handle, session->control_interface);
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
    session->control_interface = control_interface;
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

// Raises an IllegalStateException describing the transport failure, including
// symbolic transfer and packet status names for diagnostics.
void throw_transport_failure(JNIEnv* env, const UsbAudioSession* session,
                             const TransportFailure& failure) {
    // Transfer and packet statuses are only meaningful for completion failures.
    const bool has_status = std::strcmp(failure.reason, "transfer_error") == 0 ||
                            std::strcmp(failure.reason, "packet_error") == 0;
    const char* transfer_name = has_status ? transfer_status_name(failure.transfer_status) : "n/a";
    const char* packet_name = failure.failed_packet >= 0 ? transfer_status_name(failure.packet_status) : "n/a";
    char message[768];
    std::snprintf(
        message, sizeof(message),
        "USB isochronous PCM transfer failed: reason=%s; %s (%d); "
        "transfer_status=%u (%s); sequence=%llu; in_flight=%zu; failed_packet=%d; "
        "packet_status=%u (%s); actual_length=%u; requested_length=%u; "
        "interval_us=%u; sample_rate=%d; endpoint=0x%02X",
        failure.reason, libusb_error_name(failure.code), failure.code,
        failure.transfer_status, transfer_name,
        static_cast<unsigned long long>(failure.sequence), session->in_flight.size(),
        failure.failed_packet, failure.packet_status,
        packet_name, failure.actual_length,
        failure.requested_length, session->interval_us, session->sample_rate,
        session->endpoint);
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

// Queues one isochronous URB whose packets have the given byte lengths. The
// payload is consumed from the front of the pending PCM region.
bool submit_urb(UsbAudioSession* session, const std::vector<unsigned int>& packet_bytes,
                size_t total_bytes, TransportFailure* failure) {
    auto entry = std::make_unique<InFlightTransfer>();
    entry->sequence = session->next_sequence;
    entry->buffer.assign(
        session->pending_pcm.begin() + static_cast<std::ptrdiff_t>(session->pending_offset),
        session->pending_pcm.begin() +
            static_cast<std::ptrdiff_t>(session->pending_offset + total_bytes));

    const int packet_count = static_cast<int>(packet_bytes.size());
    entry->transfer = libusb_alloc_transfer(packet_count);
    if (entry->transfer == nullptr) {
        record_failure(failure, "alloc_failed", LIBUSB_ERROR_NO_MEM);
        return false;
    }
    // Timeout 0: the stall watchdog in the caller supervises progress instead.
    libusb_fill_iso_transfer(
        entry->transfer, session->handle, session->endpoint, entry->buffer.data(),
        static_cast<int>(total_bytes), packet_count, on_transfer_complete, entry.get(), 0);
    for (int i = 0; i < packet_count; ++i) {
        entry->transfer->iso_packet_desc[i].length = packet_bytes[static_cast<size_t>(i)];
    }

    if (session->in_flight.empty()) session->last_progress = std::chrono::steady_clock::now();
    const int result = libusb_submit_transfer(entry->transfer);
    if (result != LIBUSB_SUCCESS) {
        libusb_free_transfer(entry->transfer);
        record_failure(failure, "submit_failed", result);
        failure->sequence = entry->sequence;
        return false;
    }
    session->pending_offset += total_bytes;
    ++session->next_sequence;
    session->in_flight.push_back(std::move(entry));
    return true;
}

// Waits for queue progress and enforces the stall watchdog. Returns false and
// records a failure when the endpoint stops completing URBs.
bool wait_for_progress(UsbAudioSession* session, TransportFailure* failure) {
    if (!pump_events(session, 5000, failure)) return false;
    reap_completed(session, failure);
    if (failure->failed) return false;
    if (!session->in_flight.empty()) {
        const auto idle = std::chrono::steady_clock::now() - session->last_progress;
        if (idle > std::chrono::milliseconds(kStallTimeoutMs)) {
            record_failure(failure, "stalled", LIBUSB_ERROR_TIMEOUT);
            failure->sequence = session->in_flight.front()->sequence;
            return false;
        }
    }
    return true;
}

// Drops the consumed prefix of the pending PCM region.
void compact_pending(UsbAudioSession* session) {
    if (session->pending_offset == 0) return;
    session->pending_pcm.erase(
        session->pending_pcm.begin(),
        session->pending_pcm.begin() + static_cast<std::ptrdiff_t>(session->pending_offset));
    session->pending_offset = 0;
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

    if (control_interface != interface_number) {
        result = libusb_claim_interface(session->handle, control_interface);
        if (result != LIBUSB_SUCCESS) {
            close_session(session);
            return throw_usb_error(env, "Claiming USB Audio Control interface for clock negotiation", result);
        }
        session->control_claimed = true;
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

    const size_t bytes_per_frame =
        static_cast<size_t>(session->channels) * static_cast<size_t>(session->subslot_bytes);
    const jsize byte_count = env->GetArrayLength(pcm);
    if (static_cast<size_t>(byte_count) != static_cast<size_t>(frame_count) * bytes_per_frame) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(exception, "PCM byte count does not match frame count and DAC subslot format");
        return -1;
    }

    const size_t maximum_pending_bytes =
        static_cast<size_t>(session->sample_rate) * 2U * bytes_per_frame;
    const size_t live_pending = session->pending_pcm.size() - session->pending_offset;
    if (live_pending + static_cast<size_t>(byte_count) > maximum_pending_bytes) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, "USB PCM queue is full; the producer is faster than the DAC transport");
        return -1;
    }

    compact_pending(session);
    const size_t previous_size = session->pending_pcm.size();
    session->pending_pcm.resize(previous_size + static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(
        pcm, 0, byte_count,
        reinterpret_cast<jbyte*>(session->pending_pcm.data() + previous_size));
    if (env->ExceptionCheck()) {
        session->pending_pcm.resize(previous_size);
        return -1;
    }

    TransportFailure failure;
    std::vector<unsigned int> packet_bytes;
    packet_bytes.reserve(kPacketsPerUrb);

    while (true) {
        reap_completed(session, &failure);
        if (failure.failed) break;

        // Plan the next URB from whole service intervals only; a trailing
        // partial interval stays staged until the next write or drain.
        const size_t available_frames =
            (session->pending_pcm.size() - session->pending_offset) / bytes_per_frame;
        packet_bytes.clear();
        uint64_t phase = session->frame_phase;
        size_t planned_frames = 0;
        while (packet_bytes.size() < kPacketsPerUrb) {
            const uint64_t accumulated =
                phase + static_cast<uint64_t>(session->sample_rate) * session->interval_us;
            const unsigned int frames = static_cast<unsigned int>(accumulated / 1000000ULL);
            if (frames == 0 || planned_frames + frames > available_frames) break;
            const unsigned int packet_size = static_cast<unsigned int>(frames * bytes_per_frame);
            if (packet_size > session->max_packet_bytes) {
                record_failure(&failure, "packet_exceeds_endpoint_capacity", LIBUSB_ERROR_OVERFLOW);
                break;
            }
            packet_bytes.push_back(packet_size);
            planned_frames += frames;
            phase = accumulated % 1000000ULL;
        }
        if (failure.failed || packet_bytes.empty()) break;

        // Back-pressure: block until the window has room for another URB.
        if (session->in_flight.size() >= kMaxUrbsInFlight) {
            if (!wait_for_progress(session, &failure)) break;
            continue;
        }

        if (!submit_urb(session, packet_bytes, planned_frames * bytes_per_frame, &failure)) break;
        session->frame_phase = phase;
    }

    if (failure.failed) {
        abort_in_flight(session);
        throw_transport_failure(env, session, failure);
        return -1;
    }
    return frame_count;
}

// Submits any staged partial service interval as a short final packet and waits
// until every queued URB has completed. Called at natural end of playback so the
// tail of the stream is not truncated by session teardown.
extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeDrain(
    JNIEnv* env, jobject, jlong handle) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr) return -1;

    TransportFailure failure;
    reap_completed(session, &failure);

    const size_t remaining = session->pending_pcm.size() - session->pending_offset;
    if (!failure.failed && remaining > 0) {
        while (session->in_flight.size() >= kMaxUrbsInFlight) {
            if (!wait_for_progress(session, &failure)) break;
        }
        if (!failure.failed) {
            const std::vector<unsigned int> tail{static_cast<unsigned int>(remaining)};
            if (remaining <= session->max_packet_bytes) {
                submit_urb(session, tail, remaining, &failure);
            } else {
                record_failure(&failure, "packet_exceeds_endpoint_capacity", LIBUSB_ERROR_OVERFLOW);
            }
        }
    }
    while (!failure.failed && !session->in_flight.empty()) {
        wait_for_progress(session, &failure);
    }

    if (failure.failed) {
        abort_in_flight(session);
        throw_transport_failure(env, session, failure);
        return -1;
    }
    compact_pending(session);
    return 0;
}

// Discards staged PCM and cancels queued URBs. Called on seek so that audio
// from the previous position is not played after the new position is selected.
extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeFlush(
    JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr) return;
    abort_in_flight(session);
    session->pending_pcm.clear();
    session->pending_offset = 0;
    session->frame_phase = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    close_session(reinterpret_cast<UsbAudioSession*>(handle));
}
