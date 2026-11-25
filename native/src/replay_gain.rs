//! ReplayGain metadata parsing and gain planning.
//!
//! This module is deliberately independent of the decoder and output backends.
//! It provides deterministic parsing/math that can be tested before wiring gain
//! application into the AAudio PCM path. USB Direct must remain bit-perfect.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReplayGainMode {
    Off,
    Track,
    Album,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ReplayGainMetadata {
    pub track_gain_db: Option<f32>,
    pub track_peak: Option<f32>,
    pub album_gain_db: Option<f32>,
    pub album_peak: Option<f32>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ReplayGainSettings {
    pub mode: ReplayGainMode,
    pub track_preamp_db: f32,
    pub album_preamp_db: f32,
    pub prevent_clipping: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ReplayGainPlan {
    pub gain_db: f32,
    pub linear_gain: f32,
    pub peak_limit_applied: bool,
}

impl Default for ReplayGainMetadata {
    fn default() -> Self {
        Self {
            track_gain_db: None,
            track_peak: None,
            album_gain_db: None,
            album_peak: None,
        }
    }
}

impl Default for ReplayGainSettings {
    fn default() -> Self {
        Self {
            mode: ReplayGainMode::Off,
            track_preamp_db: 0.0,
            album_preamp_db: 0.0,
            prevent_clipping: true,
        }
    }
}

fn parse_db(value: &str) -> Option<f32> {
    let normalized = value.trim().trim_end_matches("dB").trim();
    let parsed = normalized.parse::<f32>().ok()?;
    (parsed.is_finite() && (-60.0..=24.0).contains(&parsed)).then_some(parsed)
}

fn parse_peak(value: &str) -> Option<f32> {
    let parsed = value.trim().parse::<f32>().ok()?;
    (parsed.is_finite() && parsed > 0.0 && parsed <= 16.0).then_some(parsed)
}

impl ReplayGainMetadata {
    /// Parse the canonical ReplayGain text values (for example ID3 TXXX,
    /// Vorbis comments, or MP4 freeform atoms) after container-specific
    /// metadata extraction has normalized their keys.
    pub fn from_values(
        track_gain: Option<&str>,
        track_peak: Option<&str>,
        album_gain: Option<&str>,
        album_peak: Option<&str>,
    ) -> Self {
        Self {
            track_gain_db: track_gain.and_then(parse_db),
            track_peak: track_peak.and_then(parse_peak),
            album_gain_db: album_gain.and_then(parse_db),
            album_peak: album_peak.and_then(parse_peak),
        }
    }

    pub fn plan(&self, settings: ReplayGainSettings) -> ReplayGainPlan {
        if settings.mode == ReplayGainMode::Off {
            return ReplayGainPlan {
                gain_db: 0.0,
                linear_gain: 1.0,
                peak_limit_applied: false,
            };
        }

        let (tag_gain, tag_peak, preamp) = match settings.mode {
            ReplayGainMode::Off => unreachable!(),
            ReplayGainMode::Track => (
                self.track_gain_db,
                self.track_peak,
                settings.track_preamp_db,
            ),
            ReplayGainMode::Album => (
                self.album_gain_db.or(self.track_gain_db),
                self.album_peak.or(self.track_peak),
                settings.album_preamp_db,
            ),
        };

        let mut gain_db = tag_gain.unwrap_or(0.0) + finite_preamp(preamp);
        let mut peak_limit_applied = false;
        if settings.prevent_clipping {
            if let Some(peak) = tag_peak {
                // A peak below 1.0 still permits positive gain; a peak above
                // 1.0 reduces the available headroom accordingly.
                let maximum_gain_db = -20.0 * peak.log10();
                if gain_db > maximum_gain_db {
                    gain_db = maximum_gain_db;
                    peak_limit_applied = true;
                }
            }
        }

        let linear_gain = 10.0_f32.powf(gain_db / 20.0);
        ReplayGainPlan {
            gain_db,
            linear_gain,
            peak_limit_applied,
        }
    }
}

fn finite_preamp(value: f32) -> f32 {
    if value.is_finite() { value.clamp(-24.0, 24.0) } else { 0.0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings(mode: ReplayGainMode) -> ReplayGainSettings {
        ReplayGainSettings {
            mode,
            ..ReplayGainSettings::default()
        }
    }

    #[test]
    fn parses_standard_replaygain_values() {
        let metadata = ReplayGainMetadata::from_values(
            Some("-7.25 dB"),
            Some("0.891251"),
            Some("-6.00 dB"),
            Some("0.95"),
        );
        assert_eq!(metadata.track_gain_db, Some(-7.25));
        assert_eq!(metadata.track_peak, Some(0.891251));
        assert_eq!(metadata.album_gain_db, Some(-6.0));
        assert_eq!(metadata.album_peak, Some(0.95));
    }

    #[test]
    fn rejects_non_finite_and_out_of_range_values() {
        let metadata = ReplayGainMetadata::from_values(
            Some("NaN"),
            Some("inf"),
            Some("99 dB"),
            Some("-0.1"),
        );
        assert_eq!(metadata, ReplayGainMetadata::default());
    }

    #[test]
    fn off_mode_is_unity_gain() {
        let metadata = ReplayGainMetadata::from_values(Some("-8 dB"), Some("0.8"), None, None);
        let plan = metadata.plan(settings(ReplayGainMode::Off));
        assert_eq!(plan.gain_db, 0.0);
        assert_eq!(plan.linear_gain, 1.0);
        assert!(!plan.peak_limit_applied);
    }

    #[test]
    fn track_mode_uses_track_gain_and_preamp() {
        let metadata = ReplayGainMetadata::from_values(Some("-8 dB"), Some("0.5"), None, None);
        let plan = metadata.plan(ReplayGainSettings {
            mode: ReplayGainMode::Track,
            track_preamp_db: 2.0,
            ..ReplayGainSettings::default()
        });
        assert!((plan.gain_db - -6.0).abs() < 0.0001);
        assert!((plan.linear_gain - 10.0_f32.powf(-6.0 / 20.0)).abs() < 0.0001);
    }

    #[test]
    fn album_mode_falls_back_to_track_tags_when_album_tags_are_missing() {
        let metadata = ReplayGainMetadata::from_values(Some("-5 dB"), Some("0.9"), None, None);
        let plan = metadata.plan(settings(ReplayGainMode::Album));
        assert!((plan.gain_db - -5.0).abs() < 0.0001);
    }

    #[test]
    fn peak_prevention_limits_positive_gain() {
        let metadata = ReplayGainMetadata::from_values(Some("6 dB"), Some("1.2"), None, None);
        let plan = metadata.plan(settings(ReplayGainMode::Track));
        let expected = -20.0 * 1.2_f32.log10();
        assert!(plan.peak_limit_applied);
        assert!((plan.gain_db - expected).abs() < 0.0001);
        assert!(plan.linear_gain * 1.2 <= 1.0001);
    }

    #[test]
    fn preamp_is_bounded_and_non_finite_values_are_ignored() {
        let metadata = ReplayGainMetadata::from_values(None, None, None, None);
        let plan = metadata.plan(ReplayGainSettings {
            mode: ReplayGainMode::Track,
            track_preamp_db: f32::INFINITY,
            ..ReplayGainSettings::default()
        });
        assert_eq!(plan.gain_db, 0.0);
    }
}

/// Process-wide playback preference. USB Direct deliberately never reads this setting.
static SETTINGS: std::sync::OnceLock<std::sync::RwLock<ReplayGainSettings>> = std::sync::OnceLock::new();

pub fn settings() -> ReplayGainSettings {
    *SETTINGS.get_or_init(|| std::sync::RwLock::new(ReplayGainSettings::default()))
        .read().unwrap_or_else(|error| error.into_inner())
}

pub fn set_settings(value: ReplayGainSettings) {
    *SETTINGS.get_or_init(|| std::sync::RwLock::new(ReplayGainSettings::default()))
        .write().unwrap_or_else(|error| error.into_inner()) = value;
}
