//! Descriptor-driven output compatibility planning.
//!
//! This module chooses a DAC-supported PCM tuple without changing the decoder's
//! native sample representation. It describes required processing; it does not
//! perform resampling or quantization.

use serde::Serialize;

use crate::usb_audio::{parse_configuration_descriptors, UsbAudioDataFormat, UsbAudioFormat};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceSampleFormat {
    Integer,
    Float32,
}

impl SourceSampleFormat {
    pub fn from_uac_code(code: u8) -> Result<Self, &'static str> {
        match code {
            1 | 2 => Ok(Self::Integer),
            3 => Ok(Self::Float32),
            _ => Err("Unsupported source PCM representation"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourcePcm {
    pub sample_rate: u32,
    pub channels: u8,
    pub valid_bits: u8,
    pub sample_format: SourceSampleFormat,
}

impl SourcePcm {
    pub fn validate(self) -> Result<Self, &'static str> {
        if self.sample_rate == 0 || !(1..=8).contains(&self.channels) {
            return Err("Invalid source sample rate or channel count");
        }
        if !matches!(self.valid_bits, 8 | 16 | 20 | 24 | 32) {
            return Err("Unsupported source PCM bit depth");
        }
        if self.sample_format == SourceSampleFormat::Float32 && self.valid_bits != 32 {
            return Err("Float32 source PCM must declare 32 valid bits");
        }
        Ok(self)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BitPerfectAssessment {
    Candidate,
    NotBitPerfect,
    NotApplicable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct OutputCompatibilityPlan {
    pub ok: bool,
    pub source_sample_rate: u32,
    pub source_channels: u8,
    pub source_valid_bits: u8,
    pub source_sample_format: SourceSampleFormat,
    pub output_sample_rate: Option<u32>,
    pub output_channels: Option<u8>,
    pub output_valid_bits: Option<u8>,
    pub output_subslot_bytes: Option<u8>,
    pub output_data_format: Option<UsbAudioDataFormat>,
    pub requires_resampling: bool,
    pub requires_bit_depth_conversion: bool,
    pub requires_bit_depth_reduction: bool,
    pub requires_bit_depth_expansion: bool,
    pub requires_sample_format_conversion: bool,
    pub requires_channel_mapping: bool,
    pub bit_perfect: BitPerfectAssessment,
    pub bit_perfect_reason: String,
    pub interface_number: Option<u8>,
    pub alternate_setting: Option<u8>,
    pub endpoint_address: Option<u8>,
    pub warnings: Vec<String>,
    pub error: Option<String>,
}

impl OutputCompatibilityPlan {
    fn failure(source: SourcePcm, error: impl Into<String>) -> Self {
        Self {
            ok: false,
            source_sample_rate: source.sample_rate,
            source_channels: source.channels,
            source_valid_bits: source.valid_bits,
            source_sample_format: source.sample_format,
            output_sample_rate: None,
            output_channels: None,
            output_valid_bits: None,
            output_subslot_bytes: None,
            output_data_format: None,
            requires_resampling: false,
            requires_bit_depth_conversion: false,
            requires_bit_depth_reduction: false,
            requires_bit_depth_expansion: false,
            requires_sample_format_conversion: false,
            requires_channel_mapping: false,
            bit_perfect: BitPerfectAssessment::NotApplicable,
            bit_perfect_reason: "No compatible USB alternate was found".into(),
            interface_number: None,
            alternate_setting: None,
            endpoint_address: None,
            warnings: Vec::new(),
            error: Some(error.into()),
        }
    }
}

fn supports_rate(format: &UsbAudioFormat, rate: u32) -> bool {
    format.sample_rates.contains(&rate)
        || format.continuous_rate_range
            .map(|(min, max)| (min..=max).contains(&rate))
            .unwrap_or(false)
}

fn candidate_rates(format: &UsbAudioFormat, source_rate: u32) -> Vec<u32> {
    let mut rates = format.sample_rates.clone();
    if let Some((min, max)) = format.continuous_rate_range {
        rates.extend([min, max, source_rate.clamp(min, max)]);
    }
    rates.sort_unstable();
    rates.dedup();
    rates
}

fn candidate_depths(format: &UsbAudioFormat, source: SourcePcm) -> Vec<(u8, u8, UsbAudioDataFormat)> {
    let mut candidates = Vec::new();
    let formats = if format.supported_data_formats.is_empty() {
        format.data_format.into_iter().collect::<Vec<_>>()
    } else {
        format.supported_data_formats.clone()
    };

    for data_format in formats {
        match data_format {
            UsbAudioDataFormat::Pcm => {
                if let (Some(bits), Some(subslot)) = (format.bit_resolution, format.subslot_bytes) {
                    if matches!(bits, 8 | 16 | 20 | 24 | 32)
                        && (1..=4).contains(&subslot)
                        && bits <= subslot.saturating_mul(8)
                    {
                        candidates.push((bits, subslot, data_format));
                    }
                }
            }
            UsbAudioDataFormat::Pcm8 => {
                if source.sample_format == SourceSampleFormat::Integer {
                    if let Some(subslot) = format.subslot_bytes {
                        if subslot == 1 {
                            candidates.push((8, subslot, data_format));
                        }
                    }
                }
            }
            UsbAudioDataFormat::IeeeFloat => {
                if source.sample_format == SourceSampleFormat::Float32
                    && format.bit_resolution == Some(32)
                    && format.subslot_bytes == Some(4)
                {
                    candidates.push((32, 4, data_format));
                }
            }
        }
    }
    candidates
}

fn rate_distance(a: u32, b: u32) -> u64 {
    u64::from(a.abs_diff(b))
}

fn plan_score(
    source: SourcePcm,
    rate: u32,
    bits: u8,
    data_format: UsbAudioDataFormat,
) -> (u8, u64, u8, u8) {
    let rate_changed = u8::from(rate != source.sample_rate);
    let sample_format_changed = u8::from(
        (source.sample_format == SourceSampleFormat::Float32)
            != (data_format == UsbAudioDataFormat::IeeeFloat),
    );
    let depth_changed = u8::from(bits != source.valid_bits);
    // Prefer preserving rate first, then native representation and bit depth.
    (rate_changed, rate_distance(rate, source.sample_rate), sample_format_changed, depth_changed)
}

/// Select the best descriptor-advertised UAC playback tuple for the source.
/// The planner never claims that a resampler or converter has already run.
pub fn plan_usb_output_compatibility(
    descriptors: &[u8],
    source: SourcePcm,
) -> OutputCompatibilityPlan {
    let parsed = parse_configuration_descriptors(descriptors);
    plan_from_usb_formats(&parsed.formats, parsed.warnings, source)
}

/// Pure planner entry point used by descriptor parsing and deterministic tests.
fn plan_from_usb_formats(
    formats: &[UsbAudioFormat],
    parser_warnings: Vec<String>,
    source: SourcePcm,
) -> OutputCompatibilityPlan {
    let source = match source.validate() {
        Ok(source) => source,
        Err(error) => return OutputCompatibilityPlan::failure(source, error),
    };
    let mut choices = Vec::new();

    for format in formats {
        if format.channels != Some(source.channels) {
            continue;
        }
        let depths = candidate_depths(format, source);
        for rate in candidate_rates(format, source.sample_rate) {
            for &(bits, subslot, data_format) in &depths {
                choices.push((plan_score(source, rate, bits, data_format), format, rate, bits, subslot, data_format));
            }
        }
    }

    choices.sort_by_key(|choice| choice.0);
    let Some((_, format, output_rate, output_bits, output_subslot, output_format)) = choices.first().copied() else {
        let mut failure = OutputCompatibilityPlan::failure(
            source,
            "No USB playback alternate supports the source channel count and a compatible PCM encoding",
        );
        failure.warnings = parser_warnings;
        return failure;
    };

    let requires_resampling = output_rate != source.sample_rate;
    let requires_bit_depth_conversion = output_bits != source.valid_bits;
    let requires_sample_format_conversion = match source.sample_format {
        SourceSampleFormat::Integer => output_format != UsbAudioDataFormat::Pcm
            && output_format != UsbAudioDataFormat::Pcm8,
        SourceSampleFormat::Float32 => output_format != UsbAudioDataFormat::IeeeFloat,
    };
    let exact_native_tuple = !requires_resampling
        && !requires_bit_depth_conversion
        && !requires_sample_format_conversion
        && format.channels == Some(source.channels);

    let mut warnings = parser_warnings;
    if requires_resampling {
        warnings.push("High-quality sample-rate conversion is required before USB packing".into());
    }
    if requires_bit_depth_conversion {
        warnings.push(if requires_bit_depth_reduction {\n            "Bit-depth reduction is required; apply TPDF dither before integer PCM packing".into()\n        } else {\n            "Bit-depth expansion is required; preserve the integer sample value without adding precision".into()\n        });
    }
    if requires_sample_format_conversion {
        warnings.push("Sample-format conversion is required at the output boundary".into());
    }

    OutputCompatibilityPlan {
        ok: true,
        source_sample_rate: source.sample_rate,
        source_channels: source.channels,
        source_valid_bits: source.valid_bits,
        source_sample_format: source.sample_format,
        output_sample_rate: Some(output_rate),
        output_channels: Some(source.channels),
        output_valid_bits: Some(output_bits),
        output_subslot_bytes: Some(output_subslot),
        output_data_format: Some(output_format),
        requires_resampling,
        requires_bit_depth_conversion,
        requires_sample_format_conversion,
        requires_channel_mapping: false,
        bit_perfect: if exact_native_tuple { BitPerfectAssessment::Candidate } else { BitPerfectAssessment::NotBitPerfect },
        bit_perfect_reason: if exact_native_tuple {
            "Native source PCM tuple matches the selected USB alternate; hardware and transport verification are still required".into()
        } else {
            "Output rate, precision, or sample representation differs from the decoded source".into()
        },
        interface_number: Some(format.interface_number),
        alternate_setting: Some(format.alternate_setting),
        endpoint_address: format.endpoint_address,
        warnings,
        error: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn format(rate: u32, bits: u8, subslot: u8) -> UsbAudioFormat {
        UsbAudioFormat {
            interface_number: 1,
            alternate_setting: 1,
            protocol: 0x20,
            channels: Some(2),
            subslot_bytes: Some(subslot),
            bit_resolution: Some(bits),
            format_type: Some(1),
            data_format: Some(UsbAudioDataFormat::Pcm),
            supported_data_formats: vec![UsbAudioDataFormat::Pcm],
            sample_rates: vec![rate],
            continuous_rate_range: None,
            endpoint_address: Some(0x01),
            max_packet_size: Some(196),
            interval: Some(1),
            synchronization_type: Some(1),
            feedback_endpoint_address: None,
            feedback_max_packet_size: None,
            feedback_interval: None,
        }
    }

    #[test]
    fn exact_native_24_48_tuple_is_a_candidate_not_a_hardware_claim() {
        let source = SourcePcm { sample_rate: 48_000, channels: 2, valid_bits: 24, sample_format: SourceSampleFormat::Integer };
        let plan = plan_from_usb_formats(&[format(48_000, 24, 3)], Vec::new(), source);
        assert!(plan.ok);
        assert!(!plan.requires_resampling);
        assert!(!plan.requires_bit_depth_conversion);
        assert_eq!(plan.bit_perfect, BitPerfectAssessment::Candidate);
    }

    #[test]
    fn 24_96_source_on_24_48_dac_requires_resampling_only() {
        let source = SourcePcm { sample_rate: 96_000, channels: 2, valid_bits: 24, sample_format: SourceSampleFormat::Integer };
        let plan = plan_from_usb_formats(&[format(48_000, 24, 3)], Vec::new(), source);
        assert!(plan.ok);
        assert_eq!(plan.output_sample_rate, Some(48_000));
        assert!(plan.requires_resampling);
        assert!(!plan.requires_bit_depth_conversion);
        assert_eq!(plan.bit_perfect, BitPerfectAssessment::NotBitPerfect);
    }

    #[test]
    fn twenty_four_bit_source_on_sixteen_bit_48khz_dac_requires_resampling_and_dithered_reduction() {
        let source = SourcePcm { sample_rate: 96_000, channels: 2, valid_bits: 24, sample_format: SourceSampleFormat::Integer };
        let plan = plan_from_usb_formats(&[format(48_000, 16, 2)], Vec::new(), source);
        assert!(plan.ok);
        assert_eq!(plan.output_valid_bits, Some(16));
        assert!(plan.requires_resampling);
        assert!(plan.requires_bit_depth_conversion);
        assert!(plan.requires_bit_depth_reduction);\n        assert!(plan.warnings.iter().any(|warning| warning.contains("TPDF")));
    }

    #[test]
    fn float_lossy_decode_can_target_integer_pcm_without_changing_decoder_output() {
        let source = SourcePcm { sample_rate: 44_100, channels: 2, valid_bits: 32, sample_format: SourceSampleFormat::Float32 };
        let plan = plan_from_usb_formats(&[format(48_000, 24, 3)], Vec::new(), source);
        assert!(plan.ok);
        assert!(plan.requires_resampling);
        assert!(plan.requires_bit_depth_conversion);
        assert!(plan.requires_sample_format_conversion);
    }

    #[test]
    fn source_rate_is_preferred_when_the_dac_advertises_it() {
        let source = SourcePcm { sample_rate: 48_000, channels: 2, valid_bits: 24, sample_format: SourceSampleFormat::Integer };
        let plan = plan_from_usb_formats(&[format(44_100, 24, 3), format(48_000, 24, 3)], Vec::new(), source);
        assert_eq!(plan.output_sample_rate, Some(48_000));
    }

    #[test]
    fn rejects_invalid_source_tuple() {
        let source = SourcePcm { sample_rate: 0, channels: 2, valid_bits: 24, sample_format: SourceSampleFormat::Integer };
        let plan = plan_from_usb_formats(&[], Vec::new(), source);
        assert!(!plan.ok);
        assert!(plan.error.is_some());
    }
}
