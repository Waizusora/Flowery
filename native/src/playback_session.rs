use crate::streaming::{decoder_ref, StreamDecoder};
use crate::playback_policy::select_playback_route;
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

#[allow(dead_code)] // Some phases are only constructed by Android-target worker paths.
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

#[derive(Clone, Copy, Debug)]
pub(crate) struct AAudioSeekRequest {
    /// Session epoch assigned when this request becomes the latest seek.
    pub(crate) epoch: u64,
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
    pub(crate) max_frames: usize,
    pub(crate) position_ms: i64,
    pub(crate) segment_start_ms: i64,
    pub(crate) segment_end_ms: i64,
}

#[allow(dead_code)] // Android-only teardown paths read the retained JNI handle.
pub(super) struct SessionState {
    pub(super) epoch: AtomicU64,
    pub(super) cancelled: AtomicBool,
    pub(super) phase: AtomicI64,
    pub(super) route: i32,
    pub(super) decoder_handle: jlong,
    pub(super) gate: Mutex<()>,
    pub(super) decoder: Arc<Mutex<StreamDecoder>>,
    pub(super) error_code: AtomicI32,
    pub(super) seek_error: Mutex<String>,
    pub(super) seek_request: Mutex<Option<AAudioSeekRequest>>,
    pub(super) last_seek_position_ms: AtomicI64,
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

fn is_completed_stop_noop(current: i32, command: i32) -> bool {
    current == SessionPhase::Completed as i32 && command == SessionPhase::Cancelled as i32
}

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

/// Keeps the cancellation flag consistent with the terminal Cancelled phase.
fn store_session_phase(phase: &AtomicI64, cancelled: &AtomicBool, next: i32) {
    phase.store(next as i64, Ordering::Release);
    if next == SessionPhase::Cancelled as i32 {
        cancelled.store(true, Ordering::Release);
    }
}

pub(super) fn advance_epoch(epoch: &AtomicU64, cancelled: &AtomicBool) -> Option<u64> {
    loop {
        if cancelled.load(Ordering::Acquire) { return None; }
        let current = epoch.load(Ordering::Acquire);
        let next = current.checked_add(1)?;
        if epoch.compare_exchange_weak(current, next, Ordering::AcqRel, Ordering::Acquire).is_ok() {
            return Some(next);
        }
    }
}

/// Applies a lifecycle transition from a native worker without crossing JNI.
#[allow(dead_code)] // Called by Android-target AAudio scheduler paths.
pub(super) fn transition_session_phase(session: &SessionState, next: i32) -> bool {
    if !(0..=7).contains(&next) { return false; }
    let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    if session.cancelled.load(Ordering::Acquire) && next != SessionPhase::Cancelled as i32 {
        return false;
    }
    let current = session.phase.load(Ordering::Acquire) as i32;
    if !valid_phase_transition(current, next) { return false; }
    store_session_phase(&session.phase, &session.cancelled, next);
    true
}

/// Creates a native playback session and selects its output route from decoder metadata.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePreparePlaybackSession(
    mut env: JNIEnv, _class: JClass, decoder_handle: jlong,
) -> jlongArray {
    let Some(decoder) = decoder_ref(decoder_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid stream decoder handle");
        return std::ptr::null_mut();
    };
    let (source_rate, channels, source_bits, source_data_format) = {
        let guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
        guard.playback_metadata()
    };
    let route = select_playback_route();
    let output = match env.new_long_array(6) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let session_handle = register_session(SessionState {
        epoch: AtomicU64::new(0),
        cancelled: AtomicBool::new(false),
        phase: AtomicI64::new(SessionPhase::Created as i64),
        route,
        decoder_handle,
        gate: Mutex::new(()),
        decoder,
        error_code: AtomicI32::new(0),
        seek_error: Mutex::new(String::new()),
        seek_request: Mutex::new(None),
        last_seek_position_ms: AtomicI64::new(-1),
    });
    if session_handle == 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "Unable to allocate playback session handle");
        return std::ptr::null_mut();
    }
    let values: [jlong; 6] = [
        session_handle,
        route as jlong,
        source_rate as jlong,
        channels as jlong,
        source_bits as jlong,
        source_data_format as jlong,
    ];
    if env.set_long_array_region(&output, 0, &values).is_err() {
        registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&session_handle);
        return std::ptr::null_mut();
    }
    output.into_raw()
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
        decoder_handle,
        gate: Mutex::new(()),
        decoder,
        error_code: AtomicI32::new(0),
        seek_error: Mutex::new(String::new()),
        seek_request: Mutex::new(None),
        last_seek_position_ms: AtomicI64::new(-1),
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
    if session.route == 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "AAudio seeks must be submitted as native seek requests");
        return -1;
    }
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
    store_session_phase(&session.phase, &session.cancelled, phase);
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
        if session.route == 0 {
            let _ = env.throw_new("java/lang/IllegalStateException", "AAudio seeks must be submitted as native seek requests");
            return -1;
        }
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
    // Queue completion may be followed by the service's ordinary Stop cleanup.
    // Preserve Completed as the terminal outcome instead of throwing on that duplicate cleanup.
    if is_completed_stop_noop(current, command) {
        return 0;
    }
    if !valid_phase_transition(current, command) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Invalid playback session command transition");
        return -1;
    }
    store_session_phase(&session.phase, &session.cancelled, command);
    #[cfg(target_os = "android")]
    let apply_pause = session.route == 0 && command == SessionPhase::Paused as jint;
    #[cfg(target_os = "android")]
    let apply_resume = session.route == 0 && command == SessionPhase::Running as jint;
    #[cfg(target_os = "android")]
    let stop_output = session.route == 0 &&
        (command == SessionPhase::Cancelled as jint || command == SessionPhase::Failed as jint);
    drop(_gate);
    #[cfg(target_os = "android")]
    let output_result = if apply_pause {
        crate::aaudio::pause_output_for_session()
    } else if apply_resume {
        crate::aaudio::resume_output_for_session()
    } else { None };
    #[cfg(target_os = "android")]
    if let Some(result) = output_result {
        if result != 0 {
            let rollback_gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
            if session.phase.load(Ordering::Acquire) as i32 == command &&
                !session.cancelled.load(Ordering::Acquire) {
                session.phase.store(current as i64, Ordering::Release);
            }
            drop(rollback_gate);
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Unable to apply AAudio session command (error {result})"),
            );
            return result as jlong;
        }
    }
    #[cfg(target_os = "android")]
    if stop_output {
        crate::aaudio::stop_output_for_session();
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeRequestPlaybackSessionSeek(
    mut env: JNIEnv, _class: JClass, handle: jlong, position_ms: jlong,
    max_frames: jint, segment_start_ms: jlong, segment_end_ms: jlong,
) -> jlong {
    if position_ms < 0 || max_frames <= 0 || segment_start_ms < 0 ||
        segment_end_ms < -1 || (segment_end_ms >= 0 && segment_end_ms < segment_start_ms) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid AAudio seek request");
        return -1;
    }
    let Some(session) = session_ref(handle) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session is no longer valid");
        return -1;
    };
    if session.route != 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "Seek requests are only accepted by AAudio sessions");
        return -1;
    }
    let gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
    let phase = session.phase.load(Ordering::Acquire) as i32;
    if session.cancelled.load(Ordering::Acquire) || !matches!(phase, 2 | 3) {
        let _ = env.throw_new("java/lang/IllegalStateException", "AAudio session is not seekable in its current state");
        return -1;
    }
    let Some(epoch) = advance_epoch(&session.epoch, &session.cancelled) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback seek epoch is exhausted or cancelled");
        return -1;
    };
    *session.seek_error.lock().unwrap_or_else(|error| error.into_inner()) = String::new();
    *session.seek_request.lock().unwrap_or_else(|error| error.into_inner()) =
        Some(AAudioSeekRequest {
            epoch,
            max_frames: max_frames as usize,
            position_ms,
            segment_start_ms,
            segment_end_ms,
        });
    drop(gate);
    #[cfg(target_os = "android")]
    if phase == SessionPhase::Running as i32 {
        match crate::aaudio::pause_output_for_session() {
            Some(0) => {}
            Some(error) => {
                session.error_code.store(error, Ordering::Release);
                transition_session_phase(&session, SessionPhase::Failed as i32);
                let _ = env.throw_new(
                    "java/lang/IllegalStateException",
                    format!("Unable to pause AAudio output for seek (error {error})"),
                );
                return -1;
            }
            None => {
                session.error_code.store(-904, Ordering::Release);
                transition_session_phase(&session, SessionPhase::Failed as i32);
                let _ = env.throw_new("java/lang/IllegalStateException", "AAudio output is unavailable during seek");
                return -1;
            }
        }
    }
    epoch as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackSessionSeekPosition(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jlong {
    session_ref(handle)
        .map(|session| session.last_seek_position_ms.load(Ordering::Acquire) as jlong)
        .unwrap_or(-1)
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
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackSessionError(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    session_ref(handle).map(|session| session.error_code.load(Ordering::Acquire)).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetPlaybackSessionSeekError(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jni::sys::jstring {
    let detail = session_ref(handle)
        .map(|session| session.seek_error.lock().unwrap_or_else(|error| error.into_inner()).clone())
        .unwrap_or_else(|| "Playback session is no longer valid".to_string());
    env.new_string(detail).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCancelPlaybackSession(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Some(session) = session_ref(handle) {
        let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
        session.cancelled.store(true, Ordering::Release);
        session.phase.store(SessionPhase::Cancelled as i64, Ordering::Release);
        drop(_gate);
        #[cfg(target_os = "android")]
        if session.route == 0 {
            crate::aaudio::stop_output_for_session();
        }
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
            drop(_gate);
            // The native session owns the AAudio decode worker lifetime. Stop and join
            // it before releasing the session's retained decoder reference.
            #[cfg(target_os = "android")]
            if session.route == 0 {
                crate::streaming::stop_stream_pump_worker(session.decoder_handle);
                crate::aaudio::stop_output_for_session();
            }
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
    fn aaudio_seek_request_is_bound_to_its_publication_epoch() {
        let epoch = AtomicU64::new(4);
        let cancelled = AtomicBool::new(false);
        let published = advance_epoch(&epoch, &cancelled).unwrap();
        let request = AAudioSeekRequest { epoch: published, max_frames: 256, position_ms: 900,
            segment_start_ms: 0, segment_end_ms: -1 };
        assert_eq!(request.epoch, 5);
        assert_eq!(epoch.load(Ordering::Acquire), request.epoch);
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
    fn stop_after_completion_is_a_noop_and_preserves_terminal_phase() {
        let completed = SessionPhase::Completed as i32;
        let cancelled = SessionPhase::Cancelled as i32;
        assert!(is_completed_stop_noop(completed, cancelled));
        assert!(!is_completed_stop_noop(cancelled, cancelled));
        assert!(!is_completed_stop_noop(completed, SessionPhase::Running as i32));
        assert!(!valid_phase_transition(completed, cancelled));
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
    fn every_phase_update_path_sets_the_cancellation_flag_for_cancelled_phase() {
        let phase = AtomicI64::new(SessionPhase::Running as i64);
        let cancelled = AtomicBool::new(false);

        store_session_phase(&phase, &cancelled, SessionPhase::Paused as i32);
        assert_eq!(phase.load(Ordering::Acquire), SessionPhase::Paused as i64);
        assert!(!cancelled.load(Ordering::Acquire));

        store_session_phase(&phase, &cancelled, SessionPhase::Cancelled as i32);
        assert_eq!(phase.load(Ordering::Acquire), SessionPhase::Cancelled as i64);
        assert!(cancelled.load(Ordering::Acquire));
    }

    #[test]
    fn session_phase_values_are_stable() {
        assert_eq!(SessionPhase::Created as i32, 0);
        assert_eq!(SessionPhase::Running as i32, 2);
        assert_eq!(SessionPhase::Cancelled as i32, 6);
        assert_eq!(SessionPhase::Failed as i32, 7);
    }
}
