use jni::objects::JClass;
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::sync::atomic::{AtomicI32, Ordering};

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

fn transition(current: i32, event: i32) -> i32 {
    match event {
        EVENT_PREPARE if current == IDLE || current == FAILED => PREPARING,
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

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreatePlaybackController(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    Box::into_raw(Box::new(AtomicI32::new(IDLE))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeTransitionPlaybackController(
    _env: JNIEnv, _class: JClass, handle: jlong, event: jint,
) -> jint {
    if handle == 0 { return -1; }
    let state = unsafe { &*(handle as *const AtomicI32) };
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

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackControllerState(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    if handle == 0 { return FAILED; }
    unsafe { &*(handle as *const AtomicI32) }.load(Ordering::Acquire)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyPlaybackController(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle != 0 { unsafe { drop(Box::from_raw(handle as *mut AtomicI32)); } }
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
    fn playback_can_restart_after_failure() {
        assert_eq!(transition(PLAYING, EVENT_FAIL), FAILED);
        assert_eq!(transition(FAILED, EVENT_PREPARE), PREPARING);
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
}
