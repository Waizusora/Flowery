use std::io::Cursor;

use jni::objects::{JByteArray, JClass, JFloatArray};
use jni::sys::{jbyteArray, jfloatArray, jint};
use jni::JNIEnv;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::errors::Error;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

fn decode_audio(data: &[u8]) -> Result<(u32, usize, Vec<f32>), String> {
    let source = MediaSourceStream::new(Box::new(Cursor::new(data.to_vec())), Default::default());
    let mut hint = Hint::new();
    let mut probed = symphonia::default::get_probe()
        .format(&hint, source, &FormatOptions::default(), &MetadataOptions::default())
        .map_err(|e| format!("Unable to read audio container: {e}"))?;
    let format = &mut probed.format;
    let track = format.default_track().ok_or("No default audio track found")?;
    let track_id = track.id;
    let codec_params = track.codec_params.clone();
    let sample_rate = codec_params.sample_rate.ok_or("Audio sample rate is unavailable")?;
    let channels = codec_params.channels.ok_or("Audio channel layout is unavailable")?.count();
    if channels == 0 || channels > 8 {
        return Err(format!("Unsupported channel count: {channels}"));
    }
    let mut decoder = symphonia::default::get_codecs()
        .make(&codec_params, &DecoderOptions::default())
        .map_err(|e| format!("Unsupported audio codec: {e}"))?;
    let mut samples = Vec::<f32>::new();

    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(Error::IoError(_)) | Err(Error::ResetRequired) => break,
            Err(error) => return Err(format!("Audio packet error: {error}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        match decoder.decode(&packet) {
            Ok(decoded) => {
                let spec = *decoded.spec();
                let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
                buffer.copy_interleaved_ref(decoded);
                samples.extend_from_slice(buffer.samples());
            }
            Err(Error::DecodeError(_)) => continue,
            Err(error) => return Err(format!("Audio decode error: {error}")),
        }
    }
    if samples.is_empty() {
        return Err("The selected file contains no decodable audio samples".to_string());
    }
    Ok((sample_rate, channels, samples))
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeEngineVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let version = env.new_string(format!("Flowery Rust audio engine {}", env!("CARGO_PKG_VERSION")))
        .expect("Unable to allocate engine version string");
    version.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeDecode(
    mut env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jbyteArray {
    let input_len = match env.get_array_length(&input) {
        Ok(value) => value as usize,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut bytes = vec![0_i8; input_len];
    if env.get_byte_array_region(&input, 0, &mut bytes).is_err() {
        return std::ptr::null_mut();
    }
    let unsigned: Vec<u8> = bytes.into_iter().map(|v| v as u8).collect();
    let (sample_rate, channels, samples) = match decode_audio(&unsigned) {
        Ok(decoded) => decoded,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let mut output = Vec::<i8>::with_capacity(8 + samples.len() * 4);
    output.extend_from_slice(&(sample_rate as i32).to_le_bytes().map(|v| v as i8));
    output.extend_from_slice(&(channels as i32).to_le_bytes().map(|v| v as i8));
    for sample in samples {
        output.extend_from_slice(&sample.to_le_bytes().map(|v| v as i8));
    }
    let result = match env.new_byte_array(output.len() as i32) {
        Ok(array) => array,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_byte_array_region(&result, 0, &output).is_err() {
        return std::ptr::null_mut();
    }
    result.into_raw()
}

fn biquad(samples: &mut [f32], channels: usize, sample_rate: f32, frequency: f32, gain_db: f32) {
    if gain_db.abs() < 0.01 || frequency >= sample_rate * 0.49 {
        return;
    }
    let a = 10.0_f32.powf(gain_db / 40.0);
    let omega = 2.0 * std::f32::consts::PI * frequency / sample_rate;
    let alpha = omega.sin() / (2.0 * 0.8);
    let cos = omega.cos();
    let b0 = 1.0 + alpha * a;
    let b1 = -2.0 * cos;
    let b2 = 1.0 - alpha * a;
    let a0 = 1.0 + alpha / a;
    let a1 = -2.0 * cos;
    let a2 = 1.0 - alpha / a;
    let (b0, b1, b2, a1, a2) = (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0);
    let mut state = vec![(0.0_f32, 0.0_f32, 0.0_f32, 0.0_f32); channels];
    for (index, sample) in samples.iter_mut().enumerate() {
        let channel = index % channels;
        let (x1, x2, y1, y2) = state[channel];
        let x = *sample;
        let y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        state[channel] = (x, x1, y, y1);
        *sample = y.clamp(-1.0, 1.0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeApplyEqualizer(
    mut env: JNIEnv,
    _class: JClass,
    input: JFloatArray,
    sample_rate: jint,
    channels: jint,
    bass_db: jni::sys::jfloat,
    mid_db: jni::sys::jfloat,
    treble_db: jni::sys::jfloat,
) -> jfloatArray {
    if sample_rate <= 0 || channels <= 0 || channels > 8 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid PCM format");
        return std::ptr::null_mut();
    }
    let length = match env.get_array_length(&input) {
        Ok(value) => value as usize,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut samples = vec![0.0_f32; length];
    if env.get_float_array_region(&input, 0, &mut samples).is_err() {
        return std::ptr::null_mut();
    }
    let channel_count = channels as usize;
    biquad(&mut samples, channel_count, sample_rate as f32, 100.0, bass_db);
    biquad(&mut samples, channel_count, sample_rate as f32, 1000.0, mid_db);
    biquad(&mut samples, channel_count, sample_rate as f32, 8000.0, treble_db);
    match env.new_float_array(length as i32) {
        Ok(output) => {
            if env.set_float_array_region(&output, 0, &samples).is_err() {
                return std::ptr::null_mut();
            }
            output.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
