use crate::usb_pcm::PcmFormat;

/// Integer PCM samples retained in their decoded source representation.
///
/// Signed samples are stored without normalization. The valid-bit width is
/// explicit so packed and padded USB subslots can be produced without a
/// floating-point round trip.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) enum NativePcmSamples {
    I8(Vec<i8>),
    I16(Vec<i16>),
    I20(Vec<i32>),
    I24(Vec<i32>),
    I32(Vec<i32>),
}

impl NativePcmSamples {
    pub(super) fn valid_bits(&self) -> u8 {
        match self {
            Self::I8(_) => 8,
            Self::I16(_) => 16,
            Self::I20(_) => 20,
            Self::I24(_) => 24,
            Self::I32(_) => 32,
        }
    }

    pub(super) fn len(&self) -> usize {
        match self {
            Self::I8(values) => values.len(),
            Self::I16(values) => values.len(),
            Self::I20(values) | Self::I24(values) | Self::I32(values) => values.len(),
        }
    }

    fn sample_i32(&self, index: usize) -> i32 {
        match self {
            Self::I8(values) => values[index] as i32,
            Self::I16(values) => values[index] as i32,
            Self::I20(values) | Self::I24(values) | Self::I32(values) => values[index],
        }
    }
}

/// Conditions that must all hold before a native integer block can be
/// advertised as bit-perfect for the negotiated direct USB PCM format.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct BitPerfectRequirements {
    pub source_rate: u32,
    pub output_rate: u32,
    pub source_channels: u16,
    pub output_channels: u16,
    pub source_valid_bits: u8,
    pub dsp_enabled: bool,
    pub software_gain_unity: bool,
    pub channel_mapping_unchanged: bool,
    pub resampling_disabled: bool,
}

pub(super) fn is_bit_perfect_eligible(
    requirements: BitPerfectRequirements,
    format: PcmFormat,
) -> bool {
    requirements.source_rate == requirements.output_rate
        && requirements.source_channels == requirements.output_channels
        && requirements.source_valid_bits == format.valid_bits
        && requirements.source_channels == format.channels
        && requirements.source_rate == format.sample_rate
        && !requirements.dsp_enabled
        && requirements.software_gain_unity
        && requirements.channel_mapping_unchanged
        && requirements.resampling_disabled
        && format.validate().is_ok()
}

/// Packs signed integer samples into the negotiated little-endian USB subslot.
///
/// Values are range-checked instead of clamped. Clamping would silently alter
/// source samples and make a bit-perfect claim invalid.
pub(super) fn pack_native_integer_pcm(
    samples: &NativePcmSamples,
    format: PcmFormat,
) -> Result<Vec<u8>, String> {
    let format = format.validate().map_err(str::to_string)?;
    let source_bits = samples.valid_bits();
    if source_bits != format.valid_bits {
        return Err(format!(
            "Native PCM depth mismatch: source is {source_bits}-bit but USB requires {}-bit",
            format.valid_bits
        ));
    }
    if samples.len() % format.channels as usize != 0 {
        return Err("Native PCM block contains an incomplete interleaved frame".into());
    }

    let minimum = -(1_i64 << (source_bits - 1));
    let maximum = (1_i64 << (source_bits - 1)) - 1;
    let mut output = Vec::with_capacity(samples.len() * format.subslot_bytes as usize);

    for index in 0..samples.len() {
        let value = samples.sample_i32(index) as i64;
        if !(minimum..=maximum).contains(&value) {
            return Err(format!(
                "Native PCM sample at index {index} exceeds its declared {source_bits}-bit range"
            ));
        }

        // USB PCM subslots left-justify the valid sample bits. Any unused
        // least-significant subslot bits are zero, not sign-extension bits.
        let subslot_shift = (format.subslot_bytes as u32 * 8) - source_bits as u32;
        let encoded = (value as i32).wrapping_shl(subslot_shift) as u32;
        let bytes = encoded.to_le_bytes();
        output.extend_from_slice(&bytes[..format.subslot_bytes as usize]);
    }

    Ok(output)
}


/// Extracts exact signed integer samples from Symphonia's typed decoded planes.
///
/// Symphonia's FLAC decoder uses S32 storage with valid bits left-aligned.
/// The low padding bits are verified before shifting back to the source width.
/// Float buffers are intentionally rejected so the caller cannot accidentally
/// advertise a floating-point round trip as native integer PCM.
pub(super) fn from_symphonia_buffer(
    decoded: symphonia::core::audio::AudioBufferRef<'_>,
    valid_bits: u8,
    channels: usize,
    discard_frames: usize,
) -> Result<NativePcmSamples, String> {
    use symphonia::core::audio::Signal;

    fn interleave<T: Copy>(planes: Vec<Vec<T>>, frames: usize) -> Vec<T> {
        let mut output = Vec::with_capacity(frames * planes.len());
        for frame in 0..frames {
            for plane in &planes { output.push(plane[frame]); }
        }
        output
    }

    let frames = decoded.frames();
    if channels == 0 || decoded.spec().channels.count() != channels || discard_frames > frames {
        return Err("Decoded native PCM channel or frame layout is invalid".into());
    }
    let take = frames - discard_frames;

    macro_rules! planes {
        ($buffer:expr, $convert:expr) => {{
            let mut values = Vec::with_capacity(channels);
            for channel in 0..channels {
                let plane = $buffer.chan(channel);
                let converted = plane[discard_frames..].iter().take(take)
                    .map($convert).collect::<Result<Vec<i32>, String>>()?;
                values.push(converted);
            }
            interleave(values, take)
        }};
    }

    fn signed_from_left_aligned(value: i32, storage_bits: u8, valid_bits: u8) -> Result<i32, String> {
        if valid_bits == 0 || valid_bits > storage_bits {
            return Err("Decoded PCM valid-bit width is incompatible with its sample storage".into());
        }
        let shift = storage_bits - valid_bits;
        if shift > 0 {
            let mask = (1_i32 << shift) - 1;
            if value & mask != 0 {
                return Err("Decoder returned non-zero padding bits; exact native PCM extraction is unsafe".into());
            }
        }
        Ok(value >> shift)
    }

    let samples = match decoded {
        symphonia::core::audio::AudioBufferRef::S8(buffer) if valid_bits == 8 => {
            NativePcmSamples::I8(planes!(buffer, |v: &i8| Ok(*v as i32)).into_iter().map(|v| v as i8).collect())
        }
        symphonia::core::audio::AudioBufferRef::U8(buffer) if valid_bits == 8 => {
            NativePcmSamples::I8(planes!(buffer, |v: &u8| Ok(*v as i32 - 128)).into_iter().map(|v| v as i8).collect())
        }
        symphonia::core::audio::AudioBufferRef::S16(buffer) if matches!(valid_bits, 8 | 16) => {
            let values = planes!(buffer, |v: &i16| signed_from_left_aligned(*v as i32, 16, valid_bits));
            if valid_bits == 16 { NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()) }
            else { NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()) }
        }
        symphonia::core::audio::AudioBufferRef::U16(buffer) if matches!(valid_bits, 8 | 16) => {
            let shift = 16 - valid_bits;
            let midpoint = 1_i64 << (valid_bits - 1);
            let values = planes!(buffer, |v: &u16| Ok(((*v as i64 >> shift) - midpoint) as i32));
            if valid_bits == 16 { NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()) }
            else { NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()) }
        }
        symphonia::core::audio::AudioBufferRef::S24(buffer) if matches!(valid_bits, 8 | 16 | 20 | 24) => {
            let values = planes!(buffer, |v: &symphonia::core::sample::i24| signed_from_left_aligned(v.inner(), 24, valid_bits));
            match valid_bits {
                8 => NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()),
                16 => NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()),
                20 => NativePcmSamples::I20(values),
                _ => NativePcmSamples::I24(values),
            }
        }
        symphonia::core::audio::AudioBufferRef::U24(buffer) if matches!(valid_bits, 8 | 16 | 20 | 24) => {
            let shift = 24 - valid_bits;
            let midpoint = 1_i64 << (valid_bits - 1);
            let values = planes!(buffer, |v: &symphonia::core::sample::u24| Ok(((v.inner() as i64 >> shift) - midpoint) as i32));
            match valid_bits {
                8 => NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()),
                16 => NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()),
                20 => NativePcmSamples::I20(values),
                _ => NativePcmSamples::I24(values),
            }
        }
        symphonia::core::audio::AudioBufferRef::S32(buffer) if matches!(valid_bits, 8 | 16 | 20 | 24 | 32) => {
            let values = planes!(buffer, |v: &i32| signed_from_left_aligned(*v, 32, valid_bits));
            match valid_bits {
                8 => NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()),
                16 => NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()),
                20 => NativePcmSamples::I20(values),
                24 => NativePcmSamples::I24(values),
                32 => NativePcmSamples::I32(values),
                _ => unreachable!(),
            }
        }
        symphonia::core::audio::AudioBufferRef::U32(buffer) if matches!(valid_bits, 8 | 16 | 20 | 24 | 32) => {
            if valid_bits == 32 {
                NativePcmSamples::I32(planes!(buffer, |v: &u32| Ok((*v ^ 0x8000_0000) as i32)))
            } else {
                let shift = 32 - valid_bits;
                let midpoint = 1_i64 << (valid_bits - 1);
                let values = planes!(buffer, |v: &u32| Ok(((*v as i64 >> shift) - midpoint) as i32));
                match valid_bits {
                    8 => NativePcmSamples::I8(values.into_iter().map(|v| v as i8).collect()),
                    16 => NativePcmSamples::I16(values.into_iter().map(|v| v as i16).collect()),
                    20 => NativePcmSamples::I20(values),
                    24 => NativePcmSamples::I24(values),
                    _ => unreachable!(),
                }
            }
        }
        _ => return Err("This decoder sample representation is not eligible for native integer PCM".into()),
    };
    Ok(samples)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn format(valid_bits: u8, subslot_bytes: u8) -> PcmFormat {
        PcmFormat {
            sample_rate: 96_000,
            channels: 2,
            valid_bits,
            subslot_bytes,
            signed: true,
            little_endian: true,
        }
    }

    #[test]
    fn packs_native_16_bit_samples_without_float_conversion() {
        let samples = NativePcmSamples::I16(vec![i16::MIN, -1, 0, i16::MAX]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(16, 2)).unwrap(),
            vec![0x00, 0x80, 0xff, 0xff, 0x00, 0x00, 0xff, 0x7f]
        );
    }

    #[test]
    fn packs_native_24_bit_samples_into_three_byte_subslots() {
        let samples = NativePcmSamples::I24(vec![-8_388_608, -1, 0, 8_388_607]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(24, 3)).unwrap(),
            vec![0x00, 0x00, 0x80, 0xff, 0xff, 0xff, 0x00, 0x00, 0x00, 0xff, 0xff, 0x7f]
        );
    }

    #[test]
    fn packs_native_24_bit_samples_left_justified_in_four_byte_subslots() {
        let samples = NativePcmSamples::I24(vec![-8_388_608, -1, 0, 8_388_607]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(24, 4)).unwrap(),
            vec![
                0x00, 0x00, 0x00, 0x80,
                0x00, 0xff, 0xff, 0xff,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0xff, 0xff, 0x7f
            ]
        );
    }


    #[test]
    fn packs_native_20_bit_samples_left_justified_in_three_byte_subslots() {
        let samples = NativePcmSamples::I20(vec![-524_288, -1, 0, 524_287]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(20, 3)).unwrap(),
            vec![0x00, 0x00, 0x80, 0xf0, 0xff, 0xff, 0x00, 0x00, 0x00, 0xf0, 0xff, 0x7f]
        );
    }

    #[test]
    fn packs_native_20_bit_samples_left_justified_in_four_byte_subslots() {
        let samples = NativePcmSamples::I20(vec![-524_288, -1, 0, 524_287]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(20, 4)).unwrap(),
            vec![
                0x00, 0x00, 0x00, 0x80,
                0x00, 0xf0, 0xff, 0xff,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0xf0, 0xff, 0x7f
            ]
        );
    }

    #[test]
    fn packs_native_32_bit_samples_exactly() {
        let samples = NativePcmSamples::I32(vec![i32::MIN, -1, 0, i32::MAX]);
        assert_eq!(
            pack_native_integer_pcm(&samples, format(32, 4)).unwrap(),
            vec![
                0x00, 0x00, 0x00, 0x80,
                0xff, 0xff, 0xff, 0xff,
                0x00, 0x00, 0x00, 0x00,
                0xff, 0xff, 0xff, 0x7f
            ]
        );
    }

    #[test]
    fn rejects_depth_mismatch_and_out_of_range_samples() {
        assert!(pack_native_integer_pcm(
            &NativePcmSamples::I16(vec![0, 1]),
            format(24, 3)
        ).is_err());
        assert!(pack_native_integer_pcm(
            &NativePcmSamples::I24(vec![8_388_608, 0]),
            format(24, 3)
        ).is_err());
    }

    #[test]
    fn bit_perfect_gate_rejects_any_processing_or_format_change() {
        let requirements = BitPerfectRequirements {
            source_rate: 96_000,
            output_rate: 96_000,
            source_channels: 2,
            output_channels: 2,
            source_valid_bits: 24,
            dsp_enabled: false,
            software_gain_unity: true,
            channel_mapping_unchanged: true,
            resampling_disabled: true,
        };
        assert!(is_bit_perfect_eligible(requirements, format(24, 3)));
        assert!(!is_bit_perfect_eligible(
            BitPerfectRequirements { dsp_enabled: true, ..requirements },
            format(24, 3)
        ));
        assert!(!is_bit_perfect_eligible(
            BitPerfectRequirements { output_rate: 48_000, ..requirements },
            format(24, 3)
        ));
        assert!(!is_bit_perfect_eligible(
            BitPerfectRequirements { software_gain_unity: false, ..requirements },
            format(24, 3)
        ));
    }
    #[test]
    fn symphonia_signed_16_bit_planes_are_interleaved_without_sample_changes() {
        use std::borrow::Cow;
        use symphonia::core::audio::{AudioBuffer, AudioBufferRef, Channels, Signal, SignalSpec};
        let spec = SignalSpec::new(48_000, Channels::FRONT_LEFT | Channels::FRONT_RIGHT);
        let mut buffer = AudioBuffer::<i16>::new(2, spec);
        buffer.render_reserved(Some(2));
        buffer.chan_mut(0).copy_from_slice(&[i16::MIN, 1234]);
        buffer.chan_mut(1).copy_from_slice(&[-1, i16::MAX]);
        let samples = from_symphonia_buffer(AudioBufferRef::S16(Cow::Owned(buffer)), 16, 2, 0).unwrap();
        assert_eq!(samples, NativePcmSamples::I16(vec![i16::MIN, -1, 1234, i16::MAX]));
        assert_eq!(
            pack_native_integer_pcm(&samples, PcmFormat {
                sample_rate: 48_000, channels: 2, valid_bits: 16,
                subslot_bytes: 2, signed: true, little_endian: true,
            }).unwrap(),
            vec![0x00, 0x80, 0xff, 0xff, 0xd2, 0x04, 0xff, 0x7f]
        );
    }

    #[test]
    fn symphonia_flac_style_left_aligned_24_bit_samples_are_restored_exactly() {
        use std::borrow::Cow;
        use symphonia::core::audio::{AudioBuffer, AudioBufferRef, Channels, Signal, SignalSpec};
        let spec = SignalSpec::new(96_000, Channels::FRONT_LEFT | Channels::FRONT_RIGHT);
        let mut buffer = AudioBuffer::<i32>::new(2, spec);
        buffer.render_reserved(Some(2));
        buffer.chan_mut(0).copy_from_slice(&[i32::MIN, 0x12345600]);
        buffer.chan_mut(1).copy_from_slice(&[-256, 0x7fffff00]);
        let samples = from_symphonia_buffer(AudioBufferRef::S32(Cow::Owned(buffer)), 24, 2, 0).unwrap();
        assert_eq!(samples, NativePcmSamples::I24(vec![-8_388_608, -1, 1_193_046, 8_388_607]));
        assert_eq!(
            pack_native_integer_pcm(&samples, PcmFormat {
                sample_rate: 96_000, channels: 2, valid_bits: 24,
                subslot_bytes: 3, signed: true, little_endian: true,
            }).unwrap(),
            vec![
                0x00, 0x00, 0x80, 0xff, 0xff, 0xff,
                0x56, 0x34, 0x12, 0xff, 0xff, 0x7f
            ]
        );
    }

    #[test]
    fn native_extraction_rejects_nonzero_decoder_padding_bits() {
        use std::borrow::Cow;
        use symphonia::core::audio::{AudioBuffer, AudioBufferRef, Channels, Signal, SignalSpec};
        let spec = SignalSpec::new(48_000, Channels::FRONT_LEFT);
        let mut buffer = AudioBuffer::<i32>::new(1, spec);
        buffer.render_reserved(Some(1));
        buffer.chan_mut(0)[0] = 0x12345601;
        assert!(from_symphonia_buffer(AudioBufferRef::S32(Cow::Owned(buffer)), 24, 1, 0).is_err());
    }

}
