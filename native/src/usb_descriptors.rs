use serde_json::{json, Value};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlaybackProfile {
    pub interface_number: u8,
    pub alternate_setting: u8,
    pub channels: u8,
    pub subslot_bytes: u8,
    pub bit_resolution: u8,
    pub endpoint_address: u8,
    pub max_packet_bytes: u16,
    pub interval: u8,
    pub synchronization: &'static str,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ClockSource {
    pub clock_id: u8,
    pub control_interface: u8,
}

#[derive(Default)]
struct Alternate {
    interface_number: u8,
    alternate_setting: u8,
    protocol: u8,
    channels: u8,
    subslot_bytes: u8,
    bit_resolution: u8,
    terminal_link: u8,
    endpoint_address: Option<u8>,
    max_packet_bytes: u16,
    interval: u8,
    synchronization: &'static str,
}

fn le16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
}

/// Parses a descriptor byte stream without trusting descriptor lengths or device-provided counts.
pub fn parse_playback_profiles(raw: &[u8], requested_bit_resolution: Option<u8>) -> Vec<PlaybackProfile> {
    let mut profiles = Vec::new();
    let mut current: Option<Alternate> = None;
    let mut offset = 0usize;

    let finish = |alt: &Option<Alternate>, out: &mut Vec<PlaybackProfile>| {
        if let Some(a) = alt {
            if a.protocol == 0x20 && a.channels == 2 && a.subslot_bytes > 0
                && a.bit_resolution > 0 && a.endpoint_address.is_some()
                && a.max_packet_bytes > 0 && a.interval > 0
                && requested_bit_resolution.map_or(true, |bits| bits == a.bit_resolution)
            {
                out.push(PlaybackProfile {
                    interface_number: a.interface_number,
                    alternate_setting: a.alternate_setting,
                    channels: a.channels,
                    subslot_bytes: a.subslot_bytes,
                    bit_resolution: a.bit_resolution,
                    endpoint_address: a.endpoint_address.unwrap(),
                    max_packet_bytes: a.max_packet_bytes,
                    interval: a.interval,
                    synchronization: a.synchronization,
                });
            }
        }
    };

    while offset + 2 <= raw.len() {
        let length = raw[offset] as usize;
        let descriptor_type = raw[offset + 1];
        if length < 2 || offset + length > raw.len() { break; }
        match descriptor_type {
            0x04 => {
                finish(&current, &mut profiles);
                current = if length >= 9 && raw[offset + 5] == 0x01
                    && raw[offset + 6] == 0x02 && raw[offset + 7] == 0x20
                {
                    Some(Alternate {
                        interface_number: raw[offset + 2],
                        alternate_setting: raw[offset + 3],
                        protocol: raw[offset + 7],
                        interval: 1,
                        synchronization: "unknown",
                        ..Alternate::default()
                    })
                } else { None };
            }
            0x24 if current.is_some() && length >= 3 => {
                let alt = current.as_mut().unwrap();
                match raw[offset + 2] {
                    0x01 if length >= 11 => {
                        alt.terminal_link = raw[offset + 3];
                        alt.channels = raw[offset + 10];
                    }
                    0x02 if length >= 6 && raw[offset + 3] == 0x01 => {
                        alt.subslot_bytes = raw[offset + 4];
                        alt.bit_resolution = raw[offset + 5];
                    }
                    _ => {}
                }
            }
            0x05 if current.is_some() && length >= 7 => {
                let alt = current.as_mut().unwrap();
                let address = raw[offset + 2];
                let attributes = raw[offset + 3];
                let packet = le16(raw, offset + 4);
                let transfer_type = attributes & 0x03;
                let transactions = ((packet >> 11) & 0x03) as u16;
                if address & 0x80 == 0 && transfer_type == 1 && transactions < 3 {
                    alt.endpoint_address = Some(address);
                    alt.max_packet_bytes = (packet & 0x07ff) * (transactions + 1);
                    alt.interval = raw[offset + 6];
                    alt.synchronization = match (attributes >> 2) & 0x03 {
                        1 => "asynchronous",
                        2 => "adaptive",
                        3 => "synchronous",
                        _ => "none",
                    };
                }
            }
            _ => {}
        }
        offset += length;
    }
    finish(&current, &mut profiles);
    profiles.sort_by_key(|p| (p.bit_resolution, p.alternate_setting));
    profiles.dedup_by_key(|p| (p.interface_number, p.alternate_setting));
    profiles
}

/// Resolves UAC2 clock sources associated with the terminal link of a playback interface.
pub fn playback_clock_sources(raw: &[u8], streaming_interface: u8) -> Vec<ClockSource> {
    let mut current_interface = 0xffu8;
    let mut current_subclass = 0xffu8;
    let mut terminal_clocks = std::collections::HashMap::<u8, u8>::new();
    let mut clock_interfaces = std::collections::HashMap::<u8, u8>::new();
    let mut playback_links = Vec::<u8>::new();
    let mut offset = 0usize;

    while offset + 2 <= raw.len() {
        let length = raw[offset] as usize;
        let descriptor_type = raw[offset + 1];
        if length < 2 || offset + length > raw.len() { break; }
        if descriptor_type == 0x04 && length >= 9 {
            current_interface = raw[offset + 2];
            current_subclass = raw[offset + 6];
        } else if descriptor_type == 0x24 && length >= 4 {
            let subtype = raw[offset + 2];
            if current_subclass == 1 {
                match subtype {
                    0x02 if length >= 8 => {
                        let id = raw[offset + 3];
                        let clock = raw[offset + 7];
                        if id != 0 && clock != 0 { terminal_clocks.insert(id, clock); }
                    }
                    0x03 if length >= 9 => {
                        let id = raw[offset + 3];
                        let clock = raw[offset + 8];
                        if id != 0 && clock != 0 { terminal_clocks.insert(id, clock); }
                    }
                    0x0a => {
                        let id = raw[offset + 3];
                        if id != 0 { clock_interfaces.insert(id, current_interface); }
                    }
                    _ => {}
                }
            } else if current_interface == streaming_interface && current_subclass == 2 && subtype == 1 {
                let terminal = raw[offset + 3];
                if terminal != 0 && !playback_links.contains(&terminal) { playback_links.push(terminal); }
            }
        }
        offset += length;
    }

    let mut result = Vec::new();
    for terminal in playback_links {
        if let Some(clock) = terminal_clocks.get(&terminal) {
            if let Some(interface) = clock_interfaces.get(clock) {
                let item = ClockSource { clock_id: *clock, control_interface: *interface };
                if !result.contains(&item) { result.push(item); }
            }
        }
    }
    result
}

pub fn profiles_json(raw: &[u8], requested_bit_resolution: Option<u8>) -> String {
    let profiles = parse_playback_profiles(raw, requested_bit_resolution);
    let values: Vec<Value> = profiles.iter().map(|p| json!({
        "interfaceNumber": p.interface_number,
        "alternateSetting": p.alternate_setting,
        "channels": p.channels,
        "subslotBytes": p.subslot_bytes,
        "bitResolution": p.bit_resolution,
        "endpointAddress": p.endpoint_address,
        "maxPacketBytes": p.max_packet_bytes,
        "interval": p.interval,
        "synchronization": p.synchronization
    })).collect();
    serde_json::to_string(&values).unwrap_or_else(|_| "[]".to_string())
}

pub fn clocks_json(raw: &[u8], streaming_interface: u8) -> String {
    let values: Vec<Value> = playback_clock_sources(raw, streaming_interface).iter().map(|c| json!({
        "clockId": c.clock_id,
        "controlInterface": c.control_interface
    })).collect();
    serde_json::to_string(&values).unwrap_or_else(|_| "[]".to_string())
}

#[cfg(test)]
mod tests {
    use super::{parse_playback_profiles, playback_clock_sources};

    #[test]
    fn parses_stereo_uac2_out_profile_and_packet_multiplier() {
        let descriptors = [
            9,4,2,2,1,1,2,0x20,0,
            16,0x24,1,0,0,0,0,0,0,0,2,0,0,0,0,0,
            6,0x24,2,1,3,24,
            7,5,0x04,0x09,0x26,1,1
        ];
        let profiles = parse_playback_profiles(&descriptors, Some(24));
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].channels, 2);
        assert_eq!(profiles[0].endpoint_address, 4);
        assert_eq!(profiles[0].max_packet_bytes, 294);
    }

    #[test]
    fn rejects_input_endpoint_and_invalid_packet_multiplier() {
        let input_endpoint = [
            9,4,2,2,1,1,2,0x20,0,
            16,0x24,1,0,0,0,0,0,0,0,2,0,0,0,0,0,
            6,0x24,2,1,3,24,
            7,5,0x84,1,0x26,1,1
        ];
        assert!(parse_playback_profiles(&input_endpoint, None).is_empty());
    }

    #[test]
    fn links_only_the_clock_used_by_the_playback_terminal() {
        let descriptors = [
            9,4,0,0,0,1,1,0x20,0,
            17,0x24,2,5,1,1,0,3,2,0,0,0,0,0,0,0,0,
            8,0x24,0x0a,3,1,0,0,0,
            8,0x24,0x0a,6,1,0,0,0,
            9,4,2,1,1,1,2,0x20,0,
            16,0x24,1,5,0,0,0,0,0,0,2,0,0,0,0,0
        ];
        let clocks = playback_clock_sources(&descriptors, 2);
        assert_eq!(clocks.len(), 1);
        assert_eq!(clocks[0].clock_id, 3);
        assert_eq!(clocks[0].control_interface, 0);
    }

    #[test]
    fn malformed_descriptor_lengths_stop_without_panicking() {
        let descriptors = [9,4,0,0,0,1,1,0x20,0,255,0x24];
        assert!(parse_playback_profiles(&descriptors, None).is_empty());
        assert!(playback_clock_sources(&descriptors, 2).is_empty());
    }
}
