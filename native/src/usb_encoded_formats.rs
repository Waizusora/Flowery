use symphonia::core::codecs::{CodecType, CODEC_TYPE_AC3};

pub(super) const UAC1_FORMAT_TYPE_II: u8 = 0x02;
pub(super) const UAC1_FORMAT_TYPE_III: u8 = 0x03;
pub(super) const UAC1_FORMAT_TAG_AC3: u16 = 0x1002;
pub(super) const IEC61937_DATATYPE_AC3: u16 = 0x0001;
pub(super) const IEC61937_AC3_BURST_BYTES: usize = 6144;

/// Keep the passthrough allow-list deliberately narrow. A format-tag match
/// alone is insufficient: the demuxed source codec must match the UAC profile.
pub(super) fn validate_uac1_type_ii(
    format_type: u8,
    format_tag: u16,
    codec: CodecType,
) -> Result<(), &'static str> {
    if format_type != UAC1_FORMAT_TYPE_II {
        return Err("The selected alternate setting is not UAC1 Type-II");
    }
    if format_tag != UAC1_FORMAT_TAG_AC3 {
        return Err("The UAC1 Type-II format tag is not in the supported codec allow-list");
    }
    if codec != CODEC_TYPE_AC3 {
        return Err("The source codec does not match the UAC1 Type-II AC-3 descriptor");
    }
    Ok(())
}

/// Wraps one complete AC-3 sync frame in an IEC 61937 Type-III burst.
/// The carrier is 16-bit stereo (4 bytes per sample period), 1536 periods
/// per AC-3 burst. Payload length in Pd is expressed in bits.
pub(super) fn build_iec61937_ac3_burst(frame: &[u8]) -> Result<Vec<u8>, &'static str> {
    if frame.len() < 7 || frame.len() > (IEC61937_AC3_BURST_BYTES - 8) {
        return Err("AC-3 frame length is outside the IEC 61937 burst envelope");
    }
    if frame[0] != 0x0b || frame[1] != 0x77 {
        return Err("AC-3 frame sync word is missing");
    }
    let payload_bits = frame.len().checked_mul(8)
        .filter(|bits| *bits <= u16::MAX as usize)
        .ok_or("AC-3 payload length cannot be represented by IEC 61937 Pd")? as u16;

    let mut burst = vec![0u8; IEC61937_AC3_BURST_BYTES];
    // IEC 61937 preamble words Pa, Pb, Pc and Pd, serialized little-endian.
    burst[0..2].copy_from_slice(&0xf872u16.to_le_bytes());
    burst[2..4].copy_from_slice(&0x4e1fu16.to_le_bytes());
    burst[4..6].copy_from_slice(&IEC61937_DATATYPE_AC3.to_le_bytes());
    burst[6..8].copy_from_slice(&payload_bits.to_le_bytes());
    burst[8..8 + frame.len()].copy_from_slice(frame);
    Ok(burst)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn type_ii_requires_exact_ac3_codec_and_format_tag() {
        assert!(validate_uac1_type_ii(UAC1_FORMAT_TYPE_II, UAC1_FORMAT_TAG_AC3, CODEC_TYPE_AC3).is_ok());
        assert!(validate_uac1_type_ii(UAC1_FORMAT_TYPE_II, 0x1001, CODEC_TYPE_AC3).is_err());
        assert!(validate_uac1_type_ii(UAC1_FORMAT_TYPE_II, UAC1_FORMAT_TAG_AC3, symphonia::core::codecs::CODEC_TYPE_MP3).is_err());
        assert!(validate_uac1_type_ii(UAC1_FORMAT_TYPE_III, UAC1_FORMAT_TAG_AC3, CODEC_TYPE_AC3).is_err());
    }

    #[test]
    fn ac3_iec61937_burst_has_preamble_length_and_fixed_period() {
        let mut frame = vec![0u8; 128];
        frame[0] = 0x0b;
        frame[1] = 0x77;
        let burst = build_iec61937_ac3_burst(&frame).unwrap();
        assert_eq!(burst.len(), IEC61937_AC3_BURST_BYTES);
        assert_eq!(&burst[0..8], &[0x72,0xf8,0x1f,0x4e,0x01,0x00,0x00,0x04]);
        assert_eq!(&burst[8..136], frame.as_slice());
        assert!(burst[136..].iter().all(|byte| *byte == 0));
    }

    #[test]
    fn rejects_invalid_or_oversized_ac3_frames() {
        assert!(build_iec61937_ac3_burst(&[0u8; 64]).is_err());
        let mut frame = vec![0u8; IEC61937_AC3_BURST_BYTES];
        frame[0] = 0x0b;
        frame[1] = 0x77;
        assert!(build_iec61937_ac3_burst(&frame).is_err());
    }
}
