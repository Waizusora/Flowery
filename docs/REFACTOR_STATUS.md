# Refactor Status

The `trial` branch is focused on three Android-managed output targets:

1. AUX
2. Built-in speaker
3. Bluetooth

Normal playback uses the Rust decoder and Android AAudio output. Source sample rate, channel count, and bit depth are detected from the selected audio stream. Flowery does not resample internally; Android owns downstream conversion and routing.

Android playback code detects the currently connected output route when playback starts. The service also monitors output-device additions and removals and reopens the current track when the monitored device snapshot changes, preserving the playback position and playing/paused state. The user-reported AUX route-switching issue has been confirmed resolved.

The now-playing lyrics panel uses the active Soft Rose theme container color rather than album-art-derived color. Static `LYRICS LRC` and `Synced Lyric` labels were removed. Timestamped sidecar LRC remains the synchronized lyric source; the player currently highlights lines, not individual words, and has no separate plain-text lyrics mode.

The application UI and native playback path do not include a USB DAC mode, Equalizer, Bit Perfect mode, or Rubato resampler.

CI covers native Rust tests, JNI contract checks, Android JVM tests, native Android library builds, and APK packaging. Physical-device route behavior and device-specific AAudio format support still require broader on-device verification.
