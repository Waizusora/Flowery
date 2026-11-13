//! Asynchronous libusb isochronous OUT streaming.
//!
//! Transfer callbacks only publish completion state. The owning worker performs
//! packet validation, PCM production, resubmission, cancellation, and teardown.

#[cfg(target_os = "android")]
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant},
};

#[cfg(target_os = "android")]
use crate::usb_feedback::{FeedbackBusSpeed, FeedbackRateTracker, PacketFrameScheduler};

#[cfg(target_os = "android")]
const TRANSFER_RING_SIZE: usize = 4;
#[cfg(target_os = "android")]
const PACKETS_PER_TRANSFER: usize = 8;
#[cfg(target_os = "android")]
const TRANSFER_COMPLETION_TIMEOUT: Duration = Duration::from_millis(500);
#[cfg(target_os = "android")]
const TRANSFER_DRAIN_TIMEOUT: Duration = Duration::from_secs(2);

#[cfg(target_os = "android")]
struct TransferSignal {
    completed: AtomicBool,
}

#[cfg(target_os = "android")]
struct TransferSlot {
    transfer: *mut libusb1_sys::libusb_transfer,
    buffer: Vec<u8>,
    signal: Box<TransferSignal>,
    submitted: bool,
    audio_frames: u64,
    submitted_at: Option<Instant>,
}

#[cfg(target_os = "android")]
struct FeedbackSlot {
    transfer: *mut libusb1_sys::libusb_transfer,
    buffer: Vec<u8>,
    signal: Box<TransferSignal>,
    submitted: bool,
    submitted_at: Option<Instant>,
}

#[cfg(target_os = "android")]
extern "system" fn transfer_callback(transfer: *mut libusb1_sys::libusb_transfer) {
    if transfer.is_null() { return; }
    unsafe {
        let signal = (*transfer).user_data as *const TransferSignal;
        if !signal.is_null() {
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
    error: Arc<Mutex<String>>,
    thread: JoinHandle<()>,
}

#[cfg(target_os = "android")]
static WORKERS: OnceLock<Mutex<HashMap<i64, UsbStreamWorker>>> = OnceLock::new();
#[cfg(target_os = "android")]
static LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();

#[cfg(target_os = "android")]
fn workers() -> &'static Mutex<HashMap<i64, UsbStreamWorker>> {
    WORKERS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[cfg(target_os = "android")]
fn lifecycle() -> &'static Mutex<()> {
    LIFECYCLE.get_or_init(|| Mutex::new(()))
}

#[cfg(target_os = "android")]
fn set_error(error: &Arc<Mutex<String>>, status: &AtomicI32, message: String) {
    *error.lock().unwrap_or_else(|e| e.into_inner()) = message;
    status.store(-1, Ordering::Release);
}

#[cfg(target_os = "android")]
fn create_slot(
    handle: *mut libusb1_sys::libusb_device_handle,
    endpoint: u8,
    max_packet_bytes: u32,
) -> Result<TransferSlot, String> {
    let buffer_len = (max_packet_bytes as usize)
        .checked_mul(PACKETS_PER_TRANSFER)
        .ok_or("USB transfer buffer size overflow")?;
    if buffer_len > i32::MAX as usize {
        return Err("USB transfer buffer exceeds libusb limits".into());
    }
    let mut buffer = vec![0_u8; buffer_len];
    let signal = Box::new(TransferSignal { completed: AtomicBool::new(false) });
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
    Ok(TransferSlot { transfer, buffer, signal, submitted: false, audio_frames: 0, submitted_at: None })
}

#[cfg(target_os = "android")]
fn create_feedback_slot(
    handle: *mut libusb1_sys::libusb_device_handle,
    endpoint: u8,
    packet_bytes: u16,
) -> Result<FeedbackSlot, String> {
    let mut buffer = vec![0_u8; usize::from(packet_bytes)];
    let signal = Box::new(TransferSignal { completed: AtomicBool::new(false) });
    let transfer = unsafe { libusb1_sys::libusb_alloc_transfer(1) };
    if transfer.is_null() {
        return Err("libusb_alloc_transfer failed for the feedback endpoint".into());
    }
    unsafe {
        libusb1_sys::libusb_fill_iso_transfer(
            transfer, handle, endpoint, buffer.as_mut_ptr(), buffer.len() as i32,
            1, transfer_callback, (&*signal as *const TransferSignal).cast_mut().cast(), 0,
        );
    }
    Ok(FeedbackSlot { transfer, buffer, signal, submitted: false, submitted_at: None })
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
    decoder_handle: i64,
    scheduler: &mut PacketFrameScheduler,
    eof: &mut bool,
    feedback_rate_hz: Option<f64>,
) -> Result<(), String> {
    let frame_bytes = usize::from(config.channels)
        .checked_mul(usize::from(config.subslot_bytes))
        .ok_or("USB PCM frame size overflow")?;
    if frame_bytes == 0 { return Err("USB PCM frame size is zero".into()); }

    slot.buffer.fill(0);
    let mut offset = 0usize;
    let mut audio_frames = 0u64;
    for packet_index in 0..PACKETS_PER_TRANSFER {
        let frames = scheduler.next_frames(
            feedback_rate_hz.unwrap_or(f64::from(config.sample_rate)),
            config.interval_us,
        )?;
        let expected = frames.checked_mul(frame_bytes).ok_or("USB packet byte count overflow")?;
        if expected > config.max_packet_bytes as usize {
            return Err(format!(
                "USB packet {packet_index} requires {expected} bytes; endpoint permits {}",
                config.max_packet_bytes
            ));
        }

        let mut written = 0usize;
        while written < expected && !*eof {
            let remaining_frames = (expected - written) / frame_bytes;
            let chunk = crate::streaming::read_usb_pcm_chunk(
                decoder_handle,
                remaining_frames,
                config.valid_bits,
                config.subslot_bytes,
                config.data_format,
            )?;
            if chunk.is_empty() {
                *eof = true;
                break;
            }
            let packet_region = &mut slot.buffer[offset + written..offset + expected];
            let copy = crate::usb_isochronous::copy_pcm_into_packet(packet_region, &chunk, frame_bytes)
                .map_err(|message| format!("Invalid PCM data for USB isochronous packet: {message}"))?;
            written += copy;
        }

        audio_frames = audio_frames.saturating_add((written / frame_bytes) as u64);
        let descriptor = unsafe { (*slot.transfer).iso_packet_desc.as_mut_ptr().add(packet_index) };
        unsafe { (*descriptor).length = expected as u32; }
        offset = offset.checked_add(expected)
            .ok_or("USB packet buffer offset overflow")?;
    }

    slot.audio_frames = audio_frames;
    slot.signal.completed.store(false, Ordering::Release);
    let result = unsafe { libusb1_sys::libusb_submit_transfer(slot.transfer) };
    if result != 0 {
        return Err(format!("libusb_submit_transfer failed with code {result}"));
    }
    slot.submitted = true;
    slot.submitted_at = Some(Instant::now());
    Ok(())
}

#[cfg(target_os = "android")]
fn transfer_packets_succeeded(slot: &TransferSlot) -> Result<(), String> {
    unsafe {
        let transfer_status = (*slot.transfer).status as i32;
        let descriptors = std::slice::from_raw_parts(
            (*slot.transfer).iso_packet_desc.as_ptr(),
            PACKETS_PER_TRANSFER,
        );
        let packets: Vec<crate::usb_isochronous::IsochronousPacketCompletion> = descriptors
            .iter()
            .map(|packet| crate::usb_isochronous::IsochronousPacketCompletion {
                status: packet.status as i32,
                actual_length: packet.actual_length,
                expected_length: packet.length,
            })
            .collect();
        crate::usb_isochronous::validate_isochronous_completion(transfer_status, &packets)
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
    error: Arc<Mutex<String>>,
) {
    let mut slots = Vec::with_capacity(TRANSFER_RING_SIZE);
    for _ in 0..TRANSFER_RING_SIZE {
        match create_slot(session.handle, config.endpoint, config.max_packet_bytes) {
            Ok(slot) => slots.push(slot),
            Err(message) => {
                set_error(&error, &status, message);
                for slot in slots.drain(..) {
                    unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
                }
                return;
            }
        }
    }

    let mut feedback_slot = match (config.feedback_endpoint, config.feedback_packet_bytes) {
        (Some(endpoint), Some(packet_bytes)) => match create_feedback_slot(session.handle, endpoint, packet_bytes) {
            Ok(mut slot) => {
                if let Err(message) = submit_feedback(&mut slot) {
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
                set_error(&error, &status, message);
                for slot in slots.drain(..) {
                    unsafe { libusb1_sys::libusb_free_transfer(slot.transfer); }
                }
                return;
            }
        },
        _ => None,
    };
    let mut feedback_tracker = FeedbackRateTracker::new(0.25).expect("valid feedback smoothing");
    let mut feedback_rate_hz: Option<f64> = None;
    let mut feedback_cancelling = false;
    let mut packet_scheduler = PacketFrameScheduler::default();
    let mut eof = false;
    for slot in &mut slots {
        if eof || paused.load(Ordering::Acquire) { break; }
        if let Err(message) = fill_and_submit(slot, config, decoder_handle, &mut packet_scheduler, &mut eof, feedback_rate_hz) {
            set_error(&error, &status, message);
            break;
        }
    }

    let mut cancelling = false;
    let mut drain_started_at: Option<Instant> = None;
    let mut unsafe_to_free = false;
    loop {
        if let Some(started_at) = drain_started_at {
            let pending = slots.iter().any(|slot| slot.submitted)
                || feedback_slot.as_ref().map(|slot| slot.submitted).unwrap_or(false);
            if pending && started_at.elapsed() >= TRANSFER_DRAIN_TIMEOUT {
                set_error(&error, &status, "Timed out draining cancelled USB transfers; retaining transfer buffers and libusb session to prevent use-after-free".into());
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
        }
        if stop.load(Ordering::Acquire) || status.load(Ordering::Acquire) < 0 {
            if !cancelling {
                cancelling = true;
                drain_started_at = Some(Instant::now());
                for slot in &slots {
                    if slot.submitted { let _ = unsafe { libusb1_sys::libusb_cancel_transfer(slot.transfer) }; }
                }
                if let Some(feedback) = feedback_slot.as_mut() {
                    if feedback.submitted { let _ = unsafe { libusb1_sys::libusb_cancel_transfer(feedback.transfer) }; }
                    feedback_cancelling = true;
                }
            }
        }
        if eof && !slots.iter().any(|slot| slot.submitted) {
            if let Some(feedback) = feedback_slot.as_mut() {
                if feedback.submitted && !feedback_cancelling {
                    let _ = unsafe { libusb1_sys::libusb_cancel_transfer(feedback.transfer) };
                    feedback_cancelling = true;
                }
            }
        }
        if !cancelling && !paused.load(Ordering::Acquire) && !eof {
            for slot in &mut slots {
                if !slot.submitted {
                    if let Err(message) = fill_and_submit(slot, config, decoder_handle, &mut packet_scheduler, &mut eof, feedback_rate_hz) {
                        set_error(&error, &status, message); break;
                    }
                    if eof { break; }
                }
            }
        }
        let audio_pending = slots.iter().any(|slot| slot.submitted);
        let feedback_pending = feedback_slot.as_ref().map(|slot| slot.submitted).unwrap_or(false);
        if !audio_pending && !feedback_pending {
            if cancelling || eof || status.load(Ordering::Acquire) < 0 { break; }
            thread::sleep(Duration::from_millis(1)); continue;
        }
        let timeout = libc::timeval { tv_sec: 0, tv_usec: 10_000 };
        let event_result = unsafe { libusb1_sys::libusb_handle_events_timeout_completed(session.context, &timeout, std::ptr::null_mut()) };
        if event_result < 0 && event_result != -10 {
            set_error(&error, &status, format!("libusb event handling failed with code {event_result}"));
            // Keep pumping events while cancellation callbacks are outstanding.
            // The bounded drain timeout below prevents an unbounded join if the
            // device/context can no longer deliver callbacks.
            thread::sleep(Duration::from_millis(1));
            continue;
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
                                if let Err(message) = crate::usb_feedback::validate_feedback_rate_hz(config.sample_rate, measured) {
                                    set_error(&error, &status, message);
                                } else if let Ok(estimate) = feedback_tracker.update(measured) {
                                    feedback_rate_hz = Some(estimate);
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
        for slot in &mut slots {
            if !slot.submitted || !slot.signal.completed.swap(false, Ordering::AcqRel) { continue; }
            slot.submitted = false;
            slot.submitted_at = None;
            if !cancelling {
                if let Err(message) = transfer_packets_succeeded(slot) { set_error(&error, &status, message); continue; }
                played_frames.fetch_add(slot.audio_frames, Ordering::AcqRel);
            }
        }
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
    if status.load(Ordering::Acquire) >= 0 {
        status.store(if stop.load(Ordering::Acquire) { 2 } else { 1 }, Ordering::Release);
    }
}

#[cfg(target_os = "android")]
fn stop_worker_locked(session: i64) {
    let worker = workers().lock().unwrap_or_else(|e| e.into_inner()).remove(&session);
    if let Some(worker) = worker {
        worker.stop.store(true, Ordering::Release);
        let _ = worker.thread.join();
    }
}

#[cfg(target_os = "android")]
pub(super) fn stop_session(session: i64) {
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    stop_worker_locked(session);
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
    let error = Arc::new(Mutex::new(String::new()));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let thread_played_frames = Arc::clone(&played_frames);
    let thread_paused = Arc::clone(&paused);
    let thread_error = Arc::clone(&error);
    let worker_thread = thread::Builder::new()
        .name("flowery-usb-iso-out".into())
        .spawn(move || run_worker(
            session, decoder_handle, config, thread_stop, thread_status,
            thread_played_frames, thread_paused, thread_error,
        ))
        .map_err(|e| e.to_string())?;
    workers().lock().unwrap_or_else(|e| e.into_inner()).insert(
        session_handle,
        UsbStreamWorker {
            session: session_handle, decoder_handle, config,
            stop, status, played_frames, paused, error, thread: worker_thread,
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
    if !(1..=16).contains(&interval) || sample_rate == 0 || channels == 0 {
        return Err("Invalid USB stream parameters".into());
    }
    if !(1..=32).contains(&valid_bits) || !(1..=4).contains(&subslot_bytes)
        || u16::from(subslot_bytes) * 8 < u16::from(valid_bits)
        || !(1..=3).contains(&data_format) {
        return Err("Invalid USB PCM subslot or data format".into());
    }

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
    let max_frames = (u64::from(sample_rate) * u64::from(interval_us) + 999_999) / 1_000_000;
    let needed = max_frames * u64::from(channels) * u64::from(subslot_bytes);
    if needed > u64::from(max_packet_bytes) {
        return Err(format!("Endpoint packet capacity {max_packet_bytes} bytes is below required {needed} bytes"));
    }

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
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    stop_worker_locked(session_handle);
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
    stop_worker_locked(session_handle);
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
    let map = workers().lock().unwrap_or_else(|e| e.into_inner());
    let worker = map.get(&session).ok_or("USB stream worker is not running")?;
    if worker.status.load(Ordering::Acquire) != 0 {
        return Err("USB stream is not in a running state".into());
    }
    worker.paused.store(paused, Ordering::Release);
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
            worker.paused.load(Ordering::Acquire),
        ),
        None => (-1, "USB stream worker is not running".into(), 0, false),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn transfer_ring_and_packet_batch_are_bounded() {
        assert_eq!(super::TRANSFER_RING_SIZE, 4);
        assert_eq!(super::PACKETS_PER_TRANSFER, 8);
    }
}
