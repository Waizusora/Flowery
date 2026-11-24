//! Stateful windowed-sinc sample-rate conversion for interleaved PCM.
//!
//! This module is intentionally separate from PCM packing. Callers should bypass it
//! entirely when the source and DAC sample rates match, preserving native integer PCM.
//! For rate conversion, callers convert decoded samples to f64, feed complete frames,
//! and quantize only after conversion.

const DEFAULT_TAPS: usize = 64;
const DEFAULT_PHASES: usize = 1024;

#[derive(Debug, Clone)]
pub struct SincResampler {
    input_rate: u32,
    output_rate: u32,
    channels: usize,
    taps: usize,
    phases: usize,
    cutoff: f64,
    phase_table: Vec<Vec<f64>>,
    input: Vec<f64>,
    base_frame: i64,
    total_input_frames: u64,
    next_position: f64,
    finished: bool,
}

impl SincResampler {
    pub fn new(input_rate: u32, output_rate: u32, channels: usize) -> Result<Self, &'static str> {
        Self::with_quality(input_rate, output_rate, channels, DEFAULT_TAPS, DEFAULT_PHASES)
    }

    pub fn with_quality(
        input_rate: u32,
        output_rate: u32,
        channels: usize,
        taps: usize,
        phases: usize,
    ) -> Result<Self, &'static str> {
        if input_rate == 0 || output_rate == 0 || !(1..=8).contains(&channels) {
            return Err("Invalid resampler rate or channel count");
        }
        if taps < 16 || taps % 2 != 0 || phases < 64 {
            return Err("Windowed-sinc quality requires an even tap count >= 16 and >= 64 phases");
        }
        let cutoff = (f64::from(output_rate) / f64::from(input_rate)).min(1.0) * 0.94;
        let half = (taps / 2) as f64;
        let mut phase_table = Vec::with_capacity(phases + 1);
        for phase in 0..=phases {
            let fraction = phase as f64 / phases as f64;
            let mut coefficients = Vec::with_capacity(taps);
            let mut sum = 0.0;
            for tap in 0..taps {
                let offset = tap as f64 - (half - 1.0) - fraction;
                let normalized = offset / half;
                let window = if normalized.abs() < 1.0 {
                    0.5 + 0.5 * (std::f64::consts::PI * normalized).cos()
                } else {
                    0.0
                };
                let x = offset * cutoff;
                let sinc = if x.abs() < 1.0e-12 {
                    1.0
                } else {
                    (std::f64::consts::PI * x).sin() / (std::f64::consts::PI * x)
                };
                let coefficient = cutoff * sinc * window;
                coefficients.push(coefficient);
                sum += coefficient;
            }
            if sum.abs() < 1.0e-15 {
                return Err("Unable to construct a stable windowed-sinc filter");
            }
            for coefficient in &mut coefficients {
                *coefficient /= sum;
            }
            phase_table.push(coefficients);
        }
        Ok(Self {
            input_rate, output_rate, channels, taps, phases, cutoff, phase_table,
            input: Vec::new(), base_frame: 0, total_input_frames: 0,
            next_position: 0.0, finished: false,
        })
    }

    pub fn input_rate(&self) -> u32 { self.input_rate }
    pub fn output_rate(&self) -> u32 { self.output_rate }
    pub fn channels(&self) -> usize { self.channels }
    pub fn filter_taps(&self) -> usize { self.taps }
    pub fn cutoff_ratio(&self) -> f64 { self.cutoff }

    /// Append interleaved input frames and return output frames whose right-hand
    /// filter support is already available. Incomplete interleaved frames are rejected.
    pub fn process(&mut self, interleaved: &[f64]) -> Result<Vec<f64>, &'static str> {
        if self.finished {
            return Err("Cannot append samples after resampler EOF");
        }
        if interleaved.len() % self.channels != 0 {
            return Err("Resampler input is not aligned to complete channel frames");
        }
        if interleaved.iter().any(|sample| !sample.is_finite()) {
            return Err("Resampler input contains a non-finite sample");
        }
        self.input.extend_from_slice(interleaved);
        self.total_input_frames = self.total_input_frames
            .checked_add((interleaved.len() / self.channels) as u64)
            .ok_or("Resampler input frame counter overflow")?;
        Ok(self.produce(false))
    }

    /// Mark EOF and emit the remaining output timeline, zero-padding only the
    /// filter's right-hand support. The returned samples must be drained once.
    pub fn finish(&mut self) -> Result<Vec<f64>, &'static str> {
        if self.finished {
            return Ok(Vec::new());
        }
        self.finished = true;
        Ok(self.produce(true))
    }

    fn produce(&mut self, flush: bool) -> Vec<f64> {
        let mut output = Vec::new();
        let half = (self.taps / 2) as i64;
        let step = f64::from(self.input_rate) / f64::from(self.output_rate);
        let final_position = self.total_input_frames as f64;
        loop {
            if flush {
                if self.next_position >= final_position { break; }
            } else if self.next_position.floor() as u64 + half as u64 >= self.total_input_frames {
                break;
            }
            let center = self.next_position.floor() as i64;
            let fraction = self.next_position - center as f64;
            let phase_position = fraction * self.phases as f64;
            let phase_index = phase_position.floor() as usize;
            let phase_mix = phase_position - phase_index as f64;
            let first = center - half + 1;
            for channel in 0..self.channels {
                let mut value = 0.0;
                for tap in 0..self.taps {
                    let frame = first + tap as i64;
                    let sample = if frame < 0 || frame >= self.total_input_frames as i64 {
                        0.0
                    } else {
                        let local = frame - self.base_frame;
                        if local < 0 { 0.0 } else {
                            let index = local as usize * self.channels + channel;
                            self.input.get(index).copied().unwrap_or(0.0)
                        }
                    };
                    let a = self.phase_table[phase_index][tap];
                    let b = self.phase_table[phase_index + 1][tap];
                    value += sample * (a + (b - a) * phase_mix);
                }
                output.push(value);
            }
            self.next_position += step;
        }
        self.discard_consumed_history();
        output
    }

    fn discard_consumed_history(&mut self) {
        let keep_from = (self.next_position.floor() as i64 - (self.taps as i64 / 2) - 1).max(0);
        let discard_frames = (keep_from - self.base_frame).max(0) as usize;
        let discard_samples = discard_frames.saturating_mul(self.channels).min(self.input.len());
        if discard_samples > 0 {
            self.input.drain(..discard_samples);
            self.base_frame += discard_frames as i64;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rms(values: &[f64]) -> f64 {
        (values.iter().map(|x| x * x).sum::<f64>() / values.len().max(1) as f64).sqrt()
    }

    #[test]
    fn preserves_dc_after_filter_startup() {
        let mut resampler = SincResampler::new(96_000, 48_000, 1).unwrap();
        let mut output = resampler.process(&vec![0.25; 96_000]).unwrap();
        output.extend(resampler.finish().unwrap());
        assert!(output.len() >= 47_900);
        assert!((rms(&output[128..output.len() - 128]) - 0.25).abs() < 1.0e-3);
    }

    #[test]
    fn stereo_channels_do_not_leak_into_each_other() {
        let mut input = Vec::new();
        for _ in 0..4096 { input.extend_from_slice(&[0.5, 0.0]); }
        let mut resampler = SincResampler::new(96_000, 48_000, 2).unwrap();
        let mut output = resampler.process(&input).unwrap();
        output.extend(resampler.finish().unwrap());
        let right: Vec<f64> = output.iter().skip(1).step_by(2).copied().collect();
        assert!(rms(&right) < 1.0e-12);
    }

    #[test]
    fn chunk_boundaries_do_not_change_the_output() {
        let input: Vec<f64> = (0..8192).map(|i| (i as f64 * 0.013).sin()).collect();
        let mut whole = SincResampler::new(44_100, 48_000, 1).unwrap();
        let mut expected = whole.process(&input).unwrap();
        expected.extend(whole.finish().unwrap());

        let mut chunked = SincResampler::new(44_100, 48_000, 1).unwrap();
        let mut actual = Vec::new();
        for chunk in input.chunks(274) {
            actual.extend(chunked.process(chunk).unwrap());
        }
        actual.extend(chunked.finish().unwrap());
        assert_eq!(expected.len(), actual.len());
        assert!(expected.iter().zip(actual).all(|(a, b)| (a - b).abs() < 1.0e-12));
    }

    #[test]
    fn rejects_incomplete_frames_and_non_finite_samples() {
        let mut resampler = SincResampler::new(48_000, 44_100, 2).unwrap();
        assert!(resampler.process(&[0.0]).is_err());
        assert!(resampler.process(&[f64::NAN, 0.0]).is_err());
    }

    #[test]
    fn eof_is_idempotent_and_input_after_eof_is_rejected() {
        let mut resampler = SincResampler::new(48_000, 44_100, 1).unwrap();
        let _ = resampler.process(&[0.0; 512]).unwrap();
        let tail = resampler.finish().unwrap();
        assert!(!tail.is_empty());
        assert!(resampler.finish().unwrap().is_empty());
        assert!(resampler.process(&[0.0]).is_err());
    }
}
