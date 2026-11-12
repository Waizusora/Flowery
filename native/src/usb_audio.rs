//! USB Audio Class descriptor parsing shared by all class-compliant DACs.
//!
//! This module intentionally does not contain VID/PID-specific device tables.
//! It parses configuration descriptors and reports candidate playback alternates.
//! Runtime clock selection and isochronous transfer scheduling belong to the USB
//! transport layer and must be validated against the selected interface/alternate.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbAudioFormat {
    pub interface_number: u8,
    pub alternate_setting: u8,
    pub protocol: u8,
    pub channels: Option<u8>,
    pub subslot_bytes: Option<u8>,
    pub bit_resolution: Option<u8>,
    pub format_type: Option<u8>,
    pub sample_rates: Vec<u32>,
    pub continuous_rate_range: Option<(u32, u32)>,
    pub endpoint_address: Option<u8>,
    pub max_packet_size: Option<u16>,
    pub interval: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbAudioParseResult {
    pub uac_version: Option<&'static str>,
    pub formats: Vec<UsbAudioFormat>,
    pub warnings: Vec<String>,
}

#[derive(Default)]
struct Alternate {
    interface_number: u8,
    alternate_setting: u8,
    protocol: u8,
    subclass: u8,
    channels: Option<u8>,
    subslot_bytes: Option<u8>,
    bit_resolution: Option<u8>,
    format_type: Option<u8>,
    sample_rates: Vec<u32>,
    continuous_rate_range: Option<(u32, u32)>,
    endpoint_address: Option<u8>,
    max_packet_size: Option<u16>,
    interval: Option<u8>,
}

fn le24(b: &[u8], i: usize) -> Option<u32> {
    Some(u32::from(b.get(i).copied()?)
        | (u32::from(b.get(i + 1).copied()?) << 8)
        | (u32::from(b.get(i + 2).copied()?) << 16))
}

/// Parse a USB configuration descriptor blob returned by UsbDeviceConnection.getRawDescriptors().
/// Malformed/truncated descriptors stop parsing safely and are reported as warnings.
pub fn parse_configuration_descriptors(bytes: &[u8]) -> UsbAudioParseResult {
    let mut result = UsbAudioParseResult { uac_version: None, formats: Vec::new(), warnings: Vec::new() };
    let mut current: Option<Alternate> = None;
    let mut offset = 0usize;

    let finish = |alt: Option<Alternate>, output: &mut UsbAudioParseResult| {
        if let Some(a) = alt {
            if a.subclass == 0x02 && a.alternate_setting > 0 && a.endpoint_address
                .map(|address| address & 0x80 == 0).unwrap_or(false) {
                output.formats.push(UsbAudioFormat {
                    interface_number: a.interface_number,
                    alternate_setting: a.alternate_setting,
                    protocol: a.protocol,
                    channels: a.channels,
                    subslot_bytes: a.subslot_bytes,
                    bit_resolution: a.bit_resolution,
                    format_type: a.format_type,
                    sample_rates: a.sample_rates,
                    continuous_rate_range: a.continuous_rate_range,
                    endpoint_address: a.endpoint_address,
                    max_packet_size: a.max_packet_size,
                    interval: a.interval,
                });
            }
        }
    };

    while offset < bytes.len() {
        if bytes.len() - offset < 2 {
            result.warnings.push(format!("Truncated descriptor header at byte {offset}"));
            break;
        }
        let length = bytes[offset] as usize;
        let kind = bytes[offset + 1];
        if length < 2 || offset + length > bytes.len() {
            result.warnings.push(format!("Invalid descriptor length {length} at byte {offset}"));
            break;
        }
        let d = &bytes[offset..offset + length];
        match kind {
            0x04 if length >= 9 => {
                finish(current.take(), &mut result);
                let class = d[5];
                let subclass = d[6];
                let protocol = d[7];
                if class == 0x01 && subclass == 0x01 {
                    result.uac_version = Some(match protocol {
                        0x00 => "UAC1",
                        0x20 => "UAC2",
                        0x30 => "UAC3",
                        _ => "UAC-unknown",
                    });
                }
                current = Some(Alternate {
                    interface_number: d[2],
                    alternate_setting: d[3],
                    protocol,
                    subclass,
                    ..Alternate::default()
                });
            }
            0x24 if length >= 3 => {
                if let Some(a) = current.as_mut() {
                    if a.subclass == 0x02 {
                        match d[2] {
                            // AS_GENERAL: UAC1 has channels at byte 4; UAC2 uses bmFormats.
                            0x01 if a.protocol == 0x00 && length >= 7 => {
                                a.channels = Some(d[4]);
                            }
                            // FORMAT_TYPE: UAC1 Type I descriptor.
                            0x02 if a.protocol == 0x00 && length >= 8 => {
                                a.format_type = Some(d[3]);
                                a.subslot_bytes = Some(d[4]);
                                a.bit_resolution = Some(d[5]);
                                let count = d[7] as usize;
                                if count == 0 && length >= 14 {
                                    let min = le24(d, 8);
                                    let max = le24(d, 11);
                                    if let (Some(min), Some(max)) = (min, max) {
                                        a.continuous_rate_range = Some((min, max));
                                    }
                                } else {
                                    for index in 0..count {
                                        if let Some(rate) = le24(d, 8 + index * 3) {
                                            if rate > 0 { a.sample_rates.push(rate); }
                                        } else {
                                            result.warnings.push(format!("Truncated UAC1 sample-rate list at byte {offset}"));
                                            break;
                                        }
                                    }
                                }
                            }
                            // UAC2 AS_GENERAL: channel count follows terminal link and controls.
                            0x01 if a.protocol == 0x20 && length >= 16 => {
                                a.channels = Some(d[10]);
                            }
                            // UAC2 FORMAT_TYPE: Type I, subslot size and bit resolution.
                            0x02 if a.protocol == 0x20 && length >= 6 => {
                                a.format_type = Some(d[3]);
                                a.subslot_bytes = Some(d[4]);
                                a.bit_resolution = Some(d[5]);
                            }
                            _ => {}
                        }
                    }
                }
            }
            0x05 if length >= 7 => {
                if let Some(a) = current.as_mut() {
                    if a.subclass == 0x02 && d[2] & 0x80 == 0 && (d[3] & 0x03) == 0x01 {
                        a.endpoint_address = Some(d[2]);
                        a.max_packet_size = Some(u16::from_le_bytes([d[4], d[5]]) & 0x07ff);
                        a.interval = Some(d[6]);
                    }
                }
            }
            _ => {}
        }
        offset += length;
    }
    finish(current, &mut result);
    if result.formats.is_empty() {
        result.warnings.push("No playback AudioStreaming alternate with an isochronous OUT endpoint was parsed".to_string());
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_uac1_stereo_pcm_playback_and_discrete_rates() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 2, 0, 0, // AudioControl interface
            9, 4, 2, 1, 1, 1, 2, 0, 0, // AudioStreaming alt 1
            7, 0x24, 1, 1, 1, 1, 0, // AS_GENERAL, stereo
            14, 0x24, 2, 1, 2, 16, 0, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00, // 44.1/48 kHz
            7, 5, 0x01, 0x01, 0x00, 0x04, 1, // Iso OUT
        ];
        let parsed = parse_configuration_descriptors(&descriptors);
        assert_eq!(parsed.uac_version, Some("UAC1"));
        assert_eq!(parsed.formats.len(), 1);
        assert_eq!(parsed.formats[0].channels, Some(2));
        assert_eq!(parsed.formats[0].subslot_bytes, Some(2));
        assert_eq!(parsed.formats[0].bit_resolution, Some(16));
        assert_eq!(parsed.formats[0].sample_rates, vec![44_100, 48_000]);
    }

    #[test]
    fn ignores_input_endpoint_and_non_streaming_interfaces() {
        let descriptors = [
            9, 4, 2, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            7, 5, 0x81, 0x01, 0x00, 0x04, 1,
        ];
        assert!(parse_configuration_descriptors(&descriptors).formats.is_empty());
    }

    #[test]
    fn rejects_truncated_descriptors_without_panicking() {
        let parsed = parse_configuration_descriptors(&[9, 4, 1]);
        assert!(parsed.formats.is_empty());
        assert!(!parsed.warnings.is_empty());
    }
}
