use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::time::Duration;

const LRCLIB_AGENT: &str = "Flowery/0.1.0 (https://github.com/Waizusora/Flowery)";

fn lrclib_request(url: &str) -> Result<String, String> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(8))
        .timeout_read(Duration::from_secs(12))
        .build();
    let response = match agent.get(url).set("User-Agent", LRCLIB_AGENT).call() {
        Ok(response) => response,
        Err(ureq::Error::Status(code, response)) => {
            let retry_after = response.header("Retry-After").unwrap_or("not provided").to_owned();
            let body = response.into_string().unwrap_or_default();
            return Err(format!("LRCLIB returned HTTP {code} (Retry-After: {retry_after}): {body}"));
        }
        Err(error) => return Err(format!("LRCLIB request failed: {error}")),
    };
    response.into_string().map_err(|error| format!("Unable to read LRCLIB response: {error}"))
}

fn throw_error(env: &mut JNIEnv, message: String) -> jstring {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeSearchLyrics(
    mut env: JNIEnv, _class: JClass, query: JString,
) -> jstring {
    let query: String = match env.get_string(&query) {
        Ok(value) => value.into(),
        Err(error) => return throw_error(&mut env, error.to_string()),
    };
    let query = query.trim();
    if query.is_empty() {
        return throw_error(&mut env, "Lyrics search query cannot be empty".to_string());
    }
    let encoded = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("q", query)
        .finish();
    let url = format!("https://lrclib.net/api/search?{encoded}");
    let body = match lrclib_request(&url) {
        Ok(value) => value,
        Err(error) => return throw_error(&mut env, error),
    };
    match env.new_string(body) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_error(&mut env, error.to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeGetLyrics(
    mut env: JNIEnv, _class: JClass, id: jlong,
) -> jstring {
    if id <= 0 {
        return throw_error(&mut env, "Invalid LRCLIB track ID".to_string());
    }
    let url = format!("https://lrclib.net/api/get/{id}");
    let body = match lrclib_request(&url) {
        Ok(value) => value,
        Err(error) => return throw_error(&mut env, error),
    };
    match env.new_string(body) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_error(&mut env, error.to_string()),
    }
}

#[derive(Deserialize)]
struct LyricsFile {
    plain: Option<String>,
    lines: Option<Vec<LyricsFileLine>>,
}

#[derive(Deserialize)]
struct LyricsFileLine {
    text: String,
    #[serde(default)]
    start_ms: i64,
    #[serde(default)]
    words: Vec<LyricsFileWord>,
}

#[derive(Deserialize)]
struct LyricsFileWord {
    text: String,
    #[serde(default)]
    start_ms: i64,
}

#[derive(Serialize)]
struct ParsedWord {
    #[serde(rename = "timeMs")]
    time_ms: i64,
    text: String,
}

#[derive(Serialize)]
struct ParsedLine {
    #[serde(rename = "timeMs")]
    time_ms: i64,
    text: String,
    words: Vec<ParsedWord>,
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseLyricsFile(
    mut env: JNIEnv, _class: JClass, content: JString,
) -> jstring {
    let content: String = match env.get_string(&content) {
        Ok(value) => value.into(),
        Err(error) => return throw_error(&mut env, error.to_string()),
    };
    let document: LyricsFile = match serde_yaml::from_str(&content) {
        Ok(value) => value,
        Err(error) => return throw_error(&mut env, format!("Invalid LRCLIB Lyricsfile YAML: {error}")),
    };
    let mut lines = document.lines.unwrap_or_default().into_iter().map(|line| ParsedLine {
        time_ms: line.start_ms.max(0),
        text: line.text,
        words: line.words.into_iter().map(|word| ParsedWord {
            time_ms: word.start_ms.max(0),
            text: word.text,
        }).collect(),
    }).collect::<Vec<_>>();
    if lines.is_empty() {
        lines = document.plain.unwrap_or_default().lines().map(|line| ParsedLine {
            time_ms: 0,
            text: line.trim_end_matches('\r').to_owned(),
            words: Vec::new(),
        }).collect();
    } else {
        lines.sort_by_key(|line| line.time_ms);
        for line in &mut lines {
            line.words.sort_by_key(|word| word.time_ms);
        }
    }
    let encoded = match serde_json::to_string(&lines) {
        Ok(value) => value,
        Err(error) => return throw_error(&mut env, error.to_string()),
    };
    match env.new_string(encoded) {
        Ok(value) => value.into_raw(),
        Err(error) => throw_error(&mut env, error.to_string()),
    }
}
