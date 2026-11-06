# Flowery

Flowery is an Android music player with a Kotlin and Jetpack Compose interface, a Rust-based decoding, DSP, and Android AAudio output layer, plus a separate native C++ libusb transport for direct USB DAC playback.

## Project status

The application currently includes folder-based audio discovery, playback queue controls, shuffle and repeat modes, seek support, a 31-band ISO graphic equalizer, software volume, audio metadata display and editing, and sidecar LRC lyric parsing.

The repository is under active development. Features described as limitations below are not implied to be available merely because a related library or native API is present.

## Technology

| Area | Implementation |
| --- | --- |
| Android application | Kotlin, Android SDK 35, minSdk 26 |
| User interface | Jetpack Compose, Material 3 |
| Compressed audio decoding | Symphonia 0.5.4 (Rust) |
| Streaming sample-rate conversion | Rubato 5.0.1 (Rust) |
| Equalizer | Native Rust 31-band ISO graphic EQ |
| Audio output | Rust AAudio NDK backend; native C++ libusb UAC2 transport API |
| PCM buffering | Rust AAudio SPSC ring; bounded Rust queue for direct USB PCM blocks |
| Audio tags | Lofty 0.25.5 |
| Folder access | Storage Access Framework (SAF) |
| Playback integration | Android foreground service and MediaSessionCompat; Rust playback controller and queue state |
| CI | GitHub Actions |

Application ID: `com.waizusora.flowery`

## Features

### Navigation and settings

- A transparent navigation bar provides Folders, Lyrics, USB DAC access, DSP, and Settings destinations. USB DAC access requests Android USB host permission for a detected USB Audio Class device; it does not enable bit-perfect output.
- The Folders destination lists configured SAF roots, supports multi-select removal from Flowery's list, per-folder scan enablement, scanning, and folder-specific track browsing. Removing a folder releases its persisted access grant and removes it from the app's configured list; it does not delete the user's original files.
- Selecting a track from a folder starts playback with that folder's tracks as the playback queue, keeping the user in the selected folder view.
- The Lyrics icon toggles synchronized sidecar LRC lyrics in the album-artwork area; toggling it again restores the artwork.
- DSP contains software output volume and the 31-band equalizer.
- Settings provides Audio information, Library guidance, Appearance, and Other pages. Appearance offers Light, Black, and Auto themes; Auto follows Android's system dark-mode setting. Language selection, translator credits, and contributor credits are currently placeholders.
- Battery optimization opens Android's per-app exemption request screen. The user must approve the system prompt; Flowery cannot grant the exemption itself.
- Update opens the project's GitHub Releases page in the browser.
- Open Source lists the direct application and native dependencies with their declared versions and applicable upstream license identifiers. It is not an inventory of transitive dependencies; consult the exact packaged notices and upstream license texts before redistribution.

### Playback

- Play audio documents selected from configured folders.
- Maintain a playback queue with previous and next controls.
- Shuffle and repeat-off, repeat-all, and repeat-one modes.
- Seek within the current track.
- Pause and resume playback.
- Continue playback through a foreground media playback service, with media-session and notification controls.
- Handle audio focus changes, including transient interruptions and ducking.

### Library

- Add multiple folder roots through the Storage Access Framework.
- Enable or disable individual roots and remove roots from the configured list.
- Recursively scan enabled roots for supported filename extensions; Rust owns audio classification, same-directory sidecar LRC matching, CUE/M3U/M3U8/PLS parsing and association, root-confined playlist path resolution, virtual-track construction, CUE time-range calculation, and catalog deduplication. Kotlin only traverses SAF trees, reads playlist text, and maps results to Android URIs.
- Associate a sidecar `.lrc` file with an audio file when the base filenames match.
- Parse CUE, M3U, and PLS playlist text in the Rust engine; Kotlin retains SAF document resolution and charset selection.
- Scan document trees directly; the library does not use MediaStore.

Folder scanning recognizes these extensions: `.mp3`, `.flac`, `.wav`, `.wave`, `.aiff`, `.aif`, `.alac`, `.m4a`, `.mp4`, `.aac`, `.ogg`, `.opus`, `.oga`, `.wma`, `.ape`, `.wv`, `.tta`, `.mka`, `.m4b`, `.dsf`, and `.dff`.

A filename being recognized by the scanner does not guarantee that its codec can be decoded. Actual playback depends on the container and codec support available in the configured Symphonia build.

### Audio processing and output

- Decode audio progressively from a SAF file descriptor using Symphonia.
- Convert sample rates incrementally using a persistent Rubato resampler.
- Apply a 31-band ISO one-third-octave equalizer with per-band gain limited to -12 dB through +12 dB.
- Preserve resampler and equalizer history across streaming chunks.
- Update equalizer gains during playback without rebuilding the track decoder.
- Feed PCM to AAudio through a bounded ring buffer.
- Request exclusive AAudio sharing first and fall back to shared mode when exclusive mode cannot be opened.
- Prefer a reported USB output sample rate when one is available and attempt to open the selected rate.

The equalizer's center frequencies are 20 Hz, 25 Hz, 31.5 Hz, 40 Hz, 50 Hz, 63 Hz, 80 Hz, 100 Hz, 125 Hz, 160 Hz, 200 Hz, 250 Hz, 315 Hz, 400 Hz, 500 Hz, 630 Hz, 800 Hz, 1 kHz, 1.25 kHz, 1.6 kHz, 2 kHz, 2.5 kHz, 3.15 kHz, 4 kHz, 5 kHz, 6.3 kHz, 8 kHz, 10 kHz, 12.5 kHz, 16 kHz, and 20 kHz.

**USB and bit-perfect limitations:** When USB exclusive mode is enabled for a compatible USB Audio Class 2 (UAC2) stereo DAC, supported stereo WAV integer PCM16/24/32 files can use the direct libusb UAC2 path without decoding or resampling; unity gain preserves source samples, while non-unity software volume intentionally changes them. Other supported audio formats are decoded by Symphonia on a dedicated decoder thread, processed as configured, scaled by the per-DAC software gain (20% by default for a newly seen DAC), quantized to packed 24-bit PCM, buffered in a bounded queue, and sent through the same USB transport; this bypasses Android's audio mixer but is not source-bit-perfect. The direct WAV path uses a per-DAC software volume preference (20% default for a newly seen DAC); non-unity gain changes PCM samples and is not bit-perfect, while unity gain leaves the source bytes unchanged. Rust parses the RIFF/WAVE container, validates stereo integer PCM16/24/32, reads frame-aligned data blocks, and handles frame-based seeking. For decoded USB sources, Rust owns segment frame limits, seek epochs, decoder seeking, the stateful software-gain ramp, signed 24-bit PCM packing, block metadata, and publication to the bounded six-block queue; Kotlin retains only producer-thread lifecycle, Android USB permission/session lifecycle, and the libusb transport writer. USB transfer diagnostics include endpoint parameters, packet-level failure details where available, outstanding URB counts and the time since the last completion. A dedicated native libusb event thread handles asynchronous transfer completions independently of the PCM writer, using 64-packet URBs and a bounded queue window. Hardware-verified bit-perfect playback, automatic hardware-volume capability detection and end-to-end device validation are not yet available. Direct USB WAV and decoded USB playback use a per-DAC software volume preference, exposed on the DSP page and saved separately for each DAC, so playback remains controllable when the device has no physical volume control. The normal AAudio path may mix or resample audio; its floating-point DSP path, active equalizer, software gain, or sample-rate conversion can alter sample data.

### Metadata and tag editing

Lofty is used to read and write supported audio tags. The editor exposes title, artist, album, album artist, genre, year/date, track number, disc number, comment, composer, and lyrics.

Tag reads and writes pass SAF file descriptors to Rust rather than copying complete audio files through JNI byte arrays. Lofty operates on a temporary app-private cache file, and the edited file is streamed back to the original SAF document. The document provider must grant write access. Tag/container support varies; unsupported or read-only combinations are rejected rather than treated as successful writes. Editing text tags does not require decoding and re-encoding the audio stream.

Embedded cover artwork can be read for display. Replacing embedded artwork is not currently exposed by the editor.

### Lyrics

- Discover a sidecar `.lrc` file with the same base filename as the audio file.
- Parse sidecar LRC timestamps, expand multiple timestamps per line, and sort entries chronologically in the Rust engine through JNI.
- Resolve the active lyric line in Rust using binary search over sorted timestamps; Kotlin maps the returned index to the UI model.

Embedded-lyrics extraction, remote lyric search, lyric downloading, and lyric-file writing are not implemented.

## Documentation

- [Architecture](ARCHITECTURE.md)
- [Audio engine](AUDIO_ENGINE.md)
- [Library, metadata, and lyrics](LIBRARY_AND_METADATA.md)
- [Build instructions](BUILDING.md)

## Audio pipeline

The streaming playback path is:

`SAF document URI → ParcelFileDescriptor → Rust/Symphonia decoder → Rubato resampler (when required) → 31-band EQ → bounded PCM ring buffer → Rust AAudio callback → Android audio route`

Rust computes the ring buffer capacity from the negotiated output rate: at least two seconds of PCM and at least 32,768 frames, rounded up to a power of two. Rust also selects the nearest supported output rate when the source rate is unavailable, converts timeline positions to frames, and computes the 1.5-second low-water mark bounded to three quarters of ring capacity. Kotlin supplies Android-reported device capabilities and coordinates lifecycle and bounded native pump calls. Rust performs decoding, resampling, equalization, and ring-buffer writes without copying AAudio PCM through Java FloatArrays; Kotlin retains Android route, lifecycle, pause, and seek coordination. The AAudio callback consumes available frames, applies output gain, fills underruns with silence, and observes end-of-stream state. It does not perform file I/O, decoding, resampling, dynamic allocation, or mutex locking.

Seeking uses a format-level Symphonia seek, resets decoder and DSP state, rebuilds resampler history where needed, discards preroll before the requested presentation time, and flushes queued output frames. The player tracks playback generations to prevent an older worker from changing the state of a newer track.

See [Audio engine](AUDIO_ENGINE.md) for details.

## Repository layout

```text
.
├── app/
│   └── src/main/
│       ├── cpp/                 # libusb UAC2 transport and CMake configuration
│       ├── java/.../flowery/    # Android UI, service, SAF library, JNI bridge
│       └── AndroidManifest.xml
├── native/
│   ├── Cargo.toml               # Rust dependencies
│   └── src/
│       ├── lib.rs               # JNI entry points and Lofty tag operations
│       ├── streaming.rs         # Streaming decode pump, resampler, EQ, seek
│       ├── library.rs           # Audio extension and filename policy
│       ├── catalog.rs           # Rust-owned library catalog and playlist association
│       ├── queue.rs             # Shuffle, repeat, and queue transitions
│       ├── usb_pcm.rs           # Bounded direct-USB PCM block queue
│       ├── usb_producer.rs      # Rust-owned decoded USB segment and producer policy
│       ├── usb_descriptors.rs   # UAC descriptor and clock-response parsing
│       ├── playback_policy.rs   # Output-rate, ring sizing and frame policy
│       └── aaudio.rs            # Rust AAudio NDK output and PCM ring buffer
├── docs/                        # Project and implementation documentation
└── .github/workflows/build.yml  # Android and native CI
```

## Build requirements

- JDK 17
- Gradle 8.9 (or the wrapper version configured by the project)
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Rust toolchain compatible with Rubato 5 (rustc 1.87 or newer)
- Rust Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`, and `x86_64-linux-android`
- `cargo-ndk`

See [Building Flowery](BUILDING.md) for local and CI build instructions.

## Automated builds

GitHub Actions runs native Rust unit tests, builds Rust libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, then assembles debug and release APKs.

Workflow: [Build Flowery](.github/workflows/build.yml)

A push to `main`, a pull request, a version tag matching `v*`, or a manual workflow dispatch can start the workflow. Version tags also trigger GitHub Release publishing. Without signing secrets, the release APK is unsigned.

For signed release builds, configure these repository secrets:

- `KEYSTORE_BASE64`: Base64-encoded JKS or PKCS12 keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Signing key alias.
- `KEY_PASSWORD`: Signing key password.

Do not commit keystores, signing passwords, or private signing material.

## Tests and verification

The Rust test suite includes checks for:

- Equalizer continuity across processing chunk boundaries.
- Finite equalizer coefficients at common sample rates and gain settings.
- Accurate WAV seek positioning, including converted seek preroll.
- Saturating millisecond-to-frame conversion.

Android local JVM unit tests use a JVM-compatible `org.json` implementation rather than the Android framework stub, so JSON parsing behavior can be exercised outside an Android runtime.

CI success confirms that the configured build and automated tests completed. It does not replace playback testing across Android versions, audio routes, USB DACs, and individual file formats.

## Native audio API boundary

The application uses the incremental Rust streaming decoder and native output ring for playback. The former whole-file JNI decoder, legacy float-array streaming read export, and stateless array-based resampler/equalizer entry points have been removed so playback cannot accidentally regress to a full-track PCM allocation or duplicate DSP path. Kotlin retains Android document, lifecycle, routing, and UI responsibilities; PCM decoding, resampling, equalization, and bounded output buffering remain in Rust.

## Current limitations

- Bit-perfect USB playback is not guaranteed or hardware-verified.
- Generic UAC2 descriptor/profile discovery and exact clock negotiation are implemented for stereo PCM16/24/32 profiles; UAC1 devices, multichannel output, and automatic DAC hardware-volume capability detection remain unsupported.
- AAudio exclusive mode is requested but may fall back to shared mode.
- Multichannel output beyond mono and stereo is not supported by the current streaming output path.
- Gapless playback and crossfade are not implemented.
- Embedded and remote lyric retrieval are not implemented.
- Embedded artwork replacement is not implemented.
- Scanner extension recognition is broader than the set of formats that can necessarily be decoded.

## License and third-party components

Review the repository's license file and the license terms of all bundled dependencies before redistributing the application. Lofty and Rubato are available under MIT OR Apache-2.0; Symphonia and transitive dependencies retain their respective upstream licenses.


## Playback and USB architecture

Playback service commands are coordinated through an Android adapter backed by a Rust playback lifecycle controller. Rust owns queue index, shuffle/repeat state, transition decisions, and UAC2 profile/clock parsing; Android retains USB permission, device connection, foreground-service lifecycle, and URI/metadata presentation. The application does not infer sample-rate support from a device name, and direct USB playback still requires a successful device clock negotiation.
