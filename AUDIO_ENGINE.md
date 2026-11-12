# Audio Engine

## Output architecture

Flowery has one normal playback transport: Android AAudio. The app resolves a preferred Android output device for one of three user-facing targets—wired AUX, built-in speaker, or Bluetooth—and passes the selected Android device ID to the AAudio stream builder. Android retains responsibility for the physical route, system volume policy, device changes, and downstream mixing.

`SAF document → Symphonia decoder → Rubato resampler when needed → bounded Rust PCM ring → AAudio callback → Android output route`

There is no direct USB output path. Flowery contains no USB host permission flow, USB device discovery, UAC1/UAC2 descriptor parsing, libusb transport, encoded USB passthrough, or USB-specific JNI.

## Decode and resampling

Symphonia probes the selected document and incrementally decodes the default audio track. The decoder retains pending samples between reads rather than materializing a complete decoded track. The SAF file descriptor remains owned until the native decoder and playback session have stopped using it.

Rubato 5 uses a fixed-ratio synchronous FFT resampler. Its instance and filter history persist across chunks. When source and output sample rates match, conversion is bypassed. Output-rate selection uses the selected Android output device's reported sample-rate capabilities; AAudio validates the requested stream configuration when opening the stream.

## Integer PCM output

The Android output contract is integer PCM only:

- Source depth up to 16-bit requests signed PCM 16-bit.
- Higher-depth sources request packed signed PCM 24-bit on Android API 31 and newer.
- Older Android versions use signed PCM 16-bit.
- Float32 is not selected as the AAudio output format.

The decoder and resampler may use floating-point samples internally. AAudio performs the final integer conversion at the output boundary. Android may subsequently mix, resample, or otherwise process audio according to the active device and system policy; Flowery does not claim bit-perfect hardware output.

The generic native PCM layout contract is isolated in `native/src/pcm_format.rs`. It is independent of any transport or device protocol.

## AAudio ring buffer and callback

The AAudio ring capacity is selected from the negotiated output rate: at least two seconds of PCM and at least 32,768 frames, rounded up to a power of two. Ring capacity is scheduling headroom, not a target fill level. The Rust decode producer limits queued PCM to approximately 250 ms at normal music sample rates, capped by ring capacity. Output starts after a low-water mark of approximately 100 ms, bounded by half the ring capacity and the queue target.

The AAudio callback copies available interleaved samples, applies output gain, converts to the configured integer PCM representation, fills underruns with silence, and stops after EOF and queue drain. It does not perform file I/O, decoding, resampling, or blocking synchronization. The producer and callback share a bounded ring buffer; flush pauses the stream before resetting queue indices.

## Playback sessions and seeking

A native playback session owns the decoder reference, lifecycle phase, cancellation state, seek epoch, pending seek request, and worker coordination. Seeking pauses output, invalidates and joins the previous decode worker, performs a format-level Symphonia seek, flushes the output ring, starts a replacement worker, and restarts output after the low-water mark is reached. Stale worker output is rejected by the session epoch.

Pause, resume, stop, and close are coordinated through native session commands. Teardown stops and joins workers before releasing decoder or callback-owned output resources.

## Android routing

The settings screen offers three logical output targets:

- **AUX:** wired headphones, headsets, and analog line output.
- **Speaker:** the built-in device speaker.
- **Bluetooth:** Android Bluetooth audio output devices.

These are preferences for Android-managed routing, not claims that a specific physical device is currently connected. If the requested target is unavailable, Flowery lets Android use the system default route. Route changes are resolved against current Android output-device information before opening AAudio.

## Diagnostics and validation

Runtime AAudio diagnostics include stream state, actual device ID, sample rate, channel count, output format, sharing mode, underruns, hardware XRuns, callback timing, and timestamps when the platform provides them. CI verifies Rust tests, JNI declarations, Android JVM tests, native library builds, and APK packaging. Physical-device tests are still required for route switching, Bluetooth transitions, wired output behavior, underrun resistance, seek behavior, and device-specific packed 24-bit support.
