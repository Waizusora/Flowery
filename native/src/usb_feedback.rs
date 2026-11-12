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

/// A bounded low-pass estimate used by the future packet scheduler. The caller
/// must validate the estimate against the selected format and endpoint capacity.
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

    pub fn estimate_hz(&self) -> Option<f64> { self.estimate_hz }
}

#[cfg(test)]
mod tests {
    use super::*;


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
    fn smooths_feedback_without_unbounded_values() {
        let mut tracker = FeedbackRateTracker::new(0.25).unwrap();
        assert_eq!(tracker.update(48_000.0).unwrap(), 48_000.0);
        assert_eq!(tracker.update(48_400.0).unwrap(), 48_100.0);
        assert!(tracker.update(f64::NAN).is_err());
    }
}
