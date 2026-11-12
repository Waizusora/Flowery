# Flowery Architecture

Flowery is an Android local-audio player. The Android application is implemented in Kotlin/Jetpack Compose; the audio engine is implemented in Rust and integrated through JNI.

## Runtime components

- **Android/Kotlin:** library access through the Storage Access Framework, metadata presentation, queue and playback controls, Android audio focus, and output-route selection.
- **Rust:** source-format inspection, Symphonia demuxing/decoding, PCM conversion, bounded buffering, playback-session lifecycle, and seek scheduling.
- **AAudio:** Android NDK output for the three supported targets: AUX, built-in speaker, and Bluetooth.

## Playback path

`SAF document → Symphonia decode → integer PCM conversion → bounded PCM ring → AAudio → Android audio system`

Flowery detects the source sample rate, channel count, and bit depth from the selected audio stream. It requests AAudio output at the source sample rate and chooses a supported signed-integer PCM representation for the output boundary. Flowery does not resample internally; Android owns any downstream mixing, resampling, or device-format conversion.

The internal decoder may use floating-point working samples. The AAudio output format is configured as signed integer PCM, not Float32.

## Output routing

The route resolver selects AUX, the built-in speaker, or Bluetooth using Android's output-device inventory. If the requested target is unavailable, Android's default output device remains the fallback.

## Native boundary

The PCM format contract is transport-neutral and lives in `native/src/pcm_format.rs`. Native audio output is provided by the Android AAudio backend. There is no application-owned USB transport, USB permission/discovery flow, or C++ audio transport layer.
