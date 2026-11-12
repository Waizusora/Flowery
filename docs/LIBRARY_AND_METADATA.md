# Library, Metadata, and Lyrics

## Folder access and scanning

Flowery uses the Android Storage Access Framework (SAF), not MediaStore. Users select folder trees through the system document picker. Configured roots can be enabled, disabled, or removed; only enabled roots are scanned. `LibraryScanner` recursively traverses trees through `DocumentFile` and passes a flat document snapshot to the Rust catalog builder, which deduplicates catalog identities.

Rust owns audio classification, same-directory sidecar matching, playlist parsing and association, root-confined path resolution, virtual-track construction, CUE time-range calculation, and catalog deduplication. Kotlin traverses SAF trees, reads playlist text using the selected charset, and maps Rust catalog URI values back to Android `Uri` models. Rust parses CUE/M3U/M3U8/PLS entries, resolves paths within the selected root, and builds virtual playlist tracks; recognizing an extension is only a discovery filter and does not guarantee that Symphonia can decode the file.

## Sidecar LRC and synchronized lyrics

The scanner associates a sidecar `.lrc` file when its base filename matches the audio filename in the same directory, ignoring case. The parser accepts line timestamps in `[minutes:seconds]` form with an optional one-to-three-digit fractional component. It accepts multiple timestamps on one line, applies the optional `[offset:...]` metadata value, and sorts parsed entries by timestamp. A line without a valid line timestamp is not emitted as a synchronized lyric line.

Enhanced LRC word markers in `<minutes:seconds.fraction>` form are also parsed and retained as per-word timing data. The current player UI uses the line timestamps to choose the active line from the playback position and scrolls the list to that line. It visually distinguishes the active line from surrounding lines; it does not currently animate or highlight individual words using the parsed word timings.

The player screen is specifically a synchronized-LRC view. It does not currently offer a separate plain-text lyrics mode. When no synchronized LRC lines are available, it displays `No synchronized LRC lyrics found.` The former `LYRICS LRC` and `Synced Lyric` captions have been removed; they were static UI labels, not controls or detection indicators.

The lyrics panel background uses `MaterialTheme.colorScheme.primaryContainer`, so its rose color follows the active light or dark Soft Rose theme. Album artwork no longer determines the lyrics-panel background.

## Downloaded lyrics

The player can search LRCLIB and retrieve plain-text lyrics, synchronized LRC, and word-synchronized YAML lyrics. Downloads are parsed before they are written to app-private storage and are associated with the selected audio document URI. Unsupported, malformed, or empty lyric payloads are rejected with an in-app error message; an invalid download does not replace a previously saved lyric file. When downloaded lyrics are present, the lyrics view uses that list in preference to sidecar lyrics. Otherwise, it displays the track's sidecar LRC lyrics.

Downloaded lyrics are stored under the application's private files directory. They are not written into the source audio file or uploaded by Flowery.

## Last-selected track restoration

Flowery persists the identity of the last selected track, including playlist or CUE entry details where applicable. On startup, the application resolves that identity against the restored library index and reloads available audio tags and sidecar lyrics. The player displays the embedded title when present, otherwise the indexed track title, and uses `Unknown artist` when artist metadata is absent. This restores track identity and metadata for display; it does not automatically restart audio playback.

## Audio tags

The Rust layer uses Lofty to read and write supported tags. The editor exposes title, artist, album, album artist, genre, year/date, track number, disc number, comment, composer, and lyrics. For reads and edits, Kotlin passes a duplicated SAF file descriptor to Rust rather than copying the complete audio file into a JNI `ByteArray`. Rust stages the descriptor contents in the app-private cache and Lofty reads or updates that staged file. The edited staged file is streamed back to the original SAF document and then removed. The provider must grant write access. Text-tag updates do not decode and re-encode the audio stream.

Tag support varies by format and tag type. Unsupported write combinations and provider write failures must be treated as failed edits. Embedded artwork can be read for display, but artwork replacement is not currently exposed.

Audio files remain in the selected document provider. Temporary metadata-editing files are stored in the application's private cache; the library does not upload audio files to a remote service.

## Folder-scoped playback

The folder browser filters the scanned track list by the configured root name. Selecting a track passes the filtered folder track list to the playback service as the queue, rather than using the entire library. Removing one or more selected folder roots removes only their entries from Flowery's configured list and releases the associated persisted SAF permission. It does not delete source folders or audio files.

The Android system folder picker returns one tree URI per selection. Multiple folder roots are supported by repeating the Add folder action; the current picker integration does not provide a single multi-tree selection dialog.
