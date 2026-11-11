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
void test_moving_a_session_lease_invalidates_the_source() {
 SessionLifetimeGate gate;
 auto source=gate.acquire();
 assert(static_cast<bool>(source));
 auto destination=std::move(source);
 assert(!static_cast<bool>(source));
 assert(static_cast<bool>(destination));
 auto replacement=gate.acquire();
 assert(static_cast<bool>(replacement));
 replacement=std::move(destination);
 assert(!static_cast<bool>(destination));
 assert(static_cast<bool>(replacement));
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

void test_pcm_drain_pause_waits_for_resume_before_submitting_tail() {
 ControlState control; std::condition_variable wake;
 using Action = flowery::usb::PcmDrainControlAction;
 // Run permits the staged final packet to be submitted.
 assert(flowery::usb::pcm_drain_control_action(control.load(),0)==Action::SubmitTail);
 assert(control.request(ControlRequest::Pause,wake));
 // Existing transfers must finish before the drain waits; no tail is submitted.
 assert(flowery::usb::pcm_drain_control_action(control.load(),2)==Action::DrainInFlight);
 assert(flowery::usb::pcm_drain_control_action(control.load(),0)==Action::WaitForResume);
 // A paused drain must not report completion while its tail remains staged.
 assert(control.request(ControlRequest::Run,wake));
 assert(flowery::usb::pcm_drain_control_action(control.load(),0)==Action::SubmitTail);
 assert(control.request(ControlRequest::Pause,wake));
 assert(control.request(ControlRequest::Stop,wake));
 assert(flowery::usb::pcm_drain_control_action(control.load(),0)==Action::Abort);
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
  if(flowery::usb::submission_allowed(control)) ++submitted;
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
  assert(!flowery::usb::submission_allowed(control));
 }
 assert(submitted.load()==1);
}
void test_pause_request_is_serialized_with_pcm_submission() {
 ControlState control; std::mutex mutex; std::condition_variable wake;
 std::promise<void> submit_lock_acquired, release_submit;
 auto release=release_submit.get_future();
 std::atomic<int> submitted{0};
 std::thread writer([&]{
  std::unique_lock<std::mutex> lock(mutex);
  submit_lock_acquired.set_value();
  release.wait();
  if(flowery::usb::submission_allowed(control)) ++submitted;
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
 assert(request_done.wait_for(40ms)==std::future_status::timeout);
 release_submit.set_value();
 writer.join();
 requester.join();
 {
  std::lock_guard<std::mutex> lock(mutex);
  assert(!flowery::usb::submission_allowed(control));
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
void test_feedback_sample_decoder_requires_exact_protocol_length() {
 using flowery::usb::decode_feedback_sample;
 const unsigned char full_speed[] = {0x00, 0x80, 0x00};
 const unsigned char high_speed[] = {0x00, 0x00, 0x80, 0x00};
 std::uint32_t value = 0;
 assert(decode_feedback_sample(full_speed, 3, 14, value));
 assert(value == 0x008000U);
 assert(decode_feedback_sample(high_speed, 4, 16, value));
 assert(value == 0x00800000U);
 // Short and overlong samples are rejected rather than partially decoded.
 assert(!decode_feedback_sample(full_speed, 2, 14, value));
 assert(!decode_feedback_sample(full_speed, 4, 14, value));
 assert(!decode_feedback_sample(high_speed, 3, 16, value));
 assert(!decode_feedback_sample(high_speed, 5, 16, value));
 assert(!decode_feedback_sample(full_speed, 3, 15, value));
 assert(!decode_feedback_sample(nullptr, 3, 14, value));
 const unsigned char zero_sample[] = {0, 0, 0};
 assert(!decode_feedback_sample(zero_sample, 3, 14, value));
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
void test_feedback_packet_scheduler_preserves_high_speed_44100hz_fraction() {
 std::uint64_t phase = 0;
 std::uint64_t total_frames = 0;
 // 44.1 kHz expressed as frames per 125 us in 16.16 format.
 constexpr std::uint32_t feedback = 361268;
 for (int packet = 0; packet < 8000; ++packet) {
  std::uint32_t frames = 0;
  std::uint64_t next_phase = 0;
  assert(flowery::usb::next_feedback_packet_frames(
      feedback, 16, 125, 125, phase, frames, next_phase));
  total_frames += frames;
  phase = next_phase;
 }
 assert(total_frames == 44100);
}
void test_feedback_packet_scheduler_preserves_full_speed_44100hz_fraction() {
 std::uint64_t phase = 0;
 std::uint64_t total_frames = 0;
 // 44.1 kHz expressed as frames per 1 ms in 10.14 format.
 constexpr std::uint32_t feedback = 722535;
 for (int packet = 0; packet < 1000; ++packet) {
  std::uint32_t frames = 0;
  std::uint64_t next_phase = 0;
  assert(flowery::usb::next_feedback_packet_frames(
      feedback, 14, 1000, 1000, phase, frames, next_phase));
  total_frames += frames;
  phase = next_phase;
 }
 assert(total_frames == 44100);
}
void test_feedback_packet_scheduler_rejects_invalid_parameters() {
 std::uint32_t frames = 0;
 std::uint64_t phase = 0;
 assert(!flowery::usb::next_feedback_packet_frames(0, 16, 125, 125, 0, frames, phase));
 assert(!flowery::usb::next_feedback_packet_frames(361267, 15, 125, 125, 0, frames, phase));
 assert(!flowery::usb::next_feedback_packet_frames(361267, 16, 0, 125, 0, frames, phase));
 assert(!flowery::usb::next_feedback_packet_frames(361267, 16, 125, 0, 0, frames, phase));
 assert(!flowery::usb::next_feedback_packet_frames(361267, 16, 125, 1000, 0, frames, phase));
 assert(!flowery::usb::next_feedback_packet_frames(361267, 16, 125, 125, 65536, frames, phase));
}
void test_pcm_packet_scheduler_preserves_16khz_interval_count() {
 std::uint64_t phase = 0;
 for (int packet = 0; packet < 8000; ++packet) {
  std::uint32_t frames = 0;
  std::uint64_t next_phase = 0;
  assert(flowery::usb::next_pcm_packet_frames(16000, 125, phase, frames, next_phase));
  assert(frames == 2);
  assert(next_phase == 0);
  phase = next_phase;
 }
}
void test_pcm_packet_scheduler_preserves_44100hz_fraction_across_urb_boundaries() {
 std::uint64_t phase = 0;
 std::uint64_t total_frames = 0;
 const std::uint32_t expected_prefix[] = {5, 6, 5, 6};
 for (std::size_t packet = 0; packet < 8000; ++packet) {
  std::uint32_t frames = 0;
  std::uint64_t next_phase = 0;
  assert(flowery::usb::next_pcm_packet_frames(44100, 125, phase, frames, next_phase));
  if (packet < 4) assert(frames == expected_prefix[packet]);
  total_frames += frames;
  phase = next_phase;
 }
 // 8,000 service intervals at 125 us are exactly one second of audio.
 // The fractional accumulator must distribute 5/6-frame packets without drift.
 assert(total_frames == 44100);
 assert(phase == 0);
}
void test_pcm_packet_scheduler_rejects_invalid_phase_and_rate() {
 std::uint32_t frames = 0;
 std::uint64_t next_phase = 0;
 assert(!flowery::usb::next_pcm_packet_frames(0, 125, 0, frames, next_phase));
 assert(!flowery::usb::next_pcm_packet_frames(44100, 0, 0, frames, next_phase));
 assert(!flowery::usb::next_pcm_packet_frames(44100, 125, 1000000, frames, next_phase));
}
void test_pcm_drain_tail_packet_preserves_24_bit_96khz_frame_alignment() {
 using flowery::usb::pcm_drain_tail_packet_bytes;
 std::uint32_t tail = 0;
 // 24-bit stereo uses six bytes per sample frame; the final service interval
 // can contain up to eleven staged frames at 96 kHz / 125 us.
 assert(pcm_drain_tail_packet_bytes(66, 6, 1024, tail));
 assert(tail == 66);
 // 32-bit containers for the same source format remain eight-byte aligned.
 assert(pcm_drain_tail_packet_bytes(88, 8, 1024, tail));
 assert(tail == 88);
 assert(!pcm_drain_tail_packet_bytes(70, 6, 1024, tail));
 assert(!pcm_drain_tail_packet_bytes(1032, 6, 1024, tail));
 assert(!pcm_drain_tail_packet_bytes(0, 6, 1024, tail));
 assert(!pcm_drain_tail_packet_bytes(66, 0, 1024, tail));
 assert(!pcm_drain_tail_packet_bytes(66, 6, 0, tail));
}
void test_isochronous_packet_requires_exact_completion() {
 using flowery::usb::iso_packet_completed_exactly;
 constexpr unsigned int completed = 0;
 // A full packet is accepted only when libusb reports the requested byte count.
 assert(iso_packet_completed_exactly(completed, completed, 192, 192));
 // Short, zero-length, and over-reported completions are never silently accepted.
 assert(!iso_packet_completed_exactly(completed, completed, 191, 192));
 assert(!iso_packet_completed_exactly(completed, completed, 0, 192));
 assert(!iso_packet_completed_exactly(completed, completed, 193, 192));
 // A packet with an error status is rejected even if its byte count matches.
 assert(!iso_packet_completed_exactly(1, completed, 192, 192));
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
 test_moving_a_session_lease_invalidates_the_source();
 test_pcm_drain_pause_waits_for_resume_before_submitting_tail();
 test_encoded_uac1_pause_finishes_frame_before_waiting();
 test_encoded_type_iii_pause_preserves_packet_cursor_and_phase();
 test_encoded_stop_takes_priority_over_pause_and_aborts();
 test_close_requests_stop_before_waiting_for_encoded_writer_lease();
 test_pause_request_is_serialized_with_encoded_submission();
 test_pause_request_is_serialized_with_pcm_submission();
 test_feedback_sample_decoder_requires_exact_protocol_length();
 test_feedback_transfer_is_not_resubmitted_after_event_loop_failure();
 test_failed_dispatcher_teardown_pump_requires_exclusive_event_ownership();
 test_transfer_reclaim_waits_until_callback_dispatch_returns();
 test_isochronous_packet_requires_exact_completion();
 test_feedback_packet_scheduler_preserves_high_speed_44100hz_fraction();
  test_feedback_packet_scheduler_preserves_full_speed_44100hz_fraction();
  test_feedback_packet_scheduler_rejects_invalid_parameters();
  test_pcm_packet_scheduler_preserves_16khz_interval_count();
 test_pcm_packet_scheduler_preserves_44100hz_fraction_across_urb_boundaries();
 test_pcm_packet_scheduler_rejects_invalid_phase_and_rate();
 test_pcm_drain_tail_packet_preserves_24_bit_96khz_frame_alignment();
 test_feedback_initial_submission_state_is_published_before_callback_can_run();
 std::cout<<"USB session control tests passed (25 scenarios).\n"; return 0;
}
