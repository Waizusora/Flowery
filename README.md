# Flowery

Flowery is a native Android music player project built with Kotlin, Jetpack Compose, Material 3, and a Rust audio engine.

## Project identity

- Application ID: `com.waizusora.flowery`
- Product name: Flowery
- Android UI: Kotlin, Jetpack Compose, Material 3
- Audio engine: Rust through JNI
- Decoder: Symphonia
- Build automation: GitHub Actions

## Architecture

The Android layer owns the user interface, folder permissions, playback controls, output-device discovery, and Android audio-route integration. The Rust layer owns audio decoding, DSP, sample conversion, and the native playback pipeline.

The project does not use ExoPlayer, AndroidX Media3, Firebase, or MediaStore. Audio files are discovered through user-selected Storage Access Framework tree permissions and persisted folder grants.

## Audio output and compatibility

Flowery is designed with separate output paths:

- Android-managed output for AUX, built-in speakers, and Bluetooth. These routes follow the Android system volume.
- USB DAC output with device capability detection and a native USB transport implementation.
- Bit-perfect mode must bypass DSP, software gain, resampling, and any format conversion that would alter PCM samples.
- DAC hardware volume and software-controlled DAC volume are separate modes. Hardware volume requires a device-specific control interface; software volume is used only when hardware volume is unavailable or disabled.

Exclusive USB access, bit-perfect playback, hardware volume control, and supported formats depend on the Android device, USB host implementation, DAC descriptors, permissions, and native backend. Unsupported combinations must be reported instead of silently claiming bit-perfect playback.

## Library

The library is based on persisted folder tree permissions. It supports multiple roots, adding and removing roots, full scans, incremental refreshes, and embedded or sidecar LRC lyrics. Folder names and scan state are stored in the app database; audio files are not copied into the application.

## Lyrics

The lyric pipeline supports sidecar `.lrc` files and embedded lyric metadata. A remote lyrics provider must be configured explicitly before network lookup is enabled. Lyrics can be cached locally for offline use.

## Build

Open the project in Android Studio with a compatible Android SDK and JDK. The GitHub Actions workflow is located at `.github/workflows/build.yml`.

Release signing uses GitHub Actions repository secrets/environment secrets:

- `KEYSTORE_BASE64`: Base64-encoded JKS or PKCS12 keystore.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Signing key alias.
- `KEY_PASSWORD`: Signing key password.

Do not commit keystores, signing credentials, or generated signing files. If signing secrets are absent, CI should build an unsigned release APK rather than fail the debug/test pipeline.

## Development status

This repository is the starting implementation scaffold. Device-dependent USB exclusive output, hardware DAC volume, complete format coverage, and release validation require implementation and testing against real Android devices and DAC hardware. Symphonia support is limited to the codecs and container features enabled in the native build.

## Attribution

AI-assisted development tools have been used during parts of the development process. Maintainers remain responsible for reviewing, testing, and maintaining project changes.
