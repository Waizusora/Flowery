# Flowery

Flowery is an Android music player project built with Kotlin, Jetpack Compose, Material 3, and a Rust audio engine.

## Project identity

- Application ID: `com.waizusora.flowery`
- Product name: Flowery
- Android UI and output: Kotlin, Jetpack Compose, Material 3, Android `AAudio`
- Decoder and DSP: Rust through JNI, using Symphonia
- Build automation: GitHub Actions

## Implemented foundation

- Persisted Storage Access Framework folder grants with multiple folder roots.
- Add, remove, enable, disable, and recursively scan selected folders.
- Audio-file discovery without MediaStore.
- Native Symphonia decoding for the codecs and containers enabled by the `all` feature set.
- Native 31-band ISO one-third-octave graphic equalizer with independent ±12 dB band controls.\n- Fixed-rate 48 kHz output conversion using Rubato 5 FFT resampling with its Blackman-Harris anti-aliasing window.\n- Lofty metadata library dependency selected for tag parsing and writing across MP3, FLAC, Ogg Vorbis, Opus, WAV, AIFF, MP4/M4A, AAC, APE, MPC, Speex, and WavPack where the format/tag mapping is writable.
- Native AAudio PCM-float output for mono and stereo playback, requesting exclusive low-latency mode and falling back to shared mode when unavailable.
- Sidecar LRC discovery and timestamp parsing.
- Basic playback controls and software volume.
- GitHub Actions debug/release APK build and tag-based GitHub Release publishing.

## Architecture

The Android layer owns the user interface, Storage Access Framework permissions, and library traversal. Rust owns compressed-audio decoding and the current equalizer DSP implementation. A C++ Android NDK layer streams PCM through AAudio.

The project does not use ExoPlayer, AndroidX Media3, Firebase, or MediaStore. Files are accessed through user-selected document-tree permissions and are not copied into the application library.

## Audio compatibility and current limitations

The current playback path decodes the selected file into memory, converts it to 48 kHz using Rubato fixed-ratio FFT resampling, then sends mono/stereo floating-point PCM to Android `AAudio`. Rubato documents this synchronous FFT path as its recommended high-quality choice for fixed sample-rate conversion. It is a functional initial playback path, not yet a low-memory streaming decoder. Large files can require substantial memory.

Symphonia's `all` feature enables the codecs and container support provided by the selected Symphonia release; it does not guarantee every proprietary, damaged, or uncommon audio format. The current Android output path supports mono and stereo. Multichannel output, gapless playback, seeking, queue management, and background playback are not implemented yet.

AUX, built-in speakers, and Bluetooth use Android's managed media output route and follow system media-volume behavior.

AAudio requests exclusive, low-latency sharing mode and falls back to shared mode if the device or Android audio service cannot provide it. This is not equivalent to guaranteed USB-exclusive access. USB Audio Class transport, DAC hardware-volume reporting/control, device-specific sample-rate negotiation, and verified bit-perfect output are not implemented. Those features require USB device handling, capability negotiation, and hardware validation. Bit-perfect mode must bypass equalization, software gain, resampling, and any sample conversion that changes the source PCM.

The current equalizer is a native 31-band peaking-EQ filter bank using ISO one-third-octave center frequencies from 20 Hz to 20 kHz. Enabling it changes PCM samples and is therefore incompatible with bit-perfect output.

## Library and lyrics

The library scanner recursively traverses enabled folder roots and discovers common audio filename extensions. It also looks for matching sidecar `.lrc` files. The LRC parser supports multiple timestamps per line and sorts timed lines.

The Lofty dependency is present for the metadata editing subsystem; interactive tag editing and writing through Android Storage Access Framework permissions still require implementation and device validation. Embedded lyric extraction, synchronized lyric position updates, lyric-provider lookup, and saving downloaded lyrics are not implemented yet. No remote lyrics service is contacted by the current build.

## Build

Open the project in Android Studio with JDK 17, Android SDK 35, and Android NDK 27.2.12479018. The GitHub Actions workflow is located at `.github/workflows/build.yml`.

The workflow builds Rust native libraries for arm64-v8a, armeabi-v7a, and x86_64, then assembles debug and release APKs. A tag matching `v*` triggers GitHub Release publishing.

Configure these GitHub repository secrets for signed releases:

- `KEYSTORE_BASE64`: Base64-encoded JKS or PKCS12 keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Signing key alias.
- `KEY_PASSWORD`: Signing key password.

Without signing secrets, CI builds an unsigned release APK. Never commit keystores or signing credentials.

## Development status

This is an actively developed foundation. USB exclusive/bit-perfect output, DAC hardware volume, embedded lyrics, remote lyrics lookup, background playback, streaming decode, seeking, and full device compatibility remain future implementation and validation work. A successful CI build does not replace testing on physical Android devices and DAC hardware.

## Attribution

AI-assisted development tools have been used during parts of the development process. Maintainers remain responsible for reviewing, testing, and maintaining project changes.
