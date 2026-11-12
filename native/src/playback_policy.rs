use jni::objects::{JClass, JIntArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

const MIN_OUTPUT_RING_CAPACITY_FRAMES: u64 = 32_768;
const MIN_AAUDIO_QUEUE_TARGET_FRAMES: u64 = 8_192;

const ROUTE_ANDROID_AAUDIO: i32 = 0;

pub(super) fn select_playback_route() -> i32 {
    ROUTE_ANDROID_AAUDIO
}

fn select_preferred_sample_rate(source_rate: i32, supported_rates: &[i32]) -> i32 {
    if source_rate <= 0 {
        return 0;
    }
    let valid: Vec<i32> = supported_rates.iter().copied().filter(|rate| *rate > 0).collect();
    if valid.contains(&source_rate) {
        return source_rate;
    }
    valid.into_iter()
        .min_by_key(|rate| (i64::from(*rate) - i64::from(source_rate)).abs())
        .unwrap_or(source_rate)
}

fn ring_capacity_frames(sample_rate: i32) -> i32 {
    if sample_rate <= 0 {
        return 0;
    }
    let required = (sample_rate as u64)
        .saturating_mul(2)
        .max(MIN_OUTPUT_RING_CAPACITY_FRAMES);
    let capacity = required.checked_next_power_of_two().unwrap_or(i32::MAX as u64);
    capacity.min(i32::MAX as u64) as i32
}

fn frames_at_milliseconds(milliseconds: i64, sample_rate: i32) -> i64 {
    if milliseconds <= 0 || sample_rate <= 0 {
        return 0;
    }
    ((milliseconds as u128 * sample_rate as u128) / 1_000)
        .min(i64::MAX as u128) as i64
}

pub(crate) fn target_queue_frames(sample_rate: i32, capacity_frames: i32) -> usize {
    if sample_rate <= 0 || capacity_frames <= 0 {
        return 0;
    }
    ((sample_rate as u64 / 4)
        .max(MIN_AAUDIO_QUEUE_TARGET_FRAMES)
        .min(capacity_frames as u64)) as usize
}

fn low_watermark_frames(sample_rate: i32, capacity_frames: i32) -> i32 {
    if sample_rate <= 0 || capacity_frames <= 0 {
        return 0;
    }
    let target = target_queue_frames(sample_rate, capacity_frames) as u64;
    let requested = ((sample_rate as u64) / 10).max(2_048);
    requested.min(target).min((capacity_frames as u64) / 2) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSelectPreferredSampleRate(
    env: JNIEnv, _class: JClass, source_rate: jint, rates: JIntArray,
) -> jint {
    let length = match env.get_array_length(&rates) {
        Ok(value) => value as usize,
        Err(_) => return 0,
    };
    let mut values = vec![0_i32; length];
    if env.get_int_array_region(&rates, 0, &mut values).is_err() {
        return 0;
    }
    select_preferred_sample_rate(source_rate, &values)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeRingCapacityFrames(
    _env: JNIEnv, _class: JClass, sample_rate: jint,
) -> jint {
    ring_capacity_frames(sample_rate)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeFramesAtMilliseconds(
    _env: JNIEnv, _class: JClass, milliseconds: jlong, sample_rate: jint,
) -> jlong {
    frames_at_milliseconds(milliseconds, sample_rate)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackLowWatermarkFrames(
    _env: JNIEnv, _class: JClass, sample_rate: jint, capacity_frames: jint,
) -> jint {
    low_watermark_frames(sample_rate, capacity_frames)
}

#[cfg(test)]
mod tests {
    use super::{
        frames_at_milliseconds, low_watermark_frames, ring_capacity_frames,
        select_preferred_sample_rate, target_queue_frames,
    };

    #[test]
    fn prefers_the_source_rate_and_otherwise_selects_the_nearest_supported_rate() {
        assert_eq!(select_preferred_sample_rate(44_100, &[48_000, 44_100, 96_000]), 44_100);
        assert_eq!(select_preferred_sample_rate(48_000, &[44_100, 96_000]), 44_100);
        assert_eq!(select_preferred_sample_rate(48_000, &[]), 48_000);
        assert_eq!(select_preferred_sample_rate(0, &[44_100]), 0);
    }

    #[test]
    fn ring_capacity_is_power_of_two_and_covers_two_seconds() {
        assert_eq!(ring_capacity_frames(44_100), 131_072);
        assert_eq!(ring_capacity_frames(48_000), 131_072);
        assert_eq!(ring_capacity_frames(0), 0);
    }

    #[test]
    fn frame_and_low_watermark_calculations_are_bounded() {
        assert_eq!(frames_at_milliseconds(1_000, 44_100), 44_100);
        assert_eq!(frames_at_milliseconds(i64::MAX, 192_000), i64::MAX);
        assert_eq!(target_queue_frames(44_100, 131_072), 11_025);
        assert_eq!(target_queue_frames(48_000, 131_072), 12_000);
        assert_eq!(target_queue_frames(8_000, 32_768), 8_192);
        assert_eq!(low_watermark_frames(48_000, 131_072), 4_800);
        assert_eq!(low_watermark_frames(48_000, 32_768), 4_800);
        assert_eq!(low_watermark_frames(192_000, 524_288), 19_200);
    }
}
