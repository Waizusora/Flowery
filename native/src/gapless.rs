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


/// Queue-boundary lifecycle. A decoder reaching EOF is not the same as the
/// playback queue reaching EOF: the output owner must remain alive while the
/// next track is prepared.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BoundaryPhase {
    Playing { generation: u64 },
    AwaitingNext { generation: u64 },
    Reconfiguring { generation: u64, kind: TransitionKind },
    Finished { generation: u64 },
    Cancelled { generation: u64 },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BoundaryEvent {
    TrackEof { generation: u64 },
    NextTrackReady {
        generation: u64,
        current: PcmSignature,
        next: PcmSignature,
    },
    OutputReady { generation: u64 },
    QueueEof { generation: u64 },
    Cancel { generation: u64 },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BoundaryAction {
    /// Keep the existing output open and attach the next decoder producer.
    AttachNextTrack,
    /// The next track requires a route/format transition before it can play.
    ReconfigureOutput(TransitionKind),
    /// The queue, not merely the current decoder, has ended.
    FinishQueue,
    /// A prefetched track has an invalid decoded PCM contract.
    InvalidNextTrack,
    /// Event belongs to an old playback generation or is invalid for this phase.
    Ignore,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct GaplessBoundary {
    phase: BoundaryPhase,
}

impl GaplessBoundary {
    pub(crate) fn new(generation: u64) -> Self {
        Self { phase: BoundaryPhase::Playing { generation } }
    }

    pub(crate) fn phase(&self) -> BoundaryPhase {
        self.phase
    }

    pub(crate) fn apply(&mut self, event: BoundaryEvent) -> BoundaryAction {
        let event_generation = match event {
            BoundaryEvent::TrackEof { generation }
            | BoundaryEvent::OutputReady { generation }
            | BoundaryEvent::QueueEof { generation }
            | BoundaryEvent::Cancel { generation }
            | BoundaryEvent::NextTrackReady { generation, .. } => generation,
        };
        let current_generation = match self.phase {
            BoundaryPhase::Playing { generation }
            | BoundaryPhase::AwaitingNext { generation }
            | BoundaryPhase::Reconfiguring { generation, .. }
            | BoundaryPhase::Finished { generation }
            | BoundaryPhase::Cancelled { generation } => generation,
        };
        if event_generation != current_generation {
            return BoundaryAction::Ignore;
        }

        match (self.phase, event) {
            (BoundaryPhase::Playing { generation }, BoundaryEvent::TrackEof { .. }) => {
                self.phase = BoundaryPhase::AwaitingNext { generation };
                BoundaryAction::Ignore
            }
            (
                BoundaryPhase::AwaitingNext { generation },
                BoundaryEvent::NextTrackReady { current, next, .. },
            ) => {
                if current.validate().is_err() || next.validate().is_err() {
                    self.phase = BoundaryPhase::Cancelled { generation };
                    return BoundaryAction::InvalidNextTrack;
                }
                let plan = plan_transition(current, next);
                if plan.sample_continuous {
                    self.phase = BoundaryPhase::Playing { generation };
                    BoundaryAction::AttachNextTrack
                } else {
                    self.phase = BoundaryPhase::Reconfiguring { generation, kind: plan.kind };
                    BoundaryAction::ReconfigureOutput(plan.kind)
                }
            }
            (
                BoundaryPhase::Reconfiguring { generation, .. },
                BoundaryEvent::OutputReady { .. },
            ) => {
                self.phase = BoundaryPhase::Playing { generation };
                BoundaryAction::AttachNextTrack
            }
            (
                BoundaryPhase::Playing { generation } | BoundaryPhase::AwaitingNext { generation } | BoundaryPhase::Reconfiguring { generation, .. },
                BoundaryEvent::QueueEof { .. },
            ) => {
                self.phase = BoundaryPhase::Finished { generation };
                BoundaryAction::FinishQueue
            }
            (
                BoundaryPhase::Playing { generation } | BoundaryPhase::AwaitingNext { generation } | BoundaryPhase::Reconfiguring { generation, .. },
                BoundaryEvent::Cancel { .. },
            ) => {
                self.phase = BoundaryPhase::Cancelled { generation };
                BoundaryAction::Ignore
            }
            _ => BoundaryAction::Ignore,
        }
    }
}

#[cfg(test)]
mod boundary_tests {
    use super::*;

    fn pcm(rate: u32) -> PcmSignature {
        PcmSignature {
            route: OutputRoute::UsbDirect,
            sample_rate_hz: rate,
            channels: 2,
            valid_bits: 24,
            sample_format: 1,
        }
    }

    #[test]
    fn track_eof_keeps_queue_and_output_alive_until_next_track_is_ready() {
        let mut boundary = GaplessBoundary::new(7);
        assert_eq!(
            boundary.apply(BoundaryEvent::TrackEof { generation: 7 }),
            BoundaryAction::Ignore
        );
        assert_eq!(boundary.phase(), BoundaryPhase::AwaitingNext { generation: 7 });
        assert_eq!(
            boundary.apply(BoundaryEvent::NextTrackReady {
                generation: 7,
                current: pcm(96_000),
                next: pcm(96_000),
            }),
            BoundaryAction::AttachNextTrack
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Playing { generation: 7 });
    }

    #[test]
    fn different_usb_rate_requests_clock_reconfiguration_not_false_gapless() {
        let mut boundary = GaplessBoundary::new(3);
        boundary.apply(BoundaryEvent::TrackEof { generation: 3 });
        assert_eq!(
            boundary.apply(BoundaryEvent::NextTrackReady {
                generation: 3,
                current: pcm(44_100),
                next: pcm(96_000),
            }),
            BoundaryAction::ReconfigureOutput(TransitionKind::RenegotiateUsbClock)
        );
    }

    #[test]
    fn output_is_not_marked_playing_until_reconfiguration_completes() {
        let mut boundary = GaplessBoundary::new(18);
        boundary.apply(BoundaryEvent::TrackEof { generation: 18 });
        assert_eq!(
            boundary.apply(BoundaryEvent::NextTrackReady {
                generation: 18,
                current: pcm(44_100),
                next: pcm(96_000),
            }),
            BoundaryAction::ReconfigureOutput(TransitionKind::RenegotiateUsbClock)
        );
        assert_eq!(
            boundary.phase(),
            BoundaryPhase::Reconfiguring {
                generation: 18,
                kind: TransitionKind::RenegotiateUsbClock,
            }
        );
        assert_eq!(
            boundary.apply(BoundaryEvent::OutputReady { generation: 18 }),
            BoundaryAction::AttachNextTrack
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Playing { generation: 18 });
    }

    #[test]
    fn only_queue_eof_finishes_playback() {
        let mut boundary = GaplessBoundary::new(11);
        boundary.apply(BoundaryEvent::TrackEof { generation: 11 });
        assert_eq!(
            boundary.apply(BoundaryEvent::QueueEof { generation: 11 }),
            BoundaryAction::FinishQueue
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Finished { generation: 11 });
    }

    #[test]
    fn queue_eof_can_finish_without_an_intermediate_track_eof_notification() {
        let mut boundary = GaplessBoundary::new(14);
        assert_eq!(
            boundary.apply(BoundaryEvent::QueueEof { generation: 14 }),
            BoundaryAction::FinishQueue
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Finished { generation: 14 });
    }

    #[test]
    fn invalid_prefetched_pcm_signature_cancels_the_boundary() {
        let mut boundary = GaplessBoundary::new(15);
        boundary.apply(BoundaryEvent::TrackEof { generation: 15 });
        let invalid = PcmSignature { sample_rate_hz: 0, ..pcm(48_000) };
        assert_eq!(
            boundary.apply(BoundaryEvent::NextTrackReady {
                generation: 15,
                current: pcm(48_000),
                next: invalid,
            }),
            BoundaryAction::InvalidNextTrack
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Cancelled { generation: 15 });
    }

    #[test]
    fn stale_generation_cannot_advance_or_finish_current_playback() {
        let mut boundary = GaplessBoundary::new(12);
        assert_eq!(
            boundary.apply(BoundaryEvent::TrackEof { generation: 11 }),
            BoundaryAction::Ignore
        );
        assert_eq!(
            boundary.apply(BoundaryEvent::QueueEof { generation: 11 }),
            BoundaryAction::Ignore
        );
        assert_eq!(boundary.phase(), BoundaryPhase::Playing { generation: 12 });
    }

    #[test]
    fn cancellation_invalidates_later_boundary_events() {
        let mut boundary = GaplessBoundary::new(5);
        boundary.apply(BoundaryEvent::Cancel { generation: 5 });
        assert_eq!(boundary.phase(), BoundaryPhase::Cancelled { generation: 5 });
        assert_eq!(
            boundary.apply(BoundaryEvent::TrackEof { generation: 5 }),
            BoundaryAction::Ignore
        );
    }
}


/// Persistent output lifetime is independent of decoder lifetime.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OutputLifetimePhase {
    Closed,
    Open { generation: u64, signature: PcmSignature, producer_epoch: u64, producer_attached: bool },
    Reconfiguring { generation: u64, next: PcmSignature },
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OutputLifetimeEvent {
    Open { generation: u64, signature: PcmSignature },
    TrackEof { generation: u64 },
    AttachProducer { generation: u64, signature: PcmSignature },
    ReconfigureComplete { generation: u64 },
    QueueEof { generation: u64 },
    Cancel { generation: u64 },
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OutputLifetimeAction {
    OpenOutput, KeepOutputOpen, AttachProducer { producer_epoch: u64 },
    ReopenOutput, CloseOutput, RejectInvalidPcm, Ignore,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct OutputLifetime { phase: OutputLifetimePhase }

impl OutputLifetime {
    pub(crate) fn new() -> Self { Self { phase: OutputLifetimePhase::Closed } }
    pub(crate) fn phase(&self) -> OutputLifetimePhase { self.phase }

    pub(crate) fn apply(&mut self, event: OutputLifetimeEvent) -> OutputLifetimeAction {
        let generation = match event {
            OutputLifetimeEvent::Open { generation, .. }
            | OutputLifetimeEvent::TrackEof { generation }
            | OutputLifetimeEvent::AttachProducer { generation, .. }
            | OutputLifetimeEvent::ReconfigureComplete { generation }
            | OutputLifetimeEvent::QueueEof { generation }
            | OutputLifetimeEvent::Cancel { generation } => generation,
        };
        let active = match self.phase {
            OutputLifetimePhase::Closed => None,
            OutputLifetimePhase::Open { generation, .. }
            | OutputLifetimePhase::Reconfiguring { generation, .. } => Some(generation),
        };
        if active.is_some_and(|value| value != generation) { return OutputLifetimeAction::Ignore; }

        match (self.phase, event) {
            (OutputLifetimePhase::Closed, OutputLifetimeEvent::Open { generation, signature }) => {
                if signature.validate().is_err() { return OutputLifetimeAction::RejectInvalidPcm; }
                self.phase = OutputLifetimePhase::Open { generation, signature, producer_epoch: 1, producer_attached: true };
                OutputLifetimeAction::OpenOutput
            }
            (OutputLifetimePhase::Open { generation, signature, producer_epoch, producer_attached: true },
             OutputLifetimeEvent::TrackEof { .. }) => {
                self.phase = OutputLifetimePhase::Open { generation, signature, producer_epoch, producer_attached: false };
                OutputLifetimeAction::KeepOutputOpen
            }
            (OutputLifetimePhase::Open { generation, signature, producer_epoch, producer_attached: false },
             OutputLifetimeEvent::AttachProducer { signature: next, .. }) => {
                if next.validate().is_err() { return OutputLifetimeAction::RejectInvalidPcm; }
                if signature == next {
                    let epoch = producer_epoch.saturating_add(1);
                    self.phase = OutputLifetimePhase::Open { generation, signature, producer_epoch: epoch, producer_attached: true };
                    OutputLifetimeAction::AttachProducer { producer_epoch: epoch }
                } else {
                    self.phase = OutputLifetimePhase::Reconfiguring { generation, next };
                    OutputLifetimeAction::ReopenOutput
                }
            }
            (OutputLifetimePhase::Reconfiguring { generation, next },
             OutputLifetimeEvent::ReconfigureComplete { .. }) => {
                if next.validate().is_err() { self.phase = OutputLifetimePhase::Closed; return OutputLifetimeAction::RejectInvalidPcm; }
                self.phase = OutputLifetimePhase::Open { generation, signature: next, producer_epoch: 1, producer_attached: true };
                OutputLifetimeAction::OpenOutput
            }
            (OutputLifetimePhase::Open { .. } | OutputLifetimePhase::Reconfiguring { .. },
             OutputLifetimeEvent::QueueEof { .. } | OutputLifetimeEvent::Cancel { .. }) => {
                self.phase = OutputLifetimePhase::Closed;
                OutputLifetimeAction::CloseOutput
            }
            _ => OutputLifetimeAction::Ignore,
        }
    }
}

#[cfg(test)]
mod output_lifetime_tests {
    use super::*;
    fn pcm(rate: u32) -> PcmSignature {
        PcmSignature { route: OutputRoute::UsbDirect, sample_rate_hz: rate, channels: 2, valid_bits: 24, sample_format: 1 }
    }
    #[test]
    fn track_eof_keeps_output_open_and_detaches_producer() {
        let mut o = OutputLifetime::new();
        assert_eq!(o.apply(OutputLifetimeEvent::Open { generation: 1, signature: pcm(96_000) }), OutputLifetimeAction::OpenOutput);
        assert_eq!(o.apply(OutputLifetimeEvent::TrackEof { generation: 1 }), OutputLifetimeAction::KeepOutputOpen);
        assert!(matches!(o.phase(), OutputLifetimePhase::Open { producer_attached: false, .. }));
    }
    #[test]
    fn same_pcm_attaches_new_producer_without_reopening() {
        let mut o = OutputLifetime::new();
        o.apply(OutputLifetimeEvent::Open { generation: 2, signature: pcm(96_000) });
        o.apply(OutputLifetimeEvent::TrackEof { generation: 2 });
        assert_eq!(o.apply(OutputLifetimeEvent::AttachProducer { generation: 2, signature: pcm(96_000) }), OutputLifetimeAction::AttachProducer { producer_epoch: 2 });
    }
    #[test]
    fn changed_usb_rate_requires_reconfiguration() {
        let mut o = OutputLifetime::new();
        o.apply(OutputLifetimeEvent::Open { generation: 3, signature: pcm(44_100) });
        o.apply(OutputLifetimeEvent::TrackEof { generation: 3 });
        assert_eq!(o.apply(OutputLifetimeEvent::AttachProducer { generation: 3, signature: pcm(96_000) }), OutputLifetimeAction::ReopenOutput);
        assert_eq!(o.apply(OutputLifetimeEvent::ReconfigureComplete { generation: 3 }), OutputLifetimeAction::OpenOutput);
    }
    #[test]
    fn only_queue_eof_or_cancel_closes_output() {
        let mut o = OutputLifetime::new();
        o.apply(OutputLifetimeEvent::Open { generation: 4, signature: pcm(48_000) });
        o.apply(OutputLifetimeEvent::TrackEof { generation: 4 });
        assert_eq!(o.apply(OutputLifetimeEvent::QueueEof { generation: 4 }), OutputLifetimeAction::CloseOutput);
        assert_eq!(o.phase(), OutputLifetimePhase::Closed);
    }
    #[test]
    fn invalid_next_signature_does_not_replace_detached_producer() {
        let mut o = OutputLifetime::new();
        o.apply(OutputLifetimeEvent::Open { generation: 7, signature: pcm(48_000) });
        o.apply(OutputLifetimeEvent::TrackEof { generation: 7 });
        let invalid = PcmSignature { sample_rate_hz: 0, ..pcm(48_000) };
        assert_eq!(
            o.apply(OutputLifetimeEvent::AttachProducer { generation: 7, signature: invalid }),
            OutputLifetimeAction::RejectInvalidPcm
        );
        assert!(matches!(
            o.phase(),
            OutputLifetimePhase::Open { producer_attached: false, producer_epoch: 1, .. }
        ));
    }

    #[test]
    fn stale_generation_cannot_attach_or_close_output() {
        let mut o = OutputLifetime::new();
        o.apply(OutputLifetimeEvent::Open { generation: 5, signature: pcm(48_000) });
        o.apply(OutputLifetimeEvent::TrackEof { generation: 5 });
        assert_eq!(o.apply(OutputLifetimeEvent::AttachProducer { generation: 4, signature: pcm(48_000) }), OutputLifetimeAction::Ignore);
        assert_eq!(o.apply(OutputLifetimeEvent::QueueEof { generation: 4 }), OutputLifetimeAction::Ignore);
    }
}
