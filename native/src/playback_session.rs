use crate::streaming::{decoder_ref, StreamDecoder};
use jni::objects::JClass;
use jni::sys::{jint, jlong, jboolean, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
enum SessionPhase {
    Created = 0,
    Prepared = 1,
    Running = 2,
    Paused = 3,
    Draining = 4,
    Completed = 5,
    Cancelled = 6,
    Failed = 7,
}

pub(super) struct SessionState {
    pub(super) epoch: AtomicU64,
    pub(super) cancelled: AtomicBool,
    phase: AtomicI64,
    route: i32,
    decoder: Arc<Mutex<StreamDecoder>>,
}

static NEXT_SESSION_HANDLE: AtomicU64 = AtomicU64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<SessionState>>>> = OnceLock::new();

fn registry() -> &'static Mutex<HashMap<i64, Arc<SessionState>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn session_ref(handle: jlong) -> Option<Arc<SessionState>> {
    if handle <= 0 { return None; }
    registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn register_session(session: SessionState) -> jlong {
    let session = Arc::new(session);
    loop {
        let candidate = NEXT_SESSION_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut sessions = registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = sessions.entry(handle) {
            entry.insert(Arc::clone(&session));
            return handle as jlong;
        }
    }
}

fn valid_route(route: i32) -> bool { (0..=3).contains(&route) }

fn advance_epoch(epoch: &AtomicU64, cancelled: &AtomicBool) -> Option<u64> {
    if cancelled.load(Ordering::Acquire) { return None; }
    Some(epoch.fetch_add(1, Ordering::AcqRel).saturating_add(1))
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreatePlaybackSession(
    mut env: JNIEnv, _class: JClass, decoder_handle: jlong, route: jint,
) -> jlong {
    if !valid_route(route) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid playback route");
        return 0;
    }
    let Some(decoder) = decoder_ref(decoder_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid stream decoder handle");
        return 0;
    };
    register_session(SessionState {
        epoch: AtomicU64::new(0),
        cancelled: AtomicBool::new(false),
        phase: AtomicI64::new(SessionPhase::Created as i64),
        route,
        decoder,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackSessionBeginSeek(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jlong {
    let Some(session) = session_ref(handle) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session is no longer valid");
        return -1;
    };
    if session.cancelled.load(Ordering::Acquire) { return -1; }
    advance_epoch(&session.epoch, &session.cancelled).map(|epoch| epoch as jlong).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackSessionSetPhase(
    mut env: JNIEnv, _class: JClass, handle: jlong, phase: jint,
) -> jint {
    if !(0..=7).contains(&phase) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid playback session phase");
        return -1;
    }
    let Some(session) = session_ref(handle) else { return -1; };
    if session.cancelled.load(Ordering::Acquire) && phase != SessionPhase::Cancelled as jint {
        return -1;
    }
    session.phase.store(phase as i64, Ordering::Release);
    0
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackSessionEpoch(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jlong {
    session_ref(handle).map(|session| session.epoch.load(Ordering::Acquire) as jlong).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackSessionIsCancelled(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jboolean {
    if session_ref(handle).map(|session| session.cancelled.load(Ordering::Acquire)).unwrap_or(true) {
        JNI_TRUE
    } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCancelPlaybackSession(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Some(session) = session_ref(handle) {
        session.cancelled.store(true, Ordering::Release);
        session.phase.store(SessionPhase::Cancelled as i64, Ordering::Release);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeClosePlaybackSession(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle > 0 {
        if let Some(session) = registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle) {
            session.cancelled.store(true, Ordering::Release);
            session.phase.store(SessionPhase::Cancelled as i64, Ordering::Release);
            let _ = session.route;
            let _ = Arc::strong_count(&session.decoder);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_registry_rejects_invalid_handles() {
        assert!(session_ref(0).is_none());
        assert!(session_ref(-1).is_none());
        assert!(session_ref(i64::MAX).is_none());
    }

    #[test]
    fn seek_epoch_is_monotonic_and_cancellation_rejects_new_seeks() {
        let epoch = AtomicU64::new(0);
        let cancelled = AtomicBool::new(false);
        assert_eq!(advance_epoch(&epoch, &cancelled), Some(1));
        assert_eq!(advance_epoch(&epoch, &cancelled), Some(2));
        cancelled.store(true, Ordering::Release);
        assert_eq!(advance_epoch(&epoch, &cancelled), None);
        assert_eq!(epoch.load(Ordering::Acquire), 2);
    }

    #[test]
    fn all_supported_output_routes_share_the_same_session_contract() {
        for route in 0..=3 { assert!(valid_route(route)); }
        assert!(!valid_route(-1));
        assert!(!valid_route(4));
    }

    #[test]
    fn session_phase_values_are_stable() {
        assert_eq!(SessionPhase::Created as i32, 0);
        assert_eq!(SessionPhase::Running as i32, 2);
        assert_eq!(SessionPhase::Cancelled as i32, 6);
        assert_eq!(SessionPhase::Failed as i32, 7);
    }
}
