# Flowery Architecture

Flowery is an Android music player with a Kotlin/Compose application layer and a Rust audio engine. The application has one normal playback transport: Android AAudio.

## Ownership boundaries

- **Kotlin / Android:** Compose UI, Activity and service lifecycle, SAF document access, Android output-device discovery, audio focus, system volume, MediaSession, notifications, and user-facing playback state.
- **Rust:** Symphonia demuxing/decoding, Rubato resampling, bounded PCM queues, playback-session lifecycle, seek scheduling, and the AAudio NDK backend.
- **C++:** No application-owned C++ audio or USB transport layer is required.

## Playback path

`SAF document → Rust/Symphonia → Rust/Rubato → bounded PCM ring → Rust AAudio NDK backend → Android audio system`

The output route resolver maps the selected AUX, speaker, or Bluetooth preference to an Android output-device ID. Android retains control of the actual route and downstream audio processing.

## Native integration

`NativeAudio.kt` declares the Rust JNI interface. `scripts/check_jni_contract.py` compares those declarations with Rust JNI exports. Native handles are opaque positive IDs; create/close operations are paired, and session teardown cancels and joins worker activity before releasing shared state.

## PCM output contract

AAudio is configured for signed integer PCM. Flowery requests PCM 16-bit for source depths up to 16-bit and packed PCM 24-bit for higher-depth sources on Android API 31+, with a PCM 16-bit fallback on older Android versions. Internal decode and resampling may use floating-point samples; Float32 is not selected as the AAudio output format.

The transport-neutral PCM layout contract is in `native/src/pcm_format.rs`. USB/UAC descriptor handling, USB permission/discovery, libusb, and direct USB playback have been removed.

## Validation

CI runs native Rust tests, JNI contract checks, Android JVM unit tests, native Android library builds for the configured ABIs, and debug/release APK packaging. Hardware verification remains necessary for AAudio behavior and actual AUX, speaker, and Bluetooth routing.
