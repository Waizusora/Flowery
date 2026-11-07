use std::collections::VecDeque;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use jni::objects::JClass;
use jni::sys::{jbyteArray, jint, jlong};
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



pub(super) fn queue_ref(handle: jlong) -> Option<Arc<EncodedPacketQueue>> {
    if handle == 0 { return None; }
    let raw = handle as *const EncodedPacketQueue;
    unsafe { Arc::increment_strong_count(raw); Some(Arc::from_raw(raw)) }
}

struct EncodedProducerState {
    decoder: *mut crate::streaming::StreamDecoder,
    queue: Arc<EncodedPacketQueue>,
    framing: EncodedFraming,
    sequence: u64,
    pending: Option<EncodedPacket>,
    ended: bool,
}

struct EncodedProducer {
    state: Mutex<EncodedProducerState>,
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbEncodedQueue(
    _env: JNIEnv, _class: JClass, max_packets: jint, max_bytes: jint,
) -> jlong {
    if max_packets <= 0 || max_bytes <= 0 { return 0; }
    match EncodedPacketQueue::new(max_packets as usize, max_bytes as usize) {
        Ok(queue) => Arc::into_raw(Arc::new(queue)) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePollUsbEncodedPacket(
    mut env: JNIEnv, _class: JClass, handle: jlong, timeout_ms: jint,
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
    mut env: JNIEnv, _class: JClass, decoder_handle: jlong, queue_handle: jlong,
    framing_kind: jint, format_code: jint,
) -> jlong {
    if decoder_handle == 0 || !(0..=0xffff).contains(&format_code) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB producer configuration");
        return 0;
    }
    let Some(queue) = queue_ref(queue_handle) else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB packet queue");
        return 0;
    };
    let decoder = unsafe { &mut *(decoder_handle as *mut crate::streaming::StreamDecoder) };
    let framing = match framing_kind {
        1 if format_code != 0 => {
            if let Err(error) = decoder.validate_encoded_uac1_type_ii(
                crate::usb_encoded_formats::UAC1_FORMAT_TYPE_II, format_code as u16) {
                let _ = env.throw_new("java/lang/IllegalArgumentException", error);
                return 0;
            }
            EncodedFraming::Uac1TypeIi { format_tag: format_code as u16 }
        }
        2 if format_code == crate::usb_encoded_formats::IEC61937_DATATYPE_AC3 as jint => {
            if let Err(error) = decoder.validate_iec61937_ac3(format_code as u16) {
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
    let producer = EncodedProducer {
        state: Mutex::new(EncodedProducerState {
            decoder: decoder_handle as *mut crate::streaming::StreamDecoder,
            queue, framing, sequence: 0, pending: None, ended: false,
        }),
    };
    Box::into_raw(Box::new(producer)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePumpUsbEncodedProducer(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jint {
    if handle == 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid compressed USB producer handle");
        return -2;
    }
    let producer = unsafe { &*(handle as *const EncodedProducer) };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    if state.ended { return -1; }
    if state.pending.is_none() {
        let decoder = unsafe { &mut *state.decoder };
        match decoder.read_encoded_packet() {
            Ok(Some((source_bytes, timestamp_us, duration_us))) => {
                let bytes = match state.framing {
                    EncodedFraming::Uac1TypeIi { .. } => source_bytes,
                    EncodedFraming::Iec61937 { data_type: crate::usb_encoded_formats::IEC61937_DATATYPE_AC3 } => {
                        match crate::usb_encoded_formats::build_iec61937_ac3_burst(&source_bytes) {
                            Ok(burst) => burst,
                            Err(error) => {
                                let _ = env.throw_new("java/lang/IllegalStateException", error);
                                return -2;
                            }
                        }
                    }
                    EncodedFraming::Iec61937 { .. } => {
                        let _ = env.throw_new("java/lang/IllegalStateException", "Unsupported IEC 61937 data type");
                        return -2;
                    }
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
            Ok(None) => {
                state.ended = true;
                return -1;
            }
            Err(error) => {
                let _ = env.throw_new("java/lang/IllegalStateException", error);
                return -2;
            }
        }
    }
    let packet = state.pending.take().unwrap();
    match state.queue.offer(packet) {
        Ok(()) => 1,
        Err(packet) => { state.pending = Some(packet); 0 }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekUsbEncodedProducer(
    mut env: JNIEnv, _class: JClass, handle: jlong, position_ms: jlong,
) -> jlong {
    if handle == 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid encoded seek request");
        return -1;
    }
    let producer = unsafe { &*(handle as *const EncodedProducer) };
    let mut state = producer.state.lock().unwrap_or_else(|error| error.into_inner());
    let decoder = unsafe { &mut *state.decoder };
    match decoder.seek_to(position_ms as u64) {
        Ok(actual_position) => {
            state.pending = None;
            state.ended = false;
            state.queue.clear();
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
    if handle != 0 { unsafe { drop(Box::from_raw(handle as *mut EncodedProducer)); } }
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
    if handle != 0 { unsafe { drop(Arc::from_raw(handle as *const EncodedPacketQueue)); } }
}

#[cfg(test)]
mod tests {
    use super::{EncodedFraming, EncodedPacket, EncodedPacketQueue};
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
    fn type_iii_packet_metadata_is_distinct_from_type_ii() {
        let mut burst = packet(0, &[0x72, 0xf8, 0x1f, 0x4e]);
        burst.framing = EncodedFraming::Iec61937 { data_type: 0x01 };
        assert_eq!(burst.framing, EncodedFraming::Iec61937 { data_type: 0x01 });
    }
}
