use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct CueTrack {
    pub(super) number: i64,
    pub(super) title: String,
    pub(super) performer: Option<String>,
    pub(super) file_name: String,
    pub(super) start_frame: i64,
    pub(super) end_frame: Option<i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct CueSheet {
    pub(super) title: Option<String>,
    pub(super) performer: Option<String>,
    pub(super) tracks: Vec<CueTrack>,
}

#[derive(Serialize)]
pub(super) struct PlaylistEntry {
    pub(super) path: String,
    pub(super) title: Option<String>,
}

#[derive(Default)]
struct PendingCueTrack {
    number: i64,
    title: Option<String>,
    performer: Option<String>,
    file_name: String,
    start_frame: Option<i64>,
}

fn unquote(value: &str) -> String {
    value.trim().strip_prefix('"').and_then(|v| v.strip_suffix('"'))
        .unwrap_or(value.trim()).to_owned()
}

fn parse_cue_time(value: &str) -> Option<i64> {
    let mut parts = value.split(':');
    let minutes = parts.next()?.parse::<i64>().ok()?;
    let seconds = parts.next()?.parse::<i64>().ok()?;
    let frames = parts.next()?.parse::<i64>().ok()?;
    if parts.next().is_some() || minutes < 0 || !(0..=59).contains(&seconds) || !(0..=74).contains(&frames) {
        return None;
    }
    minutes.checked_mul(60)?.checked_add(seconds)?.checked_mul(75)?.checked_add(frames)
}

pub(super) fn parse_cue(text: &str) -> CueSheet {
    let mut album_title = None;
    let mut album_performer = None;
    let mut current_file = String::new();
    let mut parsed: Vec<PendingCueTrack> = Vec::new();

    for raw in text.lines() {
        let line = raw.trim().trim_start_matches('﻿');
        let (command, value) = line.split_once(char::is_whitespace).unwrap_or((line, ""));
        let value = value.trim_start();
        match command.to_ascii_uppercase().as_str() {
            "TITLE" => {
                let title = unquote(value);
                if parsed.is_empty() {
                    album_title = Some(title);
                } else if let Some(track) = parsed.last_mut() {
                    track.title = Some(title);
                }
            }
            "PERFORMER" => {
                let performer = unquote(value);
                if parsed.is_empty() {
                    album_performer = Some(performer);
                } else if let Some(track) = parsed.last_mut() {
                    track.performer = Some(performer);
                }
            }
            "FILE" => {
                let file_name = value.rsplit_once(' ').map(|(name, _)| name).unwrap_or(value);
                current_file = unquote(file_name);
            }
            "TRACK" => {
                if let Some(number) = value.split_whitespace().next()
                    .and_then(|value| value.parse::<i64>().ok())
                    .filter(|number| (1..=99).contains(number))
                {
                    parsed.push(PendingCueTrack {
                        number,
                        file_name: current_file.clone(),
                        ..PendingCueTrack::default()
                    });
                }
            }
            "INDEX" => {
                let mut fields = value.split_whitespace();
                if fields.next() == Some("01") {
                    if let (Some(time), None, Some(track)) =
                        (fields.next(), fields.next(), parsed.last_mut())
                    {
                        track.start_frame = parse_cue_time(time);
                    }
                }
            }
            _ => {}
        }
    }

    let valid: Vec<CueTrack> = parsed.into_iter().filter_map(|track| {
        let start_frame = track.start_frame?;
        Some(CueTrack {
            number: track.number,
            title: track.title.unwrap_or_else(|| format!("Track {}", track.number)),
            performer: track.performer.or_else(|| album_performer.clone()),
            file_name: track.file_name,
            start_frame,
            end_frame: None,
        })
    }).collect();

    let tracks = valid.iter().enumerate().map(|(index, track)| {
        let next = valid.get(index + 1).filter(|next| next.file_name == track.file_name);
        CueTrack {
            number: track.number,
            title: track.title.clone(),
            performer: track.performer.clone(),
            file_name: track.file_name.clone(),
            start_frame: track.start_frame,
            end_frame: next.map(|next| next.start_frame),
        }
    }).collect();

    CueSheet { title: album_title, performer: album_performer, tracks }
}

pub(super) fn parse_m3u(text: &str) -> Vec<PlaylistEntry> {
    let mut next_title: Option<String> = None;
    let mut entries = Vec::new();
    for raw in text.lines() {
        let line = raw.trim().trim_start_matches('﻿');
        if line.is_empty() {
            continue;
        }
        if line.to_ascii_uppercase().starts_with("#EXTINF:") {
            next_title = line.split_once(',').map(|(_, title)| title.trim().to_owned())
                .filter(|title| !title.is_empty());
        } else if line.starts_with('#') {
            continue;
        } else {
            entries.push(PlaylistEntry { path: line.to_owned(), title: next_title.take() });
        }
    }
    entries
}

pub(super) fn parse_pls(text: &str) -> Vec<PlaylistEntry> {
    let mut paths = BTreeMap::<i64, String>::new();
    let mut titles = BTreeMap::<i64, String>::new();
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with(';') || line.starts_with('[') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        let key = key.trim();
        let value = value.trim().trim_matches('"').to_owned();
        let suffix: String = key.chars().rev().take_while(|c| c.is_ascii_digit()).collect::<String>().chars().rev().collect();
        let Ok(index) = suffix.parse::<i64>() else { continue };
        if key.get(..4).map(|v| v.eq_ignore_ascii_case("File")).unwrap_or(false) {
            paths.insert(index, value);
        } else if key.get(..5).map(|v| v.eq_ignore_ascii_case("Title")).unwrap_or(false) {
            titles.insert(index, value);
        }
    }
    paths.into_iter().map(|(index, path)| PlaylistEntry {
        path,
        title: titles.remove(&index),
    }).collect()
}

fn encode<T: Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string(value).map_err(|error| error.to_string())
}

fn input_text(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(|text| text.into()).map_err(|error| error.to_string())
}

fn return_json(env: &mut JNIEnv, encoded: Result<String, String>) -> jstring {
    match encoded {
        Ok(value) => env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseCue(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let text = match input_text(&mut env, &input) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, Err(error)),
    };
    return_json(&mut env, encode(&parse_cue(&text)))
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParseM3u(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let text = match input_text(&mut env, &input) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, Err(error)),
    };
    return_json(&mut env, encode(&parse_m3u(&text)))
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeParsePls(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let text = match input_text(&mut env, &input) {
        Ok(value) => value,
        Err(error) => return return_json(&mut env, Err(error)),
    };
    return_json(&mut env, encode(&parse_pls(&text)))
}

#[cfg(test)]
mod tests {
    use super::{parse_cue, parse_m3u, parse_pls};

    #[test]
    fn parses_cue_track_ranges_and_album_metadata() {
        let cue = "PERFORMER \"Artist\"\nTITLE \"Album\"\nFILE \"disc one.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"First\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 03:12:37\n";
        let parsed = parse_cue(cue);
        assert_eq!(parsed.title.as_deref(), Some("Album"));
        assert_eq!(parsed.tracks.len(), 2);
        assert_eq!(parsed.tracks[0].title, "First");
        assert_eq!(parsed.tracks[0].performer.as_deref(), Some("Artist"));
        assert_eq!(parsed.tracks[0].end_frame, Some(14_437));
        assert_eq!(parsed.tracks[1].start_frame, 14_437);
    }

    #[test]
    fn parses_cue_directives_with_tabs_and_repeated_whitespace() {
        let cue = "TITLE   \"Album\"\nFILE   \"disc one.flac\"   WAVE\nTRACK   01   AUDIO\nTITLE   \"First track\"\nINDEX   01   00:00:00\nTRACK\t02\tAUDIO\nINDEX\t01\t03:12:37\n";
        let parsed = parse_cue(cue);
        assert_eq!(parsed.title.as_deref(), Some("Album"));
        assert_eq!(parsed.tracks.len(), 2);
        assert_eq!(parsed.tracks[0].file_name, "disc one.flac");
        assert_eq!(parsed.tracks[0].end_frame, Some(14_437));
        assert_eq!(parsed.tracks[1].start_frame, 14_437);
    }

    #[test]
    fn rejects_negative_or_overflowing_cue_timestamps_and_invalid_track_numbers() {
        assert_eq!(super::parse_cue_time("-1:00:00"), None);
        assert_eq!(super::parse_cue_time("9223372036854775807:00:00"), None);
        assert_eq!(super::parse_cue_time("00:60:00"), None);
        let cue = "FILE \"disc.flac\" WAVE\nTRACK 00 AUDIO\nINDEX 01 00:00:00\nTRACK 100 AUDIO\nINDEX 01 00:01:00\nTRACK 01 AUDIO\nINDEX 01 -1:00:00\n";
        assert!(parse_cue(cue).tracks.is_empty());
    }

    #[test]
    fn parses_m3u_titles_and_ignores_comments() {
        let entries = parse_m3u("#EXTM3U\n#EXTINF:123, Track One\na.flac\n#comment\nb.flac\n");
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].title.as_deref(), Some("Track One"));
        assert_eq!(entries[1].title, None);
    }


    #[test]
    fn parses_bom_prefixed_cue_and_unicode_metadata() {
        let cue = r#"﻿TITLE "Álbum"
FILE "disc one.flac" WAVE
TRACK 01 AUDIO
TITLE "Café"
INDEX 01 00:00:00"#;
        let parsed = parse_cue(cue);
        assert_eq!(parsed.title.as_deref(), Some("Álbum"));
        assert_eq!(parsed.tracks[0].title, "Café");
        assert_eq!(parsed.tracks[0].file_name, "disc one.flac");
    }

    #[test]
    fn parses_bom_prefixed_m3u_and_quoted_pls_paths() {
        let m3u = r#"﻿#EXTM3U
#EXTINF:-1,東京
audio.flac"#;
        let m3u = parse_m3u(m3u);
        assert_eq!(m3u.len(), 1);
        assert_eq!(m3u[0].title.as_deref(), Some("東京"));

        let pls = r#"[playlist]
File10="ten.flac"
File2="two.flac"
Title2=Second"#;
        let pls = parse_pls(pls);
        assert_eq!(pls.len(), 2);
        assert_eq!(pls[0].path, "two.flac");
        assert_eq!(pls[0].title.as_deref(), Some("Second"));
        assert_eq!(pls[1].path, "ten.flac");
    }

    #[test]
    fn parses_pls_entries_in_numeric_order() {
        let entries = parse_pls("[playlist]\nFile10=ten.flac\nTitle10=Ten\nFile2=two.flac\nTitle2=Two\n");
        assert_eq!(entries[0].path, "two.flac");
        assert_eq!(entries[1].title.as_deref(), Some("Ten"));
    }
}
