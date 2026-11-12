# Manual Test Log

## Current trial-branch validation

The trial branch uses three Android-managed output targets: AUX, built-in speaker, and Bluetooth. Playback detects the source stream's sample rate, channel count, and bit depth, then requests AAudio output at the source sample rate using signed-integer PCM. Flowery does not resample internally; Android may perform downstream resampling and device conversion.

GitHub Actions success verifies the configured software build and automated test jobs only. It does not prove physical-device output, route switching, or device-specific PCM format support.

## Device validation still required

- Verify playback through AUX on a physical Android device.
- Verify playback through the built-in speaker.
- Verify playback through Bluetooth.
- Verify source-rate playback requests for multiple sample rates and source bit depths.
- Confirm Android-managed downstream conversion when the active output device cannot consume the source format directly.
- Verify pause, resume, seek, and track completion on each output route.

No physical-device result is claimed in this log until a reproducible test report is recorded.
