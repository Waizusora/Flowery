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
