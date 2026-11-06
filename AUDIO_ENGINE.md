# Audio Engine

## Streaming pipeline

`SAF URI → ParcelFileDescriptor → Rust/Symphonia → Rust/Rubato → Rust 31-band EQ → Rust bounded PCM ring → Rust AAudio NDK FFI`

`FloweryPlayer.kt` owns Android document access, route selection, playback lifecycle, seek/pause requests, and service-facing state. For the AAudio route it requests bounded pump operations through JNI; `native/src/streaming.rs` performs incremental Symphonia decoding, Rubato resampling, EQ and format-level seeking, then transfers decoded frames directly into the Rust ring without creating a Java float array. `native/src/aaudio.rs` owns the AAudio NDK stream, PCM ring buffer, callback, output lifecycle, and ring-write API. For direct USB, Kotlin retains Android permission, device/session lifecycle, and the calls that submit data to the C++ libusb transport. Rust owns USB descriptor parsing, the shared `PcmFormat` contract, Symphonia PCM decoding and packing, and the bounded PCM queue.

The JNI surface intentionally exposes the incremental decoder and bounded output operations only. The former whole-file `nativeDecode` API and stateless array-based `nativeResample`/`nativeApplyEqualizer` APIs have been removed, along with their Kotlin declarations and convenience wrapper. This prevents a second, state-resetting DSP route from bypassing the persistent streaming resampler and equalizer state.

## Decode and resampling

Symphonia probes the selected document and decodes the default audio track packet by packet. The streaming path retains pending samples rather than assembling the entire decoded track in one PCM array. Mono and stereo are supported by the current output path. The supported filename-extension registry is maintained in `native/src/library.rs` and queried through JNI by the Kotlin SAF scanner. This is a discovery filter only; it does not guarantee that every corresponding codec can be decoded.

Rubato 5 uses a fixed-ratio synchronous FFT resampler. Its instance and filter history persist across chunks. When source and output sample rates match, conversion is bypassed. The normal AAudio route selects rates from Android output-device capabilities and validates the result when opening AAudio. Direct USB instead derives supported rates from UAC2 clock RANGE responses and confirms the selected rate through native SET_CUR/GET_CUR negotiation.

## 31-band ISO equalizer

The band centers are 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000 and 20000 Hz. Each band uses a peaking biquad with gain constrained to -12 dB through +12 dB. Filter state is retained per channel and band across chunks. Runtime gain updates replace coefficients without clearing filter history.

Native tests check chunk-boundary continuity and coefficient finiteness. The project maintainer has also confirmed that the equalizer works in device testing. The Settings → Audio → Information Audio page describes the decode/resample/EQ/output path and displays the current playback status; it does not currently expose per-stage timing or live DSP meters.

## Ring buffer and callback

The AAudio ring capacity is selected from the negotiated output rate: at least two seconds of PCM and at least 32,768 frames, rounded up to a power of two. The Kotlin coordinator requests native pump chunks of up to 8,192 frames and starts output after a 1.5-second low-water mark, bounded to three quarters of the ring capacity. The Rust AAudio callback copies available interleaved float frames, applies output gain, clamps samples, converts to the selected PCM output format, fills underruns with silence, and stops after EOF and queue drain. It does not perform file I/O, decoding, resampling, dynamic allocation, or mutex locking. The producer and callback share a bounded frame-indexed ring buffer; flush pauses the stream before resetting queue indices.

AAudio exclusive sharing is requested first; shared mode is attempted if exclusive opening fails. Shared mode may be mixed or resampled by Android.

## Direct USB PCM processing

## Unified direct USB PCM path

"PCM Passthrough" describes the common PCM output path, not a promise that every source file can be copied byte-for-byte. Every supported source adapter must produce the negotiated PCM representation and enter the same Rust-owned `PcmFormat`, bounded queue, and C++ libusb isochronous transport.

All supported source formats use Symphonia as the only decoder. PCM containers and supported lossless codecs enter the same PCM producer, bounded queue, and USB transport. The former WAV-specific reader and byte-copy route have been removed. For the strict Bit Perfect candidate path, Symphonia reports source bit depth and codec class; normalized f32 samples are used only for 8/16/24-bit sources, whose integer values are exactly representable in f32. Rust quantizes with a power-of-two scale and bypasses EQ arithmetic when the EQ is flat. This path is not enabled for 32-bit sources.

All decoder-supported containers and codecs use Symphonia to decode into PCM. The decoded source adapter can apply Rubato when the DAC does not accept the source rate, apply DSP/gain as configured, and quantize to the selected signed little-endian valid-bit/subslot layout. This is PCM output through the same engine, but it is not source-bit-perfect when the source is lossy or samples have been transformed. Codec support remains bounded by the configured Symphonia features; a recognized file extension alone does not guarantee decodability.

`native/src/usb_pcm.rs` owns the shared `PcmFormat` contract and bounded six-block queue used by the Symphonia PCM producer and USB writer. Queue capacity, ordering, timeout behavior, clear-on-seek, and close signaling are implemented in Rust. Kotlin retains producer/writer thread lifecycle, pause/seek intent, Record Log orchestration, and submission of format-checked blocks to the libusb session. The queue is bounded by block count; the current producer uses at most 32,768 stereo frames per block.

The retained C++ translation unit remains exclusively responsible for the libusb isochronous transport. No AAudio implementation is moved into or retained in C++.

## Seeking

Seeking flushes the output ring, performs a format-level Symphonia seek, resets decoder and DSP state, reconfigures the resampler, retains decoder preroll where needed to rebuild Rubato history, and discards output before the requested presentation timestamp. Playback paused/playing state is preserved.

## Output volume policy

Speaker, wired AUX/headphones, and Bluetooth use Android's `STREAM_MUSIC` volume. Flowery reads the current system volume when the UI starts and writes slider changes back to that stream; Android remains responsible for device-specific safe-listening limits and any system warning/confirmation UI. Flowery does not impose a fixed 67% ceiling, because safe-volume thresholds and user override behavior are controlled by Android and may vary by device and region.

USB decoded PCM output normally uses a separate per-DAC software gain preference. Strict Bit Perfect mode disables software volume and digital gain entirely; volume changes are rejected while that mode is active. Ducking is also bypassed for the direct USB Bit Perfect path. The transport does not map physical DAC buttons or query hardware volume controls.

## USB diagnostic recording and descriptor profile database

The Audio settings page exposes one USB Diagnostics screen. Its Record Log action starts a bounded capture session; Stop Log ends capture and Save Log exports one text file. There is no separate AAudio Diagnostics page, live-refresh action, or second diagnostic export path.

At the beginning of a recording session, Flowery enumerates connected USB Audio Class devices. For each device with Android USB permission, it reads the raw descriptor block, parses the streaming interfaces, alternate settings, endpoint properties, PCM subslot/bit resolution, advertised sample-rate information, clock-source relationships, and UAC2 clock RANGE responses. The resulting descriptor report is saved in the local SQLite database `usb_dac_profiles.db`, together with the raw descriptor bytes in hexadecimal form, manufacturer/product strings, VID/PID, a SHA-256 descriptor fingerprint, first/last observation times, and observation count. Records are keyed by VID/PID plus descriptor fingerprint so firmware or descriptor-layout variants remain distinguishable.

USB playback events are appended only while Record Log is active. The same recording samples AAudio diagnostics once per second, including ring underruns, hardware XRuns, callback timing, stream format, negotiated rate, device ID, and hardware timestamp information when available. Save Log combines the descriptor/database snapshot, AAudio samples, and direct USB/libusb events. If Android permission is not granted, the log explicitly records that raw descriptors and clock ranges could not be read.

USB Audio Class descriptors do not reliably expose the physical silicon chipset model. Flowery therefore groups entries by VID/PID and descriptor fingerprint and does not label a profile as a confirmed chipset unless that identity is established by an independent source. USB product/manufacturer strings are descriptive metadata, not authoritative chipset identification.

## USB DAC discovery and permission

USB DAC discovery is model-independent. `UsbDacController` enumerates USB Audio Class streaming interfaces and requests Android USB host permission; it does not identify a preferred product by VID/PID or marketing name. During descriptor inspection and session negotiation, the Rust parser derives stereo UAC1 Type-I and UAC2 OUT alternate settings, PCM valid-bit/subslot layouts, endpoints, packet capacity, synchronization, clock-source links, and malformed-descriptor diagnostics. Kotlin performs Android permission, device-connection, and USB control-transfer operations. UAC1 rates are checked against descriptor-advertised rates and use endpoint SET_CUR/GET_CUR when that control is advertised; UAC2 clock RANGE responses are queried from descriptor-linked clock sources and the selected rate is negotiated with SET_CUR/GET_CUR. Descriptor snapshots and observation history are stored locally in `usb_dac_profiles.db`.

Direct USB playback has no WAV-specific path. Every supported source is decoded by Symphonia and enters the shared PCM producer. Bit Perfect mode is selected only when the source is identified as PCM or a supported lossless codec, source depth is 8/16/24 bits, channel count is stereo, and the DAC successfully negotiates the exact source sample rate, valid-bit depth, and subslot width. The mode configures the decoder at the source rate with flat EQ, bypasses EQ arithmetic, forces producer gain to unity, disables software volume and ducking, and does not instantiate the resampler. Now Playing reports `BIT PERFECT` only after the direct USB session has opened and the first PCM transfer has been accepted. 32-bit sources and unsupported codecs remain ordinary PCM and are not labelled Bit Perfect. External digital capture on physical DAC hardware is still required for hardware-level validation.

For non-Bit-Perfect direct USB playback, `FloweryPlayer` uses Symphonia to decode stereo audio to float PCM, configures the stateful Rubato resampler when a supported DAC rate differs from the source rate, applies the configured DSP, and sends the result through the shared format-aware PCM contract. Rust quantizes to the selected profile's valid-bit resolution (8–32 bits) and writes the correct 1–4 byte signed little-endian subslot, including padded layouts such as 24 valid bits in a 32-bit subslot. The same native bounded queue and libusb transport are used for all supported formats. This is processed PCM and is not source-bit-perfect playback.

Direct USB playback uses a per-DAC software volume preference (20% default for a newly seen DAC) so playback remains controllable when the DAC has no physical volume control. The normal decoded USB PCM producer applies a gain ramp before quantization and may apply the 0.25 ducking gain. Strict Bit Perfect mode overrides both with unity gain. Flowery does not yet detect hardware-volume capability or map physical volume keys. The normal Symphonia → Rubato → EQ → AAudio path remains unchanged when USB exclusive mode is disabled.

## Shared PCM format contract

The direct USB routes share one Rust `PcmFormat` contract containing sample rate, channel count, valid-bit resolution, subslot width, signedness, and byte order. All source formats use the Symphonia decoder and publish the resulting PCM through one bounded queue and the same libusb writer. The former WAV-specific byte-copy source has been removed. Bit depth determines sample representation; resampling is required only when the DAC cannot accept the source sample rate or when a different output rate is selected.





## Decoded direct USB pipeline

The decoded direct USB path uses two threads so that the isochronous transport is never starved while the decoder works. The producer thread (`FloweryUsbDecoder`) reads 32,768-frame float blocks, applies gain, quantizes to the negotiated signed little-endian PCM valid-bit/subslot layout, and places each block in a bounded queue of six blocks. The writer thread (the player worker, raised to `THREAD_PRIORITY_AUDIO`) waits until three blocks are queued before the first write, then repeatedly takes a block and passes it to `UsbDacSession.writePcm()`. The producer is the only thread that uses the native decoder handle while it runs and is joined before the handle is closed. Seeks are serviced by the producer: it seeks the decoder, discards queued blocks and increments an epoch counter; the writer flushes the USB transport when it receives the first block of a new epoch. The producer remains idle after reaching the end of the stream so that a seek issued while final blocks are queued is honored. A producer failure is reported on the writer thread through the normal playback-error path.

The USB diagnostic log records `USB_PCM_GAIN` (initial gain and queue configuration), `USB_PREBUFFER_READY`, a `queuedBlocks` value on every `USB_WRITE` line, and `USB_PRODUCER_STARVED` whenever the writer waits more than 5 ms for the next decoded block after playback has started. A log without `USB_PRODUCER_STARVED` entries indicates that decoding keeps ahead of the DAC. The reported playback position still reflects submitted audio and can lead audible output by the transport look-ahead.

## Output limitations

The current direct USB transport supports descriptor-derived stereo UAC1 Type-I and UAC2 playback profiles with signed PCM valid-bit resolutions from 8 through 32 bits and subslot widths from one through four bytes. UAC1 stereo Type-I PCM direct playback includes endpoint sample-frequency SET_CUR/GET_CUR when advertised; devices without that control are limited to descriptor-advertised rates. Strict Bit Perfect mode currently accepts supported lossless 8/16/24-bit stereo sources only when the DAC sample rate, valid-bit depth, and subslot width match exactly. Other decoded sources use the same PCM queue and transport but may be resampled or processed and are not labelled Bit Perfect. Multichannel output, UAC1 formats other than Type-I PCM, 32-bit source-sample-preserving output, and physical-DAC validation remain outstanding. The transport has been exercised against a simulated libusb layer but has not been validated across physical DAC models with USB protocol traces or external digital capture. A successful UAC2 clock readback and completed isochronous transfers are necessary runtime checks, but external measurement is still required before making a hardware-verified bit-perfect claim. The normal AAudio path outputs PCM float and can fall back from exclusive to shared mode; it is not bit-perfect. Gapless playback and crossfade are also not implemented.


## Lyrics parsing

LRC parsing runs in Rust through the native JNI boundary. The parser recognizes one- to two-digit minute fields, two-digit seconds, and optional one- to three-digit fractional seconds; it expands multiple timestamps on a line, removes timestamp tokens from the lyric text, and sorts entries chronologically. Kotlin retains only the UI model adapter and current-line presentation lookup. Sidecar document discovery and file access continue to use Android's Storage Access Framework.


## Playlist parsing

CUE, M3U, and PLS parsing runs in Rust through JNI. The native parser produces structured metadata and playlist entries; Kotlin adapts those values to app models. Charset selection and document-tree path resolution remain in Kotlin because they depend on Android/JVM and SAF APIs.


## Playback queue policy

Next, previous, natural-completion, shuffle, and repeat transition decisions are implemented in `native/src/queue.rs` and exposed through JNI. `PlaybackService` retains Android service state and the URI/metadata arrays required by notifications and media sessions, but delegates queue index transitions to Rust. The native transition function returns an explicit stop result or a replay-current result for repeat-one completion; queue index, queue size, repeat mode, shuffle state, and transition policy are owned by a native Rust queue-state handle. Kotlin retains only an adapter for Android service and notification integration.

## Playback orchestration and queue state

`PlaybackCoordinator` is an Android service adapter backed by a native Rust playback lifecycle-state handle; it forwards platform commands to `FloweryPlayer` and defers native handle destruction until the worker exits. `PlaybackService` routes media-session commands through this coordinator. `PlaybackQueueState` is a thin JNI adapter. Rust owns queue selection, repeat mode, shuffle state, and navigation transitions; the Android service retains URI and metadata collections required by platform notifications and media sessions.

## USB Audio Class descriptor parsing

UAC2 stereo playback alternate-setting selection and playback-terminal clock-source resolution are implemented in `native/src/usb_descriptors.rs`. Android retains USB permission and connection ownership, while the native parser validates descriptor boundaries, endpoint direction/type, channel count, PCM subslot/bit resolution, packet capacity, and terminal-to-clock relationships. Kotlin maps the native JSON result into Android USB session parameters. Descriptor parsing alone does not prove that a device accepts a clock rate or that the resulting signal is bit-perfect; the libusb SET_CUR/GET_CUR check remains mandatory.
