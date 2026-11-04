use std::collections::VecDeque;
use std::fs::File;
use std::os::fd::FromRawFd;

use audioadapter_buffers::direct::InterleavedSlice;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jfloatArray, jint, jlong};
use jni::JNIEnv;
use rubato::{Fft, FixedSync, Resampler};
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::{Decoder, DecoderOptions};
use symphonia::core::errors::Error;
use symphonia::core::formats::{FormatOptions, FormatReader};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

const FREQUENCIES: [f32; 31] = [20.0,25.0,31.5,40.0,50.0,63.0,80.0,100.0,125.0,160.0,200.0,250.0,315.0,400.0,500.0,630.0,800.0,1000.0,1250.0,1600.0,2000.0,2500.0,3150.0,4000.0,5000.0,6300.0,8000.0,10000.0,12500.0,16000.0,20000.0];

#[derive(Clone, Copy, Default)]
struct BiquadState { x1:f32, x2:f32, y1:f32, y2:f32 }

#[derive(Clone, Copy, Default)]
struct BiquadCoefficients { b0:f32, b1:f32, b2:f32, a1:f32, a2:f32 }

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

struct StreamDecoder {
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
    resampler_flushed: bool,
    gains: [f32;31],
    coefficients: [BiquadCoefficients;31],
    eq_state: Vec<[BiquadState;31]>,
    produced_frames: u64,
    source_frames_decoded: u64,
    resampler_delay_frames: usize,
}

impl StreamDecoder {
    fn new(fd: i32, extension: &str) -> Result<Self,String> {
        let duplicate = unsafe { libc::dup(fd) };
        if duplicate < 0 { return Err("Unable to duplicate the selected document descriptor".into()); }
        let file = unsafe { File::from_raw_fd(duplicate) };
        let source = MediaSourceStream::new(Box::new(file), Default::default());
        let mut hint = Hint::new();
        if !extension.is_empty() { hint.with_extension(extension.trim_start_matches('.')); }
        let mut probed = symphonia::default::get_probe().format(&hint, source, &FormatOptions::default(), &MetadataOptions::default())
            .map_err(|e| format!("Unable to open audio stream: {e}"))?;
        let format = probed.format;
        let track = format.default_track().ok_or("No default audio track found")?;
        let track_id = track.id;
        let params = track.codec_params.clone();
        let source_rate = params.sample_rate.ok_or("Audio sample rate is unavailable")? as usize;
        let channels = params.channels.ok_or("Audio channel layout is unavailable")?.count();
        if channels == 0 || channels > 2 { return Err(format!("AAudio streaming currently supports mono/stereo, found {channels} channels")); }
        let decoder = symphonia::default::get_codecs().make(&params, &DecoderOptions::default())
            .map_err(|e| format!("Unsupported audio codec: {e}"))?;
        Ok(Self {
            format, decoder, track_id, source_rate, channels, output_rate: source_rate,
            resampler: None, input_pending: Vec::new(), output_pending: VecDeque::new(),
            eof: false, resampler_flushed: false, gains: [0.0;31],
            coefficients: [BiquadCoefficients { b0:1.0, ..BiquadCoefficients::default() };31],
            eq_state: vec![[BiquadState::default();31]; channels], produced_frames: 0, source_frames_decoded: 0, resampler_delay_frames: 0,
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

    fn decode_packet(&mut self) -> Result<bool,String> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(Error::IoError(_)) | Err(Error::ResetRequired) => { self.eof = true; return Ok(false); }
                Err(e) => return Err(format!("Audio packet read failed: {e}")),
            };
            if packet.track_id() != self.track_id { continue; }
            match self.decoder.decode(&packet) {
                Ok(decoded) => {
                    let spec = *decoded.spec();
                    if spec.channels.count() != self.channels {
                        return Err("The audio stream changed channel layout mid-track".into());
                    }
                    let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
                    buffer.copy_interleaved_ref(decoded);
                    self.source_frames_decoded += (buffer.samples().len() / self.channels) as u64;
                    self.input_pending.extend_from_slice(buffer.samples());
                    return Ok(true);
                }
                Err(Error::DecodeError(_)) => continue,
                Err(e) => return Err(format!("Audio packet decode failed: {e}")),
            }
        }
    }

    fn apply_eq(&mut self, samples: &mut [f32]) {
        for (index, sample) in samples.iter_mut().enumerate() {
            let channel = index % self.channels;
            let mut value = *sample;
            for band in 0..31 {
                let coefficients = self.coefficients[band];
                let state = &mut self.eq_state[channel][band];
                let filtered = coefficients.b0*value + coefficients.b1*state.x1 + coefficients.b2*state.x2
                    - coefficients.a1*state.y1 - coefficients.a2*state.y2;
                state.x2=state.x1; state.x1=value; state.y2=state.y1; state.y1=filtered;
                value=filtered;
            }
            *sample=value;
        }
    }

    fn produce_resampled_chunk(&mut self) -> Result<bool,String> {
        let input_frames = self.resampler.as_ref().ok_or("Resampler is not configured")?.input_frames_next();
        while self.input_pending.len() / self.channels < input_frames && !self.eof {
            self.decode_packet()?;
        }
        let available = self.input_pending.len() / self.channels;
        if available == 0 && (!self.eof || self.resampler_flushed) { return Ok(false); }
        let mut input = vec![0.0_f32; input_frames * self.channels];
        let take_frames = available.min(input_frames);
        let take_samples = take_frames * self.channels;
        input[..take_samples].copy_from_slice(&self.input_pending[..take_samples]);
        self.input_pending.drain(..take_samples);
        if take_frames < input_frames {
            self.resampler_flushed = true;
        }
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
        if self.eof && self.input_pending.is_empty() {
            let expected_frames = (self.source_frames_decoded as u128 * self.output_rate as u128
                / self.source_rate as u128) as u64;
            let remaining_frames = expected_frames.saturating_sub(self.produced_frames) as usize;
            output.truncate(output.len().min(remaining_frames * self.channels));
        }
        self.apply_eq(&mut output);
        self.produced_frames += (output.len() / self.channels) as u64;
        self.output_pending.extend(output);
        Ok(!self.output_pending.is_empty())
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
                if self.resampler_flushed { break; }
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
    if decoder.source_frames_decoded == 0 { 0 } else {
        (decoder.source_frames_decoded as u128 * 1000u128 / decoder.source_rate as u128) as jlong
    }
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

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeReadStreamChunk(
    mut env: JNIEnv, _class: JClass, handle: jlong, max_frames: jint
) -> jfloatArray {
    if handle==0 || max_frames<=0 { return std::ptr::null_mut(); }
    let decoder=unsafe { &mut *(handle as *mut StreamDecoder) };
    let samples=match decoder.read_frames(max_frames as usize) { Ok(v)=>v,Err(e)=>{let _=env.throw_new("java/lang/IllegalStateException",e);return std::ptr::null_mut();} };
    let output=match env.new_float_array(samples.len() as i32) { Ok(v)=>v,Err(_)=>return std::ptr::null_mut() };
    if env.set_float_array_region(&output,0,&samples).is_err() { return std::ptr::null_mut(); }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseStreamDecoder(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    if handle!=0 { unsafe { drop(Box::from_raw(handle as *mut StreamDecoder)); } }
}
