# Flowery

Flowery is an music player for local audio libraries. It uses Kotlin and Jetpack Compose for the application interface and a Rust audio engine for source-format decoding, playback scheduling, and Android AAudio output.

> **Development status:** Flowery is under development. CI validates software builds and automated tests; it does not replace playback testing on physical Android devices.

## Highlights

- Local music libraries through Android's Storage Access Framework (SAF).
- Automatic output-route detection from currently connected Android audio devices, with USB DAC priority.
- USB Audio Class discovery through Android USB Host, including VID/PID, Audio Control/Streaming interfaces, and isochronous endpoint direction.
- Native full/high-speed USB isochronous PCM packet scheduling primitives.
- Experimental Android libusb file-descriptor bridge for claiming a descriptor-selected AudioStreaming interface.
- Native USB PCM chunk packing from the Symphonia decoder and UAC explicit-feedback packet decoding; asynchronous transfer submission is not yet enabled.
- AAudio output using signed integer PCM, selected from the decoded source bit depth and Android API capabilities.
- Native Rust streaming decode, source-format preservation, bounded buffering, and playback-session scheduling.
- Metadata reading/editing, timestamped sidecar LRC lyrics, and LRCLIB search/download for plain, synchronized, and word-synchronized lyrics.
- Persistent last-selected track metadata restoration across application restarts.
- Queue, seek, shuffle, repeat, and Android media-session controls. Media metadata is published when track details or duration change; periodic playback updates are limited to session position/state to avoid unnecessary SystemUI refreshes.

## Technology

| Component | Technology |
| --- | --- |
| Android app | Kotlin, Android SDK 35, minSdk 26 |
| UI | Jetpack Compose, Material 3 |
| Audio decoding | Symphonia (Rust) |
| Sample-rate handling | Preserve the decoded source sample rate; Android manages downstream conversion |
| Android audio output | AAudio NDK API |
| Native integration | Rust JNI via the Android NDK |
| Audio tags | Lofty |
| Folder access | Android Storage Access Framework |
| UI typeface | Noto Sans (SIL Open Font License 1.1) |

**Application ID:** `com.waizusora.flowery`

## Audio pipeline

`SAF document → Symphonia decode → source-rate PCM conversion → bounded Rust PCM ring → AAudio → Android audio routing`

Flowery reads each file's sample rate and bit depth from its decoded stream metadata, then configures its AAudio stream at the source sample rate. Android may resample or otherwise convert the stream downstream according to the active device and system audio policy. The AAudio output boundary uses signed integer PCM

## USB audio discovery and exclusive output

Flowery enumerates connected USB Audio Class devices independently from Android's USB audio output routes. When Android exposes a USB DAC as an output device, Flowery selects that route ahead of AUX, Bluetooth, and the built-in speaker, then requests the exact Android output device through AAudio in EXCLUSIVE sharing mode. USB playback is fail-closed: if AAudio cannot open that USB device in EXCLUSIVE mode, Flowery reports an error and does not retry in SHARED mode.

This is Android-managed USB audio output through AAudio, not a raw USB Audio Class host driver. It does not claim the USB interface, submit isochronous URBs directly, or bypass Android audio policy. AAudio's EXCLUSIVE sharing mode and integer PCM configuration are necessary route conditions, not proof that the entire device-to-DAC path is bit-perfect; Android vendor DSP, firmware, clocking, and DAC behavior still require physical-device validation.

The USB inventory reports VID/PID, interface subclass/protocol, and endpoint transfer direction. Android output capabilities are reported separately because the public AudioDeviceInfo API does not expose USB VID/PID for a reliable cross-API device match. An isochronous OUT endpoint is only a candidate indicator; it does not prove that an application can claim the interface or stream USB audio directly. Android's public USB Host API does not provide isochronous transfer support.
## Lyrics

Flowery reads same-directory sidecar `.lrc` files whose base filename matches the audio file. LRC timestamps drive the active-line highlight and scrolling position. The parser also accepts enhanced LRC word timestamp markers. Users can search LRCLIB and download plain-text, synchronized LRC, or word-synchronized YAML lyrics. Downloaded lyrics are stored in app-private storage and associated with the selected audio URI; malformed or empty downloads are rejected without terminating the application. The lyrics view uses the downloaded lyric list when available and otherwise falls back to sidecar lyrics.

The last selected track is persisted in app preferences. On application startup, Flowery restores the track selection and reloads its embedded metadata and sidecar lyrics when the document remains accessible. The player title falls back to the indexed track title when embedded title metadata is unavailable, and the artist falls back to `Unknown artist`.

The lyrics panel uses the Soft Rose theme's `primaryContainer` color in light and dark themes.

## Build requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK 27.2.12479018
- Rust toolchain compatible with the pinned dependencies
- Rust Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`, and `x86_64-linux-android`
- `cargo-ndk`

See [BUILDING.md](docs/BUILDING.md) for setup and build instructions.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Library, metadata, and lyrics](docs/LIBRARY_AND_METADATA.md)
- [Build instructions](docs/BUILDING.md)
- [UI theme](docs/UI_THEME.md)
- [Playback diagnostics](docs/PLAYBACK_DIAGNOSTICS.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Testing and limitations

GitHub Actions runs native Rust tests, the Kotlin/Rust JNI contract check, Android JVM unit tests, native Android library builds, and debug/release APK packaging. Passing CI does not establish real-device AAudio stability or device-specific PCM format support.

## License

See [LICENSE](LICENSE) and the applicable third-party licenses. Dependency and font notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
