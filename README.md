# Flowery

Flowery is an Android music player focused on local music, transparent audio routing, and high-quality playback. It combines a Jetpack Compose interface with a native Rust audio engine and direct USB DAC support.

> **Development status:** Flowery is under active development. Automated builds and tests do not guarantee compatibility with every Android device, audio format, or USB DAC. Physical-device testing and external digital capture are still required to verify hardware-level audio behavior.

## Highlights

- **Local music library** — Add and scan folders through Android's Storage Access Framework (SAF). No MediaStore library is used.
- **Native playback engine** — Rust handles audio decoding, streaming resampling, equalization, and bounded PCM buffering.
- **Audio output** — AAudio for Android audio routes and direct USB Audio Class (UAC) transport for supported USB DACs.
- **Automatic Bit-perfect detection** — No manual toggle. Flowery evaluates the connected, permissioned USB DAC and reports Bit-perfect only after the native playback producer confirms source-preserving output. External digital capture is still needed for hardware verification.
- **31-band equalizer** — ISO one-third-octave graphic EQ.
- **Metadata and lyrics** — Read and edit supported tags with Lofty and display synchronized sidecar LRC lyrics.
- **Playback controls** — Queue, seek, shuffle, repeat, and Android media-session controls.

## Technology

| Component | Technology |
| --- | --- |
| Android app | Kotlin, Android SDK 35, minSdk 26 |
| UI | Jetpack Compose, Material 3 |
| Audio decoding | Symphonia (Rust) |
| Sample-rate conversion | Rubato (Rust) |
| DSP | Native Rust 31-band equalizer |
| Android audio output | AAudio |
| Direct USB audio | C++ libusb transport with Rust playback pipeline |
| Audio tags | Lofty |
| Folder access | Android Storage Access Framework |
| UI typeface | Noto Sans (SIL Open Font License 1.1) |

**Application ID:** `com.waizusora.flowery`

## Audio pipeline

The standard Android playback path is:

`SAF audio file → Symphonia decoder → Rubato resampler (when needed) → 31-band EQ → bounded PCM ring buffer → AAudio`

For supported USB DACs, Flowery automatically checks connected devices for an active USB permission grant and can select a direct USB audio path. Output profiles are negotiated from the device's advertised capabilities; support depends on the DAC descriptors, source format, and synchronization mode. The user does not enable Bit-perfect manually.

Bit Perfect eligibility is disabled by processing that changes samples, including equalization, software gain, and resampling. A Bit Perfect indicator is not proof of the complete physical USB signal path.

## Build requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Rust toolchain compatible with Rubato 5 (rustc 1.87 or newer)
- Rust Android targets: `aarch64-linux-android`, `armv7-linux-androideabi`, and `x86_64-linux-android`
- `cargo-ndk`

See [BUILDING.md](BUILDING.md) for setup and build instructions.

## Documentation

- [Architecture](ARCHITECTURE.md) — application and native component design
- [Audio engine](AUDIO_ENGINE.md) — decoding, DSP, buffering, output, and seeking
- [Library and metadata](LIBRARY_AND_METADATA.md) — folder scanning, tags, and lyrics
- [Build instructions](BUILDING.md) — development environment and build steps
- [Refactor status](docs/REFACTOR_STATUS.md) — implementation progress
- [Third-party notices](THIRD_PARTY_NOTICES.md) — dependency and font notices

## Testing and limitations

GitHub Actions builds the Android application and native Rust components and runs the configured automated tests. CI success confirms only that those checks passed; it does not replace testing on physical Android devices and USB DACs.

Known limitations include:

- Audio format support depends on the codecs enabled in the Symphonia build. A recognized file extension does not guarantee that a file can be decoded.
- USB playback depends on the DAC's advertised UAC profiles and supported synchronization behavior.
- AAudio may fall back from exclusive to shared mode.
- Gapless playback and crossfade are not implemented.
- Embedded-lyrics extraction, remote lyric search, and lyric downloading are not implemented.
- Embedded cover-art replacement is not currently exposed.

## License

See the repository's [LICENSE](LICENSE) file and the applicable licenses of third-party dependencies before redistributing Flowery. Dependency notices and the Noto Sans SIL Open Font License 1.1 text are provided in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [LICENSES/NotoSans-OFL.txt](LICENSES/NotoSans-OFL.txt).
