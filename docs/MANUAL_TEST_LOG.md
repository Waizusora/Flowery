# Manual Test Log

## Current trial-branch behavior

The trial branch uses three Android-managed output targets: AUX, built-in speaker, and Bluetooth. At playback start, Flowery detects the connected Android output device and resolves the corresponding target. AUX takes priority over Bluetooth; the built-in speaker is the fallback.

The playback service monitors Android output-device changes. When the monitored device snapshot changes during playback or while paused, Flowery reopens the active track at the captured position and restores its prior playing/paused state. The user-reported AUX switching issue is confirmed resolved; this record reflects the reported result and does not claim a broader device compatibility matrix.

Playback detects the source stream's sample rate, channel count, and bit depth, then requests AAudio output at the source sample rate using signed-integer PCM. Flowery does not resample internally; Android may perform downstream resampling and device conversion.

## Automated validation

GitHub Actions success verifies the configured software build and automated test jobs only. It does not prove physical-device output, route switching across all devices, or device-specific PCM format support.

## Device validation still recommended

- Verify playback through AUX on a physical Android device, including unplug/replug while playing and while paused.
- Verify playback through the built-in speaker.
- Verify playback through Bluetooth, including supported A2DP/SCO/BLE device types.
- Verify source-rate playback requests for multiple sample rates and source bit depths.
- Confirm Android-managed downstream conversion when the active output device cannot consume the source format directly.
- Verify pause, resume, seek, and track completion on each output route.

Do not infer a complete device-compatibility result from the reported AUX fix or from CI alone.
