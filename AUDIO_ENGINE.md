# Audio Engine

## Playback pipeline

`SAF document → Symphonia decoder → source-rate PCM conversion → bounded Rust PCM ring → AAudio → Android output route`

Flowery supports three Android-managed output targets: AUX, built-in speaker, and Bluetooth. Android's audio system owns the physical route and downstream device policy.

## Output-route detection and switching

At playback start, `AndroidOutputRouteResolver` inspects Android's currently connected output devices and selects the application target. Wired headset, wired headphones, and analog line output are treated as AUX. Bluetooth A2DP, SCO, and (on Android 12/API 31+) BLE headset/speaker devices are treated as Bluetooth. If neither is available, Flowery selects the built-in speaker. When AUX and Bluetooth are both present, AUX has priority.

`PlaybackService` registers an `AudioDeviceCallback` and compares output-device snapshots after device additions or removals. If a monitored device set changes while a track is playing or paused, the service captures the current position and track segment, then asks `PlaybackCoordinator` to reopen the output. The new stream resumes from the captured position if it was playing, or remains paused if it was paused. The route change does not require selecting another track.

The service logs `OUTPUT_ROUTE_REOPEN` when it begins this operation and `OUTPUT_ROUTE_REOPEN_FAILURE` if reopening fails. AAudio device selection is checked against the requested device; failure to select or open the requested device is reported rather than silently treated as a successful route change.

The application route resolver exposes only AUX, built-in speaker, and Bluetooth. Android may still apply system routing and downstream processing.

## Source format detection

The decoder reads the selected stream's sample rate, channel count, and coded bit depth from codec/container metadata. Playback is configured using that detected source sample rate. No fixed 44.1/48 kHz or 96 kHz output profile is selected.

## Sample-rate handling

Flowery does not perform application-side sample-rate conversion. The decoded stream is passed to AAudio at its source rate. Android may mix or resample the stream downstream to match the active output device and system audio policy.

## PCM output

The decoder uses floating-point working samples internally where required by Symphonia. At the AAudio boundary, Flowery converts samples to a supported signed-integer PCM representation. Float32 is not requested as the AAudio output format.

## Scheduling and buffering

The decode producer runs away from the real-time AAudio callback. It writes interleaved samples to a bounded ring. The callback consumes available frames, applies output gain, converts to the configured integer PCM representation, fills underruns with silence, and stops after EOF and queue drain. The callback does not perform file I/O, decoding, or blocking synchronization.

## Playback control

The native playback session owns decode/output worker lifecycle, pause/resume, seek epochs, segment bounds, and end-of-stream reporting. Android controls AUX, speaker, and Bluetooth device routing.
