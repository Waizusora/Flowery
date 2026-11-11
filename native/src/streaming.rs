use crate::write_text_to_java;
use std::collections::VecDeque;
use std::fs::File;
use std::io::ErrorKind;
use std::time::Duration;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
#[cfg(target_os = "android")]
use std::thread::{self, JoinHandle};
#[cfg(target_os = "android")]
use crate::playback_session::session_ref;
use std::os::fd::FromRawFd;

use audioadapter_buffers::direct::InterleavedSlice;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use rubato::{Fft, FixedSync, Resampler};
use symphonia::core::audio::{SampleBuffer, Signal};
use symphonia::core::codecs::{Decoder, DecoderOptions};
use symphonia::core::errors::Error;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

const FREQUENCIES: [f32; 31] = [20.0,25.0,31.5,40.0,50.0,63.0,80.0,100.0,125.0,160.0,200.0,250.0,315.0,400.0,500.0,630.0,800.0,1000.0,1250.0,1600.0,2000.0,2500.0,3150.0,4000.0,5000.0,6300.0,8000.0,10000.0,12500.0,16000.0,20000.0];

// ISO one-third-octave bandwidth: Q = 1 / (2^(1/6) - 2^(-1/6)).
const EQ_BAND_Q: f32 = 4.318_473;

#[derive(Clone, Copy, Default)]
struct BiquadState { x1:f32, x2:f32, y1:f32, y2:f32 }

#[derive(Clone, Copy, Default)]
struct BiquadCoefficients { b0:f32, b1:f32, b2:f32, a1:f32, a2:f32 }

fn is_lossless_codec(codec: symphonia::core::codecs::CodecType) -> bool {
    use symphonia::core::codecs::*;
    matches!(codec,
        CODEC_TYPE_PCM_S32LE | CODEC_TYPE_PCM_S32LE_PLANAR | CODEC_TYPE_PCM_S32BE | CODEC_TYPE_PCM_S32BE_PLANAR |
        CODEC_TYPE_PCM_S24LE | CODEC_TYPE_PCM_S24LE_PLANAR | CODEC_TYPE_PCM_S24BE | CODEC_TYPE_PCM_S24BE_PLANAR |
        CODEC_TYPE_PCM_S16LE | CODEC_TYPE_PCM_S16LE_PLANAR | CODEC_TYPE_PCM_S16BE | CODEC_TYPE_PCM_S16BE_PLANAR |
        CODEC_TYPE_PCM_S8 | CODEC_TYPE_PCM_S8_PLANAR |
        CODEC_TYPE_PCM_U32LE | CODEC_TYPE_PCM_U32LE_PLANAR | CODEC_TYPE_PCM_U32BE | CODEC_TYPE_PCM_U32BE_PLANAR |
        CODEC_TYPE_PCM_U24LE | CODEC_TYPE_PCM_U24LE_PLANAR | CODEC_TYPE_PCM_U24BE | CODEC_TYPE_PCM_U24BE_PLANAR |
        CODEC_TYPE_PCM_U16LE | CODEC_TYPE_PCM_U16LE_PLANAR | CODEC_TYPE_PCM_U16BE | CODEC_TYPE_PCM_U16BE_PLANAR |
        CODEC_TYPE_PCM_U8 | CODEC_TYPE_PCM_U8_PLANAR |
        CODEC_TYPE_FLAC | CODEC_TYPE_WAVPACK | CODEC_TYPE_MONKEYS_AUDIO | CODEC_TYPE_ALAC | CODEC_TYPE_TTA
    )
}
fn alac_channel_count(extra_data: Option<&[u8]>) -> Option<usize> {
    let cookie = extra_data?;
    // Symphonia's ALAC decoder accepts the 24-byte mandatory configuration
    // or the 48-byte configuration with optional channel-layout information.
    if cookie.len() != 24 && cookie.len() != 48 {
        return None;
    }
    let channels = *cookie.get(9)? as usize;
    (1..=8).contains(&channels).then_some(channels)
}

fn milliseconds_to_frames(milliseconds: u64, sample_rate: usize) -> u64 {
    ((milliseconds as u128 * sample_rate as u128) / 1000)
        .min(u64::MAX as u128) as u64
}

/// Converts a container timestamp to milliseconds without floating-point rounding.
fn timestamp_to_milliseconds(
    timestamp: u64,
    time_base: Option<symphonia::core::units::TimeBase>,
    fallback_ms: u64,
) -> u64 {
    let Some(time_base) = time_base else {
        return fallback_ms;
    };
    if time_base.denom == 0 {
        return fallback_ms;
    }
    (u128::from(timestamp)
        .saturating_mul(u128::from(time_base.numer))
        .saturating_mul(1_000)
        / u128::from(time_base.denom))
        .min(u128::from(u64::MAX)) as u64
}

/// Converts an absolute seek timestamp into the output-frame window of a segment.
fn aaudio_seek_frame_window(
    actual_ms: u64,
    segment_start_ms: u64,
    segment_end_ms: Option<u64>,
    output_rate: usize,
) -> (u64, Option<u64>) {
    let relative_target_ms = actual_ms.saturating_sub(segment_start_ms);
    let target_frame = milliseconds_to_frames(relative_target_ms, output_rate);
    let remaining_frames = segment_end_ms.map(|end_ms| {
        let segment_end_frame = milliseconds_to_frames(
            end_ms.saturating_sub(segment_start_ms),
            output_rate,
        );
        segment_end_frame.saturating_sub(target_frame)
    });
    (target_frame, remaining_frames)
}

fn presentation_frame_count(
    decoded_frames: Option<u64>,
    encoder_delay: Option<u32>,
    encoder_padding: Option<u32>,
) -> Option<u64> {
    decoded_frames.map(|frames| {
        frames
            .saturating_sub(u64::from(encoder_delay.unwrap_or(0)))
            .saturating_sub(u64::from(encoder_padding.unwrap_or(0)))
    })
}

fn presentation_duration_ms(
    decoded_frames: Option<u64>,
    encoder_delay: Option<u32>,
    encoder_padding: Option<u32>,
    sample_rate: u32,
) -> u64 {
    if sample_rate == 0 {
        return 0;
    }

    presentation_frame_count(decoded_frames, encoder_delay, encoder_padding)
        .map(|frames| {
            ((frames as u128 * 1000) / u128::from(sample_rate))
                .min(u64::MAX as u128) as u64
        })
        .unwrap_or(0)
}

fn make_coefficients(frequency: f32, gain_db: f32, sample_rate: usize) -> BiquadCoefficients {
    if frequency >= sample_rate as f32 * 0.49 { return BiquadCoefficients { b0:1.0, ..Default::default() }; }
    let a = 10.0_f32.powf(gain_db.clamp(-12.0,12.0) / 40.0);
    let omega = 2.0 * std::f32::consts::PI * frequency / sample_rate as f32;
    let alpha = omega.sin() / (2.0 * EQ_BAND_Q);
    let cosine = omega.cos();
    let a0 = 1.0 + alpha / a;
    BiquadCoefficients {
        b0: (1.0 + alpha * a) / a0,
        b1: (-2.0 * cosine) / a0,
        b2: (1.0 - alpha * a) / a0,
        a1: (-2.0 * cosine) / a0,
        a2: (1.0 - alpha / a) / a0,
    }
}

fn apply_eq_samples(
    samples: &mut [f32],
    channels: usize,
    coefficients: &[BiquadCoefficients; 31],
    state: &mut Vec<[BiquadState; 31]>,
) {
    for (index, sample) in samples.iter_mut().enumerate() {
        let channel = index % channels;
        let mut value = *sample;
        for band in 0..31 {
            let c = coefficients[band];
            let z = &mut state[channel][band];
            let filtered = c.b0 * value + c.b1 * z.x1 + c.b2 * z.x2
                - c.a1 * z.y1 - c.a2 * z.y2;
            z.x2 = z.x1;
            z.x1 = value;
            z.y2 = z.y1;
            z.y1 = filtered;
            value = filtered;
        }
        *sample = value;
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum StreamSourceMode {
    Unselected,
    DecodedPcm,
    EncodedPackets,
}

pub(super) struct StreamDecoder {
    format: Box<dyn FormatReader>,
    decoder: Option<Box<dyn Decoder>>,
    track_id: u32,
    source_rate: usize,
    source_bits: u8,
    lossless_source: bool,
    ieee_float_source: bool,
    codec_type: symphonia::core::codecs::CodecType,
    channels: usize,
    output_rate: usize,
    resampler: Option<Fft<f32>>,
    input_pending: Vec<f32>,
    output_pending: VecDeque<f32>,
    eof: bool,
    gains: [f32;31],
    coefficients: [BiquadCoefficients;31],
    eq_state: Vec<[BiquadState;31]>,
    produced_frames: u64,
    source_frames_decoded: u64,
    duration_ms: u64,
    resampler_delay_frames: usize,
    time_base: Option<symphonia::core::units::TimeBase>,
    discard_source_frames: u64,
    discard_output_frames: u64,
    consecutive_decode_errors: u32,
    usb_last_gain: f32,
    source_mode: StreamSourceMode,
    native_pcm_pending: VecDeque<u8>,
    native_pcm_decided: bool,
    native_pcm_active: bool,
}

fn gapless_format_options() -> FormatOptions {
    FormatOptions {
        enable_gapless: true,
        ..FormatOptions::default()
    }
}

impl StreamDecoder {
    fn new(fd: i32, extension: &str) -> Result<Self,String> {
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 { return Err("Unable to duplicate the selected document descriptor".into()); }
        let file = unsafe { File::from_raw_fd(duplicate) };
        let source = MediaSourceStream::new(Box::new(file), Default::default());
        let mut hint = Hint::new();
        if !extension.is_empty() { hint.with_extension(extension.trim_start_matches('.')); }
        let probed = symphonia::default::get_probe().format(&hint, source, &gapless_format_options(), &MetadataOptions::default())
            .map_err(|e| format!("Unable to open audio stream: {e}"))?;
        let format = probed.format;
        let track = format.default_track().ok_or("No default audio track found")?;
        let track_id = track.id;
        let params = track.codec_params.clone();
        let time_base = params.time_base;
        let source_rate = params.sample_rate.ok_or("Audio sample rate is unavailable")? as usize;
        let source_bits = if params.codec == symphonia::core::codecs::CODEC_TYPE_PCM_F32LE {
            32
        } else {
            params.bits_per_coded_sample.or(params.bits_per_sample).unwrap_or(0).min(u8::MAX as u32) as u8
        };
        let lossless_source = is_lossless_codec(params.codec);
        let ieee_float_source = params.codec == symphonia::core::codecs::CODEC_TYPE_PCM_F32LE;
        // CodecParameters::n_frames includes the encoded stream extent. Delay and
        // padding identify decoded frames that must not contribute to presentation time.
        let duration_ms = presentation_duration_ms(
            params.n_frames,
            params.delay,
            params.padding,
            source_rate as u32,
        );
        // Some MP4/M4A ALAC tracks expose neither a channel bitmask nor a
        // channel layout in Symphonia's track parameters. The mandatory ALAC
        // magic-cookie configuration still declares the channel count.
        let channels = params.channels.map(|channels| channels.count())
            .or_else(|| params.channel_layout.map(|layout| layout.into_channels().count()))
            .or_else(|| {
                (params.codec == symphonia::core::codecs::CODEC_TYPE_ALAC)
                    .then(|| alac_channel_count(params.extra_data.as_deref()))
                    .flatten()
            })
            .ok_or("Audio channel layout is unavailable in track metadata and codec configuration")?;
        if channels == 0 || channels > 8 { return Err(format!("Audio stream channel count is outside the supported 1–8 channel envelope: {channels}")); }
        let decoder = match symphonia::default::get_codecs().make(&params, &DecoderOptions::default()) {
            Ok(decoder) => Some(decoder),
            Err(_error) if params.codec == symphonia::core::codecs::CODEC_TYPE_EAC3 => None,
            Err(error) => return Err(format!("Unsupported audio codec: {error}")),
        };
        Ok(Self {
            format, decoder, track_id, source_rate, source_bits, lossless_source, ieee_float_source, codec_type: params.codec, channels, output_rate: source_rate,
            resampler: None, input_pending: Vec::new(), output_pending: VecDeque::new(),
            eof: false, gains: [0.0;31],
            coefficients: [BiquadCoefficients { b0:1.0, ..BiquadCoefficients::default() };31],
            eq_state: vec![[BiquadState::default();31]; channels], produced_frames: 0, source_frames_decoded: 0, duration_ms, resampler_delay_frames: 0, time_base, discard_source_frames: 0, discard_output_frames: 0, consecutive_decode_errors: 0, usb_last_gain: -1.0, source_mode: StreamSourceMode::Unselected,
            native_pcm_pending: VecDeque::new(), native_pcm_decided: false, native_pcm_active: false,
        })
    }

    fn configure(&mut self, output_rate: usize, gains: &[f32]) -> Result<(),String> {
        if output_rate == 0 || gains.len() != 31 {
            return Err("Invalid streaming DSP configuration".into());
        }
        self.output_rate = output_rate;
        self.gains.copy_from_slice(gains);
        self.coefficients = std::array::from_fn(|i| make_coefficients(FREQUENCIES[i], self.gains[i], output_rate));
        self.eq_state = vec![[BiquadState::default();31]; self.channels];
        self.resampler = if output_rate != self.source_rate {
            Some(Fft::<f32>::new(self.source_rate, output_rate, 1024, self.channels, FixedSync::Both)
                .map_err(|e| format!("Rubato initialization failed: {e}"))?)
        } else { None };
        self.resampler_delay_frames = self.resampler.as_ref().map(|r| r.output_delay()).unwrap_or(0);
        Ok(())
    }

    pub(super) fn seek_to(&mut self, position_ms: u64) -> Result<u64,String> {
        let requested_ms = if self.duration_ms > 0 { position_ms.min(self.duration_ms) } else { position_ms };
        let seeked = self.format.seek(
            SeekMode::Accurate,
            SeekTo::Time { time: Duration::from_millis(requested_ms).into(), track_id: Some(self.track_id) }
        ).map_err(|e| format!("Container seek failed: {e}"))?;
        if let Some(decoder) = self.decoder.as_mut() { decoder.reset(); }
        self.input_pending.clear();
        self.output_pending.clear();
        self.native_pcm_pending.clear();
        self.native_pcm_decided = false;
        self.native_pcm_active = false;
        self.eof = false;
        self.consecutive_decode_errors = 0;
        let actual_ms = timestamp_to_milliseconds(
            seeked.actual_ts,
            self.time_base,
            requested_ms,
        );
        let actual_source_frames = milliseconds_to_frames(actual_ms, self.source_rate);
        let requested_source_frames = milliseconds_to_frames(requested_ms, self.source_rate);
        let requested_output_frames = milliseconds_to_frames(requested_ms, self.output_rate);
        let actual_output_frames = milliseconds_to_frames(actual_ms, self.output_rate);
        self.source_frames_decoded = actual_source_frames;

        if self.source_mode == StreamSourceMode::EncodedPackets {
            // Encoded seeking is demuxer/frame-boundary based. No PCM DSP or
            // resampler state is configured for this exclusive source mode.
            self.produced_frames = requested_source_frames;
            self.discard_source_frames = 0;
            self.discard_output_frames = 0;
            self.resampler = None;
            self.resampler_delay_frames = 0;
            return Ok(actual_ms);
        }

        let rate = self.output_rate;
        let gains = self.gains;
        self.configure(rate, &gains)?;

        if self.resampler.is_some() {
            // Keep the container seek preroll so Rubato can rebuild its filter history.
            // Discard converted output up to the requested presentation timestamp instead.
            self.produced_frames = actual_output_frames;
            self.discard_source_frames = 0;
            self.discard_output_frames = requested_output_frames.saturating_sub(actual_output_frames);
        } else {
            self.produced_frames = requested_output_frames;
            self.discard_source_frames = requested_source_frames.saturating_sub(actual_source_frames);
            self.discard_output_frames = 0;
        }
        Ok(requested_ms)
    }

    pub(super) fn is_ac3_source(&self) -> bool {
        self.codec_type == symphonia::core::codecs::CODEC_TYPE_EAC3
    }
    pub(super) fn playback_metadata(&self) -> (usize, usize, u8, bool, bool) {
        (
            self.source_rate,
            self.channels,
            self.source_bits,
            self.lossless_source,
            self.is_ac3_source(),
        )
    }
    pub(super) fn is_ieee_float32_source(&self) -> bool { self.ieee_float_source }

    pub(super) fn encoded_transport_bytes_per_second(&self, framing_kind: i32) -> Result<u64, String> {
        match framing_kind {
            1 if self.codec_type == symphonia::core::codecs::CODEC_TYPE_EAC3 => {
                Err("AC-3 Type-II transport uses the validated descriptor maximum bitrate".into())
            }
            2 if self.codec_type == symphonia::core::codecs::CODEC_TYPE_EAC3 => {
                (self.source_rate as u64).checked_mul(4)
                    .ok_or_else(|| "IEC 61937 carrier rate overflow".into())
            }
            _ => Err("No encoded transport rate is defined for this source codec and framing".into()),
        }
    }

    pub(super) fn begin_encoded_stream(&mut self) -> Result<(), String> {
        match self.source_mode {
            StreamSourceMode::Unselected => {
                self.source_mode = StreamSourceMode::EncodedPackets;
                Ok(())
            }
            StreamSourceMode::EncodedPackets => Ok(()),
            StreamSourceMode::DecodedPcm => Err("A decoded PCM stream cannot be switched to encoded passthrough".into()),
        }
    }

    pub(super) fn validate_ac3_encoded_frame(&self, frame: &[u8]) -> Result<(), String> {
        let expected_size = crate::usb_encoded_formats::ac3_frame_size(frame)
            .map_err(str::to_owned)?;
        if expected_size != frame.len() {
            return Err("Demuxed AC-3 packet does not contain exactly one complete frame".into());
        }
        let frame_rate = crate::usb_encoded_formats::ac3_frame_sample_rate(frame)
            .map_err(str::to_owned)?;
        if frame_rate as usize != self.source_rate {
            return Err("AC-3 frame sample rate does not match the demuxed stream sample rate".into());
        }
        Ok(())
    }

    pub(super) fn validate_encoded_uac1_type_ii(&self, format_type: u8, format_tag: u16) -> Result<(), String> {
        crate::usb_encoded_formats::validate_uac1_type_ii(format_type, format_tag, self.codec_type)
            .map_err(str::to_owned)
    }

    pub(super) fn validate_iec61937_ac3(&self, data_type: u16) -> Result<(), String> {
        if data_type != crate::usb_encoded_formats::IEC61937_DATATYPE_AC3 ||
            self.codec_type != symphonia::core::codecs::CODEC_TYPE_EAC3 {
            return Err("IEC 61937 passthrough is currently restricted to AC-3 source frames and data type 0x0001".into());
        }
        Ok(())
    }


    /// This is an exclusive source mode: callers must not interleave it with decode_packet().
    pub(super) fn read_encoded_packet(&mut self) -> Result<Option<(Vec<u8>, u64, u64)>, String> {
        match self.source_mode {
            StreamSourceMode::Unselected => self.source_mode = StreamSourceMode::EncodedPackets,
            StreamSourceMode::EncodedPackets => {}
            StreamSourceMode::DecodedPcm => {
                return Err("A stream decoder cannot switch from decoded PCM to encoded packet mode".into());
            }
        }
        loop {
            let packet = match self.format.next_packet() {
                Ok(packet) => packet,
                Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => return Ok(None),
                Err(Error::ResetRequired) => continue,
                Err(Error::IoError(error)) => return Err(format!("Compressed packet I/O failed: {error}")),
                Err(error) => return Err(format!("Compressed packet read failed: {error}")),
            };
            if packet.track_id() != self.track_id { continue; }
            if packet.data.is_empty() {
                return Err("The demuxer returned an empty compressed packet".into());
            }
            let timestamp_us = self.time_base
                .map(|base| {
                    let time = base.calc_time(packet.ts());
                    ((time.seconds as u128 * 1_000_000)
                        + (time.frac as f64 * 1_000_000.0) as u128)
                        .min(u64::MAX as u128) as u64
                })
                .unwrap_or(0);
            let duration_us = self.time_base
                .map(|base| {
                    let time = base.calc_time(packet.dur());
                    ((time.seconds as u128 * 1_000_000)
                        + (time.frac as f64 * 1_000_000.0) as u128)
                        .min(u64::MAX as u128) as u64
                })
                .unwrap_or(0);
            return Ok(Some((packet.data.to_vec(), timestamp_us, duration_us)));
        }
    }

    fn decode_packet(&mut self) -> Result<bool,String> {
        match self.source_mode {
            StreamSourceMode::Unselected => self.source_mode = StreamSourceMode::DecodedPcm,
            StreamSourceMode::DecodedPcm => {}
            StreamSourceMode::EncodedPackets => {
                return Err("A stream decoder cannot switch from encoded packet mode to decoded PCM".into());
            }
        }
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => {
                    self.eof = true;
                    return Ok(false);
                }
                Err(Error::ResetRequired) => {
                    if let Some(decoder) = self.decoder.as_mut() { decoder.reset(); }
                    continue;
                }
                Err(Error::IoError(error)) => return Err(format!("Audio stream I/O failed: {error}")),
                Err(e) => return Err(format!("Audio packet read failed: {e}")),
            };
            if packet.track_id() != self.track_id { continue; }
            let decoder = self.decoder.as_mut().ok_or_else(|| "This codec is available for encoded passthrough only; no PCM decoder is registered".to_string())?;
            match decoder.decode(&packet) {
                Ok(decoded) => {
                    self.consecutive_decode_errors = 0;
                    let spec = *decoded.spec();
                    if spec.channels.count() != self.channels {
                        return Err("The audio stream changed channel layout mid-track".into());
                    }
                    let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
                    buffer.copy_interleaved_ref(decoded);
                    let mut samples = buffer.samples().to_vec();
                    let decoded_frames = (samples.len() / self.channels) as u64;
                    let discard_samples = self.discard_source_frames.min(decoded_frames) as usize * self.channels;
                    if discard_samples > 0 {
                        samples.drain(..discard_samples);
                        self.discard_source_frames -= (discard_samples / self.channels) as u64;
                    }
                    self.source_frames_decoded += decoded_frames;
                    self.input_pending.extend_from_slice(&samples);
                    return Ok(true);
                }
                Err(Error::DecodeError(reason)) => {
                    self.consecutive_decode_errors = self.consecutive_decode_errors.saturating_add(1);
                    if self.consecutive_decode_errors >= 8 {
                        return Err(format!(
                            "Audio decoder rejected 8 consecutive packets (codec {:?}); last error: {reason}",
                            self.codec_type
                        ));
                    }
                    continue;
                }
                Err(e) => return Err(format!("Audio packet decode failed: {e}")),
            }
        }
    }

    fn apply_eq(&mut self, samples: &mut [f32]) {
        // A flat EQ must be a true bypass: even unity biquad arithmetic can alter
        // low-order sample bits and invalidate source-sample preservation.
        if self.gains.iter().all(|gain| *gain == 0.0) { return; }
        apply_eq_samples(samples, self.channels, &self.coefficients, &mut self.eq_state);
    }

    fn produce_resampled_chunk(&mut self) -> Result<bool,String> {
        let input_frames = self.resampler.as_ref().ok_or("Resampler is not configured")?.input_frames_next();
        while self.input_pending.len() / self.channels < input_frames && !self.eof {
            self.decode_packet()?;
        }
        let available = self.input_pending.len() / self.channels;
        if available == 0 {
            if !self.eof { return Ok(false); }
            let expected_frames = (self.source_frames_decoded as u128 * self.output_rate as u128
                / self.source_rate as u128) as u64;
            if self.produced_frames >= expected_frames { return Ok(false); }
        }
        let mut input = vec![0.0_f32; input_frames * self.channels];
        let take_frames = available.min(input_frames);
        let take_samples = take_frames * self.channels;
        input[..take_samples].copy_from_slice(&self.input_pending[..take_samples]);
        self.input_pending.drain(..take_samples);
        let resampler = self.resampler.as_mut().ok_or("Resampler is not configured")?;
        let output_frames = resampler.output_frames_next();
        let mut output = vec![0.0_f32; output_frames * self.channels];
        let input_adapter = InterleavedSlice::new(&input, self.channels, input_frames)
            .map_err(|e| format!("Rubato input adapter failed: {e}"))?;
        let mut output_adapter = InterleavedSlice::new_mut(&mut output, self.channels, output_frames)
            .map_err(|e| format!("Rubato output adapter failed: {e}"))?;
        let (_, written) = resampler.process_into_buffer(&input_adapter, &mut output_adapter, None)
            .map_err(|e| format!("Rubato streaming resample failed: {e}"))?;
        output.truncate(written * self.channels);
        let delay_samples = self.resampler_delay_frames.min(written) * self.channels;
        if delay_samples > 0 {
            output.drain(..delay_samples);
            self.resampler_delay_frames -= delay_samples / self.channels;
        }
        if self.discard_output_frames > 0 && !output.is_empty() {
            let discard = self.discard_output_frames.min((output.len() / self.channels) as u64) as usize;
            output.drain(..discard * self.channels);
            self.discard_output_frames -= discard as u64;
            self.produced_frames += discard as u64;
        }
        if self.eof && self.input_pending.is_empty() {
            let expected_frames = (self.source_frames_decoded as u128 * self.output_rate as u128
                / self.source_rate as u128) as u64;
            let remaining_frames = expected_frames.saturating_sub(self.produced_frames) as usize;
            output.truncate(output.len().min(remaining_frames * self.channels));
        }
        self.apply_eq(&mut output);
        self.produced_frames += (output.len() / self.channels) as u64;
        self.output_pending.extend(output);
        // A chunk may be consumed entirely by startup-delay or seek-preroll trimming.
        // Report progress so read_frames continues producing instead of mistaking it for EOF.
        Ok(true)
    }

    fn read_frames(&mut self, requested: usize) -> Result<Vec<f32>,String> {
        let mut result = Vec::with_capacity(requested * self.channels);
        while result.len() < requested * self.channels {
            if !self.output_pending.is_empty() {
                let needed = requested * self.channels - result.len();
                let take = needed.min(self.output_pending.len());
                result.extend(self.output_pending.drain(..take));
                continue;
            }
            if self.eof && self.input_pending.is_empty() {
                if self.resampler.is_none() { break; }
                let expected_frames = (self.source_frames_decoded as u128 * self.output_rate as u128
                    / self.source_rate as u128) as u64;
                if self.produced_frames >= expected_frames { break; }
            }
            if let Some(resampler) = self.resampler.as_ref() {
                let _ = resampler;
                if !self.produce_resampled_chunk()? { break; }
            } else {
                while self.input_pending.is_empty() && !self.eof { self.decode_packet()?; }
                if self.input_pending.is_empty() { break; }
                let take = (requested * self.channels - result.len()).min(self.input_pending.len());
                let mut chunk: Vec<f32> = self.input_pending.drain(..take).collect();
                self.apply_eq(&mut chunk);
                self.produced_frames += (chunk.len() / self.channels) as u64;
                result.extend(chunk);
            }
        }
        Ok(result)
    }
    fn decode_native_pcm_packet(
        &mut self,
        format: crate::usb_pcm::PcmFormat,
    ) -> Result<bool, String> {
        match self.source_mode {
            StreamSourceMode::Unselected => self.source_mode = StreamSourceMode::DecodedPcm,
            StreamSourceMode::DecodedPcm => {}
            StreamSourceMode::EncodedPackets => return Err("Encoded stream cannot be decoded as native PCM".into()),
        }
        loop {
            let packet = match self.format.next_packet() {
                Ok(packet) => packet,
                Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => {
                    self.eof = true;
                    return Ok(true);
                }
                Err(Error::ResetRequired) => {
                    if let Some(decoder) = self.decoder.as_mut() { decoder.reset(); }
                    continue;
                }
                Err(Error::IoError(error)) => return Err(format!("Audio stream I/O failed: {error}")),
                Err(error) => return Err(format!("Audio packet read failed: {error}")),
            };
            if packet.track_id() != self.track_id { continue; }
            let decoder = self.decoder.as_mut().ok_or_else(|| "This codec is available for encoded passthrough only".to_string())?;
            let decoded = match decoder.decode(&packet) {
                Ok(decoded) => decoded,
                Err(Error::DecodeError(reason)) => {
                    self.consecutive_decode_errors = self.consecutive_decode_errors.saturating_add(1);
                    if self.consecutive_decode_errors >= 8 {
                        return Err(format!("Audio decoder rejected 8 consecutive packets: {reason}"));
                    }
                    continue;
                }
                Err(error) => return Err(format!("Audio packet decode failed: {error}")),
            };
            self.consecutive_decode_errors = 0;
            let decoded_frames = decoded.frames();
            if decoded.spec().channels.count() != self.channels {
                return Err("The audio stream changed channel layout mid-track".into());
            }
            let discard = self.discard_source_frames.min(decoded_frames as u64) as usize;
            self.source_frames_decoded = self.source_frames_decoded.saturating_add(decoded_frames as u64);
            self.discard_source_frames -= discard as u64;
            if format.encoding == crate::usb_pcm::PcmEncoding::IeeeFloat32 {
                if !self.ieee_float_source { return Err("IEEE Float32 USB output requires an IEEE Float32 source".into()); }
                if let symphonia::core::audio::AudioBufferRef::F32(buffer) = fallback_decoded.clone() {
                    let mut samples = Vec::with_capacity((decoded_frames - discard) * self.channels);
                    for frame in discard..decoded_frames {
                        for channel in 0..self.channels { samples.push(buffer.chan(channel)[frame]); }
                    }
                    self.native_pcm_pending.extend(crate::native_pcm::pack_native_ieee_float32(&samples, format)?);
                    self.native_pcm_active = true;
                    self.native_pcm_decided = true;
                    return Ok(true);
                }
                return Err("Symphonia did not return an IEEE Float32 sample plane".into());
            }
            let native = crate::native_pcm::from_symphonia_buffer(decoded, format.valid_bits, self.channels, discard);
            match native {
                Ok(samples) => {
                    let packed = crate::native_pcm::pack_native_integer_pcm(&samples, format)?;
                    self.native_pcm_pending.extend(packed);
                    self.native_pcm_active = true;
                    self.native_pcm_decided = true;
                    return Ok(true);
                }
                Err(error) if !self.native_pcm_decided => {
                    // USB Direct test mode never converts an unsupported decoded
                    // sample representation to float32. Fail explicitly instead
                    // of silently changing the PCM representation.
                    return Err(format!("Decoded source cannot be represented as native integer PCM: {error}"));
                }
                Err(error) => return Err(format!("Decoder changed sample representation during native PCM playback: {error}")),
            }
        }
    }

    fn read_native_pcm_bytes(
        &mut self,
        max_frames: usize,
        format: crate::usb_pcm::PcmFormat,
    ) -> Result<Option<Vec<u8>>, String> {
        let required = max_frames.saturating_mul(format.bytes_per_frame());
        while self.native_pcm_pending.len() < required && !self.eof
            && (!self.native_pcm_decided || self.native_pcm_active)
        {
            if !self.decode_native_pcm_packet(format)? { return Ok(None); }
            if self.native_pcm_active { self.native_pcm_decided = true; }
        }
        if !self.native_pcm_active { return Ok(None); }
        let count = required.min(self.native_pcm_pending.len());
        Ok(Some(self.native_pcm_pending.drain(..count).collect()))
    }

    fn move_native_remainder_to_float(&mut self, format: crate::usb_pcm::PcmFormat) {
        if !self.native_pcm_active { return; }
        let bytes_per_sample = format.subslot_bytes as usize;
        let bytes: Vec<u8> = self.native_pcm_pending.drain(..).collect();
        if format.encoding == crate::usb_pcm::PcmEncoding::IeeeFloat32 {
            for sample in bytes.chunks_exact(4) {
                self.input_pending.push(f32::from_bits(u32::from_le_bytes([sample[0],sample[1],sample[2],sample[3]])));
            }
        } else {
            let scale = (1_u64 << (format.valid_bits - 1)) as f32;
            for sample in bytes.chunks_exact(bytes_per_sample) {
                let mut raw = [0_u8; 4]; raw[..bytes_per_sample].copy_from_slice(sample);
                let padding_bits = bytes_per_sample as u32 * 8 - format.valid_bits as u32;
                let aligned = u32::from_le_bytes(raw) >> padding_bits;
                let signed = if format.valid_bits == 32 { aligned as i32 } else {
                    let sign_shift = 32 - format.valid_bits as u32; ((aligned << sign_shift) as i32) >> sign_shift
                };
                self.input_pending.push(signed as f32 / scale);
            }
        }
        debug_assert_eq!(self.input_pending.len() % self.channels, 0);
        self.native_pcm_active = false;
        self.native_pcm_decided = true;
    }

    pub(super) fn native_pcm_active(&self) -> bool {
        self.native_pcm_active
    }

    pub(super) fn read_usb_pcm(
        &mut self,
        max_frames: usize,
        target_gain: f32,
        format: crate::usb_pcm::PcmFormat,
    ) -> Result<Vec<u8>, String> {
        let format = format.validate().map_err(str::to_string)?;
        if self.channels != format.channels as usize || max_frames == 0 || !target_gain.is_finite() {
            return Err("Invalid direct USB PCM read configuration".into());
        }
        let requirements = crate::native_pcm::BitPerfectRequirements {
            source_rate: self.source_rate as u32,
            output_rate: self.output_rate as u32,
            source_channels: self.channels as u16,
            output_channels: format.channels,
            source_valid_bits: self.source_bits,
            dsp_enabled: self.gains.iter().any(|gain| *gain != 0.0),
            software_gain_unity: target_gain == 1.0,
            channel_mapping_unchanged: true,
            resampling_disabled: self.resampler.is_none(),
        };
        let native_eligible = match format.encoding {
            crate::usb_pcm::PcmEncoding::SignedInteger => matches!(format.valid_bits, 8 | 16 | 20 | 24 | 32)
                && crate::native_pcm::is_bit_perfect_eligible(requirements, format),
            crate::usb_pcm::PcmEncoding::IeeeFloat32 => self.ieee_float_source
                && format.valid_bits == 32 && format.subslot_bytes == 4
                && crate::native_pcm::is_bit_perfect_eligible(requirements, format),
        };

        if native_eligible && (!self.native_pcm_decided || self.native_pcm_active) {
            if let Some(bytes) = self.read_native_pcm_bytes(max_frames, format)? {
                self.produced_frames = self.produced_frames.saturating_add(
                    (bytes.len() / format.bytes_per_frame()) as u64
                );
                self.usb_last_gain = 1.0;
                return Ok(bytes);
            }
        } else if !native_eligible {
            self.move_native_remainder_to_float(format);
        }

        let samples = self.read_frames(max_frames)?;
        let gain = target_gain.clamp(0.0, 1.0);
        let start_gain = if self.usb_last_gain < 0.0 { gain } else { self.usb_last_gain };
        let sample_count = samples.len();
        let total_frames = sample_count / self.channels;
        let mut packed = Vec::with_capacity(sample_count.saturating_mul(format.subslot_bytes as usize));
        for (index, sample) in samples.into_iter().enumerate() {
            // Apply one gain value to every channel in the same audio frame.
            // A sample-index ramp would give left/right channels different gains.
            let frame_index = index / self.channels;
            let fraction = if total_frames <= 1 { 1.0 } else { (frame_index + 1) as f32 / total_frames as f32 };
            let ramp_gain = start_gain + (gain - start_gain) * fraction;
            if format.encoding == crate::usb_pcm::PcmEncoding::IeeeFloat32 {
                packed.extend_from_slice(&(sample * ramp_gain).to_le_bytes());
            } else {
                let value = quantize_signed_pcm(sample, ramp_gain, format.valid_bits);
                let subslot_shift = (format.subslot_bytes as u32 * 8) - format.valid_bits as u32;
                let encoded = (value as i32).wrapping_shl(subslot_shift) as u32;
                for byte in 0..format.subslot_bytes { packed.push((encoded >> (byte * 8)) as u8); }
            }
        }
        self.usb_last_gain = gain;
        Ok(packed)
    }

}

fn quantize_signed_pcm(sample: f32, gain: f32, valid_bits: u8) -> i64 {
    if !sample.is_finite() || !gain.is_finite() || !(2..=32).contains(&valid_bits) {
        return 0;
    }
    let value = (sample * gain.clamp(0.0, 1.0)).clamp(-1.0, 1.0);
    let negative_limit = -(1_i64 << (valid_bits - 1));
    let positive_limit = (1_i64 << (valid_bits - 1)) - 1;
    let scale = (1_u64 << (valid_bits - 1)) as f64;
    ((value as f64 * scale).round() as i64).clamp(negative_limit, positive_limit)
}

fn gains_from_java(env: &mut JNIEnv, array: &JFloatArray) -> Result<[f32;31],String> {
    let len=env.get_array_length(array).map_err(|e|e.to_string())? as usize;
    if len!=31 { return Err("31 equalizer gains are required".into()); }
    let mut values=vec![0.0_f32;31];
    env.get_float_array_region(array,0,&mut values).map_err(|e|e.to_string())?;
    let mut gains=[0.0_f32;31]; gains.copy_from_slice(&values); Ok(gains)
}

static STREAM_DECODERS: OnceLock<Mutex<HashMap<i64, Arc<Mutex<StreamDecoder>>>>> = OnceLock::new();
static NEXT_STREAM_DECODER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

fn decoder_registry() -> &'static Mutex<HashMap<i64, Arc<Mutex<StreamDecoder>>>> {
    STREAM_DECODERS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn decoder_ref(handle: jlong) -> Option<Arc<Mutex<StreamDecoder>>> {
    if handle <= 0 { return None; }
    decoder_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}


#[cfg(target_os = "android")]
struct StreamPumpWorker {
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    error: Arc<Mutex<String>>,
    epoch: u64,
    thread: JoinHandle<()>,
}

#[cfg(target_os = "android")]
static STREAM_PUMP_WORKERS: OnceLock<Mutex<HashMap<i64, StreamPumpWorker>>> = OnceLock::new();

#[cfg(target_os = "android")]
static STREAM_PUMP_LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();

#[cfg(target_os = "android")]
fn stream_pump_workers() -> &'static Mutex<HashMap<i64, StreamPumpWorker>> {
    STREAM_PUMP_WORKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[cfg(target_os = "android")]
fn stream_pump_lifecycle() -> &'static Mutex<()> {
    STREAM_PUMP_LIFECYCLE.get_or_init(|| Mutex::new(()))
}

#[cfg(target_os = "android")]
pub(super) fn stream_pump_status(handle: jlong) -> Option<(i32, u64)> {
    stream_pump_workers()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .get(&handle)
        .map(|worker| (worker.status.load(Ordering::Acquire), worker.epoch))
}

#[cfg(target_os = "android")]
fn stop_stream_pump_worker_locked(handle: jlong) {
    let worker = stream_pump_workers()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .remove(&handle);
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);
        let _ = worker.thread.join();
    }
}

#[cfg(target_os = "android")]
pub(super) fn stop_stream_pump_worker(handle: jlong) {
    let _lifecycle = stream_pump_lifecycle()
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    stop_stream_pump_worker_locked(handle);
}

fn register_decoder(decoder: StreamDecoder) -> jlong {
    let decoder = Arc::new(Mutex::new(decoder));
    loop {
        let candidate = NEXT_STREAM_DECODER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = decoder_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::clone(&decoder));
            return handle as jlong;
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateStreamDecoder(
    mut env: JNIEnv, _class: JClass, fd: jint, extension: JString
) -> jlong {
    let ext=match env.get_string(&extension) { Ok(v)=>v.to_string_lossy().into_owned(),Err(_)=>return 0 };
    match StreamDecoder::new(fd,&ext) {
        Ok(decoder)=>register_decoder(decoder),
        Err(error)=>{let _=env.throw_new("java/lang/IllegalArgumentException",error);0}
    }
}

fn aaudio_seek_request_is_current(
    request: &crate::playback_session::AAudioSeekRequest,
    current_epoch: u64,
    cancelled: bool,
) -> bool {
    !cancelled && request.epoch == current_epoch
}

fn clamp_aaudio_seek_target(request: &crate::playback_session::AAudioSeekRequest) -> i64 {
    request.position_ms.max(request.segment_start_ms)
        .min(if request.segment_end_ms >= 0 { request.segment_end_ms } else { i64::MAX })
}

/// Applies a queued AAudio seek on the native output scheduler thread.
#[cfg(target_os = "android")]
pub(super) fn process_aaudio_seek(
    session: Arc<crate::playback_session::SessionState>,
    request: crate::playback_session::AAudioSeekRequest,
) -> Result<i64, String> {
    if session.route != 0 || !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        return Ok(-1);
    }
    let decoder_handle = session.decoder_handle;
    let decoder = Arc::clone(&session.decoder);
    let output_rate = decoder.lock().unwrap_or_else(|error| error.into_inner()).output_rate;
    if output_rate == 0 {
        return Err("The stream decoder has not been configured".into());
    }
    let target_ms = clamp_aaudio_seek_target(&request);
    stop_stream_pump_worker(decoder_handle);
    if !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        return Ok(-1);
    }
    let seek_result = {
        let mut guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
        guard.seek_to(target_ms as u64).map_err(|error| error.to_string())
    };
    let actual = match seek_result {
        Ok(position) => position as i64,
        Err(_) if !aaudio_seek_request_is_current(
            &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
        ) => return Ok(-1),
        Err(error) => return Err(error),
    };
    if !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        return Ok(-1);
    }
    let (target_frame, remaining_frames) = aaudio_seek_frame_window(
        actual.max(0) as u64,
        request.segment_start_ms as u64,
        (request.segment_end_ms >= 0).then_some(request.segment_end_ms as u64),
        output_rate,
    );
    if !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        return Ok(-1);
    }
    let flush_position = i64::try_from(target_frame).unwrap_or(i64::MAX);
    let flush_result = crate::aaudio::flush_output_for_seek(flush_position);
    if flush_result != 0 {
        if !aaudio_seek_request_is_current(
            &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
        ) {
            return Ok(-1);
        }
        return Err(format!("Unable to flush AAudio output for seek (error {flush_result})"));
    }
    if !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        return Ok(-1);
    }
    if let Err(error) = start_stream_pump_worker(
        decoder_handle, decoder, Arc::clone(&session), request.max_frames, remaining_frames,
    ) {
        if !aaudio_seek_request_is_current(
            &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
        ) {
            return Ok(-1);
        }
        return Err(error);
    }
    let position_gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    if !aaudio_seek_request_is_current(
        &request, session.epoch.load(Ordering::Acquire), session.cancelled.load(Ordering::Acquire),
    ) {
        drop(position_gate);
        stop_stream_pump_worker(decoder_handle);
        return Ok(-1);
    }
    session.last_seek_position_ms.store(actual, Ordering::Release);
    drop(position_gate);
    Ok(actual)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekStreamDecoder(
    mut env: JNIEnv, _class: JClass, handle: jlong, position_ms: jlong
) -> jlong {
    if position_ms < 0 { return -1; }
    let Some(decoder) = decoder_ref(handle) else { return -1; };
    let result = { let mut guard = decoder.lock().unwrap_or_else(|error| error.into_inner()); guard.seek_to(position_ms as u64) };
    match result {
        Ok(position) => position as jlong,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsIeeeFloat32Source(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jboolean {
    let Some(decoder) = decoder_ref(handle) else { return jni::sys::JNI_FALSE; };
    if decoder.lock().unwrap_or_else(|error| error.into_inner()).is_ieee_float32_source() { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsAc3Source(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jboolean {
    let Some(decoder) = decoder_ref(handle) else { return jni::sys::JNI_FALSE; };
    if decoder.lock().unwrap_or_else(|error| error.into_inner()).is_ac3_source() { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    let Some(decoder) = decoder_ref(handle) else { return 0; };
    let decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
    (((decoder.source_rate as u64)<<32) | decoder.channels as u64) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetBitPerfectSourceInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    let Some(decoder) = decoder_ref(handle) else { return 0; };
    let decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
    let lossless = if decoder.lossless_source { 1_u64 } else { 0_u64 };
    ((lossless << 32) | decoder.source_bits as u64) as jlong
}

#[no_mangle]
#[allow(non_snake_case)]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamDurationMs(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    let Some(decoder) = decoder_ref(handle) else { return 0; };
    let duration = decoder.lock().unwrap_or_else(|error| error.into_inner()).duration_ms as jlong;
    duration
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeConfigureStreamDecoder(
    mut env: JNIEnv, _class: JClass, handle: jlong, output_rate: jint, gains: JFloatArray
) -> jint {
    let gains=match gains_from_java(&mut env,&gains) { Ok(v)=>v,Err(e)=>{let _=env.throw_new("java/lang/IllegalArgumentException",e);return -1;} };
    let Some(decoder) = decoder_ref(handle) else { return -1; };
    let result = { let mut guard = decoder.lock().unwrap_or_else(|error| error.into_inner()); guard.configure(output_rate as usize,&gains) };
    match result {
        Ok(())=>0,Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);-1}
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeUpdateStreamGains(
    mut env: JNIEnv, _class: JClass, handle: jlong, gains: JFloatArray
) -> jint {
    let gains=match gains_from_java(&mut env,&gains) { Ok(v)=>v,Err(e)=>{let _=env.throw_new("java/lang/IllegalArgumentException",e);return -1;} };
    let Some(decoder) = decoder_ref(handle) else { return -1; };
    let mut decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
    decoder.gains.copy_from_slice(&gains);
    decoder.coefficients = std::array::from_fn(|i| make_coefficients(FREQUENCIES[i], decoder.gains[i], decoder.output_rate));
    0
}


/// Starts a Rust-owned scheduler that incrementally decodes into the bounded AAudio ring.
/// total_frames < 0 means decode until the source reaches EOF.
#[cfg(target_os = "android")]
fn start_stream_pump_worker(
    handle: jlong,
    decoder: Arc<Mutex<StreamDecoder>>,
    session: Arc<crate::playback_session::SessionState>,
    chunk_frames: usize,
    frame_limit: Option<u64>,
) -> Result<(), String> {
    // Serialize the complete replace operation, not just map removal. Without
    // this gate, concurrent starts can both observe an empty slot and one can
    // detach the other worker by replacing its JoinHandle before it is joined.
    let _lifecycle = stream_pump_lifecycle()
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    stop_stream_pump_worker_locked(handle);
    if session.cancelled.load(Ordering::Acquire) {
        return Err("Playback session is cancelled".into());
    }
    let worker_epoch = session.epoch.load(Ordering::Acquire);
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let error = Arc::new(Mutex::new(String::new()));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let thread_error = Arc::clone(&error);
    let thread_session = Arc::clone(&session);
    let worker = thread::Builder::new().name("flowery-aaudio-decode".into()).spawn(move || {
        let mut produced_frames = 0_u64;
        while !thread_stop.load(Ordering::Acquire) {
            if thread_session.cancelled.load(Ordering::Acquire) ||
                thread_session.epoch.load(Ordering::Acquire) != worker_epoch {
                thread_status.store(2, Ordering::Release);
                break;
            }
            let Some((queued, available, queue_target)) = crate::aaudio::output_queue_budget() else {
                thread::sleep(Duration::from_millis(2));
                continue;
            };
            let remaining_budget = queue_target.saturating_sub(queued);
            if available == 0 || remaining_budget == 0 {
                thread::sleep(Duration::from_millis(2));
                continue;
            }
            let mut decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
            if decoder.channels > 2 {
                *thread_error.lock().unwrap_or_else(|poison| poison.into_inner()) =
                    format!("AAudio streaming does not support {} output channels", decoder.channels);
                thread_status.store(-2, Ordering::Release);
                break;
            }
            let mut frames = chunk_frames.min(available).min(remaining_budget);
            if let Some(limit) = frame_limit {
                let remaining = limit.saturating_sub(produced_frames);
                if remaining == 0 {
                    crate::aaudio::signal_stream_eof();
                    thread_status.store(1, Ordering::Release);
                    break;
                }
                frames = frames.min(remaining as usize);
            }
            let samples = match decoder.read_frames(frames) {
                Ok(samples) => samples,
                Err(decode_error) => {
                    *thread_error.lock().unwrap_or_else(|poison| poison.into_inner()) = decode_error;
                    thread_status.store(-2, Ordering::Release);
                    break;
                }
            };
            if samples.is_empty() {
                crate::aaudio::signal_stream_eof();
                thread_status.store(1, Ordering::Release);
                break;
            }
            let channels = decoder.channels;
            drop(decoder);
            match crate::aaudio::write_output_samples(&samples) {
                Ok(written) if written == samples.len() / channels => {
                    produced_frames = produced_frames.saturating_add(written as u64);
                }
                Ok(written) => {
                    *thread_error.lock().unwrap_or_else(|poison| poison.into_inner()) = format!(
                        "AAudio ring buffer accepted an incomplete PCM chunk ({written} of {} frames)",
                        samples.len() / channels
                    );
                    thread_status.store(-2, Ordering::Release);
                    break;
                }
                Err(write_error) => {
                    *thread_error.lock().unwrap_or_else(|poison| poison.into_inner()) =
                        format!("AAudio ring buffer write failed: {write_error}");
                    thread_status.store(-2, Ordering::Release);
                    break;
                }
            }
            if frame_limit.is_some_and(|limit| produced_frames >= limit) {
                crate::aaudio::signal_stream_eof();
                thread_status.store(1, Ordering::Release);
                break;
            }
        }
        if thread_stop.load(Ordering::Acquire) {
            thread_status.store(2, Ordering::Release);
        }
    }).map_err(|error| error.to_string())?;
    let session_gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    if session.cancelled.load(Ordering::Acquire) ||
        session.epoch.load(Ordering::Acquire) != worker_epoch {
        stop.store(true, Ordering::Release);
        drop(session_gate);
        let _ = worker.join();
        return Err("Playback session changed while the AAudio decode worker was starting".into());
    }
    stream_pump_workers().lock().unwrap_or_else(|error| error.into_inner())
        .insert(handle, StreamPumpWorker { stop, status, error, epoch: worker_epoch, thread: worker });
    drop(session_gate);
    Ok(())
}

/// Starts the AAudio decode worker for an absolute-time segment. Rust seeks to
/// the segment start and derives the output-frame limit from the configured rate.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartSegmentedStreamPump(
    mut env: JNIEnv, _class: JClass, handle: jlong, session_handle: jlong,
    max_frames: jint, segment_start_ms: jlong, segment_end_ms: jlong,
) -> jint {
    if handle <= 0 || session_handle <= 0 || max_frames <= 0 ||
        segment_start_ms < 0 || segment_end_ms < -1 ||
        (segment_end_ms >= 0 && segment_end_ms < segment_start_ms) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid native stream segment");
        return -1;
    }
    let Some(decoder) = decoder_ref(handle) else { return -1; };
    let Some(session) = session_ref(session_handle) else { return -1; };
    if !Arc::ptr_eq(&decoder, &session.decoder) || session.cancelled.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session does not own an active decoder");
        return -1;
    }
    let (output_rate, duration_ms) = {
        let guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
        (guard.output_rate, guard.duration_ms)
    };
    if output_rate == 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "The stream decoder has not been configured");
        return -1;
    }
    let actual_start = {
        let mut guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
        match guard.seek_to(segment_start_ms as u64) {
            Ok(position) => position,
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return -1;
            }
        }
    };
    let effective_end = if segment_end_ms >= 0 { segment_end_ms as u64 } else { duration_ms };
    let start_frame = milliseconds_to_frames(actual_start, output_rate);
    let end_frame = milliseconds_to_frames(effective_end.max(actual_start), output_rate);
    let frame_limit = if segment_end_ms >= 0 || duration_ms > 0 {
        Some(end_frame.saturating_sub(start_frame))
    } else { None };
    match start_stream_pump_worker(handle, decoder, session, max_frames as usize, frame_limit) {
        Ok(()) => 0,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

/// Legacy unbounded/explicit-frame worker entry point retained for native callers.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartStreamPump(
    mut env: JNIEnv, _class: JClass, handle: jlong, session_handle: jlong, max_frames: jint, total_frames: jlong,
) -> jint {
    if handle <= 0 || session_handle <= 0 || max_frames <= 0 || total_frames < -1 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid native stream pump configuration");
        return -1;
    }
    let Some(decoder) = decoder_ref(handle) else { return -1; };
    let Some(session) = session_ref(session_handle) else { return -1; };
    if !Arc::ptr_eq(&decoder, &session.decoder) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session does not own the decoder");
        return -1;
    }
    match start_stream_pump_worker(
        handle, decoder, session, max_frames as usize,
        if total_frames < 0 { None } else { Some(total_frames as u64) },
    ) {
        Ok(()) => 0,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamPumpStatus(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    stream_pump_workers().lock().unwrap_or_else(|error| error.into_inner())
        .get(&handle).map(|worker| worker.status.load(Ordering::Acquire)).unwrap_or(-1)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamPumpError(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let error = stream_pump_workers().lock().unwrap_or_else(|poison| poison.into_inner())
        .get(&handle)
        .map(|worker| worker.error.lock().unwrap_or_else(|poison| poison.into_inner()).clone())
        .unwrap_or_else(|| "The Rust stream-pump worker is unavailable".to_string());
    write_text_to_java(&mut env, &error)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopStreamPump(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    stop_stream_pump_worker(handle);
}

fn remove_decoder(handle: jlong) {
    if handle <= 0 { return; }
    #[cfg(target_os = "android")]
    stop_stream_pump_worker(handle);
    decoder_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseStreamDecoder(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    remove_decoder(handle);
}

#[cfg(test)]
mod handle_tests {
    use super::*;

    #[test]
    fn decoder_registry_rejects_non_positive_and_unknown_handles() {
        assert!(decoder_ref(0).is_none());
        assert!(decoder_ref(-1).is_none());
        assert!(decoder_ref(i64::MAX).is_none());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    #[test]
    fn aaudio_seek_requests_enforce_latest_epoch_and_cancellation() {
        use crate::playback_session::AAudioSeekRequest;
        let older = AAudioSeekRequest { epoch: 7, max_frames: 128, position_ms: 100,
            segment_start_ms: 0, segment_end_ms: -1 };
        let latest = AAudioSeekRequest { epoch: 8, max_frames: 128, position_ms: 900,
            segment_start_ms: 0, segment_end_ms: -1 };
        assert!(aaudio_seek_request_is_current(&older, 7, false));
        assert!(!aaudio_seek_request_is_current(&older, 8, false));
        assert!(aaudio_seek_request_is_current(&latest, 8, false));
        assert!(!aaudio_seek_request_is_current(&latest, 8, true));
    }

    #[test]
    fn aaudio_seek_race_discards_old_request_after_new_epoch_is_published() {
        use crate::playback_session::AAudioSeekRequest;
        let epoch = AtomicU64::new(11);
        let in_flight = AAudioSeekRequest { epoch: 11, max_frames: 64, position_ms: 250,
            segment_start_ms: 0, segment_end_ms: -1 };
        epoch.store(12, Ordering::Release);
        let newest = AAudioSeekRequest { epoch: 12, max_frames: 64, position_ms: 1_250,
            segment_start_ms: 0, segment_end_ms: -1 };
        assert!(!aaudio_seek_request_is_current(&in_flight, epoch.load(Ordering::Acquire), false));
        assert!(aaudio_seek_request_is_current(&newest, epoch.load(Ordering::Acquire), false));
    }
    use std::io::Write;
    use std::os::fd::AsRawFd;

    #[test]
    fn stream_format_enables_gapless_packet_trimming() {
        assert!(gapless_format_options().enable_gapless);
    }

    #[test]
    fn presentation_frame_count_excludes_encoder_delay_and_padding() {
        assert_eq!(
            presentation_frame_count(Some(100_000), Some(576), Some(1_024)),
            Some(98_400)
        );
    }

    #[test]
    fn presentation_frame_count_preserves_untrimmed_and_unknown_lengths() {
        assert_eq!(presentation_frame_count(Some(48_000), None, None), Some(48_000));
        assert_eq!(presentation_frame_count(None, Some(312), Some(1_200)), None);
    }

    #[test]
    fn presentation_frame_count_saturates_when_trim_metadata_exceeds_stream_length() {
        assert_eq!(
            presentation_frame_count(Some(1_000), Some(700), Some(500)),
            Some(0)
        );
    }

    #[test]
    fn presentation_duration_uses_trimmed_frames_and_rejects_invalid_rate() {
        assert_eq!(
            presentation_duration_ms(Some(100_000), Some(576), Some(1_024), 44_100),
            2_231
        );
        assert_eq!(presentation_duration_ms(Some(48_000), None, None, 48_000), 1_000);
        assert_eq!(presentation_duration_ms(Some(48_000), None, None, 0), 0);
        assert_eq!(presentation_duration_ms(None, None, None, 48_000), 0);
    }

    #[test]
    fn alac_channel_count_reads_mandatory_magic_cookie_configuration() {
        let mut cookie = vec![0_u8; 24];
        cookie[9] = 2;
        assert_eq!(alac_channel_count(Some(&cookie)), Some(2));

        let mut extended_cookie = vec![0_u8; 48];
        extended_cookie[9] = 6;
        assert_eq!(alac_channel_count(Some(&extended_cookie)), Some(6));
    }

    #[test]
    fn alac_channel_count_rejects_invalid_magic_cookie_data() {
        assert_eq!(alac_channel_count(None), None);
        assert_eq!(alac_channel_count(Some(&[0_u8; 23])), None);
        assert_eq!(alac_channel_count(Some(&[0_u8; 47])), None);

        let mut invalid_channels = vec![0_u8; 24];
        invalid_channels[9] = 0;
        assert_eq!(alac_channel_count(Some(&invalid_channels)), None);
        invalid_channels[9] = 9;
        assert_eq!(alac_channel_count(Some(&invalid_channels)), None);
    }

    #[test]
    fn generic_pcm_quantization_handles_supported_valid_bit_widths() {
        for bits in [8, 16, 20, 24, 32] {
            let negative_limit = -(1_i64 << (bits - 1));
            let positive_limit = (1_i64 << (bits - 1)) - 1;
            assert_eq!(quantize_signed_pcm(-1.0, 1.0, bits), negative_limit);
            assert_eq!(quantize_signed_pcm(1.0, 1.0, bits), positive_limit);
            assert_eq!(quantize_signed_pcm(0.0, 0.5, bits), 0);
            assert_eq!(quantize_signed_pcm(f32::NAN, 1.0, bits), 0);
        }
    }

    #[test]
    fn normalized_f32_round_trip_preserves_supported_integer_pcm_samples() {
        for bits in [8_u8, 16, 24] {
            let scale = (1_i64 << (bits - 1)) as f32;
            let values = [
                -(1_i64 << (bits - 1)),
                -((1_i64 << (bits - 1)) / 2),
                -1,
                0,
                1,
                (1_i64 << (bits - 1)) / 2,
                (1_i64 << (bits - 1)) - 1,
            ];
            for value in values {
                let normalized = value as f32 / scale;
                assert_eq!(
                    quantize_signed_pcm(normalized, 1.0, bits),
                    value,
                    "{bits}-bit integer sample {value} changed during normalized-f32 packing"
                );
            }
        }
    }

    #[test]
    fn equalizer_state_is_continuous_across_chunk_boundaries() {
        let coefficients = std::array::from_fn(|i| make_coefficients(FREQUENCIES[i], 4.5, 48_000));
        let input: Vec<f32> = (0..4096)
            .map(|i| ((i as f32) * 0.031).sin() * 0.4)
            .collect();

        let mut whole = input.clone();
        let mut whole_state = vec![[BiquadState::default(); 31]; 2];
        apply_eq_samples(&mut whole, 2, &coefficients, &mut whole_state);

        let mut chunked = input.clone();
        let mut chunk_state = vec![[BiquadState::default(); 31]; 2];
        for chunk in chunked.chunks_mut(514) {
            apply_eq_samples(chunk, 2, &coefficients, &mut chunk_state);
        }

        for (a, b) in whole.iter().zip(chunked.iter()) {
            assert!((a - b).abs() < 1.0e-6, "chunk boundary changed filter output");
        }
    }

    #[test]
    fn wav_seek_discards_preroll_and_resumes_at_requested_time() {
        let path = std::env::temp_dir().join(format!("flowery-seek-test-{}.wav", std::process::id()));
        let sample_rate = 48_000u32;
        let channels = 2u16;
        let frames = 48_000u32;
        let data_bytes = frames * channels as u32 * 2;
        {
            let mut file = std::fs::File::create(&path).unwrap();
            file.write_all(b"RIFF").unwrap();
            file.write_all(&(36 + data_bytes).to_le_bytes()).unwrap();
            file.write_all(b"WAVEfmt ").unwrap();
            file.write_all(&16u32.to_le_bytes()).unwrap();
            file.write_all(&1u16.to_le_bytes()).unwrap();
            file.write_all(&channels.to_le_bytes()).unwrap();
            file.write_all(&sample_rate.to_le_bytes()).unwrap();
            file.write_all(&(sample_rate * channels as u32 * 2).to_le_bytes()).unwrap();
            file.write_all(&(channels * 2).to_le_bytes()).unwrap();
            file.write_all(&16u16.to_le_bytes()).unwrap();
            file.write_all(b"data").unwrap();
            file.write_all(&data_bytes.to_le_bytes()).unwrap();
            for frame in 0..frames {
                let value = (frame % 30_000) as i16;
                file.write_all(&value.to_le_bytes()).unwrap();
                file.write_all(&value.to_le_bytes()).unwrap();
            }
        }

        let source_file = std::fs::File::open(&path).unwrap();
        let mut stream = StreamDecoder::new(source_file.as_raw_fd(), "wav").unwrap();
        assert_eq!(stream.source_bits, 16);
        assert!(stream.lossless_source, "PCM WAV must be classified as lossless");
        stream.configure(48_000, &[0.0; 31]).unwrap();
        assert_eq!(stream.read_frames(128).unwrap().len(), 256);
        assert_eq!(stream.seek_to(500).unwrap(), 500);
        let resumed = stream.read_frames(8).unwrap();
        assert_eq!(resumed.len(), 16);
        let expected = 24_000.0 / 32_768.0;
        assert!((resumed[0] - expected).abs() < 0.03);

        // A converted seek must retain decoder preroll to rebuild Rubato's filter history.
        // The output blocks before the target timestamp are discarded internally.
        stream.configure(44_100, &[0.0; 31]).unwrap();
        assert_eq!(stream.seek_to(500).unwrap(), 500);
        let converted = stream.read_frames(8).unwrap();
        assert_eq!(converted.len(), 16);
        assert!((converted[0] - expected).abs() < 0.02);
        drop(stream);
        drop(source_file);
        std::fs::remove_file(path).unwrap();
    }

    fn biquad_magnitude_db(coefficients: BiquadCoefficients, frequency: f32, sample_rate: f32) -> f32 {
        let omega = 2.0 * std::f32::consts::PI * frequency / sample_rate;
        let cos1 = omega.cos();
        let sin1 = -omega.sin();
        let cos2 = (2.0 * omega).cos();
        let sin2 = -(2.0 * omega).sin();

        let numerator_real = coefficients.b0 + coefficients.b1 * cos1 + coefficients.b2 * cos2;
        let numerator_imag = coefficients.b1 * sin1 + coefficients.b2 * sin2;
        let denominator_real = 1.0 + coefficients.a1 * cos1 + coefficients.a2 * cos2;
        let denominator_imag = coefficients.a1 * sin1 + coefficients.a2 * sin2;
        let numerator_magnitude = (numerator_real * numerator_real + numerator_imag * numerator_imag).sqrt();
        let denominator_magnitude = (denominator_real * denominator_real + denominator_imag * denominator_imag).sqrt();
        20.0 * (numerator_magnitude / denominator_magnitude).log10()
    }

    #[test]
    fn equalizer_uses_strict_iso_one_third_octave_q() {
        let expected_q = 1.0
            / (2.0_f32.powf(1.0 / 6.0) - 2.0_f32.powf(-1.0 / 6.0));
        assert!((EQ_BAND_Q - expected_q).abs() < 0.001);

        let center_hz = 1_000.0;
        let sample_rate = 48_000.0;
        let coefficients = make_coefficients(center_hz, 12.0, sample_rate as usize);
        let center_gain = biquad_magnitude_db(coefficients, center_hz, sample_rate);
        assert!((center_gain - 12.0).abs() < 0.1, "center gain should match the requested dB value");
    }

    #[test]
    fn equalizer_coefficients_remain_finite_at_supported_rates() {
        for rate in [8_000usize, 22_050, 44_100, 48_000, 96_000, 192_000] {
            for frequency in FREQUENCIES {
                for gain in [-12.0, -6.0, 0.0, 6.0, 12.0] {
                    let c = make_coefficients(frequency, gain, rate);
                    assert!(c.b0.is_finite() && c.b1.is_finite() && c.b2.is_finite());
                    assert!(c.a1.is_finite() && c.a2.is_finite());
                }
            }
        }
    }

    #[test]
    fn aaudio_seek_target_is_clamped_to_the_active_segment() {
        let bounded = crate::playback_session::AAudioSeekRequest {
            epoch: 0,
            max_frames: 8192,
            position_ms: 900,
            segment_start_ms: 1_000,
            segment_end_ms: 4_000,
        };
        assert_eq!(clamp_aaudio_seek_target(&bounded), 1_000);

        let beyond_end = crate::playback_session::AAudioSeekRequest {
            position_ms: 9_000,
            ..bounded
        };
        assert_eq!(clamp_aaudio_seek_target(&beyond_end), 4_000);

        let unbounded = crate::playback_session::AAudioSeekRequest {
            segment_end_ms: -1,
            ..bounded
        };
        assert_eq!(clamp_aaudio_seek_target(&unbounded), 1_000);
    }

    #[test]
    fn seek_frame_conversion_saturates_instead_of_wrapping() {
        assert_eq!(milliseconds_to_frames(u64::MAX, 192_000), u64::MAX);
        assert_eq!(milliseconds_to_frames(1_000, 48_000), 48_000);
    }

    #[test]
    fn container_timestamp_conversion_uses_exact_rational_time_base_arithmetic() {
        use symphonia::core::units::TimeBase;

        assert_eq!(timestamp_to_milliseconds(48_000, Some(TimeBase::new(1, 48_000)), 0), 1_000);
        assert_eq!(timestamp_to_milliseconds(90_000, Some(TimeBase::new(1, 90_000)), 0), 1_000);
        assert_eq!(timestamp_to_milliseconds(1_001, Some(TimeBase::new(1, 3)), 0), 333_666);
        assert_eq!(timestamp_to_milliseconds(u64::MAX, Some(TimeBase::new(1, 1)), 0), u64::MAX);
        assert_eq!(timestamp_to_milliseconds(10, None, 123), 123);
        assert_eq!(timestamp_to_milliseconds(10, Some(TimeBase::default()), 123), 123);
    }

    #[test]
    fn aaudio_segment_seek_window_stays_relative_and_stops_at_segment_end() {
        assert_eq!(
            aaudio_seek_frame_window(2_500, 2_000, Some(5_000), 48_000),
            (24_000, Some(120_000)),
        );
        assert_eq!(
            aaudio_seek_frame_window(5_000, 2_000, Some(5_000), 48_000),
            (144_000, Some(0)),
        );
        assert_eq!(
            aaudio_seek_frame_window(1_000, 2_000, Some(5_000), 48_000),
            (0, Some(144_000)),
        );
        assert_eq!(
            aaudio_seek_frame_window(2_500, 2_000, None, 44_100),
            (22_050, None),
        );
    }
    #[test]
    fn uncompressed_wav_pcm_reaches_usb_bytes_bit_exact_without_float_conversion() {
        let path = std::env::temp_dir().join(format!("flowery-native-pcm-test-{}.wav", std::process::id()));
        let samples = [-32_768_i16, -1, 0, 1, 32_767, -12_345];
        let data_bytes = (samples.len() * 2) as u32;
        {
            let mut file = std::fs::File::create(&path).unwrap();
            file.write_all(b"RIFF").unwrap();
            file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
            file.write_all(b"WAVEfmt ").unwrap();
            file.write_all(&16_u32.to_le_bytes()).unwrap();
            file.write_all(&1_u16.to_le_bytes()).unwrap();
            file.write_all(&2_u16.to_le_bytes()).unwrap();
            file.write_all(&48_000_u32.to_le_bytes()).unwrap();
            file.write_all(&(48_000_u32 * 4).to_le_bytes()).unwrap();
            file.write_all(&4_u16.to_le_bytes()).unwrap();
            file.write_all(&16_u16.to_le_bytes()).unwrap();
            file.write_all(b"data").unwrap();
            file.write_all(&data_bytes.to_le_bytes()).unwrap();
            for sample in samples { file.write_all(&sample.to_le_bytes()).unwrap(); }
        }

        let source_file = std::fs::File::open(&path).unwrap();
        let mut stream = StreamDecoder::new(source_file.as_raw_fd(), "wav").unwrap();
        stream.configure(48_000, &[0.0; 31]).unwrap();
        let format = crate::usb_pcm::PcmFormat {
            encoding: crate::usb_pcm::PcmEncoding::SignedInteger,
            sample_rate: 48_000, channels: 2, valid_bits: 16,
            subslot_bytes: 2, signed: true, little_endian: true,
        };
        let actual = stream.read_usb_pcm(3, 1.0, format).unwrap();
        let expected: Vec<u8> = samples.iter().flat_map(|sample| sample.to_le_bytes()).collect();
        assert_eq!(actual, expected);
        assert!(stream.native_pcm_active());

        // Simulate a decoder classified as lossy: it must use the same USB
        // producer's float32 conversion path rather than native integer packing.
        let float_source = std::fs::File::open(&path).unwrap();
        let mut float_stream = StreamDecoder::new(float_source.as_raw_fd(), "wav").unwrap();
        float_stream.lossless_source = false;
        float_stream.configure(48_000, &[0.0; 31]).unwrap();
        let float_actual = float_stream.read_usb_pcm(3, 1.0, format).unwrap();
        assert_eq!(float_actual, expected);
        assert!(!float_stream.native_pcm_active());

        drop(float_stream);
        drop(float_source);
        drop(stream);
        drop(source_file);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn ieee_float32_wav_reaches_usb_payload_bit_exact() {
        let path = std::env::temp_dir().join(format!("flowery-ieee-float32-test-{}.wav", std::process::id()));
        let patterns = [0x8000_0000_u32, 0x3f80_0000, 0x7fc0_1234, 0x0000_0001, 0xbf00_0000, 0x0000_0000];
        let data_bytes = (patterns.len() * 4) as u32;
        {
            let mut file = std::fs::File::create(&path).unwrap();
            file.write_all(b"RIFF").unwrap();
            file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
            file.write_all(b"WAVEfmt ").unwrap();
            file.write_all(&16_u32.to_le_bytes()).unwrap();
            file.write_all(&3_u16.to_le_bytes()).unwrap();
            file.write_all(&2_u16.to_le_bytes()).unwrap();
            file.write_all(&48_000_u32.to_le_bytes()).unwrap();
            file.write_all(&(48_000_u32 * 8).to_le_bytes()).unwrap();
            file.write_all(&8_u16.to_le_bytes()).unwrap();
            file.write_all(&32_u16.to_le_bytes()).unwrap();
            file.write_all(b"data").unwrap();
            file.write_all(&data_bytes.to_le_bytes()).unwrap();
            for bits in patterns { file.write_all(&bits.to_le_bytes()).unwrap(); }
        }

        let source_file = std::fs::File::open(&path).unwrap();
        let mut stream = StreamDecoder::new(source_file.as_raw_fd(), "wav").unwrap();
        assert_eq!(stream.source_bits, 32);
        assert!(stream.is_ieee_float32_source());
        stream.configure(48_000, &[0.0; 31]).unwrap();
        let format = crate::usb_pcm::PcmFormat {
            encoding: crate::usb_pcm::PcmEncoding::IeeeFloat32,
            sample_rate: 48_000, channels: 2, valid_bits: 32,
            subslot_bytes: 4, signed: false, little_endian: true,
        };
        let actual = stream.read_usb_pcm(3, 1.0, format).unwrap();
        let expected: Vec<u8> = patterns.iter().flat_map(|bits| bits.to_le_bytes()).collect();
        assert_eq!(actual, expected);
        assert!(stream.native_pcm_active());

        drop(stream);
        drop(source_file);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn uncompressed_wav_24_and_32_bit_pcm_reach_usb_bytes_bit_exact() {
        for bits in [24_u16, 32_u16] {
            let path = std::env::temp_dir().join(format!("flowery-native-pcm-{bits}-{}.wav", std::process::id()));
            let values = if bits == 24 {
                vec![-8_388_608_i32, -1, 0, 8_388_607]
            } else {
                vec![i32::MIN, -1, 0, i32::MAX]
            };
            let subslot = (bits / 8) as usize;
            let data_bytes = (values.len() * subslot) as u32;
            {
                let mut file = std::fs::File::create(&path).unwrap();
                file.write_all(b"RIFF").unwrap();
                file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
                file.write_all(b"WAVEfmt ").unwrap();
                file.write_all(&16_u32.to_le_bytes()).unwrap();
                file.write_all(&1_u16.to_le_bytes()).unwrap();
                file.write_all(&2_u16.to_le_bytes()).unwrap();
                file.write_all(&48_000_u32.to_le_bytes()).unwrap();
                file.write_all(&(48_000_u32 * 2 * subslot as u32).to_le_bytes()).unwrap();
                file.write_all(&((2 * subslot) as u16).to_le_bytes()).unwrap();
                file.write_all(&bits.to_le_bytes()).unwrap();
                file.write_all(b"data").unwrap();
                file.write_all(&data_bytes.to_le_bytes()).unwrap();
                for value in &values {
                    let bytes = value.to_le_bytes();
                    file.write_all(&bytes[..subslot]).unwrap();
                }
            }

            let source_file = std::fs::File::open(&path).unwrap();
            let mut stream = StreamDecoder::new(source_file.as_raw_fd(), "wav").unwrap();
            assert_eq!(stream.source_bits, bits as u8);
            stream.configure(48_000, &[0.0; 31]).unwrap();
            let format = crate::usb_pcm::PcmFormat {
                encoding: crate::usb_pcm::PcmEncoding::SignedInteger,
                sample_rate: 48_000, channels: 2, valid_bits: bits as u8,
                subslot_bytes: subslot as u8, signed: true, little_endian: true,
            };
            let actual = stream.read_usb_pcm(2, 1.0, format).unwrap();
            let expected: Vec<u8> = values.iter().flat_map(|value| value.to_le_bytes()[..subslot].to_vec()).collect();
            assert_eq!(actual, expected, "{bits}-bit WAV samples changed in the USB path");
            assert!(stream.native_pcm_active());

            if bits == 24 {
                let float_source = std::fs::File::open(&path).unwrap();
                let mut float_stream = StreamDecoder::new(float_source.as_raw_fd(), "wav").unwrap();
                float_stream.lossless_source = false;
                float_stream.configure(48_000, &[0.0; 31]).unwrap();
                let padded_format = crate::usb_pcm::PcmFormat {
                    encoding: crate::usb_pcm::PcmEncoding::SignedInteger,
                    sample_rate: 48_000, channels: 2, valid_bits: 24,
                    subslot_bytes: 4, signed: true, little_endian: true,
                };
                let float_actual = float_stream.read_usb_pcm(2, 1.0, padded_format).unwrap();
                let float_expected: Vec<u8> = values.iter()
                    .flat_map(|value| (*value).wrapping_shl(8).to_le_bytes())
                    .collect();
                assert_eq!(float_actual, float_expected, "float32 fallback did not left-justify padded 24-bit PCM");
                assert!(!float_stream.native_pcm_active());
                drop(float_stream);
                drop(float_source);
            }

            drop(stream);
            drop(source_file);
            std::fs::remove_file(path).unwrap();
        }
    }

}


#[cfg(test)]
mod usb_seek_regression_tests {
    use super::StreamDecoder;
    use crate::usb_pcm::{PcmEncoding, PcmFormat};
    use std::io::Write;
    use std::os::fd::AsRawFd;

    #[test]
    fn usb_pcm_seek_after_native_decode_resumes_at_requested_frame() {
        let path = std::env::temp_dir().join(format!(
            "flowery-usb-seek-regression-{}.wav",
            std::process::id()
        ));
        let samples: Vec<(i16, i16)> = (0..256)
            .map(|frame| (frame as i16, -(frame as i16)))
            .collect();
        let data_bytes = (samples.len() * 4) as u32;
        {
            let mut file = std::fs::File::create(&path).unwrap();
            file.write_all(b"RIFF").unwrap();
            file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
            file.write_all(b"WAVEfmt ").unwrap();
            file.write_all(&16_u32.to_le_bytes()).unwrap();
            file.write_all(&1_u16.to_le_bytes()).unwrap();
            file.write_all(&2_u16.to_le_bytes()).unwrap();
            file.write_all(&48_000_u32.to_le_bytes()).unwrap();
            file.write_all(&(48_000_u32 * 4).to_le_bytes()).unwrap();
            file.write_all(&4_u16.to_le_bytes()).unwrap();
            file.write_all(&16_u16.to_le_bytes()).unwrap();
            file.write_all(b"data").unwrap();
            file.write_all(&data_bytes.to_le_bytes()).unwrap();
            for (left, right) in &samples {
                file.write_all(&left.to_le_bytes()).unwrap();
                file.write_all(&right.to_le_bytes()).unwrap();
            }
        }

        let source = std::fs::File::open(&path).unwrap();
        let mut decoder = StreamDecoder::new(source.as_raw_fd(), "wav").unwrap();
        decoder.configure(48_000, &[0.0; 31]).unwrap();
        let format = PcmFormat {
            encoding: PcmEncoding::SignedInteger,
            sample_rate: 48_000,
            channels: 2,
            valid_bits: 16,
            subslot_bytes: 2,
            signed: true,
            little_endian: true,
        };

        let first = decoder.read_usb_pcm(4, 1.0, format).unwrap();
        let expected_first: Vec<u8> = samples[..4]
            .iter()
            .flat_map(|(left, right)| left.to_le_bytes().into_iter().chain(right.to_le_bytes()))
            .collect();
        assert_eq!(first, expected_first);
        assert!(decoder.native_pcm_active());

        assert_eq!(decoder.seek_to(1).unwrap(), 1);
        let after_seek = decoder.read_usb_pcm(4, 1.0, format).unwrap();
        let expected_after_seek: Vec<u8> = samples[48..52]
            .iter()
            .flat_map(|(left, right)| left.to_le_bytes().into_iter().chain(right.to_le_bytes()))
            .collect();
        assert_eq!(after_seek, expected_after_seek);
        assert!(decoder.native_pcm_active());

        drop(decoder);
        drop(source);
        std::fs::remove_file(path).unwrap();
    }
}


#[cfg(test)]
mod eof_boundary_regression_tests {
    use super::StreamDecoder;
    use std::io::Write;
    use std::os::fd::AsRawFd;

    fn write_stereo_pcm_wav(path: &std::path::Path, sample_rate: u32, frames: u32) {
        let data_bytes = frames * 4;
        let mut file = std::fs::File::create(path).unwrap();
        file.write_all(b"RIFF").unwrap();
        file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
        file.write_all(b"WAVEfmt ").unwrap();
        file.write_all(&16_u32.to_le_bytes()).unwrap();
        file.write_all(&1_u16.to_le_bytes()).unwrap();
        file.write_all(&2_u16.to_le_bytes()).unwrap();
        file.write_all(&sample_rate.to_le_bytes()).unwrap();
        file.write_all(&(sample_rate * 4).to_le_bytes()).unwrap();
        file.write_all(&4_u16.to_le_bytes()).unwrap();
        file.write_all(&16_u16.to_le_bytes()).unwrap();
        file.write_all(b"data").unwrap();
        file.write_all(&data_bytes.to_le_bytes()).unwrap();
        for frame in 0..frames {
            let sample = (frame % 30_000) as i16;
            file.write_all(&sample.to_le_bytes()).unwrap();
            file.write_all(&sample.to_le_bytes()).unwrap();
        }
    }

    #[test]
    fn resampled_short_stream_emits_exact_converted_frame_count_at_eof() {
        let path = std::env::temp_dir().join(format!(
            "flowery-resampled-eof-{}.wav",
            std::process::id()
        ));
        write_stereo_pcm_wav(&path, 44_100, 441);

        let source = std::fs::File::open(&path).unwrap();
        let mut decoder = StreamDecoder::new(source.as_raw_fd(), "wav").unwrap();
        decoder.configure(48_000, &[0.0; 31]).unwrap();

        let mut total_frames = 0_usize;
        loop {
            let chunk = decoder.read_frames(17).unwrap();
            if chunk.is_empty() {
                break;
            }
            assert_eq!(chunk.len() % 2, 0, "decoder emitted a partial stereo frame");
            total_frames += chunk.len() / 2;
        }
        assert_eq!(total_frames, 480, "resampler truncated or extended the final stream");
        assert!(decoder.read_frames(17).unwrap().is_empty(), "EOF must remain stable on repeated reads");

        drop(decoder);
        drop(source);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn native_pcm_usb_reader_drains_final_partial_chunk_once_then_stays_at_eof() {
        let path = std::env::temp_dir().join(format!(
            "flowery-native-usb-eof-{}.wav",
            std::process::id()
        ));
        write_stereo_pcm_wav(&path, 48_000, 6);

        let source = std::fs::File::open(&path).unwrap();
        let mut decoder = StreamDecoder::new(source.as_raw_fd(), "wav").unwrap();
        decoder.configure(48_000, &[0.0; 31]).unwrap();
        let format = crate::usb_pcm::PcmFormat {
            encoding: crate::usb_pcm::PcmEncoding::SignedInteger,
            sample_rate: 48_000,
            channels: 2,
            valid_bits: 16,
            subslot_bytes: 2,
            signed: true,
            little_endian: true,
        };

        let first = decoder.read_usb_pcm(4, 1.0, format).unwrap();
        let final_chunk = decoder.read_usb_pcm(4, 1.0, format).unwrap();
        let after_eof = decoder.read_usb_pcm(4, 1.0, format).unwrap();
        assert_eq!(first.len(), 4 * format.bytes_per_frame());
        assert_eq!(final_chunk.len(), 2 * format.bytes_per_frame());
        assert!(after_eof.is_empty(), "native PCM EOF must not repeat or synthesize samples");

        drop(decoder);
        drop(source);
        std::fs::remove_file(path).unwrap();
    }
}


#[cfg(test)]
mod chunk_boundary_continuity_tests {
    use super::StreamDecoder;
    use std::io::Write;
    use std::os::fd::AsRawFd;

    fn write_test_wav(path: &std::path::Path) {
        let frames = 8_192_u32;
        let sample_rate = 44_100_u32;
        let data_bytes = frames * 4;
        let mut file = std::fs::File::create(path).unwrap();
        file.write_all(b"RIFF").unwrap();
        file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
        file.write_all(b"WAVEfmt ").unwrap();
        file.write_all(&16_u32.to_le_bytes()).unwrap();
        file.write_all(&1_u16.to_le_bytes()).unwrap();
        file.write_all(&2_u16.to_le_bytes()).unwrap();
        file.write_all(&sample_rate.to_le_bytes()).unwrap();
        file.write_all(&(sample_rate * 4).to_le_bytes()).unwrap();
        file.write_all(&4_u16.to_le_bytes()).unwrap();
        file.write_all(&16_u16.to_le_bytes()).unwrap();
        file.write_all(b"data").unwrap();
        file.write_all(&data_bytes.to_le_bytes()).unwrap();
        for frame in 0..frames {
            let left = ((frame * 97) % 30_000) as i16 - 15_000;
            let right = ((frame * 193) % 28_000) as i16 - 14_000;
            file.write_all(&left.to_le_bytes()).unwrap();
            file.write_all(&right.to_le_bytes()).unwrap();
        }
    }

    fn render(path: &std::path::Path, request_frames: usize) -> Vec<f32> {
        let source = std::fs::File::open(path).unwrap();
        let mut decoder = StreamDecoder::new(source.as_raw_fd(), "wav").unwrap();
        let mut gains = [0.0_f32; 31];
        gains[10] = 3.0;
        gains[20] = -2.0;
        decoder.configure(48_000, &gains).unwrap();

        let mut rendered = Vec::new();
        loop {
            let chunk = decoder.read_frames(request_frames).unwrap();
            if chunk.is_empty() {
                break;
            }
            assert_eq!(chunk.len() % 2, 0, "decoder split an interleaved stereo frame");
            rendered.extend(chunk);
        }
        rendered
    }

    #[test]
    fn resampler_and_equalizer_output_is_independent_of_consumer_chunk_size() {
        let path = std::env::temp_dir().join(format!(
            "flowery-chunk-continuity-{}.wav",
            std::process::id()
        ));
        write_test_wav(&path);

        let small_reads = render(&path, 7);
        let irregular_reads = render(&path, 127);
        let large_reads = render(&path, 1_024);

        assert!(!small_reads.is_empty());
        assert_eq!(small_reads.len(), irregular_reads.len());
        assert_eq!(small_reads.len(), large_reads.len());
        assert_eq!(small_reads, irregular_reads,
            "resampler/EQ state changed with irregular consumer chunk boundaries");
        assert_eq!(small_reads, large_reads,
            "resampler/EQ state changed with large consumer chunk boundaries");

        drop(small_reads);
        drop(irregular_reads);
        drop(large_reads);
        std::fs::remove_file(path).unwrap();
    }
}


#[cfg(test)]
mod seek_dsp_state_regression_tests {
    use super::StreamDecoder;
    use std::io::Write;
    use std::os::fd::AsRawFd;

    fn write_seek_signal(path: &std::path::Path) {
        let frames = 48_000_u32;
        let sample_rate = 48_000_u32;
        let data_bytes = frames * 4;
        let mut file = std::fs::File::create(path).unwrap();
        file.write_all(b"RIFF").unwrap();
        file.write_all(&(36_u32 + data_bytes).to_le_bytes()).unwrap();
        file.write_all(b"WAVEfmt ").unwrap();
        file.write_all(&16_u32.to_le_bytes()).unwrap();
        file.write_all(&1_u16.to_le_bytes()).unwrap();
        file.write_all(&2_u16.to_le_bytes()).unwrap();
        file.write_all(&sample_rate.to_le_bytes()).unwrap();
        file.write_all(&(sample_rate * 4).to_le_bytes()).unwrap();
        file.write_all(&4_u16.to_le_bytes()).unwrap();
        file.write_all(&16_u16.to_le_bytes()).unwrap();
        file.write_all(b"data").unwrap();
        file.write_all(&data_bytes.to_le_bytes()).unwrap();
        for frame in 0..frames {
            let phase = frame as f32 * 0.031;
            let left = (phase.sin() * 24_000.0) as i16;
            let right = ((phase * 0.73).sin() * 20_000.0) as i16;
            file.write_all(&left.to_le_bytes()).unwrap();
            file.write_all(&right.to_le_bytes()).unwrap();
        }
    }

    fn configured_decoder_at_rate(
        path: &std::path::Path,
        output_rate: usize,
    ) -> (std::fs::File, StreamDecoder) {
        let source = std::fs::File::open(path).unwrap();
        let mut decoder = StreamDecoder::new(source.as_raw_fd(), "wav").unwrap();
        let mut gains = [0.0_f32; 31];
        gains[8] = 5.0;
        gains[17] = -4.0;
        gains[24] = 3.0;
        decoder.configure(output_rate, &gains).unwrap();
        (source, decoder)
    }

    fn configured_decoder(path: &std::path::Path) -> (std::fs::File, StreamDecoder) {
        configured_decoder_at_rate(path, 48_000)
    }

    #[test]
    fn seek_resets_eq_history_and_discards_pre_seek_pcm_state() {
        let path = std::env::temp_dir().join(format!(
            "flowery-seek-eq-state-{}.wav",
            std::process::id()
        ));
        write_seek_signal(&path);

        let (source_after_playback, mut after_playback) = configured_decoder(&path);
        let priming = after_playback.read_frames(4_096).unwrap();
        assert_eq!(priming.len(), 8_192);
        assert_eq!(after_playback.seek_to(500).unwrap(), 500);
        let resumed_after_seek = after_playback.read_frames(256).unwrap();

        let (source_fresh, mut fresh) = configured_decoder(&path);
        assert_eq!(fresh.seek_to(500).unwrap(), 500);
        let resumed_fresh = fresh.read_frames(256).unwrap();

        assert_eq!(resumed_after_seek, resumed_fresh,
            "seek output retained EQ history or pending PCM from the previous playback position");

        // Seeking to the presentation end must drain immediately, and a later
        // backward seek must clear the decoder's EOF state and resume normally.
        assert_eq!(after_playback.seek_to(1_000).unwrap(), 1_000);
        assert!(after_playback.read_frames(256).unwrap().is_empty(),
            "seeking to the track duration must not emit samples beyond EOF");
        assert_eq!(after_playback.seek_to(500).unwrap(), 500);
        assert_eq!(after_playback.read_frames(256).unwrap(), resumed_fresh,
            "a backward seek after EOF must reset EOF and DSP state");

        drop(after_playback);
        drop(fresh);
        drop(source_after_playback);
        drop(source_fresh);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn resampled_seek_rebuilds_rubato_and_eq_state() {
        let path = std::env::temp_dir().join(format!(
            "flowery-resampled-seek-state-{}.wav",
            std::process::id()
        ));
        write_seek_signal(&path);

        let (source_after_playback, mut after_playback) =
            configured_decoder_at_rate(&path, 44_100);
        let priming = after_playback.read_frames(4_096).unwrap();
        assert_eq!(priming.len(), 8_192);
        assert_eq!(after_playback.seek_to(500).unwrap(), 500);
        let resumed_after_seek = after_playback.read_frames(256).unwrap();

        let (source_fresh, mut fresh) = configured_decoder_at_rate(&path, 44_100);
        assert_eq!(fresh.seek_to(500).unwrap(), 500);
        let resumed_fresh = fresh.read_frames(256).unwrap();

        assert_eq!(resumed_after_seek.len(), 512);
        assert_eq!(
            resumed_after_seek, resumed_fresh,
            "resampled seek retained prior Rubato or EQ state"
        );

        assert_eq!(after_playback.seek_to(1_000).unwrap(), 1_000);
        assert!(
            after_playback.read_frames(256).unwrap().is_empty(),
            "resampled seek to the presentation duration must not emit samples beyond EOF"
        );
        assert_eq!(after_playback.seek_to(500).unwrap(), 500);
        assert_eq!(
            after_playback.read_frames(256).unwrap(),
            resumed_fresh,
            "resampled backward seek after EOF must rebuild Rubato and EQ state"
        );

        drop(after_playback);
        drop(fresh);
        drop(source_after_playback);
        drop(source_fresh);
        std::fs::remove_file(path).unwrap();
    }
}
