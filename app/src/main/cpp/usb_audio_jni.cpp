#include <jni.h>
#include <libusb.h>
#include "usb_session_control.h"

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
#include <shared_mutex>
#include <unordered_map>
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
    // The transfer is reclaimable only after the libusb event-dispatch call
    // that invoked its callback has returned.
    std::atomic<uint64_t> callback_return_iteration{0};
    uint64_t sequence = 0;
    int64_t submitted_at_ns = 0;
    uint64_t frame_phase_before = 0;
    uint64_t frame_phase_after = 0;
    uint64_t feedback_phase_before = 0;
    uint64_t feedback_phase_after = 0;
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
    std::atomic<uint64_t> feedback_callback_return_iteration{0};
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
    // Shared JNI leases keep the session alive while an operation is in progress.
    flowery::usb::SessionLifetimeGate lifecycle;
    // 0=running, 1=pause requested, 2=stop requested. Only the writer mutates URB queues.
    flowery::usb::ControlState control;
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
    std::vector<unsigned int> actual_packet_lengths;
    long long transfer_age_ms = -1;
    uint64_t frame_phase_before = 0;
    uint64_t frame_phase_after = 0;
    uint64_t feedback_phase_before = 0;
    uint64_t feedback_phase_after = 0;
    int scheduled_start_frame = 0;
    unsigned int transfer_flags = 0;
    int device_speed = LIBUSB_SPEED_UNKNOWN;
    std::vector<unsigned int> requested_packet_lengths;
    size_t in_flight_at_failure = 0;
    size_t max_urbs_at_failure = 0;
    size_t pending_pcm_remaining_at_failure = 0;
    size_t pending_encoded_remaining_at_failure = 0;
    char in_flight_summary[512] = {};
    bool stall_snapshot_captured = false;
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
        if (owner != nullptr) {
            owner->completed_transfer_callback_count.fetch_add(1, std::memory_order_relaxed);
            owner->event_condition.notify_all();
            entry->callback_return_iteration.store(
                owner->event_loop_iteration_count.load(std::memory_order_acquire) + 1,
                std::memory_order_release);
        }
        // Publish completion last. Reaping also waits for the event-loop
        // iteration barrier, proving this callback has returned to libusb.
        entry->completed.store(true, std::memory_order_release);
    }
}

void LIBUSB_CALL on_feedback_complete(libusb_transfer* transfer) {
    auto* session = static_cast<UsbAudioSession*>(transfer->user_data);
    if (session == nullptr) return;
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->num_iso_packets > 0) {
        const auto& packet = transfer->iso_packet_desc[0];
        const unsigned char* data = libusb_get_iso_packet_buffer_simple(transfer, 0);
        if (packet.status == LIBUSB_TRANSFER_COMPLETED && data != nullptr) {
            uint32_t value = 0;
            if (flowery::usb::decode_feedback_sample(
                    data, packet.actual_length, session->feedback_fraction_bits, value)) {
                session->feedback_rate_fp.store(value, std::memory_order_release);
                const auto now = std::chrono::steady_clock::now().time_since_epoch();
                session->feedback_last_update_ns.store(
                    std::chrono::duration_cast<std::chrono::nanoseconds>(now).count(),
                    std::memory_order_release);
                session->feedback_valid.store(true, std::memory_order_release);
            }
        }
    }
    bool resubmitted = false;
    {
        std::lock_guard<std::mutex> lock(session->event_mutex);
        if (flowery::usb::feedback_resubmit_allowed(
                session->feedback_stopped.load(std::memory_order_acquire),
                session->event_thread_running.load(std::memory_order_acquire),
                session->event_loop_error.load(std::memory_order_acquire), LIBUSB_SUCCESS)) {
            const int result = libusb_submit_transfer(transfer);
            if (result == LIBUSB_SUCCESS) {
                resubmitted = true;
                session->feedback_in_flight.store(true, std::memory_order_release);
            } else {
                session->event_loop_error.store(result, std::memory_order_release);
            }
        }
    }
    if (!resubmitted) {
        // Publish the event-loop barrier before reporting that the feedback
        // transfer is no longer in flight; teardown waits for both conditions.
        session->feedback_callback_return_iteration.store(
            session->event_loop_iteration_count.load(std::memory_order_acquire) + 1,
            std::memory_order_release);
        session->feedback_in_flight.store(false, std::memory_order_release);
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
        session->event_loop_iteration_count.fetch_add(1, std::memory_order_release);
        session->event_condition.notify_all();
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        session->last_event_loop_ns.store(
            std::chrono::duration_cast<std::chrono::nanoseconds>(now).count(),
            std::memory_order_release);
        if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_INTERRUPTED) {
            session->event_loop_error.store(result, std::memory_order_release);
            session->event_thread_running.store(false, std::memory_order_release);
            session->feedback_stopped.store(true, std::memory_order_release);
            session->event_condition.notify_all();
            break;
        }
    }
    // Publish dispatcher termination on every exit path.
    session->event_thread_running.store(false, std::memory_order_release);
    session->event_condition.notify_all();
}

// After a fatal dispatcher error, the dedicated event thread is no longer
// able to deliver cancellation callbacks. Once it has exited and been joined,
// teardown may temporarily take exclusive ownership of libusb event handling.
// This is bounded: if libusb still cannot return ownership, callers retain the
// session and all callback-referenced memory rather than freeing it.
template <typename Pending>
bool pump_failed_event_loop_for_teardown(UsbAudioSession* session, Pending pending) {
    if (!flowery::usb::failed_dispatcher_teardown_pump_allowed(
            session->event_thread_running.load(std::memory_order_acquire),
            session->event_loop_error.load(std::memory_order_acquire), LIBUSB_SUCCESS)) {
        return false;
    }
    if (session->event_thread.joinable()) {
        session->event_thread.join();
    }
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(kTeardownTimeoutMs);
    while (pending() && std::chrono::steady_clock::now() < deadline) {
        timeval timeout{0, 10000};
        const int result = libusb_handle_events_timeout_completed(
            session->context, &timeout, nullptr);
        session->event_loop_iteration_count.fetch_add(1, std::memory_order_release);
        session->event_condition.notify_all();
        if (result != LIBUSB_SUCCESS && result != LIBUSB_ERROR_INTERRUPTED) {
            // Retry only within the same teardown deadline. A persistent
            // dispatcher failure must not turn into an unbounded cleanup wait.
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
    return !pending();
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
        auto feedback_callback_pending = [&]() {
            return session->feedback_in_flight.load(std::memory_order_acquire) ||
                   session->feedback_callback_return_iteration.load(std::memory_order_acquire) >
                       session->event_loop_iteration_count.load(std::memory_order_acquire);
        };
        while (feedback_callback_pending() &&
               std::chrono::steady_clock::now() < deadline) {
            std::unique_lock<std::mutex> lock(session->event_mutex);
            session->event_condition.wait_for(lock, std::chrono::milliseconds(10));
        }
        if (feedback_callback_pending() &&
            !pump_failed_event_loop_for_teardown(session, feedback_callback_pending)) {
            // Keep the session, transfer buffer, and callback context alive if
            // the controller's ownership cannot be returned within the bound.
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
        if (!flowery::usb::transfer_reclaimable(
                entry->completed.load(std::memory_order_acquire),
                entry->callback_return_iteration.load(std::memory_order_acquire),
                session->event_loop_iteration_count.load(std::memory_order_acquire))) {
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
                    if (!flowery::usb::iso_packet_completed_exactly(
                            static_cast<unsigned int>(packet.status),
                            LIBUSB_TRANSFER_COMPLETED,
                            packet.actual_length,
                            packet.length)) {
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
                        failure->feedback_phase_before = entry->feedback_phase_before;
                        failure->feedback_phase_after = entry->feedback_phase_after;
                        // libusb's public transfer structure does not expose the host-scheduled start frame.
                        failure->scheduled_start_frame = -1;
                        failure->transfer_flags = transfer->flags;
                        failure->device_speed = session->device_speed;
                        failure->requested_packet_lengths = entry->requested_packet_lengths;
                        failure->actual_packet_lengths.reserve(
                            static_cast<size_t>(transfer->num_iso_packets));
                        for (int packet_index = 0; packet_index < transfer->num_iso_packets; ++packet_index) {
                            failure->actual_packet_lengths.push_back(
                                transfer->iso_packet_desc[packet_index].actual_length);
                        }
                        if (entry->submitted_at_ns > 0) {
                            const auto now = std::chrono::steady_clock::now().time_since_epoch();
                            const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
                            failure->transfer_age_ms = std::max<int64_t>(0, now_ns - entry->submitted_at_ns) / 1000000LL;
                        }
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
    if (!session->in_flight.empty() &&
        session->event_loop_error.load(std::memory_order_acquire) != LIBUSB_SUCCESS) {
        pump_failed_event_loop_for_teardown(session, [&]() {
            reap_completed(session, &ignored);
            return !session->in_flight.empty();
        });
    }
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

bool close_session_resources(UsbAudioSession* session) {
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
    return true;
}

bool close_session(UsbAudioSession* session) {
    if (!close_session_resources(session)) return false;
    delete session;
    return true;
}

std::mutex g_session_registry_mutex;
std::unordered_map<jlong, UsbAudioSession*> g_session_registry;
std::atomic<jlong> g_next_session_handle{1};

struct SessionLease {
    UsbAudioSession* session = nullptr;
    flowery::usb::SessionLifetimeGate::Lease lock;
    SessionLease() = default;
    explicit SessionLease(UsbAudioSession* value) : lock(value->lifecycle.acquire()) {
        if (lock) session = value;
    }
    SessionLease(SessionLease&& other) noexcept
        : session(std::exchange(other.session, nullptr)), lock(std::move(other.lock)) {}
    SessionLease& operator=(SessionLease&& other) noexcept {
        if (this != &other) {
            lock = std::move(other.lock);
            session = std::exchange(other.session, nullptr);
        }
        return *this;
    }
    SessionLease(const SessionLease&) = delete;
    SessionLease& operator=(const SessionLease&) = delete;
};

jlong register_session(UsbAudioSession* session) {
    if (session == nullptr) return 0;
    const jlong handle = g_next_session_handle.fetch_add(1, std::memory_order_relaxed);
    if (handle <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    g_session_registry.emplace(handle, session);
    return handle;
}

SessionLease acquire_session(jlong handle) {
    if (handle == 0) return {};
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    const auto found = g_session_registry.find(handle);
    if (found == g_session_registry.end()) return {};
    return SessionLease(found->second);
}

bool close_registered_session(jlong handle) {
    if (handle == 0) return true;
    std::unique_lock<std::mutex> registry_lock(g_session_registry_mutex);
    const auto found = g_session_registry.find(handle);
    if (found == g_session_registry.end()) return true;
    UsbAudioSession* session = found->second;
    // Publish terminal Stop before waiting for active JNI leases. An encoded
    // writer may be blocked in Pause and must be woken before Close can drain it.
    flowery::usb::request_control_serialized(
        session->control, flowery::usb::ControlRequest::Stop,
        session->event_mutex, session->event_condition);
    auto session_lock = session->lifecycle.begin_close();
    if (!close_session_resources(session)) return false;
    g_session_registry.erase(found);
    session_lock.unlock();
    registry_lock.unlock();
    delete session;
    return true;
}

constexpr int kSessionControlRun = 0;
constexpr int kSessionControlPause = 1;
constexpr int kSessionControlStop = 2;

bool request_session_control(jlong handle, int requested) {
    auto lease = acquire_session(handle);
    UsbAudioSession* session = lease.session;
    if (session == nullptr || requested < kSessionControlRun || requested > kSessionControlStop) return false;
    return flowery::usb::request_control_serialized(
        session->control, static_cast<flowery::usb::ControlRequest>(requested),
        session->event_mutex, session->event_condition);
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
    char actual_packet_lengths[256] = {};
    if (!failure.actual_packet_lengths.empty() && failure.failed_packet >= 0) {
        const size_t failed = static_cast<size_t>(failure.failed_packet);
        const size_t begin = failed > 4 ? failed - 4 : 0;
        const size_t end = std::min(failure.actual_packet_lengths.size(), failed + 5);
        size_t used = 0;
        for (size_t index = begin; index < end && used < sizeof(actual_packet_lengths); ++index) {
            const int written = std::snprintf(actual_packet_lengths + used,
                sizeof(actual_packet_lengths) - used, "%s%zu:%u",
                index == begin ? "" : ",", index, failure.actual_packet_lengths[index]);
            if (written < 0) break;
            used += std::min(static_cast<size_t>(written), sizeof(actual_packet_lengths) - used);
        }
    }
    const size_t max_urbs = failure.max_urbs_at_failure != 0
        ? failure.max_urbs_at_failure : max_urbs_in_flight(session);
    char message[1536];
    std::snprintf(
        message, sizeof(message),
        "USB isochronous transfer failed: reason=%s; %s (%d); "
        "transfer_status=%u (%s); sequence=%llu; in_flight_at_failure=%zu; idle_ms=%lld; failed_packet=%d; "
        "packet_status=%u (%s); actual_length=%u; requested_length=%u; "
        "packet_count=%u; packet_lengths_near_failure=%s; actual_packet_lengths_near_failure=%s; "
        "transfer_age_ms=%lld; phase_before=%llu; phase_after=%llu; "
        "feedback_phase_before=%llu; feedback_phase_after=%llu; start_frame=%d; "
        "transfer_flags=%u; device_speed=%d; packet_lengths_near_failure=%s; "
        "interval_us=%u; sample_rate=%d; endpoint=0x%02X; "
        "submitted=%llu; callbacks=%llu; event_loops=%llu; "
        "event_loop_error=%d; event_loop_idle_ms=%lld; "
        "pending_pcm_remaining=%zu; pending_encoded_remaining=%zu; in_flight_detail=%s; "
        "queue_target_us=%llu; max_urbs_actual=%zu",
        failure.reason, libusb_error_name(failure.code), failure.code,
        failure.transfer_status, transfer_name,
        static_cast<unsigned long long>(failure.sequence), failure.in_flight_at_failure,
        failure.idle_ms_at_failure, failure.failed_packet, failure.packet_status,
        packet_name, failure.actual_length,
        failure.requested_length, failure.packet_count, packet_lengths, actual_packet_lengths,
        failure.transfer_age_ms,
        static_cast<unsigned long long>(failure.frame_phase_before),
        static_cast<unsigned long long>(failure.frame_phase_after),
        static_cast<unsigned long long>(failure.feedback_phase_before),
        static_cast<unsigned long long>(failure.feedback_phase_after),
        failure.scheduled_start_frame, failure.transfer_flags, failure.device_speed,
        packet_lengths, session->interval_us, session->sample_rate,
        session->endpoint,
        static_cast<unsigned long long>(failure.submitted_at_failure),
        static_cast<unsigned long long>(failure.callbacks_at_failure),
        static_cast<unsigned long long>(failure.event_loops_at_failure),
        failure.event_loop_error_at_failure,
        failure.event_loop_idle_ms_at_failure,
        failure.pending_pcm_remaining_at_failure,
        failure.pending_encoded_remaining_at_failure,
        failure.in_flight_summary,
        static_cast<unsigned long long>(kTargetQueuedAudioUs), max_urbs);
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

// Queues one isochronous URB whose packets have the given byte lengths. The
// payload is consumed from the front of the pending PCM region.
bool submit_urb(UsbAudioSession* session, const std::vector<unsigned int>& packet_bytes,
                size_t total_bytes, uint64_t frame_phase_before,
                uint64_t frame_phase_after, uint64_t feedback_phase_before,
                uint64_t feedback_phase_after, TransportFailure* failure) {
    auto entry = std::make_unique<InFlightTransfer>();
    entry->sequence = session->next_sequence;
    entry->frame_phase_before = frame_phase_before;
    entry->frame_phase_after = frame_phase_after;
    entry->feedback_phase_before = feedback_phase_before;
    entry->feedback_phase_after = feedback_phase_after;
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
    const auto submitted_now = std::chrono::steady_clock::now().time_since_epoch();
    entry->submitted_at_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(submitted_now).count();
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

enum class PcmSubmitResult { Submitted, ControlChanged, Failed };

// Linearize PCM submission against Pause/Stop publication, just like the
// encoded path. A control request cannot slip between the final check and
// libusb_submit_transfer().
PcmSubmitResult submit_pcm_urb_if_running(
    UsbAudioSession* session, const std::vector<unsigned int>& packet_bytes,
    size_t total_bytes, uint64_t frame_phase_before, uint64_t frame_phase_after,
    uint64_t feedback_phase_before, uint64_t feedback_phase_after,
    TransportFailure* failure) {
    std::lock_guard<std::mutex> control_lock(session->event_mutex);
    if (!flowery::usb::submission_allowed(session->control)) {
        return PcmSubmitResult::ControlChanged;
    }
    return submit_urb(session, packet_bytes, total_bytes, frame_phase_before,
                      frame_phase_after, feedback_phase_before, feedback_phase_after, failure)
        ? PcmSubmitResult::Submitted
        : PcmSubmitResult::Failed;
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
    const auto submitted_now = std::chrono::steady_clock::now().time_since_epoch();
    entry->submitted_at_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(submitted_now).count();
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

enum class EncodedSubmitResult { Submitted, ControlChanged, Failed };

// Serialize the final control check with request_session_control(). A Pause or
// Stop request cannot be published between this check and libusb submission.
EncodedSubmitResult submit_encoded_urb_if_running(
    UsbAudioSession* session, const std::vector<unsigned int>& packet_bytes,
    size_t total_bytes, TransportFailure* failure) {
    std::lock_guard<std::mutex> control_lock(session->event_mutex);
    if (!flowery::usb::submission_allowed(session->control)) {
        return EncodedSubmitResult::ControlChanged;
    }
    return submit_encoded_urb(session, packet_bytes, total_bytes, failure)
        ? EncodedSubmitResult::Submitted
        : EncodedSubmitResult::Failed;
}

void compact_encoded(UsbAudioSession* session) {
    if (session->encoded_offset == 0) return;
    session->pending_encoded.erase(
        session->pending_encoded.begin(),
        session->pending_encoded.begin() + static_cast<std::ptrdiff_t>(session->encoded_offset));
    session->encoded_offset = 0;
}

// Capture the queue shape before cancellation changes ownership or counters.
// Each entry is sequence/packet-count/phase-range/byte-count/edge-packet-lengths.
void snapshot_stalled_transport(const UsbAudioSession* session, TransportFailure* failure) {
    if (failure->stall_snapshot_captured) return;
    failure->max_urbs_at_failure = max_urbs_in_flight(session);
    failure->pending_pcm_remaining_at_failure =
        session->pending_pcm.size() - session->pending_offset;
    failure->pending_encoded_remaining_at_failure =
        session->pending_encoded.size() - session->encoded_offset;
    size_t used = 0;
    for (const auto& entry : session->in_flight) {
        uint64_t requested_bytes = 0;
        for (const unsigned int length : entry->requested_packet_lengths) {
            requested_bytes += length;
        }
        const unsigned int first_packet = entry->requested_packet_lengths.empty()
            ? 0U : entry->requested_packet_lengths.front();
        const unsigned int last_packet = entry->requested_packet_lengths.empty()
            ? 0U : entry->requested_packet_lengths.back();
        unsigned int packets_with_completion = 0;
        unsigned int packets_with_error = 0;
        unsigned int first_actual = 0;
        unsigned int last_actual = 0;
        unsigned int first_packet_status = LIBUSB_TRANSFER_ERROR;
        unsigned int last_packet_status = LIBUSB_TRANSFER_ERROR;
        const libusb_transfer* transfer = entry->transfer;
        if (transfer != nullptr) {
            for (int packet_index = 0; packet_index < transfer->num_iso_packets; ++packet_index) {
                const auto& packet = transfer->iso_packet_desc[packet_index];
                if (packet_index == 0) {
                    first_actual = packet.actual_length;
                    first_packet_status = static_cast<unsigned int>(packet.status);
                }
                if (packet_index == transfer->num_iso_packets - 1) {
                    last_actual = packet.actual_length;
                    last_packet_status = static_cast<unsigned int>(packet.status);
                }
                if (packet.status == LIBUSB_TRANSFER_COMPLETED) ++packets_with_completion;
                else ++packets_with_error;
            }
        }
        const int written = std::snprintf(
            failure->in_flight_summary + used,
            sizeof(failure->in_flight_summary) - used,
            "%s#%llu/%zu/f%llu-%llu/b%llu-%llu/%llu/%u-%u/cb%d/ts%u/pc%u/pe%u/a%u-%u/ps%u-%u",
            used == 0 ? "" : ";",
            static_cast<unsigned long long>(entry->sequence),
            entry->requested_packet_lengths.size(),
            static_cast<unsigned long long>(entry->frame_phase_before),
            static_cast<unsigned long long>(entry->frame_phase_after),
            static_cast<unsigned long long>(entry->feedback_phase_before),
            static_cast<unsigned long long>(entry->feedback_phase_after),
            static_cast<unsigned long long>(requested_bytes),
            first_packet, last_packet,
            entry->completed.load(std::memory_order_acquire) ? 1 : 0,
            transfer == nullptr ? LIBUSB_TRANSFER_ERROR : static_cast<unsigned int>(transfer->status),
            packets_with_completion, packets_with_error, first_actual, last_actual,
            first_packet_status, last_packet_status);
        if (written < 0) break;
        const size_t remaining = sizeof(failure->in_flight_summary) - used;
        used += std::min(static_cast<size_t>(written), remaining);
        if (used >= sizeof(failure->in_flight_summary)) break;
    }
    if (!session->in_flight.empty()) {
        const auto& oldest = session->in_flight.front();
        if (oldest->submitted_at_ns > 0) {
            const auto now = std::chrono::steady_clock::now().time_since_epoch();
            const int64_t now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
            failure->transfer_age_ms = std::max<int64_t>(0, now_ns - oldest->submitted_at_ns) / 1000000LL;
        }
        failure->packet_count = static_cast<unsigned int>(oldest->requested_packet_lengths.size());
        failure->frame_phase_before = oldest->frame_phase_before;
        failure->frame_phase_after = oldest->frame_phase_after;
        failure->feedback_phase_before = oldest->feedback_phase_before;
        failure->feedback_phase_after = oldest->feedback_phase_after;
        if (oldest->transfer != nullptr) {
            failure->transfer_flags = oldest->transfer->flags;
        }
        failure->device_speed = session->device_speed;
        failure->scheduled_start_frame = -1;
    }
    failure->stall_snapshot_captured = true;
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
            snapshot_stalled_transport(session, failure);
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
            snapshot_stalled_transport(session, failure);
            return false;
        }
    }
    return true;
}

// Drops the consumed prefix of the pending PCM region.

// Encoded writes are controlled only by their writer thread. Pause waits for the
// current frame/URB window to complete before blocking for Resume; Stop aborts
// and clears staged encoded data on this same thread.
enum class EncodedControlResult { Running, Stopped, Failed };

EncodedControlResult honor_encoded_write_control(
    UsbAudioSession* session, TransportFailure* failure) {
    for (;;) {
        const auto action = flowery::usb::encoded_control_action(
            session->control.load(), session->in_flight.size());
        if (action == flowery::usb::EncodedControlAction::Abort) {
            if (!abort_in_flight(session)) {
                record_failure(failure, "encoded_stop_reap_failed", LIBUSB_ERROR_TIMEOUT);
                return EncodedControlResult::Failed;
            }
            session->pending_encoded.clear();
            session->encoded_offset = 0;
            session->encoded_rate_phase = 0;
            session->encoded_feedback_phase = 0;
            session->encoded_frame_deadline_valid = false;
            return EncodedControlResult::Stopped;
        }
        if (action == flowery::usb::EncodedControlAction::Submit) return EncodedControlResult::Running;
        if (action == flowery::usb::EncodedControlAction::DrainInFlight) {
            if (!wait_for_progress(session, failure)) return EncodedControlResult::Failed;
            continue;
        }

        std::unique_lock<std::mutex> lock(session->event_mutex);
        session->event_condition.wait(lock, [&]() {
            return session->control.load() != kSessionControlPause;
        });
    }
}

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
        // Publish ownership before submission: the event thread may dispatch
        // the first callback immediately after libusb accepts the transfer.
        session->feedback_in_flight.store(true, std::memory_order_release);
        const int feedback_result = libusb_submit_transfer(session->feedback_transfer);
        if (feedback_result != LIBUSB_SUCCESS) {
            session->feedback_in_flight.store(false, std::memory_order_release);
            close_session(session);
            return throw_usb_error(env, "Submitting asynchronous endpoint feedback transfer", feedback_result);
        }
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

    return register_session(session);
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
    auto lease = acquire_session(handle);
    auto* session = lease.session;
    if (session == nullptr) return 0;
    if (sampling_frequency_control == JNI_TRUE) {
        int frequency_error = LIBUSB_SUCCESS;
        if (!set_and_verify_uac1_frequency(session, source_sample_rate, &frequency_error)) {
            lease.lock.release();
            lease.session = nullptr;
            close_registered_session(handle);
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
        lease.lock.release();
        lease.session = nullptr;
        close_registered_session(handle);
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
    auto lease = acquire_session(handle);
    auto* session = lease.session;
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

    if (session->control.load() == kSessionControlStop) {
        return -2; // Stop was requested before this block was accepted.
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
        const int control = session->control.load();
        if (control == kSessionControlStop) {
            if (!abort_in_flight(session)) {
                record_failure(&failure, "stop_reap_failed", LIBUSB_ERROR_TIMEOUT);
                break;
            }
            session->pending_pcm.clear();
            session->pending_offset = 0;
            session->frame_phase = 0;
            session->feedback_phase = 0;
            return -2;
        }
        if (control == kSessionControlPause) {
            // Stop submitting new URBs, but let the already-submitted bounded
            // window complete. Preserve the unsent PCM suffix and packet phase.
            while (!session->in_flight.empty() && !failure.failed) {
                if (session->control.load() == kSessionControlStop) break;
                if (!wait_for_progress(session, &failure)) break;
            }
            if (session->control.load() == kSessionControlStop) {
                if (!abort_in_flight(session)) {
                    record_failure(&failure, "stop_reap_failed", LIBUSB_ERROR_TIMEOUT);
                    break;
                }
                session->pending_pcm.clear();
                session->pending_offset = 0;
                session->frame_phase = 0;
                session->feedback_phase = 0;
                return -2;
            }
            if (failure.failed) break;
            return frame_count;
        }

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
                const uint32_t feedback = session->feedback_rate_fp.load(std::memory_order_acquire);
                if (!flowery::usb::next_feedback_packet_frames(
                        feedback, session->feedback_fraction_bits, session->interval_us,
                        session->feedback_base_interval_us, feedback_phase,
                        frames, next_feedback_phase)) {
                    record_failure(&failure, "invalid_feedback_packet_schedule", LIBUSB_ERROR_INVALID_PARAM);
                    break;
                }
            } else {
                if (!flowery::usb::next_pcm_packet_frames(
                        static_cast<uint32_t>(session->sample_rate), session->interval_us,
                        phase, frames, next_phase)) {
                    record_failure(&failure, "invalid_pcm_packet_schedule", LIBUSB_ERROR_INVALID_PARAM);
                    break;
                }
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
        if (session->control.load() != kSessionControlRun) {
            continue;
        }
        if (session->in_flight.size() >= max_urbs_in_flight(session)) {
            if (!wait_for_progress(session, &failure)) break;
            continue;
        }

        const auto submit_result = submit_pcm_urb_if_running(
            session, packet_bytes, planned_frames * bytes_per_frame,
            session->frame_phase, phase, session->feedback_phase, feedback_phase, &failure);
        if (submit_result == PcmSubmitResult::ControlChanged) continue;
        if (submit_result == PcmSubmitResult::Failed) break;
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
    auto lease = acquire_session(handle);
    auto* session = lease.session;
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
        compact_encoded(session);
        session->pending_encoded.resize(frame_bytes);
        env->GetByteArrayRegion(encoded, payload_offset, byte_count,
            reinterpret_cast<jbyte*>(session->pending_encoded.data()));
        if (env->ExceptionCheck()) { session->pending_encoded.clear(); return -1; }

        TransportFailure frame_failure;
        const auto control_result = honor_encoded_write_control(session, &frame_failure);
        if (control_result == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
        if (control_result == EncodedControlResult::Failed) {
            snapshot_transport_failure(session, &frame_failure);
            throw_transport_failure(env, session, frame_failure);
            return -1;
        }
        if (session->encoded_frame_deadline_valid &&
            std::chrono::steady_clock::now() < session->next_encoded_frame_due) {
            std::unique_lock<std::mutex> lock(session->event_mutex);
            session->event_condition.wait_until(lock, session->next_encoded_frame_due, [&]() {
                return session->control.load() != kSessionControlRun;
            });
            lock.unlock();
            const auto deadline_control = honor_encoded_write_control(session, &frame_failure);
            if (deadline_control == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
            if (deadline_control == EncodedControlResult::Failed) {
                snapshot_transport_failure(session, &frame_failure);
                throw_transport_failure(env, session, frame_failure);
                return -1;
            }
        }
        const auto frame_start = std::chrono::steady_clock::now();
        std::vector<unsigned int> frame_packets;
        frame_packets.reserve(packet_count);
        for (size_t i = 0; i < full_packets; ++i) frame_packets.push_back(session->max_packet_bytes);
        if (remainder != 0) frame_packets.push_back(static_cast<unsigned int>(remainder));
        frame_packets.push_back(0); // UAC1 transfer delimiter between encoded frames
        for (;;) {
            const auto submit_result = submit_encoded_urb_if_running(
                session, frame_packets, frame_bytes, &frame_failure);
            if (submit_result == EncodedSubmitResult::Submitted) break;
            if (submit_result == EncodedSubmitResult::Failed) {
                snapshot_transport_failure(session, &frame_failure);
                throw_transport_failure(env, session, frame_failure);
                return -1;
            }
            const auto changed_control = honor_encoded_write_control(session, &frame_failure);
            if (changed_control == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
            if (changed_control == EncodedControlResult::Failed) {
                snapshot_transport_failure(session, &frame_failure);
                throw_transport_failure(env, session, frame_failure);
                return -1;
            }
        }
        while (!frame_failure.failed && !session->in_flight.empty()) {
            if (!wait_for_progress(session, &frame_failure)) break;
            const auto control_result = honor_encoded_write_control(session, &frame_failure);
            if (control_result == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
            if (control_result == EncodedControlResult::Failed) break;
        }
        if (frame_failure.failed) {
            snapshot_transport_failure(session, &frame_failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, frame_failure);
            return -1;
        }
        const auto after_frame_control = honor_encoded_write_control(session, &frame_failure);
        if (after_frame_control == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
        if (after_frame_control == EncodedControlResult::Failed) {
            snapshot_transport_failure(session, &frame_failure);
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
        const auto control_result = honor_encoded_write_control(session, &failure);
        if (control_result == EncodedControlResult::Stopped) return -2; // Stop cancels this encoded packet.
        if (control_result == EncodedControlResult::Failed) break;
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
                std::uint32_t feedback_frames = 0;
                std::uint64_t next_feedback_phase = 0;
                if (!flowery::usb::next_feedback_packet_frames(
                        static_cast<std::uint32_t>(feedback), session->feedback_fraction_bits,
                        session->interval_us, session->feedback_base_interval_us,
                        feedback_phase, feedback_frames, next_feedback_phase)) {
                    record_failure(&failure, "invalid_encoded_feedback_schedule", LIBUSB_ERROR_INVALID_PARAM);
                    break;
                }
                frames = feedback_frames;
                feedback_phase = next_feedback_phase;
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
        const auto submit_result = submit_encoded_urb_if_running(
            session, packet_bytes, total, &failure);
        if (submit_result == EncodedSubmitResult::ControlChanged) continue;
        if (submit_result == EncodedSubmitResult::Failed) break;
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
    auto lease = acquire_session(handle);
    auto* session = lease.session;
    if (session == nullptr) return -1;

    TransportFailure failure;
    reap_completed(session, &failure);

    auto stop_encoded_writer = [&]() -> jint {
        if (!abort_in_flight(session)) return -1;
        session->pending_encoded.clear();
        session->encoded_offset = 0;
        session->encoded_rate_phase = 0;
        session->encoded_feedback_phase = 0;
        session->encoded_frame_deadline_valid = false;
        return -2;
    };
    auto drain_encoded_window = [&]() -> bool {
        while (!failure.failed && !session->in_flight.empty()) {
            if (session->control.load() == kSessionControlStop) return false;
            if (!wait_for_progress(session, &failure)) return false;
        }
        return !failure.failed;
    };
    if (session->encoded_mode == 1) {
        if (!drain_encoded_window()) {
            if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
            snapshot_transport_failure(session, &failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, failure);
            return -1;
        }
        if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
        return 0;
    }
    if (session->encoded_mode == 2) {
        while (!failure.failed) {
            const auto control_action = flowery::usb::encoded_control_action(
                session->control.load(), session->in_flight.size());
            if (control_action == flowery::usb::EncodedControlAction::Abort) return stop_encoded_writer();
            if (control_action == flowery::usb::EncodedControlAction::WaitForResume) return 0;
            if (control_action == flowery::usb::EncodedControlAction::DrainInFlight) {
                if (!drain_encoded_window()) {
                    if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
                    break;
                }
                if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
                return 0;
            }
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
            const auto submit_result = submit_encoded_urb_if_running(
                session, final_packet, tail, &failure);
            if (submit_result == EncodedSubmitResult::ControlChanged) continue;
            if (submit_result == EncodedSubmitResult::Failed) break;
        }
        if (!drain_encoded_window()) {
            if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
            snapshot_transport_failure(session, &failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, failure);
            return -1;
        }
        if (session->control.load() == kSessionControlStop) return stop_encoded_writer();
        if (session->control.load() == kSessionControlPause) return 0;
        compact_encoded(session);
        return 0;
    }

    auto stop_pcm_writer = [&]() -> jint {
        if (!abort_in_flight(session)) return -1;
        session->pending_pcm.clear();
        session->pending_offset = 0;
        session->frame_phase = 0;
        session->feedback_phase = 0;
        return -2;
    };
    auto pause_pcm_writer = [&]() -> jint {
        while (!failure.failed) {
            const auto action = flowery::usb::pcm_drain_control_action(
                session->control.load(), session->in_flight.size());
            if (action == flowery::usb::PcmDrainControlAction::Abort) {
                return stop_pcm_writer();
            }
            if (action == flowery::usb::PcmDrainControlAction::SubmitTail) return 0;
            if (action == flowery::usb::PcmDrainControlAction::DrainInFlight) {
                if (!wait_for_progress(session, &failure)) break;
                continue;
            }
            std::unique_lock<std::mutex> lock(session->event_mutex);
            session->event_condition.wait(lock, [&]() {
                return session->control.load() != kSessionControlPause;
            });
        }
        if (session->control.load() == kSessionControlStop) return stop_pcm_writer();
        if (failure.failed) {
            snapshot_transport_failure(session, &failure);
            abort_in_flight(session);
            throw_transport_failure(env, session, failure);
            return -1;
        }
        return 0;
    };
    if (session->control.load() == kSessionControlStop) {
        return stop_pcm_writer();
    }
    const size_t bytes_per_frame =
        static_cast<size_t>(session->channels) * static_cast<size_t>(session->subslot_bytes);
    const size_t remaining = session->pending_pcm.size() - session->pending_offset;
    if (!failure.failed && remaining > 0) {
        while (session->in_flight.size() >= max_urbs_in_flight(session)) {
            const int control = session->control.load();
            if (control == kSessionControlStop) return stop_pcm_writer();
            if (control == kSessionControlPause) {
                const jint pause_result = pause_pcm_writer();
                if (pause_result != 0) return pause_result;
                continue;
            }
            if (!wait_for_progress(session, &failure)) break;
        }
        if (session->control.load() == kSessionControlStop) return stop_pcm_writer();
        if (session->control.load() == kSessionControlPause) {
            const jint pause_result = pause_pcm_writer();
            if (pause_result != 0) return pause_result;
        }
        if (!failure.failed) {
            std::uint32_t tail_packet_bytes = 0;
            if (!flowery::usb::pcm_drain_tail_packet_bytes(
                    remaining, static_cast<std::uint32_t>(bytes_per_frame),
                    session->max_packet_bytes, tail_packet_bytes)) {
                record_failure(&failure, remaining > session->max_packet_bytes
                    ? "packet_exceeds_endpoint_capacity"
                    : "pcm_drain_tail_not_frame_aligned", LIBUSB_ERROR_INVALID_PARAM);
            } else {
                const std::vector<unsigned int> tail{tail_packet_bytes};
                for (;;) {
                    const auto submit_result = submit_pcm_urb_if_running(
                        session, tail, tail_packet_bytes, session->frame_phase,
                        session->frame_phase, session->feedback_phase,
                        session->feedback_phase, &failure);
                    if (submit_result == PcmSubmitResult::Submitted ||
                        submit_result == PcmSubmitResult::Failed) break;
                    const int control = session->control.load();
                    if (control == kSessionControlStop) return stop_pcm_writer();
                    if (control == kSessionControlPause) {
                        const jint pause_result = pause_pcm_writer();
                        if (pause_result != 0) return pause_result;
                    }
                    // A concurrent Resume may have won immediately after the
                    // Pause request; retry under the same submission gate.
                }
            }
        }
    }
    while (!failure.failed && !session->in_flight.empty()) {
        if (session->control.load() == kSessionControlStop) {
            return stop_pcm_writer();
        }
        wait_for_progress(session, &failure);
    }
    if (session->control.load() == kSessionControlStop) {
        return stop_pcm_writer();
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
    auto lease = acquire_session(handle);
    auto* session = lease.session;
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
Java_com_waizusora_flowery_UsbDacNative_nativeRequestPause(
    JNIEnv*, jobject, jlong handle) {
    return request_session_control(handle, kSessionControlPause) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeRequestResume(
    JNIEnv*, jobject, jlong handle) {
    return request_session_control(handle, kSessionControlRun) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeRequestStop(
    JNIEnv*, jobject, jlong handle) {
    return request_session_control(handle, kSessionControlStop) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_waizusora_flowery_UsbDacNative_nativeClose(
    JNIEnv*, jobject, jlong handle) {
    return close_registered_session(handle) ? JNI_TRUE : JNI_FALSE;
}
