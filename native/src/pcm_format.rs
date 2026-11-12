/// Converts normalized floating-point samples to signed integer PCM with saturation.
/// Kept for unit tests; production output quantizes directly in the AAudio callback.
#[cfg(test)]
pub(super) fn quantize_signed_pcm(sample: f32, gain: f32, valid_bits: u8) -> i64 {
    if !sample.is_finite() || !gain.is_finite() || !(1..=32).contains(&valid_bits) {
        return 0;
    }
    let scale = (1_i64 << (valid_bits - 1)) as f32;
    let minimum = -(1_i64 << (valid_bits - 1));
    let maximum = (1_i64 << (valid_bits - 1)) - 1;
    let scaled = (sample.clamp(-1.0, 1.0) * gain.clamp(0.0, 1.0) * scale).round();
    (scaled as i64).clamp(minimum, maximum)
}

/// Platform-neutral signed integer PCM layout used at native output boundaries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct PcmFormat {
    pub sample_rate: u32,
    pub channels: u16,
    pub valid_bits: u8,
    pub subslot_bytes: u8,
    pub little_endian: bool,
}

impl PcmFormat {
    pub fn validate(self) -> Result<Self, &'static str> {
        if self.sample_rate == 0 || self.channels == 0 {
            return Err("PCM sample rate and channel count must be positive");
        }
        if !(1..=4).contains(&self.subslot_bytes) || !self.little_endian {
            return Err("Unsupported PCM subslot or byte order");
        }
        if !matches!(self.valid_bits, 8 | 16 | 20 | 24 | 32)
            || self.valid_bits as u16 > self.subslot_bytes as u16 * 8 {
            return Err("Unsupported signed integer PCM representation");
        }
        Ok(self)
    }

    #[cfg(test)]
    pub fn bytes_per_frame(self) -> usize {
        self.channels as usize * self.subslot_bytes as usize
    }
}

/// Pack decoded normalized float samples into little-endian USB Audio Class PCM.
/// Valid sample bits are left-aligned in each subslot, as required by Type I PCM.
pub(super) fn encode_usb_pcm(
    samples: &[f32],
    format: PcmFormat,
    output: &mut Vec<u8>,
) -> Result<(), &'static str> {
    let format = format.validate()?;
    if !matches!(format.valid_bits, 16 | 20 | 24 | 32) {
        return Err("USB PCM encoder supports signed 16/20/24/32-bit samples");
    }
    let channels = usize::from(format.channels);
    if samples.len() % channels != 0 {
        return Err("Decoded PCM sample count is not channel-frame aligned");
    }
    let required = samples.len().checked_mul(usize::from(format.subslot_bytes))
        .ok_or("USB PCM output size overflow")?;
    output.clear();
    output.reserve(required);
    let scale = (1_i64 << (format.valid_bits - 1)) as f64;
    let minimum = -(1_i64 << (format.valid_bits - 1));
    let maximum = (1_i64 << (format.valid_bits - 1)) - 1;
    let padding = format.subslot_bytes * 8 - format.valid_bits;
    for sample in samples {
        let normalized = if sample.is_finite() { sample.clamp(-1.0, 1.0) } else { 0.0 };
        let quantized = ((normalized as f64 * scale).round() as i64).clamp(minimum, maximum);
        let packed = (quantized << padding) as u32;
        let bytes = packed.to_le_bytes();
        output.extend_from_slice(&bytes[..usize::from(format.subslot_bytes)]);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{encode_usb_pcm, PcmFormat};

    #[test]
    fn accepts_integer_16_and_24_bit_pcm_layouts() {
        for format in [
            PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 16, subslot_bytes: 2, little_endian: true },
            PcmFormat { sample_rate: 96_000, channels: 2, valid_bits: 24, subslot_bytes: 3, little_endian: true },
        ] {
            assert_eq!(format.validate().unwrap(), format);
            assert_eq!(format.bytes_per_frame(), format.channels as usize * format.subslot_bytes as usize);
        }
    }

    #[test]
    fn rejects_float_like_or_invalid_pcm_layouts() {
        let invalid = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 32, subslot_bytes: 4, little_endian: false };
        assert!(invalid.validate().is_err());
        let invalid = PcmFormat { sample_rate: 0, channels: 2, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        assert!(invalid.validate().is_err());
    }
    #[test]
    fn encodes_24_valid_bits_left_aligned_in_32_bit_subslot() {
        let format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 24, subslot_bytes: 4, little_endian: true };
        let mut output = Vec::new();
        encode_usb_pcm(&[0.0, -1.0, 0.5, 1.0], format, &mut output).unwrap();
        assert_eq!(&output[0..4], &[0, 0, 0, 0]);
        assert_eq!(&output[4..8], &[0, 0, 0, 0x80]);
        assert_eq!(&output[8..12], &[0, 0, 0, 0x40]);
        assert_eq!(&output[12..16], &[0, 0, 0, 0x7f]);
    }

    #[test]
    fn rejects_unaligned_pcm_sample_count() {
        let format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        assert!(encode_usb_pcm(&[0.0], format, &mut Vec::new()).is_err());
    }

}
