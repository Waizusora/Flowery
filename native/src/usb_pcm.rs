use std::collections::VecDeque;
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use jni::objects::JClass;
use jni::sys::{jbyteArray, jint, jlong, jsize};
use jni::JNIEnv;

/// Encoding selected from the source representation and the DAC's class descriptors.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum PcmEncoding { SignedInteger, IeeeFloat32 }

/// Format contract shared by direct USB PCM producers and the bounded transport queue.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct PcmFormat {
    pub encoding: PcmEncoding,
    pub sample_rate: u32,
    pub channels: u16,
    pub valid_bits: u8,
    pub subslot_bytes: u8,
    pub signed: bool,
    pub little_endian: bool,
}

impl PcmFormat {
    pub fn validate(self) -> Result<Self, &'static str> {
        if self.sample_rate == 0 || self.channels == 0 {
            return Err("PCM sample rate and channel count must be positive");
        }
        if !(1..=4).contains(&self.subslot_bytes) || !self.little_endian {
            return Err("Unsupported PCM subslot or byte order");
        }
        match self.encoding {
            PcmEncoding::SignedInteger if (8..=32).contains(&self.valid_bits)
                && self.valid_bits as u16 <= self.subslot_bytes as u16 * 8 && self.signed => {}
            PcmEncoding::IeeeFloat32 if self.valid_bits == 32 && self.subslot_bytes == 4 && !self.signed => {}
            PcmEncoding::SignedInteger => return Err("Unsupported signed integer PCM representation"),
            PcmEncoding::IeeeFloat32 => return Err("IEEE Float USB PCM requires a 32-bit, four-byte subslot"),
        }
        Ok(self)
    }

    pub fn bytes_per_frame(self) -> usize {
        self.channels as usize * self.subslot_bytes as usize
    }
}

#[derive(Clone)]
pub(super) struct PcmBlock {
    pub(super) pcm: Vec<u8>,
    pub(super) frames: u32,
    pub(super) epoch: i32,
    pub(super) start_frames: i64,
    pub(super) end_of_stream: bool,
}

struct QueueState {
    blocks: VecDeque<PcmBlock>,
    closed: bool,
}

pub(super) struct UsbPcmQueue {
    capacity: usize,
    state: Mutex<QueueState>,
    changed: Condvar,
}

impl UsbPcmQueue {
    fn new(capacity: usize) -> Self {
        Self {
            capacity,
            state: Mutex::new(QueueState { blocks: VecDeque::with_capacity(capacity), closed: false }),
            changed: Condvar::new(),
        }
    }

    pub(super) fn offer(&self, block: PcmBlock, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        while state.blocks.len() >= self.capacity && !state.closed {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() { return false; }
            let result = self.changed.wait_timeout(state, remaining);
            let (next, timed_out) = match result {
                Ok((guard, status)) => (guard, status.timed_out()),
                Err(poisoned) => {
                    let (guard, status) = poisoned.into_inner();
                    (guard, status.timed_out())
                }
            };
            state = next;
            if timed_out && state.blocks.len() >= self.capacity { return false; }
        }
        if state.closed { return false; }
        state.blocks.push_back(block);
        self.changed.notify_all();
        true
    }

    pub(super) fn clear(&self) {
        self.state.lock().unwrap_or_else(|error| error.into_inner()).blocks.clear();
        self.changed.notify_all();
    }

    fn poll(&self, timeout: Duration) -> Option<PcmBlock> {
        let deadline = Instant::now() + timeout;
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        loop {
            if let Some(block) = state.blocks.pop_front() {
                self.changed.notify_all();
                return Some(block);
            }
            if state.closed { return None; }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() { return None; }
            let result = self.changed.wait_timeout(state, remaining);
            let (next, timed_out) = match result {
                Ok((guard, status)) => (guard, status.timed_out()),
                Err(poisoned) => {
                    let (guard, status) = poisoned.into_inner();
                    (guard, status.timed_out())
                }
            };
            state = next;
            if timed_out && state.blocks.is_empty() { return None; }
        }
    }
}

static NEXT_QUEUE_HANDLE: AtomicU64 = AtomicU64::new(1);
static PCM_QUEUES: OnceLock<Mutex<HashMap<i64, Arc<UsbPcmQueue>>>> = OnceLock::new();

fn queue_registry() -> &'static Mutex<HashMap<i64, Arc<UsbPcmQueue>>> {
    PCM_QUEUES.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(super) fn queue_ref(handle: jlong) -> Option<Arc<UsbPcmQueue>> {
    if handle <= 0 { return None; }
    queue_registry().lock().unwrap_or_else(|error| error.into_inner()).get(&handle).cloned()
}

fn create_queue(capacity: usize) -> jlong {
    loop {
        let candidate = NEXT_QUEUE_HANDLE.fetch_add(1, Ordering::Relaxed);
        if candidate == 0 || candidate > i64::MAX as u64 { return 0; }
        let handle = candidate as i64;
        let mut registry = queue_registry().lock().unwrap_or_else(|error| error.into_inner());
        if let std::collections::hash_map::Entry::Vacant(entry) = registry.entry(handle) {
            entry.insert(Arc::new(UsbPcmQueue::new(capacity)));
            return handle as jlong;
        }
    }
}

fn destroy_queue(handle: jlong) {
    if handle <= 0 { return; }
    if let Some(queue) = queue_registry().lock().unwrap_or_else(|error| error.into_inner()).remove(&handle) {
        queue.state.lock().unwrap_or_else(|error| error.into_inner()).closed = true;
        queue.changed.notify_all();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbPcmQueue(
    _env: JNIEnv, _class: JClass, capacity: jint
) -> jlong {
    if !(1..=64).contains(&capacity) { return 0; }
    create_queue(capacity as usize)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePollUsbPcmBlock(
    env: JNIEnv, _class: JClass, handle: jlong, timeout_ms: jint
) -> jbyteArray {
    let Some(queue) = queue_ref(handle) else { return std::ptr::null_mut(); };
    if timeout_ms < 0 { return std::ptr::null_mut(); }
    let Some(block) = queue.poll(Duration::from_millis(timeout_ms as u64)) else {
        return std::ptr::null_mut();
    };
    const HEADER_SIZE: usize = 17;
    let mut encoded = Vec::<i8>::with_capacity(HEADER_SIZE + block.pcm.len());
    encoded.push(if block.end_of_stream { 1 } else { 0 });
    encoded.extend_from_slice(&block.epoch.to_le_bytes().map(|value| value as i8));
    encoded.extend_from_slice(&block.frames.to_le_bytes().map(|value| value as i8));
    encoded.extend_from_slice(&block.start_frames.to_le_bytes().map(|value| value as i8));
    encoded.extend(block.pcm.into_iter().map(|value| value as i8));
    let output = match env.new_byte_array(encoded.len() as jsize) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_byte_array_region(&output, 0, &encoded).is_err() {
        return std::ptr::null_mut();
    }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeUsbPcmQueueSize(
    _env: JNIEnv, _class: JClass, handle: jlong
) -> jint {
    let Some(queue) = queue_ref(handle) else { return 0; };
    let size = queue.state.lock().unwrap_or_else(|error| error.into_inner()).blocks.len() as jint;
    size
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseUsbPcmQueue(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    if let Some(queue) = queue_ref(handle) {
        queue.state.lock().unwrap_or_else(|error| error.into_inner()).closed = true;
        queue.changed.notify_all();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDestroyUsbPcmQueue(
    _env: JNIEnv, _class: JClass, handle: jlong
) {
    destroy_queue(handle);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm_format_accepts_packed_and_padded_sample_layouts() {
        for (valid_bits, subslot_bytes) in [(8, 1), (16, 2), (20, 3), (24, 3), (24, 4), (32, 4)] {
            let format = PcmFormat {
                encoding: PcmEncoding::SignedInteger,
                sample_rate: 192_000,
                channels: 2,
                valid_bits,
                subslot_bytes,
                signed: true,
                little_endian: true,
            };
            assert!(format.validate().is_ok());
            assert_eq!(format.bytes_per_frame(), 2 * subslot_bytes as usize);
        }
    }

    #[test]
    fn pcm_format_accepts_only_four_byte_ieee_float32() {
        let format = PcmFormat { encoding: PcmEncoding::IeeeFloat32, sample_rate: 48_000,
            channels: 2, valid_bits: 32, subslot_bytes: 4, signed: false, little_endian: true };
        assert!(format.validate().is_ok());
        assert_eq!(format.bytes_per_frame(), 8);
        assert!(PcmFormat { subslot_bytes: 3, ..format }.validate().is_err());
        assert!(PcmFormat { signed: true, ..format }.validate().is_err());
    }

    #[test]
    fn pcm_format_rejects_invalid_sample_representation() {
        let format = PcmFormat {
            encoding: PcmEncoding::SignedInteger,
            sample_rate: 48_000,
            channels: 2,
            valid_bits: 32,
            subslot_bytes: 3,
            signed: true,
            little_endian: true,
        };
        assert!(format.validate().is_err());
    }

    #[test]
    fn queue_is_bounded_and_preserves_block_order() {
        let queue = UsbPcmQueue::new(1);
        let first = PcmBlock { pcm: vec![1, 2], frames: 1, epoch: 0, start_frames: 0, end_of_stream: false };
        let second = PcmBlock { pcm: vec![3, 4], frames: 1, epoch: 0, start_frames: 1, end_of_stream: false };
        assert!(queue.offer(first, Duration::ZERO));
        assert!(!queue.offer(second.clone(), Duration::ZERO));
        let block = queue.poll(Duration::ZERO).unwrap();
        assert_eq!(block.pcm, vec![1, 2]);
        assert!(queue.offer(second, Duration::ZERO));
    }

    #[test]
    fn close_wakes_and_rejects_new_blocks() {
        let queue = UsbPcmQueue::new(2);
        queue.state.lock().unwrap().closed = true;
        let block = PcmBlock { pcm: Vec::new(), frames: 0, epoch: 0, start_frames: 0, end_of_stream: true };
        assert!(!queue.offer(block, Duration::ZERO));
        assert!(queue.poll(Duration::ZERO).is_none());
    }
}


#[cfg(test)]
mod handle_tests {
    use super::*;

    #[test]
    fn pcm_queue_handles_reject_stale_values_and_keep_inflight_references_alive() {
        assert!(queue_ref(0).is_none());
        assert!(queue_ref(-1).is_none());
        let handle = create_queue(2);
        let retained = queue_ref(handle).expect("new queue handle must resolve");
        destroy_queue(handle);
        assert!(queue_ref(handle).is_none());
        let block = PcmBlock { pcm: vec![1, 2], frames: 1, epoch: 0, start_frames: 0, end_of_stream: false };
        assert!(!retained.offer(block, Duration::ZERO));
    }

    #[test]
    fn pcm_queue_handles_are_unique() {
        let first = create_queue(1);
        let second = create_queue(1);
        assert_ne!(first, second);
        destroy_queue(first);
        assert!(queue_ref(second).is_some());
        destroy_queue(second);
    }

    #[test]
    fn destroying_pcm_queue_wakes_inflight_poll_and_rejects_writes() {
        let handle = create_queue(1);
        let retained = queue_ref(handle).expect("queue handle must resolve");
        let waiter_queue = Arc::clone(&retained);
        let waiter = std::thread::spawn(move || waiter_queue.poll(Duration::from_secs(5)));
        std::thread::sleep(Duration::from_millis(10));
        destroy_queue(handle);
        assert!(waiter.join().unwrap().is_none());
        let block = PcmBlock {
            pcm: vec![0, 0],
            frames: 1,
            epoch: 0,
            start_frames: 0,
            end_of_stream: false,
        };
        assert!(!retained.offer(block, Duration::ZERO));
    }
}
