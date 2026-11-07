use std::collections::VecDeque;
use std::fs::File;
use std::io::ErrorKind;
use std::time::Duration;
use std::os::fd::FromRawFd;

use audioadapter_buffers::direct::InterleavedSlice;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jint, jlong};
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
fn milliseconds_to_frames(milliseconds: u64, sample_rate: usize) -> u64 {
    ((milliseconds as u128 * sample_rate as u128) / 1000)
        .min(u64::MAX as u128) as u64
}

fn make_coefficients(frequency: f32, gain_db: f32, sample_rate: usize) -> BiquadCoefficients {
    if frequency >= sample_rate as f32 * 0.49 { return BiquadCoefficients { b0:1.0, ..Default::default() }; }
    let a = 10.0_f32.powf(gain_db.clamp(-12.0,12.0) / 40.0);
    let omega = 2.0 * std::f32::consts::PI * frequency / sample_rate as f32;
    let alpha = omega.sin() / (2.0 * 4.318_f32);
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

impl StreamDecoder {
    fn new(fd: i32, extension: &str) -> Result<Self,String> {
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 { return Err("Unable to duplicate the selected document descriptor".into()); }
        let file = unsafe { File::from_raw_fd(duplicate) };
        let source = MediaSourceStream::new(Box::new(file), Default::default());
        let mut hint = Hint::new();
        if !extension.is_empty() { hint.with_extension(extension.trim_start_matches('.')); }
        let probed = symphonia::default::get_probe().format(&hint, source, &FormatOptions::default(), &MetadataOptions::default())
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
        let duration_ms = params.n_frames.map(|frames| (frames as u128 * 1000u128 / source_rate as u128) as u64).unwrap_or(0);
        let channels = params.channels.ok_or("Audio channel layout is unavailable")?.count();
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
        let actual_time = self.time_base.map(|tb| tb.calc_time(seeked.actual_ts));
        let actual_ms = actual_time
            .map(|t| (t.seconds as f64 + t.frac) * 1000.0)
            .unwrap_or(requested_ms as f64)
            .max(0.0);
        let actual_source_frames = milliseconds_to_frames(actual_ms as u64, self.source_rate);
        let requested_source_frames = milliseconds_to_frames(requested_ms, self.source_rate);
        let requested_output_frames = milliseconds_to_frames(requested_ms, self.output_rate);
        let actual_output_frames = milliseconds_to_frames(actual_ms as u64, self.output_rate);
        self.source_frames_decoded = actual_source_frames;

        if self.source_mode == StreamSourceMode::EncodedPackets {
            // Encoded seeking is demuxer/frame-boundary based. No PCM DSP or
            // resampler state is configured for this exclusive source mode.
            self.produced_frames = requested_source_frames;
            self.discard_source_frames = 0;
            self.discard_output_frames = 0;
            self.resampler = None;
            self.resampler_delay_frames = 0;
            return Ok(actual_ms as u64);
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
            let fallback_decoded = decoded.clone();
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
                    // The decoder's typed output is not a supported integer
                    // representation. Preserve playback by switching this
                    // stream to the established float32 path before any
                    // native PCM block has been emitted.
                    let mut buffer = SampleBuffer::<f32>::new(fallback_decoded.capacity() as u64, *fallback_decoded.spec());
                    buffer.copy_interleaved_ref(fallback_decoded);
                    let mut samples = buffer.samples().to_vec();
                    let discard_samples = discard * self.channels;
                    if discard_samples > 0 && discard_samples <= samples.len() {
                        samples.drain(..discard_samples);
                    }
                    self.input_pending.extend_from_slice(&samples);
                    self.native_pcm_active = false;
                    self.native_pcm_decided = true;
                    let _ = error;
                    return Ok(false);
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
            crate::usb_pcm::PcmEncoding::SignedInteger => self.lossless_source
                && matches!(format.valid_bits, 8 | 16 | 20 | 24 | 32)
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

fn gains_from_java(env: &mut JNIEnv, array: &JFloatArray) -> Result<[f32;31],String> {
    let len=env.get_array_length(array).map_err(|e|e.to_string())? as usize;
    if len!=31 { return Err("31 equalizer gains are required".into()); }
    let mut values=vec![0.0_f32;31];
    env.get_float_array_region(array,0,&mut values).map_err(|e|e.to_string())?;
    let mut gains=[0.0_f32;31]; gains.copy_from_slice(&values); Ok(gains)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateStreamDecoder(
    mut env: JNIEnv, _class: JClass, fd: jint, extension: JString
) -> jlong {
    let ext=match env.get_string(&extension) { Ok(v)=>v.to_string_lossy().into_owned(),Err(_)=>return 0 };
    match StreamDecoder::new(fd,&ext) {
        Ok(decoder)=>Box::into_raw(Box::new(decoder)) as jlong,
        Err(error)=>{let _=env.throw_new("java/lang/IllegalArgumentException",error);0}
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekStreamDecoder(
    mut env: JNIEnv, _class: JClass, handle: jlong, position_ms: jlong
) -> jlong {
    if handle == 0 || position_ms < 0 { return -1; }
    let decoder = unsafe { &mut *(handle as *mut StreamDecoder) };
    match decoder.seek_to(position_ms as u64) {
        Ok(position) => position as jlong,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsIeeeFloat32Source(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jboolean {
    if handle == 0 { return jni::sys::JNI_FALSE; }
    let decoder = unsafe { &*(handle as *const StreamDecoder) };
    if decoder.is_ieee_float32_source() { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsAc3Source(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jboolean {
    if handle == 0 { return jni::sys::JNI_FALSE; }
    let decoder = unsafe { &*(handle as *const StreamDecoder) };
    if decoder.is_ac3_source() { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    if handle==0 { return 0; }
    let decoder=unsafe { &*(handle as *mut StreamDecoder) };
    (((decoder.source_rate as u64)<<32) | decoder.channels as u64) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetBitPerfectSourceInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    if handle == 0 { return 0; }
    let decoder = unsafe { &*(handle as *mut StreamDecoder) };
    let lossless = if decoder.lossless_source { 1_u64 } else { 0_u64 };
    ((lossless << 32) | decoder.source_bits as u64) as jlong
}

pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamDurationMs(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    if handle==0 { return 0; }
    let decoder=unsafe { &*(handle as *mut StreamDecoder) };
    decoder.duration_ms as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeConfigureStreamDecoder(
    mut env: JNIEnv, _class: JClass, handle: jlong, output_rate: jint, gains: JFloatArray
) -> jint {
    if handle==0 { return -1; }
    let gains=match gains_from_java(&mut env,&gains) { Ok(v)=>v,Err(e)=>{let _=env.throw_new("java/lang/IllegalArgumentException",e);return -1;} };
    let decoder=unsafe { &mut *(handle as *mut StreamDecoder) };
    match decoder.configure(output_rate as usize,&gains) { Ok(())=>0,Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);-1} }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeUpdateStreamGains(
    mut env: JNIEnv, _class: JClass, handle: jlong, gains: JFloatArray
) -> jint {
    if handle==0 { return -1; }
    let gains=match gains_from_java(&mut env,&gains) { Ok(v)=>v,Err(e)=>{let _=env.throw_new("java/lang/IllegalArgumentException",e);return -1;} };
    let decoder=unsafe { &mut *(handle as *mut StreamDecoder) };
    decoder.gains.copy_from_slice(&gains);
    decoder.coefficients = std::array::from_fn(|i| make_coefficients(FREQUENCIES[i], decoder.gains[i], decoder.output_rate));
    0
}


/// Decodes one bounded chunk and copies it directly into the Rust-owned AAudio ring.
/// Returns produced frames, 0 when the ring is full, and -1 at end of stream.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePumpStreamChunk(
    mut env: JNIEnv, _class: JClass, handle: jlong, max_frames: jint
) -> jint {
    if handle == 0 || max_frames <= 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid streaming pump arguments");
        return -2;
    }
    let available = crate::aaudio::available_output_frames();
    if available == 0 {
        return 0;
    }
    let decoder = unsafe { &mut *(handle as *mut StreamDecoder) };
    if decoder.channels > 2 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "AAudio output currently supports mono/stereo; multichannel PCM must use a validated direct USB route.",
        );
        return -2;
    }
    let frames = (max_frames as usize).min(available);
    let samples = match decoder.read_frames(frames) {
        Ok(samples) => samples,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return -2;
        }
    };
    if samples.is_empty() {
        return -1;
    }
    let channels = decoder.channels;
    match crate::aaudio::write_output_samples(&samples) {
        Ok(written) if written == samples.len() / channels => written as jint,
        Ok(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "AAudio ring accepted an incomplete decoded chunk");
            -2
        }
        Err(code) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("AAudio ring write failed: {code}"));
            -2
        }
    }
}

fn quantize_signed_pcm(sample: f32, gain: f32, valid_bits: u8) -> i64 {
    let finite_sample = if sample.is_finite() { sample } else { 0.0 };
    let scaled = (finite_sample * gain).clamp(-1.0, 1.0);
    let negative_limit = -(1_i64 << (valid_bits - 1));
    let positive_limit = (1_i64 << (valid_bits - 1)) - 1;
    let scale = (1_i64 << (valid_bits - 1)) as f64;
    ((scaled as f64 * scale).round() as i64).clamp(negative_limit, positive_limit)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseStreamDecoder(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    if handle!=0 { unsafe { drop(Box::from_raw(handle as *mut StreamDecoder)); } }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::fd::AsRawFd;

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
    fn seek_frame_conversion_saturates_instead_of_wrapping() {
        assert_eq!(milliseconds_to_frames(u64::MAX, 192_000), u64::MAX);
        assert_eq!(milliseconds_to_frames(1_000, 48_000), 48_000);
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
