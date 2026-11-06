use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use crate::library::{is_supported_audio_extension, normalize_playlist_path, normalized_stem};
use crate::playlists::{parse_cue, parse_m3u, parse_pls};

#[derive(Deserialize)]
struct Snapshot { roots: Vec<Root> }
#[derive(Deserialize)]
struct Root { name: String, entries: Vec<Entry> }
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    path: String, name: String, uri: String, mime_type: Option<String>,
    directory: bool, text: Option<String>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Track {
    uri: String, title: String, folder: String, mime_type: Option<String>,
    sidecar_lyrics_uri: Option<String>, cue_start_ms: Option<i64>, cue_end_ms: Option<i64>,
    cue_playlist: Option<String>, playlist_name: Option<String>, playlist_order: Option<usize>,
}
#[derive(Serialize)]
struct Catalog { tracks: Vec<Track> }

fn key(path: &str) -> Option<String> {
    let normalized = path.replace('\\', "/");
    let mut parts: Vec<&str> = Vec::new();
    for part in normalized.split('/') {
        match part.trim() {
            "" | "." => {},
            ".." => { if parts.pop().is_none() { return None; } },
            value => parts.push(value),
        }
    }
    if parts.is_empty() { None } else { Some(parts.join("/").to_lowercase()) }
}
fn parent(path: &str) -> &str { path.rsplit_once('/').map(|(p, _)| p).unwrap_or("") }
fn ext(name: &str) -> &str { name.rsplit_once('.').map(|(_, e)| e).unwrap_or("") }

fn resolve<'a>(raw: &str, base: &str, paths: &'a HashMap<String, &'a Entry>,
    uris: &'a HashMap<String, &'a Entry>) -> Option<&'a Entry> {
    let path = normalize_playlist_path(raw)?;
    if path.to_ascii_lowercase().starts_with("content://") { return uris.get(&path).copied(); }
    let full = if base.is_empty() { path } else { format!("{base}/{path}") };
    paths.get(&key(&full)?).copied()
}

fn build_root(root: &Root, tracks: &mut Vec<Track>, seen: &mut HashSet<String>) {
    let mut paths = HashMap::new();
    let mut uris = HashMap::new();
    for entry in &root.entries {
        if entry.directory { continue; }
        if let Some(k) = key(&entry.path) { paths.entry(k).or_insert(entry); }
        uris.entry(entry.uri.clone()).or_insert(entry);
    }
    let mut audio: Vec<&Entry> = root.entries.iter()
        .filter(|e| !e.directory && is_supported_audio_extension(ext(&e.name))).collect();
    audio.sort_by_key(|e| e.path.to_lowercase());
    let lyrics: Vec<&Entry> = root.entries.iter()
        .filter(|e| !e.directory && e.name.to_ascii_lowercase().ends_with(".lrc")).collect();
    let mut sidecars = HashMap::new();
    for item in &audio {
        if let Some(lyric) = lyrics.iter().find(|l|
            parent(&l.path).eq_ignore_ascii_case(parent(&item.path))
            && normalized_stem(&l.name) == normalized_stem(&item.name)) {
            sidecars.insert(item.uri.clone(), lyric.uri.clone());
        }
    }
    for item in &audio {
        if seen.insert(item.uri.clone()) {
            tracks.push(Track {
                uri: item.uri.clone(),
                title: item.name.rsplit_once('.').map(|(s, _)| s).unwrap_or(&item.name).to_owned(),
                folder: root.name.clone(), mime_type: item.mime_type.clone(),
                sidecar_lyrics_uri: sidecars.get(&item.uri).cloned(),
                cue_start_ms: None, cue_end_ms: None, cue_playlist: None,
                playlist_name: None, playlist_order: None,
            });
        }
    }
    let mut sources: Vec<&Entry> = root.entries.iter()
        .filter(|e| !e.directory && e.text.is_some()).collect();
    sources.sort_by_key(|e| e.path.to_lowercase());
    for source in sources {
        let extension = ext(&source.name).to_ascii_lowercase();
        let label = format!("{}/{}", root.name, source.path);
        let base = parent(&source.path);
        if extension == "cue" {
            for cue in parse_cue(source.text.as_deref().unwrap_or_default()).tracks {
                let Some(item) = resolve(&cue.file_name, base, &paths, &uris) else { continue };
                if item.directory || !is_supported_audio_extension(ext(&item.name)) { continue; }
                let identity = format!("{}#cue:{}:{}", item.uri, label, cue.number);
                if !seen.insert(identity) { continue; }
                tracks.push(Track {
                    uri: item.uri.clone(), title: cue.title, folder: root.name.clone(),
                    mime_type: item.mime_type.clone(), sidecar_lyrics_uri: sidecars.get(&item.uri).cloned(),
                    cue_start_ms: Some(cue.start_frame.saturating_mul(1000) / 75),
                    cue_end_ms: cue.end_frame.map(|v| v.saturating_mul(1000) / 75),
                    cue_playlist: Some(label.clone()), playlist_name: None, playlist_order: None,
                });
            }
        } else if ["m3u", "m3u8", "pls"].contains(&extension.as_str()) {
            let entries = if extension == "pls" { parse_pls(source.text.as_deref().unwrap_or_default()) }
                else { parse_m3u(source.text.as_deref().unwrap_or_default()) };
            for (index, entry) in entries.into_iter().enumerate() {
                let Some(item) = resolve(&entry.path, base, &paths, &uris) else { continue };
                if item.directory || !is_supported_audio_extension(ext(&item.name)) { continue; }
                let identity = format!("{}#playlist:{}:{}", item.uri, label, index);
                if !seen.insert(identity) { continue; }
                tracks.push(Track {
                    uri: item.uri.clone(),
                    title: entry.title.filter(|v| !v.trim().is_empty()).unwrap_or_else(||
                        item.name.rsplit_once('.').map(|(s, _)| s).unwrap_or(&item.name).to_owned()),
                    folder: root.name.clone(), mime_type: item.mime_type.clone(),
                    sidecar_lyrics_uri: sidecars.get(&item.uri).cloned(), cue_start_ms: None,
                    cue_end_ms: None, cue_playlist: None, playlist_name: Some(label.clone()),
                    playlist_order: Some(index),
                });
            }
        }
    }
}

fn build(snapshot: Snapshot) -> Catalog {
    let mut tracks = Vec::new();
    let mut seen = HashSet::new();
    for root in &snapshot.roots { build_root(root, &mut tracks, &mut seen); }
    Catalog { tracks }
}

#[no_mangle]
pub extern "system" fn Java_com_waizusora_flowery_NativeAudio_nativeBuildLibraryCatalog(
    mut env: JNIEnv, _class: JClass, input: JString,
) -> jstring {
    let input: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            return std::ptr::null_mut();
        }
    };
    let result = match serde_json::from_str::<Snapshot>(&input) {
        Ok(snapshot) => serde_json::to_string(&build(snapshot)).map_err(|error| error.to_string()),
        Err(error) => Err(error.to_string()),
    };
    match result {
        Ok(value) => env.new_string(value).map(|value| value.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{build, Snapshot};
    #[test]
    fn matches_sidecars_by_directory_and_builds_playlist_and_cue_tracks() {
        let input = r##"{"roots":[{"name":"Music","entries":[
            {"path":"Album/list.m3u","name":"list.m3u","uri":"content://list","mimeType":null,"directory":false,"text":"#EXTM3U\n#EXTINF:1,First\nsong.flac\n"},
            {"path":"Album/disc.cue","name":"disc.cue","uri":"content://cue","mimeType":null,"directory":false,"text":"FILE \"disc.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"Intro\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:00:00\n"},
            {"path":"Album/song.flac","name":"song.flac","uri":"content://song","mimeType":"audio/flac","directory":false,"text":null},
            {"path":"Album/SONG.lrc","name":"SONG.lrc","uri":"content://lyric","mimeType":"text/plain","directory":false,"text":null},
            {"path":"Album/disc.flac","name":"disc.flac","uri":"content://disc","mimeType":"audio/flac","directory":false,"text":null}
        ]}]}"##;
        let catalog = build(serde_json::from_str::<Snapshot>(input).unwrap());
        assert_eq!(catalog.tracks.len(), 5);
        assert_eq!(catalog.tracks[1].sidecar_lyrics_uri.as_deref(), Some("content://lyric"));
        assert_eq!(catalog.tracks[2].title, "Intro");
        assert_eq!(catalog.tracks[2].cue_end_ms, Some(60_000));
        assert_eq!(catalog.tracks[3].cue_start_ms, Some(60_000));
        assert_eq!(catalog.tracks[4].title, "First");
    }
}
