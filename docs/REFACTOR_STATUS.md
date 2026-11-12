# Refactor Status

The `trial` branch is focused on three Android-managed output targets:

1. AUX
2. Built-in speaker
3. Bluetooth

Normal playback uses the Rust decoder and Android AAudio output. Source sample rate, channel count, and bit depth are detected from the selected audio stream. Flowery does not resample internally; Android owns downstream conversion and routing.

The application UI and native playback path do not include a USB DAC mode, Equalizer, Bit Perfect mode, or Rubato resampler.

CI covers native Rust tests, JNI contract checks, Android JVM tests, native Android library builds, and APK packaging. Physical-device route behavior and device-specific AAudio format support still require on-device verification.
