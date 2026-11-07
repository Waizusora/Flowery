use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const IDLE: i32 = 0;
const PREPARING: i32 = 1;
const PLAYING: i32 = 2;
const PAUSED: i32 = 3;
const STOPPING: i32 = 4;
const FAILED: i32 = 5;

const EVENT_PREPARE: i32 = 0;
const EVENT_START: i32 = 1;
const EVENT_PAUSE: i32 = 2;
const EVENT_RESUME: i32 = 3;
const EVENT_STOP: i32 = 4;
const EVENT_FINISH: i32 = 5;
const EVENT_FAIL: i32 = 6;

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static CONTROLLERS: OnceLock<Mutex<HashMap<i64, Arc<AtomicI32>>>> = OnceLock::new();

fn controllers() -> &'static Mutex<HashMap<i64, Arc<AtomicI32>>> {
    CONTROLLERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn controller(handle: jlong) -> Option<Arc<AtomicI32>> {
    if handle <= 0 {
        return None;
    }
    controllers().lock().unwrap_or_else(|error| error.into_inner())
        .get(&handle).cloned()
}

fn create_controller() -> jlong {
    loop {
        let candidate = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 {
            return 0;
        }
        let handle = candidate as i64;
        let mut registry = controllers().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::new(AtomicI32::new(IDLE)));
            return handle as jlong;
        }
    }
}

fn transition(current: i32, event: i32) -> i32 {
    match event {
        EVENT_PREPARE => PREPARING,
        EVENT_START if current == PREPARING => PLAYING,
        EVENT_RESUME if current == PAUSED => PLAYING,
        EVENT_PAUSE if current == PLAYING => PAUSED,
        EVENT_STOP if current != IDLE => STOPPING,
        EVENT_FINISH if current != IDLE => IDLE,
        EVENT_FAIL if current != IDLE => FAILED,
        EVENT_FAIL => FAILED,
        EVENT_PREPARE | EVENT_START | EVENT_PAUSE | EVENT_RESUME | EVENT_STOP | EVENT_FINISH => current,
        _ => -1,
    }
}

fn transition_handle(handle: jlong, event: jint) -> jint {
    let Some(state) = controller(handle) else { return -1; };
    let mut current = state.load(Ordering::Acquire);
    loop {
        let next = transition(current, event);
        if next < 0 { return -1; }
        match state.compare_exchange_weak(current, next, Ordering::AcqRel, Ordering::Acquire) {
            Ok(_) => return next,
            Err(observed) => current = observed,
        }
    }
}

fn state_for_handle(handle: jlong) -> jint {
    controller(handle).map(|state| state.load(Ordering::Acquire)).unwrap_or(FAILED)
}

fn destroy_controller(handle: jlong) {
    if handle <= 0 { return; }
    controllers().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreatePlaybackController(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    create_controller()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeTransitionPlaybackController(
    _env: JNIEnv, _class: JClass, handle: jlong, event: jint,
) -> jint {
    transition_handle(handle, event)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackControllerState(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    state_for_handle(handle)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyPlaybackController(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    destroy_controller(handle)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playback_lifecycle_accepts_only_valid_transitions() {
        let mut state = transition(IDLE, EVENT_PREPARE);
        assert_eq!(state, PREPARING);
        state = transition(state, EVENT_START);
        assert_eq!(state, PLAYING);
        state = transition(state, EVENT_PAUSE);
        assert_eq!(state, PAUSED);
        state = transition(state, EVENT_RESUME);
        assert_eq!(state, PLAYING);
        state = transition(state, EVENT_STOP);
        assert_eq!(state, STOPPING);
        assert_eq!(transition(state, EVENT_FINISH), IDLE);
    }

    #[test]
    fn playback_can_restart_after_failure_or_pause() {
        assert_eq!(transition(PLAYING, EVENT_FAIL), FAILED);
        assert_eq!(transition(FAILED, EVENT_PREPARE), PREPARING);
        assert_eq!(transition(PAUSED, EVENT_PREPARE), PREPARING);
        assert_eq!(transition(STOPPING, EVENT_PREPARE), PREPARING);
    }

    #[test]
    fn invalid_events_are_rejected_and_duplicate_events_are_idempotent() {
        assert_eq!(transition(IDLE, EVENT_PAUSE), IDLE);
        assert_eq!(transition(IDLE, 99), -1);
        assert_eq!(transition(PLAYING, EVENT_START), PLAYING);
    }

    #[test]
    fn state_values_match_the_android_adapter_contract() {
        assert_eq!((IDLE, PREPARING, PLAYING, PAUSED, STOPPING, FAILED), (0, 1, 2, 3, 4, 5));
    }

    #[test]
    fn registered_handles_reject_stale_and_double_destroyed_values() {
        let handle = create_controller();
        assert!(handle > 0);
        assert_eq!(state_for_handle(handle), IDLE);
        assert_eq!(transition_handle(handle, EVENT_PREPARE), PREPARING);
        destroy_controller(handle);
        assert_eq!(state_for_handle(handle), FAILED);
        assert_eq!(transition_handle(handle, EVENT_START), -1);
        destroy_controller(handle);
    }

    #[test]
    fn registered_controller_handles_are_unique() {
        let first = create_controller();
        let second = create_controller();
        assert_ne!(first, 0);
        assert_ne!(second, 0);
        assert_ne!(first, second);
        destroy_controller(first);
        destroy_controller(second);
    }

    #[test]
    fn non_positive_handles_are_rejected() {
        assert_eq!(state_for_handle(0), FAILED);
        assert_eq!(state_for_handle(-1), FAILED);
        assert_eq!(transition_handle(0, EVENT_PREPARE), -1);
        destroy_controller(0);
        destroy_controller(-1);
    }
}
