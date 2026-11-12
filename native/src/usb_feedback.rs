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
