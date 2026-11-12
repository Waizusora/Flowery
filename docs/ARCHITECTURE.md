# Architecture

Flowery uses Kotlin/Jetpack Compose for Android integration and a Rust audio engine for decoding and AAudio playback.

## Components

| Component | Responsibility |
| --- | --- |
| `MainActivity.kt` | Activity lifecycle, SAF folder access, and application UI state |
| `FloweryNavigation.kt` | Destination selection and shared navigation state |
| `FloweryPlayer.kt` | Playback generation, output route selection, native session coordination |
| `AndroidOutputRouteResolver.kt` | Resolve AUX, speaker, and Bluetooth Android output devices |
| `PlaybackSourceDescriptor.kt` | SAF descriptor lifetime and decoder extension resolution |
| `NativeAudio.kt` | Kotlin declarations for the Rust JNI API |
| `native/src/streaming.rs` | Incremental Symphonia decode, Rubato resampling, and PCM frame production |
| `native/src/playback_session.rs` | Playback session state, seek epochs, cancellation, and worker coordination |
| `native/src/aaudio.rs` | AAudio NDK stream, output ring, callback, and output lifecycle |
| `native/src/pcm_format.rs` | Transport-neutral signed integer PCM layout validation |

## Playback flow

`SAF descriptor → Symphonia → Rubato when required → bounded PCM ring → AAudio → Android audio system`

Android output routing is managed by the platform. Flowery exposes three preferences: wired AUX, built-in speaker, and Bluetooth. When the preferred device is unavailable, the resolver uses the Android default output route.

## PCM output

The AAudio boundary uses signed integer PCM, not Float32. Flowery requests PCM 16-bit for source depths up to 16-bit and packed PCM 24-bit for higher-depth sources on API 31+, falling back to PCM 16-bit on older Android versions. Decoder and resampler internals may use floating-point samples.

## Removed transport

The application no longer contains USB host permissions, USB DAC discovery, UAC1/UAC2 parsing, direct USB PCM or encoded playback, USB-specific JNI, Rust USB transport modules, C++ libusb, or CMake integration.

## JNI and lifecycle

Rust JNI functions are declared in `NativeAudio.kt` and checked by `scripts/check_jni_contract.py`. Native handles are opaque positive IDs. Playback-session close cancels and joins active workers before releasing decoder and callback-owned output state.

## Verification

CI runs Rust tests, JNI contract checks, Android JVM tests, native Android library builds, and APK packaging. Physical-device testing remains necessary to verify AAudio stability and actual AUX, speaker, and Bluetooth behavior.
