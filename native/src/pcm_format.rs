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
        if !(1..=8).contains(&self.subslot_bytes) || !self.little_endian {
            return Err("Unsupported PCM subslot or byte order");
        }
        if !matches!(self.valid_bits, 8 | 16 | 20 | 24 | 32 | 64)
            || self.valid_bits as u16 > self.subslot_bytes as u16 * 8 {
            return Err("Unsupported PCM sample representation");
        }
        Ok(self)
    }

    #[cfg(test)]
    pub fn bytes_per_frame(self) -> usize {
        self.channels as usize * self.subslot_bytes as usize
    }
}

/// Decoded samples retained in the exact Symphonia sample representation.
#[derive(Clone, Debug, PartialEq)]
pub(super) enum UsbPcmSamples {
    Empty,
    U8(Vec<u8>),
    S16(Vec<i16>),
    S24(Vec<i32>),
    S32(Vec<i32>),
    F32(Vec<f32>),
    F64(Vec<f64>),
}

impl UsbPcmSamples {
    pub fn from_audio_buffer(decoded: symphonia::core::audio::AudioBufferRef<'_>) -> Result<Self, &'static str> {
        use symphonia::core::audio::{AudioBufferRef, Signal};
        fn interleave<T: Copy>(buffer: &symphonia::core::audio::AudioBuffer<T>) -> Vec<T>
        where T: symphonia::core::sample::Sample {
            let channels = buffer.spec().channels.count();
            let frames = buffer.frames();
            let mut samples = Vec::with_capacity(frames.saturating_mul(channels));
            for frame in 0..frames {
                for channel in 0..channels {
                    samples.push(buffer.chan(channel)[frame]);
                }
            }
            samples
        }
        Ok(match decoded {
            AudioBufferRef::U8(buffer) => Self::U8(interleave(&buffer)),
            AudioBufferRef::S16(buffer) => Self::S16(interleave(&buffer)),
            AudioBufferRef::S24(buffer) => Self::S24(interleave(&buffer).into_iter().map(|sample| sample.inner()).collect()),
            AudioBufferRef::S32(buffer) => Self::S32(interleave(&buffer)),
            AudioBufferRef::F32(buffer) => Self::F32(interleave(&buffer)),
            AudioBufferRef::F64(buffer) => Self::F64(interleave(&buffer)),
            AudioBufferRef::U16(_) | AudioBufferRef::U24(_) | AudioBufferRef::U32(_)
            | AudioBufferRef::S8(_) => return Err("USB output does not support this Symphonia decoded sample representation yet"),
        })
    }

    pub fn append(&mut self, other: Self) -> Result<(), &'static str> {
        if matches!(self, Self::Empty) {
            *self = other;
            return Ok(());
        }
        if matches!(other, Self::Empty) {
            return Ok(());
        }
        match (self, other) {
            (Self::U8(a), Self::U8(b)) => a.extend(b),
            (Self::S16(a), Self::S16(b)) => a.extend(b),
            (Self::S24(a), Self::S24(b)) => a.extend(b),
            (Self::S32(a), Self::S32(b)) => a.extend(b),
            (Self::F32(a), Self::F32(b)) => a.extend(b),
            (Self::F64(a), Self::F64(b)) => a.extend(b),
            _ => return Err("Decoded sample representation changed during the track"),
        }
        Ok(())
    }

    pub fn drain(&mut self, count: usize) -> Self {
        macro_rules! take { ($v:expr, $variant:ident) => {{ let n = count.min($v.len()); Self::$variant($v.drain(..n).collect()) }}; }
        match self {
            Self::Empty => Self::Empty,
            Self::U8(v) => take!(v, U8),
            Self::S16(v) => take!(v, S16),
            Self::S24(v) => take!(v, S24),
            Self::S32(v) => take!(v, S32),
            Self::F32(v) => take!(v, F32),
            Self::F64(v) => take!(v, F64),
        }
    }

    pub fn len(&self) -> usize {
        match self { Self::Empty => 0, Self::U8(v) => v.len(), Self::S16(v) => v.len(), Self::S24(v) => v.len(),
            Self::S32(v) => v.len(), Self::F32(v) => v.len(), Self::F64(v) => v.len() }
    }

    /// USB Audio Class Type I data format code: PCM=1, PCM8=2, IEEE_FLOAT=3.
    pub fn data_format_code(&self) -> u8 {
        match self {
            Self::U8(_) => 2,
            Self::S16(_) | Self::S24(_) | Self::S32(_) => 1,
            Self::F32(_) => 3,
            Self::F64(_) => 4,
            Self::Empty => 0,
        }
    }
}

/// Dispatch typed decoded samples to the matching USB integer or float encoder.
pub(super) fn encode_usb_pcm(
    samples: &UsbPcmSamples,
    format: PcmFormat,
    output: &mut Vec<u8>,
) -> Result<(), &'static str> {
    match samples {
        UsbPcmSamples::F32(_) | UsbPcmSamples::F64(_) =>
            encode_usb_float_pcm(samples, format, output),
        _ => encode_usb_integer_pcm(samples, format, output),
    }
}

/// Enforce descriptor-selected UAC Type I encoding before packing the bytes.
pub(super) fn encode_usb_pcm_for_data_format(
    samples: &UsbPcmSamples,
    format: PcmFormat,
    data_format: u8,
    output: &mut Vec<u8>,
) -> Result<(), &'static str> {
    if !matches!(samples, UsbPcmSamples::Empty) && samples.data_format_code() != data_format {
        return Err("Decoded sample representation does not match the USB descriptor data format");
    }
    encode_usb_pcm(samples, format, output)
}

/// Pack integer samples in their native code representation. No float conversion
/// or requantization is performed.
pub(super) fn encode_usb_integer_pcm(
    samples: &UsbPcmSamples,
    format: PcmFormat,
    output: &mut Vec<u8>,
) -> Result<(), &'static str> {
    let format = format.validate()?;
    validate_sample_alignment(samples, format)?;
    output.clear();
    if samples.len() == 0 { return Ok(()); }
    match samples {
        UsbPcmSamples::U8(values) if format.valid_bits == 8 && format.subslot_bytes == 1 =>
            output.extend(values),
        UsbPcmSamples::S16(values) if format.valid_bits == 16 && format.subslot_bytes == 2 =>
            for value in values { output.extend_from_slice(&value.to_le_bytes()); },
        UsbPcmSamples::S16(values) if format.valid_bits == 16 && format.subslot_bytes == 4 =>
            for value in values { output.extend_from_slice(&((*value as u32) << 16).to_le_bytes()); },
        UsbPcmSamples::S24(values) if format.valid_bits == 24 && format.subslot_bytes == 3 =>
            for value in values { output.extend_from_slice(&value.to_le_bytes()[..3]); },
        UsbPcmSamples::S24(values) if format.valid_bits == 24 && format.subslot_bytes == 4 =>
            for value in values { output.extend_from_slice(&((*value as u32) << 8).to_le_bytes()); },
        // Symphonia's FLAC decoder stores every decoded depth in i32 and
        // left-aligns valid sample bits. Remove only the low padding bits when
        // the USB subslot is narrower than 32 bits; preserve left alignment in
        // a 32-bit subslot.
        UsbPcmSamples::S32(values)
            if matches!(format.valid_bits, 16 | 24 | 32)
                && matches!(format.subslot_bytes, 2 | 3 | 4)
                && format.valid_bits as u16 <= format.subslot_bytes as u16 * 8 =>
        {
            let padding = if format.subslot_bytes == 4 {
                0
            } else {
                32 - u32::from(format.valid_bits)
            };
            for value in values {
                let aligned = if padding == 0 { *value } else { *value >> padding };
                let bytes = aligned.to_le_bytes();
                output.extend_from_slice(&bytes[..format.subslot_bytes as usize]);
            }
        },
        UsbPcmSamples::Empty => {},
        _ => return Err("USB integer PCM format does not exactly match the decoder sample representation"),
    }
    Ok(())
}

/// Pack IEEE-754 float samples directly as little-endian USB Audio Class data.
pub(super) fn encode_usb_float_pcm(
    samples: &UsbPcmSamples,
    format: PcmFormat,
    output: &mut Vec<u8>,
) -> Result<(), &'static str> {
    let format = format.validate()?;
    validate_sample_alignment(samples, format)?;
    output.clear();
    if samples.len() == 0 { return Ok(()); }
    match samples {
        UsbPcmSamples::F32(values) if format.valid_bits == 32 && format.subslot_bytes == 4 =>
            for value in values { output.extend_from_slice(&value.to_le_bytes()); },
        UsbPcmSamples::F64(values) if format.valid_bits == 64 && format.subslot_bytes == 8 =>
            for value in values { output.extend_from_slice(&value.to_le_bytes()); },
        UsbPcmSamples::Empty => {},
        _ => return Err("USB float PCM format does not exactly match the decoder sample representation"),
    }
    Ok(())
}

fn validate_sample_alignment(
    samples: &UsbPcmSamples,
    format: PcmFormat,
) -> Result<(), &'static str> {
    if samples.len() % usize::from(format.channels) != 0 {
        return Err("Decoded PCM sample count is not channel-frame aligned");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{encode_usb_float_pcm, encode_usb_pcm, encode_usb_pcm_for_data_format, PcmFormat, UsbPcmSamples};


    fn make_test_wav(format_tag: u16, bits: u16, sample_rate: u32, sample_bytes: &[u8]) -> Vec<u8> {
        let channels = 1_u16;
        let block_align = channels * (bits / 8);
        let byte_rate = sample_rate * u32::from(block_align);
        let data_len = sample_bytes.len() as u32;
        let mut wav = Vec::with_capacity(44 + sample_bytes.len());
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36_u32 + data_len).to_le_bytes());
        wav.extend_from_slice(b"WAVEfmt ");
        wav.extend_from_slice(&16_u32.to_le_bytes());
        wav.extend_from_slice(&format_tag.to_le_bytes());
        wav.extend_from_slice(&channels.to_le_bytes());
        wav.extend_from_slice(&sample_rate.to_le_bytes());
        wav.extend_from_slice(&byte_rate.to_le_bytes());
        wav.extend_from_slice(&block_align.to_le_bytes());
        wav.extend_from_slice(&bits.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&data_len.to_le_bytes());
        wav.extend_from_slice(sample_bytes);
        wav
    }

    fn decode_test_audio(bytes: Vec<u8>, extension: &str) -> UsbPcmSamples {
        use std::io::{Cursor, ErrorKind};
        use symphonia::core::codecs::{Decoder, DecoderOptions};
        use symphonia::core::errors::Error;
        use symphonia::core::formats::{FormatOptions, FormatReader};
        use symphonia::core::io::MediaSourceStream;
        use symphonia::core::meta::MetadataOptions;
        use symphonia::core::probe::Hint;

        let source = MediaSourceStream::new(Box::new(Cursor::new(bytes)), Default::default());
        let mut hint = Hint::new();
        hint.with_extension(extension);
        let probed = symphonia::default::get_probe()
            .format(&hint, source, &FormatOptions::default(), &MetadataOptions::default())
            .expect("probe test audio");
        let mut format = probed.format;
        let track = format.default_track().expect("default test track");
        let mut decoder = symphonia::default::get_codecs()
            .make(&track.codec_params, &DecoderOptions::default())
            .expect("create test decoder");
        let mut samples = UsbPcmSamples::Empty;
        loop {
            let packet = match format.next_packet() {
                Ok(packet) => packet,
                Err(Error::IoError(error)) if error.kind() == ErrorKind::UnexpectedEof => break,
                Err(error) => panic!("read test packet: {error}"),
            };
            let decoded = decoder.decode(&packet).expect("decode test packet");
            samples.append(UsbPcmSamples::from_audio_buffer(decoded).expect("typed decoded samples"))
                .expect("stable decoded representation");
        }
        samples
    }

    #[test]
    fn end_to_end_wav_pcm16_pcm24_pcm32_and_float32_preserve_sample_bytes() {
        let pcm16 = [-32768_i16, -1, 0, 1, 32767];
        let pcm16_bytes: Vec<u8> = pcm16.iter().flat_map(|sample| sample.to_le_bytes()).collect();
        let pcm24 = [-8_388_608_i32, -1, 0, 1, 8_388_607];
        let pcm24_bytes: Vec<u8> = pcm24.iter().flat_map(|sample| sample.to_le_bytes()[..3].to_vec()).collect();
        let pcm32 = [i32::MIN, -1, 0, 1, i32::MAX];
        let pcm32_bytes: Vec<u8> = pcm32.iter().flat_map(|sample| sample.to_le_bytes()).collect();
        let float32 = [0.0_f32, -0.5, 1.0, f32::from_bits(0x3f80_0001)];
        let float32_bytes: Vec<u8> = float32.iter().flat_map(|sample| sample.to_le_bytes()).collect();

        let cases = [
            (make_test_wav(1, 16, 48_000, &pcm16_bytes), 16_u8, 2_u8, 1_u8, pcm16_bytes),
            (make_test_wav(1, 24, 48_000, &pcm24_bytes), 24_u8, 3_u8, 1_u8, pcm24_bytes),
            (make_test_wav(1, 32, 48_000, &pcm32_bytes), 32_u8, 4_u8, 1_u8, pcm32_bytes),
            (make_test_wav(3, 32, 48_000, &float32_bytes), 32_u8, 4_u8, 3_u8, float32_bytes),
        ];

        for (wav, valid_bits, subslot_bytes, data_format, expected) in cases {
            let decoded = decode_test_audio(wav, "wav");
            let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits, subslot_bytes, little_endian: true };
            let mut actual = Vec::new();
            encode_usb_pcm_for_data_format(&decoded, format, data_format, &mut actual)
                .expect("encode descriptor-matched USB PCM");
            assert_eq!(actual, expected, "valid_bits={valid_bits}, data_format={data_format}");
        }
    }

    #[test]
    fn end_to_end_flac24_s32_left_alignment_is_restored_to_usb_24_bit_pcm() {
        use base64::Engine;
        const FLAC_24_FIXTURE: &str = "ZkxhQwAAACIQABAAAAAYAAAYC7gBcAAAAAX5cmc8NYTV0MCDbk1mk3rMhAAAKCAAAAByZWZlcmVuY2UgbGliRkxBQyAxLjUuMCAyMDI1MDIxMQAAAAD/+GoMAASQGIAAAP///wAAAAAAAQAQREw=";
        let flac = base64::engine::general_purpose::STANDARD.decode(FLAC_24_FIXTURE).unwrap();
        let decoded = decode_test_audio(flac, "flac");
        assert!(matches!(decoded, UsbPcmSamples::S32(_)), "FLAC 24-bit should decode to Symphonia S32");
        let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 24, subslot_bytes: 3, little_endian: true };
        let mut actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, format, 1, &mut actual).unwrap();
        let source = [-8_388_608_i32, -1, 0, 1, 8_388_607];
        let expected: Vec<u8> = source.iter().flat_map(|sample| sample.to_le_bytes()[..3].to_vec()).collect();
        assert_eq!(actual, expected);
    }

    #[test]
    fn end_to_end_alac24_m4a_decodes_and_preserves_pcm_codes() {
        use base64::Engine;
        // Five mono samples: INT24_MIN, -1, 0, 1, INT24_MAX.
        const ALAC_24_FIXTURE: &str = "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAqptb292AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAAAQABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAB1XRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAAAEAAAAAAAEAAAAAAU1tZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAALuAAAAABVXEAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAD4bWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAC8c3RibAAAAFhzdHNkAAAAAAAAAAEAAABIYWxhYwAAAAAAAAABAAAAAAAAAAAAAQAYAAAAALuAAAAAAAAkYWxhYwAAAAAAABAAABgoCg4BAAAAADAEABGUAAAAu4AAAAAYc3R0cwAAAAAAAAABAAAAAQAAAAUAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAFHN0c3oAAAAAAAAAFwAAAAEAAAAUc3RjbwAAAAAAAAABAAAC1gAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjEuNy4xMDMAAAAIZnJlZQAAAB9tZGF0AAASAAAACwAAAf///gAAAAAAAv///8A=";
        let m4a = base64::engine::general_purpose::STANDARD.decode(ALAC_24_FIXTURE).unwrap();
        let decoded = decode_test_audio(m4a, "m4a");
        assert!(matches!(decoded, UsbPcmSamples::S32(_)), "ALAC 24-bit should decode to Symphonia S32");
        let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 24, subslot_bytes: 3, little_endian: true };
        let mut actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, format, 1, &mut actual).unwrap();
        let source = [-8_388_608_i32, -1, 0, 1, 8_388_607];
        let expected: Vec<u8> = source.iter().flat_map(|sample| sample.to_le_bytes()[..3].to_vec()).collect();
        assert_eq!(actual, expected);
    }

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
        encode_usb_pcm(&UsbPcmSamples::S24(vec![0, -8_388_608, 4_194_304, 8_388_607]), format, &mut output).unwrap();
        assert_eq!(&output[0..4], &[0, 0, 0, 0]);
        assert_eq!(&output[4..8], &[0, 0, 0, 0x80]);
        assert_eq!(&output[8..12], &[0, 0, 0, 0x40]);
        assert_eq!(&output[12..16], &[0, 0xff, 0xff, 0x7f]);
    }

    #[test]
    fn encodes_32_bit_integer_pcm_without_f32_precision_loss() {
        let format = PcmFormat {
            sample_rate: 192_000,
            channels: 1,
            valid_bits: 32,
            subslot_bytes: 4,
            little_endian: true,
        };
        let source_codes = [
            i32::MIN,
            -16_777_217,
            -1,
            0,
            1,
            16_777_217,
            i32::MAX,
        ];
        let mut output = Vec::new();
        encode_usb_pcm(&UsbPcmSamples::S32(source_codes.to_vec()), format, &mut output).unwrap();

        let decoded_codes: Vec<i32> = output
            .chunks_exact(4)
            .map(|bytes| i32::from_le_bytes(bytes.try_into().unwrap()))
            .collect();
        assert_eq!(decoded_codes, source_codes);
    }

    #[test]
    fn encodes_symphonia_left_aligned_s32_for_16_24_and_32_bit_pcm() {
        let cases = [
            (16_u8, 2_u8, vec![i32::MIN, -65_536, 0, 65_536, i32::MAX & !0xffff]),
            (24_u8, 3_u8, vec![i32::MIN, -256, 0, 256, i32::MAX & !0xff]),
            (24_u8, 4_u8, vec![i32::MIN, -256, 0, 256, i32::MAX & !0xff]),
            (32_u8, 4_u8, vec![i32::MIN, -1, 0, 1, i32::MAX]),
        ];
        for (valid_bits, subslot_bytes, source) in cases {
            let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits, subslot_bytes, little_endian: true };
            let mut output = Vec::new();
            encode_usb_pcm(&UsbPcmSamples::S32(source.clone()), format, &mut output).unwrap();
            let padding = if subslot_bytes == 4 { 0 } else { 32 - u32::from(valid_bits) };
            let expected: Vec<u8> = source.iter().flat_map(|sample| {
                let aligned = if padding == 0 { *sample } else { *sample >> padding };
                aligned.to_le_bytes()[..subslot_bytes as usize].to_vec()
            }).collect();
            assert_eq!(output, expected, "valid_bits={valid_bits}, subslot_bytes={subslot_bytes}");
        }
    }

    #[test]
    fn integer_encoder_preserves_signed16_and_unsigned8_codes() {
        let mut output = Vec::new();
        let s16 = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        encode_usb_pcm(&UsbPcmSamples::S16(vec![i16::MIN, -1, 0, i16::MAX]), s16, &mut output).unwrap();
        assert_eq!(output, [-32768_i16, -1, 0, 32767].iter().flat_map(|value| value.to_le_bytes()).collect::<Vec<_>>());

        let u8 = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 8, subslot_bytes: 1, little_endian: true };
        encode_usb_pcm(&UsbPcmSamples::U8(vec![0, 1, 127, 255]), u8, &mut output).unwrap();
        assert_eq!(output, vec![0, 1, 127, 255]);
    }

    #[test]
    fn float_encoders_preserve_f32_and_f64_ieee_bit_patterns() {
        let f32_values = [0.0_f32, -0.5, 1.0, f32::from_bits(0x3f80_0001)];
        let f32_format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 32, subslot_bytes: 4, little_endian: true };
        let mut output = Vec::new();
        encode_usb_float_pcm(&UsbPcmSamples::F32(f32_values.to_vec()), f32_format, &mut output).unwrap();
        let expected: Vec<u8> = f32_values.iter().flat_map(|value| value.to_le_bytes()).collect();
        assert_eq!(output, expected);

        let f64_values = [0.0_f64, -0.5, f64::from_bits(0x3ff0_0000_0000_0001), 1.0];
        let f64_format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 64, subslot_bytes: 8, little_endian: true };
        encode_usb_float_pcm(&UsbPcmSamples::F64(f64_values.to_vec()), f64_format, &mut output).unwrap();
        let expected: Vec<u8> = f64_values.iter().flat_map(|value| value.to_le_bytes()).collect();
        assert_eq!(output, expected);
    }

    #[test]
    fn descriptor_format_dispatch_preserves_float_bytes_and_rejects_cross_format() {
        let format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 32, subslot_bytes: 4, little_endian: true };
        let values = [0.0_f32, -0.5, 1.0, f32::from_bits(0x3f80_0001)];
        let samples = UsbPcmSamples::F32(values.to_vec());
        let mut output = Vec::new();
        encode_usb_pcm_for_data_format(&samples, format, 3, &mut output).unwrap();
        let expected: Vec<u8> = values.iter().flat_map(|value| value.to_le_bytes()).collect();
        assert_eq!(output, expected);
        assert!(encode_usb_pcm_for_data_format(&samples, format, 1, &mut output).is_err());
        assert!(encode_usb_pcm_for_data_format(
            &UsbPcmSamples::S32(vec![0, 1, -1, i32::MAX]), format, 3, &mut output
        ).is_err());
    }

    #[test]
    fn pcm8_descriptor_requires_unsigned_8_bit_decoder_samples() {
        let format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 8, subslot_bytes: 1, little_endian: true };
        let mut output = Vec::new();
        encode_usb_pcm_for_data_format(&UsbPcmSamples::U8(vec![0, 128, 255, 1]), format, 2, &mut output).unwrap();
        assert_eq!(output, vec![0, 128, 255, 1]);
        assert!(encode_usb_pcm_for_data_format(&UsbPcmSamples::S16(vec![0, 1]), format, 2, &mut output).is_err());
    }

    #[test]
    fn rejects_unaligned_pcm_sample_count() {
        let format = PcmFormat { sample_rate: 48_000, channels: 2, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        assert!(encode_usb_pcm(&UsbPcmSamples::F64(vec![0.0]), format, &mut Vec::new()).is_err());
    }

}
