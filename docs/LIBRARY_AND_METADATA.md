# Library, Metadata, and Lyrics

## Folder access

Flowery uses Android's Storage Access Framework (SAF) for user-selected audio folders. It does not query MediaStore.

The user selects a folder through the system document picker. The application retains the selected tree URI and stores folder configuration locally. Configured roots can be enabled, disabled, or removed. Only enabled roots are scanned.

## Scanning behavior

`LibraryScanner` recursively traverses each enabled root using `DocumentFile`. The scanner recognizes the following filename extensions:

`mp3, flac, wav, wave, aiff, aif, alac, m4a, mp4, aac, ogg, opus, oga, wma, ape, wv, tta, mka, m4b, dsf, dff`.

Tracks are represented by their document URI, filename-derived title, root folder name, MIME type, and optional sidecar-lyrics URI. Entries are deduplicated by document URI.

The extension list is a discovery filter only. Successful playback depends on Symphonia's actual container and codec support. Kotlin submits a flat SAF document snapshot to Rust. Rust classifies audio files, associates same-directory sidecar lyrics, parses CUE/M3U/M3U8/PLS content, resolves playlist paths without allowing traversal outside the selected root, creates virtual playlist/CUE entries, calculates CUE ranges, and deduplicates catalog identities. Kotlin maps returned document URIs into Android models.

## Sidecar LRC files

During traversal of a directory, the scanner indexes files ending in `.lrc` by their filename without the extension. An audio file is associated with an LRC file when the base filenames match, ignoring case.

`LrcParser` delegates parsing to Rust, which recognizes timestamps in the form `[minutes:seconds]` with an optional fractional component of one to three digits. Multiple timestamps on one line are expanded into separate lyric entries and sorted by timestamp. Current-line lookup is also performed in Rust using a binary search over the sorted timestamp array; Kotlin only maps the returned index to the UI model. Equal timestamps resolve to the last entry at that timestamp.

The current implementation does not search embedded lyrics or remote lyric providers, and it does not download or write LRC files.

## Metadata reading and editing

The Rust layer uses Lofty to read and write supported audio tags. The UI exposes:
- Title
- Artist
- Album
- Album artist
- Genre
- Year/date
- Track number
- Disc number
- Comment
- Composer
- Lyrics

For editing, the selected document is copied to an app-private temporary file, updated through Lofty, and written back to the original SAF document. This requires the document provider to grant write access. The audio stream is not decoded and re-encoded for text-tag updates.

Support varies by container and tag type. The implementation checks whether Lofty can write the target format/tag combination. If the operation is unsupported or the provider denies write access, the edit must be treated as failed. Embedded artwork can be read for display, but artwork replacement is not currently exposed in the editor.

## Storage and privacy notes

Audio files remain in the user-selected document provider. Temporary metadata-editing files are staged in the application's private cache. The library does not upload audio files to a remote service.
