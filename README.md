# Flowery

Flowery is an music player for local audio libraries. It uses Kotlin and Jetpack Compose for the application interface and a Rust audio engine for source-format decoding, playback scheduling, and Android AAudio output.

> **Development status:** Flowery is under development. CI validates software builds and automated tests; it does not replace playback testing on physical Android devices.

## Highlights

- Local music libraries through Android's Storage Access Framework (SAF).
- Automatic output-route detection from currently connected Android audio devices.
- AAudio output using signed integer PCM, selected from the decoded source bit depth and Android API capabilities.
- Native Rust streaming decode, source-format preservation, bounded buffering, and playback-session scheduling.
- Metadata reading/editing and timestamped sidecar LRC lyrics.
- Queue, seek, shuffle, repeat, and Android media-session controls.

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

## Lyrics

Flowery reads same-directory sidecar `.lrc` files whose base filename matches the audio file. LRC timestamps drive the active-line highlight and scrolling position. The parser also accepts enhanced LRC word timestamp markers, but the current player screen highlights lyrics by line rather than animating individual words. The player displays a message when no synchronized sidecar LRC is available; it does not currently provide a separate plain-text lyrics mode, embedded-lyrics extraction, remote lyric search, or lyric downloading.

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
- [Audio engine](AUDIO_ENGINE.md)
- [Library, metadata, and lyrics](LIBRARY_AND_METADATA.md)
- [Build instructions](docs/BUILDING.md)
- [UI theme](docs/UI_THEME.md)
- [Playback diagnostics](docs/PLAYBACK_DIAGNOSTICS.md)
- [Manual test log](docs/MANUAL_TEST_LOG.md)
- [Refactor status](docs/REFACTOR_STATUS.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Testing and limitations

GitHub Actions runs native Rust tests, the Kotlin/Rust JNI contract check, Android JVM unit tests, native Android library builds, and debug/release APK packaging. Passing CI does not establish real-device AAudio stability or device-specific PCM format support.

## License

See [LICENSE](LICENSE) and the applicable third-party licenses. Dependency and font notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
