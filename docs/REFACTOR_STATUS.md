# Refactor Status

## Current architecture

The trial branch is being simplified to a single Android-managed playback transport.

- Normal playback uses AAudio.
- The application exposes AUX, built-in speaker, and Bluetooth output targets.
- AAudio output requests signed integer PCM: 16-bit for source depths up to 16-bit and packed 24-bit for higher-depth sources on Android API 31+, with 16-bit fallback on older Android versions.
- Rust owns streaming decode, resampling, bounded buffering, playback sessions, and AAudio scheduling.
- Generic PCM layout validation is isolated from transport-specific code in `native/src/pcm_format.rs`.

## Removed from the trial branch

- USB host permission requests and receivers.
- USB DAC discovery, descriptor inspection, profile database, and diagnostic recording.
- UAC1/UAC2 PCM and encoded playback pipelines.
- USB-specific Kotlin classes and tests.
- USB JNI declarations and Rust exports.
- USB descriptor, PCM queue, producer, and encoded transport Rust modules.
- C++ libusb transport and CMake integration.

## Verification still required

- Full CI on the latest trial commit.
- Physical-device playback through wired AUX, built-in speaker, and Bluetooth.
- Route changes while playback is active.
- 16-bit and 24-bit output format acceptance across supported Android versions.
- Seek, pause/resume, stop, repeated start/stop, and underrun behavior.

CI success is software validation only; it does not prove hardware-level output behavior.
