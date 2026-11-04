# Flowery

Flowery is an Android music player project built with Kotlin, Jetpack Compose, Material 3, and a Rust audio engine.

## Project identity

- Application ID: `com.waizusora.flowery`
- Product name: Flowery
- Android UI and output: Kotlin, Jetpack Compose, Material 3, Android `AudioTrack`
- Decoder and DSP: Rust through JNI, using Symphonia
- Build automation: GitHub Actions

## Implemented foundation

- Persisted Storage Access Framework folder grants with multiple folder roots.
- Add, remove, enable, disable, and recursively scan selected folders.
- Audio-file discovery without MediaStore.
- Native Symphonia decoding for the codecs and containers enabled by the `all` feature set.
- Native three-band bass, midrange, and treble equalization.
- Android PCM-float streaming through `AudioTrack` for mono and stereo output.
- Sidecar LRC discovery and timestamp parsing.
- Basic playback controls and software volume.
- GitHub Actions debug/release APK build and tag-based GitHub Release publishing.

## Architecture

The Android layer owns the user interface, Storage Access Framework permissions, library traversal, and Android output routing. Rust owns compressed-audio decoding and the current equalizer DSP implementation.

The project does not use ExoPlayer, AndroidX Media3, Firebase, or MediaStore. Files are accessed through user-selected document-tree permissions and are not copied into the application library.

## Audio compatibility and current limitations

The current playback path decodes the selected file into memory and sends mono/stereo floating-point PCM to Android `AudioTrack`. It is a functional initial playback path, not yet a low-memory streaming decoder. Large files can require substantial memory.

Symphonia's `all` feature enables the codecs and container support provided by the selected Symphonia release; it does not guarantee every proprietary, damaged, or uncommon audio format. The current Android output path supports mono and stereo. Multichannel output, gapless playback, seeking, queue management, and background playback are not implemented yet.

AUX, built-in speakers, and Bluetooth use Android's managed media output route and follow system media-volume behavior.

USB exclusive access, USB Audio Class transport, DAC hardware-volume reporting/control, sample-rate negotiation, and verified bit-perfect output are not implemented by the current `AudioTrack` backend. Android `AudioTrack` must not be described as USB-exclusive or bit-perfect. Those features require a separate USB Audio backend, device capability negotiation, and hardware validation. Bit-perfect mode must bypass equalization, software gain, resampling, and any sample conversion that changes the source PCM.

The current equalizer is a native three-band biquad processor. Enabling it changes PCM samples and is therefore incompatible with bit-perfect output.

## Library and lyrics

The library scanner recursively traverses enabled folder roots and discovers common audio filename extensions. It also looks for matching sidecar `.lrc` files. The LRC parser supports multiple timestamps per line and sorts timed lines.

Embedded lyric extraction, synchronized lyric position updates, lyric-provider lookup, and saving downloaded lyrics are not implemented yet. No remote lyrics service is contacted by the current build.

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
