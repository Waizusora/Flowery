# Audio Engine

## Playback pipeline

`SAF document → Symphonia decoder → source-rate PCM conversion → bounded Rust PCM ring → AAudio → Android output route`

Flowery supports three Android-managed output targets: AUX, built-in speaker, and Bluetooth. Android's audio system owns the physical route and downstream device policy.

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
