//! Gapless transition planning for PCM playback.
//!
//! This module deliberately does not alter PCM samples. It classifies whether
//! an already-open output can be reused for the next decoded track or whether
//! the output must be drained and reconfigured first. The latter cannot be
//! advertised as sample-continuous gapless playback.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OutputRoute {
    AAudio,
    UsbDirect,
    UsbDsp,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PcmSignature {
    pub route: OutputRoute,
    pub sample_rate_hz: u32,
    pub channels: u8,
    pub valid_bits: u8,
    /// Stable decoded representation identifier: 1 = signed PCM, 2 = unsigned PCM8,
    /// 3 = IEEE float32, 4 = IEEE float64. This is not a request to convert.
    pub sample_format: u8,
}

impl PcmSignature {
    pub(crate) fn validate(self) -> Result<Self, &'static str> {
        if self.sample_rate_hz == 0 {
            return Err("sample rate must be non-zero");
        }
        if !(1..=8).contains(&self.channels) {
            return Err("channel count must be between 1 and 8");
        }
        if !(1..=32).contains(&self.valid_bits) {
            return Err("valid bit depth must be between 1 and 32");
        }
        if !matches!(self.sample_format, 1..=4) {
            return Err("unsupported decoded PCM sample format");
        }
        Ok(self)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TransitionKind {
    /// Same route and identical PCM contract: keep the output stream alive.
    ReuseOutput,
    /// Same route, but the output PCM contract changes. Drain before re-opening.
    ReconfigureOutput,
    /// The Android output route changed; the route owner must reopen it.
    ReopenRoute,
    /// USB Direct needs a fresh interface/clock negotiation for the next format.
    RenegotiateUsbClock,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TransitionPlan {
    pub kind: TransitionKind,
    /// True only when the output session can remain open without changing PCM.
    pub sample_continuous: bool,
}

pub(crate) fn plan_transition(current: PcmSignature, next: PcmSignature) -> TransitionPlan {
    let same_route = current.route == next.route;
    let same_pcm = current.sample_rate_hz == next.sample_rate_hz
        && current.channels == next.channels
        && current.valid_bits == next.valid_bits
        && current.sample_format == next.sample_format;

    if same_route && same_pcm {
        return TransitionPlan {
            kind: TransitionKind::ReuseOutput,
            sample_continuous: true,
        };
    }

    let kind = if !same_route {
        TransitionKind::ReopenRoute
    } else if next.route == OutputRoute::UsbDirect {
        TransitionKind::RenegotiateUsbClock
    } else {
        TransitionKind::ReconfigureOutput
    };

    TransitionPlan {
        kind,
        sample_continuous: false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signature(route: OutputRoute, rate: u32, channels: u8, bits: u8, format: u8) -> PcmSignature {
        PcmSignature {
            route,
            sample_rate_hz: rate,
            channels,
            valid_bits: bits,
            sample_format: format,
        }
    }

    #[test]
    fn identical_pcm_reuses_output_without_touching_samples() {
        let pcm = signature(OutputRoute::UsbDirect, 96_000, 2, 24, 1);
        assert_eq!(
            plan_transition(pcm, pcm),
            TransitionPlan {
                kind: TransitionKind::ReuseOutput,
                sample_continuous: true,
            }
        );
    }

    #[test]
    fn usb_direct_rate_change_requires_clock_renegotiation() {
        let current = signature(OutputRoute::UsbDirect, 44_100, 2, 24, 1);
        let next = signature(OutputRoute::UsbDirect, 96_000, 2, 24, 1);
        assert_eq!(
            plan_transition(current, next),
            TransitionPlan {
                kind: TransitionKind::RenegotiateUsbClock,
                sample_continuous: false,
            }
        );
    }

    #[test]
    fn aaudio_format_change_requires_output_reconfiguration() {
        let current = signature(OutputRoute::AAudio, 44_100, 2, 16, 1);
        let next = signature(OutputRoute::AAudio, 48_000, 2, 24, 1);
        assert_eq!(
            plan_transition(current, next).kind,
            TransitionKind::ReconfigureOutput
        );
    }

    #[test]
    fn route_change_requires_route_reopen() {
        let current = signature(OutputRoute::AAudio, 48_000, 2, 16, 1);
        let next = signature(OutputRoute::UsbDirect, 48_000, 2, 16, 1);
        assert_eq!(
            plan_transition(current, next).kind,
            TransitionKind::ReopenRoute
        );
    }

    #[test]
    fn signature_rejects_invalid_pcm_contracts() {
        assert_eq!(
            signature(OutputRoute::AAudio, 0, 2, 16, 1).validate(),
            Err("sample rate must be non-zero")
        );
        assert_eq!(
            signature(OutputRoute::AAudio, 48_000, 9, 16, 1).validate(),
            Err("channel count must be between 1 and 8")
        );
        assert_eq!(
            signature(OutputRoute::AAudio, 48_000, 2, 16, 5).validate(),
            Err("unsupported decoded PCM sample format")
        );
    }
}
