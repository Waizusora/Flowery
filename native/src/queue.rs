use jni::objects::JClass;
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use std::sync::atomic::{AtomicU64, Ordering};

const ACTION_NEXT: jint = 0;
const ACTION_PREVIOUS: jint = 1;
const ACTION_COMPLETION: jint = 2;
const REPEAT_ALL: jint = 1;
const REPEAT_ONE: jint = 2;

static RANDOM_STATE: AtomicU64 = AtomicU64::new(0x9E37_79B9_7F4A_7C15);

fn next_random() -> u64 {
    let mut current = RANDOM_STATE.load(Ordering::Relaxed);
    loop {
        let mut next = current;
        next ^= next >> 12;
        next ^= next << 25;
        next ^= next >> 27;
        match RANDOM_STATE.compare_exchange_weak(
            current,
            next,
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            Ok(_) => return next.wrapping_mul(0x2545_F491_4F6C_DD1D),
            Err(observed) => current = observed,
        }
    }
}

fn shuffled_index(current: usize, size: usize) -> usize {
    let candidate = (next_random() % (size as u64 - 1)) as usize;
    if candidate >= current { candidate + 1 } else { candidate }
}

/// Returns a new index, -1 to stop, or -2 to replay the current item.
fn transition(current: i32, size: i32, shuffle: bool, repeat_mode: i32, action: i32) -> i32 {
    if size <= 0 || current < 0 || current >= size {
        return -1;
    }

    let current = current as usize;
    let size = size as usize;
    match action {
        ACTION_NEXT => {
            if shuffle && size > 1 {
                shuffled_index(current, size) as i32
            } else if current + 1 < size {
                (current + 1) as i32
            } else if repeat_mode == REPEAT_ALL {
                0
            } else {
                -1
            }
        }
        ACTION_PREVIOUS => {
            if current > 0 {
                (current - 1) as i32
            } else if repeat_mode == REPEAT_ALL {
                (size - 1) as i32
            } else {
                0
            }
        }
        ACTION_COMPLETION => {
            if repeat_mode == REPEAT_ONE {
                -2
            } else if current + 1 < size || repeat_mode == REPEAT_ALL || (shuffle && size > 1) {
                transition(current as i32, size as i32, shuffle, repeat_mode, ACTION_NEXT)
            } else {
                -1
            }
        }
        _ => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeQueueTransition(
    _env: JNIEnv,
    _class: JClass,
    current: jint,
    size: jint,
    shuffle: jboolean,
    repeat_mode: jint,
    action: jint,
) -> jint {
    transition(current, size, shuffle != 0, repeat_mode, action)
}

#[cfg(test)]
mod tests {
    use super::{transition, ACTION_COMPLETION, ACTION_NEXT, ACTION_PREVIOUS};

    #[test]
    fn advances_and_stops_at_the_end_without_repeat() {
        assert_eq!(transition(0, 3, false, 0, ACTION_NEXT), 1);
        assert_eq!(transition(2, 3, false, 0, ACTION_NEXT), -1);
    }

    #[test]
    fn repeat_all_wraps_in_both_directions() {
        assert_eq!(transition(2, 3, false, 1, ACTION_NEXT), 0);
        assert_eq!(transition(0, 3, false, 1, ACTION_PREVIOUS), 2);
    }

    #[test]
    fn previous_at_the_first_item_keeps_the_first_item_without_repeat() {
        assert_eq!(transition(0, 3, false, 0, ACTION_PREVIOUS), 0);
    }

    #[test]
    fn repeat_one_replays_only_on_natural_completion() {
        assert_eq!(transition(1, 3, false, 2, ACTION_COMPLETION), -2);
        assert_eq!(transition(1, 3, false, 2, ACTION_NEXT), 2);
    }

    #[test]
    fn shuffle_never_selects_the_current_item() {
        for _ in 0..128 {
            let next = transition(1, 4, true, 0, ACTION_NEXT);
            assert!((0..4).contains(&next));
            assert_ne!(next, 1);
        }
    }

    #[test]
    fn completion_advances_when_shuffle_is_enabled() {
        for _ in 0..128 {
            let next = transition(2, 4, true, 0, ACTION_COMPLETION);
            assert!((0..4).contains(&next));
            assert_ne!(next, 2);
        }
    }

    #[test]
    fn invalid_queue_state_stops_safely() {
        assert_eq!(transition(-1, 3, false, 0, ACTION_NEXT), -1);
        assert_eq!(transition(3, 3, false, 0, ACTION_NEXT), -1);
        assert_eq!(transition(0, 0, true, 1, ACTION_PREVIOUS), -1);
    }
}


use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use jni::sys::{jlong, jlongArray};

struct QueueSnapshot {
    current_index: i32,
    queue_size: i32,
    repeat_mode: i32,
    shuffle_enabled: bool,
}

static NEXT_QUEUE_HANDLE: AtomicU64 = AtomicU64::new(1);
static QUEUE_STATES: OnceLock<Mutex<HashMap<i64, Arc<Mutex<QueueSnapshot>>>>> = OnceLock::new();

fn queue_registry() -> &'static Mutex<HashMap<i64, Arc<Mutex<QueueSnapshot>>>> {
    QUEUE_STATES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn queue_state(handle: jlong) -> Option<Arc<Mutex<QueueSnapshot>>> {
    if handle <= 0 { return None; }
    queue_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn create_queue_state() -> jlong {
    loop {
        let candidate = NEXT_QUEUE_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = queue_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::new(Mutex::new(QueueSnapshot {
                current_index: -1, queue_size: 0, repeat_mode: 0, shuffle_enabled: false,
            })));
            return handle as jlong;
        }
    }
}

fn destroy_queue_state(handle: jlong) {
    if handle > 0 {
        queue_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreatePlaybackQueueState(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    create_queue_state()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyPlaybackQueueState(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    destroy_queue_state(handle);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueSelect(
    _env: JNIEnv, _class: JClass, handle: jlong, index: jint, size: jint,
) -> jint {
    let Some(state) = queue_state(handle) else { return -1; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.queue_size = size.max(0);
    state.current_index = if state.queue_size == 0 { -1 } else { index.clamp(0, state.queue_size - 1) };
    state.current_index
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueSetRepeatMode(
    _env: JNIEnv, _class: JClass, handle: jlong, mode: jint,
) -> jint {
    if !(0..=2).contains(&mode) { return -1; }
    let Some(state) = queue_state(handle) else { return -1; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.repeat_mode = mode;
    state.repeat_mode
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueCycleRepeatMode(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    let Some(state) = queue_state(handle) else { return -1; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.repeat_mode = (state.repeat_mode + 1) % 3;
    state.repeat_mode
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueSetShuffle(
    _env: JNIEnv, _class: JClass, handle: jlong, enabled: jboolean,
) -> jboolean {
    let Some(state) = queue_state(handle) else { return 0; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.shuffle_enabled = enabled != 0;
    if state.shuffle_enabled { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueToggleShuffle(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jboolean {
    let Some(state) = queue_state(handle) else { return 0; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.shuffle_enabled = !state.shuffle_enabled;
    if state.shuffle_enabled { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackQueueTransition(
    _env: JNIEnv, _class: JClass, handle: jlong, size: jint, action: jint,
) -> jint {
    let Some(state) = queue_state(handle) else { return -1; };
    let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
    state.queue_size = size.max(0);
    if state.queue_size == 0 || state.current_index < 0 || state.current_index >= state.queue_size {
        return -1;
    }
    let next = transition(state.current_index, state.queue_size, state.shuffle_enabled, state.repeat_mode, action);
    if next >= 0 { state.current_index = next; }
    next
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackQueueSnapshot(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jlongArray {
    let Some(state) = queue_state(handle) else { return std::ptr::null_mut(); };
    let state = state.lock().unwrap_or_else(|error| error.into_inner());
    let values = [state.current_index as i64, state.repeat_mode as i64, if state.shuffle_enabled { 1 } else { 0 }];
    let output = match env.new_long_array(values.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_long_array_region(&output, 0, &values).is_err() { return std::ptr::null_mut(); }
    output.into_raw()
}


#[cfg(test)]
mod handle_tests {
    use super::*;

    #[test]
    fn queue_handles_reject_invalid_and_stale_values() {
        assert!(queue_state(0).is_none());
        assert!(queue_state(-1).is_none());
        let handle = create_queue_state();
        assert!(handle > 0);
        assert!(queue_state(handle).is_some());
        destroy_queue_state(handle);
        assert!(queue_state(handle).is_none());
        destroy_queue_state(handle);
        assert!(queue_state(handle).is_none());
    }

    #[test]
    fn queue_handles_are_unique_and_destroy_isolated() {
        let first = create_queue_state();
        let second = create_queue_state();
        assert_ne!(first, second);
        destroy_queue_state(first);
        assert!(queue_state(first).is_none());
        assert!(queue_state(second).is_some());
        destroy_queue_state(second);
    }
}
