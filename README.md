# Flowery

Flowery is an Android music player built with Kotlin, Jetpack Compose, Material 3, Android AAudio, and a Rust audio engine.

## Project identity

- Application ID: `com.waizusora.flowery`
- Product name: Flowery
- Android UI and audio output: Kotlin, Jetpack Compose, Material 3, Android AAudio
- Audio decoding and DSP: Rust, Symphonia, Rubato
- Audio metadata: Lofty
- Native Android output: C++ and AAudio
- Build automation: GitHub Actions

## Player interface

The player uses an amber/gold visual theme with a large embedded-cover display, track and artist information, transport controls, format details, folder selection, library listing, software volume, and a 31-band ISO equalizer.

- Long-press the album artwork or a library row to inspect file metadata and technical audio properties.
- Long-press the track title or artist to open the tag editor.
- Embedded front-cover artwork is displayed when available. Artwork is preserved when text tags are edited; replacing artwork is not currently exposed.
- The tag editor writes supported metadata fields back into the original SAF document.

## Metadata support

Lofty 0.25.5 is used for metadata parsing and writing. The editor exposes title, artist, album, album artist, genre, release date/year, track number, disc number, composer, comment, and lyrics.

Lofty supports metadata for AAC (ADTS), APE, AIFF, FLAC, MP3, MP4, MPC, Opus, Ogg Vorbis, Speex, WAV, and WavPack. The exact writable tag mapping depends on the container. Some tag/container combinations are read-only; Flowery checks Lofty's write support and refuses to write those combinations. Unsupported formats such as formats outside Lofty's supported list cannot be edited through this metadata path.

The editor stages the selected file in the app-private cache directory, asks Lofty to update the tag, then writes the resulting bytes back through the user's Storage Access Framework document permission. A document provider must grant write access. The audio stream itself is not decoded and re-encoded when text tags are changed.

## Audio engine

- Symphonia opens the SAF document file descriptor and decodes compressed audio incrementally in bounded packets/chunks; the compressed file and complete decoded PCM are not copied into a whole-track byte array.
- Rubato 5 performs fixed-ratio streaming sample-rate conversion with one persistent synchronous FFT resampler per track. Resampler history is retained between chunks.
- Flowery enumerates connected USB output devices through Android AudioManager, prefers a USB DAC with reported sample rates, selects the source rate when supported, and binds the AAudio stream to that device ID. If the DAC does not report a rate list, AAudio is probed directly. If the requested rate cannot be opened, Flowery uses the device default stream rate; if the USB route cannot be opened, it falls back to the system output route.
- If the source and selected output rates match, the resampler returns the original PCM samples without conversion.
- The native 31-band ISO one-third-octave graphic equalizer provides independent ±12 dB band controls. Per-channel biquad delay state is retained across decoded chunks, and gain changes are applied without resetting filter history. Native unit tests verify chunk-boundary continuity and finite filter coefficients across common sample rates.
- AAudio requests exclusive, low-latency output and falls back to shared mode if exclusive mode is unavailable.

The Android-reported USB sample-rate list is used as the preferred capability hint, then AAudio validates that the selected device and rate can actually open. In shared mode, Android may still mix or resample internally. This is not proof of the physical DAC's native clock or bit-perfect transport; device firmware and Android audio policy can still affect the signal path. Guaranteed USB-exclusive output, USB Audio Class capability enumeration, hardware-volume control, and hardware-validated bit-perfect output are not implemented.

Bit-perfect playback is incompatible with active EQ, software volume scaling, or any sample-rate conversion. The current path uses floating-point decoded PCM and is therefore not a verified bit-perfect path.

## Library and lyrics

- Multiple Storage Access Framework folder roots can be added, removed, enabled, disabled, and recursively scanned.
- The library scanner does not use MediaStore.
- Sidecar LRC files are discovered and parsed.
- Embedded lyrics, synchronized lyric position updates, remote lyric lookup, and saving downloaded lyrics are not implemented.

## Architecture and exclusions

The Android layer owns the UI, SAF permissions, and library traversal. Rust owns compressed-audio decoding, metadata operations through Lofty, resampling through Rubato, and the 31-band equalizer. C++ streams PCM through AAudio. A dedicated producer thread feeds a fixed-capacity single-producer/single-consumer ring buffer; the AAudio callback only copies available frames, applies output gain, fills underruns with silence, and checks end-of-stream state. It performs no file I/O, decoding, resampling, allocation, or locking.

The project does not use ExoPlayer, AndroidX Media3, Firebase, MediaStore, or Google app services.

## Build

Open the project in Android Studio with JDK 17, Android SDK 35, Android NDK 27.2.12479018, and a Rust toolchain compatible with Rubato 5 (rustc 1.87 or newer). The GitHub Actions workflow is located at `.github/workflows/build.yml`.

The workflow builds Rust native libraries for arm64-v8a, armeabi-v7a, and x86_64, then assembles debug and release APKs. A tag matching `v*` triggers GitHub Release publishing.

Configure these GitHub repository secrets for signed releases:

- `KEYSTORE_BASE64`: Base64-encoded JKS or PKCS12 keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Signing key alias.
- `KEY_PASSWORD`: Signing key password.

Without signing secrets, CI builds an unsigned release APK. Never commit keystores or signing credentials.

## Development status

The player uses incremental decode, bounded PCM buffering, format-level seeking, and ring-buffer flushing. Seeking resets decoder and DSP history, discards preroll samples to the requested source timestamp, and restarts AAudio output when necessary. Native unit tests cover EQ continuity across chunk boundaries and coefficient stability across common sample rates. CI runs these host-side tests before Android cross-compilation and APK assembly. Gapless transitions, multichannel output, artwork replacement, exhaustive USB Audio Class descriptor enumeration, and hardware-verified exclusive transport remain future work. A successful CI build does not replace testing on physical Android devices and DAC hardware.

## Open-source licenses

- Lofty: MIT OR Apache-2.0.
- Rubato: MIT OR Apache-2.0.
- Symphonia and transitive dependencies retain their respective upstream licenses. Review the dependency license set before distributing binaries.

## Attribution

AI-assisted development tools have been used during parts of the development process. Maintainers remain responsible for reviewing, testing, and maintaining project changes.
