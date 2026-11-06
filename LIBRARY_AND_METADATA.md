# Library, Metadata, and Lyrics

## Folder access and scanning

Flowery uses the Android Storage Access Framework (SAF), not MediaStore. Users select folder trees through the system document picker. Configured roots can be enabled, disabled, or removed; only enabled roots are scanned. `LibraryScanner` recursively traverses trees through `DocumentFile` and deduplicates tracks by document URI.

The supported audio filename-extension registry, case-insensitive sidecar LRC matching, and relative playlist-path normalization are owned by `native/src/library.rs`. `LibraryScanner` passes directory filename snapshots to Rust for sidecar association and asks Rust to normalize playlist paths before resolving them through SAF. Kotlin remains responsible for traversing `DocumentFile` trees, opening SAF documents, and associating Android `Uri` values; recognizing an extension is only a discovery filter and does not guarantee that Symphonia can decode the file.

## Sidecar LRC

The scanner associates a sidecar `.lrc` file when its base filename matches the audio filename in the same directory, ignoring case. `LrcParser` accepts `[minutes:seconds]` timestamps with an optional one-to-three-digit fractional component. Multiple timestamps on one line create multiple lyric entries. Entries are sorted by time, and the current line is the last timestamp not greater than the current playback position.

Embedded lyric extraction, remote lyric search, lyric downloads, and writing LRC files are not implemented.

## Audio tags

The Rust layer uses Lofty to read and write supported tags. The editor exposes title, artist, album, album artist, genre, year/date, track number, disc number, comment, composer, and lyrics. For reads and edits, Kotlin passes a duplicated SAF file descriptor to Rust rather than copying the complete audio file into a JNI `ByteArray`. Rust stages the descriptor contents in the app-private cache and Lofty reads or updates that staged file. The edited staged file is streamed back to the original SAF document and then removed. The provider must grant write access. Text-tag updates do not decode and re-encode the audio stream.

Tag support varies by format and tag type. Unsupported write combinations and provider write failures must be treated as failed edits. Embedded artwork can be read for display, but artwork replacement is not currently exposed.

Audio files remain in the selected document provider. Temporary metadata-editing files are stored in the application's private cache; the library does not upload audio files to a remote service.

## Folder-scoped playback

The folder browser filters the scanned track list by the configured root name. Selecting a track passes the filtered folder track list to the playback service as the queue, rather than using the entire library. Removing one or more selected folder roots removes only their entries from Flowery's configured list and releases the associated persisted SAF permission. It does not delete source folders or audio files.

The Android system folder picker returns one tree URI per selection. Multiple folder roots are supported by repeating the Add folder action; the current picker integration does not provide a single multi-tree selection dialog.
