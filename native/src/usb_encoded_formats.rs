use symphonia::core::codecs::{CodecType, CODEC_TYPE_AC3};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

pub(super) const UAC1_FORMAT_TYPE_II: u8 = 0x02;
pub(super) const UAC1_FORMAT_TYPE_III: u8 = 0x03;
pub(super) const UAC1_FORMAT_TAG_AC3: u16 = 0x1002;
pub(super) const UAC1_FORMAT_TAG_IEC61937_AC3: u16 = 0x2001;
pub(super) const IEC61937_DATATYPE_AC3: u16 = 0x0001;
pub(super) const IEC61937_AC3_BURST_BYTES: usize = 6144;

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeValidateUsbEncodedDescriptor(
    mut env: JNIEnv, _class: JClass, descriptors: JByteArray,
    interface_number: jint, alternate_setting: jint, format_type: jint, format_tag: jint,
) -> jstring {
    if !(0..=255).contains(&interface_number) || !(0..=255).contains(&alternate_setting) ||
        !(0..=255).contains(&format_type) || !(0..=0xffff).contains(&format_tag) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid encoded UAC1 descriptor selector");
        return std::ptr::null_mut();
    }
    let length = match env.get_array_length(&descriptors) {
        Ok(value) => value as usize,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return std::ptr::null_mut(); }
    };
    let mut signed = vec![0_i8; length];
    if let Err(error) = env.get_byte_array_region(&descriptors, 0, &mut signed) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
        return std::ptr::null_mut();
    }
    let raw: Vec<u8> = signed.into_iter().map(|value| value as u8).collect();
    match validate_uac1_encoded_descriptor(
        &raw, interface_number as u8, alternate_setting as u8,
        format_type as u8, format_tag as u16) {
        Ok((endpoint, max_packet, interval)) => {
            let json = serde_json::json!({
                "valid": true, "endpoint": endpoint,
                "maxPacketBytes": max_packet, "interval": interval,
                "formatType": format_type, "formatTag": format_tag
            });
            match env.new_string(json.to_string()) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
        }
        Err(error) => {
            let json = serde_json::json!({"valid": false, "reason": error});
            match env.new_string(json.to_string()) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
        }
    }
}

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


/// Verifies that a selected UAC1 streaming alternate setting advertises the
/// exact format family/tag and a valid OUT isochronous data endpoint.
pub(super) fn validate_uac1_encoded_descriptor(
    raw: &[u8], interface_number: u8, alternate_setting: u8,
    expected_format_type: u8, expected_format_tag: u16,
) -> Result<(u8, u16, u8), &'static str> {
    if !matches!(expected_format_type, UAC1_FORMAT_TYPE_II | UAC1_FORMAT_TYPE_III) {
        return Err("Only UAC1 Type-II and Type-III encoded descriptors are accepted");
    }
    let mut offset = 0usize;
    let mut selected = false;
    let mut format_tag = None;
    let mut format_type = None;
    let mut endpoint: Option<(u8,u16,u8)> = None;
    while offset + 2 <= raw.len() {
        let length = raw[offset] as usize;
        if length < 2 || offset + length > raw.len() { return Err("Malformed USB descriptor length"); }
        match raw[offset + 1] {
            0x04 if length >= 9 => {
                selected = raw[offset + 2] == interface_number &&
                    raw[offset + 3] == alternate_setting &&
                    raw[offset + 5] == 0x01 && raw[offset + 6] == 0x02 &&
                    raw[offset + 7] == 0x00;
            }
            0x24 if selected && length >= 7 && raw[offset + 2] == 0x01 => {
                format_tag = Some(u16::from_le_bytes([raw[offset + 5], raw[offset + 6]]));
            }
            0x24 if selected && length >= 4 && raw[offset + 2] == 0x02 => {
                let kind = raw[offset + 3];
                if kind == UAC1_FORMAT_TYPE_II {
                    if length < 9 { return Err("UAC1 Type-II format descriptor is truncated"); }
                    let maximum_bitrate = u16::from_le_bytes([raw[offset + 4], raw[offset + 5]]);
                    let samples_per_frame = u16::from_le_bytes([raw[offset + 6], raw[offset + 7]]);
                    if maximum_bitrate == 0 || samples_per_frame == 0 {
                        return Err("UAC1 Type-II descriptor has invalid bitrate or frame size");
                    }
                } else if kind == UAC1_FORMAT_TYPE_III {
                    if length < 8 || raw[offset + 4] != 2 || raw[offset + 5] != 2 || raw[offset + 6] != 16 {
                        return Err("UAC1 Type-III IEC 61937 requires two 16-bit subframes");
                    }
                }
                format_type = Some(kind);
            }
            0x05 if selected && length >= 7 => {
                let address = raw[offset + 2];
                let attributes = raw[offset + 3];
                let packet_raw = u16::from_le_bytes([raw[offset + 4], raw[offset + 5]]);
                let packet_bytes = (packet_raw & 0x07ff) * ((((packet_raw >> 11) & 0x03) + 1) as u16);
                let interval = raw[offset + 6];
                if address & 0x80 == 0 && address & 0x0f != 0 &&
                    attributes & 0x03 == 0x01 && ((attributes >> 4) & 0x03) == 0 &&
                    packet_bytes > 0 && (1..=16).contains(&interval) {
                    endpoint = Some((address, packet_bytes, interval));
                }
            }
            _ => {}
        }
        offset += length;
    }
    if format_type != Some(expected_format_type) {
        return Err("The selected alternate setting does not advertise the requested UAC1 format type");
    }
    if format_tag != Some(expected_format_tag) {
        return Err("The selected alternate setting format tag does not match the requested codec");
    }
    endpoint.ok_or("The selected alternate setting has no valid OUT isochronous data endpoint")
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
