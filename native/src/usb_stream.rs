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
    time::Duration,
};

#[cfg(target_os = "android")]
const TRANSFER_RING_SIZE: usize = 4;
#[cfg(target_os = "android")]
const PACKETS_PER_TRANSFER: usize = 8;

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
    max_packet_bytes: u32,
    interval_us: u32,
}

#[cfg(target_os = "android")]
struct UsbStreamWorker {
    session: i64,
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
    Ok(TransferSlot { transfer, buffer, signal, submitted: false, audio_frames: 0 })
}

#[cfg(target_os = "android")]
fn fill_and_submit(
    slot: &mut TransferSlot,
    config: StreamConfig,
    decoder_handle: i64,
    phase: &mut u64,
    eof: &mut bool,
) -> Result<(), String> {
    let frame_bytes = usize::from(config.channels)
        .checked_mul(usize::from(config.subslot_bytes))
        .ok_or("USB PCM frame size overflow")?;
    if frame_bytes == 0 { return Err("USB PCM frame size is zero".into()); }

    slot.buffer.fill(0);
    let mut offset = 0usize;
    let mut audio_frames = 0u64;
    for packet_index in 0..PACKETS_PER_TRANSFER {
        let numerator = u64::from(config.sample_rate)
            .checked_mul(u64::from(config.interval_us))
            .ok_or("USB service interval frame calculation overflow")?;
        *phase = phase.checked_add(numerator).ok_or("USB frame phase overflow")?;
        let frames = (*phase / 1_000_000) as usize;
        *phase %= 1_000_000;
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
            )?;
            if chunk.is_empty() {
                *eof = true;
                break;
            }
            if chunk.len() % frame_bytes != 0 {
                return Err("PCM decoder produced a non-frame-aligned USB chunk".into());
            }
            let copy = chunk.len().min(expected - written);
            slot.buffer[offset + written..offset + written + copy].copy_from_slice(&chunk[..copy]);
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
    Ok(())
}

#[cfg(target_os = "android")]
fn transfer_packets_succeeded(slot: &TransferSlot) -> Result<(), String> {
    unsafe {
        if (*slot.transfer).status != 0 {
            return Err(format!("Isochronous USB transfer completed with status {}", (*slot.transfer).status));
        }
        let descriptors = std::slice::from_raw_parts(
            (*slot.transfer).iso_packet_desc.as_ptr(),
            PACKETS_PER_TRANSFER,
        );
        for (index, packet) in descriptors.iter().enumerate() {
            if packet.status != 0 {
                return Err(format!("Isochronous USB packet {index} failed with status {}", packet.status));
            }
            if packet.actual_length != packet.length {
                return Err(format!(
                    "Isochronous USB packet {index} was short: {} of {} bytes",
                    packet.actual_length, packet.length
                ));
            }
        }
    }
    Ok(())
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

    let mut phase = 0_u64;
    let mut eof = false;
    for slot in &mut slots {
        if eof { break; }
        if let Err(message) = fill_and_submit(slot, config, decoder_handle, &mut phase, &mut eof) {
            set_error(&error, &status, message);
            break;
        }
    }

    let mut cancelling = false;
    loop {
        if stop.load(Ordering::Acquire) || status.load(Ordering::Acquire) < 0 {
            if !cancelling {
                cancelling = true;
                for slot in &slots {
                    if slot.submitted { let _ = unsafe { libusb1_sys::libusb_cancel_transfer(slot.transfer) }; }
                }
            }
        }
        if !cancelling && !paused.load(Ordering::Acquire) && !eof {
            for slot in &mut slots {
                if !slot.submitted {
                    if let Err(message) = fill_and_submit(slot, config, decoder_handle, &mut phase, &mut eof) {
                        set_error(&error, &status, message); break;
                    }
                    if eof { break; }
                }
            }
        }
        if !slots.iter().any(|slot| slot.submitted) {
            if cancelling || eof || status.load(Ordering::Acquire) < 0 { break; }
            thread::sleep(Duration::from_millis(1)); continue;
        }
        let timeout = libc::timeval { tv_sec: 0, tv_usec: 10_000 };
        let event_result = unsafe { libusb1_sys::libusb_handle_events_timeout_completed(session.context, &timeout, std::ptr::null_mut()) };
        if event_result < 0 && event_result != -10 {
            set_error(&error, &status, format!("libusb event handling failed with code {event_result}")); continue;
        }
        for slot in &mut slots {
            if !slot.submitted || !slot.signal.completed.swap(false, Ordering::AcqRel) { continue; }
            slot.submitted = false;
            if !cancelling {
                if let Err(message) = transfer_packets_succeeded(slot) { set_error(&error, &status, message); continue; }
                played_frames.fetch_add(slot.audio_frames, Ordering::AcqRel);
            }
        }
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
) -> Result<(), String> {
    use crate::usb_isochronous::{max_isochronous_payload, UsbBusSpeed};

    if endpoint & 0x80 != 0 || endpoint & 0x0f == 0 {
        return Err("USB playback endpoint must be a non-zero OUT endpoint".into());
    }
    if !(1..=16).contains(&interval) || sample_rate == 0 || channels == 0 {
        return Err("Invalid USB stream parameters".into());
    }
    if !(1..=32).contains(&valid_bits) || !(1..=4).contains(&subslot_bytes)
        || u16::from(subslot_bytes) * 8 < u16::from(valid_bits) {
        return Err("Invalid USB PCM subslot format".into());
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
    let factor = 1_u32 << (interval - 1);
    let base_interval_us = match speed {
        UsbBusSpeed::Full => 1_000_u32,
        UsbBusSpeed::High => 125_u32,
    };
    let interval_us = base_interval_us
        .checked_mul(factor).ok_or("USB service interval overflow")?;
    let max_frames = (u64::from(sample_rate) * u64::from(interval_us) + 999_999) / 1_000_000;
    let needed = max_frames * u64::from(channels) * u64::from(subslot_bytes);
    if needed > u64::from(max_packet_bytes) {
        return Err(format!("Endpoint packet capacity {max_packet_bytes} bytes is below required {needed} bytes"));
    }

    let decoder = crate::streaming::decoder_ref(decoder_handle)
        .ok_or("Unknown stream decoder handle")?;
    {
        let decoder = decoder.lock().unwrap_or_else(|e| e.into_inner());
        let (rate, decoder_channels, _) = decoder.playback_metadata();
        if rate != sample_rate as usize || decoder_channels != channels as usize {
            return Err(format!(
                "USB plan is {sample_rate} Hz/{channels} channel(s), decoder is {rate} Hz/{decoder_channels} channel(s)"
            ));
        }
    }
    let _gate = lifecycle().lock().unwrap_or_else(|e| e.into_inner());
    stop_worker_locked(session_handle);
    let stop = Arc::new(AtomicBool::new(false));
    let status = Arc::new(AtomicI32::new(0));
    let played_frames = Arc::new(AtomicU64::new(0));
    let paused = Arc::new(AtomicBool::new(false));
    let error = Arc::new(Mutex::new(String::new()));
    let thread_stop = Arc::clone(&stop);
    let thread_status = Arc::clone(&status);
    let thread_played_frames = Arc::clone(&played_frames);
    let thread_paused = Arc::clone(&paused);
    let thread_error = Arc::clone(&error);
    let config = StreamConfig {
        endpoint, sample_rate, channels, valid_bits, subslot_bytes,
        max_packet_bytes, interval_us,
    };
    let worker_thread = thread::Builder::new()
        .name("flowery-usb-iso-out".into())
        .spawn(move || run_worker(
            session, decoder_handle, config, thread_stop, thread_status, thread_played_frames, thread_paused, thread_error,
        ))
        .map_err(|e| e.to_string())?;
    workers().lock().unwrap_or_else(|e| e.into_inner()).insert(
        session_handle,
        UsbStreamWorker { session: session_handle, stop, status, played_frames, paused, error, thread: worker_thread },
    );
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
