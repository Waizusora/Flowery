//! Asynchronous libusb isochronous OUT streaming.
//!
//! Transfer callbacks only publish completion state. The owning worker performs
//! packet validation, PCM production, resubmission, cancellation, and teardown.

#[cfg(target_os = "android")]
use std::{
    collections::{HashMap, HashSet},
    ffi::CString,
    mem::ManuallyDrop,
    os::raw::c_char,
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

#[cfg(target_os = "android")]
use rtrb::{Consumer, Producer, RingBuffer};

#[cfg(target_os = "android")]
use crate::usb_feedback::{FeedbackBusSpeed, FeedbackOutlierGuard, FeedbackRateTracker, PacketFrameScheduler};

#[cfg(any(target_os = "android", test))]
fn retain_submitted_resources<B, S, T>(
    buffer: Vec<B>,
    signal: Box<S>,
    session: std::sync::Arc<T>,
) -> *const T {
    // A submitted libusb transfer may still reference its buffer and callback
    // state. Keep all three allocations alive if worker unwinding bypasses the
    // normal callback-drain path; the raw transfer pointer is intentionally
    // not freed by the slot either.
    std::mem::forget(buffer);
    std::mem::forget(signal);
    std::sync::Arc::into_raw(session)
}

/// Lower bound of isochronous transfers kept in flight.
#[cfg(any(target_os = "android", test))]
const MIN_TRANSFER_RING_SIZE: usize = 4;
/// Upper bound of isochronous transfers kept in flight.
#[cfg(any(target_os = "android", test))]
const MAX_TRANSFER_RING_SIZE: usize = 32;
/// Buffering target, in microseconds, for streams whose packets all carry the same
/// number of frames (for example 48 kHz and 96 kHz at 125 us service intervals). These
/// streams play without audible gaps at four 1 ms transfers on the tested DAC. A
/// 32 ms ring also streamed correctly at 96 kHz, but the player did not advance to the
/// next track, so the shallow ring is kept until that is understood.
#[cfg(any(target_os = "android", test))]
const CONSTANT_PACKET_RING_BUFFER_US: u64 = 4_000;
/// Buffering target for streams whose packets alternate between frame counts (44.1 kHz
/// and 88.2 kHz). A 4 ms ring ran dry about once every 2.6 s at 44.1 kHz because the
/// worker thread occasionally stalled for 4 to 8 ms. A 32 ms ring, however, failed on
/// the same DAC at the 34th completed transfer with every packet reporting
/// `actual_length != length`. This intermediate depth is an experiment; see
/// [`transfer_length_mismatch_is_benign`].
#[cfg(any(target_os = "android", test))]
const FRACTIONAL_PACKET_RING_BUFFER_US: u64 = 8_000;
/// Requested nice values for the USB submit and PCM decode threads (Android
/// `THREAD_PRIORITY_URGENT_AUDIO` is -19). Best effort; see
/// [`raise_current_thread_priority`].
#[cfg(target_os = "android")]
const USB_WORKER_THREAD_NICE: i32 = -19;
#[cfg(target_os = "android")]
const PCM_DECODE_THREAD_NICE: i32 = -16;
#[cfg(any(target_os = "android", test))]
const PACKETS_PER_TRANSFER: usize = 8;
#[cfg(target_os = "android")]
const TRANSFER_COMPLETION_TIMEOUT: Duration = Duration::from_millis(500);
#[cfg(target_os = "android")]
const TRANSFER_DRAIN_TIMEOUT: Duration = Duration::from_secs(2);
#[cfg(target_os = "android")]
const WORKER_JOIN_TIMEOUT: Duration = Duration::from_secs(3);
#[cfg(target_os = "android")]
const PAUSE_DRAIN_TIMEOUT: Duration = Duration::from_millis(750);

#[cfg(any(target_os = "android", test))]
const PCM_QUEUE_CAPACITY_FRAMES: usize = 16_384;
#[cfg(target_os = "android")]
const PCM_PRODUCER_CHUNK_FRAMES: usize = 2_048;

#[cfg(target_os = "android")]
struct PcmQueue {
    producer: Mutex<Producer<u8>>,
    consumer: Mutex<Consumer<u8>>,
    eof: AtomicBool,
    error: Mutex<Option<String>>,
    frame_bytes: usize,
    decoder_read_calls: AtomicU64,
    decoder_read_total_us: AtomicU64,
    decoder_read_max_us: AtomicU64,
    starvation_count: AtomicU64,
    queued_min_frames: AtomicU64,
}

#[cfg(target_os = "android")]
impl PcmQueue {
    fn new(frame_bytes: usize) -> Result<Self, String> {
        if frame_bytes == 0 { return Err("PCM queue frame size is zero".into()); }
        let capacity_bytes = frame_bytes.checked_mul(PCM_QUEUE_CAPACITY_FRAMES)
            .ok_or("PCM queue capacity overflow")?;
        let (producer, consumer) = RingBuffer::<u8>::new(capacity_bytes);
        Ok(Self {
            producer: Mutex::new(producer),
            consumer: Mutex::new(consumer),
            eof: AtomicBool::new(false),
            error: Mutex::new(None),
            frame_bytes,
            decoder_read_calls: AtomicU64::new(0),
            decoder_read_total_us: AtomicU64::new(0),
            decoder_read_max_us: AtomicU64::new(0),
            starvation_count: AtomicU64::new(0),
            queued_min_frames: AtomicU64::new(u64::MAX),
        })
    }

    fn occupancy(&self) -> (usize, bool, Option<String>) {
        let queued_bytes = self.consumer.lock()
            .unwrap_or_else(|error| error.into_inner()).slots();
        let error = self.error.lock().unwrap_or_else(|poison| poison.into_inner()).clone();
        (queued_bytes / self.frame_bytes, self.eof.load(Ordering::Acquire), error)
    }

    fn record_occupancy(&self, frames: usize) {
        self.queued_min_frames.fetch_min(frames as u64, Ordering::Relaxed);
    }

    fn record_starvation(&self) {
        self.starvation_count.fetch_add(1, Ordering::Relaxed);
    }

    fn push(&self, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() % self.frame_bytes != 0 {
            return Err("Decoder produced PCM that is not aligned to complete frames".into());
        }
        if self.eof.load(Ordering::Acquire) {
            return Err("PCM producer attempted to write after terminal state".into());
        }
        self.producer.lock().unwrap_or_else(|error| error.into_inner())
            .push_entire_slice(bytes)
            .map_err(|_| "PCM producer exceeded the bounded queue capacity".to_string())
    }

    fn finish(&self, error: Option<String>) {
        if let Some(message) = error {
            *self.error.lock().unwrap_or_else(|poison| poison.into_inner()) = Some(message);
        }
        self.eof.store(true, Ordering::Release);
    }

    fn take_frames(&self, requested: usize, allow_partial: bool) -> Option<Vec<u8>> {
        let mut consumer = self.consumer.lock().unwrap_or_else(|error| error.into_inner());
        let available_bytes = consumer.slots();
        let available_frames = available_bytes / self.frame_bytes;
        if available_frames < requested && !allow_partial { return None; }
        let frames = available_frames.min(requested);
        let byte_count = frames.checked_mul(self.frame_bytes)?;
        let mut bytes = vec![0; byte_count];
        let (popped, _) = consumer.pop_partial_slice(&mut bytes);
        bytes.truncate(popped.len());
        Some(bytes)
    }

    fn is_drained_eof(&self) -> bool {
        self.eof.load(Ordering::Acquire)
            && self.consumer.lock().unwrap_or_else(|error| error.into_inner()).slots() == 0
    }

    fn metrics(&self) -> (u64, u64, u64, u64, u64) {
        let calls = self.decoder_read_calls.load(Ordering::Relaxed);
        let total = self.decoder_read_total_us.load(Ordering::Relaxed);
        let max = self.decoder_read_max_us.load(Ordering::Relaxed);
        let starvation = self.starvation_count.load(Ordering::Relaxed);
        let minimum = self.queued_min_frames.load(Ordering::Relaxed);
        (calls, if calls == 0 { 0 } else { total / calls }, max, starvation,
            if minimum == u64::MAX { 0 } else { minimum })
    }
}

/// True when every service interval carries a whole number of frames.
#[cfg(any(target_os = "android", test))]
fn has_constant_packet_frames(sample_rate: u32, interval_us: u32) -> bool {
    (u64::from(sample_rate) * u64::from(interval_us)) % 1_000_000 == 0
}

/// Number of isochronous transfers to keep in flight for a stream, bounded to
/// [`MIN_TRANSFER_RING_SIZE`]..=[`MAX_TRANSFER_RING_SIZE`].
#[cfg(any(target_os = "android", test))]
fn transfer_ring_size(sample_rate: u32, interval_us: u32) -> usize {
    let target_us = if has_constant_packet_frames(sample_rate, interval_us) {
        CONSTANT_PACKET_RING_BUFFER_US
    } else {
        FRACTIONAL_PACKET_RING_BUFFER_US
    };
    transfer_ring_size_for(target_us, sample_rate, interval_us)
}

/// Ring depth for an explicit buffering target. The ring is additionally limited so
/// that it never holds more than half of the PCM queue, which keeps the producer
/// ahead of the submitter at high sample rates.
#[cfg(any(target_os = "android", test))]
fn transfer_ring_size_for(target_us: u64, sample_rate: u32, interval_us: u32) -> usize {
    let transfer_us = (PACKETS_PER_TRANSFER as u64).saturating_mul(u64::from(interval_us.max(1)));
    let wanted = ((target_us + transfer_us - 1) / transfer_us)
        .clamp(MIN_TRANSFER_RING_SIZE as u64, MAX_TRANSFER_RING_SIZE as u64) as usize;
    let frames_per_transfer = ((u64::from(sample_rate) * transfer_us + 999_999) / 1_000_000).max(1) as usize;
    let queue_limited = (PCM_QUEUE_CAPACITY_FRAMES / 2) / frames_per_transfer;
    wanted.min(queue_limited).max(MIN_TRANSFER_RING_SIZE)
}

/// Raises the scheduling priority of the calling thread (negative nice value).
///
/// Best effort: Android may refuse the request, in which case the thread keeps its
/// default priority and the outcome is logged.
#[cfg(target_os = "android")]
fn raise_current_thread_priority(label: &str, nice: i32) {
    let tid = unsafe { libc::gettid() };
    let result = unsafe { libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, nice) };
    let errno = if result == 0 { 0 } else { std::io::Error::last_os_error().raw_os_error().unwrap_or(-1) };
    log_usb_diagnostic(&format!(
        "USB_THREAD_PRIORITY thread={label} requestedNice={nice} result={result} errno={errno}"
    ));
}

#[cfg(target_os = "android")]
fn start_pcm_producer(
    decoder_handle: i64,
    config: StreamConfig,
    stop: Arc<AtomicBool>,
) -> Result<(Arc<PcmQueue>, JoinHandle<()>), String> {
    let frame_bytes = usize::from(config.channels)
        .checked_mul(usize::from(config.subslot_bytes))
        .ok_or("PCM producer frame size overflow")?;
    let queue = Arc::new(PcmQueue::new(frame_bytes)?);
    let producer_queue = Arc::clone(&queue);
    let worker = thread::Builder::new()
        .name("flowery-usb-pcm-decode".into())
        .spawn(move || {
            raise_current_thread_priority("flowery-usb-pcm-decode", PCM_DECODE_THREAD_NICE);
            while !stop.load(Ordering::Acquire) {
                if producer_queue.eof.load(Ordering::Acquire) { break; }
                if producer_queue.error.lock().unwrap_or_else(|error| error.into_inner()).is_some() { break; }
                let free_frames = producer_queue.producer.lock()
                    .unwrap_or_else(|error| error.into_inner()).slots()
                    / producer_queue.frame_bytes;
                if free_frames == 0 {
                    thread::sleep(Duration::from_millis(1));
                    continue;
                }
                let request_frames = free_frames.min(PCM_PRODUCER_CHUNK_FRAMES);
                let started = Instant::now();
                let decoded = crate::streaming::read_usb_pcm_chunk(
                    decoder_handle, request_frames, config.valid_bits,
                    config.subslot_bytes, config.data_format,
                );
                let elapsed = started.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
                producer_queue.decoder_read_calls.fetch_add(1, Ordering::Relaxed);
                producer_queue.decoder_read_total_us.fetch_add(elapsed, Ordering::Relaxed);
                producer_queue.decoder_read_max_us.fetch_max(elapsed, Ordering::Relaxed);
                match decoded {
                    Ok(bytes) if bytes.is_empty() => {
                        producer_queue.finish(None);
                        break;
                    }
                    Ok(bytes) => {
                        if let Err(message) = producer_queue.push(&bytes) {
                            producer_queue.finish(Some(message));
                            break;
                        }
                    }
                    Err(message) => {
                        producer_queue.finish(Some(message));
                        break;
                    }
                }
            }
        })
        .map_err(|error| error.to_string())?;
    Ok((queue, worker))
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(priority: i32, tag: *const c_char, text: *const c_char) -> i32;
}

#[cfg(target_os = "android")]
fn log_usb_diagnostic(message: &str) {
    let tag = CString::new("FloweryUsbNative").expect("static log tag");
    let safe_message = message.replace('\0', " ");
    let text = CString::new(safe_message).expect("NUL bytes were replaced");
    unsafe {
        __android_log_write(4, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(target_os = "android")]
fn transfer_packet_metrics(slot: &TransferSlot) -> (u64, u64, u32, i32) {
    unsafe {
        let transfer_status = (*slot.transfer).status as i32;
        let descriptors = (*slot.transfer).iso_packet_desc.as_ptr();
        let mut expected_bytes = 0u64;
        let mut actual_bytes = 0u64;
        let mut packet_errors = 0u32;
        for index in 0..PACKETS_PER_TRANSFER {
            let descriptor = &*descriptors.add(index);
            expected_bytes = expected_bytes.saturating_add(u64::from(descriptor.length));
            actual_bytes = actual_bytes.saturating_add(u64::from(descriptor.actual_length));
            if descriptor.status != 0 || descriptor.actual_length != descriptor.length {
                packet_errors = packet_errors.saturating_add(1);
            }
        }
        (expected_bytes, actual_bytes, packet_errors, transfer_status)
    }
}

/// True when a completed transfer succeeded at transfer and packet level and moved
/// exactly the submitted number of bytes, yet individual packets report a different
/// `actual_length` than their submitted `length`. The contiguous byte stream is then
/// intact and only packet boundaries differ, so audio content is preserved.
#[cfg(target_os = "android")]
fn transfer_length_mismatch_is_benign(slot: &TransferSlot) -> bool {
    unsafe {
        if (*slot.transfer).status as i32 != 0 { return false; }
        let descriptors = (*slot.transfer).iso_packet_desc.as_ptr();
        let mut expected = 0u64;
        let mut actual = 0u64;
        for index in 0..PACKETS_PER_TRANSFER {
            let descriptor = &*descriptors.add(index);
            if descriptor.status != 0 { return false; }
            expected = expected.saturating_add(u64::from(descriptor.length));
            actual = actual.saturating_add(u64::from(descriptor.actual_length));
        }
        expected == actual
    }
}

/// Per-packet `length/actual_length/status` of a completed transfer, for diagnostics.
#[cfg(target_os = "android")]
fn describe_transfer_packets(slot: &TransferSlot) -> String {
    unsafe {
        let descriptors = (*slot.transfer).iso_packet_desc.as_ptr();
        (0..PACKETS_PER_TRANSFER)
            .map(|index| {
                let descriptor = &*descriptors.add(index);
                format!("{}/{}/{}", descriptor.length, descriptor.actual_length, descriptor.status)
            })
            .collect::<Vec<_>>()
            .join(",")
    }
}

#[cfg(target_os = "android")]
struct TransferSignal {
    completed: AtomicBool,
    completed_at_us: AtomicU64,
}

#[cfg(target_os = "android")]
static CALLBACK_CLOCK_ORIGIN: OnceLock<Instant> = OnceLock::new();

#[cfg(target_os = "android")]
fn callback_clock_us() -> u64 {
    CALLBACK_CLOCK_ORIGIN.get_or_init(Instant::now)
        .elapsed().as_micros().min(u128::from(u64::MAX)) as u64
}

#[cfg(target_os = "android")]
struct TransferSlot {
    transfer: *mut libusb1_sys::libusb_transfer,
    buffer: ManuallyDrop<Vec<u8>>,
    signal: ManuallyDrop<Box<TransferSignal>>,
    session: ManuallyDrop<Arc<crate::usb_libusb::UsbSession>>,
    submitted: bool,
    audio_frames: u64,
    submitted_at: Option<Instant>,
    fill_duration_us: u64,
    zero_padded_frames: u64,
    completion_at_us: Option<u64>,
}

#[cfg(target_os = "android")]
struct FeedbackSlot {
    transfer: *mut libusb1_sys::libusb_transfer,
    buffer: ManuallyDrop<Vec<u8>>,
    signal: ManuallyDrop<Box<TransferSignal>>,
    session: ManuallyDrop<Arc<crate::usb_libusb::UsbSession>>,
    submitted: bool,
    submitted_at: Option<Instant>,
}

#[cfg(target_os = "android")]
impl Drop for TransferSlot {
    fn drop(&mut self) {
        if self.submitted {
            // libusb may still access the transfer, buffer, and callback state.
            // Retain the session/context as well; leaking is safer than unwinding
            // into a use-after-free if the worker panics before observing a callback.
            // Move every callback-visible allocation out of ManuallyDrop.
            // The submitted transfer itself is a raw pointer and remains
            // allocated; retain its buffer, callback state, and session too.
            let buffer = unsafe { ManuallyDrop::take(&mut self.buffer) };
            let signal = unsafe { ManuallyDrop::take(&mut self.signal) };
            let session = unsafe { ManuallyDrop::take(&mut self.session) };
            let _ = retain_submitted_resources(buffer, signal, session);
            return;
        }
        unsafe {
            ManuallyDrop::drop(&mut self.buffer);
            ManuallyDrop::drop(&mut self.signal);
            ManuallyDrop::drop(&mut self.session);
        }
    }
}

#[cfg(target_os = "android")]
impl Drop for FeedbackSlot {
    fn drop(&mut self) {
        if self.submitted {
            // Move every callback-visible allocation out of ManuallyDrop.
            // The submitted transfer itself is a raw pointer and remains
            // allocated; retain its buffer, callback state, and session too.
            let buffer = unsafe { ManuallyDrop::take(&mut self.buffer) };
            let signal = unsafe { ManuallyDrop::take(&mut self.signal) };
            let session = unsafe { ManuallyDrop::take(&mut self.session) };
            let _ = retain_submitted_resources(buffer, signal, session);
            return;
        }
        unsafe {
            ManuallyDrop::drop(&mut self.buffer);
            ManuallyDrop::drop(&mut self.signal);
            ManuallyDrop::drop(&mut self.session);
        }
    }
}

#[cfg(target_os = "android")]
extern "system" fn transfer_callback(transfer: *mut libusb1_sys::libusb_transfer) {
    if transfer.is_null() { return; }
    unsafe {
        let signal = (*transfer).user_data as *const TransferSignal;
        if !signal.is_null() {
            (*signal).completed_at_us.store(callback_clock_us(), Ordering::Relaxed);
            (*signal).completed.store(true, Ordering::Release);
        }
    }
}

#[cfg(target_os = "android")]
#[derive(Clone, Copy)]
struct StreamConfig {
    endpoint: u8,
    sample_rate: u32,
    channels: u8,
    valid_bits: u8,
    subslot_bytes: u8,
    data_format: u8,
    max_packet_bytes: u32,
    interval_us: u32,
    feedback_endpoint: Option<u8>,
    feedback_packet_bytes: Option<u16>,
    feedback_speed: FeedbackBusSpeed,
}

#[cfg(target_os = "android")]
struct UsbStreamWorker {
    session: i64,
    decoder_handle: i64,
    config: StreamConfig,
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    played_frames: Arc<AtomicU64>,
    paused: Arc<AtomicBool>,
    pause_drained: Arc<AtomicBool>,
    error: Arc<Mutex<String>>,
    thread: JoinHandle<()>,
}

#[cfg(target_os = "android")]
static WORKERS: OnceLock<Mutex<HashMap<i64, UsbStreamWorker>>> = OnceLock::new();
#[cfg(target_os = "android")]
static LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();
#[cfg(target_os = "android")]
static UNSAFE_SESSIONS: OnceLock<Mutex<HashSet<i64>>> = OnceLock::new();

#[cfg(target_os = "android")]
fn unsafe_sessions() -> &'static Mutex<HashSet<i64>> {
    UNSAFE_SESSIONS.get_or_init(|| Mutex::new(HashSet::new()))
}

#[cfg(target_os = "android")]
fn workers() -> &'static Mutex<HashMap<i64, UsbStreamWorker>> {
    WORKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[cfg(target_os = "android")]
fn ensure_session_safe(session: i64) -> Result<(), String> {
    if unsafe_sessions().lock().unwrap_or_else(|e| e.into_inner()).contains(&session) {
        return Err(
            "USB session previously failed to drain outstanding transfers and cannot be restarted; close it while retaining the Android USB connection"
                .into(),
        );
    }
    Ok(())
}

#[cfg(target_os = "android")]
fn lifecycle() -> &'static Mutex<()> {
    LIFECYCLE.get_or_init(|| Mutex::new(()))
}

#[cfg(target_os = "android")]
fn set_error(error: &Arc<Mutex<String>>, status: &AtomicI32, message: String) {
    crate::usb_isochronous::preserve_first_error(
        &mut error.lock().unwrap_or_else(|e| e.into_inner()),
        message,
    );
    // Do not downgrade a terminal unsafe-teardown state or overwrite a
    // completed/stopped state if an asynchronous diagnostic arrives late.
    let _ = status.compare_exchange(0, -1, Ordering::AcqRel, Ordering::Acquire);
}

#[cfg(target_os = "android")]
fn create_slot(
    session: Arc<crate::usb_libusb::UsbSession>,
    endpoint: u8,
    max_packet_bytes: u32,
) -> Result<TransferSlot, String> {
    let handle = session.handle;
    let buffer_len = (max_packet_bytes as usize)
        .checked_mul(PACKETS_PER_TRANSFER)
        .ok_or("USB transfer buffer size overflow")?;
    if buffer_len > i32::MAX as usize {
        return Err("USB transfer buffer exceeds libusb limits".into());
    }
    let mut buffer = vec![0_u8; buffer_len];
    let signal = Box::new(TransferSignal { completed: AtomicBool::new(false), completed_at_us: AtomicU64::new(0) });
    let transfer = unsafe { libusb1_sys::libusb_alloc_transfer(PACKETS_PER_TRANSFER as i32) };
    if transfer.is_null() {
        return Err("libusb_alloc_transfer returned null".into());
    }
    unsafe {
        libusb1_sys::libusb_fill_iso_transfer(
            transfer,
            handle,
            endpoint,
            buffer.as_mut_ptr(),
            buffer_len as i32,
            PACKETS_PER_TRANSFER as i32,
            transfer_callback,
            (&*signal as *const TransferSignal).cast_mut().cast(),
            0,
        );
    }
    Ok(TransferSlot { transfer, buffer: ManuallyDrop::new(buffer), signal: ManuallyDrop::new(signal), session: ManuallyDrop::new(session), submitted: false, audio_frames: 0, submitted_at: None, fill_duration_us: 0, zero_padded_frames: 0, completion_at_us: None })
}

#[cfg(target_os = "android")]
fn create_feedback_slot(
    session: Arc<crate::usb_libusb::UsbSession>,
    endpoint: u8,
    packet_bytes: u16,
) -> Result<FeedbackSlot, String> {
    let handle = session.handle;
    let mut buffer = vec![0_u8; usize::from(packet_bytes)];
    let signal = Box::new(TransferSignal { completed: AtomicBool::new(false), completed_at_us: AtomicU64::new(0) });
    let transfer = unsafe { libusb1_sys::libusb_alloc_transfer(1) };
    if transfer.is_null() {
        return Err("libusb_alloc_transfer failed for the feedback endpoint".into());
    }
    unsafe {
        libusb1_sys::libusb_fill_iso_transfer(
            transfer, handle, endpoint, buffer.as_mut_ptr(), buffer.len() as i32,
            1, transfer_callback, (&*signal as *const TransferSignal).cast_mut().cast(), 0,
        );
        // libusb_fill_iso_transfer configures the transfer but does not set
        // individual isochronous packet lengths.
        // libusb exposes iso_packet_desc as a C flexible array. Its Rust
        // binding has length zero, so address the allocated trailing element
        // through the base pointer instead of indexing the zero-length field.
        let descriptor = (*transfer).iso_packet_desc.as_mut_ptr();
        (*descriptor).length = u32::from(packet_bytes);
    }
    Ok(FeedbackSlot { transfer, buffer: ManuallyDrop::new(buffer), signal: ManuallyDrop::new(signal), session: ManuallyDrop::new(session), submitted: false, submitted_at: None })
}

#[cfg(target_os = "android")]
fn submit_feedback(slot: &mut FeedbackSlot) -> Result<(), String> {
    slot.signal.completed.store(false, Ordering::Release);
    let result = unsafe { libusb1_sys::libusb_submit_transfer(slot.transfer) };
    if result != 0 {
        return Err(format!("Unable to submit USB feedback transfer: libusb error {result}"));
    }
    slot.submitted = true;
    slot.submitted_at = Some(Instant::now());
    Ok(())
}

#[cfg(target_os = "android")]
fn fill_and_submit(
    slot: &mut TransferSlot,
    config: StreamConfig,
    pcm_queue: &PcmQueue,
    scheduler: &mut PacketFrameScheduler,
    eof: &mut bool,
    feedback_rate_hz: Option<f64>,
) -> Result<bool, String> {
    let frame_bytes = usize::from(config.channels)
        .checked_mul(usize::from(config.subslot_bytes))
        .ok_or("USB PCM frame size overflow")?;
    if frame_bytes == 0 { return Err("USB PCM frame size is zero".into()); }

    let (queued_frames, producer_eof, producer_error) = pcm_queue.occupancy();
    if queued_frames > 0 {
        pcm_queue.record_occupancy(queued_frames);
    }
    if let Some(message) = producer_error {
        return Err(format!("USB PCM decoder producer failed: {message}"));
    }

    // Keep a complete transfer queued before advancing the fractional scheduler.
    // The margin covers fractional packet scheduling and small feedback changes.
    let conservative_packet_frames = (
        (f64::from(config.sample_rate) * 1.10 * f64::from(config.interval_us) / 1_000_000.0)
            .ceil() as usize
    ).saturating_add(1);
    let minimum_frames = conservative_packet_frames.saturating_mul(PACKETS_PER_TRANSFER);
    if queued_frames < minimum_frames && !producer_eof {
        pcm_queue.record_starvation();
        return Ok(false);
    }

    let fill_started_at = Instant::now();
    let mut scheduled_packet_frames = [0usize; PACKETS_PER_TRANSFER];
    let mut scheduled_frames = 0usize;
    for (packet_index, packet_frames) in scheduled_packet_frames.iter_mut().enumerate() {
        let frames = scheduler.next_frames(
            feedback_rate_hz.unwrap_or(f64::from(config.sample_rate)),
            config.interval_us,
        )?;
        *packet_frames = frames;
        scheduled_frames = scheduled_frames.checked_add(frames)
            .ok_or("USB transfer frame count overflow")?;
        let expected = frames.checked_mul(frame_bytes).ok_or("USB packet byte count overflow")?;
        if expected > config.max_packet_bytes as usize {
            return Err(format!(
                "USB packet {packet_index} requires {expected} bytes; endpoint permits {}",
                config.max_packet_bytes
            ));
        }
    }

    let Some(pcm) = pcm_queue.take_frames(scheduled_frames, producer_eof) else {
        pcm_queue.record_starvation();
        return Ok(false);
    };
    if pcm.is_empty() {
        if pcm_queue.is_drained_eof() { *eof = true; }
        return Ok(false);
    }
    if pcm.len() % frame_bytes != 0 {
        return Err("PCM queue returned a partial USB frame".into());
    }

    slot.buffer.fill(0);
    let mut source_offset = 0usize;
    let mut buffer_offset = 0usize;
    for (packet_index, frames) in scheduled_packet_frames.iter().copied().enumerate() {
        let expected = frames.checked_mul(frame_bytes).ok_or("USB packet byte count overflow")?;
        let copy_bytes = pcm.len().saturating_sub(source_offset).min(expected);
        if copy_bytes > 0 {
            let source_end = source_offset.checked_add(copy_bytes).ok_or("PCM source offset overflow")?;
            let destination_end = buffer_offset.checked_add(copy_bytes)
                .ok_or("USB packet destination offset overflow")?;
            slot.buffer[buffer_offset..destination_end]
                .copy_from_slice(&pcm[source_offset..source_end]);
            source_offset = source_end;
        }
        let descriptor = unsafe { (*slot.transfer).iso_packet_desc.as_mut_ptr().add(packet_index) };
        unsafe { (*descriptor).length = expected as u32; }
        buffer_offset = buffer_offset.checked_add(expected)
            .ok_or("USB packet buffer offset overflow")?;
    }

    slot.audio_frames = (pcm.len() / frame_bytes) as u64;
    slot.zero_padded_frames = scheduled_frames.saturating_sub(pcm.len() / frame_bytes) as u64;
    slot.fill_duration_us = fill_started_at.elapsed().as_micros().min(u128::from(u64::MAX)) as u64;
    *eof = pcm_queue.is_drained_eof();
    slot.signal.completed.store(false, Ordering::Release);
    let result = unsafe { libusb1_sys::libusb_submit_transfer(slot.transfer) };
    if result != 0 {
        return Err(format!("libusb_submit_transfer failed with code {result}"));
    }
    slot.submitted = true;
    slot.submitted_at = Some(Instant::now());
    Ok(true)
}

#[cfg(target_os = "android")]
fn transfer_packets_succeeded(slot: &TransferSlot) -> Result<(), String> {
    unsafe {
        let transfer_status = (*slot.transfer).status as i32;
        let descriptors = (*slot.transfer).iso_packet_desc.as_ptr();
        let mut packet_index = 0usize;
        let packets = std::iter::from_fn(|| {
            if packet_index >= PACKETS_PER_TRANSFER {
                return None;
            }
            let descriptor = &*descriptors.add(packet_index);
            packet_index += 1;
            Some(crate::usb_isochronous::IsochronousPacketCompletion {
                status: descriptor.status as i32,
                actual_length: descriptor.actual_length,
                expected_length: descriptor.length,
            })
        });
        crate::usb_isochronous::validate_isochronous_completion_iter(transfer_status, packets)
    }
}

#[cfg(target_os = "android")]
fn run_worker(
    session: Arc<crate::usb_libusb::UsbSession>,
    decoder_handle: i64,
    config: StreamConfig,
    stop: Arc<AtomicBool>,
    status: Arc<AtomicI32>,
    played_frames: Arc<AtomicU64>,
    paused: Arc<AtomicBool>,
    pause_drained: Arc<AtomicBool>,
    error: Arc<Mutex<String>>,
) {
    // Initialize the monotonic callback clock before any transfer can complete.
    let _ = CALLBACK_CLOCK_ORIGIN.get_or_init(Instant::now);

    // Validate worker-local control state before allocating or submitting any
    // libusb transfer. A panic after submission could unwind callback-owned
    // buffers while libusb still references them.
    let mut feedback_tracker = match FeedbackRateTracker::new(0.25) {
        Ok(tracker) => tracker,
        Err(message) => {
            set_error(&error, &status, format!("Unable to initialize USB feedback tracker: {message}"));
            return;
        }
    };
    let mut feedback_outlier_guard = match FeedbackOutlierGuard::new(3) {
        Ok(guard) => guard,
        Err(message) => {
            set_error(&error, &status, format!("Unable to initialize USB feedback outlier guard: {message}"));
            return;
        }
    };
    let producer_stop = Arc::new(AtomicBool::new(false));
    let (pcm_queue, producer_thread) = match start_pcm_producer(
        decoder_handle, config, Arc::clone(&producer_stop),
    ) {
        Ok(worker) => worker,
        Err(message) => {
            set_error(&error, &status, format!("Unable to start USB PCM decoder producer: {message}"));
            return;
        }
    };
    let ring_size = transfer_ring_size(config.sample_rate, config.interval_us);
    log_usb_diagnostic(&format!(
        "USB_TRANSFER_RING slots={} packetsPerTransfer={} bufferedUs={}",
        ring_size, PACKETS_PER_TRANSFER,
        (ring_size as u64) * (PACKETS_PER_TRANSFER as u64) * u64::from(config.interval_us)
    ));
    let mut slots = Vec::with_capacity(ring_size);
    for _ in 0..ring_size {
        match create_slot(Arc::clone(&session), config.endpoint, config.max_packet_bytes) {
            Ok(slot) => slots.push(slot),
            Err(message) => {
                producer_stop.store(true, Ordering::Release);
                let _ = producer_thread.join();
                set_error(&error, &status, message);
                for slot in slots.drain(..) {
                    unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
                }
                return;
            }
        }
    }

    let mut feedback_slot = match (config.feedback_endpoint, config.feedback_packet_bytes) {
        (Some(endpoint), Some(packet_bytes)) => match create_feedback_slot(Arc::clone(&session), endpoint, packet_bytes) {
            Ok(mut slot) => {
                if let Err(message) = submit_feedback(&mut slot) {
                    producer_stop.store(true, Ordering::Release);
                    let _ = producer_thread.join();
                    set_error(&error, &status, message);
                    unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
                    for audio_slot in slots.drain(..) {
                        unsafe { libusb1_sys::libusb_free_transfer(audio_slot.transfer); }
                    }
                    return;
                }
                Some(slot)
            }
            Err(message) => {
                producer_stop.store(true, Ordering::Release);
                let _ = producer_thread.join();
                set_error(&error, &status, message);
                for slot in slots.drain(..) {
                    unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
                }
                return;
            }
        },
        _ => None,
    };
    let mut feedback_rate_hz: Option<f64> = None;
    let mut last_feedback_at: Option<Instant> = None;
    let mut feedback_samples = 0u64;
    let mut last_feedback_raw = String::from("none");
    let mut completed_transfers = 0u64;
    let mut completed_frames = 0u64;
    let mut expected_bytes = 0u64;
    let mut actual_bytes = 0u64;
    let mut packet_errors = 0u64;
    let mut fill_calls = 0u64;
    let mut fill_total_us = 0u64;
    let mut fill_max_us = 0u64;
    let mut submit_gap_max_us = 0u64;
    let mut last_submit_at: Option<Instant> = None;
    let mut event_loop_max_us = 0u64;
    let mut pending_min = usize::MAX;
    let mut active_slots_min = usize::MAX;
    let mut ring_dry_events = 0u64;
    let mut idle_wait_iterations = 0u64;
    let mut idle_wait_log_count = 0u32;
    let mut length_mismatch_transfers = 0u64;
    let mut callback_dispatch_max_us = 0u64;
    let mut callback_to_resubmit_max_us = 0u64;
    let mut callback_to_resubmit_total_us = 0u64;
    let mut callback_to_resubmit_count = 0u64;
    let mut zero_padded_frames = 0u64;
    let mut last_diagnostic_at = Instant::now();
    let mut feedback_cancelling = false;
    let mut packet_scheduler = PacketFrameScheduler::default();
    let mut eof = false;
    if crate::usb_feedback::audio_clock_is_ready(feedback_slot.is_some(), feedback_rate_hz) {
        for slot in &mut slots {
            if eof || paused.load(Ordering::Acquire) { break; }
            match fill_and_submit(slot, config, &pcm_queue, &mut packet_scheduler, &mut eof, feedback_rate_hz) {
                Ok(true) => {}
                Ok(false) => break,
                Err(message) => { set_error(&error, &status, message); break; }
            }
            fill_calls = fill_calls.saturating_add(1);
            fill_total_us = fill_total_us.saturating_add(slot.fill_duration_us);
            fill_max_us = fill_max_us.max(slot.fill_duration_us);
            zero_padded_frames = zero_padded_frames.saturating_add(slot.zero_padded_frames);
            let submitted_at = Instant::now();
            if let Some(previous) = last_submit_at {
                submit_gap_max_us = submit_gap_max_us.max(previous.elapsed().as_micros().min(u128::from(u64::MAX)) as u64);
            }
            last_submit_at = Some(submitted_at);
        }
    }

    let mut cancelling = false;
    let mut drain_started_at: Option<Instant> = None;
    let mut unsafe_to_free = false;
    loop {
        let (_, _, producer_error) = pcm_queue.occupancy();
        if status.load(Ordering::Acquire) >= 0 {
            if let Some(message) = producer_error {
                set_error(&error, &status, format!("USB PCM decoder producer failed: {message}"));
            }
        }
        if let Some(started_at) = drain_started_at {
            let pending = slots.iter().any(|slot| slot.submitted)
                || feedback_slot.as_ref().map(|slot| slot.submitted).unwrap_or(false);
            if pending && crate::usb_isochronous::transfer_drain_timed_out(started_at.elapsed(), TRANSFER_DRAIN_TIMEOUT) {
                set_error(&error, &status, "Timed out draining cancelled USB transfers; retaining transfer buffers and libusb session to prevent use-after-free".into());
                // Distinct terminal state: this session must not be reused for seek/restart.
                status.store(-2, Ordering::Release);
                unsafe_to_free = true;
                break;
            }
        }
        if !cancelling {
            let audio_timed_out = slots.iter().any(|slot| {
                slot.submitted_at.map(|submitted| submitted.elapsed() > TRANSFER_COMPLETION_TIMEOUT).unwrap_or(false)
            });
            let feedback_timed_out = feedback_slot.as_ref().map(|slot| {
                slot.submitted && slot.submitted_at
                    .map(|submitted| submitted.elapsed() > TRANSFER_COMPLETION_TIMEOUT)
                    .unwrap_or(false)
            }).unwrap_or(false);
            if audio_timed_out {
                set_error(&error, &status, "USB isochronous OUT transfer timed out".into());
            } else if feedback_timed_out {
                set_error(&error, &status, "USB explicit feedback transfer timed out".into());
            }
            if feedback_rate_hz.is_some()
                && crate::usb_feedback::feedback_clock_is_stale(
                    last_feedback_at.map(|received| received.elapsed()),
                    TRANSFER_COMPLETION_TIMEOUT,
                )
            {
                set_error(&error, &status, "USB explicit feedback clock estimate became stale".into());
            }
        }
        if stop.load(Ordering::Acquire) || status.load(Ordering::Acquire) < 0 {
            if !cancelling {
                cancelling = true;
                producer_stop.store(true, Ordering::Release);
                drain_started_at = Some(Instant::now());
                for (index, slot) in slots.iter().enumerate() {
                    if slot.submitted {
                        let result = unsafe { libusb1_sys::libusb_cancel_transfer(slot.transfer) };
                        // NOT_FOUND means libusb has already removed the transfer
                        // from its active set; its completion callback still owns
                        // the slot until the worker observes that callback.
                        if let Some(code) = crate::usb_isochronous::cancellation_request_error(result) {
                            set_error(
                                &error,
                                &status,
                                format!("Unable to cancel USB audio transfer {index}: libusb error {code}; waiting for callback before teardown"),
                            );
                        }
                    }
                }
                if let Some(feedback) = feedback_slot.as_mut() {
                    if feedback.submitted {
                        let result = unsafe { libusb1_sys::libusb_cancel_transfer(feedback.transfer) };
                        if let Some(code) = crate::usb_isochronous::cancellation_request_error(result) {
                            set_error(
                                &error,
                                &status,
                                format!("Unable to cancel USB feedback transfer: libusb error {code}; waiting for callback before teardown"),
                            );
                        }
                    }
                    feedback_cancelling = true;
                }
            }
        }
        if eof && !slots.iter().any(|slot| slot.submitted) {
            if let Some(feedback) = feedback_slot.as_mut() {
                if feedback.submitted && !feedback_cancelling {
                    let result = unsafe { libusb1_sys::libusb_cancel_transfer(feedback.transfer) };
                    if let Some(code) = crate::usb_isochronous::cancellation_request_error(result) {
                        set_error(
                            &error,
                            &status,
                            format!("Unable to cancel completed-stream feedback transfer: libusb error {code}; waiting for callback before teardown"),
                        );
                    }
                    feedback_cancelling = true;
                }
            }
        }
        let active_before_refill = slots.iter().filter(|slot| slot.submitted).count();
        active_slots_min = active_slots_min.min(active_before_refill);
        // A ring with no submitted transfer while audio should be flowing means the
        // host controller had nothing to play: an audible gap.
        if active_before_refill == 0
            && completed_transfers > slots.len() as u64
            && !eof
            && !paused.load(Ordering::Acquire)
        {
            ring_dry_events = ring_dry_events.saturating_add(1);
            if ring_dry_events <= 20 || ring_dry_events % 100 == 0 {
                log_usb_diagnostic(&format!(
                    "USB_RING_DRY event={} completedTransfers={} sinceLastSubmitUs={}",
                    ring_dry_events, completed_transfers,
                    last_submit_at.map(|t| t.elapsed().as_micros()).unwrap_or(0)
                ));
            }
        }
        if crate::usb_isochronous::audio_submission_allowed(
            paused.load(Ordering::Acquire), cancelling, eof,
        ) && crate::usb_feedback::audio_clock_is_ready(feedback_slot.is_some(), feedback_rate_hz) {
            for slot in &mut slots {
                if !slot.submitted {
                    match fill_and_submit(slot, config, &pcm_queue, &mut packet_scheduler, &mut eof, feedback_rate_hz) {
                        Ok(true) => {}
                        Ok(false) => break,
                        Err(message) => { set_error(&error, &status, message); break; }
                    }
                    if let Some(completed_at_us) = slot.completion_at_us.take() {
                        let elapsed_us = callback_clock_us().saturating_sub(completed_at_us);
                        callback_to_resubmit_max_us = callback_to_resubmit_max_us.max(elapsed_us);
                        callback_to_resubmit_total_us = callback_to_resubmit_total_us.saturating_add(elapsed_us);
                        callback_to_resubmit_count = callback_to_resubmit_count.saturating_add(1);
                    }
                    fill_calls = fill_calls.saturating_add(1);
                    fill_total_us = fill_total_us.saturating_add(slot.fill_duration_us);
                    fill_max_us = fill_max_us.max(slot.fill_duration_us);
                    zero_padded_frames = zero_padded_frames.saturating_add(slot.zero_padded_frames);
                    let submitted_at = Instant::now();
                    if let Some(previous) = last_submit_at {
                        submit_gap_max_us = submit_gap_max_us.max(previous.elapsed().as_micros().min(u128::from(u64::MAX)) as u64);
                    }
                    last_submit_at = Some(submitted_at);
                    if eof { break; }
                }
            }
        }
        let audio_pending = slots.iter().any(|slot| slot.submitted);
        pause_drained.store(
            crate::usb_isochronous::pause_is_drained(paused.load(Ordering::Acquire), audio_pending),
            Ordering::Release,
        );
        let feedback_pending = feedback_slot.as_ref().map(|slot| slot.submitted).unwrap_or(false);
        if !audio_pending && !feedback_pending {
            if cancelling || eof || status.load(Ordering::Acquire) < 0 { break; }
            // No transfer is in flight and the stream has not ended. Record the PCM queue
            // state so a worker waiting for data that never arrives can be identified.
            idle_wait_iterations = idle_wait_iterations.saturating_add(1);
            if idle_wait_log_count < 10 && idle_wait_iterations % 1_000 == 1 {
                idle_wait_log_count += 1;
                let (queued_frames, producer_eof, producer_error) = pcm_queue.occupancy();
                log_usb_diagnostic(&format!(
                    "USB_STREAM_IDLE_WAIT paused={} queuedFrames={} producerEof={} producerError={} completedTransfers={}",
                    paused.load(Ordering::Acquire), queued_frames, producer_eof,
                    producer_error.as_deref().unwrap_or("none"), completed_transfers
                ));
            }
            thread::sleep(Duration::from_millis(1)); continue;
        }
        let timeout = libc::timeval { tv_sec: 0, tv_usec: 10_000 };
        let event_started_at = Instant::now();
        let event_result = unsafe { libusb1_sys::libusb_handle_events_timeout_completed(session.context, &timeout, std::ptr::null_mut()) };
        event_loop_max_us = event_loop_max_us.max(event_started_at.elapsed().as_micros().min(u128::from(u64::MAX)) as u64);
        if crate::usb_isochronous::event_loop_interrupted(event_result) {
            // INTERRUPTED is retryable, but repeated signals must not turn the
            // event loop into a busy spin while transfers remain outstanding.
            thread::sleep(Duration::from_millis(1));
        } else if event_result < 0 {
            set_error(&error, &status, crate::usb_isochronous::event_error_message(event_result));
            // Do not skip completion-signal inspection after an event-loop error:
            // a callback may have completed before libusb returned the error.
            // The next iteration enters cancellation and keeps pumping events
            // until every submitted transfer is acknowledged or the drain times out.
            thread::sleep(Duration::from_millis(1));
        }
        if let Some(feedback) = feedback_slot.as_mut() {
            if feedback.submitted && feedback.signal.completed.swap(false, Ordering::AcqRel) {
                feedback.submitted = false;
                feedback.submitted_at = None;
                if !cancelling && !feedback_cancelling {
                    let transfer_status = unsafe { (*feedback.transfer).status };
                    let descriptor = unsafe { &*(*feedback.transfer).iso_packet_desc.as_ptr() };
                    if transfer_status != 0 || descriptor.status != 0 {
                        set_error(&error, &status, format!(
                            "USB feedback transfer failed (transfer status {transfer_status}, packet status {})",
                            descriptor.status
                        ));
                    } else if descriptor.actual_length as usize != feedback.buffer.len() {
                        set_error(&error, &status, format!(
                            "USB feedback packet length mismatch: received {}, expected {}",
                            descriptor.actual_length, feedback.buffer.len()
                        ));
                    } else {
                        match crate::usb_feedback::decode_feedback_rate_hz(&feedback.buffer, config.feedback_speed) {
                            Ok(measured) => {
                                feedback_samples = feedback_samples.saturating_add(1);
                                last_feedback_raw = feedback.buffer.iter()
                                    .map(|byte| format!("{byte:02x}"))
                                    .collect::<String>();
                                if let Err(message) = crate::usb_feedback::validate_feedback_rate_hz(config.sample_rate, measured) {
                                    set_error(&error, &status, message);
                                } else {
                                    match feedback_tracker.update_checked(measured, 0.02) {
                                        Ok(Some(estimate)) => {
                                            feedback_rate_hz = Some(estimate);
                                            last_feedback_at = Some(Instant::now());
                                            feedback_outlier_guard.record_accepted();
                                        }
                                        Ok(None) => {
                                            if feedback_outlier_guard.record_outlier() {
                                                set_error(&error, &status, "USB feedback clock produced repeated abrupt rate changes".into());
                                            }
                                        }
                                        Err(message) => set_error(&error, &status, message),
                                    }
                                }
                            }
                            Err(message) => set_error(&error, &status, message),
                        }
                    }
                    if status.load(Ordering::Acquire) >= 0 && !eof {
                        if let Err(message) = submit_feedback(feedback) {
                            set_error(&error, &status, message);
                        }
                    }
                }
            }
        }
        let slots_len = slots.len();
        for (slot_index, slot) in slots.iter_mut().enumerate() {
            if !slot.submitted || !slot.signal.completed.swap(false, Ordering::AcqRel) { continue; }
            slot.submitted = false;
            slot.submitted_at = None;
            let completed_at_us = slot.signal.completed_at_us.load(Ordering::Acquire);
            let observed_at_us = callback_clock_us();
            callback_dispatch_max_us = callback_dispatch_max_us.max(observed_at_us.saturating_sub(completed_at_us));
            slot.completion_at_us = Some(completed_at_us);
            if !cancelling {
                let (transfer_expected, transfer_actual, transfer_packet_errors, transfer_status) =
                    transfer_packet_metrics(slot);
                completed_transfers = completed_transfers.saturating_add(1);
                completed_frames = completed_frames.saturating_add(slot.audio_frames);
                expected_bytes = expected_bytes.saturating_add(transfer_expected);
                actual_bytes = actual_bytes.saturating_add(transfer_actual);
                packet_errors = packet_errors.saturating_add(u64::from(transfer_packet_errors));
                if let Err(message) = transfer_packets_succeeded(slot) {
                    // Strict validation applies to the minimum ring. With a deeper ring,
                    // a length-only mismatch that preserves the byte total is counted and
                    // logged instead of aborting playback.
                    let tolerated = slots_len > MIN_TRANSFER_RING_SIZE
                        && transfer_length_mismatch_is_benign(slot);
                    if tolerated {
                        length_mismatch_transfers = length_mismatch_transfers.saturating_add(1);
                        if length_mismatch_transfers <= 10 {
                            log_usb_diagnostic(&format!(
                                "USB_PACKET_LENGTH_MISMATCH_TOLERATED event={} slot={} ringSize={} completedTransfers={} audioFrames={} packets(length/actual/status)={}",
                                length_mismatch_transfers, slot_index, slots_len,
                                completed_transfers, slot.audio_frames,
                                describe_transfer_packets(slot)
                            ));
                        }
                    } else {
                        log_usb_diagnostic(&format!(
                            "TRANSFER_INVALID endpoint=0x{:02x} transferStatus={} packetErrors={} expectedBytes={} actualBytes={}",
                            config.endpoint, transfer_status, transfer_packet_errors,
                            transfer_expected, transfer_actual
                        ));
                        log_usb_diagnostic(&format!(
                            "USB_TRANSFER_INVALID_DETAIL slot={} ringSize={} completedTransfers={} audioFrames={} packets(length/actual/status)={}",
                            slot_index, slots_len, completed_transfers, slot.audio_frames,
                            describe_transfer_packets(slot)
                        ));
                        set_error(&error, &status, message);
                        continue;
                    }
                }
                played_frames.fetch_add(slot.audio_frames, Ordering::AcqRel);
            }
        }
        let pending_now = slots.iter().filter(|slot| slot.submitted).count();
        pending_min = pending_min.min(pending_now);
        active_slots_min = active_slots_min.min(pending_now);
        if last_diagnostic_at.elapsed() >= Duration::from_secs(5) {
            let (decoder_read_calls, decoder_read_avg_us, decoder_read_max_us,
                pcm_starvation_count, pcm_queued_min_frames) = pcm_queue.metrics();
            log_usb_diagnostic(&format!(
                "TRANSFER_SUMMARY endpoint=0x{:02x} sampleRate={} channels={} intervalUs={} maxPacketBytes={} busSpeed={:?} completedTransfers={} completedFrames={} expectedBytes={} actualBytes={} packetErrors={} feedbackSamples={} feedbackRaw={} feedbackRateHz={} playedFrames={} fillCalls={} fillAvgUs={} fillMaxUs={} submitGapMaxUs={} pendingMin={} activeSlotsMin={} callbackDispatchMaxUs={} callbackToResubmitAvgUs={} callbackToResubmitMaxUs={} callbackToResubmitCount={} zeroPaddedFrames={} eventLoopMaxUs={} decoderReadCalls={} decoderReadAvgUs={} decoderReadMaxUs={} pcmQueueWaits={} pcmQueuedMinFrames={} ringDryEvents={} lengthMismatchTransfers={}",
                config.endpoint, config.sample_rate, config.channels, config.interval_us,
                config.max_packet_bytes, config.feedback_speed, completed_transfers,
                completed_frames, expected_bytes, actual_bytes, packet_errors,
                feedback_samples, last_feedback_raw,
                feedback_rate_hz.map(|rate| format!("{rate:.3}")).unwrap_or_else(|| "none".into()),
                played_frames.load(Ordering::Acquire),
                fill_calls,
                if fill_calls == 0 { 0 } else { fill_total_us / fill_calls },
                fill_max_us,
                submit_gap_max_us,
                if pending_min == usize::MAX { 0 } else { pending_min },
                if active_slots_min == usize::MAX { 0 } else { active_slots_min },
                callback_dispatch_max_us,
                if callback_to_resubmit_count == 0 { 0 } else { callback_to_resubmit_total_us / callback_to_resubmit_count },
                callback_to_resubmit_max_us,
                callback_to_resubmit_count,
                zero_padded_frames,
                event_loop_max_us,
                decoder_read_calls, decoder_read_avg_us, decoder_read_max_us,
                pcm_starvation_count, pcm_queued_min_frames, ring_dry_events, length_mismatch_transfers
            ));
            last_diagnostic_at = Instant::now();
        }
    }

    producer_stop.store(true, Ordering::Release);
    if producer_thread.join().is_err() {
        set_error(&error, &status, "USB PCM decoder producer thread panicked".into());
    }

    if unsafe_to_free {
        // A callback may still dereference user_data and the transfer buffer.
        // Leak only outstanding slots and retain the session/context; safety is
        // preferred over reclaiming resources after a broken libusb event loop.
        if let Some(feedback) = feedback_slot {
            if feedback.submitted {
                std::mem::forget(feedback);
            } else {
                unsafe { libusb1_sys::libusb_free_transfer(feedback.transfer); }
            }
        }
        for slot in slots {
            if slot.submitted {
                std::mem::forget(slot);
            } else {
                unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
            }
        }
        std::mem::forget(session);
        return;
    }
    if let Some(feedback) = feedback_slot {
        unsafe { libusb1_sys::libusb_free_transfer(feedback.transfer); }
    }
    for slot in slots {
        unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
    }
    log_usb_diagnostic(&format!(
        "USB_STREAM_END eof={} stopRequested={} status={} completedTransfers={} playedFrames={}",
        eof, stop.load(Ordering::Acquire), status.load(Ordering::Acquire),
        completed_transfers, played_frames.load(Ordering::Acquire)
    ));
    if status.load(Ordering::Acquire) >= 0 {
        status.store(if stop.load(Ordering::Acquire) { 2 } else { 1 }, Ordering::Release);
    }
}

#[cfg(target_os = "android")]
fn stop_worker_locked(session: i64) -> Result<(), String> {
    let worker = workers().lock().unwrap_or_else(|e| e.into_inner()).remove(&session);
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);

        // Joining directly can block forever if the worker is stuck outside
        // libusb's bounded event loop (for example while producing a decoder
        // chunk). Bound the caller's wait as well as the transfer-drain wait.
        let join_deadline = Instant::now() + WORKER_JOIN_TIMEOUT;
        while !worker.thread.is_finished() && Instant::now() < join_deadline {
            thread::sleep(Duration::from_millis(1));
        }
        if !worker.thread.is_finished() {
            // Dropping this JoinHandle detaches the still-running worker. Keep
            // the registry session and Java fd alive permanently: that worker
            // may still access the libusb context or device handle.
            unsafe_sessions().lock().unwrap_or_else(|e| e.into_inner()).insert(session);
            return Err(
                "USB stream worker did not stop before the bounded join deadline; retaining the Android USB connection to prevent use-after-free"
                    .into(),
            );
        }

        let worker_panicked = worker.thread.join().is_err();
        let drain_timed_out = worker.status.load(Ordering::Acquire) == -2;
        if let Some(message) = crate::usb_isochronous::worker_teardown_error(
            worker_panicked,
            drain_timed_out,
        ) {
            // Both a panic and an incomplete cancellation drain leave native
            // ownership uncertain. Keep the registry session and Java fd alive.
            unsafe_sessions().lock().unwrap_or_else(|e| e.into_inner()).insert(session);
            return Err(message.into());
        }
    }
    Ok(())
}

#[cfg(target_os = "android")]
pub(super) fn bus_speed(session_handle: i64) -> i32 {
    // Serialize the handle query with close so the Java-owned fd cannot be
    // released while libusb is resolving the device speed.
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    let Some(session) = crate::usb_libusb::session_ref(session_handle) else {
        return 0;
    };
    unsafe {
        let device = libusb1_sys::libusb_get_device(session.handle);
        if device.is_null() { 0 } else { libusb1_sys::libusb_get_device_speed(device) }
    }
}

#[cfg(target_os = "android")]
pub(super) fn stop_session(session: i64) -> Result<(), String> {
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    stop_worker_locked(session)
}

/// Stop the worker and remove the session while holding the same lifecycle
/// gate used by start/seek. This closes the lookup-vs-close race.
#[cfg(target_os = "android")]
pub(super) fn close_session(session: i64) -> Result<(), String> {
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    stop_worker_locked(session)?;
    if unsafe_sessions().lock().unwrap_or_else(|e| e.into_inner()).contains(&session) {
        return Err("USB session has outstanding transfers that could not be drained; retain the Android USB connection".into());
    }
    crate::usb_libusb::remove_session(session);
    Ok(())
}

#[cfg(target_os = "android")]
fn spawn_worker(
    session_handle: i64,
    session: Arc<crate::usb_libusb::UsbSession>,
    decoder_handle: i64,
    config: StreamConfig,
    initially_paused: bool,
    initial_position_frames: u64,
) -> Result<(), String> {
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let played_frames = Arc::new(AtomicU64::new(initial_position_frames));
    let paused = Arc::new(AtomicBool::new(initially_paused));
    let pause_drained = Arc::new(AtomicBool::new(initially_paused));
    let error = Arc::new(Mutex::new(String::new()));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let thread_played_frames = Arc::clone(&played_frames);
    let thread_paused = Arc::clone(&paused);
    let thread_pause_drained = Arc::clone(&pause_drained);
    let thread_error = Arc::clone(&error);
    let worker_thread = thread::Builder::new()
        .name("flowery-usb-iso-out".into())
        .spawn(move || {
            raise_current_thread_priority("flowery-usb-iso-out", USB_WORKER_THREAD_NICE);
            run_worker(
                session, decoder_handle, config, thread_stop, thread_status,
                thread_played_frames, thread_paused, thread_pause_drained, thread_error,
            )
        })
        .map_err(|e| e.to_string())?;
    workers().lock().unwrap_or_else(|e| e.into_inner()).insert(
        session_handle,
        UsbStreamWorker {
            session: session_handle, decoder_handle, config,
            stop, status, played_frames, paused, pause_drained, error, thread: worker_thread,
        },
    );
    Ok(())
}

#[cfg(target_os = "android")]
pub(super) fn start(
    session_handle: i64,
    decoder_handle: i64,
    endpoint: u8,
    raw_max_packet_size: u16,
    interval: u8,
    sample_rate: u32,
    channels: u8,
    valid_bits: u8,
    subslot_bytes: u8,
    data_format: u8,
    feedback_endpoint: u8,
    feedback_max_packet_size: u16,
    initial_position_ms: u64,
) -> Result<(), String> {
    use crate::usb_isochronous::{max_isochronous_payload, UsbBusSpeed};

    if endpoint & 0x80 != 0 || endpoint & 0x0f == 0 {
        return Err("USB playback endpoint must be a non-zero OUT endpoint".into());
    }
    if !(1..=16).contains(&interval) || !(1_000..=768_000).contains(&sample_rate) || channels == 0 {
        return Err("Invalid USB stream parameters".into());
    }
    if !(1..=32).contains(&valid_bits) || !(1..=4).contains(&subslot_bytes)
        || u16::from(subslot_bytes) * 8 < u16::from(valid_bits)
        || !(1..=3).contains(&data_format) {
        return Err("Invalid USB PCM subslot or data format".into());
    }

    // Serialize session lookup with close: once close acquires this gate,
    // no new worker can retain a handle to the session being removed.
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    ensure_session_safe(session_handle)?;
    let session = crate::usb_libusb::session_ref(session_handle)
        .ok_or("Unknown USB session handle")?;
    let device = unsafe { libusb1_sys::libusb_get_device(session.handle) };
    if device.is_null() {
        return Err("libusb could not resolve the opened USB device".into());
    }
    let negotiated_speed = unsafe { libusb1_sys::libusb_get_device_speed(device) };
    let speed = match negotiated_speed {
        2 => UsbBusSpeed::Full,
        3 => UsbBusSpeed::High,
        4 | 5 => return Err("SuperSpeed USB streaming requires SuperSpeed endpoint companion descriptor support; refusing to reuse the High-Speed schedule".into()),
        0 => return Err("USB bus speed is unknown; refusing to guess the isochronous schedule".into()),
        1 => return Err("Low-Speed USB does not support this audio streaming mode".into()),
        other => return Err(format!("Unsupported libusb bus speed value {other}")),
    };
    let max_packet_bytes = max_isochronous_payload(raw_max_packet_size, speed)?;
    let interval_us = match speed {
        // Full-speed isochronous bInterval is a linear frame count.
        UsbBusSpeed::Full => 1_000_u32.checked_mul(u32::from(interval)),
        // High-speed bInterval encodes an exponential microframe interval.
        UsbBusSpeed::High => 125_u32.checked_mul(1_u32 << (interval - 1)),
    }.ok_or("USB service interval overflow")?;
    let decoder = crate::streaming::decoder_ref(decoder_handle)
        .ok_or("Unknown stream decoder handle")?;
    {
        let decoder = decoder.lock().unwrap_or_else(|e| e.into_inner());
        let (rate, decoder_channels, source_bits, source_data_format) = decoder.playback_metadata();
        if rate != sample_rate as usize || decoder_channels != channels as usize {
            return Err(format!(
                "USB plan is {sample_rate} Hz/{channels} channel(s), decoder is {rate} Hz/{decoder_channels} channel(s)"
            ));
        }
        if source_data_format != data_format {
            return Err(format!(
                "USB descriptor data format code {data_format} does not match decoder format code {source_data_format}"
            ));
        }
        if source_bits != valid_bits {
            return Err(format!(
                "USB descriptor bit resolution {valid_bits} does not match decoder source precision {source_bits}"
            ));
        }
    }
    if feedback_endpoint != 0
        && (feedback_endpoint & 0x80 == 0 || feedback_endpoint & 0x0f == 0)
    {
        return Err("USB feedback endpoint must be a non-zero IN endpoint".into());
    }
    let feedback_endpoint = if feedback_endpoint == 0 { None } else { Some(feedback_endpoint) };
    let feedback_packet_bytes = if feedback_endpoint.is_some() {
        let expected = match speed { UsbBusSpeed::Full => 3, UsbBusSpeed::High => 4 };
        if u32::from(feedback_max_packet_size) != expected {
            return Err(format!("Feedback endpoint packet size must be {expected} bytes for this USB bus speed"));
        }
        Some(feedback_max_packet_size)
    } else {
        if feedback_max_packet_size != 0 {
            return Err("Feedback packet size was provided without a feedback endpoint".into());
        }
        None
    };
    let required_packet_bytes = crate::usb_isochronous::required_isochronous_packet_capacity(
        sample_rate,
        channels,
        subslot_bytes,
        interval_us,
        feedback_endpoint.is_some(),
    )?;
    if required_packet_bytes > max_packet_bytes {
        return Err(format!(
            "Endpoint packet capacity {max_packet_bytes} bytes is below required {required_packet_bytes} bytes for this USB clock mode"
        ));
    }
    let config = StreamConfig {
        endpoint, sample_rate, channels, valid_bits, subslot_bytes, data_format,
        max_packet_bytes, interval_us, feedback_endpoint, feedback_packet_bytes,
        feedback_speed: match speed {
            UsbBusSpeed::Full => FeedbackBusSpeed::Full,
            UsbBusSpeed::High => FeedbackBusSpeed::High,
        },
    };
    let initial_position_frames = (u128::from(initial_position_ms)
        .saturating_mul(u128::from(sample_rate)) / 1_000)
        .min(u128::from(u64::MAX)) as u64;
    // Do not interrupt an existing stream until every new stream parameter
    // has passed validation.
    stop_worker_locked(session_handle)?;
    spawn_worker(
        session_handle, session, decoder_handle, config, false,
        initial_position_frames,
    )
}

#[cfg(target_os = "android")]
pub(super) fn seek(
    session_handle: i64,
    decoder_handle: i64,
    position_ms: u64,
) -> Result<u64, String> {
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    ensure_session_safe(session_handle)?;
    let (saved_decoder, config, was_paused) = {
        let map = workers().lock().unwrap_or_else(|e| e.into_inner());
        let worker = map.get(&session_handle).ok_or("USB stream worker is not running")?;
        (worker.decoder_handle, worker.config, worker.paused.load(Ordering::Acquire))
    };
    if saved_decoder != decoder_handle {
        return Err("USB seek decoder does not match the active stream".into());
    }
    let session = crate::usb_libusb::session_ref(session_handle)
        .ok_or("Unknown USB session handle")?;
    let decoder = crate::streaming::decoder_ref(decoder_handle)
        .ok_or("Unknown stream decoder handle")?;

    // Joining the worker drains cancellation callbacks before touching decoder state.
    stop_worker_locked(session_handle)?;
    let actual_position = decoder.lock().unwrap_or_else(|e| e.into_inner())
        .seek_to(position_ms)?;
    let initial_position_frames = (u128::from(actual_position)
        .saturating_mul(u128::from(config.sample_rate)) / 1_000)
        .min(u128::from(u64::MAX)) as u64;
    spawn_worker(
        session_handle, session, decoder_handle, config, was_paused,
        initial_position_frames,
    )?;
    Ok(actual_position)
}

#[cfg(target_os = "android")]
pub(super) fn set_paused(session: i64, paused: bool) -> Result<(), String> {
    // Serialize the state transition with stop/close, but never hold the
    // lifecycle gate while waiting for in-flight transfers to drain.
    let (worker_status, pause_drained) = {
        let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
        let map = workers().lock().unwrap_or_else(|e| e.into_inner());
        let worker = map.get(&session).ok_or("USB stream worker is not running")?;
        if worker.status.load(Ordering::Acquire) != 0 {
            return Err("USB stream is not in a running state".into());
        }
        let state = (
            Arc::clone(&worker.status),
            Arc::clone(&worker.pause_drained),
        );
        worker.paused.store(paused, Ordering::Release);
        if !paused {
            worker.pause_drained.store(false, Ordering::Release);
        }
        state
    };
    if !paused {
        return Ok(());
    }

    let deadline = Instant::now() + PAUSE_DRAIN_TIMEOUT;
    while !pause_drained.load(Ordering::Acquire) {
        if worker_status.load(Ordering::Acquire) < 0 {
            return Err("USB stream failed while draining transfers for pause".into());
        }
        if Instant::now() >= deadline {
            return Err("Timed out waiting for submitted USB audio transfers to drain for pause".into());
        }
        thread::sleep(Duration::from_millis(1));
    }

    // A concurrent close may have removed the worker while the gate was
    // intentionally released. Do not report pause success for a dead session.
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    let map = workers().lock().unwrap_or_else(|e| e.into_inner());
    let worker = map.get(&session).ok_or("USB stream stopped while pausing")?;
    if !Arc::ptr_eq(&worker.status, &worker_status)
        || worker.status.load(Ordering::Acquire) != 0
        || !worker.pause_drained.load(Ordering::Acquire)
    {
        return Err("USB stream changed state before pause completed".into());
    }
    Ok(())
}

#[cfg(target_os = "android")]
pub(super) fn status(session: i64) -> (i32, String, u64, bool) {
    let map = workers().lock().unwrap_or_else(|e| e.into_inner());
    match map.get(&session) {
        Some(worker) => (
            worker.status.load(Ordering::Acquire),
            worker.error.lock().unwrap_or_else(|e| e.into_inner()).clone(),
            worker.played_frames.load(Ordering::Acquire),
            worker.pause_drained.load(Ordering::Acquire),
        ),
        None => (-1, "USB stream worker is not running".into(), 0, false),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn submitted_slot_retains_buffer_callback_state_and_exactly_one_session_reference() {
        use std::sync::{
            atomic::{AtomicUsize, Ordering},
            Arc,
        };

        struct DropProbe(Arc<AtomicUsize>);
        impl Drop for DropProbe {
            fn drop(&mut self) {
                self.0.fetch_add(1, Ordering::SeqCst);
            }
        }

        let buffer_drops = Arc::new(AtomicUsize::new(0));
        let signal_drops = Arc::new(AtomicUsize::new(0));
        let session_drops = Arc::new(AtomicUsize::new(0));
        let session = Arc::new(DropProbe(Arc::clone(&session_drops)));
        let weak_session = Arc::downgrade(&session);
        let slot_reference = Arc::clone(&session);
        assert_eq!(Arc::strong_count(&session), 2);

        let retained = super::retain_submitted_resources(
            vec![DropProbe(Arc::clone(&buffer_drops))],
            Box::new(DropProbe(Arc::clone(&signal_drops))),
            slot_reference,
        );
        assert_eq!(Arc::strong_count(&session), 2);
        drop(session);

        assert_eq!(buffer_drops.load(Ordering::SeqCst), 0);
        assert_eq!(signal_drops.load(Ordering::SeqCst), 0);
        assert_eq!(session_drops.load(Ordering::SeqCst), 0);
        assert!(weak_session.upgrade().is_some());

        // Reclaim the session reference in the test. Production intentionally
        // retains it because libusb has not proven callback access has ended.
        unsafe { drop(Arc::from_raw(retained)); }
        assert_eq!(session_drops.load(Ordering::SeqCst), 1);
        assert!(weak_session.upgrade().is_none());
    }

    #[test]
    fn transfer_ring_and_packet_batch_are_bounded() {
        assert_eq!(super::MIN_TRANSFER_RING_SIZE, 4);
        assert_eq!(super::MAX_TRANSFER_RING_SIZE, 32);
        assert_eq!(super::PACKETS_PER_TRANSFER, 8);
    }

    #[test]
    fn constant_packet_rates_keep_the_shallow_ring() {
        assert_eq!(super::transfer_ring_size(48_000, 125), 4);
        assert_eq!(super::transfer_ring_size(96_000, 125), 4);
        assert_eq!(super::transfer_ring_size(192_000, 125), 4);
    }

    #[test]
    fn fractional_packet_rates_use_the_smaller_experimental_ring() {
        assert_eq!(super::transfer_ring_size(44_100, 125), 8);
        assert_eq!(super::transfer_ring_size(88_200, 125), 8);
    }

    #[test]
    fn constant_packet_detection() {
        assert!(super::has_constant_packet_frames(48_000, 125));
        assert!(super::has_constant_packet_frames(96_000, 125));
        assert!(!super::has_constant_packet_frames(44_100, 125));
        assert!(!super::has_constant_packet_frames(88_200, 125));
    }

    #[test]
    fn ring_depth_scales_with_buffering_target() {
        // 8 packets x 125 us = 1 ms per transfer.
        assert_eq!(super::transfer_ring_size_for(32_000, 44_100, 125), 32);
        assert_eq!(super::transfer_ring_size_for(8_000, 44_100, 125), 8);
    }

    #[test]
    fn full_speed_ring_keeps_minimum() {
        // 8 packets x 1000 us = 8 ms per transfer -> 4 transfers = 32 ms.
        assert_eq!(super::transfer_ring_size_for(32_000, 44_100, 1_000), 4);
    }

    #[test]
    fn ring_is_limited_by_pcm_queue_at_high_sample_rates() {
        // 768 kHz: 768 frames per 1 ms transfer -> at most 8192 / 768 = 10 transfers.
        assert_eq!(super::transfer_ring_size_for(32_000, 768_000, 125), 10);
        // 384 kHz: 384 frames per transfer -> 8192 / 384 = 21 transfers.
        assert_eq!(super::transfer_ring_size_for(32_000, 384_000, 125), 21);
    }

    #[test]
    fn ring_never_drops_below_minimum_or_divides_by_zero() {
        assert!(super::transfer_ring_size(44_100, 0) >= super::MIN_TRANSFER_RING_SIZE);
        assert!(super::transfer_ring_size(u32::MAX, 125) >= super::MIN_TRANSFER_RING_SIZE);
    }
}