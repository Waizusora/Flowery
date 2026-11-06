mod streaming;
mod lyrics;
mod library;
mod queue;
mod playlists;
mod wav_pcm;
mod usb_pcm;
mod usb_descriptors;
#[cfg(target_os = "android")]
mod aaudio;
use base64::Engine;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let version = env.new_string(format!("Flowery Rust audio engine {}", env!("CARGO_PKG_VERSION")))
        .expect("Unable to allocate engine version string");
    version.into_raw()
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

fn descriptor_bytes(env: &mut JNIEnv, input: &JByteArray) -> Result<Vec<u8>, String> {
    let length = env.get_array_length(input).map_err(|e| e.to_string())? as usize;
    let mut signed = vec![0_i8; length];
    env.get_byte_array_region(input, 0, &mut signed).map_err(|e| e.to_string())?;
    Ok(signed.into_iter().map(|value| value as u8).collect())
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseUsbDacProfiles(
    mut env: JNIEnv, _class: JClass, input: JByteArray, requested_bits: jint,
) -> jstring {
    let bytes = match descriptor_bytes(&mut env, &input) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let requested = if requested_bits <= 0 { None } else if requested_bits <= 255 {
        Some(requested_bits as u8)
    } else {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid PCM bit resolution");
        return std::ptr::null_mut();
    };
    match env.new_string(usb_descriptors::profiles_json(&bytes, requested)) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseUsbDacPlaybackClocks(
    mut env: JNIEnv, _class: JClass, input: JByteArray, streaming_interface: jint,
) -> jstring {
    if !(0..=255).contains(&streaming_interface) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid USB interface number");
        return std::ptr::null_mut();
    }
    let bytes = match descriptor_bytes(&mut env, &input) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    match env.new_string(usb_descriptors::clocks_json(&bytes, streaming_interface as u8)) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
