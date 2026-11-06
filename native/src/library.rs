use jni::objects::{JClass, JString};
use jni::sys::jboolean;
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
    use super::is_supported_audio_extension;

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
