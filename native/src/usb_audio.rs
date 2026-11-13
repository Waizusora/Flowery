//! USB Audio Class descriptor parsing shared by all class-compliant DACs.
//!
//! This module intentionally does not contain VID/PID-specific device tables.
//! It parses configuration descriptors and reports candidate playback alternates.
//! Runtime clock selection and isochronous transfer scheduling belong to the USB
//! transport layer and must be validated against the selected interface/alternate.

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UsbAudioDataFormat {
    Pcm,
    Pcm8,
    IeeeFloat,
}

impl UsbAudioDataFormat {
    pub fn code(self) -> u8 {
        match self { Self::Pcm => 1, Self::Pcm8 => 2, Self::IeeeFloat => 3 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct UsbAudioFormat {
    pub interface_number: u8,
    pub alternate_setting: u8,
    pub protocol: u8,
    pub channels: Option<u8>,
    pub subslot_bytes: Option<u8>,
    pub bit_resolution: Option<u8>,
    pub format_type: Option<u8>,
    pub data_format: Option<UsbAudioDataFormat>,
    pub sample_rates: Vec<u32>,
    pub continuous_rate_range: Option<(u32, u32)>,
    pub endpoint_address: Option<u8>,
    pub max_packet_size: Option<u16>,
    pub interval: Option<u8>,
    pub synchronization_type: Option<u8>,
    pub feedback_endpoint_address: Option<u8>,
    pub feedback_max_packet_size: Option<u16>,
    pub feedback_interval: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
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
    data_format: Option<UsbAudioDataFormat>,
    sample_rates: Vec<u32>,
    continuous_rate_range: Option<(u32, u32)>,
    endpoint_address: Option<u8>,
    max_packet_size: Option<u16>,
    interval: Option<u8>,
    synchronization_type: Option<u8>,
    feedback_endpoint_address: Option<u8>,
    feedback_max_packet_size: Option<u16>,
    feedback_interval: Option<u8>,
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
                    data_format: a.data_format,
                    sample_rates: a.sample_rates,
                    continuous_rate_range: a.continuous_rate_range,
                    endpoint_address: a.endpoint_address,
                    max_packet_size: a.max_packet_size,
                    interval: a.interval,
                    synchronization_type: a.synchronization_type,
                    feedback_endpoint_address: a.feedback_endpoint_address,
                    feedback_max_packet_size: a.feedback_max_packet_size,
                    feedback_interval: a.feedback_interval,
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
                            // UAC1 AS_GENERAL carries the two-byte wFormatTag.
                            0x01 if a.protocol == 0x00 && length >= 7 => {
                                let format_tag = u16::from_le_bytes([d[5], d[6]]);
                                a.data_format = match format_tag {
                                    0x0001 => Some(UsbAudioDataFormat::Pcm),
                                    0x0002 => Some(UsbAudioDataFormat::Pcm8),
                                    0x0003 => Some(UsbAudioDataFormat::IeeeFloat),
                                    _ => None,
                                };
                            }
                            // FORMAT_TYPE_I: format, channels, subframe size, bit resolution, rate count.
                            0x02 if a.protocol == 0x00 && length >= 8 => {
                                a.format_type = Some(d[3]);
                                a.channels = Some(d[4]);
                                a.subslot_bytes = Some(d[5]);
                                a.bit_resolution = Some(d[6]);
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
                            // UAC2 AS_GENERAL: bmFormats is a 32-bit bitmap at bytes 6..10.
                            // Exactly one supported Type I format bit must be selected.
                            0x01 if a.protocol == 0x20 && length >= 16 => {
                                a.channels = Some(d[10]);
                                let formats = u32::from_le_bytes([d[6], d[7], d[8], d[9]]);
                                a.data_format = match formats {
                                    0x0000_0001 => Some(UsbAudioDataFormat::Pcm),
                                    0x0000_0002 => Some(UsbAudioDataFormat::Pcm8),
                                    0x0000_0004 => Some(UsbAudioDataFormat::IeeeFloat),
                                    _ => None,
                                };
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
                    if a.subclass == 0x02 && (d[3] & 0x03) == 0x01 {
                        let is_input = d[2] & 0x80 != 0;
                        let usage = (d[3] >> 4) & 0x03;
                        if is_input && usage == 0x01 {
                            a.feedback_endpoint_address = Some(d[2]);
                            a.feedback_max_packet_size = Some(u16::from_le_bytes([d[4], d[5]]));
                            a.feedback_interval = Some(d[6]);
                        } else if !is_input {
                            a.endpoint_address = Some(d[2]);
                            a.synchronization_type = Some((d[3] >> 2) & 0x03);
                            a.max_packet_size = Some(u16::from_le_bytes([d[4], d[5]]));
                            a.interval = Some(d[6]);
                        }
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


/// Selects a descriptor-advertised PCM playback alternate. This is a planning
/// result only: it does not claim the USB interface or submit isochronous URBs.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct UsbAudioStreamPlan {
    pub interface_number: u8,
    pub alternate_setting: u8,
    pub endpoint_address: u8,
    pub max_packet_size: u16,
    pub interval: u8,
    pub synchronization_type: Option<u8>,
    pub sample_rate: u32,
    pub channels: u8,
    pub subslot_bytes: u8,
    pub bit_resolution: u8,
    pub data_format: UsbAudioDataFormat,
    pub requires_uac2_clock_negotiation: bool,
    pub feedback_endpoint_address: Option<u8>,
    pub feedback_max_packet_size: Option<u16>,
    pub feedback_interval: Option<u8>,
}

pub fn plan_pcm_playback(
    descriptors: &[u8],
    sample_rate: u32,
    channels: u8,
    bit_resolution: u8,
) -> Result<UsbAudioStreamPlan, String> {
    plan_pcm_playback_with_format(descriptors, sample_rate, channels, bit_resolution, UsbAudioDataFormat::Pcm)
}

pub fn plan_pcm_playback_with_format(
    descriptors: &[u8],
    sample_rate: u32,
    channels: u8,
    bit_resolution: u8,
    requested_format: UsbAudioDataFormat,
) -> Result<UsbAudioStreamPlan, String> {
    if sample_rate == 0 || channels == 0 || bit_resolution == 0 {
        return Err("Requested PCM parameters must be non-zero".to_string());
    }
    let parsed = parse_configuration_descriptors(descriptors);
    let mut missing_async_feedback = false;
    for format in parsed.formats {
        let (Some(format_channels), Some(subslot_bytes), Some(bits), Some(endpoint), Some(packet), Some(interval)) = (
            format.channels, format.subslot_bytes, format.bit_resolution,
            format.endpoint_address, format.max_packet_size, format.interval
        ) else { continue };

        if format.format_type != Some(1)
            || format.data_format != Some(requested_format)
            || format_channels != channels
            || bits != bit_resolution
            || subslot_bytes.saturating_mul(8) < bits
        {
            continue;
        }
        // UAC2 frequency support is determined by the Clock Source GET_RANGE
        // control, not by the UAC1 discrete-rate fields in the AS descriptor.
        let rate_supported = format.sample_rates.contains(&sample_rate)
            || format.continuous_rate_range
                .map(|(min, max)| sample_rate >= min && sample_rate <= max)
                .unwrap_or(false)
            || (format.protocol == 0x20 && format.sample_rates.is_empty()
                && format.continuous_rate_range.is_none());
        if !rate_supported { continue; }
        if format.synchronization_type == Some(1) && format.feedback_endpoint_address.is_none() {
            missing_async_feedback = true;
            continue;
        }

        return Ok(UsbAudioStreamPlan {
            interface_number: format.interface_number,
            alternate_setting: format.alternate_setting,
            endpoint_address: endpoint,
            max_packet_size: packet,
            interval,
            synchronization_type: format.synchronization_type,
            sample_rate,
            channels,
            subslot_bytes,
            bit_resolution: bits,
            data_format: requested_format,
            requires_uac2_clock_negotiation: format.protocol == 0x20,
            feedback_endpoint_address: if format.synchronization_type == Some(1) {
                format.feedback_endpoint_address
            } else {
                None
            },
            feedback_max_packet_size: if format.synchronization_type == Some(1) {
                format.feedback_max_packet_size
            } else {
                None
            },
            feedback_interval: if format.synchronization_type == Some(1) {
                format.feedback_interval
            } else {
                None
            },
        });
    }
    if missing_async_feedback {
        return Err("The matching asynchronous USB Audio OUT endpoint has no explicit feedback endpoint; implicit feedback is not supported yet".into());
    }
    Err(format!(
        "No advertised {:?} Type I playback alternate matches {sample_rate} Hz, {channels} channel(s), {bit_resolution}-bit", requested_format
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_uac1_stereo_pcm_playback_and_discrete_rates() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 1, 0, 0, // AudioControl interface
            9, 4, 2, 1, 1, 1, 2, 0, 0, // AudioStreaming alt 1
            7, 0x24, 1, 1, 1, 1, 0, // AS_GENERAL, stereo
            14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00, // stereo, 16-bit, 44.1/48 kHz
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
    
    #[test]
    fn plans_matching_uac1_pcm_alternate() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 2, 0, 0,
            9, 4, 2, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00,
            7, 5, 0x01, 0x01, 0x00, 0x04, 1,
        ];
        let plan = plan_pcm_playback(&descriptors, 44_100, 2, 16).unwrap();
        assert_eq!(plan.interface_number, 2);
        assert_eq!(plan.alternate_setting, 1);
        assert_eq!(plan.endpoint_address, 0x01);
        assert_eq!(plan.sample_rate, 44_100);
        assert!(!plan.requires_uac2_clock_negotiation);
        assert!(plan_pcm_playback(&descriptors, 96_000, 2, 24).is_err());
    }

    #[test]
    fn associates_explicit_feedback_endpoint_with_playback_alternate() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 2, 0, 0,
            9, 4, 2, 1, 2, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00,
            7, 5, 0x01, 0x05, 0x00, 0x04, 1,
            7, 5, 0x81, 0x11, 0x03, 0x00, 1,
        ];
        let plan = plan_pcm_playback(&descriptors, 44_100, 2, 16).unwrap();
        assert_eq!(plan.endpoint_address, 0x01);
        assert_eq!(plan.feedback_endpoint_address, Some(0x81));
        assert_eq!(plan.feedback_max_packet_size, Some(3));
        assert_eq!(plan.feedback_interval, Some(1));
        assert_eq!(plan.synchronization_type, Some(1));
    }

    #[test]
    fn rejects_asynchronous_out_without_explicit_feedback() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 2, 0, 0,
            9, 4, 2, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 1, 0,
            14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00,
            7, 5, 0x01, 0x05, 0x00, 0x04, 1,
        ];
        let error = plan_pcm_playback(&descriptors, 44_100, 2, 16).unwrap_err();
        assert!(error.contains("no explicit feedback endpoint"));
    }

    #[test]
    fn plans_uac2_pcm_and_defers_rate_validation_to_clock_source() {
        let descriptors = [
            9, 4, 0, 0, 0, 1, 1, 0x20, 0, // UAC2 AudioControl
            8, 0x24, 0x0A, 7, 0x03, 0x02, 0, 0, // Clock Source 7
            17, 0x24, 0x02, 1, 0x01, 0x01, 0, 7, 2, 3, 0, 0, 0, 0, 0, 0, 0, // USB Streaming Input Terminal
            9, 4, 1, 1, 1, 1, 2, 0x20, 0, // UAC2 AudioStreaming alt 1
            16, 0x24, 0x01, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0, // AS_GENERAL
            6, 0x24, 0x02, 1, 4, 24, // Type I, 4-byte subslot, 24 valid bits
            7, 5, 0x01, 0x09, 0x00, 0x14, 1, // Adaptive isochronous OUT
        ];
        let plan = plan_pcm_playback(&descriptors, 48_000, 2, 24).unwrap();
        assert_eq!(plan.interface_number, 1);
        assert_eq!(plan.channels, 2);
        assert_eq!(plan.subslot_bytes, 4);
        assert_eq!(plan.bit_resolution, 24);
        assert!(plan.requires_uac2_clock_negotiation);
    }

    #[test]
    fn preserves_high_speed_packet_multiplier_bits() {
        let descriptors = [
            9, 4, 1, 0, 0, 1, 2, 0x20, 0,
            9, 4, 2, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 1, 1, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 4, 24,
            7, 5, 0x01, 0x05, 0x00, 0x14, 1,
        ];
        let parsed = parse_configuration_descriptors(&descriptors);
        assert_eq!(parsed.formats[0].max_packet_size, Some(0x1400));
    }

}
