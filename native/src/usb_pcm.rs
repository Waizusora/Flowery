use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

use jni::objects::JClass;
use jni::sys::{jbyteArray, jint, jlong, jsize};
use jni::JNIEnv;

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

pub(super) fn queue_ref(handle: jlong) -> Option<&'static UsbPcmQueue> {
    if handle == 0 { None } else { Some(unsafe { &*(handle as *const UsbPcmQueue) }) }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCreateUsbPcmQueue(
    _env: JNIEnv, _class: JClass, capacity: jint
) -> jlong {
    if !(1..=64).contains(&capacity) { return 0; }
    Box::into_raw(Box::new(UsbPcmQueue::new(capacity as usize))) as jlong
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
    queue.state.lock().unwrap_or_else(|error| error.into_inner()).blocks.len() as jint
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
    if handle != 0 {
        unsafe { drop(Box::from_raw(handle as *mut UsbPcmQueue)); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
