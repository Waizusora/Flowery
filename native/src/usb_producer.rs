use crate::streaming::StreamDecoder;
use crate::usb_pcm::{queue_ref, PcmBlock, PcmFormat, UsbPcmQueue};
use jni::objects::JClass;
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;
use std::sync::Mutex;
use std::time::Duration;

const USB_DECODE_CHUNK_FRAMES: usize = 32_768;

struct ProducerState {
    decoder: *mut StreamDecoder,
    queue: &'static UsbPcmQueue,
    source_rate: u64,
    channels: usize,
    format: PcmFormat,
    segment_start_ms: u64,
    segment_end_ms: Option<u64>,
    segment_remaining_frames: Option<u64>,
    position_frames: u64,
    epoch: i32,
    at_end: bool,
    pending_seek_ms: Option<u64>,
    pending_block: Option<PcmBlock>,
}

// The decoder and queue pointers are borrowed from handles owned by FloweryPlayer.
// The caller must stop and join its producer worker before destroying either handle.
struct UsbPcmProducer {
    state: Mutex<ProducerState>,
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
    source_rate: jint,
    channels: jint,
    bit_resolution: jint,
    subslot_bytes: jint,
    segment_start_ms: jlong,
    segment_end_ms: jlong,
) -> jlong {
    if decoder_handle == 0 || source_rate <= 0 || channels != 2
        || !(8..=32).contains(&bit_resolution) || !(1..=4).contains(&subslot_bytes)
        || bit_resolution > subslot_bytes * 8
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
    let end = (segment_end_ms >= 0).then_some(segment_end_ms as u64);
    let start = segment_start_ms as u64;
    let remaining = end.map(|value| frames_at_ms(value.saturating_sub(start), source_rate as u64));
    let format = PcmFormat { sample_rate: source_rate as u32, channels: channels as u16, valid_bits: bit_resolution as u8, subslot_bytes: subslot_bytes as u8, signed: true, little_endian: true };
    if format.validate().is_err() || format.sample_rate != source_rate as u32 { return 0; }
    let producer = UsbPcmProducer {
        state: Mutex::new(ProducerState {
            decoder: decoder_handle as *mut StreamDecoder,
            queue,
            source_rate: source_rate as u64,
            channels: channels as usize,
            format,
            segment_start_ms: start,
            segment_end_ms: end,
            segment_remaining_frames: remaining,
            position_frames: 0,
            epoch: 0,
            at_end: false,
            pending_seek_ms: None,
            pending_block: None,
        }),
    };
    Box::into_raw(Box::new(producer)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeRequestUsbPcmProducerSeek(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    position_ms: jlong,
) {
    if handle == 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB seek position");
        return;
    }
    let producer = unsafe { &*(handle as *const UsbPcmProducer) };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    state.pending_seek_ms = Some(position_ms as u64);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePumpUsbPcmProducer(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    target_gain: jfloat,
) -> jint {
    if handle == 0 || !target_gain.is_finite() {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid direct USB producer handle or gain");
        return -2;
    }
    let producer = unsafe { &*(handle as *const UsbPcmProducer) };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());

    if let Some(relative_ms) = state.pending_seek_ms.take() {
        let absolute_ms = absolute_seek_target(
            state.segment_start_ms, state.segment_end_ms, relative_ms
        );
        let decoder = unsafe { &mut *state.decoder };
        match decoder.seek_to(absolute_ms) {
            Ok(actual_ms) => {
                state.queue.clear();
                state.epoch = state.epoch.wrapping_add(1);
                state.at_end = false;
                state.pending_block = None;
                state.position_frames = frames_at_ms(actual_ms.saturating_sub(state.segment_start_ms), state.source_rate);
                state.segment_remaining_frames = state.segment_end_ms
                    .map(|end| frames_at_ms(end.saturating_sub(actual_ms), state.source_rate));
            }
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return -2;
            }
        }
    }

    if let Some(block) = state.pending_block.take() {
        let is_end = block.end_of_stream;
        if state.queue.offer(block.clone(), Duration::ZERO) {
            if is_end {
                state.at_end = true;
                return -1;
            }
            return 1;
        }
        state.pending_block = Some(block);
        return 0;
    }

    if state.at_end {
        return 0;
    }

    if state.segment_remaining_frames == Some(0) {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return -1;
        }
        state.pending_block = Some(block);
        return 0;
    }

    let decoder = unsafe { &mut *state.decoder };
    let pcm = match decoder.read_usb_pcm(USB_DECODE_CHUNK_FRAMES, target_gain, state.format) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return -2;
        }
    };
    if pcm.is_empty() {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return -1;
        }
        state.pending_block = Some(block);
        return 0;
    }

    let bytes_per_frame = state.format.bytes_per_frame();
    if pcm.len() % bytes_per_frame != 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "Rust decoder returned an incomplete packed PCM frame");
        return -2;
    }
    let decoded_frames = pcm.len() / bytes_per_frame;
    let frames = state.segment_remaining_frames
        .map(|remaining| decoded_frames.min(remaining.min(usize::MAX as u64) as usize))
        .unwrap_or(decoded_frames);
    if frames == 0 {
        let block = end_block(state.epoch, state.position_frames);
        if state.queue.offer(block.clone(), Duration::ZERO) {
            state.at_end = true;
            return -1;
        }
        state.pending_block = Some(block);
        return 0;
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
        1
    } else {
        state.pending_block = Some(block);
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyUsbPcmProducer(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut UsbPcmProducer)); }
    }
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
