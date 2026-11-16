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

/// Apply Q1.31 gain to signed little-endian integer PCM without float conversion.
/// A unity gain is a strict byte-preserving fast path.
pub(super) fn apply_integer_pcm_gain(
    bytes: &mut [u8],
    valid_bits: u8,
    sample_count: usize,
    gain_q31: i64,
) -> Result<(), &'static str> {
    let sample_bytes = match valid_bits {
        16 => 2,
        24 => 3,
        32 => 4,
        _ => return Err("Unsupported integer PCM gain bit depth"),
    };
    if !(0..=2_147_483_648_i64).contains(&gain_q31) {
        return Err("Q1.31 gain is outside the supported range");
    }
    let required = sample_count.checked_mul(sample_bytes).ok_or("PCM gain buffer length overflow")?;
    if bytes.len() < required {
        return Err("PCM gain buffer is shorter than the requested sample count");
    }
    if gain_q31 == 2_147_483_648_i64 || sample_count == 0 {
        return Ok(());
    }
    let minimum = -(1_i64 << (valid_bits - 1));
    let maximum = (1_i64 << (valid_bits - 1)) - 1;
    for sample in bytes[..required].chunks_exact_mut(sample_bytes) {
        let raw = match valid_bits {
            16 => i16::from_le_bytes([sample[0], sample[1]]) as i64,
            24 => {
                let value = sample[0] as i32
                    | ((sample[1] as i32) << 8)
                    | ((sample[2] as i32) << 16);
                ((value << 8) >> 8) as i64
            }
            32 => i32::from_le_bytes([sample[0], sample[1], sample[2], sample[3]]) as i64,
            _ => unreachable!(),
        };
        let product = raw as i128 * gain_q31 as i128;
        let half = 1_i128 << 30;
        let rounded = if product >= 0 {
            (product + half) >> 31
        } else {
            -(((-product) + half) >> 31)
        };
        let scaled = rounded.clamp(minimum as i128, maximum as i128) as i64;
        match valid_bits {
            16 => sample.copy_from_slice(&(scaled as i16).to_le_bytes()),
            24 => {
                let value = scaled as i32;
                sample[0] = value as u8;
                sample[1] = (value >> 8) as u8;
                sample[2] = (value >> 16) as u8;
            }
            32 => sample.copy_from_slice(&(scaled as i32).to_le_bytes()),
            _ => unreachable!(),
        }
    }
    Ok(())
}

#[cfg(test)]
mod integer_pcm_gain_tests {
    use super::apply_integer_pcm_gain;

    #[test]
    fn unity_gain_preserves_every_pcm_byte_for_16_24_and_32_bit() {
        for (bits, source) in [
            (16, vec![0x00, 0x80, 0xff, 0xff, 0x01, 0x00, 0xff, 0x7f]),
            (24, vec![0x00, 0x00, 0x80, 0xff, 0xff, 0xff, 0x01, 0x00, 0x00]),
            (32, vec![0x00, 0x00, 0x00, 0x80, 0xff, 0xff, 0xff, 0xff]),
        ] {
            let mut actual = source.clone();
            apply_integer_pcm_gain(&mut actual, bits, source.len() / (bits as usize / 8), 1_i64 << 31).unwrap();
            assert_eq!(actual, source, "bit depth {bits}");
        }
    }

    #[test]
    fn half_gain_scales_signed_extremes_without_changing_pcm_width() {
        let mut pcm16 = [i16::MIN, -2, 0, 2, i16::MAX].iter()
            .flat_map(|value| value.to_le_bytes()).collect::<Vec<_>>();
        apply_integer_pcm_gain(&mut pcm16, 16, 5, 1_i64 << 30).unwrap();
        let decoded: Vec<i16> = pcm16.chunks_exact(2)
            .map(|bytes| i16::from_le_bytes([bytes[0], bytes[1]])).collect();
        assert_eq!(decoded, [-16_384, -1, 0, 1, 16_384]);

        let mut pcm24 = [-(1_i32 << 23), -2, 0, 2, (1_i32 << 23) - 1].iter()
            .flat_map(|value| value.to_le_bytes()[..3].to_vec()).collect::<Vec<_>>();
        apply_integer_pcm_gain(&mut pcm24, 24, 5, 1_i64 << 30).unwrap();
        let decoded: Vec<i32> = pcm24.chunks_exact(3).map(|b| {
            let raw = b[0] as i32 | ((b[1] as i32) << 8) | ((b[2] as i32) << 16);
            (raw << 8) >> 8
        }).collect();
        assert_eq!(decoded, [-(1 << 22), -1, 0, 1, (1 << 22)]);

        let mut pcm32 = [i32::MIN, -2, 0, 2, i32::MAX].iter()
            .flat_map(|value| value.to_le_bytes()).collect::<Vec<_>>();
        apply_integer_pcm_gain(&mut pcm32, 32, 5, 1_i64 << 30).unwrap();
        let decoded: Vec<i32> = pcm32.chunks_exact(4)
            .map(|b| i32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
        assert_eq!(decoded, [i32::MIN / 2, -1, 0, 1, (i32::MAX / 2) + 1]);
    }

    #[test]
    fn zero_gain_mutes_signed_pcm16_24_and_32_without_changing_buffer_length() {
        let cases = [
            (16_u8, vec![0x00, 0x80, 0xff, 0x7f]),
            (24_u8, vec![0x00, 0x00, 0x80, 0xff, 0xff, 0x7f]),
            (32_u8, vec![0x00, 0x00, 0x00, 0x80, 0xff, 0xff, 0xff, 0x7f]),
        ];
        for (bits, mut pcm) in cases {
            let original_len = pcm.len();
            let samples = original_len / (bits as usize / 8);
            apply_integer_pcm_gain(&mut pcm, bits, samples, 0).unwrap();
            assert_eq!(pcm, vec![0; original_len], "bit depth {bits}");
            assert_eq!(pcm.len(), original_len, "bit depth {bits}");
        }
    }

    #[test]
    fn rejects_invalid_gain_depth_and_short_buffer() {
        assert!(apply_integer_pcm_gain(&mut [0; 4], 20, 1, 1_i64 << 31).is_err());
        assert!(apply_integer_pcm_gain(&mut [0; 2], 16, 2, 1_i64 << 31).is_err());
        assert!(apply_integer_pcm_gain(&mut [0; 2], 16, 1, -1).is_err());
    }
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
    pub fn from_audio_buffer(decoded: symphonia::core::audio::GenericAudioBufferRef<'_>) -> Result<Self, &'static str> {
        use symphonia::core::audio::{Audio, GenericAudioBufferRef};
        fn interleave<T: Copy>(buffer: &symphonia::core::audio::AudioBuffer<T>) -> Vec<T>
        where T: symphonia::core::audio::sample::Sample {
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
            GenericGenericAudioBufferRef::U8(buffer) => Self::U8(interleave(&buffer)),
            GenericAudioBufferRef::S16(buffer) => Self::S16(interleave(&buffer)),
            GenericAudioBufferRef::S24(buffer) => Self::S24(interleave(&buffer).into_iter().map(|sample| sample.inner()).collect()),
            GenericAudioBufferRef::S32(buffer) => Self::S32(interleave(&buffer)),
            GenericAudioBufferRef::F32(buffer) => Self::F32(interleave(&buffer)),
            GenericAudioBufferRef::F64(buffer) => Self::F64(interleave(&buffer)),
            GenericAudioBufferRef::U16(_) | GenericAudioBufferRef::U24(_) | GenericAudioBufferRef::U32(_)
            | GenericAudioBufferRef::S8(_) => return Err("USB output does not support this Symphonia decoded sample representation yet"),
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


/// Derive the PCM declaration from the decoder's actual output buffer.
/// Container metadata is only a hint: lossy codecs commonly omit sample_format
/// and bit-depth fields even though Symphonia emits valid typed PCM.
pub(super) fn decoded_output_format(
    samples: &UsbPcmSamples,
    declared_bits: u8,
) -> Result<(u8, u8), &'static str> {
    match samples {
        UsbPcmSamples::U8(_) => Ok((8, 2)),
        UsbPcmSamples::S16(_) => Ok((16, 1)),
        UsbPcmSamples::S24(_) => Ok((24, 1)),
        UsbPcmSamples::S32(_) => Ok((
            if matches!(declared_bits, 16 | 20 | 24 | 32) { declared_bits } else { 32 },
            1,
        )),
        UsbPcmSamples::F32(_) => Ok((32, 3)),
        UsbPcmSamples::F64(_) => Ok((64, 4)),
        UsbPcmSamples::Empty => Err("Decoder has not produced a PCM sample buffer"),
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
    use super::{decoded_output_format, encode_usb_float_pcm, encode_usb_pcm, encode_usb_pcm_for_data_format, PcmFormat, UsbPcmSamples};


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
        use symphonia::core::codecs::audio::AudioDecoderOptions;
        use symphonia::core::codecs::registry::CodecRegistry;
        use symphonia::core::formats::probe::Hint;
        use symphonia::core::errors::Error;
        use symphonia::core::formats::{FormatOptions, FormatReader};
        use symphonia::core::io::MediaSourceStream;
        use symphonia::core::meta::MetadataOptions;
        

        let source = MediaSourceStream::new(Box::new(Cursor::new(bytes)), Default::default());
        let mut hint = Hint::new();
        hint.with_extension(extension);
        let probed = symphonia::default::get_probe()
            .format(&hint, source, &FormatOptions::default(), &MetadataOptions::default())
            .expect("probe test audio");
        let mut format = probed.format;
        let track = format.default_track().expect("default test track");
        let mut registry = CodecRegistry::new();
        symphonia::default::register_enabled_codecs(&mut registry);
        let params = track.codec_params.as_ref().audio().expect("audio codec parameters");
        let mut decoder = registry
            .make_audio_decoder(params, &AudioDecoderOptions::default())
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
    fn decoded_output_format_uses_actual_sample_representation_not_container_hints() {
        assert_eq!(decoded_output_format(&UsbPcmSamples::F32(vec![0.25]), 0), Ok((32, 3)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::F32(vec![0.25]), 16), Ok((32, 3)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::S16(vec![1]), 0), Ok((16, 1)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::S24(vec![1]), 0), Ok((24, 1)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::S32(vec![1]), 24), Ok((24, 1)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::S32(vec![1]), 0), Ok((32, 1)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::U8(vec![1]), 0), Ok((8, 2)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::F64(vec![0.25]), 0), Ok((64, 4)));
        assert_eq!(decoded_output_format(&UsbPcmSamples::Empty, 0), Err("Decoder has not produced a PCM sample buffer"));
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

            let frame_bytes = subslot_bytes as usize;
            let schedule = crate::usb_isochronous::build_isochronous_schedule(
                48_000, 1, subslot_bytes, 192, 1,
                crate::usb_isochronous::UsbBusSpeed::Full, 1,
            ).unwrap();
            let packet_bytes = schedule.packets[0].payload_bytes as usize;
            let mut iso_payload = vec![0xff; packet_bytes];
            let copied = crate::usb_isochronous::copy_pcm_into_packet(
                &mut iso_payload, &actual, frame_bytes,
            ).unwrap();
            assert_eq!(copied, expected.len());
            assert_eq!(&iso_payload[..expected.len()], expected.as_slice());
            assert!(iso_payload[expected.len()..].iter().all(|byte| *byte == 0));
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

        // A 4-byte USB subslot carries the same 24 valid bits left-aligned in S32.
        let wide_format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 24, subslot_bytes: 4, little_endian: true };
        let mut wide_actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, wide_format, 1, &mut wide_actual).unwrap();
        let wide_source = [-8_388_608_i32, -1, 0, 1, 8_388_607];
        let wide_expected: Vec<u8> = wide_source.iter().map(|sample| (*sample << 8).to_le_bytes()).flatten().collect();
        assert_eq!(wide_actual, wide_expected);
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

        let wide_format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 24, subslot_bytes: 4, little_endian: true };
        let mut wide_actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, wide_format, 1, &mut wide_actual).unwrap();
        let wide_source = [-8_388_608_i32, -1, 0, 1, 8_388_607];
        let wide_expected: Vec<u8> = wide_source.iter().map(|sample| (sample << 8).to_le_bytes()).flatten().collect();
        assert_eq!(wide_actual, wide_expected);
    }

    #[test]
    fn end_to_end_flac16_decodes_and_preserves_pcm_codes() {
        use base64::Engine;
        const FLAC_16_FIXTURE: &str = "ZkxhQwAAACIQABAAAAAUAAAUC7gA8AAAAAV9hM3Gd/XpiVW1YDHEfdLAAwAAEgAAAAAAAAAAAAAAAAAAAAAABYQAACggAAAAcmVmZXJlbmNlIGxpYkZMQUMgMS41LjAgMjAyNTAyMTEAAAAA//hqCAAEOxiAAP//AAAAAQAQqnA=";
        let flac = base64::engine::general_purpose::STANDARD.decode(FLAC_16_FIXTURE).unwrap();
        let decoded = decode_test_audio(flac, "flac");
        assert_eq!(
            decoded,
            UsbPcmSamples::S32(vec![i32::MIN, -65_536, 0, 65_536, i32::MAX & !0xffff]),
            "FLAC 16-bit must decode as left-aligned S32 samples"
        );
        let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        let mut actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, format, 1, &mut actual).unwrap();
        let source = [-32_768_i16, -1, 0, 1, 32_767];
        let expected: Vec<u8> = source.iter().flat_map(|sample| sample.to_le_bytes()).collect();
        assert_eq!(actual, expected);

        let wide_format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 16, subslot_bytes: 4, little_endian: true };
        let mut wide_actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, wide_format, 1, &mut wide_actual).unwrap();
        let wide_expected: Vec<u8> = [-2_147_483_648_i32, -65_536, 0, 65_536, 2_147_418_112_i32]
            .iter().flat_map(|sample| sample.to_le_bytes()).collect();
        assert_eq!(wide_actual, wide_expected);
    }

    #[test]
    fn end_to_end_alac16_m4a_decodes_and_preserves_pcm_codes() {
        use base64::Engine;
        // Five mono samples: INT16_MIN, -1, 0, 1, INT16_MAX.
        const ALAC_16_FIXTURE: &str = "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAAhmcmVlAAAAGm1kYXQAABIAAAALAAH//gAAAAL//8AAAAKqbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAAEAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAdV0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAAAAAAAAEBAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAABAAAAAAABAAAAAAFNbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAC7gAAAAAVVxAAAAAAALWhkbHIAAAAAAAAAAHNvdW4AAAAAAAAAAAAAAABTb3VuZEhhbmRsZXIAAAAA+G1pbmYAAAAQc21oZAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAAvHN0YmwAAABYc3RzZAAAAAAAAAABAAAASGFsYWMAAAAAAAAAAQAAAAAAAAAAAAEAEAAAAAC7gAAAAAAAJGFsYWMAAAAAAAAQAAAQKAoOAQAAAAAgBAALuAAAALuAAAAAGHN0dHMAAAAAAAAAAQAAAAEAAAAFAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAABIAAAABAAAAFHN0Y28AAAAAAAAAAQAAACwAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYxLjcuMTAz";
        let m4a = base64::engine::general_purpose::STANDARD.decode(ALAC_16_FIXTURE).unwrap();
        let decoded = decode_test_audio(m4a, "m4a");
        assert_eq!(
            decoded,
            UsbPcmSamples::S32(vec![i32::MIN, -65_536, 0, 65_536, i32::MAX & !0xffff]),
            "ALAC 16-bit must decode as left-aligned S32 samples"
        );
        let format = PcmFormat { sample_rate: 48_000, channels: 1, valid_bits: 16, subslot_bytes: 2, little_endian: true };
        let mut actual = Vec::new();
        encode_usb_pcm_for_data_format(&decoded, format, 1, &mut actual).unwrap();
        let source = [-32_768_i16, -1, 0, 1, 32_767];
        let expected: Vec<u8> = source.iter().flat_map(|sample| sample.to_le_bytes()).collect();
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
