//! USB Audio Class explicit feedback decoding and rate tracking.
//!
//! Full-speed feedback reports 10.14 samples per 1 ms frame. High-speed
//! feedback reports 16.16 samples per 125 us microframe.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FeedbackBusSpeed { Full, High }

pub fn decode_feedback_rate_hz(packet: &[u8], speed: FeedbackBusSpeed) -> Result<f64, String> {
    match speed {
        FeedbackBusSpeed::Full => {
            if packet.len() != 3 { return Err("Full-speed feedback packet must contain 3 bytes".into()); }
            let fixed = u32::from(packet[0]) | (u32::from(packet[1]) << 8) | (u32::from(packet[2]) << 16);
            if fixed == 0 { return Err("Feedback rate must be non-zero".into()); }
            Ok((f64::from(fixed) * 1_000.0) / 16_384.0)
        }
        FeedbackBusSpeed::High => {
            if packet.len() != 4 { return Err("High-speed feedback packet must contain 4 bytes".into()); }
            let fixed = u32::from_le_bytes([packet[0], packet[1], packet[2], packet[3]]);
            if fixed == 0 { return Err("Feedback rate must be non-zero".into()); }
            Ok((f64::from(fixed) * 8_000.0) / 65_536.0)
        }
    }
}


/// Fractional frame scheduler driven by the nominal or measured DAC clock.
/// The phase accumulator preserves fractional samples across service intervals.
#[derive(Debug, Clone, Copy, Default)]
pub struct PacketFrameScheduler {
    phase: u64,
}

impl PacketFrameScheduler {
    /// Discard fractional phase after the audio ring has been fully drained.
    pub fn reset(&mut self) {
        self.phase = 0;
    }

    pub fn next_frames(&mut self, rate_hz: f64, interval_us: u32) -> Result<usize, String> {
        if !rate_hz.is_finite() || !(1_000.0..=768_000.0).contains(&rate_hz) {
            return Err("Packet scheduler rate is outside the supported USB audio range".into());
        }
        if interval_us == 0 {
            return Err("USB service interval must be non-zero".into());
        }
        let rate_millihz = (rate_hz * 1_000.0).round() as u64;
        let numerator = rate_millihz
            .checked_mul(u64::from(interval_us))
            .ok_or("USB service interval frame calculation overflow")?;
        self.phase = self.phase.checked_add(numerator)
            .ok_or("USB frame phase overflow")?;
        let frames = self.phase / 1_000_000_000;
        self.phase %= 1_000_000_000;
        usize::try_from(frames).map_err(|_| "USB packet frame count exceeds platform limits".into())
    }
}

/// Worker-owned lifecycle for recovering an expired explicit-feedback clock.
/// No audio may be submitted while the old ring drains or while a new sample
/// is being acquired.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FeedbackReacquisitionState {
    Running,
    Quiescing,
    AwaitingFreshFeedback,
    Repriming,
}

impl Default for FeedbackReacquisitionState {
    fn default() -> Self { Self::Running }
}

impl FeedbackReacquisitionState {
    pub fn begin_if_stale(&mut self, stale: bool) -> bool {
        if stale && *self == Self::Running {
            *self = Self::Quiescing;
            true
        } else {
            false
        }
    }

    pub fn audio_ring_drained(&mut self) {
        if *self == Self::Quiescing {
            *self = Self::AwaitingFreshFeedback;
        }
    }

    pub fn fresh_sample_accepted(&mut self) {
        if *self == Self::AwaitingFreshFeedback {
            *self = Self::Repriming;
        }
    }

    pub fn audio_submission_allowed(self) -> bool {
        matches!(self, Self::Running | Self::Repriming)
    }

    pub fn audio_submission_started(&mut self) {
        if *self == Self::Repriming {
            *self = Self::Running;
        }
    }

    pub fn ignores_feedback_for_estimation(self) -> bool {
        self == Self::Quiescing
    }
}

/// Audio submission on an asynchronous endpoint must wait for its first
/// valid explicit-feedback sample. Endpoints without feedback use the nominal
/// rate selected by their synchronization mode.
pub fn audio_clock_is_ready(feedback_required: bool, measured_rate_hz: Option<f64>) -> bool {
    !feedback_required || measured_rate_hz.is_some_and(|rate| rate.is_finite() && rate > 0.0)
}

/// Validate a measured clock rate against a nominal stream rate before it can
/// influence packet sizing. The tolerance accommodates normal clock drift while
/// rejecting malformed or unrelated feedback values.
pub fn validate_feedback_rate_hz(nominal_hz: u32, measured_hz: f64) -> Result<(), String> {
    if nominal_hz == 0 {
        return Err("Nominal USB audio sample rate must be non-zero".into());
    }
    if !measured_hz.is_finite() {
        return Err("USB feedback rate is not finite".into());
    }
    // Feedback is a clock measurement, not a new stream rate. A value
    // outside ±10% is almost certainly a unit/endpoint/speed decoding error.
    // Keep this guard deliberately conservative before it reaches packet sizing.
    const MIN_CLOCK_RATIO: f64 = 0.90;
    const MAX_CLOCK_RATIO: f64 = 1.10;
    let lower = f64::from(nominal_hz) * MIN_CLOCK_RATIO;
    let upper = f64::from(nominal_hz) * MAX_CLOCK_RATIO;
    if measured_hz < lower || measured_hz > upper {
        return Err(format!(
            "USB feedback rate {measured_hz:.2} Hz is outside the allowed range for nominal {nominal_hz} Hz"
        ));
    }
    Ok(())
}

/// Tracks consecutive abrupt feedback samples without coupling policy to libusb callbacks.
#[derive(Debug, Clone, Copy)]
pub struct FeedbackOutlierGuard {
    consecutive: u8,
    limit: u8,
}

impl FeedbackOutlierGuard {
    pub fn new(limit: u8) -> Result<Self, String> {
        if limit == 0 {
            return Err("Feedback outlier limit must be non-zero".into());
        }
        Ok(Self { consecutive: 0, limit })
    }

    pub fn record_accepted(&mut self) {
        self.consecutive = 0;
    }

    /// Returns true when the consecutive-outlier limit has been reached.
    pub fn record_outlier(&mut self) -> bool {
        self.consecutive = self.consecutive.saturating_add(1);
        self.consecutive >= self.limit
    }

    pub fn consecutive(&self) -> u8 {
        self.consecutive
    }
}


/// Validate one explicit-feedback isochronous packet before decoding its clock value.
/// A feedback sample is usable only when both transfer and packet completed fully.
pub fn validate_feedback_completion(
    transfer_status: i32,
    packet_status: i32,
    actual_length: u32,
    expected_length: u32,
) -> Result<(), String> {
    if transfer_status != 0 {
        return Err(format!("USB feedback transfer failed with status {transfer_status}"));
    }
    if packet_status != 0 {
        return Err(format!("USB feedback packet failed with status {packet_status}"));
    }
    if actual_length != expected_length {
        return Err(format!(
            "USB feedback packet length mismatch: received {actual_length}, expected {expected_length}"
        ));
    }
    if expected_length == 0 {
        return Err("USB feedback packet length must be non-zero".into());
    }
    Ok(())
}

/// Allow at least two advertised feedback polling periods before treating
/// the clock as stale. The floor covers normal host scheduling jitter.
pub fn feedback_clock_stale_timeout(interval_us: u32) -> std::time::Duration {
    std::time::Duration::from_micros(u64::from(interval_us).saturating_mul(2))
        .max(std::time::Duration::from_millis(250))
}

/// The feedback transfer deadline spans at least three advertised polling
/// periods so a legal slow endpoint is not mistaken for a failed transfer.
pub fn feedback_completion_timeout(interval_us: u32) -> std::time::Duration {
    std::time::Duration::from_micros(u64::from(interval_us).saturating_mul(3))
        .max(std::time::Duration::from_millis(500))
}

/// Missing feedback and feedback older than the timeout are both stale.
pub fn feedback_clock_is_stale(age: Option<std::time::Duration>, timeout: std::time::Duration) -> bool {
    age.map(|elapsed| elapsed > timeout).unwrap_or(true)
}

/// A bounded low-pass estimate used by the packet scheduler. The caller must
/// validate each sample against the selected format before updating the tracker.
#[derive(Debug, Clone, Copy)]
pub struct FeedbackRateTracker {
    estimate_hz: Option<f64>,
    smoothing: f64,
}

impl FeedbackRateTracker {
    pub fn new(smoothing: f64) -> Result<Self, String> {
        if !(0.0..=1.0).contains(&smoothing) || smoothing == 0.0 {
            return Err("Feedback smoothing must be in the interval (0, 1]".into());
        }
        Ok(Self { estimate_hz: None, smoothing })
    }

    pub fn update(&mut self, measured_hz: f64) -> Result<f64, String> {
        if !measured_hz.is_finite() || !(1_000.0..=768_000.0).contains(&measured_hz) {
            return Err("Feedback rate is outside the supported USB audio range".into());
        }
        let estimate = match self.estimate_hz {
            Some(previous) => previous + self.smoothing * (measured_hz - previous),
            None => measured_hz,
        };
        self.estimate_hz = Some(estimate);
        Ok(estimate)
    }

    /// Accept a feedback sample only when it is close to the current clock
    /// estimate. A rejected outlier does not mutate the estimator.
    pub fn update_checked(
        &mut self,
        measured_hz: f64,
        max_relative_step: f64,
    ) -> Result<Option<f64>, String> {
        if !max_relative_step.is_finite() || !(0.0..=1.0).contains(&max_relative_step) {
            return Err("Feedback outlier threshold must be in the interval [0, 1]".into());
        }
        if !measured_hz.is_finite() || !(1_000.0..=768_000.0).contains(&measured_hz) {
            return Err("Feedback rate is outside the supported USB audio range".into());
        }
        if let Some(previous) = self.estimate_hz {
            let relative_step = (measured_hz - previous).abs() / previous;
            if relative_step > max_relative_step { return Ok(None); }
        }
        self.update(measured_hz).map(Some)
    }
    pub fn estimate_hz(&self) -> Option<f64> { self.estimate_hz }

    /// Start a new acquisition epoch without carrying the previous clock into it.
    pub fn reset(&mut self) {
        self.estimate_hz = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;


    #[test]
    fn feedback_reacquisition_requires_drain_then_fresh_sample_then_reprime() {
        let mut state = FeedbackReacquisitionState::default();
        assert!(state.audio_submission_allowed());
        assert!(state.begin_if_stale(true));
        assert_eq!(state, FeedbackReacquisitionState::Quiescing);
        assert!(!state.audio_submission_allowed());
        assert!(state.ignores_feedback_for_estimation());
        state.audio_ring_drained();
        assert_eq!(state, FeedbackReacquisitionState::AwaitingFreshFeedback);
        assert!(!state.audio_submission_allowed());
        state.fresh_sample_accepted();
        assert_eq!(state, FeedbackReacquisitionState::Repriming);
        assert!(state.audio_submission_allowed());
        state.audio_submission_started();
        assert_eq!(state, FeedbackReacquisitionState::Running);
        assert!(!state.begin_if_stale(false));
    }

    #[test]
    fn feedback_tracker_reset_does_not_reuse_old_clock_estimate() {
        let mut tracker = FeedbackRateTracker::new(0.25).unwrap();
        assert_eq!(tracker.update(48_000.0).unwrap(), 48_000.0);
        tracker.reset();
        assert_eq!(tracker.estimate_hz(), None);
        assert_eq!(tracker.update_checked(47_999.0, 0.02).unwrap(), Some(47_999.0));
    }

    #[test]
    fn packet_scheduler_reset_discards_fractional_phase() {
        let mut scheduler = PacketFrameScheduler::default();
        assert_eq!(scheduler.next_frames(44_100.0, 125).unwrap(), 5);
        scheduler.reset();
        assert_eq!(scheduler.next_frames(44_100.0, 125).unwrap(), 5);
    }

    #[test]
    fn schedules_fractional_full_speed_44100_without_long_term_drift() {
        let mut scheduler = PacketFrameScheduler::default();
        let frames: usize = (0..1_000)
            .map(|_| scheduler.next_frames(44_100.0, 1_000).unwrap())
            .sum();
        assert_eq!(frames, 44_100);
    }

    #[test]
    fn schedules_high_speed_48000_as_six_frames_per_microframe() {
        let mut scheduler = PacketFrameScheduler::default();
        for _ in 0..100 {
            assert_eq!(scheduler.next_frames(48_000.0, 125).unwrap(), 6);
        }
    }

    #[test]
    fn schedules_high_speed_44100_fractionally_without_drift() {
        let mut scheduler = PacketFrameScheduler::default();
        let frames: usize = (0..80)
            .map(|_| scheduler.next_frames(44_100.0, 125).unwrap())
            .sum();
        assert_eq!(frames, 441);
    }

    #[test]
    fn decodes_full_speed_44100_feedback() {
        // 44.1 samples per 1 ms frame in 10.14 format.
        let fixed = (44.1_f64 * 16_384.0).round() as u32;
        let packet = [fixed as u8, (fixed >> 8) as u8, (fixed >> 16) as u8];
        let rate = decode_feedback_rate_hz(&packet, FeedbackBusSpeed::Full).unwrap();
        assert!((rate - 44_100.0).abs() < 1.0);
    }

    #[test]
    fn decodes_high_speed_48000_feedback() {
        // Six samples per 125 us microframe in 16.16 format.
        let fixed = (6.0_f64 * 65_536.0).round() as u32;
        let rate = decode_feedback_rate_hz(&fixed.to_le_bytes(), FeedbackBusSpeed::High).unwrap();
        assert!((rate - 48_000.0).abs() < 0.01);
    }

    #[test]
    fn rejects_feedback_packets_with_wrong_lengths_or_zero_values() {
        assert!(decode_feedback_rate_hz(&[1, 2], FeedbackBusSpeed::Full).is_err());
        assert!(decode_feedback_rate_hz(&[0, 0, 0], FeedbackBusSpeed::Full).is_err());
        assert!(decode_feedback_rate_hz(&[1, 2, 3], FeedbackBusSpeed::High).is_err());
        assert!(decode_feedback_rate_hz(&[0, 0, 0, 0], FeedbackBusSpeed::High).is_err());
    }

    #[test]
    fn asynchronous_audio_waits_for_a_valid_initial_feedback_sample() {
        assert!(!audio_clock_is_ready(true, None));
        assert!(!audio_clock_is_ready(true, Some(f64::NAN)));
        assert!(!audio_clock_is_ready(true, Some(0.0)));
        assert!(audio_clock_is_ready(true, Some(48_000.0)));
        assert!(audio_clock_is_ready(false, None));
    }

    #[test]
    fn validates_feedback_against_nominal_rate_tolerance() {
        assert!(validate_feedback_rate_hz(48_000, 43_200.0).is_ok());
        assert!(validate_feedback_rate_hz(48_000, 52_800.0).is_ok());
        assert!(validate_feedback_rate_hz(48_000, 43_199.0).is_err());
        assert!(validate_feedback_rate_hz(48_000, 52_801.0).is_err());
        // Catch common wrong-unit and wrong-speed interpretations early.
        assert!(validate_feedback_rate_hz(48_000, 6_000.0).is_err());
        assert!(validate_feedback_rate_hz(48_000, 384_000.0).is_err());
        assert!(validate_feedback_rate_hz(48_000, f64::INFINITY).is_err());
        assert!(validate_feedback_rate_hz(0, 48_000.0).is_err());
    }

    #[test]
    fn tracker_rejects_out_of_range_and_non_finite_samples_without_mutating_estimate() {
        let mut tracker = FeedbackRateTracker::new(0.5).unwrap();
        assert_eq!(tracker.update(48_000.0).unwrap(), 48_000.0);
        assert!(tracker.update(0.0).is_err());
        assert!(tracker.update(f64::NAN).is_err());
        assert_eq!(tracker.estimate_hz(), Some(48_000.0));
    }

    #[test]
    fn tracker_rejects_single_feedback_outlier_without_changing_clock_estimate() {
        let mut tracker = FeedbackRateTracker::new(0.25).unwrap();
        assert_eq!(tracker.update_checked(48_000.0, 0.02).unwrap(), Some(48_000.0));
        assert_eq!(tracker.update_checked(52_000.0, 0.02).unwrap(), None);
        assert_eq!(tracker.estimate_hz(), Some(48_000.0));
        assert_eq!(tracker.update_checked(48_100.0, 0.02).unwrap(), Some(48_025.0));
    }

    #[test]
    fn tracker_accepts_gradual_clock_drift_with_outlier_guard() {
        let mut tracker = FeedbackRateTracker::new(0.5).unwrap();
        assert_eq!(tracker.update_checked(44_100.0, 0.02).unwrap(), Some(44_100.0));
        assert_eq!(tracker.update_checked(44_200.0, 0.02).unwrap(), Some(44_150.0));
        assert_eq!(tracker.update_checked(44_300.0, 0.02).unwrap(), Some(44_225.0));
    }
    #[test]
    fn outlier_guard_requires_consecutive_outliers_and_resets_on_acceptance() {
        let mut guard = FeedbackOutlierGuard::new(3).unwrap();
        assert!(!guard.record_outlier());
        assert!(!guard.record_outlier());
        guard.record_accepted();
        assert_eq!(guard.consecutive(), 0);
        assert!(!guard.record_outlier());
        assert!(!guard.record_outlier());
        assert!(guard.record_outlier());
        assert_eq!(guard.consecutive(), 3);
        assert!(FeedbackOutlierGuard::new(0).is_err());
    }

    #[test]
    fn feedback_completion_requires_successful_transfer_and_packet() {
        assert_eq!(validate_feedback_completion(0, 0, 3, 3), Ok(()));
        assert!(validate_feedback_completion(5, 0, 3, 3)
            .unwrap_err().contains("transfer failed"));
        assert!(validate_feedback_completion(0, 2, 3, 3)
            .unwrap_err().contains("packet failed"));
    }

    #[test]
    fn feedback_completion_rejects_short_long_and_empty_packets() {
        assert!(validate_feedback_completion(0, 0, 2, 3)
            .unwrap_err().contains("received 2, expected 3"));
        assert!(validate_feedback_completion(0, 0, 4, 3)
            .unwrap_err().contains("received 4, expected 3"));
        assert!(validate_feedback_completion(0, 0, 0, 0)
            .unwrap_err().contains("must be non-zero"));
    }

    #[test]
    fn feedback_timeouts_follow_advertised_polling_interval() {
        use std::time::Duration;
        assert_eq!(feedback_clock_stale_timeout(1_000), Duration::from_millis(250));
        assert_eq!(feedback_completion_timeout(1_000), Duration::from_millis(500));
        assert_eq!(feedback_clock_stale_timeout(200_000), Duration::from_millis(400));
        assert_eq!(feedback_completion_timeout(200_000), Duration::from_millis(600));
        assert_eq!(feedback_clock_stale_timeout(32_768_000), Duration::from_micros(65_536_000));
        assert_eq!(feedback_completion_timeout(32_768_000), Duration::from_micros(98_304_000));
    }

    #[test]
    fn feedback_clock_staleness_handles_missing_and_expired_samples() {
        use std::time::Duration;
        let timeout = Duration::from_millis(500);
        assert!(feedback_clock_is_stale(None, timeout));
        assert!(!feedback_clock_is_stale(Some(timeout), timeout));
        assert!(feedback_clock_is_stale(Some(timeout + Duration::from_nanos(1)), timeout));
        assert!(!feedback_clock_is_stale(Some(Duration::from_millis(100)), timeout));
    }

    #[test]
    fn smooths_feedback_without_unbounded_values() {
        let mut tracker = FeedbackRateTracker::new(0.25).unwrap();
        assert_eq!(tracker.update(48_000.0).unwrap(), 48_000.0);
        assert_eq!(tracker.update(48_400.0).unwrap(), 48_100.0);
        assert!(tracker.update(f64::NAN).is_err());
    }
}


#[cfg(test)]
mod reacquisition_integration_tests {
    use super::*;
    use std::time::Duration;

    /// Deterministic worker-level harness: time and transfer completions are
    /// injected by the test, while the production policy primitives are used
    /// unchanged. No libusb device or wall-clock sleep is involved.
    struct SimulatedFeedbackStream {
        lifecycle: FeedbackReacquisitionState,
        tracker: FeedbackRateTracker,
        scheduler: PacketFrameScheduler,
        audio_transfers_in_flight: Vec<u64>,
        next_transfer_id: u64,
        terminal_error: bool,
    }

    impl SimulatedFeedbackStream {
        fn new() -> Self {
            Self {
                lifecycle: FeedbackReacquisitionState::Running,
                tracker: FeedbackRateTracker::new(0.25).unwrap(),
                scheduler: PacketFrameScheduler::default(),
                audio_transfers_in_flight: Vec::new(),
                next_transfer_id: 1,
                terminal_error: false,
            }
        }

        fn submit_audio(&mut self) -> Option<u64> {
            if self.terminal_error
                || !self.lifecycle.audio_submission_allowed()
                || !audio_clock_is_ready(true, self.tracker.estimate_hz())
            {
                return None;
            }
            let id = self.next_transfer_id;
            self.next_transfer_id += 1;
            self.audio_transfers_in_flight.push(id);
            self.lifecycle.audio_submission_started();
            Some(id)
        }

        fn observe_feedback_age(&mut self, age: Option<Duration>, timeout: Duration) {
            let stale = feedback_clock_is_stale(age, timeout);
            self.lifecycle.begin_if_stale(stale);
        }

        fn complete_audio_transfer(&mut self, transfer_id: u64) {
            let index = self.audio_transfers_in_flight.iter()
                .position(|id| *id == transfer_id)
                .expect("audio transfer must be in flight");
            self.audio_transfers_in_flight.remove(index);
            if self.audio_transfers_in_flight.is_empty() {
                let was_quiescing =
                    self.lifecycle == FeedbackReacquisitionState::Quiescing;
                self.lifecycle.audio_ring_drained();
                if was_quiescing {
                    self.scheduler.reset();
                    self.tracker.reset();
                }
            }
        }

        fn complete_feedback(
            &mut self,
            transfer_status: i32,
            packet_status: i32,
            bytes: &[u8],
            speed: FeedbackBusSpeed,
        ) -> Result<(), String> {
            if self.terminal_error {
                return Err("USB feedback stream is already in a terminal error state".into());
            }
            let expected = match speed {
                FeedbackBusSpeed::Full => 3,
                FeedbackBusSpeed::High => 4,
            };
            if let Err(error) = validate_feedback_completion(
                transfer_status, packet_status, bytes.len() as u32, expected,
            ) {
                self.terminal_error = true;
                return Err(error);
            }
            let measured = match decode_feedback_rate_hz(bytes, speed) {
                Ok(rate) => rate,
                Err(error) => {
                    self.terminal_error = true;
                    return Err(error);
                }
            };
            if self.lifecycle.ignores_feedback_for_estimation() {
                return Ok(());
            }
            if self.lifecycle == FeedbackReacquisitionState::AwaitingFreshFeedback {
                self.tracker.update(measured)?;
                self.lifecycle.fresh_sample_accepted();
                return Ok(());
            }
            self.tracker.update_checked(measured, 0.02)?;
            Ok(())
        }
    }

    fn high_speed_feedback(rate_hz: f64) -> [u8; 4] {
        let fixed = ((rate_hz / 8_000.0) * 65_536.0).round() as u32;
        fixed.to_le_bytes()
    }

    #[test]
    fn delayed_feedback_quiesces_drains_ignores_old_samples_and_recovers() {
        let mut stream = SimulatedFeedbackStream::new();
        let timeout = Duration::from_millis(250);
        let initial = high_speed_feedback(48_000.0);

        stream.complete_feedback(0, 0, &initial, FeedbackBusSpeed::High).unwrap();
        assert_eq!(stream.tracker.estimate_hz(), Some(48_000.0));
        let first_audio = stream.submit_audio().unwrap();
        let second_audio = stream.submit_audio().unwrap();
        assert_eq!(stream.audio_transfers_in_flight.len(), 2);

        // The feedback clock ages beyond its descriptor-derived stale limit.
        stream.observe_feedback_age(Some(timeout + Duration::from_nanos(1)), timeout);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Quiescing);
        assert!(!stream.submit_audio());

        // A late but otherwise valid sample during quiescing must not seed
        // the next estimator epoch while old audio packets remain in flight.
        let late = high_speed_feedback(48_020.0);
        stream.complete_feedback(0, 0, &late, FeedbackBusSpeed::High).unwrap();
        assert_eq!(stream.tracker.estimate_hz(), Some(48_000.0));

        // Complete the newer transfer first: the older transfer still keeps
        // the ring non-empty and must prevent a new feedback epoch.
        stream.complete_audio_transfer(second_audio);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Quiescing);
        assert_eq!(stream.audio_transfers_in_flight, vec![first_audio]);
        stream.complete_audio_transfer(first_audio);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::AwaitingFreshFeedback);
        assert_eq!(stream.tracker.estimate_hz(), None);

        // Only a fresh post-drain sample starts the new clock epoch.
        let recovered = high_speed_feedback(48_050.0);
        stream.complete_feedback(0, 0, &recovered, FeedbackBusSpeed::High).unwrap();
        assert!((stream.tracker.estimate_hz().unwrap() - 48_050.0).abs() < 1.0);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Repriming);
        assert!(stream.submit_audio().is_some());
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Running);
        assert_eq!(stream.audio_transfers_in_flight.len(), 1);
    }

    #[test]
    fn feedback_disconnect_during_reacquisition_fails_closed() {
        let mut stream = SimulatedFeedbackStream::new();
        let initial = high_speed_feedback(48_000.0);
        stream.complete_feedback(0, 0, &initial, FeedbackBusSpeed::High).unwrap();
        let audio_id = stream.submit_audio().unwrap();

        stream.observe_feedback_age(
            Some(Duration::from_millis(251)),
            Duration::from_millis(250),
        );
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Quiescing);
        assert!(stream.submit_audio().is_none());

        // Simulate libusb reporting a disconnected/cancelled feedback transfer.
        let error = stream.complete_feedback(
            -4, 0, &initial, FeedbackBusSpeed::High,
        ).unwrap_err();
        assert!(error.contains("transfer failed"));
        assert!(stream.terminal_error);
        assert!(stream.submit_audio().is_none());

        // A later packet cannot revive a terminal stream.
        stream.complete_audio_transfer(audio_id);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::AwaitingFreshFeedback);
        assert!(stream.submit_audio().is_none());
        assert!(stream.complete_feedback(0, 0, &initial, FeedbackBusSpeed::High).is_err());
        assert!(stream.tracker.estimate_hz().is_none());
    }

    #[test]
    fn delayed_but_not_stale_feedback_keeps_stream_running() {
        let mut stream = SimulatedFeedbackStream::new();
        let sample = high_speed_feedback(44_100.0);
        stream.complete_feedback(0, 0, &sample, FeedbackBusSpeed::High).unwrap();
        stream.observe_feedback_age(
            Some(Duration::from_millis(249)),
            Duration::from_millis(250),
        );
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Running);
        assert!(stream.submit_audio().is_some());
        assert!((stream.tracker.estimate_hz().unwrap() - 44_100.0).abs() < 1.0);
    }

    #[test]
    fn terminal_feedback_failure_cannot_be_revived_after_audio_ring_drains() {
        let mut stream = SimulatedFeedbackStream::new();
        let sample = high_speed_feedback(48_000.0);
        stream.complete_feedback(0, 0, &sample, FeedbackBusSpeed::High).unwrap();
        let first = stream.submit_audio().unwrap();
        let second = stream.submit_audio().unwrap();

        stream.observe_feedback_age(Some(Duration::from_millis(251)), Duration::from_millis(250));
        stream.complete_audio_transfer(second);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Quiescing);

        let error = stream.complete_feedback(-4, 0, &sample, FeedbackBusSpeed::High).unwrap_err();
        assert!(error.contains("transfer failed"));
        stream.complete_audio_transfer(first);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::AwaitingFreshFeedback);
        assert!(stream.submit_audio().is_none());

        // A valid-looking late sample must not clear the terminal error.
        assert!(stream.complete_feedback(0, 0, &sample, FeedbackBusSpeed::High).is_err());
        assert!(stream.terminal_error);
        assert_eq!(stream.tracker.estimate_hz(), None);
        assert!(stream.submit_audio().is_none());
    }

    #[test]
    fn feedback_recovery_waits_for_every_audio_transfer_in_any_completion_order() {
        let mut stream = SimulatedFeedbackStream::new();
        let sample = high_speed_feedback(48_000.0);
        stream.complete_feedback(0, 0, &sample, FeedbackBusSpeed::High).unwrap();
        let a = stream.submit_audio().unwrap();
        let b = stream.submit_audio().unwrap();
        let c = stream.submit_audio().unwrap();

        stream.observe_feedback_age(Some(Duration::from_millis(251)), Duration::from_millis(250));
        stream.complete_audio_transfer(b);
        stream.complete_audio_transfer(c);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Quiescing);
        assert_eq!(stream.audio_transfers_in_flight, vec![a]);
        assert!(stream.tracker.estimate_hz().is_some());

        stream.complete_audio_transfer(a);
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::AwaitingFreshFeedback);
        assert_eq!(stream.tracker.estimate_hz(), None);
        let recovered = high_speed_feedback(48_010.0);
        stream.complete_feedback(0, 0, &recovered, FeedbackBusSpeed::High).unwrap();
        assert_eq!(stream.lifecycle, FeedbackReacquisitionState::Repriming);
    }

}
