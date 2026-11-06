use std::collections::VecDeque;
use std::fs::File;
use std::io::ErrorKind;
use std::time::Duration;
use std::os::fd::FromRawFd;

use audioadapter_buffers::direct::InterleavedSlice;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use rubato::{Fft, FixedSync, Resampler};
use symphonia::core::audio::SampleBuffer;
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

pub(super) struct StreamDecoder {
    format: Box<dyn FormatReader>,
    decoder: Box<dyn Decoder>,
    track_id: u32,
    source_rate: usize,
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
        let duration_ms = params.n_frames.map(|frames| (frames as u128 * 1000u128 / source_rate as u128) as u64).unwrap_or(0);
        let channels = params.channels.ok_or("Audio channel layout is unavailable")?.count();
        if channels == 0 || channels > 2 { return Err(format!("AAudio streaming currently supports mono/stereo, found {channels} channels")); }
        let decoder = symphonia::default::get_codecs().make(&params, &DecoderOptions::default())
            .map_err(|e| format!("Unsupported audio codec: {e}"))?;
        Ok(Self {
            format, decoder, track_id, source_rate, channels, output_rate: source_rate,
            resampler: None, input_pending: Vec::new(), output_pending: VecDeque::new(),
            eof: false, gains: [0.0;31],
            coefficients: [BiquadCoefficients { b0:1.0, ..BiquadCoefficients::default() };31],
            eq_state: vec![[BiquadState::default();31]; channels], produced_frames: 0, source_frames_decoded: 0, duration_ms, resampler_delay_frames: 0, time_base, discard_source_frames: 0, discard_output_frames: 0, consecutive_decode_errors: 0, usb_last_gain: -1.0,
        })
    }

    fn configure(&mut self, output_rate: usize, gains: &[f32]) -> Result<(),String> {
        if output_rate == 0 || gains.len() != 31 { return Err("Invalid streaming DSP configuration".into()); }
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
        self.decoder.reset();
        self.input_pending.clear();
        self.output_pending.clear();
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

    fn decode_packet(&mut self) -> Result<bool,String> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => {
                    self.eof = true;
                    return Ok(false);
                }
                Err(Error::ResetRequired) => {
                    self.decoder.reset();
                    continue;
                }
                Err(Error::IoError(error)) => return Err(format!("Audio stream I/O failed: {error}")),
                Err(e) => return Err(format!("Audio packet read failed: {e}")),
            };
            if packet.track_id() != self.track_id { continue; }
            match self.decoder.decode(&packet) {
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
                            self.decoder.codec_params().codec
                        ));
                    }
                    continue;
                }
                Err(e) => return Err(format!("Audio packet decode failed: {e}")),
            }
        }
    }

    fn apply_eq(&mut self, samples: &mut [f32]) {
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
    pub(super) fn read_usb_pcm24(&mut self, max_frames: usize, target_gain: f32) -> Result<Vec<u8>, String> {
        if self.channels != 2 || max_frames == 0 || !target_gain.is_finite() {
            return Err("Invalid direct USB PCM read configuration".into());
        }
        let samples = self.read_frames(max_frames)?;
        let gain = target_gain.clamp(0.0, 1.0);
        let start_gain = if self.usb_last_gain < 0.0 { gain } else { self.usb_last_gain };
        let sample_count = samples.len();
        let mut packed = Vec::with_capacity(sample_count.saturating_mul(3));
        for (index, sample) in samples.into_iter().enumerate() {
            let fraction = if sample_count <= 1 { 1.0 } else { index as f32 / sample_count as f32 };
            let ramp_gain = start_gain + (gain - start_gain) * fraction;
            let value = quantize_signed_24(sample, ramp_gain);
            packed.push(value as u8);
            packed.push((value >> 8) as u8);
            packed.push((value >> 16) as u8);
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
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    if handle==0 { return 0; }
    let decoder=unsafe { &*(handle as *mut StreamDecoder) };
    (((decoder.source_rate as u64)<<32) | decoder.channels as u64) as jlong
}

#[no_mangle]
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

fn quantize_signed_24(sample: f32, gain: f32) -> i32 {
    let finite_sample = if sample.is_finite() { sample } else { 0.0 };
    let scaled = (finite_sample * gain).clamp(-1.0, 1.0);
    if scaled <= -1.0 { -8_388_608 } else { (scaled * 8_388_607.0) as i32 }
}

/// Reads a bounded decoded chunk, applies a stateful software-gain ramp, and packs
/// stereo samples as signed 24-bit little-endian PCM for the direct USB transport.
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
    fn signed_24_bit_quantization_handles_full_scale_and_non_finite_samples() {
        assert_eq!(quantize_signed_24(-1.0, 1.0), -8_388_608);
        assert_eq!(quantize_signed_24(1.0, 1.0), 8_388_607);
        assert_eq!(quantize_signed_24(0.0, 0.5), 0);
        assert_eq!(quantize_signed_24(f32::NAN, 1.0), 0);
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
}
