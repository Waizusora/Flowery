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
    pub protocol: u8,
    pub sample_rates: Vec<u32>,
    pub sample_rate_range: Option<(u32, u32)>,
    pub sampling_frequency_control: bool,
    pub format_type: u8,
    pub format_tag: u16,
    pub feedback_endpoint: Option<u8>,
    pub feedback_max_packet_bytes: u16,
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
    sample_rates: Vec<u32>,
    sample_rate_range: Option<(u32, u32)>,
    sampling_frequency_control: bool,
    format_type: u8,
    format_tag: u16,
    feedback_endpoint: Option<u8>,
    feedback_max_packet_bytes: u16,
    synchronization_address: u8,
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
            let supported_protocol = a.protocol == 0x00 || a.protocol == 0x20;
            if supported_protocol && (a.protocol != 0x00 || a.format_tag == 0x0001)
                && (1..=8).contains(&a.channels) && (1..=4).contains(&a.subslot_bytes)
                && (8..=32).contains(&a.bit_resolution)
                && a.bit_resolution <= a.subslot_bytes.saturating_mul(8)
                && a.terminal_link > 0 && a.endpoint_address.is_some()
                && a.max_packet_bytes > 0 && a.interval > 0
                && (a.synchronization != "asynchronous" || a.feedback_endpoint.is_some_and(|endpoint| a.synchronization_address == 0 || endpoint == a.synchronization_address))
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
                    protocol: a.protocol,
                    sample_rates: a.sample_rates.clone(),
                    sample_rate_range: a.sample_rate_range,
                    sampling_frequency_control: a.sampling_frequency_control,
                    format_type: a.format_type,
                    format_tag: a.format_tag,
                    feedback_endpoint: a.feedback_endpoint,
                    feedback_max_packet_bytes: a.feedback_max_packet_bytes,
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
                    && raw[offset + 6] == 0x02
                    && (raw[offset + 7] == 0x00 || raw[offset + 7] == 0x20)
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
                match (alt.protocol, raw[offset + 2]) {
                    (0x20, 0x01) if length >= 11 => {
                        alt.terminal_link = raw[offset + 3];
                        alt.channels = raw[offset + 10];
                    }
                    (0x20, 0x02) if length >= 6 && raw[offset + 3] == 0x01 => {
                        alt.subslot_bytes = raw[offset + 4];
                        alt.bit_resolution = raw[offset + 5];
                    }
                    // UAC1 AudioStreaming General descriptor (AS_GENERAL).
                    (0x00, 0x01) if length >= 7 => {
                        alt.terminal_link = raw[offset + 3];
                        alt.format_tag = le16(raw, offset + 5);
                    }
                    // UAC1 Type-I format descriptor. Only PCM (format tag 0x0001)
                    // is accepted by the direct integer-PCM transport.
                    (0x00, 0x02) if length >= 4 => {
                        alt.format_type = raw[offset + 3];
                        if alt.format_type != 0x01 || length < 8 { offset += length; continue; }
                        alt.channels = raw[offset + 4];
                        alt.subslot_bytes = raw[offset + 5];
                        alt.bit_resolution = raw[offset + 6];
                        let frequency_count = raw[offset + 7] as usize;
                        if frequency_count == 0 {
                            if length >= 14 {
                                let minimum = le24(raw, offset + 8);
                                let maximum = le24(raw, offset + 11);
                                if minimum > 0 && maximum >= minimum {
                                    alt.sample_rate_range = Some((minimum, maximum));
                                }
                            }
                        } else {
                            let available = (length - 8) / 3;
                            if frequency_count <= available {
                                for index in 0..frequency_count {
                                    let rate = le24(raw, offset + 8 + index * 3);
                                    if rate > 0 && !alt.sample_rates.contains(&rate) {
                                        alt.sample_rates.push(rate);
                                    }
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }
            0x25 if current.is_some() && length >= 4 => {
                let alt = current.as_mut().unwrap();
                if alt.protocol == 0x00 && raw[offset + 2] == 0x01 {
                    alt.sampling_frequency_control = raw[offset + 3] & 0x01 != 0;
                }
            }
            0x05 if current.is_some() && length >= 7 => {
                let alt = current.as_mut().unwrap();
                let address = raw[offset + 2];
                let attributes = raw[offset + 3];
                let packet = le16(raw, offset + 4);
                let transfer_type = attributes & 0x03;
                let usage_type = (attributes >> 4) & 0x03;
                let transactions = ((packet >> 11) & 0x03) as u16;
                if transfer_type == 1 && address & 0x80 != 0 && usage_type == 1 && transactions < 3 {
                    alt.feedback_endpoint = Some(address);
                    alt.feedback_max_packet_bytes = (packet & 0x07ff) * (transactions + 1);
                } else if address & 0x80 == 0 && transfer_type == 1 && usage_type == 0 && transactions < 3 {
                    alt.endpoint_address = Some(address);
                    alt.synchronization_address = if length >= 9 { raw[offset + 8] } else { 0 };
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
    profiles.sort_by_key(|p| (std::cmp::Reverse(p.bit_resolution), p.alternate_setting));
    profiles.dedup_by_key(|p| (p.interface_number, p.alternate_setting));
    profiles
}

/// Resolves UAC2 clock sources associated with the terminal link of a playback interface.
pub fn playback_clock_sources(raw: &[u8], streaming_interface: u8) -> Vec<ClockSource> {
    let mut current_interface = 0xffu8;
    let mut current_class = 0xffu8;
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
            current_class = raw[offset + 5];
            current_subclass = raw[offset + 6];
        } else if descriptor_type == 0x24 && length >= 4 {
            let subtype = raw[offset + 2];
            if current_class == 1 && current_subclass == 1 {
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
            } else if current_class == 1 && current_interface == streaming_interface && current_subclass == 2 && subtype == 1 {
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

/// Parses a UAC2 clock-source GET_RANGE control-transfer response.
pub fn clock_ranges_json(response: &[u8], transferred: usize) -> String {
    let available = transferred.min(response.len());
    if available < 2 {
        return "[]".to_string();
    }
    let count = le16(response, 0) as usize;
    let valid_count = count.min((available - 2) / 12);
    let ranges: Vec<Value> = (0..valid_count).map(|index| {
        let offset = 2 + index * 12;
        json!({
            "minimum": le32(response, offset),
            "maximum": le32(response, offset + 4),
            "resolution": le32(response, offset + 8)
        })
    }).collect();
    serde_json::to_string(&ranges).unwrap_or_else(|_| "[]".to_string())
}

fn le32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes([
        bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]
    ])
}

/// Builds a bounded diagnostic trace for one UAC streaming interface.
pub fn playback_descriptor_trace(raw: &[u8], streaming_interface: u8) -> String {
    let mut lines = Vec::<String>::new();
    let mut current_interface = -1i32;
    let mut current_class = -1i32;
    let mut current_subclass = -1i32;
    let mut offset = 0usize;

    while offset + 2 <= raw.len() && lines.len() < 100 {
        let length = raw[offset] as usize;
        let descriptor_type = raw[offset + 1] as usize;
        if length < 2 || offset + length > raw.len() {
            lines.push(format!(
                "offset={offset} malformed_length={length} remaining={}",
                raw.len() - offset
            ));
            break;
        }
        if descriptor_type == 0x04 && length >= 9 {
            current_interface = raw[offset + 2] as i32;
            current_class = raw[offset + 5] as i32;
            current_subclass = raw[offset + 6] as i32;
            if current_class == 0x01 {
                lines.push(format!(
                    "IF offset={offset} number={current_interface} alt={} subclass={current_subclass} protocol=0x{:x}",
                    raw[offset + 3], raw[offset + 7]
                ));
            }
        } else if current_class == 0x01 && (descriptor_type == 0x24 || descriptor_type == 0x05) {
            let bytes = raw[offset..(offset + length).min(offset + 24)]
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<Vec<_>>()
                .join(" ");
            let scope = if current_interface == streaming_interface as i32 {
                "SELECTED_STREAM"
            } else {
                "AUDIO"
            };
            lines.push(format!(
                "{scope} offset={offset} type=0x{descriptor_type:x} subclass={current_subclass} bytes={bytes}"
            ));
        }
        offset += length;
    }

    if lines.is_empty() {
        return format!(
            "No audio descriptors found in raw descriptor block (bytes={}).",
            raw.len()
        );
    }
    if offset < raw.len() && lines.len() >= 100 {
        lines.push("Trace truncated at 100 entries.".to_string());
    }
    lines.join("\n")
}

/// Selects candidate output rates from UAC2 clock RANGE values. Every candidate is
/// still subject to a successful device SET_CUR/GET_CUR readback before use.
pub fn select_usb_sample_rates_json(source_rate: u32, ranges_json: &str) -> String {
    let standard_rates = [
        44_100_u32, 48_000, 88_200, 96_000, 176_400, 192_000,
        352_800, 384_000, 32_000, 64_000, 128_000, 256_000,
        8_000, 11_025, 16_000, 22_050, 24_000,
    ];
    if source_rate == 0 {
        return "[]".to_string();
    }

    let mut candidates = Vec::<u32>::new();
    if let Ok(value) = serde_json::from_str::<Value>(ranges_json) {
        if let Some(ranges) = value.as_array() {
            for range in ranges {
                let minimum = range.get("minimum").and_then(Value::as_u64).unwrap_or(0);
                let maximum = range.get("maximum").and_then(Value::as_u64).unwrap_or(0);
                let resolution = range.get("resolution").and_then(Value::as_u64).unwrap_or(0);
                if minimum == 0 || maximum < minimum || maximum > u32::MAX as u64 {
                    continue;
                }
                let bounded = (source_rate as u64).clamp(minimum, maximum);
                let selected = if resolution > 0 {
                    let offset = bounded - minimum;
                    let steps = (offset + resolution / 2) / resolution;
                    minimum.saturating_add(steps.saturating_mul(resolution)).min(maximum)
                } else {
                    bounded
                };
                if selected > 0 && selected <= u32::MAX as u64 {
                    candidates.push(selected as u32);
                }
            }
        }
    }

    if candidates.is_empty() {
        candidates.push(source_rate);
        candidates.extend(standard_rates);
    }
    candidates.sort_by_key(|rate| rate.abs_diff(source_rate));
    candidates.dedup();
    serde_json::to_string(&candidates).unwrap_or_else(|_| "[]".to_string())
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
        "synchronization": p.synchronization,
        "protocol": p.protocol,
        "sampleRates": p.sample_rates,
        "sampleRateRange": p.sample_rate_range.map(|(minimum, maximum)| json!([minimum, maximum])),
        "samplingFrequencyControl": p.sampling_frequency_control,
        "formatType": p.format_type,
        "formatTag": p.format_tag,
        "feedbackEndpoint": p.feedback_endpoint,
        "feedbackMaxPacketBytes": p.feedback_max_packet_bytes
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



#[derive(Default)]
struct StreamDescriptor {
    interface_number: u8,
    alternate_setting: u8,
    protocol: u8,
    channels: std::collections::BTreeSet<u8>,
    subslot_bytes: std::collections::BTreeSet<u8>,
    bit_resolutions: std::collections::BTreeSet<u8>,
    sample_rates: std::collections::BTreeSet<u32>,
    sample_rate_ranges: Vec<(u32, u32)>,
    endpoints: Vec<Value>,
    uac1_format_types: std::collections::BTreeSet<u8>,
    uac1_format_tags: std::collections::BTreeSet<u16>,
    uac1_max_bit_rates: std::collections::BTreeSet<u16>,
    uac1_samples_per_frames: std::collections::BTreeSet<u16>,
}

fn le24(bytes: &[u8], offset: usize) -> u32 {
    (bytes[offset] as u32)
        | ((bytes[offset + 1] as u32) << 8)
        | ((bytes[offset + 2] as u32) << 16)
}

fn parse_stream_descriptors(raw: &[u8]) -> (Vec<StreamDescriptor>, bool) {
    let mut streams = Vec::<StreamDescriptor>::new();
    let mut current: Option<usize> = None;
    let mut offset = 0usize;
    let mut malformed = false;

    while offset + 2 <= raw.len() {
        let length = raw[offset] as usize;
        let descriptor_type = raw[offset + 1];
        if length < 2 || offset + length > raw.len() {
            malformed = true;
            break;
        }
        match descriptor_type {
            0x04 => {
                current = None;
                if length < 9 {
                    malformed = true;
                } else if raw[offset + 5] == 0x01 && raw[offset + 6] == 0x02 {
                    streams.push(StreamDescriptor {
                        interface_number: raw[offset + 2],
                        alternate_setting: raw[offset + 3],
                        protocol: raw[offset + 7],
                        ..StreamDescriptor::default()
                    });
                    current = Some(streams.len() - 1);
                }
            }
            0x05 if current.is_some() && length >= 7 => {
                let address = raw[offset + 2];
                let attributes = raw[offset + 3];
                let packet = le16(raw, offset + 4);
                let transfer_type = attributes & 0x03;
                let transactions = ((packet >> 11) & 0x03) as u16;
                let base_packet = packet & 0x07ff;
                let max_packet_bytes = if transactions < 3 {
                    base_packet.saturating_mul(transactions + 1)
                } else { 0 };
                let transfer = match transfer_type {
                    1 => "isochronous", 2 => "bulk", 3 => "interrupt", _ => "control"
                };
                let sync_type = (attributes >> 2) & 0x03;
                let usage_type = (attributes >> 4) & 0x03;
                let synchronization = if transfer_type == 1 {
                    match sync_type {
                        1 => "asynchronous", 2 => "adaptive", 3 => "synchronous", _ => "none"
                    }
                } else { "n/a" };
                streams[current.unwrap()].endpoints.push(json!({
                    "address": address,
                    "direction": if address & 0x80 != 0 { "IN" } else { "OUT" },
                    "transfer": transfer,
                    "maxPacketBytes": max_packet_bytes,
                    "interval": raw[offset + 6],
                    "synchronization": synchronization,
                    "usage": match usage_type { 0 => "data", 1 => "feedback", 2 => "implicit_feedback", _ => "reserved" },
                    "synchronizationAddress": if length >= 9 { raw[offset + 8] } else { 0 },
                    "transactionsPerMicroframe": transactions + 1,
                    "validIsochronousEndpoint": transfer_type == 1 && base_packet > 0 && transactions < 3 && sync_type != 0
                }));
            }
            0x24 if current.is_some() && length >= 3 => {
                let stream = &mut streams[current.unwrap()];
                let subtype = raw[offset + 2];
                if stream.protocol == 0x20 {
                    match subtype {
                        0x01 if length >= 11 => {
                            let channels = raw[offset + 10];
                            if channels > 0 { stream.channels.insert(channels); }
                        }
                        0x02 if length >= 6 && raw[offset + 3] == 0x01 => {
                            let subslot = raw[offset + 4];
                            let bits = raw[offset + 5];
                            if subslot > 0 { stream.subslot_bytes.insert(subslot); }
                            if bits > 0 { stream.bit_resolutions.insert(bits); }
                        }
                        _ => {}
                    }
                } else if stream.protocol == 0x00 && subtype == 0x01 && length >= 7 {
                    let tag = le16(raw, offset + 5);
                    if tag != 0 { stream.uac1_format_tags.insert(tag); }
                } else if stream.protocol == 0x00 && subtype == 0x02 && length >= 4 {
                    let format_type = raw[offset + 3];
                    if format_type != 0 { stream.uac1_format_types.insert(format_type); }
                    if format_type == 0x02 {
                        if length >= 8 {
                            stream.uac1_max_bit_rates.insert(le16(raw, offset + 4));
                            stream.uac1_samples_per_frames.insert(le16(raw, offset + 6));
                        }
                        let frequency_count_offset = 8usize;
                        if length > frequency_count_offset {
                            let frequency_count = raw[offset + frequency_count_offset] as usize;
                            if frequency_count > 0 {
                                let available = (length - frequency_count_offset - 1) / 3;
                                if frequency_count > available { malformed = true; }
                                for index in 0..frequency_count.min(available) {
                                    let rate = le24(raw, offset + frequency_count_offset + 1 + index * 3);
                                    if rate > 0 { stream.sample_rates.insert(rate); }
                                }
                            }
                        }
                    } else if format_type == 0x01 || format_type == 0x03 {
                        if length < 8 {
                            malformed = true;
                            offset += length;
                            continue;
                        }
                        let channels = raw[offset + 4];
                        let subslot = raw[offset + 5];
                        let bits = raw[offset + 6];
                        if channels > 0 { stream.channels.insert(channels); }
                        if subslot > 0 { stream.subslot_bytes.insert(subslot); }
                        if bits > 0 { stream.bit_resolutions.insert(bits); }
                        let frequency_count = raw[offset + 7] as usize;
                        if frequency_count == 0 {
                            if length >= 14 {
                                let minimum = le24(raw, offset + 8);
                                let maximum = le24(raw, offset + 11);
                                if minimum > 0 && maximum >= minimum {
                                    stream.sample_rate_ranges.push((minimum, maximum));
                                }
                            } else { malformed = true; }
                        } else {
                            let available = (length - 8) / 3;
                            if frequency_count > available { malformed = true; }
                            for index in 0..frequency_count.min(available) {
                                let rate = le24(raw, offset + 8 + index * 3);
                                if rate > 0 { stream.sample_rates.insert(rate); }
                            }
                        }
                    }
                }
            }
            _ => {}
        }
        offset += length;
    }
    (streams, malformed)
}

/// Returns all advertised UAC2 clock-source entities and their owning AudioControl interfaces.
pub fn clock_sources(raw: &[u8]) -> Vec<ClockSource> {
    let mut result = Vec::<ClockSource>::new();
    let mut current_interface = None;
    let mut current_is_audio_control = false;
    let mut offset = 0usize;
    while offset + 2 <= raw.len() {
        let length = raw[offset] as usize;
        if length < 2 || offset + length > raw.len() { break; }
        match raw[offset + 1] {
            0x04 if length >= 9 => {
                current_interface = Some(raw[offset + 2]);
                current_is_audio_control = raw[offset + 5] == 0x01 && raw[offset + 6] == 0x01;
            }
            0x24 if current_is_audio_control && length >= 4 && raw[offset + 2] == 0x0A => {
                let clock_id = raw[offset + 3];
                if clock_id != 0 {
                    let source = ClockSource {
                        clock_id,
                        control_interface: current_interface.unwrap_or(0xff),
                    };
                    if !result.contains(&source) { result.push(source); }
                }
            }
            _ => {}
        }
        offset += length;
    }
    result
}

pub fn clock_sources_json(raw: &[u8]) -> String {
    let values: Vec<Value> = clock_sources(raw).iter().map(|clock| json!({
        "clockId": clock.clock_id,
        "controlInterface": clock.control_interface
    })).collect();
    serde_json::to_string(&values).unwrap_or_else(|_| "[]".to_string())
}

/// Produces structured UAC1/UAC2 streaming-interface details for the Android presentation layer.
pub fn descriptor_report_json(raw: &[u8]) -> String {
    let (streams, malformed) = parse_stream_descriptors(raw);
    let values: Vec<Value> = streams.iter().map(|stream| json!({
        "interfaceNumber": stream.interface_number,
        "alternateSetting": stream.alternate_setting,
        "protocol": stream.protocol,
        "channels": stream.channels.iter().copied().collect::<Vec<_>>(),
        "subslotBytes": stream.subslot_bytes.iter().copied().collect::<Vec<_>>(),
        "bitResolutions": stream.bit_resolutions.iter().copied().collect::<Vec<_>>(),
        "sampleRates": stream.sample_rates.iter().copied().collect::<Vec<_>>(),
        "sampleRateRanges": stream.sample_rate_ranges.iter().map(|(min, max)| vec![min, max]).collect::<Vec<_>>(),
        "uac1FormatTypes": stream.uac1_format_types.iter().copied().collect::<Vec<_>>(),
        "uac1FormatTags": stream.uac1_format_tags.iter().copied().collect::<Vec<_>>(),
        "uac1MaxBitRates": stream.uac1_max_bit_rates.iter().copied().collect::<Vec<_>>(),
        "uac1SamplesPerFrames": stream.uac1_samples_per_frames.iter().copied().collect::<Vec<_>>(),
        "endpoints": stream.endpoints.clone()
    })).collect();
    serde_json::to_string(&json!({
        "descriptorBytes": raw.len(),
        "malformed": malformed,
        "streams": values
    })).unwrap_or_else(|_| "{\"descriptorBytes\":0,\"malformed\":true,\"streams\":[]}".to_string())
}

#[cfg(test)]
mod tests {
    use super::{clock_ranges_json, clock_sources, descriptor_report_json, parse_playback_profiles, playback_clock_sources, playback_descriptor_trace, select_usb_sample_rates_json};
    use serde_json::Value;

    #[test]
    fn parses_stereo_uac2_out_profile_and_packet_multiplier() {
        let descriptors = [
            9,4,2,2,1,1,2,0x20,0,
            16,0x24,1,5,0,0,0,0,0,0,2,0,0,0,0,0,
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
    fn parses_uac1_stereo_pcm_playback_profile_and_discrete_rates() {
        let descriptors = [
            9,4,2,1,1,1,2,0x00,0,
            7,0x24,1,5,0,1,0,
            14,0x24,2,1,2,3,24,2,0x44,0xAC,0,0x80,0xBB,0,
            7,5,0x01,0x05,0x26,1,1,
            7,5,0x81,0x11,0x03,1,1
        ];
        let profiles = parse_playback_profiles(&descriptors, Some(24));
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].protocol, 0x00);
        assert_eq!(profiles[0].channels, 2);
        assert_eq!(profiles[0].sample_rates, vec![44_100, 48_000]);
        assert_eq!(profiles[0].endpoint_address, 1);
        assert_eq!(profiles[0].feedback_endpoint, Some(0x81));
    }

    #[test]
    fn asynchronous_endpoint_requires_matching_explicit_feedback_endpoint() {
        let base = [
            9,4,2,1,1,1,2,0x00,0,
            7,0x24,1,5,0,1,0,
            14,0x24,2,1,2,3,24,2,0x44,0xac,0,0x80,0xbb,0,
            9,5,0x01,0x05,0x26,0,1,0,0x82
        ];
        assert!(parse_playback_profiles(&base, None).is_empty());

        let mut matching = base.to_vec();
        matching.extend_from_slice(&[7,5,0x82,0x11,0x03,1,1]);
        let profiles = parse_playback_profiles(&matching, None);
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].feedback_endpoint, Some(0x82));

        let mut mismatched = base.to_vec();
        mismatched.extend_from_slice(&[7,5,0x81,0x11,0x03,1,1]);
        assert!(parse_playback_profiles(&mismatched, None).is_empty());
    }

    #[test]
    fn adaptive_and_synchronous_endpoints_do_not_require_feedback_endpoint() {
        for attributes in [0x09, 0x0d] {
            let descriptors = [
                9,4,2,1,1,1,2,0x00,0,
                7,0x24,1,5,0,1,0,
                14,0x24,2,1,2,3,24,2,0x44,0xac,0,0x80,0xbb,0,
                7,5,0x01,attributes,0x26,0,1
            ];
            assert_eq!(parse_playback_profiles(&descriptors, None).len(), 1);
        }
    }

    #[test]
    fn reports_uac1_type_ii_and_type_iii_without_exposing_them_as_type_i_pcm() {
        let type_ii = [
            9,4,1,1,1,1,2,0,0,
            7,0x24,1,5,0,2,0,
            9,0x24,2,2,0x80,0x3e,0x00,0x01,0
        ];
        let report: Value = serde_json::from_str(&descriptor_report_json(&type_ii)).unwrap();
        assert_eq!(report["streams"][0]["uac1FormatTypes"][0], 2);
        assert_eq!(report["streams"][0]["uac1FormatTags"][0], 2);
        assert!(parse_playback_profiles(&type_ii, None).is_empty());

        let type_iii = [
            9,4,1,1,1,1,2,0,0,
            7,0x24,1,5,0,1,0,
            14,0x24,2,3,2,4,24,2,0x44,0xac,0,0x80,0xbb,0
        ];
        let report: Value = serde_json::from_str(&descriptor_report_json(&type_iii)).unwrap();
        assert_eq!(report["streams"][0]["uac1FormatTypes"][0], 3);
        assert_eq!(report["streams"][0]["channels"][0], 2);
        assert!(parse_playback_profiles(&type_iii, None).is_empty());
    }

    #[test]
    fn selects_nearest_rate_from_uac2_clock_ranges() {
        let ranges = r#"[{"minimum":48000,"maximum":96000,"resolution":1}]"#;
        let selected: Vec<u32> = serde_json::from_str(
            &select_usb_sample_rates_json(44_100, ranges)
        ).unwrap();
        assert_eq!(selected, vec![48_000]);
    }

    #[test]
    fn preserves_source_rate_when_clock_range_accepts_it() {
        let ranges = r#"[{"minimum":44100,"maximum":96000,"resolution":1}]"#;
        let selected: Vec<u32> = serde_json::from_str(
            &select_usb_sample_rates_json(44_100, ranges)
        ).unwrap();
        assert_eq!(selected[0], 44_100);
    }

    #[test]
    fn falls_back_to_verified_standard_rate_candidates_when_range_is_unavailable() {
        let selected: Vec<u32> = serde_json::from_str(
            &select_usb_sample_rates_json(48_000, "[]")
        ).unwrap();
        assert_eq!(selected[0], 48_000);
        assert!(selected.contains(&44_100));
        assert!(selected.contains(&192_000));
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

    #[test]
    fn parses_uac1_pcm_rates_and_endpoint_in_native_report() {
        let descriptors = [
            9,4,1,1,1,1,2,0,0,
            14,0x24,2,1,2,3,24,2,0x44,0xAC,0,0x80,0xBB,0,
            7,5,1,5,0,4,1
        ];
        let report: Value = serde_json::from_str(&descriptor_report_json(&descriptors)).unwrap();
        assert_eq!(report["streams"][0]["channels"][0], 2);
        assert_eq!(report["streams"][0]["sampleRates"][0], 44100);
        assert_eq!(report["streams"][0]["sampleRates"][1], 48000);
        assert_eq!(report["streams"][0]["endpoints"][0]["transfer"], "isochronous");
    }

    #[test]
    fn parses_uac1_continuous_sample_rate_range() {
        let descriptors = [
            9,4,1,1,1,1,2,0,0,
            14,0x24,2,1,2,3,24,0,0x44,0xAC,0,0x00,0x77,0x01
        ];
        let report: Value = serde_json::from_str(&descriptor_report_json(&descriptors)).unwrap();
        assert_eq!(report["streams"][0]["sampleRateRanges"][0][0], 44100);
        assert_eq!(report["streams"][0]["sampleRateRanges"][0][1], 96000);
    }

    #[test]
    fn discovers_all_audio_control_clock_sources_in_native_parser() {
        let descriptors = [
            9,4,0,0,0,1,1,0x20,0,
            8,0x24,0x0A,5,1,3,0,0,
            9,4,1,0,0,1,1,0x20,0,
            8,0x24,0x0A,7,1,3,0,0
        ];
        let clocks = clock_sources(&descriptors);
        assert_eq!(clocks.len(), 2);
        assert_eq!(clocks[0].clock_id, 5);
        assert_eq!(clocks[1].control_interface, 1);
    }

    #[test]
    fn descriptor_report_marks_truncated_sequences_without_panicking() {
        let report: Value = serde_json::from_str(&descriptor_report_json(&[9,4,0,0,0,1,1,0x20,0,255,0x24])).unwrap();
        assert_eq!(report["malformed"], true);
    }

    #[test]
    fn parses_only_complete_uac2_clock_ranges_from_transferred_bytes() {
        let mut response = vec![1, 0];
        response.extend_from_slice(&44_100u32.to_le_bytes());
        response.extend_from_slice(&96_000u32.to_le_bytes());
        response.extend_from_slice(&1u32.to_le_bytes());
        let parsed: Value = serde_json::from_str(&clock_ranges_json(&response, response.len())).unwrap();
        assert_eq!(parsed[0]["minimum"], 44_100);
        assert_eq!(parsed[0]["maximum"], 96_000);
        assert_eq!(parsed[0]["resolution"], 1);
        assert_eq!(clock_ranges_json(&response, 13), "[]");
    }

    #[test]
    fn playback_descriptor_trace_is_bounded_and_identifies_selected_stream() {
        let descriptors = [
            9,4,2,1,1,1,2,0x20,0,
            3,0x24,1,
            9,4,3,0,0,1,1,0x20,0
        ];
        let trace = playback_descriptor_trace(&descriptors, 2);
        assert!(trace.contains("SELECTED_STREAM"));
        assert!(trace.contains("number=2"));
    }

}
