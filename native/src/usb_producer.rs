use crate::streaming::{decoder_ref, StreamDecoder};
use crate::playback_session::{session_ref, SessionState};
use crate::usb_pcm::{queue_ref, PcmBlock, PcmEncoding, PcmFormat, UsbPcmQueue};
use jni::objects::JClass;
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicU32, AtomicU64, AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

const USB_DECODE_CHUNK_FRAMES: usize = 32_768;

struct ProducerState {
    decoder: Arc<Mutex<StreamDecoder>>,
    session: Arc<SessionState>,
    queue: Arc<UsbPcmQueue>,
    format: PcmFormat,
    segment_start_ms: u64,
    segment_end_ms: Option<u64>,
    segment_remaining_frames: Option<u64>,
    position_frames: u64,
    epoch: i32,
    at_end: bool,
    pending_seek_ms: Option<u64>,
    pending_block: Option<PcmBlock>,
    bit_perfect_active: bool,
}

struct UsbPcmProducer {
    state: Mutex<ProducerState>,
    worker: Mutex<Option<JoinHandle<()>>>,
    worker_cancelled: AtomicBool,
    target_gain_bits: AtomicU32,
    worker_error: Mutex<Option<String>>,
}

static NEXT_PRODUCER_HANDLE: AtomicU64 = AtomicU64::new(1);
static PRODUCERS: OnceLock<Mutex<HashMap<i64, Arc<UsbPcmProducer>>>> = OnceLock::new();

fn producer_registry() -> &'static Mutex<HashMap<i64, Arc<UsbPcmProducer>>> {
    PRODUCERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn producer_ref(handle: jlong) -> Option<Arc<UsbPcmProducer>> {
    if handle <= 0 { return None; }
    producer_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn register_producer(producer: UsbPcmProducer) -> jlong {
    let producer = Arc::new(producer);
    loop {
        let candidate = NEXT_PRODUCER_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = producer_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::clone(&producer));
            return handle as jlong;
        }
    }
}

fn destroy_producer(handle: jlong) {
    if handle > 0 {
        if let Some(producer) = producer_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle) {
            producer.worker_cancelled.store(true, Ordering::Release);
            let _ = stop_producer_worker(&producer);
            // Synchronize with any in-flight manual pump before releasing producer state.
            let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
            state.pending_block = None;
        }
    }
}

fn state_epoch(session: &SessionState) -> i32 {
    session.epoch.load(std::sync::atomic::Ordering::Acquire).min(i32::MAX as u64) as i32
}

fn frames_at_ms(milliseconds: u64, sample_rate: u64) -> u64 {
    ((milliseconds as u128 * sample_rate as u128) / 1_000)
        .min(u64::MAX as u128) as u64
}

fn absolute_seek_target(segment_start_ms: u64, segment_end_ms: Option<u64>, relative_ms: u64) -> u64 {
    let duration = segment_end_ms.map(|end| end.saturating_sub(segment_start_ms));
    segment_start_ms.saturating_add(relative_ms.min(duration.unwrap_or(u64::MAX)))
}

fn end_block(epoch: i32, position_frames: u64) -> PcmBlock {
    PcmBlock {
        pcm: Vec::new(),
        frames: 0,
        epoch,
        start_frames: position_frames.min(i64::MAX as u64) as i64,
        end_of_stream: true,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbPcmProducer(
    mut env: JNIEnv,
    _class: JClass,
    decoder_handle: jlong,
    queue_handle: jlong,
    session_handle: jlong,
    source_rate: jint,
    channels: jint,
    bit_resolution: jint,
    subslot_bytes: jint,
    segment_start_ms: jlong,
    segment_end_ms: jlong,
    encoding: jint,
) -> jlong {
    if decoder_handle == 0 || source_rate <= 0 || !(1..=8).contains(&channels)
        || !(8..=32).contains(&bit_resolution) || !(1..=4).contains(&subslot_bytes)
        || bit_resolution > subslot_bytes * 8 || !(0..=1).contains(&encoding)
        || segment_start_ms < 0 || segment_end_ms < -1
        || (segment_end_ms >= 0 && segment_end_ms < segment_start_ms)
    {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB producer configuration");
        return 0;
    }
    let Some(queue) = queue_ref(queue_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB PCM queue");
        return 0;
    };
    let Some(decoder) = decoder_ref(decoder_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid stream decoder handle");
        return 0;
    };
    let Some(session) = session_ref(session_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid playback session handle");
        return 0;
    };
    let end = (segment_end_ms >= 0).then_some(segment_end_ms as u64);
    let start = segment_start_ms as u64;
    let remaining = end.map(|value| frames_at_ms(value.saturating_sub(start), source_rate as u64));
    let format = PcmFormat { encoding: if encoding == 1 { PcmEncoding::IeeeFloat32 } else { PcmEncoding::SignedInteger },
        sample_rate: source_rate as u32, channels: channels as u16, valid_bits: bit_resolution as u8,
        subslot_bytes: subslot_bytes as u8, signed: encoding == 0, little_endian: true };
    if format.validate().is_err() { return 0; }
    let initial_epoch = state_epoch(&session);
    let producer = UsbPcmProducer {
        state: Mutex::new(ProducerState {
            decoder,
            session,
            queue,
            format,
            segment_start_ms: start,
            segment_end_ms: end,
            segment_remaining_frames: remaining,
            position_frames: 0,
            epoch: initial_epoch,
            at_end: false,
            pending_seek_ms: None,
            pending_block: None,
            bit_perfect_active: false,
        }),
        worker: Mutex::new(None),
        worker_cancelled: AtomicBool::new(false),
        target_gain_bits: AtomicU32::new(1.0_f32.to_bits()),
        worker_error: Mutex::new(None),
    };
    register_producer(producer)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeRequestUsbPcmProducerSeek(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_ms: jlong,
) {
    if handle <= 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB seek position");
        return;
    }
    let Some(producer) = producer_ref(handle) else { return; };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    state.pending_seek_ms = Some(position_ms as u64);
    state.epoch = state_epoch(&state.session);
    state.pending_block = None;
    state.queue.clear();
}

fn pump_once(producer: &UsbPcmProducer, target_gain: f32) -> Result<jint, String> {
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if state.session.cancelled.load(Ordering::Acquire) || producer.worker_cancelled.load(Ordering::Acquire) {
        return Ok(-3);
    }
    let session_epoch = state_epoch(&state.session);
    if session_epoch != state.epoch {
        state.pending_block = None;
        state.queue.clear();
        if state.pending_seek_ms.is_none() {
            return Ok(0);
        }
        state.epoch = session_epoch;
    }

    if let Some(relative_ms) = state.pending_seek_ms.take() {
        let absolute_ms = absolute_seek_target(state.segment_start_ms, state.segment_end_ms, relative_ms);
        let mut decoder = state.decoder.lock().unwrap_or_else(|error| error.into_inner());
        let actual_ms = decoder.seek_to(absolute_ms)?;
        drop(decoder);
        state.queue.clear();
        state.epoch = state_epoch(&state.session);
        state.at_end = false;
        state.pending_block = None;
        state.bit_perfect_active = false;
        state.position_frames = frames_at_ms(actual_ms.saturating_sub(state.segment_start_ms), state.format.sample_rate as u64);
        state.segment_remaining_frames = state.segment_end_ms
            .map(|end| frames_at_ms(end.saturating_sub(actual_ms), state.format.sample_rate as u64));
    }

    if let Some(block) = state.pending_block.take() {
        let is_end = block.end_of_stream;
        if state.queue.offer(block.clone(), Duration::ZERO) {
            if is_end {
                state.at_end = true;
                return Ok(-1);
            }
            return Ok(1);
        }
        state.pending_block = Some(block);
        return Ok(0);
    }

    if state.at_end { return Ok(0); }

    if state.segment_remaining_frames == Some(0) {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return Ok(-1);
        }
        state.pending_block = Some(block);
        return Ok(0);
    }

    let decoder_owner = Arc::clone(&state.decoder);
    let mut decoder = decoder_owner.lock().unwrap_or_else(|error| error.into_inner());
    let pcm = decoder.read_usb_pcm(USB_DECODE_CHUNK_FRAMES, target_gain, state.format)?;
    let bit_perfect_active = decoder.native_pcm_active();
    drop(decoder);
    state.bit_perfect_active = bit_perfect_active;
    if pcm.is_empty() {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return Ok(-1);
        }
        state.pending_block = Some(block);
        return Ok(0);
    }

    let bytes_per_frame = state.format.bytes_per_frame();
    if pcm.len() % bytes_per_frame != 0 {
        return Err("Rust decoder returned an incomplete packed PCM frame".into());
    }
    let decoded_frames = pcm.len() / bytes_per_frame;
    let frames = state.segment_remaining_frames
        .map(|remaining| decoded_frames.min(remaining.min(usize::MAX as u64) as usize))
        .unwrap_or(decoded_frames);
    if frames == 0 {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return Ok(-1);
        }
        state.pending_block = Some(block);
        return Ok(0);
    }

    let block = PcmBlock {
        pcm: pcm[..frames * bytes_per_frame].to_vec(),
        frames: frames as u32,
        epoch: state.epoch,
        start_frames: state.position_frames.min(i64::MAX as u64) as i64,
        end_of_stream: false,
    };
    state.position_frames = state.position_frames.saturating_add(frames as u64);
    if let Some(remaining) = state.segment_remaining_frames.as_mut() {
        *remaining = remaining.saturating_sub(frames as u64);
    }
    if state.queue.offer(block.clone(), Duration::ZERO) {
        Ok(1)
    } else {
        state.pending_block = Some(block);
        Ok(0)
    }
}

fn start_producer_worker(producer: Arc<UsbPcmProducer>) -> Result<(), String> {
    let mut worker = producer.worker.lock().unwrap_or_else(|error| error.into_inner());
    if worker.is_some() { return Err("USB PCM producer worker is already started".into()); }
    producer.worker_cancelled.store(false, Ordering::Release);
    *producer.worker_error.lock().unwrap_or_else(|error| error.into_inner()) = None;
    let worker_producer = Arc::clone(&producer);
    let handle = thread::Builder::new().name("flowery-rust-usb-pcm-producer".into()).spawn(move || {
        loop {
            if worker_producer.worker_cancelled.load(Ordering::Acquire) { break; }
            let gain = f32::from_bits(worker_producer.target_gain_bits.load(Ordering::Acquire));
            match pump_once(&worker_producer, gain) {
                Ok(1) => {}
                Ok(0) | Ok(-1) => thread::sleep(Duration::from_millis(2)),
                Ok(-3) => break,
                Ok(_) => {
                    *worker_producer.worker_error.lock().unwrap_or_else(|error| error.into_inner()) =
                        Some("USB PCM producer returned an invalid worker status".into());
                    break;
                }
                Err(error) => {
                    *worker_producer.worker_error.lock().unwrap_or_else(|poison| poison.into_inner()) = Some(error);
                    break;
                }
            }
        }
    }).map_err(|error| format!("Unable to start USB PCM producer worker: {error}"))?;
    *worker = Some(handle);
    Ok(())
}

fn stop_producer_worker(producer: &UsbPcmProducer) -> Result<(), String> {
    producer.worker_cancelled.store(true, Ordering::Release);
    let handle = producer.worker.lock().unwrap_or_else(|error| error.into_inner()).take();
    if let Some(handle) = handle {
        handle.join().map_err(|_| "USB PCM producer worker panicked during shutdown".to_string())?;
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartUsbPcmProducerWorker(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    let Some(producer) = producer_ref(handle) else { return -1; };
    match start_producer_worker(producer) {
        Ok(()) => 0,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetUsbPcmProducerGain(
    mut env: JNIEnv, _class: JClass, handle: jlong, gain: jfloat,
) -> jint {
    if !gain.is_finite() { let _ = env.throw_new("java/lang/IllegalArgumentException", "USB PCM producer gain must be finite"); return -1; }
    let Some(producer) = producer_ref(handle) else { return -1; };
    producer.target_gain_bits.store(gain.clamp(0.0, 1.0).to_bits(), Ordering::Release);
    0
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeUsbPcmProducerWorkerError(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jstring {
    let Some(producer) = producer_ref(handle) else { return std::ptr::null_mut(); };
    let error = producer.worker_error.lock().unwrap_or_else(|poison| poison.into_inner())
        .clone().unwrap_or_default();
    match env.new_string(error) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopUsbPcmProducerWorker(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    let Some(producer) = producer_ref(handle) else { return 0; };
    match stop_producer_worker(&producer) {
        Ok(()) => 0,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePumpUsbPcmProducer(
    mut env: JNIEnv, _class: JClass, handle: jlong, target_gain: jfloat,
) -> jint {
    if !target_gain.is_finite() {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB producer gain");
        return -2;
    }
    let Some(producer) = producer_ref(handle) else { return -2; };
    match pump_once(&producer, target_gain) {
        Ok(status) => status,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -2 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsUsbPcmProducerAtEnd(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jboolean {
    let Some(producer) = producer_ref(handle) else { return jni::sys::JNI_FALSE; };
    let state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if state.at_end { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsUsbPcmProducerBitPerfect(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jni::sys::jboolean {
    let Some(producer) = producer_ref(handle) else { return jni::sys::JNI_FALSE; };
    let state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if state.bit_perfect_active { jni::sys::JNI_TRUE } else { jni::sys::JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyUsbPcmProducer(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    destroy_producer(handle);
}

#[cfg(test)]
mod tests {
    use super::{absolute_seek_target, frames_at_ms};

    #[test]
    fn segment_frame_conversion_is_exact_for_common_audio_rates() {
        assert_eq!(frames_at_ms(1_000, 44_100), 44_100);
        assert_eq!(frames_at_ms(1_500, 48_000), 72_000);
        assert_eq!(frames_at_ms(u64::MAX, 192_000), u64::MAX);
    }

    #[test]
    fn segment_seek_is_relative_and_clamped_to_segment_end() {
        assert_eq!(absolute_seek_target(1_000, Some(4_000), 500), 1_500);
        assert_eq!(absolute_seek_target(1_000, Some(4_000), 9_000), 4_000);
        assert_eq!(absolute_seek_target(1_000, None, 500), 1_500);
    }
}


#[cfg(test)]
mod handle_tests {
    use super::*;

    #[test]
    fn pcm_producer_registry_rejects_invalid_and_unknown_handles() {
        assert!(producer_ref(0).is_none());
        assert!(producer_ref(-1).is_none());
        assert!(producer_ref(i64::MAX).is_none());
        destroy_producer(0);
        destroy_producer(-1);
    }
}
