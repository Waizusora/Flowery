use symphonia::core::codecs::{CodecType, CODEC_TYPE_EAC3};
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
    interface_number: jint, alternate_setting: jint, format_type: jint, format_tag: jint, sample_rate: jint,
) -> jstring {
    if !(0..=255).contains(&interface_number) || !(0..=255).contains(&alternate_setting) ||
        !(0..=255).contains(&format_type) || !(0..=0xffff).contains(&format_tag) || sample_rate <= 0 {
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
        format_type as u8, format_tag as u16, sample_rate as u32) {
        Ok((endpoint, max_packet, interval, maximum_bitrate, sampling_frequency_control)) => {
            let json = serde_json::json!({
                "valid": true, "endpoint": endpoint,
                "maxPacketBytes": max_packet, "interval": interval,
                "maximumBitrate": maximum_bitrate,
                "samplingFrequencyControl": sampling_frequency_control,
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
    if codec != CODEC_TYPE_EAC3 {
        return Err("The source codec does not match the UAC1 Type-II AC-3 descriptor");
    }
    Ok(())
}

/// Returns the AC-3 frame size from fscod/frmsizecod. Unsupported
/// reserved header values are rejected instead of being passed to the DAC.
pub(super) fn ac3_frame_size(frame: &[u8]) -> Result<usize, &'static str> {
    if frame.len() < 7 || frame[0] != 0x0b || frame[1] != 0x77 {
        return Err("AC-3 sync word or minimum header is invalid");
    }
    let fscod = frame[4] >> 6;
    let frame_size_code = frame[4] & 0x3f;
    let bitstream_id = frame[5] >> 3;
    if bitstream_id > 10 {
        return Err("Enhanced AC-3 or a reserved bitstream identifier is not accepted as AC-3");
    }
    if fscod == 3 || frame_size_code > 37 {
        return Err("AC-3 frame header contains a reserved sample-rate or frame-size code");
    }
    const WORDS_48K: [usize; 19] = [64,80,96,112,128,160,192,224,256,320,384,448,512,640,768,896,1024,1152,1280];
    const WORDS_44K: [usize; 19] = [69,87,104,121,139,174,208,243,278,348,417,487,557,696,835,975,1114,1253,1393];
    const WORDS_32K: [usize; 19] = [96,120,144,168,192,240,288,336,384,480,576,672,768,960,1152,1344,1536,1728,1920];
    let index = (frame_size_code / 2) as usize;
    let words = match fscod {
        0 => WORDS_48K[index],
        1 => WORDS_44K[index] + (frame_size_code as usize & 1),
        2 => WORDS_32K[index],
        _ => unreachable!(),
    };
    Ok(words * 2)
}

pub(super) fn ac3_frame_bitrate_kbps(frame: &[u8]) -> Result<u16, &'static str> {
    if frame.len() < 5 || frame[0] != 0x0b || frame[1] != 0x77 {
        return Err("AC-3 sync word or frame header is invalid");
    }
    const BITRATES_KBPS: [u16; 19] = [
        32, 40, 48, 56, 64, 80, 96, 112, 128, 160,
        192, 224, 256, 320, 384, 448, 512, 576, 640,
    ];
    let code = frame[4] & 0x3f;
    if code > 37 { return Err("AC-3 frame-size code is reserved"); }
    Ok(BITRATES_KBPS[(code / 2) as usize])
}

pub(super) fn ac3_frame_sample_rate(frame: &[u8]) -> Result<u32, &'static str> {
    if frame.len() < 5 || frame[0] != 0x0b || frame[1] != 0x77 {
        return Err("AC-3 sync word or frame header is invalid");
    }
    match frame[4] >> 6 {
        0 => Ok(48_000),
        1 => Ok(44_100),
        2 => Ok(32_000),
        _ => Err("AC-3 frame uses a reserved sample-rate code"),
    }
}

/// Wraps one complete AC-3 sync frame in an IEC 61937 Type-III burst.
/// The carrier is 16-bit stereo (4 bytes per sample period), 1536 periods
/// per AC-3 burst. Payload length in Pd is expressed in bits.
pub(super) fn build_iec61937_ac3_burst(frame: &[u8]) -> Result<Vec<u8>, &'static str> {
    let expected_frame_bytes = ac3_frame_size(frame)?;
    if frame.len() != expected_frame_bytes || frame.len() > (IEC61937_AC3_BURST_BYTES - 8) {
        return Err("Demuxed AC-3 packet is not exactly one complete frame or exceeds the IEC 61937 burst envelope");
    }
    let payload_bits = frame.len().checked_mul(8)
        .filter(|bits| *bits <= u16::MAX as usize)
        .ok_or("AC-3 payload length cannot be represented by IEC 61937 Pd")? as u16;

    let mut burst = vec![0u8; IEC61937_AC3_BURST_BYTES];
    // IEC 61937 preamble words Pa, Pb, Pc and Pd, serialized little-endian.
    burst[0..2].copy_from_slice(&0xf872u16.to_le_bytes());
    burst[2..4].copy_from_slice(&0x4e1fu16.to_le_bytes());
    // Pc carries the AC-3 bitstream mode in bits 8..10.
    let bitstream_mode = (frame[5] & 0x07) as u16;
    let data_type = IEC61937_DATATYPE_AC3 | (bitstream_mode << 8);
    burst[4..6].copy_from_slice(&data_type.to_le_bytes());
    burst[6..8].copy_from_slice(&payload_bits.to_le_bytes());
    // IEC 61937 little-endian output stores payload as little-endian 16-bit
    // words; AC-3 elementary-stream bytes therefore swap within each word.
    for (source, target) in frame.chunks_exact(2).zip(burst[8..8 + frame.len()].chunks_exact_mut(2)) {
        target[0] = source[1];
        target[1] = source[0];
    }
    Ok(burst)
}


/// Verifies that a selected UAC1 streaming alternate setting advertises the
/// exact format family/tag and a valid OUT isochronous data endpoint.
pub(super) fn validate_uac1_encoded_descriptor(
    raw: &[u8], interface_number: u8, alternate_setting: u8,
    expected_format_type: u8, expected_format_tag: u16, sample_rate: u32,
) -> Result<(u8, u16, u8, u16, bool), &'static str> {
    if !matches!(expected_format_type, UAC1_FORMAT_TYPE_II | UAC1_FORMAT_TYPE_III) {
        return Err("Only UAC1 Type-II and Type-III encoded descriptors are accepted");
    }
    let mut offset = 0usize;
    let mut selected = false;
    let mut format_tag = None;
    let mut format_type = None;
    let mut endpoint: Option<(u8,u16,u8)> = None;
    let mut maximum_bitrate = 0u16;
    let mut sample_rate_supported = false;
    let mut sampling_frequency_control = false;
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
                let (frequency_count, frequency_offset) = if kind == UAC1_FORMAT_TYPE_II {
                    if length < 9 { return Err("UAC1 Type-II format descriptor is truncated"); }
                    maximum_bitrate = u16::from_le_bytes([raw[offset + 4], raw[offset + 5]]);
                    let samples_per_frame = u16::from_le_bytes([raw[offset + 6], raw[offset + 7]]);
                    if maximum_bitrate == 0 || samples_per_frame != 1536 {
                        return Err("AC-3 Type-II descriptor must advertise a nonzero bitrate and 1536 samples per encoded frame");
                    }
                    (raw[offset + 8] as usize, 9usize)
                } else if kind == UAC1_FORMAT_TYPE_III {
                    if length < 8 || raw[offset + 4] != 2 || raw[offset + 5] != 2 || raw[offset + 6] != 16 {
                        return Err("UAC1 Type-III IEC 61937 requires two 16-bit subframes");
                    }
                    (raw[offset + 7] as usize, 8usize)
                } else {
                    return Err("Unsupported UAC1 encoded format type");
                };
                if frequency_count == 0 {
                    if length < frequency_offset + 6 { return Err("UAC1 continuous sample-rate range is truncated"); }
                    let minimum = raw[offset + frequency_offset] as u32 |
                        ((raw[offset + frequency_offset + 1] as u32) << 8) |
                        ((raw[offset + frequency_offset + 2] as u32) << 16);
                    let maximum = raw[offset + frequency_offset + 3] as u32 |
                        ((raw[offset + frequency_offset + 4] as u32) << 8) |
                        ((raw[offset + frequency_offset + 5] as u32) << 16);
                    sample_rate_supported = minimum > 0 && maximum >= minimum &&
                        sample_rate >= minimum && sample_rate <= maximum;
                } else {
                    let available = length.saturating_sub(frequency_offset) / 3;
                    if frequency_count > available { return Err("UAC1 discrete sample-rate list is truncated"); }
                    for index in 0..frequency_count {
                        let at = offset + frequency_offset + index * 3;
                        let rate = raw[at] as u32 | ((raw[at + 1] as u32) << 8) | ((raw[at + 2] as u32) << 16);
                        if rate == sample_rate { sample_rate_supported = true; }
                    }
                }
                format_type = Some(kind);
            }
            0x25 if selected && length >= 4 && raw[offset + 2] == 0x01 => {
                sampling_frequency_control = raw[offset + 3] & 0x01 != 0;
            }
            0x05 if selected && length >= 7 => {
                let address = raw[offset + 2];
                let attributes = raw[offset + 3];
                let packet_raw = u16::from_le_bytes([raw[offset + 4], raw[offset + 5]]);
                let transactions = (packet_raw >> 11) & 0x03;
                let packet_bytes = (packet_raw & 0x07ff) * ((transactions + 1) as u16);
                let interval = raw[offset + 6];
                let synchronization_type = (attributes >> 2) & 0x03;
                if transactions == 3 { return Err("Encoded endpoint uses a reserved high-bandwidth transaction count"); }
                if synchronization_type == 1 {
                    return Err("Asynchronous encoded endpoints are not enabled without a verified feedback scheduler");
                }
                if address & 0x80 == 0 && address & 0x0f != 0 &&
                    attributes & 0x03 == 0x01 && ((attributes >> 4) & 0x03) == 0 &&
                    (synchronization_type == 2 || synchronization_type == 3) &&
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
    if !sample_rate_supported {
        return Err("The selected source sample rate is not advertised by this encoded UAC1 alternate setting");
    }
    let (address, packet, interval) = endpoint.ok_or("The selected alternate setting has no valid OUT isochronous data endpoint")?;
    Ok((address, packet, interval, maximum_bitrate, sampling_frequency_control))
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

    fn uac1_type_ii_descriptor() -> Vec<u8> {
        vec![
            9, 0x04, 1, 1, 0, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 0x02, 0x10,
            12, 0x24, 2, 2, 0x80, 0x02, 0x00, 0x06, 1, 0x80, 0xbb, 0x00,
            7, 0x05, 0x01, 0x09, 0xc0, 0x00, 1,
            7, 0x25, 1, 1, 0, 0, 0,
        ]
    }

    fn uac1_type_iii_ac3_descriptor() -> Vec<u8> {
        vec![
            9, 0x04, 2, 1, 0, 1, 2, 0, 0,
            7, 0x24, 1, 1, 1, 0x01, 0x20,
            11, 0x24, 2, 3, 2, 2, 16, 1, 0x80, 0xbb, 0x00,
            7, 0x05, 0x02, 0x09, 0xc0, 0x00, 1,
            7, 0x25, 1, 1, 0, 0, 0,
        ]
    }

    #[test]
    fn validates_exact_uac1_type_ii_ac3_descriptor_and_rate() {
        let descriptor = uac1_type_ii_descriptor();
        assert_eq!(
            validate_uac1_encoded_descriptor(&descriptor, 1, 1, UAC1_FORMAT_TYPE_II, UAC1_FORMAT_TAG_AC3, 48_000),
            Ok((1, 192, 1, 640, true))
        );
        assert!(validate_uac1_encoded_descriptor(&descriptor, 1, 1, UAC1_FORMAT_TYPE_II, UAC1_FORMAT_TAG_AC3, 44_100).is_err());
        assert!(validate_uac1_encoded_descriptor(&descriptor, 1, 1, UAC1_FORMAT_TYPE_II, 0x1001, 48_000).is_err());
    }

    #[test]
    fn validates_uac1_type_iii_ac3_subframes_and_sample_rate() {
        let descriptor = uac1_type_iii_ac3_descriptor();
        assert_eq!(
            validate_uac1_encoded_descriptor(&descriptor, 2, 1, UAC1_FORMAT_TYPE_III, UAC1_FORMAT_TAG_IEC61937_AC3, 48_000),
            Ok((2, 192, 1, 0, true))
        );
        let mut invalid = descriptor;
        invalid[20] = 3; // Type-III channel count must be exactly two.
        assert!(validate_uac1_encoded_descriptor(&invalid, 2, 1, UAC1_FORMAT_TYPE_III, UAC1_FORMAT_TAG_IEC61937_AC3, 48_000).is_err());
    }

    #[test]
    fn ac3_iec61937_burst_has_preamble_length_and_fixed_period() {
        let mut frame = vec![0u8; 128];
        frame[0] = 0x0b;
        frame[1] = 0x77;
        frame[5] = 0x05; // bsmod=5
        let burst = build_iec61937_ac3_burst(&frame).unwrap();
        assert_eq!(burst.len(), IEC61937_AC3_BURST_BYTES);
        assert_eq!(&burst[0..8], &[0x72,0xf8,0x1f,0x4e,0x01,0x05,0x00,0x04]);
        assert_eq!(&burst[8..12], &[frame[1], frame[0], frame[3], frame[2]]);
        assert!(burst[8 + frame.len()..].iter().all(|byte| *byte == 0));
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
