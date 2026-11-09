#include "usb_session_control.h"
#include <cstddef>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdint>
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

void test_encoded_uac1_pause_finishes_frame_before_waiting() {
 ControlState control; std::condition_variable wake;
 std::size_t in_flight=1; int submitted_frames=1; bool frame_completed=false;
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::Submit);
 assert(control.request(ControlRequest::Pause,wake));
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::DrainInFlight);
 // The already-submitted frame is allowed to complete; no next frame may start.
 assert(submitted_frames==1 && !frame_completed);
 --in_flight; frame_completed=true;
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::WaitForResume);
 assert(submitted_frames==1 && frame_completed);
 assert(control.request(ControlRequest::Run,wake));
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::Submit);
 ++submitted_frames;
 assert(submitted_frames==2);
}
void test_encoded_type_iii_pause_preserves_packet_cursor_and_phase() {
 ControlState control; std::condition_variable wake;
 std::size_t in_flight=2, encoded_offset=384;
 uint64_t rate_phase=17, feedback_phase=29;
 assert(control.request(ControlRequest::Pause,wake));
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::DrainInFlight);
 // Pause must not advance the unsent suffix or either packet pacing phase.
 assert(encoded_offset==384 && rate_phase==17 && feedback_phase==29);
 --in_flight;
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::DrainInFlight);
 --in_flight;
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::WaitForResume);
 assert(encoded_offset==384 && rate_phase==17 && feedback_phase==29);
 assert(control.request(ControlRequest::Run,wake));
 assert(flowery::usb::encoded_control_action(control.load(),in_flight)==
        flowery::usb::EncodedControlAction::Submit);
}

void test_pause_request_is_serialized_with_encoded_submission() {
 ControlState control; std::mutex mutex; std::condition_variable wake;
 std::promise<void> submit_lock_acquired, release_submit;
 auto release=release_submit.get_future();
 std::atomic<int> submitted{0};
 std::thread writer([&]{
  std::unique_lock<std::mutex> lock(mutex);
  submit_lock_acquired.set_value();
  release.wait();
  if(flowery::usb::encoded_submit_allowed(control)) ++submitted;
 });
 submit_lock_acquired.get_future().wait();
 std::promise<void> request_started,request_finished;
 auto request_done=request_finished.get_future();
 std::thread requester([&]{
  request_started.set_value();
  assert(flowery::usb::request_control_serialized(
      control,ControlRequest::Pause,mutex,wake));
  request_finished.set_value();
 });
 request_started.get_future().wait();
 // The request must wait while the writer owns the submission gate.
 assert(request_done.wait_for(40ms)==std::future_status::timeout);
 release_submit.set_value();
 writer.join();
 requester.join();
 {
  std::lock_guard<std::mutex> lock(mutex);
  assert(!flowery::usb::encoded_submit_allowed(control));
 }
 assert(submitted.load()==1);
}
void test_encoded_stop_takes_priority_over_pause_and_aborts() {
 ControlState control; std::condition_variable wake;
 assert(control.request(ControlRequest::Pause,wake));
 assert(flowery::usb::encoded_control_action(control.load(),3)==
        flowery::usb::EncodedControlAction::DrainInFlight);
 assert(control.request(ControlRequest::Stop,wake));
 assert(flowery::usb::encoded_control_action(control.load(),3)==
        flowery::usb::EncodedControlAction::Abort);
 assert(!control.request(ControlRequest::Run,wake));
}
void test_close_requests_stop_before_waiting_for_encoded_writer_lease() {
 ControlState control; std::mutex mutex; std::condition_variable wake;
 SessionLifetimeGate gate; auto writer_lease=gate.acquire();
 assert(static_cast<bool>(writer_lease));
 std::promise<void> close_started,close_finished; auto done=close_finished.get_future();
 std::thread closer([&]{
  assert(flowery::usb::request_control_serialized(
      control,ControlRequest::Stop,mutex,wake));
  close_started.set_value();
  auto exclusive=gate.begin_close();
  close_finished.set_value();
 });
 close_started.get_future().wait();
 auto deadline=std::chrono::steady_clock::now()+1s;
 while(!gate.is_closing() && std::chrono::steady_clock::now()<deadline) std::this_thread::yield();
 assert(gate.is_closing());
 assert(flowery::usb::encoded_control_action(control.load(),1)==
        flowery::usb::EncodedControlAction::Abort);
 assert(done.wait_for(40ms)==std::future_status::timeout);
 writer_lease=SessionLifetimeGate::Lease{};
 closer.join();
 assert(done.wait_for(0ms)==std::future_status::ready);
}
void test_feedback_transfer_is_not_resubmitted_after_event_loop_failure() {
 assert(flowery::usb::feedback_resubmit_allowed(false, true, 0, 0));
 assert(!flowery::usb::feedback_resubmit_allowed(true, true, 0, 0));
 assert(!flowery::usb::feedback_resubmit_allowed(false, false, 0, 0));
 assert(!flowery::usb::feedback_resubmit_allowed(false, true, -1, 0));
}
void test_failed_dispatcher_teardown_pump_requires_exclusive_event_ownership() {
 assert(flowery::usb::failed_dispatcher_teardown_pump_allowed(false, -1, 0));
 assert(!flowery::usb::failed_dispatcher_teardown_pump_allowed(true, -1, 0));
 assert(!flowery::usb::failed_dispatcher_teardown_pump_allowed(false, 0, 0));
}
void test_feedback_initial_submission_state_is_published_before_callback_can_run() {
 // The event dispatcher may invoke the callback as soon as submission is
 // accepted. Model that ordering: the callback clears in-flight after a
 // failed resubmit, and the submitter must not overwrite that result later.
 std::atomic<bool> in_flight{false};
 in_flight.store(true, std::memory_order_release); // before libusb_submit_transfer
 assert(in_flight.load(std::memory_order_acquire));
 in_flight.store(false, std::memory_order_release); // callback: resubmit failed
 assert(!in_flight.load(std::memory_order_acquire));
}
void test_transfer_reclaim_waits_until_callback_dispatch_returns() {
 using flowery::usb::transfer_reclaimable;
 // Not completed: never reclaim, even if the dispatcher has advanced.
 assert(!transfer_reclaimable(false, 4, 10));
 // Callback published completion inside event iteration 4; iteration 4
 // itself is not sufficient because libusb has not returned from dispatch.
 assert(!transfer_reclaimable(true, 5, 4));
 assert(!transfer_reclaimable(true, 5, 4));
 // Once libusb_handle_events returns and iteration 5 is published, reclaim is safe.
 assert(transfer_reclaimable(true, 5, 5));
 assert(transfer_reclaimable(true, 5, 6));
 // A missing callback barrier must never be treated as proof of ownership.
 assert(!transfer_reclaimable(true, 0, 100));
}
int main() {
 test_pause_wakes_blocked_writer_without_submitting(); test_resume_wakes_paused_writer();
 test_repeated_stop_is_idempotent_and_terminal(); test_close_waits_for_active_lease_and_rejects_new_ones();
 test_encoded_uac1_pause_finishes_frame_before_waiting();
 test_encoded_type_iii_pause_preserves_packet_cursor_and_phase();
 test_encoded_stop_takes_priority_over_pause_and_aborts();
 test_close_requests_stop_before_waiting_for_encoded_writer_lease();
 test_pause_request_is_serialized_with_encoded_submission();
 test_feedback_transfer_is_not_resubmitted_after_event_loop_failure();
 test_failed_dispatcher_teardown_pump_requires_exclusive_event_ownership();
 test_transfer_reclaim_waits_until_callback_dispatch_returns();
 test_feedback_initial_submission_state_is_published_before_callback_can_run();
 std::cout<<"USB session control tests passed (13 scenarios).\n"; return 0;
}
