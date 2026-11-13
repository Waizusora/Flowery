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
            Ok(payload)
        }
        UsbBusSpeed::High => {
            if raw & 0xe000 != 0 { return Err("High-speed wMaxPacketSize contains reserved bits".into()); }
            let additional = (raw >> 11) & 0x03;
            if additional == 3 { return Err("High-speed endpoint uses the reserved transaction multiplier".into()); }
            Ok(payload * (u32::from(additional) + 1))
        }
    }
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

/// Validate the transfer and every packet before accounting frames or reusing its buffer.
/// A short OUT packet is treated as an underrun: it is not silently counted as played audio.
pub fn validate_isochronous_completion(
    transfer_status: i32,
    packets: &[IsochronousPacketCompletion],
) -> Result<(), String> {
    if transfer_status != 0 {
        return Err(format!("Isochronous USB transfer completed with status {transfer_status}"));
    }
    for (index, packet) in packets.iter().enumerate() {
        if packet.status != 0 {
            return Err(format!("Isochronous USB packet {index} failed with status {}", packet.status));
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
    let mut packets = Vec::with_capacity(service_count as usize);
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

#[cfg(test)]
mod tests {
    use super::*;

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
        assert!(validate_isochronous_completion(-4, &packet).unwrap_err().contains("transfer completed"));
        let packet_error = [IsochronousPacketCompletion { status: 2, actual_length: 8, expected_length: 8 }];
        assert!(validate_isochronous_completion(0, &packet_error).unwrap_err().contains("packet 0 failed"));
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
