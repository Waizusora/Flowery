use jni::objects::{JClass, JLongArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use serde::Serialize;

#[derive(Clone, Serialize)]
struct LyricWord {
    #[serde(rename = "timeMs")]
    time_ms: i64,
    text: String,
}

#[derive(Clone, Serialize)]
struct LyricLine {
    #[serde(rename = "timeMs")]
    time_ms: i64,
    text: String,
    words: Vec<LyricWord>,
}

fn parse_timestamp(bytes: &[u8], start: usize, opening: u8, closing: u8) -> Option<(usize, i64)> {
    if bytes.get(start) != Some(&opening) { return None; }
    let mut cursor = start + 1;
    let minute_start = cursor;
    while cursor < bytes.len() && bytes[cursor].is_ascii_digit() && cursor - minute_start < 3 { cursor += 1; }
    if cursor == minute_start || bytes.get(cursor) != Some(&b':') { return None; }
    let minutes = std::str::from_utf8(&bytes[minute_start..cursor]).ok()?.parse::<i64>().ok()?;
    cursor += 1;
    let second_start = cursor;
    while cursor < bytes.len() && bytes[cursor].is_ascii_digit() && cursor - second_start < 2 { cursor += 1; }
    if cursor - second_start != 2 { return None; }
    let seconds = std::str::from_utf8(&bytes[second_start..cursor]).ok()?.parse::<i64>().ok()?;
    if seconds > 59 { return None; }
    let mut milliseconds = 0_i64;
    if bytes.get(cursor) == Some(&b'.') {
        cursor += 1;
        let fraction_start = cursor;
        while cursor < bytes.len() && bytes[cursor].is_ascii_digit() && cursor - fraction_start < 3 { cursor += 1; }
        let digits = cursor - fraction_start;
        if digits == 0 || (cursor < bytes.len() && bytes[cursor].is_ascii_digit()) { return None; }
        let fraction = std::str::from_utf8(&bytes[fraction_start..cursor]).ok()?.parse::<i64>().ok()?;
        milliseconds = match digits { 1 => fraction * 100, 2 => fraction * 10, _ => fraction };
    }
    if bytes.get(cursor) != Some(&closing) { return None; }
    let time_ms = minutes.saturating_mul(60_000)
        .saturating_add(seconds.saturating_mul(1_000)).saturating_add(milliseconds);
    Some((cursor + 1, time_ms))
}

fn parse_line(line: &str, output: &mut Vec<LyricLine>) {
    let bytes = line.as_bytes();
    let mut cursor = 0;
    let mut removed_ranges = Vec::new();
    let mut line_timestamps = Vec::new();
    let mut word_markers: Vec<(usize, usize, i64)> = Vec::new();
    while cursor < bytes.len() {
        let parsed = match bytes[cursor] {
            b'[' => parse_timestamp(bytes, cursor, b'[', b']').map(|(end, time)| {
                removed_ranges.push((cursor, end)); line_timestamps.push(time); end
            }),
            b'<' => parse_timestamp(bytes, cursor, b'<', b'>').map(|(end, time)| {
                removed_ranges.push((cursor, end)); word_markers.push((cursor, end, time)); end
            }),
            _ => None,
        };
        if let Some(end) = parsed { cursor = end; } else { cursor += 1; }
    }
    if line_timestamps.is_empty() {
        if let Some((_, _, first_word_time)) = word_markers.first() { line_timestamps.push(*first_word_time); }
    }
    if line_timestamps.is_empty() { return; }

    removed_ranges.sort_by_key(|range| range.0);
    let mut text = String::with_capacity(line.len());
    let mut copied_until = 0;
    for (start, end) in &removed_ranges {
        text.push_str(&line[copied_until..*start]);
        copied_until = *end;
    }
    text.push_str(&line[copied_until..]);
    let text = text.trim().to_owned();

    let words: Vec<LyricWord> = word_markers.iter().enumerate().map(|(index, (_, marker_end, time_ms))| {
        let segment_end = word_markers.get(index + 1).map(|next| next.0).unwrap_or(line.len());
        LyricWord { time_ms: *time_ms, text: line[*marker_end..segment_end].to_owned() }
    }).collect();

    for time_ms in line_timestamps {
        output.push(LyricLine { time_ms, text: text.clone(), words: words.clone() });
    }
}

fn parse_lrc(content: &str) -> Vec<LyricLine> {
    let offset_ms = content.lines().find_map(|line| {
        let value = line.trim().strip_prefix("[offset:")?.strip_suffix(']')?;
        value.trim().parse::<i64>().ok()
    }).unwrap_or(0);
    let mut lines = Vec::new();
    for line in content.lines() { parse_line(line, &mut lines); }
    for line in &mut lines {
        line.time_ms = line.time_ms.saturating_add(offset_ms).max(0);
        for word in &mut line.words { word.time_ms = word.time_ms.saturating_add(offset_ms).max(0); }
    }
    lines.sort_by_key(|line| line.time_ms);
    lines
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseLrc(
    mut env: JNIEnv, _class: JClass, content: JString,
) -> jstring {
    let content: String = match env.get_string(&content) {
        Ok(value) => value.into(),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return std::ptr::null_mut(); }
    };
    let encoded = match serde_json::to_string(&parse_lrc(&content)) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error.to_string()); return std::ptr::null_mut(); }
    };
    match env.new_string(encoded) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
}

fn parse_plain_lyrics(content: &str) -> Vec<LyricLine> {
    content.lines().map(|line| LyricLine {
        time_ms: 0,
        text: line.trim_end_matches('\r').to_owned(),
        words: Vec::new(),
    }).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParsePlainLyrics(
    mut env: JNIEnv, _class: JClass, content: JString,
) -> jstring {
    let content: String = match env.get_string(&content) {
        Ok(value) => value.into(),
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return std::ptr::null_mut(); }
    };
    let encoded = match serde_json::to_string(&parse_plain_lyrics(&content)) {
        Ok(value) => value,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalStateException", error.to_string()); return std::ptr::null_mut(); }
    };
    match env.new_string(encoded) { Ok(value) => value.into_raw(), Err(_) => std::ptr::null_mut() }
}

/// Finds the final lyric timestamp at or before the requested playback position.
fn current_lyric_index(timestamps: &[i64], position_ms: i64) -> Option<usize> {
    if position_ms < 0 { return None; }
    timestamps.partition_point(|timestamp| *timestamp <= position_ms).checked_sub(1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeFindCurrentLyricIndex(
    mut env: JNIEnv, _class: JClass, timestamps: JLongArray, position_ms: jni::sys::jlong,
) -> jint {
    let length = match env.get_array_length(&timestamps) {
        Ok(value) => value as usize,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return -1; }
    };
    let mut values = vec![0_i64; length];
    if let Err(error) = env.get_long_array_region(&timestamps, 0, &mut values) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return -1;
    }
    current_lyric_index(&values, position_ms).and_then(|index| i32::try_from(index).ok()).unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeFindCurrentLyricWordIndex(
    mut env: JNIEnv, _class: JClass, timestamps: JLongArray, position_ms: jlong,
) -> jint {
    let length = match env.get_array_length(&timestamps) {
        Ok(value) => value as usize,
        Err(error) => { let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return -1; }
    };
    let mut values = vec![0_i64; length];
    if let Err(error) = env.get_long_array_region(&timestamps, 0, &mut values) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string()); return -1;
    }
    current_lyric_index(&values, position_ms)
        .and_then(|index| i32::try_from(index).ok()).unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::{current_lyric_index, parse_lrc, parse_plain_lyrics};

    #[test]
    fn parses_multiple_timestamps_and_sorts_entries() {
        let lines = parse_lrc("[00:02.5][00:01.25]Second\n[00:03.125]Third");
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0].time_ms, 1_250);
        assert_eq!(lines[1].time_ms, 2_500);
        assert_eq!(lines[2].time_ms, 3_125);
        assert_eq!(lines[0].text, "Second");
        assert!(lines[0].words.is_empty());
    }

    #[test]
    fn parses_enhanced_lrc_word_timestamps_without_displaying_tags() {
        let lines = parse_lrc("[00:31.59]<00:31.59>Any <00:31.83>seemin<00:32.22>gly");
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].time_ms, 31_590);
        assert_eq!(lines[0].text, "Any seemingly");
        assert_eq!(lines[0].words.len(), 3);
        assert_eq!(lines[0].words[1].time_ms, 31_830);
        assert_eq!(lines[0].words[1].text, "seemin");
        assert_eq!(lines[0].words[2].text, "gly");
    }

    #[test]
    fn infers_line_start_from_word_only_timestamps() {
        let lines = parse_lrc("<00:01.00>Hello <00:01.50>world");
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].time_ms, 1_000);
        assert_eq!(lines[0].text, "Hello world");
        assert_eq!(lines[0].words.len(), 2);
    }

    #[test]
    fn applies_lrc_offset_to_line_and_word_timestamps() {
        let lines = parse_lrc("[offset:+75]\n[00:01.00]<00:01.00>Hello <00:01.50>world");
        assert_eq!(lines[0].time_ms, 1_075);
        assert_eq!(lines[0].words[0].time_ms, 1_075);
    }

    #[test]
    fn ignores_invalid_timestamps_and_keeps_unmatched_text() {
        let lines = parse_lrc("[00:99]invalid\nplain text\n[1:02.3] Valid ");
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].time_ms, 62_300);
        assert_eq!(lines[0].text, "Valid");
    }

    #[test]
    fn accepts_crlf_and_empty_lyric_text() {
        let lines = parse_lrc("[00:01.00]\r\n[00:02.00]Next\r\n");
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].text, "");
        assert_eq!(lines[1].text, "Next");
    }

    #[test]
    fn plain_lyrics_keep_lines_without_timestamps() {
        let lines = parse_plain_lyrics("Verse one\n\nVerse two\r\n");
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0].text, "Verse one");
        assert_eq!(lines[1].text, "");
        assert_eq!(lines[2].text, "Verse two");
        assert!(lines.iter().all(|line| line.time_ms == 0 && line.words.is_empty()));
    }

    #[test]
    fn finds_current_lyric_with_binary_search_semantics() {
        let timestamps = [1_000, 2_000, 2_000, 4_500];
        assert_eq!(current_lyric_index(&timestamps, -1), None);
        assert_eq!(current_lyric_index(&timestamps, 0), None);
        assert_eq!(current_lyric_index(&timestamps, 1_000), Some(0));
        assert_eq!(current_lyric_index(&timestamps, 2_000), Some(2));
        assert_eq!(current_lyric_index(&timestamps, 4_499), Some(2));
        assert_eq!(current_lyric_index(&timestamps, 4_500), Some(3));
        assert_eq!(current_lyric_index(&timestamps, 9_000), Some(3));
    }
}
