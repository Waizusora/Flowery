use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jintArray, jstring};
use jni::JNIEnv;

const SUPPORTED_AUDIO_EXTENSIONS: &[&str] = &[
    "mp3", "flac", "wav", "wave", "aiff", "aif", "alac", "m4a", "mp4",
    "aac", "ogg", "opus", "oga", "wma", "ape", "wv", "tta", "mka",
    "m4b", "dsf", "dff",
];

fn is_supported_audio_extension(extension: &str) -> bool {
    let normalized = extension.trim().trim_start_matches('.').to_ascii_lowercase();
    SUPPORTED_AUDIO_EXTENSIONS.contains(&normalized.as_str())
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeIsSupportedAudioExtension(
    mut env: JNIEnv, _class: JClass, extension: JString,
) -> jboolean {
    let extension = match env.get_string(&extension) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    if is_supported_audio_extension(&extension) { 1 } else { 0 }
}

#[cfg(test)]
mod tests {
    use super::{is_supported_audio_extension, match_sidecar_lyrics, normalize_playlist_path};

    #[test]
    fn recognizes_supported_formats_case_insensitively() {
        for extension in [
            "mp3", "FLAC", ".Wav", "wave", "aiff", "aif", "alac", "m4a",
            "mp4", "aac", "ogg", "opus", "oga", "wma", "ape", "wv",
            "tta", "mka", "m4b", "dsf", "dff",
        ] {
            assert!(is_supported_audio_extension(extension), "{extension}");
        }
    }

    #[test]
    fn rejects_non_audio_and_empty_extensions() {
        for extension in ["", " ", "lrc", "cue", "m3u", "m3u8", "pls", "txt", "flac/"] {
            assert!(!is_supported_audio_extension(extension), "{extension}");
        }
    }

    #[test]
    fn trims_whitespace_and_a_leading_dot() {
        assert!(is_supported_audio_extension(" .FlAc "));
    }
}


fn normalized_stem(name: &str) -> String {
    let name = name.rsplit_once('/').map(|(_, value)| value).unwrap_or(name);
    name.rsplit_once('.').map(|(stem, _)| stem).unwrap_or(name).trim().to_lowercase()
}

fn match_sidecar_lyrics(audio_names: &[String], lyric_names: &[String]) -> Vec<i32> {
    use std::collections::HashMap;

    let mut lyric_by_stem = HashMap::<String, usize>::new();
    for (index, name) in lyric_names.iter().enumerate() {
        lyric_by_stem.entry(normalized_stem(name)).or_insert(index);
    }
    audio_names.iter()
        .map(|name| lyric_by_stem.get(&normalized_stem(name)).copied().map(|i| i as i32).unwrap_or(-1))
        .collect()
}

fn normalize_playlist_path(raw: &str) -> Option<String> {
    let path = raw.trim().trim_matches('"').replace('\\', "/");
    if path.is_empty()
        || path.starts_with('/')
        || path.starts_with("//")
        || path.starts_with("http://")
        || path.starts_with("https://")
    {
        return None;
    }
    if path.to_ascii_lowercase().starts_with("content://") {
        return Some(path);
    }

    let mut segments: Vec<&str> = Vec::new();
    for segment in path.split('/') {
        match segment.trim() {
            "" | "." => {}
            ".." => {
                segments.pop()?;
            }
            value => segments.push(value),
        }
    }
    if segments.is_empty() {
        None
    } else {
        Some(segments.join("/"))
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeMatchSidecarLyrics(
    mut env: JNIEnv,
    _class: JClass,
    audio_names: JObjectArray,
    lyric_names: JObjectArray,
) -> jintArray {
    let read_names = |env: &mut JNIEnv, values: &JObjectArray| -> Result<Vec<String>, String> {
        let length = env.get_array_length(values).map_err(|error| error.to_string())?;
        let mut names = Vec::with_capacity(length as usize);
        for index in 0..length {
            let value = env.get_object_array_element(values, index).map_err(|error| error.to_string())?;
            let value = JString::from(value);
            let name: String = env.get_string(&value).map_err(|error| error.to_string())?.into();
            names.push(name);
        }
        Ok(names)
    };

    let audio = match read_names(&mut env, &audio_names) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let lyrics = match read_names(&mut env, &lyric_names) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };
    let matches = match_sidecar_lyrics(&audio, &lyrics);
    let output = match env.new_int_array(matches.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_int_array_region(&output, 0, &matches).is_err() {
        return std::ptr::null_mut();
    }
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeNormalizePlaylistPath(
    mut env: JNIEnv,
    _class: JClass,
    input: JString,
) -> jstring {
    let input: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            return std::ptr::null_mut();
        }
    };
    match normalize_playlist_path(&input) {
        Some(value) => env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}



#[cfg(test)]
mod catalog_tests {
    use super::{match_sidecar_lyrics, normalize_playlist_path};

    #[test]
    fn matches_sidecar_lyrics_by_case_insensitive_stem() {
        let audio = vec!["Track.FLAC".to_string(), "live set.mp3".to_string(), "missing.wav".to_string()];
        let lyrics = vec!["track.lrc".to_string(), "LIVE SET.LRC".to_string()];
        assert_eq!(match_sidecar_lyrics(&audio, &lyrics), vec![0, 1, -1]);
    }

    #[test]
    fn keeps_first_duplicate_sidecar_match() {
        let audio = vec!["song.flac".to_string()];
        let lyrics = vec!["song.LRC".to_string(), "SONG.lrc".to_string()];
        assert_eq!(match_sidecar_lyrics(&audio, &lyrics), vec![0]);
    }

    #[test]
    fn normalizes_playlist_paths_without_escaping_root() {
        assert_eq!(normalize_playlist_path(r#"disc\\..\\music\\track.flac"#).as_deref(), Some("music/track.flac"));
        assert_eq!(normalize_playlist_path("./album/track.flac").as_deref(), Some("album/track.flac"));
        assert_eq!(normalize_playlist_path("../../outside.flac"), None);
        assert_eq!(normalize_playlist_path("https://example.com/track.flac"), None);
        assert_eq!(normalize_playlist_path("content://media/item/42").as_deref(), Some("content://media/item/42"));
    }
}
