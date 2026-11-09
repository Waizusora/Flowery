#pragma once
#include <atomic>
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
class SessionLifetimeGate {
public:
 class Lease {
 public:
  Lease()=default; Lease(Lease&&) noexcept=default; Lease& operator=(Lease&&) noexcept=default;
  Lease(const Lease&)=delete; Lease& operator=(const Lease&)=delete;
  explicit operator bool() const noexcept{return valid_;}
 private:
  friend class SessionLifetimeGate;
  Lease(std::shared_lock<std::shared_mutex>&& lock,bool valid):lock_(std::move(lock)),valid_(valid){}
  std::shared_lock<std::shared_mutex> lock_; bool valid_=false;
 };
 Lease acquire() const {
  std::shared_lock<std::shared_mutex> lock(mutex_);
  return Lease(std::move(lock),!closing_.load(std::memory_order_acquire));
 }
 std::unique_lock<std::shared_mutex> begin_close() {
  closing_.store(true,std::memory_order_release); return std::unique_lock<std::shared_mutex>(mutex_);
 }
 bool is_closing() const noexcept{return closing_.load(std::memory_order_acquire);}
private: mutable std::shared_mutex mutex_; std::atomic<bool> closing_{false};
};
} // namespace flowery::usb
