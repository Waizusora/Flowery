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

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreatePlaybackController(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    Box::into_raw(Box::new(AtomicI32::new(IDLE))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSetPlaybackControllerState(
    _env: JNIEnv, _class: JClass, handle: jlong, new_state: jint,
) -> jint {
    if handle == 0 || !(IDLE..=FAILED).contains(&new_state) { return -1; }
    unsafe { &*(handle as *const AtomicI32) }.store(new_state, Ordering::Release);
    0
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
    fn playback_state_values_are_stable() {
        assert_eq!((IDLE, PREPARING, PLAYING, PAUSED, STOPPING, FAILED), (0, 1, 2, 3, 4, 5));
    }
}
