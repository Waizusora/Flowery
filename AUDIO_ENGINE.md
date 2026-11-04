# Audio Engine

## Streaming pipeline

`SAF URI → ParcelFileDescriptor → Symphonia → Rubato (when required) → 31-band EQ → bounded PCM ring → AAudio`

`FloweryPlayer.kt` coordinates the worker. `native/src/streaming.rs` implements incremental Symphonia decoding, Rubato resampling, EQ and format-level seeking. `app/src/main/cpp/aaudio_output.cpp` owns AAudio and the PCM ring buffer.

## Decode and resampling

Symphonia probes the selected document and decodes the default audio track packet by packet. The streaming path retains pending samples rather than assembling the entire decoded track in one PCM array. Mono and stereo are supported by the current output path. Scanner extension recognition does not guarantee that every corresponding codec can be decoded.

Rubato 5 uses a fixed-ratio synchronous FFT resampler. Its instance and filter history persist across chunks. When source and output sample rates match, conversion is bypassed. USB output rates are selected using Android-reported device information where available, then validated by attempting to open AAudio. This is not exhaustive USB Audio Class capability enumeration.

## 31-band ISO equalizer

The band centers are 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000 and 20000 Hz. Each band uses a peaking biquad with gain constrained to -12 dB through +12 dB. Filter state is retained per channel and band across chunks. Runtime gain updates replace coefficients without clearing filter history.

Native tests check chunk-boundary continuity and coefficient finiteness. The project maintainer has also confirmed that the equalizer works in device testing. The Settings → Audio → Information Audio page describes the decode/resample/EQ/output path and displays the current playback status; it does not currently expose per-stage timing or live DSP meters.

## Ring buffer and callback

The configured ring capacity is 16,384 frames. The worker decodes chunks up to 2,048 frames and targets a 4,096-frame low-water mark. The AAudio callback copies available interleaved float frames, applies output gain, clamps samples, fills underruns with silence, and stops after EOF and queue drain. It does not perform file I/O, decoding, resampling, dynamic allocation, or mutex locking. A flush epoch invalidates stale frames after a seek.

AAudio exclusive sharing is requested first; shared mode is attempted if exclusive opening fails. Shared mode may be mixed or resampled by Android.

## Seeking

Seeking flushes the output ring, performs a format-level Symphonia seek, resets decoder and DSP state, reconfigures the resampler, retains decoder preroll where needed to rebuild Rubato history, and discards output before the requested presentation timestamp. Playback paused/playing state is preserved.

## Output volume policy

Speaker, wired AUX/headphones, and Bluetooth use Android's `STREAM_MUSIC` volume. Flowery reads the current system volume when the UI starts and writes slider changes back to that stream; Android remains responsible for device-specific safe-listening limits and any system warning/confirmation UI. Flowery does not impose a fixed 67% ceiling, because safe-volume thresholds and user override behavior are controlled by Android and may vary by device and region.

USB output uses a separate software gain preference keyed to the selected Android USB output identity. A newly encountered DAC defaults to 20%; subsequent slider changes are saved per key. This is software gain in the current float PCM callback, not physical DAC hardware volume control. The current implementation cannot detect whether a DAC exposes physical volume buttons or map those controls to the application.

## Output limitations

Flowery does not claim hardware-verified bit-perfect playback. Floating-point DSP, equalization, software gain and sample-rate conversion can change sample values. AAudio shared mode may introduce system mixing or resampling. USB Audio Class descriptor enumeration, USB permission-to-stream integration, DAC hardware-volume integration, guaranteed exclusive USB transport, native-format PCM passthrough, and physical DAC clock verification are not implemented. The current AAudio path outputs PCM float and can fall back from exclusive to shared mode; therefore it is not bit-perfect. Multichannel output beyond mono/stereo, gapless playback and crossfade are also not implemented.
