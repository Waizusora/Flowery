use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, AtomicI32, Ordering};
use std::thread::{self, JoinHandle};
use crate::streaming::{decoder_ref, StreamDecoder};
use crate::playback_session::{session_ref, SessionState};
use std::time::{Duration, Instant};

use jni::objects::{JClass, JObject, JValue};
use jni::sys::{jbyteArray, jint, jlong, jlongArray};
use jni::JNIEnv;

/// Identifies the framing contract of a compressed USB payload.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum EncodedFraming {
    /// UAC1 Type-II payload: codec-specific compressed frames, not PCM.
    Uac1TypeIi { format_tag: u16 },
    /// IEC 61937 burst payload carried by a UAC1 Type-III interface.
    Iec61937 { data_type: u16 },
}

/// An indivisible compressed frame or IEC 61937 burst.
///
/// The byte vector is preserved exactly by the queue. It is never interpreted
/// as PCM and is never split across queue entries.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct EncodedPacket {
    pub sequence: u64,
    pub presentation_time_us: Option<u64>,
    pub duration_us: u64,
    pub framing: EncodedFraming,
    pub bytes: Vec<u8>,
}

struct QueueState {
    packets: VecDeque<EncodedPacket>,
    queued_bytes: usize,
    closed: bool,
}

/// A bounded, packet-preserving queue independent from the PCM queue.
///
/// Capacity is constrained by both packet count and total payload bytes. This
/// prevents a malformed or unusually large packet from bypassing the memory
/// bound merely because it occupies one queue entry.
pub(super) struct EncodedPacketQueue {
    max_packets: usize,
    max_bytes: usize,
    state: Mutex<QueueState>,
    changed: Condvar,
}

fn synchronize_epoch(
    epoch: &mut u64,
    session_epoch: u64,
    pending: &mut Option<EncodedPacket>,
    queue: &EncodedPacketQueue,
) -> bool {
    if *epoch == session_epoch {
        return false;
    }
    *pending = None;
    queue.clear();
    *epoch = session_epoch;
    true
}

impl EncodedPacketQueue {
    pub(super) fn new(max_packets: usize, max_bytes: usize) -> Result<Self, &'static str> {
        if max_packets == 0 || max_bytes == 0 {
            return Err("Encoded queue limits must be positive");
        }
        Ok(Self {
            max_packets,
            max_bytes,
            state: Mutex::new(QueueState {
                packets: VecDeque::with_capacity(max_packets),
                queued_bytes: 0,
                closed: false,
            }),
            changed: Condvar::new(),
        })
    }

    /// Adds one complete compressed packet without changing its contents.
    /// Returns the packet to the caller when the queue is full or closed.
    pub(super) fn offer(&self, packet: EncodedPacket) -> Result<(), EncodedPacket> {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        let next_bytes = state.queued_bytes.checked_add(packet.bytes.len());
        if state.closed
            || state.packets.len() >= self.max_packets
            || next_bytes.is_none()
            || next_bytes.unwrap_or(usize::MAX) > self.max_bytes
        {
            return Err(packet);
        }
        state.queued_bytes = next_bytes.unwrap();
        state.packets.push_back(packet);
        self.changed.notify_all();
        Ok(())
    }

    pub(super) fn poll(&self, timeout: Duration) -> Option<EncodedPacket> {
        let deadline = Instant::now() + timeout;
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        loop {
            if let Some(packet) = state.packets.pop_front() {
                state.queued_bytes = state.queued_bytes.saturating_sub(packet.bytes.len());
                self.changed.notify_all();
                return Some(packet);
            }
            if state.closed {
                return None;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            let result = self.changed.wait_timeout(state, remaining);
            let (next, timed_out) = match result {
                Ok((guard, status)) => (guard, status.timed_out()),
                Err(poisoned) => {
                    let (guard, status) = poisoned.into_inner();
                    (guard, status.timed_out())
                }
            };
            state = next;
            if timed_out && state.packets.is_empty() {
                return None;
            }
        }
    }

    pub(super) fn is_empty(&self) -> bool {
        self.state.lock().unwrap_or_else(|error| error.into_inner()).packets.is_empty()
    }

    /// Drops queued packets at a compressed-frame boundary, for seek/flush.
    pub(super) fn clear(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.packets.clear();
        state.queued_bytes = 0;
        self.changed.notify_all();
    }

    pub(super) fn close(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.closed = true;
        self.changed.notify_all();
    }

    #[cfg(test)]
    fn usage(&self) -> (usize, usize) {
        let state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        (state.packets.len(), state.queued_bytes)
    }
}



static NEXT_ENCODED_QUEUE_HANDLE: AtomicU64 = AtomicU64::new(1);
static ENCODED_QUEUES: OnceLock<Mutex<HashMap<i64, Arc<EncodedPacketQueue>>>> = OnceLock::new();
static NEXT_ENCODED_PRODUCER_HANDLE: AtomicU64 = AtomicU64::new(1);
static ENCODED_PRODUCERS: OnceLock<Mutex<HashMap<i64, Arc<EncodedProducer>>>> = OnceLock::new();

fn queue_registry() -> &'static Mutex<HashMap<i64, Arc<EncodedPacketQueue>>> {
    ENCODED_QUEUES.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn queue_ref(handle: jlong) -> Option<Arc<EncodedPacketQueue>> {
    if handle <= 0 { return None; }
    queue_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn register_queue(queue: EncodedPacketQueue) -> jlong {
    let queue = Arc::new(queue);
    loop {
        let candidate = NEXT_ENCODED_QUEUE_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = queue_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::clone(&queue));
            return handle as jlong;
        }
    }
}

fn remove_queue(handle: jlong) {
    if handle > 0 { queue_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle); }
}

fn producer_registry() -> &'static Mutex<HashMap<i64, Arc<EncodedProducer>>> {
    ENCODED_PRODUCERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn producer_ref(handle: jlong) -> Option<Arc<EncodedProducer>> {
    if handle <= 0 { return None; }
    producer_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn register_producer(producer: EncodedProducer) -> jlong {
    let producer = Arc::new(producer);
    loop {
        let candidate = NEXT_ENCODED_PRODUCER_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = producer_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::clone(&producer));
            return handle as jlong;
        }
    }
}

fn remove_producer(handle: jlong) {
    if handle <= 0 { return; }
    let producer = producer_registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .remove(&handle);
    if let Some(producer) = producer {
        producer.cancelled.store(true, Ordering::Release);
        let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
        state.pending = None;
        state.queue.clear();
        state.queue.close();
    }
}

struct EncodedProducerState {
    decoder: Arc<Mutex<StreamDecoder>>,
    session: Arc<SessionState>,
    queue: Arc<EncodedPacketQueue>,
    framing: EncodedFraming,
    maximum_bitrate_kbps: Option<u16>,
    sequence: u64,
    pending: Option<EncodedPacket>,
    ended: bool,
    epoch: u64,
}

struct EncodedProducer {
    state: Mutex<EncodedProducerState>,
    cancelled: AtomicBool,
}

struct EncodedProducerWorker {
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    thread: JoinHandle<()>,
}

static ENCODED_PRODUCER_WORKERS: OnceLock<Mutex<HashMap<i64, EncodedProducerWorker>>> = OnceLock::new();

fn encoded_worker_registry() -> &'static Mutex<HashMap<i64, EncodedProducerWorker>> {
    ENCODED_PRODUCER_WORKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn stop_encoded_producer_worker(handle: jlong) {
    let worker = encoded_worker_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle);
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);
        let _ = worker.thread.join();
    }
}


#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbEncodedQueue(
    _env: JNIEnv, _class: JClass, max_packets: jint, max_bytes: jint,
) -> jlong {
    if max_packets <= 0 || max_bytes <= 0 { return 0; }
    match EncodedPacketQueue::new(max_packets as usize, max_bytes as usize) {
        Ok(queue) => register_queue(queue),
        Err(_) => 0,
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EncodedWriteOutcome {
    Accepted,
    Stopped,
    Rejected,
}

fn classify_encoded_write_result(written: jint, expected: jint) -> EncodedWriteOutcome {
    if written == -2 {
        EncodedWriteOutcome::Stopped
    } else if written == expected {
        EncodedWriteOutcome::Accepted
    } else {
        EncodedWriteOutcome::Rejected
    }
}

/// Drains a bounded batch of encoded packets from Rust and submits each packet
/// through the already-open Android USB session. Kotlin supplies the transport
/// object only as an Android/libusb bridge; packet scheduling stays native.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePumpUsbEncodedTransport(
    mut env: JNIEnv, _class: JClass, queue_handle: jlong, producer_handle: jlong,
    transport: JObject, max_packets: jint, expected_sequence: jlong,
    framing_kind: jint, format_code: jint, last_position_ms: jlong,
) -> jlongArray {
    if max_packets <= 0 || expected_sequence < 0 || last_position_ms < 0 ||
        (framing_kind != 1 && framing_kind != 2) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid encoded transport scheduler configuration");
        return std::ptr::null_mut();
    }
    let Some(queue) = queue_ref(queue_handle) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Encoded packet queue is no longer valid");
        return std::ptr::null_mut();
    };
    let producer_status = encoded_worker_registry()
        .lock().unwrap_or_else(|error| error.into_inner())
        .get(&producer_handle)
        .map(|worker| worker.status.load(Ordering::Acquire))
        .unwrap_or(-1);
    if producer_status != 0 && producer_status != 1 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            if producer_status == 2 { "Encoded packet producer was cancelled" } else { "Encoded packet producer is unavailable" },
        );
        return std::ptr::null_mut();
    }
    let mut submitted = 0_i64;
    let mut sequence = expected_sequence as u64;
    let mut position_ms = last_position_ms;
    let mut stopped = false;
    while submitted < max_packets as i64 {
        let Some(packet) = queue.poll(Duration::ZERO) else { break; };
        let framing_matches = match packet.framing {
            EncodedFraming::Uac1TypeIi { format_tag } => framing_kind == 1 && format_code == format_tag as jint,
            EncodedFraming::Iec61937 { data_type } => framing_kind == 2 && format_code == data_type as jint,
        };
        if !framing_matches || packet.sequence != sequence || packet.bytes.is_empty() {
            let _ = env.throw_new("java/lang/IllegalStateException", "Encoded packet framing or sequence is invalid");
            return std::ptr::null_mut();
        }
        let payload = match env.byte_array_from_slice(&packet.bytes) {
            Ok(array) => array,
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
                return std::ptr::null_mut();
            }
        };
        let payload_object = JObject::from(payload);
        let result = env.call_method(
            &transport,
            "writePacket",
            "([BIIJ)I",
            &[
                JValue::Object(&payload_object),
                JValue::Int(0),
                JValue::Int(packet.bytes.len() as jint),
                JValue::Long(packet.duration_us as jlong),
            ],
        );
        let written = match result.and_then(|value| value.i()) {
            Ok(value) => value,
            Err(error) => {
                if !env.exception_check().unwrap_or(false) {
                    let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
                }
                return std::ptr::null_mut();
            }
        };
        match classify_encoded_write_result(written, packet.bytes.len() as jint) {
            EncodedWriteOutcome::Accepted => {}
            EncodedWriteOutcome::Stopped => {
                stopped = true;
                break;
            }
            EncodedWriteOutcome::Rejected => {
                let _ = env.throw_new("java/lang/IllegalStateException", "USB transport did not accept a complete encoded packet");
                return std::ptr::null_mut();
            }
        }
        sequence = match sequence.checked_add(1) {
            Some(next) => next,
            None => {
                let _ = env.throw_new("java/lang/IllegalStateException", "Encoded packet sequence exhausted");
                return std::ptr::null_mut();
            }
        };
        if let Some(presentation_time) = packet.presentation_time_us {
            position_ms = (presentation_time / 1000).min(i64::MAX as u64) as jlong;
        }
        submitted += 1;
    }
    let ended = !stopped && producer_status == 1 && queue.is_empty();
    let result = match env.new_long_array(4) {
        Ok(array) => array,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            return std::ptr::null_mut();
        }
    };
    let values = [if stopped { -2 } else { submitted }, sequence as jlong, position_ms, if ended { 1 } else { 0 }];
    if let Err(error) = env.set_long_array_region(&result, 0, &values) {
        let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
        return std::ptr::null_mut();
    }
    result.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePollUsbEncodedPacket(
    env: JNIEnv, _class: JClass, handle: jlong, timeout_ms: jint,
) -> jbyteArray {
    if timeout_ms < 0 { return std::ptr::null_mut(); }
    let Some(queue) = queue_ref(handle) else { return std::ptr::null_mut(); };
    let Some(packet) = queue.poll(Duration::from_millis(timeout_ms as u64)) else {
        return std::ptr::null_mut();
    };
    let (kind, format) = match packet.framing {
        EncodedFraming::Uac1TypeIi { format_tag } => (1u8, format_tag),
        EncodedFraming::Iec61937 { data_type } => (2u8, data_type),
    };
    let mut bytes = Vec::<i8>::with_capacity(27 + packet.bytes.len());
    bytes.push(kind as i8);
    bytes.extend_from_slice(&format.to_le_bytes().map(|value| value as i8));
    bytes.extend_from_slice(&packet.sequence.to_le_bytes().map(|value| value as i8));
    bytes.extend_from_slice(&packet.presentation_time_us.unwrap_or(0).to_le_bytes().map(|value| value as i8));
    bytes.extend_from_slice(&packet.duration_us.to_le_bytes().map(|value| value as i8));
    bytes.extend(packet.bytes.into_iter().map(|value| value as i8));
    let output = match env.new_byte_array(bytes.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_byte_array_region(&output, 0, &bytes).is_err() { return std::ptr::null_mut(); }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbEncodedProducer(
    mut env: JNIEnv, _class: JClass, decoder_handle: jlong, queue_handle: jlong, session_handle: jlong,
    framing_kind: jint, format_code: jint, maximum_bitrate_kbps: jint,
) -> jlong {
    if decoder_handle == 0 || !(0..=0xffff).contains(&format_code) || !(0..=0xffff).contains(&maximum_bitrate_kbps) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB producer configuration");
        return 0;
    }
    let Some(queue) = queue_ref(queue_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB packet queue");
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
    if !Arc::ptr_eq(&decoder, &session.decoder) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Playback session does not own the supplied decoder");
        return 0;
    }
    if session.cancelled.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Playback session is already cancelled");
        return 0;
    }
    let decoder_guard = decoder.lock().unwrap_or_else(|error| error.into_inner());
    let framing = match framing_kind {
        1 if format_code != 0 => {
            if let Err(error) = decoder_guard.validate_encoded_uac1_type_ii(
                crate::usb_encoded_formats::UAC1_FORMAT_TYPE_II, format_code as u16) {
                let _ = env.throw_new("java/lang/IllegalArgumentException", error);
                return 0;
            }
            EncodedFraming::Uac1TypeIi { format_tag: format_code as u16 }
        }
        2 if format_code == crate::usb_encoded_formats::IEC61937_DATATYPE_AC3 as jint => {
            if let Err(error) = decoder_guard.validate_iec61937_ac3(format_code as u16) {
                let _ = env.throw_new("java/lang/IllegalArgumentException", error);
                return 0;
            }
            EncodedFraming::Iec61937 { data_type: format_code as u16 }
        }
        _ => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "Unsupported compressed USB framing identifier");
            return 0;
        }
    };
    let maximum_bitrate = match framing {
        EncodedFraming::Uac1TypeIi { .. } if maximum_bitrate_kbps > 0 =>
            Some(maximum_bitrate_kbps as u16),
        EncodedFraming::Iec61937 { .. } if maximum_bitrate_kbps == 0 => None,
        _ => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "Encoded profile bitrate limit does not match the selected framing");
            return 0;
        }
    };
    drop(decoder_guard);
    let initial_epoch = session.epoch.load(Ordering::Acquire);
    let producer = EncodedProducer {
        state: Mutex::new(EncodedProducerState {
            decoder,
            session,
            queue, framing, maximum_bitrate_kbps: maximum_bitrate,
            sequence: 0, pending: None, ended: false,
            epoch: initial_epoch,
        }),
        cancelled: AtomicBool::new(false),
    };
    register_producer(producer)
}


fn pump_encoded_producer(handle: jlong) -> Result<jint, String> {
    let producer = producer_ref(handle).ok_or_else(|| "Invalid compressed USB producer handle".to_string())?;
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if producer.cancelled.load(Ordering::Acquire) || state.session.cancelled.load(Ordering::Acquire) {
        return Ok(-3);
    }
    let session_epoch = state.session.epoch.load(Ordering::Acquire);
    let queue = Arc::clone(&state.queue);
    let epoch_changed = {
        let EncodedProducerState { epoch, pending, .. } = &mut *state;
        synchronize_epoch(epoch, session_epoch, pending, &queue)
    };
    if epoch_changed { return Ok(0); }
    if state.ended { return Ok(-1); }
    if state.pending.is_none() {
        let decoder_owner = Arc::clone(&state.decoder);
        let mut decoder = decoder_owner.lock().unwrap_or_else(|error| error.into_inner());
        match decoder.read_encoded_packet()? {
            Some((source_bytes, timestamp_us, duration_us)) => {
                decoder.validate_ac3_encoded_frame(&source_bytes)?;
                if let Some(maximum) = state.maximum_bitrate_kbps {
                    let actual = crate::usb_encoded_formats::ac3_frame_bitrate_kbps(&source_bytes)?;
                    if actual > maximum {
                        return Err(format!("AC-3 frame bitrate {actual} kbps exceeds UAC1 descriptor maximum {maximum} kbps"));
                    }
                }
                let bytes = match state.framing {
                    EncodedFraming::Uac1TypeIi { .. } => source_bytes,
                    EncodedFraming::Iec61937 { data_type: crate::usb_encoded_formats::IEC61937_DATATYPE_AC3 } => {
                        crate::usb_encoded_formats::build_iec61937_ac3_burst(&source_bytes)?
                    }
                    EncodedFraming::Iec61937 { .. } => return Err("Unsupported IEC 61937 data type".into()),
                };
                let packet = EncodedPacket {
                    sequence: state.sequence,
                    presentation_time_us: Some(timestamp_us),
                    duration_us,
                    framing: state.framing,
                    bytes,
                };
                state.sequence = state.sequence.wrapping_add(1);
                state.pending = Some(packet);
            }
            None => {
                state.ended = true;
                return Ok(-1);
            }
        }
    }
    let packet = state.pending.take().expect("pending encoded packet was just populated");
    match state.queue.offer(packet) {
        Ok(()) => Ok(1),
        Err(packet) => {
            state.pending = Some(packet);
            Ok(0)
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartUsbEncodedProducerWorker(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    if producer_ref(handle).is_none() {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB producer handle");
        return -1;
    }
    stop_encoded_producer_worker(handle);
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let spawned = thread::Builder::new().name("flowery-usb-encoded-producer".into()).spawn(move || {
        while !thread_stop.load(Ordering::Acquire) {
            match pump_encoded_producer(handle) {
                Ok(1) => {}
                Ok(0) => thread::sleep(Duration::from_millis(2)),
                Ok(-1) => {
                    thread_status.store(1, Ordering::Release);
                    break;
                }
                Ok(-3) => {
                    thread_status.store(2, Ordering::Release);
                    break;
                }
                Ok(_) | Err(_) => {
                    thread_status.store(-2, Ordering::Release);
                    break;
                }
            }
        }
        if thread_stop.load(Ordering::Acquire) {
            thread_status.store(2, Ordering::Release);
        }
    });
    match spawned {
        Ok(thread) => {
            encoded_worker_registry().lock().unwrap_or_else(|error| error.into_inner())
                .insert(handle, EncodedProducerWorker { stop, status, thread });
            0
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetUsbEncodedProducerWorkerStatus(
    _env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    encoded_worker_registry().lock().unwrap_or_else(|error| error.into_inner())
        .get(&handle).map(|worker| worker.status.load(Ordering::Acquire)).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopUsbEncodedProducerWorker(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    stop_encoded_producer_worker(handle);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetUsbEncodedTransportRate(
    mut env: JNIEnv, _class: JClass, decoder_handle: jlong, framing_kind: jint,
) -> jlong {
    if decoder_handle == 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid stream decoder handle");
        return 0;
    }
    let Some(decoder) = decoder_ref(decoder_handle) else { return 0; };
    let decoder = decoder.lock().unwrap_or_else(|error| error.into_inner());
    match decoder.encoded_transport_bytes_per_second(framing_kind) {
        Ok(rate) if rate > 0 && rate <= jlong::MAX as u64 => rate as jlong,
        Ok(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Invalid encoded transport rate");
            0
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

/// Performs an encoded USB seek as one native operation. The Android
/// transport bridge is asked to flush in-flight libusb transfers, while Rust
/// advances the shared epoch, seeks the decoder, resets the bounded queue, and
/// restarts the packet producer.
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekUsbEncodedSession(
    mut env: JNIEnv, _class: JClass, handle: jlong, transport: JObject, position_ms: jlong,
) -> jlong {
    if handle <= 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid encoded session seek request");
        return -1;
    }
    let Some(producer) = producer_ref(handle) else {
        let _ = env.throw_new("java/lang/IllegalStateException", "Encoded producer is no longer valid");
        return -1;
    };
    stop_encoded_producer_worker(handle);
    let session = {
        let state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
        if producer.cancelled.load(Ordering::Acquire) || state.session.cancelled.load(Ordering::Acquire) {
            let _ = env.throw_new("java/lang/IllegalStateException", "Encoded playback session is cancelled");
            return -1;
        }
        Arc::clone(&state.session)
    };
    {
        let _gate = session.gate.lock().unwrap_or_else(|error| error.into_inner());
        if crate::playback_session::advance_epoch(&session.epoch, &session.cancelled).is_none() {
            let _ = env.throw_new("java/lang/IllegalStateException", "Encoded playback seek epoch is exhausted or cancelled");
            return -1;
        }
    }
    if let Err(error) = env.call_method(&transport, "flush", "()V", &[]) {
        if !env.exception_check().unwrap_or(false) {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
        }
        return -1;
    }
    let actual = {
        let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
        let decoder_owner = Arc::clone(&state.decoder);
        let mut decoder = decoder_owner.lock().unwrap_or_else(|error| error.into_inner());
        if let Err(error) = decoder.begin_encoded_stream() {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            return -1;
        }
        let seek_result = decoder.seek_to(position_ms as u64);
        drop(decoder);
        match seek_result {
            Ok(actual_position) => {
                state.pending = None;
                state.ended = false;
                state.queue.clear();
                state.epoch = session.epoch.load(Ordering::Acquire);
                actual_position as jlong
            }
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return -1;
            }
        }
    };
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let spawned = thread::Builder::new().name("flowery-usb-encoded-producer".into()).spawn(move || {
        while !thread_stop.load(Ordering::Acquire) {
            match pump_encoded_producer(handle) {
                Ok(1) => {}
                Ok(0) => thread::sleep(Duration::from_millis(2)),
                Ok(-1) => {
                    thread_status.store(1, Ordering::Release);
                    break;
                }
                Ok(-3) => {
                    thread_status.store(2, Ordering::Release);
                    break;
                }
                Ok(_) | Err(_) => {
                    thread_status.store(-2, Ordering::Release);
                    break;
                }
            }
        }
        if thread_stop.load(Ordering::Acquire) {
            thread_status.store(2, Ordering::Release);
        }
    });
    match spawned {
        Ok(thread) => {
            encoded_worker_registry().lock().unwrap_or_else(|error| error.into_inner())
                .insert(handle, EncodedProducerWorker { stop, status, thread });
            actual
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekUsbEncodedProducer(
    mut env: JNIEnv, _class: JClass, handle: jlong, position_ms: jlong,
) -> jlong {
    if handle <= 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid encoded seek request");
        return -1;
    }
    let Some(producer) = producer_ref(handle) else { return -1; };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if producer.cancelled.load(Ordering::Acquire) || state.session.cancelled.load(Ordering::Acquire) { return -1; }
    let decoder_owner = Arc::clone(&state.decoder);
    let mut decoder = decoder_owner.lock().unwrap_or_else(|error| error.into_inner());
    if let Err(error) = decoder.begin_encoded_stream() {
        let _ = env.throw_new("java/lang/IllegalStateException", error);
        return -1;
    }
    let seek_result = decoder.seek_to(position_ms as u64);
    drop(decoder);
    match seek_result {
        Ok(actual_position) => {
            state.pending = None;
            state.ended = false;
            state.queue.clear();
            state.epoch = state.session.epoch.load(Ordering::Acquire);
            actual_position as jlong
        }
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyUsbEncodedProducer(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    stop_encoded_producer_worker(handle);
    remove_producer(handle);
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseUsbEncodedQueue(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Some(queue) = queue_ref(handle) { queue.close(); }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeClearUsbEncodedQueue(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Some(queue) = queue_ref(handle) { queue.clear(); }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyUsbEncodedQueue(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    remove_queue(handle);
}

#[cfg(test)]
mod tests {
    use super::{synchronize_epoch, EncodedFraming, EncodedPacket, EncodedPacketQueue};
    use std::time::Duration;

    fn packet(sequence: u64, bytes: &[u8]) -> EncodedPacket {
        EncodedPacket {
            sequence,
            presentation_time_us: Some(sequence * 20_000),
            duration_us: 20_000,
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: bytes.to_vec(),
        }
    }

    #[test]
    fn encoded_write_result_distinguishes_stop_from_rejection() {
        assert_eq!(super::classify_encoded_write_result(128, 128), super::EncodedWriteOutcome::Accepted);
        assert_eq!(super::classify_encoded_write_result(-2, 128), super::EncodedWriteOutcome::Stopped);
        assert_eq!(super::classify_encoded_write_result(-1, 128), super::EncodedWriteOutcome::Rejected);
        assert_eq!(super::classify_encoded_write_result(64, 128), super::EncodedWriteOutcome::Rejected);
    }

    #[test]
    fn queue_preserves_complete_packet_bytes_and_order() {
        let queue = EncodedPacketQueue::new(2, 8).unwrap();
        queue.offer(packet(0, &[0x0b, 0x77, 1, 2])).unwrap();
        queue.offer(packet(1, &[3, 4, 5, 6])).unwrap();
        assert_eq!(queue.poll(Duration::ZERO).unwrap().bytes, [0x0b, 0x77, 1, 2]);
        assert_eq!(queue.poll(Duration::ZERO).unwrap().sequence, 1);
        assert_eq!(queue.usage(), (0, 0));
    }

    #[test]
    fn queue_rejects_packet_and_byte_capacity_overflow_without_truncation() {
        let queue = EncodedPacketQueue::new(1, 4).unwrap();
        queue.offer(packet(0, &[1, 2, 3, 4])).unwrap();
        let rejected = queue.offer(packet(1, &[5])).unwrap_err();
        assert_eq!(rejected.bytes, [5]);
        assert_eq!(queue.usage(), (1, 4));
        let oversized = EncodedPacketQueue::new(2, 3).unwrap();
        assert_eq!(oversized.offer(packet(0, &[1, 2, 3, 4])).unwrap_err().bytes.len(), 4);
    }

    #[test]
    fn flush_discards_only_queued_encoded_packets() {
        let queue = EncodedPacketQueue::new(4, 32).unwrap();
        queue.offer(packet(0, &[1, 2])).unwrap();
        queue.clear();
        assert_eq!(queue.poll(Duration::ZERO), None);
        assert_eq!(queue.usage(), (0, 0));
    }

    #[test]
    fn close_rejects_new_packets_and_wakes_empty_polls() {
        let queue = EncodedPacketQueue::new(2, 32).unwrap();
        queue.close();
        assert!(queue.offer(packet(0, &[1])).is_err());
        assert_eq!(queue.poll(Duration::ZERO), None);
    }

    #[test]
    fn session_epoch_change_discards_pending_and_queued_encoded_packets() {
        let queue = EncodedPacketQueue::new(4, 64).unwrap();
        queue.offer(packet(0, &[1, 2, 3])).unwrap();
        let mut pending = Some(packet(1, &[4, 5]));
        let mut epoch = 7;

        assert!(synchronize_epoch(&mut epoch, 8, &mut pending, &queue));
        assert_eq!(epoch, 8);
        assert!(pending.is_none());
        assert_eq!(queue.poll(Duration::ZERO), None);
        assert!(!synchronize_epoch(&mut epoch, 8, &mut pending, &queue));
    }

    #[test]
    fn type_iii_packet_metadata_is_distinct_from_type_ii() {
        let mut burst = packet(0, &[0x72, 0xf8, 0x1f, 0x4e]);
        burst.framing = EncodedFraming::Iec61937 { data_type: 0x01 };
        assert_eq!(burst.framing, EncodedFraming::Iec61937 { data_type: 0x01 });
    }
}


#[cfg(test)]
mod handle_tests {
    use super::*;

    #[test]
    fn encoded_queue_registry_rejects_stale_handles_and_retains_inflight_references() {
        assert!(queue_ref(0).is_none());
        assert!(queue_ref(-1).is_none());
        let handle = register_queue(EncodedPacketQueue::new(2, 64).unwrap());
        let retained = queue_ref(handle).expect("registered queue must resolve");
        remove_queue(handle);
        assert!(queue_ref(handle).is_none());
        assert!(retained.offer(EncodedPacket {
            sequence: 0,
            presentation_time_us: None,
            duration_us: 1,
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: vec![1, 2],
        }).is_ok());
    }

    #[test]
    fn encoded_queue_registry_allocates_unique_handles() {
        let first = register_queue(EncodedPacketQueue::new(1, 8).unwrap());
        let second = register_queue(EncodedPacketQueue::new(1, 8).unwrap());
        assert_ne!(first, second);
        remove_queue(first);
        assert!(queue_ref(first).is_none());
        assert!(queue_ref(second).is_some());
        remove_queue(second);
    }

    #[test]
    fn encoded_producer_registry_rejects_invalid_and_stale_handles() {
        assert!(producer_ref(0).is_none());
        assert!(producer_ref(-1).is_none());
        assert!(producer_ref(i64::MAX).is_none());
        remove_producer(0);
    }

    #[test]
    fn closing_encoded_queue_rejects_new_packets_and_wakes_waiters() {
        let queue = Arc::new(EncodedPacketQueue::new(2, 64).unwrap());
        let waiter_queue = Arc::clone(&queue);
        let waiter = std::thread::spawn(move || waiter_queue.poll(Duration::from_secs(5)));
        std::thread::sleep(Duration::from_millis(10));
        queue.close();
        assert!(waiter.join().unwrap().is_none());
        assert!(queue.offer(EncodedPacket {
            sequence: 0,
            presentation_time_us: None,
            duration_us: 1,
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: vec![1],
        }).is_err());
    }

    #[test]
    fn session_epoch_change_discards_pending_and_queued_encoded_packets() {
        let queue = EncodedPacketQueue::new(4, 128).unwrap();
        queue.offer(EncodedPacket {
            sequence: 7,
            presentation_time_us: Some(7),
            duration_us: 1,
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: vec![7, 8],
        }).unwrap();
        let mut epoch = 3;
        let mut pending = Some(EncodedPacket {
            sequence: 6,
            presentation_time_us: Some(6),
            duration_us: 1,
            framing: EncodedFraming::Uac1TypeIi { format_tag: 0x1002 },
            bytes: vec![6],
        });
        assert!(synchronize_epoch(&mut epoch, 4, &mut pending, &queue));
        assert_eq!(epoch, 4);
        assert!(pending.is_none());
        assert_eq!(queue.usage(), (0, 0));
        assert!(!synchronize_epoch(&mut epoch, 4, &mut pending, &queue));
    }
}
