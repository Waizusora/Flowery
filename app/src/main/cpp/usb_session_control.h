#pragma once
#include <atomic>
#include <cstddef>
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
  Lease()=default; Lease(Lease&&) noexcept=default; Lease& operator=(Lease&&) noexcept=default;
  Lease(const Lease&)=delete; Lease& operator=(const Lease&)=delete;
  explicit operator bool() const noexcept{return valid_;}
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
