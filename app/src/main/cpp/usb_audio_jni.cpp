#include <jni.h>
#include <libusb.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <sys/time.h>
#include <thread>
#include <utility>
#include <vector>

namespace {

// Isochronous transport tuning.
//
// Keep the scheduling horizon deliberately short. A 64-packet URB covers 8 ms
// at a 125 us service interval; a 32 ms target therefore keeps roughly four
// URBs queued instead of the previous 192 ms / 24-URB window. This bounds
// endpoint/controller scheduling depth while retaining enough work to avoid
// submitting one service interval at a time.
constexpr size_t kPacketsPerUrb = 64;
constexpr size_t kMaxUrbsInFlight = 8;
constexpr uint64_t kTargetQueuedAudioUs = 32000ULL;
// No URB completion for this long while URBs are queued is treated as a stalled
// endpoint. libusb's per-transfer timeout is deliberately disabled (set to 0)
// because it cancels queued isochronous URBs and breaks the scheduling chain.
constexpr int kStallTimeoutMs = 1500;
// Upper bound on how long teardown waits for cancelled URBs to be reaped.
constexpr int kTeardownTimeoutMs = 2000;

struct UsbAudioSession;

struct InFlightTransfer {
    std::vector<unsigned char> buffer;
    libusb_transfer* transfer = nullptr;
    std::atomic<bool> completed{false};
    uint64_t sequence = 0;
    uint64_t frame_phase_before = 0;
    uint64_t frame_phase_after = 0;
    std::vector<unsigned int> requested_packet_lengths;
    UsbAudioSession* owner = nullptr;
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
    uint8_t feedback_endpoint = 0;
    uint16_t feedback_max_packet_bytes = 0;
    libusb_transfer* feedback_transfer = nullptr;
    std::vector<unsigned char> feedback_buffer;
    std::atomic<bool> feedback_stopped{false};
    std::atomic<bool> feedback_in_flight{false};
    std::atomic<bool> feedback_valid{false};
    std::atomic<uint32_t> feedback_rate_fp{0};
    std::atomic<int64_t> feedback_last_update_ns{0};
    uint8_t feedback_fraction_bits = 0;
    uint32_t feedback_base_interval_us = 0;
    uint64_t feedback_phase = 0;
    uint64_t frame_phase = 0;
    uint32_t interval_us = 1000;
    int device_speed = LIBUSB_SPEED_UNKNOWN;
    // PCM that has been accepted from the caller but not yet submitted. The
    // region [pending_offset, size) is live; the prefix is compacted lazily.
    std::vector<unsigned char> pending_pcm;
    size_t pending_offset = 0;
    // Encoded payload staging is intentionally separate from the PCM writer.
    std::vector<unsigned char> pending_encoded;
    size_t encoded_offset = 0;
    uint64_t encoded_bytes_per_second = 0;
    uint32_t encoded_frame_rate = 0;
    uint8_t encoded_frame_bytes = 0;
    uint64_t encoded_rate_phase = 0;
    uint64_t encoded_feedback_phase = 0;
    int encoded_mode = 0; // 0=PCM, 1=UAC1 Type-II, 2=IEC 61937 Type-III
    std::chrono::steady_clock::time_point next_encoded_frame_due{};
    bool encoded_frame_deadline_valid = false;
    // Transfers currently queued on the endpoint, in submission order.
    std::vector<std::unique_ptr<InFlightTransfer>> in_flight;
    uint64_t next_sequence = 0;
    std::chrono::steady_clock::time_point last_progress{};
    std::atomic<uint64_t> submitted_transfer_count{0};
    std::atomic<uint64_t> completed_transfer_callback_count{0};
    std::atomic<uint64_t> event_loop_iteration_count{0};
    std::atomic<int64_t> last_event_loop_ns{0};
    std::atomic<bool> event_thread_running{false};
    std::atomic<int> event_loop_error{LIBUSB_SUCCESS};
    std::thread event_thread;
    std::mutex event_mutex;
    std::condition_variable event_condition;
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
    unsigned int packet_count = 0;
    uint64_t frame_phase_before = 0;
    uint64_t frame_phase_after = 0;
    int scheduled_start_frame = 0;
    unsigned int transfer_flags = 0;
    int device_speed = LIBUSB_SPEED_UNKNOWN;
    std::vector<unsigned int> requested_packet_lengths;
    size_t in_flight_at_failure = 0;
    long long idle_ms_at_failure = 0;
    uint64_t submitted_at_failure = 0;
    uint64_t callbacks_at_failure = 0;
    uint64_t event_loops_at_failure = 0;
    int event_loop_error_at_failure = LIBUSB_SUCCESS;
    long long event_loop_idle_ms_at_failure = -1;
    bool snapshot_captured = false;
};

void LIBUSB_CALL on_transfer_complete(libusb_transfer* transfer) {
    auto* entry = static_cast<InFlightTransfer*>(transfer->user_data);
    if (entry != nullptr) {
        UsbAudioSession* owner = entry->owner;
        entry->completed.store(true, std::memory_order_release);
        if (owner != nullptr) {
            owner->completed_transfer_callback_count.fetch_add(1, std::memory_order_relaxed);
            owner->event_condition.notify_all();
        }
    }
}

void LIBUSB_CALL on_feedback_complete(libusb_transfer* transfer) {
    auto* session = static_cast<UsbAudioSession*>(transfer->user_data);
    if (session == nullptr) return;
    session->feedback_in_flight.store(false, std::memory_order_release);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->num_iso_packets > 0) {
        const auto& packet = transfer->iso_packet_desc[0];
        const unsigned char* data = libusb_get_iso_packet_buffer_simple(transfer, 0);
        if (packet.status == LIBUSB_TRANSFER_COMPLETED && data != nullptr) {
            uint32_t value = 0;
            if (session->feedback_fraction_bits == 14 && packet.actual_length >= 3) {
                value = static_cast<uint32_t>(data[0]) |
                        (static_cast<uint32_t>(data[1]) << 8) |
                        (static_cast<uint32_t>(data[2]) << 16);
            } else if (session->feedback_fraction_bits == 16 && packet.actual_length >= 4) {
                value = static_cast<uint32_t>(data[0]) |
                        (static_cast<uint32_t>(data[1]) << 8) |
                        (static_cast<uint32_t>(data[2]) << 16) |
                        (static_cast<uint32_t>(data[3]) << 24);
            }
            if (value != 0) {
                session->feedback_rate_fp.store(value, std::memory_order_release);
                const auto now = std::chrono::steady_clock::now().time_since_epoch();
                session->feedback_last_update_ns.store(
                    std::chrono::duration_cast<std::chrono::nanoseconds>(now).count(),
                    std::memory_order_release);
                session->feedback_valid.store(true, std::memory_order_release);
            }
        }
    }
    session->event_condition.notify_all();
    {
        std::lock_guard<std::mutex> lock(session->event_mutex);
        if (!session->feedback_stopped.load(std::memory_order_acquire) &&
            session->event_thread_running.load(std::memory_order_acquire)) {
            const int result = libusb_submit_transfer(transfer);
            if (result == LIBUSB_SUCCESS) {
                session->feedback_in_flight.store(true, std::memory_order_release);
            } else {
                session->event_loop_error.store(result, std::memory_order_release);
            }
        }
    }
    session->event_condition.notify_all();
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

// Capture counters before cancellation callbacks can change the observed totals.
void snapshot_transport_failure(const UsbAudioSession* session, TransportFailure* failure) {
    if (failure->snapshot_captured) return;
    failure->submitted_at_failure = session->submitted_transfer_count.load(std::memory_order_acquire);
    failure->callbacks_at_failure = session->completed_transfer_callback_count.load(std::memory_order_acquire);
    failure->event_loops_at_failure = session->event_loop_iteration_count.load(std::memory_order_acquire);
    failure->event_loop_error_at_failure = session->event_loop_error.load(std::memory_order_acquire);
    const int64_t last_ns = session->last_event_loop_ns.load(std::memory_order_acquire);
    if (last_ns > 0) {
        const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        failure->event_loop_idle_ms_at_failure =
            std::max<int64_t>(0, (now_ns - last_ns) / 1000000LL);
    }
    failure->snapshot_captured = true;
}

// Owns libusb event processing on a dedicated thread. Transfer submission and
// queue bookkeeping remain on the playback worker; callbacks only publish
// completion state and wake that worker.
void event_loop_main(UsbAudioSession* session) {
    while (session->event_thread_running.load(std::memory_order_acquire)) {
        timeval timeout{0, 10000};
        const int result = libusb_handle_events_timeout_completed(
            session->context, &timeout, nullptr);
        session->event_loop_iteration_count.fetch_add(1, std::memory_order_relaxed);
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        session->last_event_loop_ns.store(
            std::chrono::duration_cast<std::chrono::nanoseconds>(now).count(),
            std::memory_order_release);
        if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_INTERRUPTED) {
            session->event_loop_error.store(result, std::memory_order_release);
            session->event_condition.notify_all();
            break;
        }
    }
}

bool stop_event_thread(UsbAudioSession* session) {
    {
        std::lock_guard<std::mutex> lock(session->event_mutex);
        session->feedback_stopped.store(true, std::memory_order_release);
        if (session->feedback_transfer != nullptr &&
            session->feedback_in_flight.load(std::memory_order_acquire)) {
            libusb_cancel_transfer(session->feedback_transfer);
        }
    }
    if (session->feedback_transfer != nullptr) {
        const auto deadline = std::chrono::steady_clock::now() +
                              std::chrono::milliseconds(kTeardownTimeoutMs);
        while (session->feedback_in_flight.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline) {
            std::unique_lock<std::mutex> lock(session->event_mutex);
            session->event_condition.wait_for(lock, std::chrono::milliseconds(10));
        }
        if (session->feedback_in_flight.load(std::memory_order_acquire)) {
            // Keep the session, transfer buffer, and event loop alive rather than
            // freeing memory that the USB controller may still reference.
            return false;
        }
    }
    session->event_thread_running.store(false, std::memory_order_release);
    session->event_condition.notify_all();
    if (session->event_thread.joinable()) session->event_thread.join();
    if (session->feedback_transfer != nullptr &&
        !session->feedback_in_flight.load(std::memory_order_acquire)) {
        libusb_free_transfer(session->feedback_transfer);
        session->feedback_transfer = nullptr;
    }
    return true;
}

// Releases every completed transfer and records the first transport failure.
void reap_completed(UsbAudioSession* session, TransportFailure* failure) {
    auto& queue = session->in_flight;
    size_t write_index = 0;
    for (size_t read_index = 0; read_index < queue.size(); ++read_index) {
        std::unique_ptr<InFlightTransfer>& entry = queue[read_index];
        if (!entry->completed.load(std::memory_order_acquire)) {
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
                failure->in_flight_at_failure = queue.size();
                failure->transfer_status = static_cast<unsigned int>(transfer->status);
            } else {
                for (int i = 0; i < transfer->num_iso_packets; ++i) {
                    const auto& packet = transfer->iso_packet_desc[i];
                    if (packet.status != LIBUSB_TRANSFER_COMPLETED ||
                        packet.actual_length != packet.length) {
                        record_failure(failure, "packet_error", LIBUSB_ERROR_IO);
                        failure->sequence = entry->sequence;
                        failure->in_flight_at_failure = queue.size();
                        failure->transfer_status = static_cast<unsigned int>(transfer->status);
                        failure->failed_packet = i;
                        failure->packet_status = static_cast<unsigned int>(packet.status);
                        failure->actual_length = packet.actual_length;
                        failure->requested_length = packet.length;
                        failure->packet_count = static_cast<unsigned int>(transfer->num_iso_packets);
                        failure->frame_phase_before = entry->frame_phase_before;
                        failure->frame_phase_after = entry->frame_phase_after;
                        // libusb's public transfer structure does not expose the host-scheduled start frame.
                        failure->scheduled_start_frame = -1;
                        failure->transfer_flags = transfer->flags;
                        failure->device_speed = session->device_speed;
                        failure->requested_packet_lengths = entry->requested_packet_lengths;
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
bool abort_in_flight(UsbAudioSession* session) {
    for (auto& entry : session->in_flight) {
        if (!entry->completed.load(std::memory_order_acquire)) {
            libusb_cancel_transfer(entry->transfer);
        }
    }
    TransportFailure ignored;
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(kTeardownTimeoutMs);
    while (!session->in_flight.empty() && std::chrono::steady_clock::now() < deadline) {
        reap_completed(session, &ignored);
        if (session->in_flight.empty()) break;
        std::unique_lock<std::mutex> lock(session->event_mutex);
        session->event_condition.wait_for(lock, std::chrono::milliseconds(20));
    }
    reap_completed(session, &ignored);
    // Keep the session and transfer owners alive if libusb has not delivered all
    // cancellation callbacks. A late callback still references its owner session.
    return session->in_flight.empty();
}

bool close_session(UsbAudioSession* session) {
    if (session == nullptr) return true;
    if (session->handle != nullptr) {
        if (!abort_in_flight(session)) return false;
        if (!stop_event_thread(session)) return false;
        if (session->claimed) {
            // Restore the zero-bandwidth alternate setting before releasing the streaming interface.
            libusb_set_interface_alt_setting(session->handle, session->interface_number, 0);
            libusb_release_interface(session->handle, session->interface_number);
        }
        if (session->control_claimed) {
            libusb_release_interface(session->handle, session->control_interface);
        }
        libusb_close(session->handle);
        session->handle = nullptr;
    }
    if (session->context != nullptr) {
        libusb_exit(session->context);
        session->context = nullptr;
    }
    delete session;
    return true;
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

size_t max_urbs_in_flight(const UsbAudioSession* session) {
    const uint64_t urb_duration_us =
        static_cast<uint64_t>(kPacketsPerUrb) * session->interval_us;
    if (urb_duration_us == 0) return 1;
    const uint64_t target =
        (kTargetQueuedAudioUs + urb_duration_us - 1ULL) / urb_duration_us;
    return static_cast<size_t>(std::clamp<uint64_t>(target, 1ULL, kMaxUrbsInFlight));
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

bool set_and_verify_uac1_frequency(UsbAudioSession* session, int sample_rate, int* error) {
    if (sample_rate <= 0 || sample_rate > 0x00ffffff) {
        *error = LIBUSB_ERROR_INVALID_PARAM;
        return false;
    }
    const uint16_t index = session->endpoint;
    unsigned char rate_bytes[3] = {
        static_cast<unsigned char>(sample_rate & 0xff),
        static_cast<unsigned char>((sample_rate >> 8) & 0xff),
        static_cast<unsigned char>((sample_rate >> 16) & 0xff)
    };
    int result = libusb_control_transfer(
        session->handle, 0x22, 0x01, 0x0100, index, rate_bytes, sizeof(rate_bytes), 1500);
    if (result != 3) {
        *error = result < 0 ? result : LIBUSB_ERROR_IO;
        return false;
    }
    unsigned char current_rate[3] = {};
    result = libusb_control_transfer(
        session->handle, 0xA2, 0x81, 0x0100, index, current_rate, sizeof(current_rate), 1500);
    if (result != 3) {
        *error = result < 0 ? result : LIBUSB_ERROR_IO;
        return false;
    }
    const uint32_t confirmed = static_cast<uint32_t>(current_rate[0]) |
        (static_cast<uint32_t>(current_rate[1]) << 8) |
        (static_cast<uint32_t>(current_rate[2]) << 16);
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
    char packet_lengths[256] = {};
    if (!failure.requested_packet_lengths.empty() && failure.failed_packet >= 0) {
        const size_t failed = static_cast<size_t>(failure.failed_packet);
        const size_t begin = failed > 4 ? failed - 4 : 0;
        const size_t end = std::min(failure.requested_packet_lengths.size(), failed + 5);
        size_t used = 0;
        for (size_t index = begin; index < end && used < sizeof(packet_lengths); ++index) {
            const int written = std::snprintf(packet_lengths + used, sizeof(packet_lengths) - used,
                "%s%zu:%u", index == begin ? "" : ",", index,
                failure.requested_packet_lengths[index]);
            if (written < 0) break;
            used += std::min(static_cast<size_t>(written), sizeof(packet_lengths) - used);
        }
    }
    char message[1024];
    std::snprintf(
        message, sizeof(message),
        "USB isochronous transfer failed: reason=%s; %s (%d); "
        "transfer_status=%u (%s); sequence=%llu; in_flight_at_failure=%zu; idle_ms=%lld; failed_packet=%d; "
        "packet_status=%u (%s); actual_length=%u; requested_length=%u; "
        "packet_count=%u; phase_before=%llu; phase_after=%llu; start_frame=%d; "
        "transfer_flags=%u; device_speed=%d; packet_lengths_near_failure=%s; "
        "interval_us=%u; sample_rate=%d; endpoint=0x%02X; "
        "submitted=%llu; callbacks=%llu; event_loops=%llu; "
        "event_loop_error=%d; event_loop_idle_ms=%lld; "
        "queue_target_us=%llu; max_urbs=%zu",
        failure.reason, libusb_error_name(failure.code), failure.code,
        failure.transfer_status, transfer_name,
        static_cast<unsigned long long>(failure.sequence), failure.in_flight_at_failure,
        failure.idle_ms_at_failure, failure.failed_packet, failure.packet_status,
        packet_name, failure.actual_length,
        failure.requested_length, failure.packet_count,
        static_cast<unsigned long long>(failure.frame_phase_before),
        static_cast<unsigned long long>(failure.frame_phase_after),
        failure.scheduled_start_frame, failure.transfer_flags, failure.device_speed,
        packet_lengths, session->interval_us, session->sample_rate,
        session->endpoint,
        static_cast<unsigned long long>(failure.submitted_at_failure),
        static_cast<unsigned long long>(failure.callbacks_at_failure),
        static_cast<unsigned long long>(failure.event_loops_at_failure),
        failure.event_loop_error_at_failure,
        failure.event_loop_idle_ms_at_failure,
        static_cast<unsigned long long>(kTargetQueuedAudioUs), kMaxUrbsInFlight);
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

// Queues one isochronous URB whose packets have the given byte lengths. The
// payload is consumed from the front of the pending PCM region.
bool submit_urb(UsbAudioSession* session, const std::vector<unsigned int>& packet_bytes,
                size_t total_bytes, uint64_t frame_phase_before,
                uint64_t frame_phase_after, TransportFailure* failure) {
    auto entry = std::make_unique<InFlightTransfer>();
    entry->sequence = session->next_sequence;
    entry->frame_phase_before = frame_phase_before;
    entry->frame_phase_after = frame_phase_after;
    entry->requested_packet_lengths = packet_bytes;
    entry->owner = session;
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
    session->submitted_transfer_count.fetch_add(1, std::memory_order_relaxed);
    return true;
}

// Queues encoded bytes on a dedicated path. Encoded payloads are split only
// at USB service-interval boundaries; they never enter pending_pcm or submit_urb.
bool submit_encoded_urb(UsbAudioSession* session,
                        const std::vector<unsigned int>& packet_bytes,
                        size_t total_bytes, TransportFailure* failure) {
    auto entry = std::make_unique<InFlightTransfer>();
    entry->sequence = session->next_sequence;
    entry->owner = session;
    entry->buffer.assign(
        session->pending_encoded.begin() + static_cast<std::ptrdiff_t>(session->encoded_offset),
        session->pending_encoded.begin() +
            static_cast<std::ptrdiff_t>(session->encoded_offset + total_bytes));
    const int packet_count = static_cast<int>(packet_bytes.size());
    entry->transfer = libusb_alloc_transfer(packet_count);
    if (entry->transfer == nullptr) {
        record_failure(failure, "encoded_alloc_failed", LIBUSB_ERROR_NO_MEM);
        return false;
    }
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
        record_failure(failure, "encoded_submit_failed", result);
        failure->sequence = entry->sequence;
        return false;
    }
    session->encoded_offset += total_bytes;
    ++session->next_sequence;
    session->in_flight.push_back(std::move(entry));
    session->submitted_transfer_count.fetch_add(1, std::memory_order_relaxed);
    return true;
}

void compact_encoded(UsbAudioSession* session) {
    if (session->encoded_offset == 0) return;
    session->pending_encoded.erase(
        session->pending_encoded.begin(),
        session->pending_encoded.begin() + static_cast<std::ptrdiff_t>(session->encoded_offset));
    session->encoded_offset = 0;
}

// Waits for the dedicated event thread to publish completions and enforces
// a progress watchdog against the oldest outstanding URB.
bool wait_for_progress(UsbAudioSession* session, TransportFailure* failure) {
    reap_completed(session, failure);
    if (failure->failed) return false;

    const int event_error = session->event_loop_error.load(std::memory_order_acquire);
    if (event_error != LIBUSB_SUCCESS) {
        record_failure(failure, "event_loop_error", event_error);
        failure->in_flight_at_failure = session->in_flight.size();
        return false;
    }

    if (!session->in_flight.empty()) {
        const auto idle = std::chrono::steady_clock::now() - session->last_progress;
        if (idle > std::chrono::milliseconds(kStallTimeoutMs)) {
            record_failure(failure, "stalled", LIBUSB_ERROR_TIMEOUT);
            failure->sequence = session->in_flight.front()->sequence;
            failure->in_flight_at_failure = session->in_flight.size();
            failure->idle_ms_at_failure =
                std::chrono::duration_cast<std::chrono::milliseconds>(idle).count();
            return false;
        }
    }

    std::unique_lock<std::mutex> lock(session->event_mutex);
    session->event_condition.wait_for(lock, std::chrono::milliseconds(5));
    reap_completed(session, failure);
    if (failure->failed) return false;

    const int updated_event_error = session->event_loop_error.load(std::memory_order_acquire);
    if (updated_event_error != LIBUSB_SUCCESS) {
        record_failure(failure, "event_loop_error", updated_event_error);
        failure->in_flight_at_failure = session->in_flight.size();
        return false;
    }

    if (!session->in_flight.empty()) {
        const auto idle = std::chrono::steady_clock::now() - session->last_progress;
        if (idle > std::chrono::milliseconds(kStallTimeoutMs)) {
            record_failure(failure, "stalled", LIBUSB_ERROR_TIMEOUT);
            failure->sequence = session->in_flight.front()->sequence;
            failure->in_flight_at_failure = session->in_flight.size();
            failure->idle_ms_at_failure =
                std::chrono::duration_cast<std::chrono::milliseconds>(idle).count();
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
    jint feedback_endpoint, jint feedback_max_packet_bytes,
    jint channels, jint subslot_bytes, jint sample_rate, jint clock_id,
    jint control_interface, jint protocol, jboolean sampling_frequency_control) {
    if (file_descriptor < 0 || interface_number < 0 || alternate_setting <= 0 ||
        endpoint <= 0 || (endpoint & 0x80) != 0 || max_packet_bytes <= 0 ||
        interval <= 0 || feedback_endpoint < 0 || feedback_endpoint > 255 || feedback_max_packet_bytes < 0 ||
        ((feedback_endpoint == 0) != (feedback_max_packet_bytes == 0)) ||
        (feedback_endpoint != 0 && (feedback_endpoint & 0x80) == 0) ||
        channels < 1 || channels > 8 || subslot_bytes <= 0 || sample_rate <= 0 ||
        (protocol != 0x00 && protocol != 0x20)) {
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
    session->feedback_endpoint = static_cast<uint8_t>(feedback_endpoint);
    session->feedback_max_packet_bytes = static_cast<uint16_t>(feedback_max_packet_bytes);

    const int speed = libusb_get_device_speed(libusb_get_device(session->handle));
    session->device_speed = speed;
    session->interval_us = service_interval_us(speed, session->interval);
    if (session->feedback_endpoint != 0) {
        const bool high_speed = speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER || speed == LIBUSB_SPEED_SUPER_PLUS;
        const uint16_t minimum_feedback_bytes = high_speed ? 4 : 3;
        if (session->feedback_max_packet_bytes < minimum_feedback_bytes) {
            close_session(session);
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "Feedback endpoint packet is too short for its USB speed");
            return 0;
        }
    }
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

    if (protocol == 0x20 && control_interface != interface_number) {
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
    if (protocol == 0x20) {
        if (!set_and_verify_clock(session, clock_id, control_interface, sample_rate, &clock_error)) {
            close_session(session);
            return throw_usb_error(env, "UAC2 clock SET_CUR/GET_CUR verification", clock_error);
        }
    } else if (sampling_frequency_control == JNI_TRUE) {
        if (!set_and_verify_uac1_frequency(session, sample_rate, &clock_error)) {
            close_session(session);
            return throw_usb_error(env, "UAC1 endpoint sampling-frequency SET_CUR/GET_CUR verification", clock_error);
        }
    }

    result = libusb_set_interface_alt_setting(
        session->handle, interface_number, alternate_setting);
    if (result != LIBUSB_SUCCESS) {
        close_session(session);
        return throw_usb_error(env, "Selecting USB Audio alternate setting", result);
    }

    session->event_thread_running.store(true, std::memory_order_release);
    try {
        session->event_thread = std::thread(event_loop_main, session);
    } catch (...) {
        session->event_thread_running.store(false, std::memory_order_release);
        close_session(session);
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, "Could not start the libusb event thread");
        return 0;
    }

    if (session->feedback_endpoint != 0) {
        const bool high_speed = speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER ||
                                speed == LIBUSB_SPEED_SUPER_PLUS;
        session->feedback_fraction_bits = high_speed ? 16 : 14;
        session->feedback_base_interval_us = high_speed ? 125 : 1000;
        session->feedback_buffer.resize(session->feedback_max_packet_bytes);
        session->feedback_transfer = libusb_alloc_transfer(1);
        if (session->feedback_transfer == nullptr) {
            close_session(session);
            jclass exception = env->FindClass("java/lang/IllegalStateException");
            if (exception != nullptr) env->ThrowNew(exception, "Could not allocate the USB feedback transfer");
            return 0;
        }
        libusb_fill_iso_transfer(
            session->feedback_transfer, session->handle, session->feedback_endpoint,
            session->feedback_buffer.data(), static_cast<int>(session->feedback_buffer.size()),
            1, on_feedback_complete, session, 0);
        libusb_set_iso_packet_lengths(
            session->feedback_transfer, static_cast<unsigned int>(session->feedback_buffer.size()));
        const int feedback_result = libusb_submit_transfer(session->feedback_transfer);
        if (feedback_result != LIBUSB_SUCCESS) {
            close_session(session);
            return throw_usb_error(env, "Submitting asynchronous endpoint feedback transfer", feedback_result);
        }
        session->feedback_in_flight.store(true, std::memory_order_release);
        const auto feedback_deadline = std::chrono::steady_clock::now() +
                                       std::chrono::milliseconds(500);
        while (!session->feedback_valid.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < feedback_deadline &&
               session->event_loop_error.load(std::memory_order_acquire) == LIBUSB_SUCCESS) {
            std::unique_lock<std::mutex> lock(session->event_mutex);
            session->event_condition.wait_for(lock, std::chrono::milliseconds(10));
        }
        if (!session->feedback_valid.load(std::memory_order_acquire)) {
            const int feedback_error = session->event_loop_error.load(std::memory_order_acquire);
            close_session(session);
            jclass exception = env->FindClass("java/lang/IllegalStateException");
            if (exception != nullptr) env->ThrowNew(
                exception,
                feedback_error == LIBUSB_SUCCESS
                    ? "Asynchronous USB endpoint did not provide a valid feedback sample"
                    : "Asynchronous USB feedback endpoint failed");
            return 0;
        }
    }

    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeOpenEncoded(
    JNIEnv* env, jobject object, jint file_descriptor, jint interface_number,
    jint alternate_setting, jint endpoint, jint max_packet_bytes, jint interval,
    jint framing_kind, jlong encoded_bytes_per_second,
    jint encoded_frame_rate, jint encoded_frame_bytes,
    jint source_sample_rate, jboolean sampling_frequency_control,
    jint feedback_endpoint, jint feedback_max_packet_bytes) {
    if ((framing_kind != 1 && framing_kind != 2) || encoded_bytes_per_second <= 0 ||
        (framing_kind == 2 && (encoded_frame_rate <= 0 || encoded_frame_bytes != 4)) ||
        (framing_kind == 1 && (encoded_frame_rate != 0 || encoded_frame_bytes != 0)) ||
        source_sample_rate <= 0 || source_sample_rate > 0x00ffffff) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(exception, "Encoded transport rate must be positive");
        return 0;
    }
    // Reuse only the libusb session setup and interface-claiming lifecycle.
    // The PCM writer is disabled for this session immediately after opening.
    const jlong handle = Java_com_waizusora_flowery_UsbDacNative_nativeOpen(
        env, object, file_descriptor, interface_number, alternate_setting,
        endpoint, max_packet_bytes, interval, feedback_endpoint, feedback_max_packet_bytes,
        1, 1, 1, 0, interface_number, 0, JNI_FALSE);
    if (handle == 0 || env->ExceptionCheck()) return 0;
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (sampling_frequency_control == JNI_TRUE) {
        int frequency_error = LIBUSB_SUCCESS;
        if (!set_and_verify_uac1_frequency(session, source_sample_rate, &frequency_error)) {
            close_session(session);
            return throw_usb_error(env, "UAC1 encoded endpoint sampling-frequency SET_CUR/GET_CUR verification", frequency_error);
        }
    }
    session->sample_rate = source_sample_rate;
    const uint64_t bytes_per_interval = framing_kind == 2
        ? (((static_cast<uint64_t>(encoded_frame_rate) * session->interval_us + 999999ULL) /
             1000000ULL) * static_cast<uint64_t>(encoded_frame_bytes))
        : ((static_cast<uint64_t>(encoded_bytes_per_second) * session->interval_us + 999999ULL) /
           1000000ULL);
    if (bytes_per_interval == 0 || bytes_per_interval > session->max_packet_bytes) {
        close_session(session);
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(
            exception, "Encoded transport rate exceeds the selected endpoint service-interval capacity");
        return 0;
    }
    session->encoded_mode = framing_kind;
    session->encoded_frame_rate = static_cast<uint32_t>(encoded_frame_rate);
    session->encoded_frame_bytes = static_cast<uint8_t>(encoded_frame_bytes);
    session->encoded_bytes_per_second = static_cast<uint64_t>(encoded_bytes_per_second);
    return handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeWriteIsochronous(
    JNIEnv* env, jobject, jlong handle, jbyteArray pcm, jint frame_count) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr || pcm == nullptr || frame_count <= 0) return -1;
    if (session->encoded_mode != 0) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, "PCM writes are disabled for an encoded USB session");
        return -1;
    }

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
        uint64_t feedback_phase = session->feedback_phase;
        size_t planned_frames = 0;
        while (packet_bytes.size() < kPacketsPerUrb) {
            unsigned int frames = 0;
            uint64_t next_phase = phase;
            uint64_t next_feedback_phase = feedback_phase;
            if (session->feedback_endpoint != 0 &&
                session->feedback_valid.load(std::memory_order_acquire)) {
                const auto now = std::chrono::steady_clock::now().time_since_epoch();
                const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
                const int64_t feedback_ns = session->feedback_last_update_ns.load(std::memory_order_acquire);
                if (feedback_ns <= 0 || now_ns - feedback_ns > 100000000LL) {
                    record_failure(&failure, "feedback_timeout", LIBUSB_ERROR_TIMEOUT);
                    break;
                }
                const uint64_t feedback = session->feedback_rate_fp.load(std::memory_order_acquire);
                const uint64_t scaled = feedback * session->interval_us /
                                        session->feedback_base_interval_us;
                const uint64_t accumulated = feedback_phase + scaled;
                frames = static_cast<unsigned int>(accumulated >> session->feedback_fraction_bits);
                next_feedback_phase = accumulated & ((1ULL << session->feedback_fraction_bits) - 1ULL);
            } else {
                const uint64_t accumulated =
                    phase + static_cast<uint64_t>(session->sample_rate) * session->interval_us;
                frames = static_cast<unsigned int>(accumulated / 1000000ULL);
                next_phase = accumulated % 1000000ULL;
            }
            if (frames == 0 || planned_frames + frames > available_frames) break;
            const unsigned int packet_size = static_cast<unsigned int>(frames * bytes_per_frame);
            if (packet_size > session->max_packet_bytes) {
                record_failure(&failure, "packet_exceeds_endpoint_capacity", LIBUSB_ERROR_OVERFLOW);
                break;
            }
            packet_bytes.push_back(packet_size);
            planned_frames += frames;
            phase = next_phase;
            feedback_phase = next_feedback_phase;
        }
        if (failure.failed || packet_bytes.empty()) break;

        // Back-pressure: block until the window has room for another URB.
        if (session->in_flight.size() >= max_urbs_in_flight(session)) {
            if (!wait_for_progress(session, &failure)) break;
            continue;
        }

        if (!submit_urb(session, packet_bytes, planned_frames * bytes_per_frame,
                        session->frame_phase, phase, &failure)) break;
        session->frame_phase = phase;
        session->feedback_phase = feedback_phase;
    }

    if (failure.failed) {
        snapshot_transport_failure(session, &failure);
        abort_in_flight(session);
        throw_transport_failure(env, session, failure);
        return -1;
    }
    return frame_count;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeWriteEncodedPacket(
    JNIEnv* env, jobject, jlong handle, jbyteArray encoded, jint payload_offset, jint payload_length, jlong duration_us) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr || encoded == nullptr || !session->encoded_mode) return -1;
    const jsize array_length = env->GetArrayLength(encoded);
    if (payload_offset < 0 || payload_length <= 0 ||
        payload_offset > array_length || payload_length > array_length - payload_offset) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        if (exception != nullptr) env->ThrowNew(exception, "Encoded payload range is outside the supplied byte array");
        return -1;
    }
    const jsize byte_count = payload_length;

    // UAC1 Type-II is a sequence of independently encoded frames. Every frame
    // is split into full-size USB packets plus one short packet, followed by a
    // zero-length transfer delimiter. A following frame is not submitted until
    // this delimiter has completed and its presentation interval is due.
    if (session->encoded_mode == 1) {
        const size_t frame_bytes = static_cast<size_t>(byte_count);
        const size_t full_packets = frame_bytes / session->max_packet_bytes;
        const size_t remainder = frame_bytes % session->max_packet_bytes;
        const size_t packet_count = full_packets + (remainder != 0 ? 1 : 0) + 1;
        if (packet_count > kPacketsPerUrb) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "UAC1 Type-II encoded frame exceeds the bounded isochronous URB packet count");
            return -1;
        }
        uint64_t effective_duration_us = static_cast<uint64_t>(duration_us);
        if (session->feedback_endpoint != 0) {
            const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            const int64_t feedback_ns = session->feedback_last_update_ns.load(std::memory_order_acquire);
            const uint64_t feedback = session->feedback_rate_fp.load(std::memory_order_acquire);
            if (!session->feedback_valid.load(std::memory_order_acquire) || feedback == 0 ||
                feedback_ns <= 0 || now_ns - feedback_ns > 100000000LL) {
                jclass exception = env->FindClass("java/lang/IllegalStateException");
                if (exception != nullptr) env->ThrowNew(exception, "Asynchronous encoded endpoint feedback is stale or unavailable");
                return -1;
            }
            const uint64_t numerator = 1536ULL * session->feedback_base_interval_us *
                (1ULL << session->feedback_fraction_bits);
            effective_duration_us = (numerator + feedback - 1ULL) / feedback;
        }
        const uint64_t frame_service_time_us =
            static_cast<uint64_t>(packet_count) * session->interval_us;
        if (effective_duration_us == 0 || frame_service_time_us > effective_duration_us) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(
                exception, "UAC1 Type-II frame cannot be transmitted within its encoded frame duration");
            return -1;
        }
        if (duration_us <= 0 || duration_us > 1000000) {
            jclass exception = env->FindClass("java/lang/IllegalArgumentException");
            if (exception != nullptr) env->ThrowNew(exception, "UAC1 Type-II encoded frame duration is invalid");
            return -1;
        }
        if (session->encoded_frame_deadline_valid &&
            std::chrono::steady_clock::now() < session->next_encoded_frame_due) {
            std::this_thread::sleep_until(session->next_encoded_frame_due);
        }
        const auto frame_start = std::chrono::steady_clock::now();
        compact_encoded(session);
        session->pending_encoded.resize(frame_bytes);
        env->GetByteArrayRegion(encoded, payload_offset, byte_count,
            reinterpret_cast<jbyte*>(session->pending_encoded.data()));
        if (env->ExceptionCheck()) { session->pending_encoded.clear(); return -1; }
        std::vector<unsigned int> frame_packets;
        frame_packets.reserve(packet_count);
        for (size_t i = 0; i < full_packets; ++i) frame_packets.push_back(session->max_packet_bytes);
        if (remainder != 0) frame_packets.push_back(static_cast<unsigned int>(remainder));
        frame_packets.push_back(0); // UAC1 transfer delimiter between encoded frames
        TransportFailure frame_failure;
        if (!submit_encoded_urb(session, frame_packets, frame_bytes, &frame_failure)) {
            snapshot_transport_failure(session, &frame_failure);
            throw_transport_failure(env, session, frame_failure);
            return -1;
        }
        while (!frame_failure.failed && !session->in_flight.empty()) {
            if (!wait_for_progress(session, &frame_failure)) break;
        }
        if (frame_failure.failed) {
            snapshot_transport_failure(session, &frame_failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, frame_failure);
            return -1;
        }
        session->pending_encoded.clear();
        session->encoded_offset = 0;
        session->next_encoded_frame_due = frame_start + std::chrono::microseconds(effective_duration_us);
        session->encoded_frame_deadline_valid = true;
        return byte_count;
    }

    const uint64_t max_buffer = session->encoded_bytes_per_second * 2ULL;
    const size_t live = session->pending_encoded.size() - session->encoded_offset;
    if (static_cast<uint64_t>(live) + static_cast<uint64_t>(byte_count) > max_buffer) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        if (exception != nullptr) env->ThrowNew(exception, "Encoded USB staging queue is full");
        return -1;
    }
    compact_encoded(session);
    const size_t previous = session->pending_encoded.size();
    session->pending_encoded.resize(previous + static_cast<size_t>(byte_count));
    env->GetByteArrayRegion(encoded, payload_offset, byte_count,
        reinterpret_cast<jbyte*>(session->pending_encoded.data() + previous));
    if (env->ExceptionCheck()) {
        session->pending_encoded.resize(previous);
        return -1;
    }

    TransportFailure failure;
    std::vector<unsigned int> packet_bytes;
    packet_bytes.reserve(kPacketsPerUrb);
    while (true) {
        reap_completed(session, &failure);
        if (failure.failed) break;
        uint64_t phase = session->encoded_rate_phase;
        uint64_t feedback_phase = session->encoded_feedback_phase;
        size_t total = 0;
        packet_bytes.clear();
        const size_t available = session->pending_encoded.size() - session->encoded_offset;
        while (packet_bytes.size() < kPacketsPerUrb) {
            uint64_t frames = 0;
            uint64_t next_phase = phase;
            if (session->feedback_endpoint != 0) {
                const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                const int64_t feedback_ns = session->feedback_last_update_ns.load(std::memory_order_acquire);
                const uint64_t feedback = session->feedback_rate_fp.load(std::memory_order_acquire);
                if (!session->feedback_valid.load(std::memory_order_acquire) || feedback == 0 ||
                    feedback_ns <= 0 || now_ns - feedback_ns > 100000000LL) {
                    record_failure(&failure, "encoded_feedback_timeout", LIBUSB_ERROR_TIMEOUT);
                    break;
                }
                const uint64_t scaled = feedback * session->interval_us /
                    session->feedback_base_interval_us + feedback_phase;
                frames = scaled >> session->feedback_fraction_bits;
                feedback_phase = scaled & ((1ULL << session->feedback_fraction_bits) - 1ULL);
            } else {
                const uint64_t scaled = static_cast<uint64_t>(session->encoded_frame_rate) *
                                       session->interval_us + phase;
                frames = scaled / 1000000ULL;
                next_phase = scaled % 1000000ULL;
            }
            const unsigned int expected = static_cast<unsigned int>(
                frames * session->encoded_frame_bytes);
            if (expected == 0 || expected > session->max_packet_bytes ||
                total + expected > available) break;
            packet_bytes.push_back(expected);
            total += expected;
            phase = next_phase;
        }
        if (packet_bytes.empty()) break;
        if (session->in_flight.size() >= max_urbs_in_flight(session)) {
            if (!wait_for_progress(session, &failure)) break;
            continue;
        }
        if (!submit_encoded_urb(session, packet_bytes, total, &failure)) break;
        session->encoded_rate_phase = phase;
        session->encoded_feedback_phase = feedback_phase;
    }
    if (failure.failed) {
        snapshot_transport_failure(session, &failure);
        abort_in_flight(session);
        throw_transport_failure(env, session, failure);
        return -1;
    }
    return byte_count;
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

    if (session->encoded_mode == 1) {
        while (!failure.failed && !session->in_flight.empty()) wait_for_progress(session, &failure);
        if (failure.failed) {
            snapshot_transport_failure(session, &failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, failure);
            return -1;
        }
        return 0;
    }
    if (session->encoded_mode == 2) {
        while (!failure.failed) {
            const size_t remaining_encoded = session->pending_encoded.size() - session->encoded_offset;
            if (remaining_encoded == 0) break;
            if (session->in_flight.size() >= max_urbs_in_flight(session)) {
                if (!wait_for_progress(session, &failure)) break;
                continue;
            }
            const size_t tail = std::min<size_t>(remaining_encoded, session->max_packet_bytes);
            if (session->encoded_frame_bytes == 0 || tail % session->encoded_frame_bytes != 0) {
                record_failure(&failure, "encoded_tail_not_frame_aligned", LIBUSB_ERROR_INVALID_PARAM);
                break;
            }
            const std::vector<unsigned int> final_packet{static_cast<unsigned int>(tail)};
            if (!submit_encoded_urb(session, final_packet, tail, &failure)) break;
        }
        while (!failure.failed && !session->in_flight.empty()) wait_for_progress(session, &failure);
        if (failure.failed) {
            snapshot_transport_failure(session, &failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, failure);
            return -1;
        }
        compact_encoded(session);
        return 0;
    }

    const size_t remaining = session->pending_pcm.size() - session->pending_offset;
    if (!failure.failed && remaining > 0) {
        while (session->in_flight.size() >= max_urbs_in_flight(session)) {
            if (!wait_for_progress(session, &failure)) break;
        }
        if (!failure.failed) {
            const std::vector<unsigned int> tail{static_cast<unsigned int>(remaining)};
            if (remaining <= session->max_packet_bytes) {
                submit_urb(session, tail, remaining, session->frame_phase,
                           session->frame_phase, &failure);
            } else {
                record_failure(&failure, "packet_exceeds_endpoint_capacity", LIBUSB_ERROR_OVERFLOW);
            }
        }
    }
    while (!failure.failed && !session->in_flight.empty()) {
        wait_for_progress(session, &failure);
    }

    if (failure.failed) {
        snapshot_transport_failure(session, &failure);
        abort_in_flight(session);
        throw_transport_failure(env, session, failure);
        return -1;
    }
    compact_pending(session);
    return 0;
}

// Discards staged PCM and cancels queued URBs. Called on seek so that audio
// from the previous position is not played after the new position is selected.
extern "C" JNIEXPORT jint JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeFlush(
    JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<UsbAudioSession*>(handle);
    if (session == nullptr) return -1;
    if (!abort_in_flight(session)) return -1;
    session->pending_pcm.clear();
    session->pending_offset = 0;
    session->pending_encoded.clear();
    session->encoded_offset = 0;
    session->encoded_rate_phase = 0;
    session->encoded_feedback_phase = 0;
    session->encoded_frame_deadline_valid = false;
    session->frame_phase = 0;
    session->feedback_phase = 0;
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    return close_session(reinterpret_cast<UsbAudioSession*>(handle)) ? JNI_TRUE : JNI_FALSE;
}
