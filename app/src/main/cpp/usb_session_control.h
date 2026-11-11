#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <condition_variable>
#include <mutex>
#include <shared_mutex>
#include <utility>
namespace flowery::usb {
enum class ControlRequest : int { Run=0, Pause=1, Stop=2 };
class ControlState {
public:
 int load() const noexcept { return state_.load(std::memory_order_acquire); }
 bool request(ControlRequest requested,std::condition_variable& wake) noexcept {
  int desired=static_cast<int>(requested),current=state_.load(std::memory_order_acquire);
  for (;;) {
   if(current==static_cast<int>(ControlRequest::Stop)) {
    if(requested==ControlRequest::Stop){wake.notify_all();return true;} return false;
   }
   if(state_.compare_exchange_weak(current,desired,std::memory_order_acq_rel,std::memory_order_acquire)){wake.notify_all();return true;}
  }
 }
private: std::atomic<int> state_{static_cast<int>(ControlRequest::Run)};
};
inline bool request_control_serialized(
 ControlState& control, ControlRequest requested, std::mutex& mutex,
 std::condition_variable& wake) {
 std::lock_guard<std::mutex> lock(mutex);
 return control.request(requested,wake);
}
inline bool submission_allowed(const ControlState& control) noexcept {
 return control.load()==static_cast<int>(ControlRequest::Run);
}
inline bool feedback_resubmit_allowed(bool stopped, bool event_thread_running,
                                      int event_loop_error, int success_code) noexcept {
 return !stopped && event_thread_running && event_loop_error == success_code;
}
inline bool decode_feedback_sample(const unsigned char* data,
                                    unsigned int actual_length,
                                    std::uint8_t fraction_bits,
                                    std::uint32_t& value) noexcept {
 const unsigned int expected_length = fraction_bits == 14 ? 3U :
                                      fraction_bits == 16 ? 4U : 0U;
 if (data == nullptr || expected_length == 0 || actual_length != expected_length) return false;
 std::uint32_t decoded = static_cast<std::uint32_t>(data[0]) |
                         (static_cast<std::uint32_t>(data[1]) << 8) |
                         (static_cast<std::uint32_t>(data[2]) << 16);
 if (expected_length == 4U) {
  decoded |= static_cast<std::uint32_t>(data[3]) << 24;
 }
 if (decoded == 0) return false;
 value = decoded;
 return true;
}
inline bool failed_dispatcher_teardown_pump_allowed(
    bool event_thread_running, int event_loop_error, int success_code) noexcept {
 return !event_thread_running && event_loop_error != success_code;
}
inline bool transfer_reclaimable(bool callback_completed,
                                 std::uint64_t callback_return_iteration,
                                 std::uint64_t completed_event_iteration) noexcept {
 return callback_completed && callback_return_iteration != 0 &&
        completed_event_iteration >= callback_return_iteration;
}
// Advance the nominal PCM service-interval accumulator without losing its
// fractional frame remainder between packets or URB boundaries.
inline bool next_pcm_packet_frames(std::uint32_t sample_rate,
                                   std::uint32_t interval_us,
                                   std::uint64_t phase_before,
                                   std::uint32_t& frames,
                                   std::uint64_t& phase_after) noexcept {
 if (sample_rate == 0 || interval_us == 0 || phase_before >= 1000000ULL) return false;
 const std::uint64_t accumulated =
     phase_before + static_cast<std::uint64_t>(sample_rate) * interval_us;
 const std::uint64_t frame_count = accumulated / 1000000ULL;
 if (frame_count > 0xffffffffULL) return false;
 frames = static_cast<std::uint32_t>(frame_count);
 phase_after = accumulated % 1000000ULL;
 return true;
}
inline bool iso_packet_completed_exactly(unsigned int packet_status,
                                          unsigned int completed_status,
                                          unsigned int actual_length,
                                          unsigned int requested_length) noexcept {
 return packet_status == completed_status && actual_length == requested_length;
}
enum class EncodedControlAction { Submit, DrainInFlight, WaitForResume, Abort };

inline EncodedControlAction encoded_control_action(int control, std::size_t in_flight) noexcept {
 if (control == static_cast<int>(ControlRequest::Stop)) return EncodedControlAction::Abort;
 if (control == static_cast<int>(ControlRequest::Pause)) {
  return in_flight == 0 ? EncodedControlAction::WaitForResume
                        : EncodedControlAction::DrainInFlight;
 }
 return EncodedControlAction::Submit;
}
class SessionLifetimeGate {
public:
 class Lease {
 public:
  Lease()=default;
  Lease(Lease&& other) noexcept
      : lock_(std::move(other.lock_)), valid_(std::exchange(other.valid_, false)) {}
  Lease& operator=(Lease&& other) noexcept {
   if (this != &other) {
    lock_ = std::move(other.lock_);
    valid_ = std::exchange(other.valid_, false);
   }
   return *this;
  }
  Lease(const Lease&)=delete; Lease& operator=(const Lease&)=delete;
  explicit operator bool() const noexcept{return valid_ && lock_.owns_lock();}
  void release() noexcept { if (lock_.owns_lock()) lock_.unlock(); valid_=false; }
 private:
  friend class SessionLifetimeGate;
  Lease(std::shared_lock<std::shared_mutex>&& lock,bool valid):lock_(std::move(lock)),valid_(valid){}
  std::shared_lock<std::shared_mutex> lock_; bool valid_=false;
 };
 Lease acquire() const {
  if (closing_.load(std::memory_order_acquire)) {
   return Lease(std::shared_lock<std::shared_mutex>{},false);
  }
  std::shared_lock<std::shared_mutex> lock(mutex_);
  if (closing_.load(std::memory_order_acquire)) return Lease(std::move(lock),false);
  return Lease(std::move(lock),true);
 }
 std::unique_lock<std::shared_mutex> begin_close() {
  closing_.store(true,std::memory_order_release); return std::unique_lock<std::shared_mutex>(mutex_);
 }
 bool is_closing() const noexcept{return closing_.load(std::memory_order_acquire);}
private: mutable std::shared_mutex mutex_; std::atomic<bool> closing_{false};
};
} // namespace flowery::usb
