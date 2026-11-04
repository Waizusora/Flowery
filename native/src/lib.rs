use std::io::Cursor;
use base64::Engine;

use jni::objects::{JByteArray, JClass, JFloatArray, JString};
use jni::sys::{jbyteArray, jfloatArray, jint, jstring};
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


const ISO_FREQUENCIES: [f32; 31] = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000];
fn apply_peaking_eq(samples: &mut [f32], channels: usize, sample_rate: f32, frequency: f32, gain_db: f32) {
    if gain_db.abs() < 0.001 || frequency >= sample_rate * 0.49 { return; }
    let a = 10.0_f32.powf(gain_db / 40.0);
    let omega = 2.0 * std::f32::consts::PI * frequency / sample_rate;
    let alpha = omega.sin() / (2.0 * 4.318_f32);
    let cos = omega.cos();
    let a0 = 1.0 + alpha / a;
    let b0 = (1.0 + alpha * a) / a0;
    let b1 = (-2.0 * cos) / a0;
    let b2 = (1.0 - alpha * a) / a0;
    let a1 = (-2.0 * cos) / a0;
    let a2 = (1.0 - alpha / a) / a0;
    let mut state = vec![(0.0_f32, 0.0_f32, 0.0_f32, 0.0_f32); channels];
    for (index, sample) in samples.iter_mut().enumerate() {
        let channel = index % channels;
        let (x1, x2, y1, y2) = state[channel];
        let x = *sample;
        let y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
        state[channel] = (x, x1, y, y1);
        *sample = y;
    }
}
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeApplyEqualizer(
    mut env: JNIEnv, _class: JClass, input: JFloatArray, sample_rate: jint, channels: jint, gains: JFloatArray,
) -> jfloatArray {
    if sample_rate <= 0 || channels <= 0 || channels > 8 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid PCM format");
        return std::ptr::null_mut();
    }
    let gain_length = match env.get_array_length(&gains) { Ok(v) => v as usize, Err(_) => return std::ptr::null_mut() };
    if gain_length != ISO_FREQUENCIES.len() {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "The ISO equalizer requires exactly 31 band gains");
        return std::ptr::null_mut();
    }
    let mut band_gains = vec![0.0_f32; gain_length];
    if env.get_float_array_region(&gains, 0, &mut band_gains).is_err() { return std::ptr::null_mut(); }
    let length = match env.get_array_length(&input) { Ok(v) => v as usize, Err(_) => return std::ptr::null_mut() };
    let mut samples = vec![0.0_f32; length];
    if env.get_float_array_region(&input, 0, &mut samples).is_err() { return std::ptr::null_mut(); }
    for (frequency, gain) in ISO_FREQUENCIES.iter().zip(band_gains.iter()) {
        apply_peaking_eq(&mut samples, channels as usize, sample_rate as f32, *frequency, gain.clamp(-12.0, 12.0));
    }
    match env.new_float_array(length as i32) {
        Ok(output) => {
            if env.set_float_array_region(&output, 0, &samples).is_err() { return std::ptr::null_mut(); }
            output.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}


use audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Fft, FixedSync, Resampler};

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeResample(
    mut env: JNIEnv,
    _class: JClass,
    input: JFloatArray,
    input_rate: jint,
    output_rate: jint,
    channels: jint,
) -> jfloatArray {
    if input_rate <= 0 || output_rate <= 0 || channels <= 0 || channels > 2 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid resampler parameters");
        return std::ptr::null_mut();
    }
    let length = match env.get_array_length(&input) { Ok(v) => v as usize, Err(_) => return std::ptr::null_mut() };
    if length == 0 || length % channels as usize != 0 { return std::ptr::null_mut(); }
    let mut source = vec![0.0_f32; length];
    if env.get_float_array_region(&input, 0, &mut source).is_err() { return std::ptr::null_mut(); }
    if input_rate == output_rate {
        return match env.new_float_array(length as i32) {
            Ok(output) => {
                if env.set_float_array_region(&output, 0, &source).is_err() { return std::ptr::null_mut(); }
                output.into_raw()
            }
            Err(_) => std::ptr::null_mut(),
        };
    }
    let channel_count = channels as usize;
    let frames = length / channel_count;
    let mut resampler = match Fft::<f32>::new(input_rate as usize, output_rate as usize, 1024, channel_count, FixedSync::Both) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", format!("Resampler initialization failed: {error}"));
            return std::ptr::null_mut();
        }
    };
    let output_capacity = resampler.process_all_needed_output_len(frames);
    let mut output_samples = vec![0.0_f32; output_capacity * channel_count];
    let input_adapter = match InterleavedSlice::new(&source, channel_count, frames) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut output_adapter = match InterleavedSlice::new_mut(&mut output_samples, channel_count, output_capacity) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let (_, written_frames) = match resampler.process_all_into_buffer(&input_adapter, &mut output_adapter, frames, None) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("Resampling failed: {error}"));
            return std::ptr::null_mut();
        }
    };
    output_samples.truncate(written_frames * channel_count);
    match env.new_float_array(output_samples.len() as i32) {
        Ok(output) => {
            if env.set_float_array_region(&output, 0, &output_samples).is_err() { return std::ptr::null_mut(); }
            output.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

use lofty::config::WriteOptions;
use lofty::file::{AudioFile, TaggedFileExt};
use lofty::tag::{ItemKey, Tag, TagExt};
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static TAG_TEMP_ID: AtomicU64 = AtomicU64::new(0);

fn temporary_audio_path(temp_dir: &str, extension: &str) -> PathBuf {
    let safe_extension = extension.trim_start_matches('.').chars()
        .filter(|c| c.is_ascii_alphanumeric()).collect::<String>();
    let id = TAG_TEMP_ID.fetch_add(1, Ordering::Relaxed);
    PathBuf::from(temp_dir).join(format!("flowery-tag-{}-{}.{}", std::process::id(), id, safe_extension))
}

fn jbytes(env: &mut JNIEnv, input: &JByteArray) -> Result<Vec<u8>, String> {
    let len = env.get_array_length(input).map_err(|e| e.to_string())? as usize;
    let mut signed = vec![0_i8; len];
    env.get_byte_array_region(input, 0, &mut signed).map_err(|e| e.to_string())?;
    Ok(signed.into_iter().map(|v| v as u8).collect())
}

fn java_text(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(|s| s.to_string_lossy().into_owned()).map_err(|e| e.to_string())
}

fn write_bytes_to_java(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    let output = match env.new_byte_array(bytes.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let signed: Vec<i8> = bytes.iter().map(|v| *v as i8).collect();
    if env.set_byte_array_region(&output, 0, &signed).is_err() { return std::ptr::null_mut(); }
    output.into_raw()
}

fn write_text_to_java(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(output) => output.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn with_temp_audio<T>(bytes: &[u8], extension: &str, temp_dir: &str, operation: impl FnOnce(&std::path::Path) -> Result<T, String>) -> Result<T, String> {
    if extension.is_empty() { return Err("The audio file extension is missing".to_string()); }
    let path = temporary_audio_path(temp_dir, extension);
    fs::write(&path, bytes).map_err(|e| format!("Unable to stage audio file: {e}"))?;
    let result = operation(&path);
    let _ = fs::remove_file(&path);
    result
}

fn tag_json(path: &std::path::Path) -> Result<String, String> {
    let tagged = lofty::read_from_path(path).map_err(|e| format!("Lofty could not read this file: {e}"))?;
    let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
    let value = if let Some(tag) = tag {
        let picture = tag.pictures().iter()
            .find(|p| p.pic_type() == lofty::picture::PictureType::CoverFront)
            .or_else(|| tag.pictures().first());
        serde_json::json!({
            "title": tag.get_string(ItemKey::TrackTitle).unwrap_or_default(),
            "artist": tag.get_string(ItemKey::TrackArtist).unwrap_or_default(),
            "album": tag.get_string(ItemKey::AlbumTitle).unwrap_or_default(),
            "albumArtist": tag.get_string(ItemKey::AlbumArtist).unwrap_or_default(),
            "genre": tag.get_string(ItemKey::Genre).unwrap_or_default(),
            "year": tag.get_string(ItemKey::RecordingDate).or_else(|| tag.get_string(ItemKey::Year)).unwrap_or_default(),
            "track": tag.get_string(ItemKey::TrackNumber).unwrap_or_default(),
            "disc": tag.get_string(ItemKey::DiscNumber).unwrap_or_default(),
            "comment": tag.get_string(ItemKey::Comment).unwrap_or_default(),
            "composer": tag.get_string(ItemKey::Composer).unwrap_or_default(),
            "lyrics": tag.get_string(ItemKey::Lyrics).or_else(|| tag.get_string(ItemKey::UnsyncLyrics)).unwrap_or_default(),
            "pictureMime": picture.and_then(|p| p.mime_type()).map(|mime| format!("{mime:?}")).unwrap_or_default(),
            "pictureBase64": picture.map(|p| base64::engine::general_purpose::STANDARD.encode(p.data())).unwrap_or_default(),
            "tagType": format!("{:?}", tag.tag_type()),
            "fileType": format!("{:?}", tagged.file_type()),
            "sampleRate": tagged.properties().sample_rate(),
            "bitDepth": tagged.properties().bit_depth(),
            "channels": tagged.properties().channels(),
            "bitrateKbps": tagged.properties().overall_bitrate(),
            "durationMs": tagged.properties().duration().as_millis(),
            "writable": tagged.tag_support(tagged.primary_tag_type()).is_writable(),
        })
    } else {
        serde_json::json!({
            "title": "", "artist": "", "album": "", "albumArtist": "", "genre": "",
            "year": "", "track": "", "disc": "", "comment": "", "composer": "",
            "lyrics": "", "pictureMime": "", "pictureBase64": "",
            "tagType": "None", "fileType": format!("{:?}", tagged.file_type()),
            "sampleRate": tagged.properties().sample_rate(), "bitDepth": tagged.properties().bit_depth(),
            "channels": tagged.properties().channels(), "bitrateKbps": tagged.properties().overall_bitrate(),
            "durationMs": tagged.properties().duration().as_millis(), "writable": tagged.tag_support(tagged.primary_tag_type()).is_writable()
        })
    };
    serde_json::to_string(&value).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeReadTags(
    mut env: JNIEnv, _class: JClass, input: JByteArray, extension: JString, temp_dir: JString,
) -> jstring {
    let bytes = match jbytes(&mut env, &input) { Ok(v) => v, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); } };
    let ext = match java_text(&mut env, &extension) { Ok(v) => v, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); } };
    let temp_dir = match java_text(&mut env, &temp_dir) { Ok(v) => v, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); } };
    let json = match with_temp_audio(&bytes, &ext, &temp_dir, tag_json) {
        Ok(v) => v,
        Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); }
    };
    write_text_to_java(&mut env, &json)
}

fn set_tag_text(tag: &mut Tag, key: ItemKey, value: String) {
    if value.trim().is_empty() { tag.remove_key(key); } else { tag.insert_text(key, value); }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeWriteTags(
    mut env: JNIEnv, _class: JClass, input: JByteArray, extension: JString,
    temp_dir: JString, title: JString, artist: JString, album: JString, album_artist: JString,
    genre: JString, year: JString, track: JString, disc: JString,
    comment: JString, composer: JString, lyrics: JString,
) -> jbyteArray {
    let bytes = match jbytes(&mut env, &input) { Ok(v) => v, Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); } };
    let values = [&extension, &temp_dir, &title, &artist, &album, &album_artist, &genre, &year, &track, &disc, &comment, &composer, &lyrics];
    let mut strings = Vec::with_capacity(values.len());
    for value in values {
        match java_text(&mut env, value) {
            Ok(v) => strings.push(v),
            Err(e) => { let _ = env.throw_new("java/lang/IllegalArgumentException", e); return std::ptr::null_mut(); }
        }
    }
    let ext = strings.remove(0);
    let temp_dir = strings.remove(0);
    let result = with_temp_audio(&bytes, &ext, &temp_dir, |path| {
        let mut tagged = lofty::read_from_path(path).map_err(|e| format!("This format cannot be edited by Lofty: {e}"))?;
        let tag_type = tagged.primary_tag_type();
        if tagged.primary_tag_mut().is_none() {
            tagged.insert_tag(Tag::new(tag_type));
        }
        if !tagged.tag_support(tag_type).is_writable() {
            return Err("Lofty can read this tag format but cannot write it. The original file was not changed.".to_string());
        }
        let tag = tagged.primary_tag_mut().ok_or("The format does not support a writable primary tag")?;
        let mut values = strings.into_iter();
        set_tag_text(tag, ItemKey::TrackTitle, values.next().unwrap());
        set_tag_text(tag, ItemKey::TrackArtist, values.next().unwrap());
        set_tag_text(tag, ItemKey::AlbumTitle, values.next().unwrap());
        set_tag_text(tag, ItemKey::AlbumArtist, values.next().unwrap());
        set_tag_text(tag, ItemKey::Genre, values.next().unwrap());
        set_tag_text(tag, ItemKey::RecordingDate, values.next().unwrap());
        set_tag_text(tag, ItemKey::TrackNumber, values.next().unwrap());
        set_tag_text(tag, ItemKey::DiscNumber, values.next().unwrap());
        set_tag_text(tag, ItemKey::Comment, values.next().unwrap());
        set_tag_text(tag, ItemKey::Composer, values.next().unwrap());
        let lyric_key = if tag.tag_type() == lofty::tag::TagType::Id3v2 { ItemKey::UnsyncLyrics } else { ItemKey::Lyrics };
        set_tag_text(tag, lyric_key, values.next().unwrap());
        tagged.save_to_path(path, WriteOptions::default())
            .map_err(|e| format!("Lofty could not write tags for this format: {e}"))?;
        fs::read(path).map_err(|e| format!("Unable to read edited audio file: {e}"))
    });
    match result {
        Ok(output) => write_bytes_to_java(&mut env, &output),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}
