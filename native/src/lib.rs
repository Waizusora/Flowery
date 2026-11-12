mod streaming;
mod lyrics;
mod lyrics_api;
mod library;
mod catalog;
mod queue;
mod playback_controller;
mod playback_session;
mod playlists;
mod pcm_format;
mod playback_policy;
mod usb_audio;
mod usb_uac2;
mod usb_isochronous;
mod usb_feedback;
#[cfg(target_os = "android")]
mod usb_libusb;
#[cfg(target_os = "android")]
mod usb_stream;
#[cfg(target_os = "android")]
mod aaudio;
use base64::Engine;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeOpenUsbAudioSession(
    mut env: JNIEnv, _class: JClass, fd: jint, interface_number: jint, alternate_setting: jint,
) -> jni::sys::jlong {
    if !(0..=255).contains(&interface_number) || !(0..=255).contains(&alternate_setting) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "USB interface and alternate setting must fit in one byte");
        return 0;
    }
    match crate::usb_libusb::open_android_fd(fd, interface_number as u8, alternate_setting as u8) {
        Ok(handle) => handle as jni::sys::jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetUsbBusSpeed(
    _env: JNIEnv, _class: JClass, handle: jni::sys::jlong,
) -> jint {
    crate::usb_libusb::bus_speed(handle)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeCloseUsbAudioSession(
    _env: JNIEnv, _class: JClass, handle: jni::sys::jlong,
) {
    crate::usb_libusb::close_session(handle);
}


#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStartUsbIsochronousPlayback(
    mut env: JNIEnv, _class: JClass,
    session: jni::sys::jlong, decoder: jni::sys::jlong,
    endpoint: jint, raw_max_packet_size: jint, interval: jint,
    sample_rate: jint, channels: jint, valid_bits: jint, subslot_bytes: jint,
    feedback_endpoint: jint, feedback_max_packet_size: jint,
    initial_position_ms: jni::sys::jlong,
) -> jint {
    if session <= 0 || decoder <= 0 || !(0..=255).contains(&endpoint)
        || !(0..=u16::MAX as jint).contains(&raw_max_packet_size)
        || !(1..=16).contains(&interval)
        || sample_rate <= 0 || !(1..=8).contains(&channels)
        || !(1..=32).contains(&valid_bits) || !(1..=4).contains(&subslot_bytes)
        || !((feedback_endpoint == 0 && feedback_max_packet_size == 0)
            || ((0x81..=0x8f).contains(&feedback_endpoint) && (3..=4).contains(&feedback_max_packet_size)))
        || initial_position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid native USB playback parameters");
        return -1;
    }
    match crate::usb_stream::start(
        session, decoder, endpoint as u8, raw_max_packet_size as u16,
        interval as u8, sample_rate as u32,
        channels as u8, valid_bits as u8, subslot_bytes as u8,
        feedback_endpoint as u8, feedback_max_packet_size as u16,
        initial_position_ms as u64,
    ) {
        Ok(()) => 0,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSeekUsbPlayback(
    mut env: JNIEnv, _class: JClass,
    session: jni::sys::jlong, decoder: jni::sys::jlong,
    position_ms: jni::sys::jlong,
) -> jni::sys::jlong {
    if session <= 0 || decoder <= 0 || position_ms < 0 {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid USB seek parameters");
        return -1;
    }
    match crate::usb_stream::seek(session, decoder, position_ms as u64) {
        Ok(position) => position.min(i64::MAX as u64) as jni::sys::jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            -1
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePauseUsbPlayback(
    mut env: JNIEnv, _class: JClass, session: jni::sys::jlong,
) -> jint {
    match crate::usb_stream::set_paused(session, true) {
        Ok(()) => 0,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeResumeUsbPlayback(
    mut env: JNIEnv, _class: JClass, session: jni::sys::jlong,
) -> jint {
    match crate::usb_stream::set_paused(session, false) {
        Ok(()) => 0,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error); -1 }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeStopUsbPlayback(
    _env: JNIEnv, _class: JClass, session: jni::sys::jlong,
) {
    crate::usb_stream::stop_session(session);
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetUsbPlaybackStatus(
    mut env: JNIEnv, _class: JClass, session: jni::sys::jlong,
) -> jstring {
    let (status, error, position_frames, paused) = crate::usb_stream::status(session);
    let payload = serde_json::json!({
        "status": status,
        "error": error,
        "positionFrames": position_frames,
        "paused": paused
    }).to_string();
    write_text_to_java(&mut env, &payload)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeReadUsbPcmChunk(
    mut env: JNIEnv, _class: JClass, decoder_handle: jni::sys::jlong,
    max_frames: jint, valid_bits: jint, subslot_bytes: jint,
) -> jbyteArray {
    if max_frames <= 0 || !(1..=32).contains(&valid_bits) || !(1..=4).contains(&subslot_bytes) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid USB PCM chunk format");
        return std::ptr::null_mut();
    }
    match crate::streaming::read_usb_pcm_chunk(
        decoder_handle, max_frames as usize, valid_bits as u8, subslot_bytes as u8,
    ) {
        Ok(bytes) => write_bytes_to_java(&mut env, &bytes),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
    }
}


#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseUsbAudioDescriptors(
    mut env: JNIEnv,
    _class: JClass,
    descriptors: JByteArray,
) -> jstring {
    let bytes = match jbytes(&mut env, &descriptors) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let parsed = crate::usb_audio::parse_configuration_descriptors(&bytes);
    match serde_json::to_string(&parsed) {
        Ok(json) => write_text_to_java(&mut env, &json),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}


#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativePlanUsbAudioPlayback(
    mut env: JNIEnv,
    _class: JClass,
    descriptors: JByteArray,
    sample_rate: jint,
    channels: jint,
    bit_resolution: jint,
) -> jstring {
    let bytes = match jbytes(&mut env, &descriptors) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let result = if sample_rate <= 0 || channels <= 0 || channels > u8::MAX as jint
        || bit_resolution <= 0 || bit_resolution > u8::MAX as jint
    {
        serde_json::json!({"ok": false, "error": "Invalid PCM parameters"})
    } else {
        match crate::usb_audio::plan_pcm_playback(
            &bytes, sample_rate as u32, channels as u8, bit_resolution as u8
        ) {
            Ok(plan) => {
                let mut plan_value = match serde_json::to_value(&plan) {
                    Ok(value) => value,
                    Err(error) => return write_text_to_java(&mut env, &serde_json::json!({
                        "ok": false, "error": format!("Unable to serialize USB playback plan: {error}")
                    }).to_string()),
                };
                if plan.requires_uac2_clock_negotiation {
                    match crate::usb_uac2::parse_playback_clock_info_for_interface(&bytes, Some(plan.interface_number)) {
                        Ok(clock) if clock.streaming_interface_number == plan.interface_number => {
                            plan_value["uac2_control_interface_number"] = serde_json::json!(clock.control_interface_number);
                            plan_value["uac2_clock_source_entity_id"] = serde_json::json!(clock.clock_source_entity_id);
                            plan_value["uac2_clock_controls"] = serde_json::json!(clock.clock_controls);
                            plan_value["uac2_clock_selector_entity_id"] = serde_json::json!(clock.clock_selector_entity_id);
                            plan_value["uac2_clock_selector_controls"] = serde_json::json!(clock.clock_selector_controls);
                            plan_value["uac2_selector_clock_sources"] = serde_json::json!(clock.selector_clock_sources);
                            plan_value["uac2_selector_source_controls"] = serde_json::json!(clock.selector_source_controls);
                            plan_value["uac2_clock_multiplier_entity_id"] = serde_json::json!(clock.clock_multiplier_entity_id);
                            plan_value["uac2_clock_multiplier_controls"] = serde_json::json!(clock.clock_multiplier_controls);
                            plan_value["uac2_clock_paths"] = serde_json::json!(clock.clock_paths);
                        }
                        Ok(_) => return write_text_to_java(&mut env, &serde_json::json!({
                            "ok": false, "error": "Resolved UAC2 clock path does not belong to the selected playback interface"
                        }).to_string()),
                        Err(error) => return write_text_to_java(&mut env, &serde_json::json!({
                            "ok": false, "error": error
                        }).to_string()),
                    }
                }
                serde_json::json!({"ok": true, "plan": plan_value})
            },
            Err(error) => serde_json::json!({"ok": false, "error": error}),
        }
    };
    match serde_json::to_string(&result) {
        Ok(json) => write_text_to_java(&mut env, &json),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let version = env.new_string(format!("Flowery Rust audio engine {}", env!("CARGO_PKG_VERSION")))
        .expect("Unable to allocate engine version string");
    version.into_raw()
}

use lofty::config::WriteOptions;
use lofty::file::{AudioFile, TaggedFileExt};
use lofty::tag::{ItemKey, Tag};
use std::fs;
use std::os::fd::FromRawFd;
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


fn write_tags_to_path(path: &std::path::Path, values: Vec<String>) -> Result<(), String> {
    let mut tagged = lofty::read_from_path(path)
        .map_err(|e| format!("This format cannot be edited by Lofty: {e}"))?;
    let tag_type = tagged.primary_tag_type();
    if tagged.primary_tag_mut().is_none() {
        tagged.insert_tag(Tag::new(tag_type));
    }
    if !tagged.tag_support(tag_type).is_writable() {
        return Err("Lofty can read this tag format but cannot write it. The original file was not changed.".to_string());
    }
    let tag = tagged.primary_tag_mut().ok_or("The format does not support a writable primary tag")?;
    let mut values = values.into_iter();
    set_tag_text(tag, ItemKey::TrackTitle, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::TrackArtist, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::AlbumTitle, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::AlbumArtist, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::Genre, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::RecordingDate, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::TrackNumber, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::DiscNumber, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::Comment, values.next().unwrap_or_default());
    set_tag_text(tag, ItemKey::Composer, values.next().unwrap_or_default());
    let lyric_key = if tag.tag_type() == lofty::tag::TagType::Id3v2 { ItemKey::UnsyncLyrics } else { ItemKey::Lyrics };
    set_tag_text(tag, lyric_key, values.next().unwrap_or_default());
    let mut output = fs::File::create(path)
        .map_err(|e| format!("Unable to open staged audio file for tag writing: {e}"))?;
    tagged.save_to(&mut output, WriteOptions::default())
        .map_err(|e| format!("Lofty could not write tags for this format: {e}"))?;
    Ok(())
}

fn copy_descriptor_to_temp(fd: i32, extension: &str, temp_dir: &str) -> Result<PathBuf, String> {
    if fd < 0 { return Err("Invalid audio document descriptor".to_string()); }
    let duplicate = unsafe { libc::dup(fd) };
    if duplicate < 0 { return Err(format!("Unable to duplicate audio descriptor: {}", std::io::Error::last_os_error())); }
    let mut source = unsafe { std::fs::File::from_raw_fd(duplicate) };
    let path = temporary_audio_path(temp_dir, extension);
    let mut destination = fs::File::create(&path).map_err(|e| format!("Unable to stage audio file: {e}"))?;
    std::io::copy(&mut source, &mut destination).map_err(|e| format!("Unable to copy audio document: {e}"))?;
    Ok(path)
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
        write_tags_to_path(path, strings)?;
        fs::read(path).map_err(|e| format!("Unable to read edited audio file: {e}"))
    });
    match result {
        Ok(output) => write_bytes_to_java(&mut env, &output),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}



#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeReadTagsFromFd(
    mut env: JNIEnv, _class: JClass, file_descriptor: jint, extension: JString, temp_dir: JString,
) -> jstring {
    let ext = match java_text(&mut env, &extension) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); return std::ptr::null_mut(); }
    };
    let temp_dir = match java_text(&mut env, &temp_dir) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); return std::ptr::null_mut(); }
    };
    let path = match copy_descriptor_to_temp(file_descriptor, &ext, &temp_dir) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); return std::ptr::null_mut(); }
    };
    let result = tag_json(&path);
    let _ = fs::remove_file(&path);
    match result {
        Ok(value) => write_text_to_java(&mut env, &value),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeWriteTagsFromFd(
    mut env: JNIEnv, _class: JClass, file_descriptor: jint, extension: JString, temp_dir: JString,
    title: JString, artist: JString, album: JString, album_artist: JString, genre: JString,
    year: JString, track: JString, disc: JString, comment: JString, composer: JString, lyrics: JString,
) -> jstring {
    let inputs = [&extension, &temp_dir, &title, &artist, &album, &album_artist, &genre,
        &year, &track, &disc, &comment, &composer, &lyrics];
    let mut values = Vec::with_capacity(inputs.len());
    for input in inputs {
        match java_text(&mut env, input) {
            Ok(value) => values.push(value),
            Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); return std::ptr::null_mut(); }
        }
    }
    let ext = values.remove(0);
    let temp_dir = values.remove(0);
    let path = match copy_descriptor_to_temp(file_descriptor, &ext, &temp_dir) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error); return std::ptr::null_mut(); }
    };
    match write_tags_to_path(&path, values) {
        Ok(()) => write_text_to_java(&mut env, &path.to_string_lossy()),
        Err(error) => {
            let _ = fs::remove_file(&path);
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            std::ptr::null_mut()
        }
    }
}
