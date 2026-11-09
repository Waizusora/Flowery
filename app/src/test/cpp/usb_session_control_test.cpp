#include "usb_session_control.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <future>
#include <iostream>
#include <mutex>
#include <thread>
using namespace std::chrono_literals;
using flowery::usb::ControlRequest;
using flowery::usb::ControlState;
using flowery::usb::SessionLifetimeGate;
void test_pause_wakes_blocked_writer_without_submitting() {
 ControlState control; std::mutex mutex; std::condition_variable wake;
 std::promise<void> waiting; std::atomic<int> submitted{0};
 std::thread writer([&]{ std::unique_lock<std::mutex> lock(mutex); waiting.set_value();
  wake.wait(lock,[&]{return control.load()!=static_cast<int>(ControlRequest::Run);});
  if(control.load()==static_cast<int>(ControlRequest::Run)) ++submitted;
 });
 waiting.get_future().wait();
 // Acquiring the mutex proves the writer entered condition_variable::wait()
 // and released the mutex before the Pause request is issued.
 std::unique_lock<std::mutex> barrier(mutex);
 assert(control.request(ControlRequest::Pause,wake));
 barrier.unlock();
 writer.join();
 assert(control.load()==static_cast<int>(ControlRequest::Pause)); assert(submitted.load()==0);
}
void test_resume_wakes_paused_writer() {
 ControlState control; std::mutex mutex; std::condition_variable wake;
 assert(control.request(ControlRequest::Pause,wake)); std::promise<void> waiting; std::atomic<int> submitted{0};
 std::thread writer([&]{std::unique_lock<std::mutex> lock(mutex); waiting.set_value();
  wake.wait(lock,[&]{return control.load()!=static_cast<int>(ControlRequest::Pause);});
  if(control.load()==static_cast<int>(ControlRequest::Run)) ++submitted;
 });
 waiting.get_future().wait();
 // The writer releases this mutex only after entering condition_variable::wait().
 std::unique_lock<std::mutex> barrier(mutex);
 assert(control.request(ControlRequest::Run,wake));
 barrier.unlock();
 writer.join();
 assert(submitted.load()==1);
}
void test_repeated_stop_is_idempotent_and_terminal() {
 ControlState control; std::condition_variable wake;
 assert(control.request(ControlRequest::Stop,wake)); assert(control.request(ControlRequest::Stop,wake));
 assert(control.request(ControlRequest::Stop,wake)); assert(!control.request(ControlRequest::Pause,wake));
 assert(!control.request(ControlRequest::Run,wake)); assert(control.load()==static_cast<int>(ControlRequest::Stop));
}
void test_close_waits_for_active_lease_and_rejects_new_ones() {
 SessionLifetimeGate gate; auto active=gate.acquire(); assert(static_cast<bool>(active));
 std::promise<void> started,finished; auto done=finished.get_future();
 std::thread closer([&]{started.set_value(); auto exclusive=gate.begin_close(); finished.set_value();});
 started.get_future().wait(); auto deadline=std::chrono::steady_clock::now()+1s;
 while(!gate.is_closing() && std::chrono::steady_clock::now()<deadline) std::this_thread::yield();
 assert(gate.is_closing()); assert(done.wait_for(40ms)==std::future_status::timeout);
 auto rejected=gate.acquire(); assert(!static_cast<bool>(rejected));
 active=SessionLifetimeGate::Lease{}; closer.join(); assert(done.wait_for(0ms)==std::future_status::ready);
}
int main() {
 test_pause_wakes_blocked_writer_without_submitting(); test_resume_wakes_paused_writer();
 test_repeated_stop_is_idempotent_and_terminal(); test_close_waits_for_active_lease_and_rejects_new_ones();
 std::cout<<"USB session control tests passed (4 scenarios).\n"; return 0;
}
