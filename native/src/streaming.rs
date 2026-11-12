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

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use symphonia::core::audio::SampleBuffer;
use crate::pcm_format::quantize_signed_pcm;
use symphonia::core::codecs::{Decoder, DecoderOptions};
use symphonia::core::errors::Error;
use symphonia::core::formats::{FormatOptions, FormatReader, SeekMode, SeekTo};
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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



pub(super) struct StreamDecoder {
    format: Box<dyn FormatReader>,
    decoder: Option<Box<dyn Decoder>>,
    track_id: u32,
    source_rate: usize,
    source_bits: u8,
    channels: usize,
    input_pending: Vec<f32>,
    eof: bool,
    duration_ms: u64,
    time_base: Option<symphonia::core::units::TimeBase>,
    discard_source_frames: u64,
    consecutive_decode_errors: u32,
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
        let decoder = Some(symphonia::default::get_codecs().make(&params, &DecoderOptions::default())
            .map_err(|error| format!("Unsupported audio codec: {error}"))?);
        Ok(Self {
            format, decoder, track_id, source_rate, source_bits, channels,
            input_pending: Vec::new(), eof: false,
            duration_ms, time_base, discard_source_frames: 0,
            consecutive_decode_errors: 0,
        })
    }

    pub(super) fn seek_to(&mut self, position_ms: u64) -> Result<u64,String> {
        let requested_ms = if self.duration_ms > 0 { position_ms.min(self.duration_ms) } else { position_ms };
        let seeked = self.format.seek(
            SeekMode::Accurate,
            SeekTo::Time { time: Duration::from_millis(requested_ms).into(), track_id: Some(self.track_id) }
        ).map_err(|e| format!("Container seek failed: {e}"))?;
        if let Some(decoder) = self.decoder.as_mut() { decoder.reset(); }
        self.input_pending.clear();
        self.eof = false;
        self.consecutive_decode_errors = 0;
        let actual_ms = timestamp_to_milliseconds(seeked.actual_ts, self.time_base, requested_ms);
        let actual_source_frames = milliseconds_to_frames(actual_ms, self.source_rate);
        let requested_source_frames = milliseconds_to_frames(requested_ms, self.source_rate);
        self.discard_source_frames = requested_source_frames.saturating_sub(actual_source_frames);
        Ok(requested_ms)
    }


    pub(super) fn playback_metadata(&self) -> (usize, usize, u8) {
        (self.source_rate, self.channels, self.source_bits)
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
                    if let Some(decoder) = self.decoder.as_mut() { decoder.reset(); }
                    continue;
                }
                Err(Error::IoError(error)) => return Err(format!("Audio stream I/O failed: {error}")),
                Err(e) => return Err(format!("Audio packet read failed: {e}")),
            };
            if packet.track_id() != self.track_id { continue; }
            let decoder = self.decoder.as_mut().ok_or_else(|| "No PCM decoder is registered for this codec".to_string())?;
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
                    self.input_pending.extend_from_slice(&samples);
                    return Ok(true);
                }
                Err(Error::DecodeError(reason)) => {
                    self.consecutive_decode_errors = self.consecutive_decode_errors.saturating_add(1);
                    if self.consecutive_decode_errors >= 8 {
                        return Err(format!(
                            "Audio decoder rejected 8 consecutive packets; last error: {reason}",
                        ));
                    }
                    continue;
                }
                Err(e) => return Err(format!("Audio packet decode failed: {e}")),
            }
        }
    }

    fn read_frames(&mut self, requested: usize) -> Result<Vec<f32>,String> {
        while self.input_pending.is_empty() && !self.eof { self.decode_packet()?; }
        if self.input_pending.is_empty() { return Ok(Vec::new()); }
        let take = (requested * self.channels).min(self.input_pending.len());
        let samples: Vec<f32> = self.input_pending.drain(..take).collect();
        Ok(samples)
    }




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
    let source_rate = decoder.lock().unwrap_or_else(|error| error.into_inner()).source_rate;
    if source_rate == 0 {
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
        source_rate,
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
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetStreamInfo(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jlong {
    let Some(decoder) = decoder_ref(handle) else { return 0; };
    let decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
    (((decoder.source_rate as u64)<<32) | decoder.channels as u64) as jlong
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
    let (source_rate, duration_ms) = {
        let guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
        (guard.source_rate, guard.duration_ms)
    };
    if source_rate == 0 {
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
    let start_frame = milliseconds_to_frames(actual_start, source_rate);
    let end_frame = milliseconds_to_frames(effective_end.max(actual_start), source_rate);
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

}