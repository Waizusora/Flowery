# Flowery

Flowery is an Android music player focused on a clear local music library, flexible playback, and direct USB DAC integration. It combines a Jetpack Compose and Material 3 interface with a Rust audio engine for decoding, PCM processing, DSP, and playback policy. Kotlin provides Android platform integration, while a narrowly scoped C++ component provides the libusb transport used by direct USB playback.

The project aims to provide a consistent PCM output path across supported audio formats, with transparent reporting of the negotiated DAC format and whether the active path meets Flowery's strict Bit Perfect conditions.

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
| Audio output | Rust AAudio NDK backend; native C++ libusb UAC1/UAC2 transport API |
| PCM buffering | Rust AAudio SPSC ring; bounded Rust queue for direct USB PCM blocks |
| Audio tags | Lofty 0.25.5 |
| Folder access | Storage Access Framework (SAF) |
| Playback integration | Android foreground service and MediaSessionCompat; Rust playback controller and queue state |
| CI | GitHub Actions |

Application ID: `com.waizusora.flowery`

## Features

### Navigation and settings

- A transparent navigation bar provides Folders, Lyrics, USB DAC access, DSP, and Settings destinations. USB DAC opens a descriptor report for detected USB Audio Class devices. It displays VID/PID, manufacturer/product identity, raw and parsed UAC descriptors, validated PCM playback profiles, endpoint synchronization, advertised sample rates and channels, UAC2 clock ranges, Feature Unit volume capability, and the local descriptor-fingerprint record. Opening the panel does not toggle playback mode or enable bit-perfect output.
- The Folders destination lists configured SAF roots, supports multi-select removal from Flowery's list, per-folder scan enablement, scanning, and folder-specific track browsing. Removing a folder releases its persisted access grant and removes it from the app's configured list; it does not delete the user's original files.
- Selecting a track from a folder starts playback with that folder's tracks as the playback queue, keeping the user in the selected folder view.
- The Lyrics icon toggles synchronized sidecar LRC lyrics in the album-artwork area; toggling it again restores the artwork. Now Playing keeps playback presentation focused on artwork, track metadata, controls, and timeline; diagnostic telemetry is available only through USB Diagnostics.
- DSP contains software output volume and the 31-band equalizer.
- Settings provides Audio information, USB Diagnostics, Library guidance, Appearance, and Other pages. USB Diagnostics has one Record Log workflow that captures USB descriptors, descriptor-derived DAC profiles and clock ranges, AAudio runtime telemetry, and direct USB/libusb playback events into a single export. Appearance offers Light, Black, and Auto themes; Auto follows Android's system dark-mode setting. Language selection, translator credits, and contributor credits are currently placeholders.
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

**USB and Bit Perfect status:** The direct USB route uses Symphonia as the only decoder and sends decoded PCM through one shared Rust PCM producer, bounded queue, and C++ libusb transport. WAV has no special passthrough route. Bit Perfect mode is intentionally strict: the source must be recognized as PCM or a supported lossless codec, Symphonia must expose exact integer samples at 8/16/20/24/32-bit depth, and the DAC must negotiate the exact valid-bit depth, sample rate, channel count, and matching subslot width. Rust packs the typed integer samples directly into the shared bounded USB PCM queue without a float round trip. FLAC's S32 output is restored to its valid-bit width only after padding-bit validation. Lossy codecs and unsupported decoder sample representations use float32. EQ, software gain, and resampling disable the native path; DAC hardware volume may remain active because it does not alter USB PCM samples. Kotlin reports Bit Perfect only after the Rust producer confirms native integer output and the first USB transfer is accepted. Physical digital capture is still required for hardware-level verification.

Direct USB PCM playback negotiates matching 1–8 channel UAC1 Type-I PCM and UAC2 PCM profiles. When direct USB/Bit Perfect mode is enabled, AC-3 sources use a separate encoded-only route: Flowery prefers a descriptor-validated UAC1 Type-III IEC 61937 AC-3 profile (`wFormatTag=0x2001`, two 16-bit subframes), then falls back to UAC1 Type-II AC-3 (`wFormatTag=0x1002`, 1536 samples per frame and descriptor bitrate/rate validation). Type-II sends each complete encoded frame as full-size isochronous packets, a possible short final packet, and a zero-length transfer delimiter. Type-III builds a 6144-byte IEC 61937 burst with `Pa/Pb/Pc/Pd`, AC-3 data type `0x0001` plus the frame `bsmod` bits in `Pc`, payload bit length, 16-bit little-endian payload word ordering, and zero padding; its USB packets remain aligned to complete 4-byte stereo sample frames. The Rust producer, bounded encoded queue, Kotlin pipeline, and dedicated C++/libusb writer remain isolated from the PCM queue and `nativeWriteIsochronous()`. Packet-boundary seek, pause-time transport drain, and end-of-stream drain are implemented. Asynchronous encoded endpoints are rejected until a feedback-aware encoded scheduler is available; adaptive and synchronous profiles use nominal scheduling. MPEG, DTS, AAC, and other encoded codecs are not enabled. Physical-DAC USB traces and external digital capture are still required before hardware compatibility can be claimed.

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

The application uses the incremental Rust streaming decoder and native output ring for playback. The former whole-file JNI decoder, WAV-specific source reader, legacy float-array streaming read export, and stateless array-based resampler/equalizer entry points have been removed so playback cannot accidentally regress to a full-track PCM allocation or duplicate DSP path. Kotlin retains Android document, lifecycle, routing, and UI responsibilities; PCM decoding, resampling, equalization, and bounded output buffering remain in Rust.

## Current limitations

- Bit Perfect is only reported when the Rust producer is actually sending native integer samples and the negotiated DAC sample rate, channel count, valid-bit depth, and subslot width exactly match. Supported integer depths are 8/16/20/24/32 bits when the decoder exposes exact typed integer samples. EQ, software gain, ducking, and resampling disable the native path; hardware Feature Unit volume may remain active. Unsupported decoder representations fall back to float32. Software tests include WAV decoder-to-USB byte comparisons; physical DAC digital capture remains outstanding.
- Direct USB decoded-PCM playback supports descriptor-selected UAC1 Type-I PCM and UAC2 profiles with 1–8 channels, signed PCM valid-bit resolutions of 8–32 bits, and 1–4 byte subslots. UAC1 Type-II/III are descriptor-recognized but not decoded-PCM playback formats. Explicit asynchronous feedback is used for packet scheduling; implicit feedback and vendor-specific synchronization variants remain unsupported.
- USB descriptor profiles are stored locally by VID/PID and SHA-256 descriptor fingerprint. Standard USB descriptors do not reliably identify the physical silicon chipset, so the database records descriptor families rather than asserting an unverified chipset model.
- AAudio exclusive mode is requested but may fall back to shared mode.
- The direct USB PCM path supports up to eight channels when the source and selected DAC alternate setting match. The Android AAudio output route remains limited to the channel layouts accepted by the selected Android output device.
- Gapless playback and crossfade are not implemented.
- Embedded and remote lyric retrieval are not implemented.
- Embedded artwork replacement is not implemented.
- Scanner extension recognition is broader than the set of formats that can necessarily be decoded.

## License and third-party components

Review the repository's license file and the license terms of all bundled dependencies before redistributing the application. Lofty and Rubato are available under MIT OR Apache-2.0; Symphonia and transitive dependencies retain their respective upstream licenses.


## Playback and USB architecture

USB Diagnostics → Record Log is the single diagnostic capture/export workflow. It combines periodic AAudio runtime telemetry, direct USB/libusb playback events, and the current connected-device descriptor/clock snapshot. Descriptor records are stored in the local SQLite database `usb_dac_profiles.db`, keyed by VID/PID and a SHA-256 fingerprint of the raw descriptor block. This is a descriptor-family identity, not proof of a silicon chipset model.

Playback service commands are coordinated through an Android adapter backed by a Rust playback lifecycle controller. Rust owns queue index, shuffle/repeat state, transition decisions, and UAC1/UAC2 profile parsing; Android retains USB permission, device connection, foreground-service lifecycle, and URI/metadata presentation. The application does not infer sample-rate support from a device name, and direct USB playback still requires a successful device clock negotiation.


### Encoded queue ownership and JNI packet envelope

The encoded queue is reference-counted across JNI handles. A producer retains its own shared queue reference, so releasing the Kotlin queue handle does not invalidate a producer that is still alive. The packet envelope returned to Kotlin is 27 bytes before the opaque payload: framing kind (1 byte), format/data-type code (2-byte little-endian), sequence (8-byte little-endian), presentation timestamp in microseconds (8-byte little-endian; zero means unavailable), and duration in microseconds (8-byte little-endian). The envelope describes metadata only; it does not establish codec compatibility or USB wire framing.


### Native integer PCM and Bit Perfect policy

Direct USB PCM now attempts native integer extraction from Symphonia's typed decoded buffers for integer PCM sources and lossless codecs whose decoder exposes an exact supported integer representation. The extracted samples are interleaved and packed directly into the existing bounded USB PCM producer queue; valid bits are left-justified in the USB subslot and unused low bits are zero-filled. The C++ libusb transport and float32 path are shared. Lossy and unsupported-depth sources also use this same USB producer with float32 decoding and DAC-compatible output conversion. The initial implementation includes signed/unsigned 8- and 16-bit PCM, 20/24-bit samples stored in 24- or 32-bit decoder planes, and 32-bit integer PCM. FLAC's S32 left-aligned output is shifted back only after its padding bits are verified. Unsupported decoder representations fall back to the float32 path before native PCM is emitted.

Native selection is performed in Rust from codec classification, typed decoder output, source/output sample rate, channel count, valid-bit width, negotiated USB format, flat EQ, unity software gain, unchanged channel mapping, and disabled resampling. Lossy codecs always use float32 decoded PCM. Kotlin receives the producer's actual native-path state and displays Bit Perfect only after a native integer block has been produced and accepted by the USB transport. Changing DSP or software gain switches the producer back to float32; queued native samples are converted into the float pipeline before processing so frames are not discarded. The USB format negotiation still must exactly match the source for a Bit Perfect claim.

Automated tests cover integer packing, Symphonia typed-plane extraction, left-aligned FLAC-style 24-bit restoration, rejection of non-zero padding bits, and an end-to-end WAV PCM decoder-to-USB-byte comparison. This is software-level bit-exact validation; external digital capture on physical DACs is still required to validate the complete hardware path. IEEE-float source files and codecs whose decoders do not expose exact integer samples are not eligible for the native integer Bit Perfect path.