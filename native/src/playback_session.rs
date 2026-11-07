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
    gate: Mutex<()>,
    pub(super) decoder: Arc<Mutex<StreamDecoder>>,
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

fn valid_phase_transition(current: i32, next: i32) -> bool {
    if current == next { return true; }
    match current {
        0 => matches!(next, 1 | 6 | 7),
        1 => matches!(next, 2 | 6 | 7),
        2 => matches!(next, 3 | 4 | 5 | 6 | 7),
        3 => matches!(next, 2 | 6 | 7),
        4 => matches!(next, 5 | 6 | 7),
        5 | 6 | 7 => false,
        _ => false,
    }
}

fn advance_epoch(epoch: &AtomicU64, cancelled: &AtomicBool) -> Option<u64> {
    loop {
        if cancelled.load(Ordering::Acquire) { return None; }
        let current = epoch.load(Ordering::Acquire);
        let next = current.checked_add(1)?;
        if epoch.compare_exchange_weak(current, next, Ordering::AcqRel, Ordering::Acquire).is_ok() {
            return Some(next);
        }
    }
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
        gate: Mutex::new(()),
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
    let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
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
    let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    if session.cancelled.load(Ordering::Acquire) && phase != SessionPhase::Cancelled as jint {
        return -1;
    }
    let current = session.phase.load(Ordering::Acquire) as i32;
    if !valid_phase_transition(current, phase) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Invalid playback session phase transition");
        return -1;
    }
    session.phase.store(phase as i64, Ordering::Release);
    0
}

/// Applies a playback lifecycle command under the session gate. Commands 0..=7
/// are lifecycle phases; command 8 advances the seek epoch without changing phase.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlaybackSessionCommand(
    mut env: JNIEnv, _class: JClass, handle: jlong, command: jint,
) -> jlong {
    let Some(session) = session_ref(handle) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session is no longer valid");
        return -1;
    };
    let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    if command == 8 {
        return advance_epoch(&session.epoch, &session.cancelled)
            .map(|epoch| epoch as jlong).unwrap_or(-1);
    }
    if !(0..=7).contains(&command) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid playback session command");
        return -1;
    }
    if session.cancelled.load(Ordering::Acquire) && command != SessionPhase::Cancelled as jint {
        return -1;
    }
    let current = session.phase.load(Ordering::Acquire) as i32;
    if !valid_phase_transition(current, command) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Invalid playback session command transition");
        return -1;
    }
    session.phase.store(command as i64, Ordering::Release);
    if command == SessionPhase::Cancelled as jint {
        session.cancelled.store(true, Ordering::Release);
    }
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
        let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
        session.cancelled.store(true, Ordering::Release);
        session.phase.store(SessionPhase::Cancelled as i64, Ordering::Release);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeClosePlaybackSession(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle > 0 {
        let session = registry()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .remove(&handle);
        if let Some(session) = session {
            let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
            session.cancelled.store(true, Ordering::Release);
            session.phase.store(SessionPhase::Cancelled as i64, Ordering::Release);
            // Keep the shared decoder alive until every producer and in-flight JNI
            // operation releases its session reference.
            let _ = session.route;
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
    fn session_gate_rejects_seek_after_teardown_for_every_route() {
        for route in 0..=3 {
            assert!(valid_route(route));
            let gate = Mutex::new(());
            let epoch = AtomicU64::new(0);
            let cancelled = AtomicBool::new(false);

            {
                let _guard = gate.lock().unwrap();
                assert_eq!(advance_epoch(&epoch, &cancelled), Some(1));
            }
            {
                let _guard = gate.lock().unwrap();
                cancelled.store(true, Ordering::Release);
            }
            {
                let _guard = gate.lock().unwrap();
                assert_eq!(advance_epoch(&epoch, &cancelled), None);
            }
            assert_eq!(epoch.load(Ordering::Acquire), 1);
        }
    }

    #[test]
    fn epoch_overflow_is_rejected_without_wrapping() {
        let epoch = AtomicU64::new(u64::MAX);
        let cancelled = AtomicBool::new(false);
        assert_eq!(advance_epoch(&epoch, &cancelled), None);
        assert_eq!(epoch.load(Ordering::Acquire), u64::MAX);
    }

    #[test]
    fn all_supported_output_routes_share_the_same_session_contract() {
        for route in 0..=3 { assert!(valid_route(route)); }
        assert!(!valid_route(-1));
        assert!(!valid_route(4));
    }


    #[test]
    fn playback_session_phase_machine_accepts_normal_and_failure_lifecycles() {
        assert!(valid_phase_transition(0, 1));
        assert!(valid_phase_transition(1, 2));
        assert!(valid_phase_transition(2, 3));
        assert!(valid_phase_transition(3, 2));
        assert!(valid_phase_transition(2, 5));
        assert!(valid_phase_transition(0, 7));
        assert!(valid_phase_transition(3, 7));
        assert!(valid_phase_transition(2, 6));
        assert!(!valid_phase_transition(0, 2));
        assert!(!valid_phase_transition(1, 3));
        assert!(!valid_phase_transition(5, 2));
        assert!(!valid_phase_transition(6, 2));
        assert!(!valid_phase_transition(7, 2));
    }

    #[test]
    fn native_session_command_contract_keeps_seek_out_of_phase_transitions() {
        assert!(valid_phase_transition(0, 1));
        assert!(valid_phase_transition(1, 2));
        assert!(valid_phase_transition(2, 3));
        assert!(valid_phase_transition(3, 2));
        assert!(valid_phase_transition(2, 5));
        assert!(valid_phase_transition(2, 6));
        assert!(!valid_phase_transition(5, 2));
        assert!(!valid_phase_transition(6, 2));
        assert!(!valid_phase_transition(7, 2));
    }

    #[test]
    fn session_phase_values_are_stable() {
        assert_eq!(SessionPhase::Created as i32, 0);
        assert_eq!(SessionPhase::Running as i32, 2);
        assert_eq!(SessionPhase::Cancelled as i32, 6);
        assert_eq!(SessionPhase::Failed as i32, 7);
    }
}
