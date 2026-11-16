//! USB isochronous OUT packet scheduling primitives.
//!
//! This module computes the PCM payload for each USB service interval. It does
//! not submit URBs; the libusb/usbfs transport must consume this schedule.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UsbBusSpeed { Full, High }

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsochronousPacket {
    pub service_index: u32,
    pub sample_frames: u32,
    pub payload_bytes: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsochronousSchedule {
    pub service_interval_micros: u32,
    pub max_payload_bytes: u32,
    pub packets: Vec<IsochronousPacket>,
}

/// Decode wMaxPacketSize into the maximum payload per service interval.
pub fn max_isochronous_payload(raw: u16, speed: UsbBusSpeed) -> Result<u32, String> {
    let payload = u32::from(raw & 0x07ff);
    if payload == 0 { return Err("Isochronous endpoint advertises a zero-byte packet".into()); }
    match speed {
        UsbBusSpeed::Full => {
            if raw & 0xf800 != 0 { return Err("Full-speed wMaxPacketSize contains reserved transaction bits".into()); }
            if payload > 1_023 {
                return Err("Full-speed isochronous wMaxPacketSize exceeds the 1023-byte USB limit".into());
            }
            Ok(payload)
        }
        UsbBusSpeed::High => {
            if raw & 0xe000 != 0 { return Err("High-speed wMaxPacketSize contains reserved bits".into()); }
            if payload > 1_024 {
                return Err("High-speed isochronous transaction exceeds the 1024-byte USB limit".into());
            }
            let additional = (raw >> 11) & 0x03;
            if additional == 3 { return Err("High-speed endpoint uses the reserved transaction multiplier".into()); }
            Ok(payload * (u32::from(additional) + 1))
        }
    }
}


/// Calculate the endpoint capacity required for one service interval.
/// Explicit-feedback streams reserve room for the full accepted +10% clock
/// deviation; the scheduler itself is bounded to 768 kHz.
pub fn required_isochronous_packet_capacity(
    sample_rate: u32,
    channels: u8,
    subslot_bytes: u8,
    interval_us: u32,
    feedback_required: bool,
) -> Result<u32, String> {
    if !(1_000..=768_000).contains(&sample_rate) || channels == 0 || subslot_bytes == 0 || interval_us == 0 {
        return Err("Invalid USB audio rate, PCM frame size, or service interval".into());
    }
    let nominal = u64::from(sample_rate);
    let maximum_rate = if feedback_required {
        ((nominal * 110 + 99) / 100).min(768_000)
    } else {
        nominal
    };
    let frames = maximum_rate
        .checked_mul(u64::from(interval_us))
        .and_then(|value| value.checked_add(999_999))
        .ok_or("USB packet capacity calculation overflow")?
        / 1_000_000;
    let bytes = frames
        .checked_mul(u64::from(channels))
        .and_then(|value| value.checked_mul(u64::from(subslot_bytes)))
        .ok_or("USB packet capacity calculation overflow")?;
    u32::try_from(bytes).map_err(|_| "USB packet capacity exceeds supported limits".into())
}

/// Copy complete interleaved PCM frames into one isochronous packet payload.
/// The destination is zero-filled first so a final short packet is silence-padded.
pub fn copy_pcm_into_packet(destination: &mut [u8], source: &[u8], frame_bytes: usize) -> Result<usize, String> {
    if frame_bytes == 0 {
        return Err("PCM frame size must be non-zero".into());
    }
    if destination.len() % frame_bytes != 0 || source.len() % frame_bytes != 0 {
        return Err("PCM packet data is not aligned to complete sample frames".into());
    }
    if source.len() > destination.len() {
        return Err("PCM chunk exceeds the remaining isochronous packet capacity".into());
    }
    destination.fill(0);
    destination[..source.len()].copy_from_slice(source);
    Ok(source.len())
}

/// A pause is fully drained only after no audio transfer remains submitted.
pub fn pause_is_drained(paused: bool, audio_transfers_pending: bool) -> bool {
    paused && !audio_transfers_pending
}

/// Whether the worker may queue another audio transfer.
/// Pause drains already submitted transfers but never refills the ring until resumed.
pub fn audio_submission_allowed(paused: bool, cancelling: bool, eof: bool) -> bool {
    !paused && !cancelling && !eof
}

/// Whether a cancellation drain has exceeded its bounded wait period.
pub fn transfer_drain_timed_out(elapsed: std::time::Duration, limit: std::time::Duration) -> bool {
    elapsed >= limit
}

/// Classify libusb_cancel_transfer results without treating a request as completion.
/// LIBUSB_ERROR_NOT_FOUND (-5) means the transfer is no longer in the active set;
/// the owning worker must still observe its callback before freeing transfer memory.
pub fn cancellation_request_error(result: i32) -> Option<i32> {
    if result == 0 || result == -5 { None } else { Some(result) }
}

/// Preserve the first asynchronous stream error as the primary diagnostic.
pub fn preserve_first_error(current: &mut String, message: String) {
    if current.is_empty() {
        *current = message;
    }
}

/// Completion metadata copied from one libusb isochronous transfer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IsochronousPacketCompletion {
    pub status: i32,
    pub actual_length: u32,
    pub expected_length: u32,
}

/// Translate libusb_transfer_status values into stable diagnostic labels.
pub fn transfer_status_name(status: i32) -> &'static str {
    match status {
        0 => "completed",
        1 => "generic error",
        2 => "timed out",
        3 => "cancelled",
        4 => "stalled",
        5 => "device disconnected",
        6 => "overflow",
        _ => "unknown transfer status",
    }
}

/// Translate libusb event-loop errors into actionable diagnostics.
pub fn event_error_message(code: i32) -> String {
    match code {
        -4 => "USB device disconnected while handling libusb events".into(),
        -10 => "libusb event handling was interrupted".into(),
        _ => format!("libusb event handling failed with code {code}"),
    }
}

/// Validate the transfer and every packet before accounting frames or reusing its buffer.
/// A short OUT packet is treated as an underrun: it is not silently counted as played audio.
pub fn validate_isochronous_completion(
    transfer_status: i32,
    packets: &[IsochronousPacketCompletion],
) -> Result<(), String> {
    validate_isochronous_completion_iter(transfer_status, packets.iter().copied())
}

/// Validate packet completions from a fixed-size or lazily produced iterator.
/// This lets the Android transfer worker inspect libusb's flexible-array
/// descriptors without allocating a temporary Vec while other transfers remain
/// submitted.
pub fn validate_isochronous_completion_iter(
    transfer_status: i32,
    packets: impl IntoIterator<Item = IsochronousPacketCompletion>,
) -> Result<(), String> {
    if transfer_status != 0 {
        return Err(format!("Isochronous USB transfer {} (status {transfer_status})", transfer_status_name(transfer_status)));
    }
    for (index, packet) in packets.into_iter().enumerate() {
        if packet.status != 0 {
            return Err(format!("Isochronous USB packet {index} {} (status {})", transfer_status_name(packet.status), packet.status));
        }
        if packet.actual_length != packet.expected_length {
            return Err(format!(
                "Isochronous USB packet {index} was short: {} of {} bytes",
                packet.actual_length, packet.expected_length
            ));
        }
    }
    Ok(())
}

/// Maximum number of service intervals accepted by the pure schedule planner.
/// Runtime transfers use a fixed, much smaller packet ring; this cap also keeps
/// diagnostic/planning callers from requesting unbounded allocations.
const MAX_SCHEDULE_SERVICES: u32 = 4_096;

/// Build a deterministic PCM packet schedule for the requested USB service intervals.
/// Fractional sample frames are distributed without long-term drift.
pub fn build_isochronous_schedule(
    sample_rate: u32,
    channels: u8,
    subslot_bytes: u8,
    raw_max_packet_size: u16,
    b_interval: u8,
    speed: UsbBusSpeed,
    service_count: u32,
) -> Result<IsochronousSchedule, String> {
    if sample_rate == 0 || channels == 0 || subslot_bytes == 0 || service_count == 0 {
        return Err("PCM parameters and service count must be non-zero".into());
    }
    if service_count > MAX_SCHEDULE_SERVICES {
        return Err(format!("Isochronous schedule exceeds the {MAX_SCHEDULE_SERVICES}-service planning limit"));
    }
    if !(1..=16).contains(&b_interval) { return Err("Isochronous bInterval must be between 1 and 16".into()); }

    let interval_us = match speed {
        // Full-speed isochronous bInterval is a linear frame count (1-16 ms).
        UsbBusSpeed::Full => 1_000u32.checked_mul(u32::from(b_interval)),
        // High-speed bInterval encodes 2^(bInterval-1) microframes.
        UsbBusSpeed::High => 125u32.checked_mul(1u32 << (b_interval - 1)),
    }.ok_or("USB service interval overflow")?;
    let max_payload = max_isochronous_payload(raw_max_packet_size, speed)?;
    let frame_bytes = u32::from(channels).checked_mul(u32::from(subslot_bytes))
        .ok_or("PCM frame size overflow")?;

    let numerator = u64::from(sample_rate) * u64::from(interval_us);
    let mut previous_total = 0u64;
    let capacity = usize::try_from(service_count)
        .map_err(|_| "Isochronous schedule service count exceeds platform limits")?;
    let mut packets = Vec::new();
    packets.try_reserve_exact(capacity)
        .map_err(|_| "Unable to reserve memory for the isochronous schedule")?;
    for index in 0..service_count {
        let total = numerator.checked_mul(u64::from(index) + 1)
            .ok_or("Isochronous schedule frame count overflow")? / 1_000_000;
        let frames = total - previous_total;
        previous_total = total;
        let bytes = frames.checked_mul(u64::from(frame_bytes))
            .ok_or("Isochronous packet size overflow")?;
        if bytes > u64::from(max_payload) {
            return Err(format!("Service interval {} needs {} bytes, endpoint permits {}", index, bytes, max_payload));
        }
        packets.push(IsochronousPacket {
            service_index: index,
            sample_frames: frames as u32,
            payload_bytes: bytes as u32,
        });
    }
    Ok(IsochronousSchedule {
        service_interval_micros: interval_us,
        max_payload_bytes: max_payload,
        packets,
    })
}


/// Classify worker termination before releasing the USB session and Android fd.
/// A panic or incomplete transfer drain makes teardown permanently unsafe.
pub fn worker_teardown_error(
    worker_panicked: bool,
    drain_timed_out: bool,
) -> Option<&'static str> {
    if worker_panicked {
        Some("USB stream worker panicked; outstanding transfer resources were retained and the Android USB connection must remain open")
    } else if drain_timed_out {
        Some("USB transfer cancellation could not be drained safely; the Android USB connection must remain open to protect outstanding libusb transfers")
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_panic_is_classified_as_unsafe_teardown() {
        let worker = std::thread::spawn(|| panic!("injected USB worker panic"));
        let panicked = worker.join().is_err();

        assert!(panicked);
        assert_eq!(
            worker_teardown_error(panicked, false),
            Some("USB stream worker panicked; outstanding transfer resources were retained and the Android USB connection must remain open")
        );
    }

    #[test]
    fn incomplete_transfer_drain_is_classified_as_unsafe_teardown() {
        assert_eq!(
            worker_teardown_error(false, true),
            Some("USB transfer cancellation could not be drained safely; the Android USB connection must remain open to protect outstanding libusb transfers")
        );
    }

    #[test]
    fn clean_worker_exit_allows_session_teardown() {
        assert_eq!(worker_teardown_error(false, false), None);
    }


    #[test]
    fn pause_acknowledgement_waits_for_all_audio_transfers() {
        assert!(!pause_is_drained(true, true));
        assert!(pause_is_drained(true, false));
        assert!(!pause_is_drained(false, false));
    }

    #[test]
    fn pause_blocks_audio_refill_and_resume_reenables_it() {
        assert!(!audio_submission_allowed(true, false, false));
        assert!(audio_submission_allowed(false, false, false));
        assert!(!audio_submission_allowed(false, true, false));
        assert!(!audio_submission_allowed(false, false, true));
    }

    #[test]
    fn cancellation_drain_timeout_has_a_strict_boundary() {
        use std::time::Duration;
        assert!(!transfer_drain_timed_out(Duration::from_millis(1999), Duration::from_secs(2)));
        assert!(transfer_drain_timed_out(Duration::from_secs(2), Duration::from_secs(2)));
    }

    #[test]
    fn classifies_cancel_request_results_without_claiming_completion() {
        assert_eq!(cancellation_request_error(0), None);
        assert_eq!(cancellation_request_error(-5), None);
        assert_eq!(cancellation_request_error(-4), Some(-4));
        assert_eq!(cancellation_request_error(-1), Some(-1));
    }

    #[test]
    fn preserves_original_stream_error_when_cleanup_also_fails() {
        let mut error = String::new();
        preserve_first_error(&mut error, "audio packet underrun".into());
        preserve_first_error(&mut error, "cancel transfer failed with code -4".into());
        assert_eq!(error, "audio packet underrun");
    }

    #[test]
    fn validates_complete_isochronous_out_transfer_and_all_packets() {
        let packets = [
            IsochronousPacketCompletion { status: 0, actual_length: 192, expected_length: 192 },
            IsochronousPacketCompletion { status: 0, actual_length: 188, expected_length: 188 },
        ];
        assert_eq!(validate_isochronous_completion(0, &packets), Ok(()));
    }

    #[test]
    fn rejects_transfer_and_individual_packet_completion_errors() {
        let packet = [IsochronousPacketCompletion { status: 0, actual_length: 8, expected_length: 8 }];
        assert!(validate_isochronous_completion(5, &packet).unwrap_err().contains("device disconnected"));
        let packet_error = [IsochronousPacketCompletion { status: 2, actual_length: 8, expected_length: 8 }];
        assert!(validate_isochronous_completion(0, &packet_error).unwrap_err().contains("packet 0 timed out"));
    }

    #[test]
    fn validates_isochronous_completions_from_a_lazy_iterator() {
        let packets = [
            IsochronousPacketCompletion { status: 0, actual_length: 192, expected_length: 192 },
            IsochronousPacketCompletion { status: 0, actual_length: 188, expected_length: 188 },
        ];
        assert_eq!(
            validate_isochronous_completion_iter(0, packets.into_iter()),
            Ok(())
        );
    }

    #[test]
    fn lazy_completion_validation_stops_at_the_first_invalid_packet() {
        use std::cell::Cell;

        let yielded = Cell::new(0);
        let packets = std::iter::from_fn(|| {
            let index = yielded.get();
            yielded.set(index + 1);
            match index {
                0 => Some(IsochronousPacketCompletion {
                    status: 2,
                    actual_length: 0,
                    expected_length: 8,
                }),
                1 => Some(IsochronousPacketCompletion {
                    status: 0,
                    actual_length: 8,
                    expected_length: 8,
                }),
                _ => None,
            }
        });

        let error = validate_isochronous_completion_iter(0, packets).unwrap_err();
        assert!(error.contains("packet 0 timed out"));
        assert_eq!(yielded.get(), 1);
    }

    #[test]
    fn iterator_validation_rejects_transfer_error_before_polling_packets() {
        use std::cell::Cell;

        let polled = Cell::new(false);
        let packets = std::iter::from_fn(|| {
            polled.set(true);
            None
        });

        let error = validate_isochronous_completion_iter(5, packets).unwrap_err();
        assert!(error.contains("device disconnected"));
        assert!(!polled.get());
    }

    #[test]
    fn describes_libusb_transfer_statuses_and_disconnect_errors() {
        assert_eq!(transfer_status_name(0), "completed");
        assert_eq!(transfer_status_name(2), "timed out");
        assert_eq!(transfer_status_name(5), "device disconnected");
        assert_eq!(transfer_status_name(6), "overflow");
        assert!(event_error_message(-4).contains("disconnected"));
        assert!(event_error_message(-9).contains("code -9"));
    }

    #[test]
    fn reports_disconnect_instead_of_an_opaque_transfer_status() {
        let packet = [IsochronousPacketCompletion { status: 5, actual_length: 0, expected_length: 8 }];
        assert!(validate_isochronous_completion(0, &packet).unwrap_err().contains("device disconnected"));
        assert!(validate_isochronous_completion(5, &[]).unwrap_err().contains("device disconnected"));
    }

    #[test]
    fn treats_short_isochronous_out_packet_as_underrun() {
        let packet = [IsochronousPacketCompletion { status: 0, actual_length: 6, expected_length: 8 }];
        assert!(validate_isochronous_completion(0, &packet).unwrap_err().contains("short: 6 of 8 bytes"));
    }

    #[test]
    fn copies_pcm_frames_into_isochronous_packet_and_pads_final_packet() {
        let source = [0x10, 0x11, 0x20, 0x21];
        let mut packet = [0xff; 8];
        let copied = copy_pcm_into_packet(&mut packet, &source, 2).unwrap();
        assert_eq!(copied, 4);
        assert_eq!(packet, [0x10, 0x11, 0x20, 0x21, 0, 0, 0, 0]);
    }

    #[test]
    fn appending_multiple_decoder_chunks_preserves_previously_copied_pcm() {
        let mut packet = [0_u8; 8];
        copy_pcm_into_packet(&mut packet[0..2], &[0x10, 0x11], 2).unwrap();
        copy_pcm_into_packet(&mut packet[2..4], &[0x20, 0x21], 2).unwrap();
        assert_eq!(packet, [0x10, 0x11, 0x20, 0x21, 0, 0, 0, 0]);
    }

    #[test]
    fn rejects_unaligned_or_oversized_pcm_packet_data() {
        let mut packet = [0_u8; 4];
        assert!(copy_pcm_into_packet(&mut packet, &[1, 2, 3], 2).is_err());
        assert!(copy_pcm_into_packet(&mut packet, &[1, 2, 3, 4, 5, 6], 2).is_err());
        assert!(copy_pcm_into_packet(&mut packet, &[1, 2], 0).is_err());
    }

    #[test]
    fn distributes_44100_frames_without_drift_at_full_speed() {
        let s = build_isochronous_schedule(44_100, 2, 2, 192, 1, UsbBusSpeed::Full, 10).unwrap();
        assert_eq!(s.packets.iter().map(|p| p.sample_frames).sum::<u32>(), 441);
        assert!(s.packets.iter().all(|p| p.payload_bytes <= 192));
        assert!(s.packets.iter().any(|p| p.sample_frames == 44));
        assert!(s.packets.iter().any(|p| p.sample_frames == 45));
    }


    #[test]
    fn feedback_packet_capacity_reserves_the_accepted_clock_tolerance() {
        assert_eq!(required_isochronous_packet_capacity(48_000, 2, 2, 1_000, false).unwrap(), 192);
        assert_eq!(required_isochronous_packet_capacity(48_000, 2, 2, 1_000, true).unwrap(), 212);
        assert_eq!(required_isochronous_packet_capacity(48_000, 2, 2, 125, true).unwrap(), 28);
    }

    #[test]
    fn packet_capacity_rejects_rates_outside_the_scheduler_range() {
        assert!(required_isochronous_packet_capacity(999, 2, 2, 1_000, false).is_err());
        assert!(required_isochronous_packet_capacity(768_001, 2, 2, 1_000, false).is_err());
        assert!(required_isochronous_packet_capacity(48_000, 0, 2, 1_000, false).is_err());
    }

    #[test]
    fn enforces_usb_speed_specific_isochronous_transaction_limits() {
        assert_eq!(max_isochronous_payload(0x03ff, UsbBusSpeed::Full).unwrap(), 1023);
        assert!(max_isochronous_payload(0x0400, UsbBusSpeed::Full).is_err());
        assert_eq!(max_isochronous_payload(0x0400, UsbBusSpeed::High).unwrap(), 1024);
        assert_eq!(max_isochronous_payload(0x0c00, UsbBusSpeed::High).unwrap(), 2048);
        assert!(max_isochronous_payload(0x0401, UsbBusSpeed::High).is_err());
    }

    #[test]
    fn decodes_high_speed_transaction_multiplier() {
        assert_eq!(max_isochronous_payload(0x1400, UsbBusSpeed::High).unwrap(), 3072);
    }

    #[test]
    fn rejects_reserved_high_speed_multiplier() {
        assert!(max_isochronous_payload(0x1800, UsbBusSpeed::High).is_err());
    }

    #[test]
    fn rejects_packet_that_exceeds_endpoint_capacity() {
        assert!(build_isochronous_schedule(192_000, 2, 4, 192, 1, UsbBusSpeed::Full, 1).is_err());
    }

    #[test]
    fn full_speed_interval_is_linear_frame_count() {
        let schedule = build_isochronous_schedule(
            48_000, 2, 2, 384, 2, UsbBusSpeed::Full, 1
        ).unwrap();
        assert_eq!(schedule.service_interval_micros, 2_000);
        assert_eq!(schedule.packets[0].sample_frames, 96);
    }

    #[test]
    fn high_speed_interval_doubles_each_binterval_step() {
        let schedule = build_isochronous_schedule(
            48_000, 2, 2, 0x0400, 2, UsbBusSpeed::High, 4
        ).unwrap();
        assert_eq!(schedule.service_interval_micros, 250);
        assert_eq!(schedule.packets.iter().map(|p| p.sample_frames).sum::<u32>(), 48);
    }

    #[test]
    fn fractional_frames_are_distributed_without_drift_at_high_speed() {
        let schedule = build_isochronous_schedule(
            44_100, 2, 4, 0x0400, 1, UsbBusSpeed::High, 80
        ).unwrap();
        assert_eq!(schedule.packets.iter().map(|p| p.sample_frames).sum::<u32>(), 441);
        assert!(schedule.packets.iter().all(|p| p.sample_frames == 5 || p.sample_frames == 6));
        assert!(schedule.packets.iter().enumerate().all(|(index, p)| p.service_index == index as u32));
    }

    #[test]
    fn rejects_zero_and_invalid_packet_descriptors() {
        assert!(max_isochronous_payload(0, UsbBusSpeed::Full).is_err());
        assert!(max_isochronous_payload(0xe400, UsbBusSpeed::High).is_err());
        assert!(build_isochronous_schedule(48_000, 2, 4, 0x0400, 0, UsbBusSpeed::High, 1).is_err());
    }

    #[test]
    fn rejects_unbounded_service_count_before_allocating_schedule() {
        let error = build_isochronous_schedule(
            48_000, 2, 2, 0x0400, 1, UsbBusSpeed::High, u32::MAX
        ).unwrap_err();
        assert!(error.contains("planning limit"));
    }

    #[test]
    fn accepts_schedule_at_service_count_limit() {
        let schedule = build_isochronous_schedule(
            48_000, 2, 2, 0x0400, 1, UsbBusSpeed::High, MAX_SCHEDULE_SERVICES
        ).unwrap();
        assert_eq!(schedule.packets.len(), MAX_SCHEDULE_SERVICES as usize);
    }

    #[test]
    fn rejects_service_intervals_that_exceed_endpoint_capacity() {
        assert!(build_isochronous_schedule(
            768_000, 8, 4, 0x0400, 16, UsbBusSpeed::High, 1
        ).is_err());
    }

    #[test]
    fn high_speed_interval_uses_microframes() {
        let s = build_isochronous_schedule(48_000, 2, 2, 0x0400, 1, UsbBusSpeed::High, 8).unwrap();
        assert_eq!(s.service_interval_micros, 125);
        assert_eq!(s.packets.iter().map(|p| p.sample_frames).sum::<u32>(), 48);
    }
}
