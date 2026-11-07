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

All supported source formats use Symphonia as the demuxer and decoder. PCM containers and supported lossless codecs enter the same PCM producer, bounded queue, and USB transport. The native integer path extracts typed decoder samples and packs them directly; lossy or unsupported decoder representations use the float32 processing path. The native path is not a raw compressed-file copy and does not bypass decoding.

All decoder-supported containers and codecs use Symphonia to decode into PCM. The decoded source adapter can apply Rubato when the DAC does not accept the source rate, apply DSP/gain as configured, and quantize to the selected signed little-endian valid-bit/subslot layout. This is PCM output through the same engine, but it is not source-bit-perfect when the source is lossy or samples have been transformed. Codec support remains bounded by the configured Symphonia features; a recognized file extension alone does not guarantee decodability.

`native/src/usb_pcm.rs` owns the shared `PcmFormat` contract and bounded six-block queue used by the Symphonia PCM producer and USB writer. Queue capacity, ordering, timeout behavior, clear-on-seek, and close signaling are implemented in Rust. Kotlin retains producer/writer thread lifecycle, pause/seek intent, Record Log orchestration, and submission of format-checked blocks to the libusb session. The queue is bounded by block count; the current producer uses at most 32,768 stereo frames per block.

The retained C++ translation unit remains exclusively responsible for the libusb isochronous transport. No AAudio implementation is moved into or retained in C++.

## Seeking

Seeking flushes the output ring, performs a format-level Symphonia seek, resets decoder and DSP state, reconfigures the resampler, retains decoder preroll where needed to rebuild Rubato history, and discards output before the requested presentation timestamp. Playback paused/playing state is preserved.

## Output volume policy

Flowery uses three output-volume profiles. Profile 1 routes speaker, wired AUX/headphones, and Bluetooth through Android `STREAM_MUSIC`; Android remains responsible for safe-listening limits and system warning UI. Profile 2 is selected when the USB DAC advertises a master-channel UAC Feature Unit Volume Control. Flowery reads and writes the Feature Unit's signed 1/256 dB volume value, and the UI polls the current hardware value so physical DAC volume buttons and the software slider remain synchronized. Profile 3 is used when no usable Feature Unit volume control is advertised; it applies a per-DAC software gain, initially 20% for a new DAC, and persists later changes. Profile 3 changes PCM samples and therefore cannot be labelled Bit Perfect. Strict Bit Perfect mode bypasses software gain and ducking; profile 2 may still adjust the DAC's own output volume without changing the PCM payload. Vendor-specific volume controls that are not exposed as standard UAC Feature Units are not inferred.

## USB DAC descriptor information panel

The bottom-navigation USB icon opens a dedicated descriptor-information dialog instead of toggling Direct USB mode or showing a generic status message. The panel requests Android USB host permission when needed, then reads the connected device's raw configuration descriptors and presents the parsed USB Audio Class interfaces, alternate settings, endpoint transfer and synchronization properties, PCM channel/subslot/bit-resolution capabilities, advertised sample rates, and readable UAC2 clock RANGE results. It also reports manufacturer/product strings, VID/PID, standard Feature Unit master-volume capability, the local descriptor-profile fingerprint, and the observed-profile database record. A readable hardware master-volume value is appended when the device exposes one through the supported standard control path.

The report does not infer a silicon chipset from the product name or VID/PID, and descriptor inspection is not a playback test. Opening the panel does not enable or disable Direct USB mode and does not certify bit-perfect output.

## USB diagnostic recording and descriptor profile database

The Audio settings page exposes one USB Diagnostics screen. Its Record Log action starts a bounded capture session; Stop Log ends capture and Save Log exports one text file. There is no separate AAudio Diagnostics page, live-refresh action, or second diagnostic export path.

At the beginning of a recording session, Flowery enumerates connected USB Audio Class devices. For each device with Android USB permission, it reads the raw descriptor block, parses the streaming interfaces, alternate settings, endpoint properties, PCM subslot/bit resolution, advertised sample-rate information, clock-source relationships, and UAC2 clock RANGE responses. The resulting descriptor report is saved in the local SQLite database `usb_dac_profiles.db`, together with the raw descriptor bytes in hexadecimal form, manufacturer/product strings, VID/PID, a SHA-256 descriptor fingerprint, first/last observation times, and observation count. Records are keyed by VID/PID plus descriptor fingerprint so firmware or descriptor-layout variants remain distinguishable.

USB playback events are appended only while Record Log is active. The same recording samples AAudio diagnostics once per second, including ring underruns, hardware XRuns, callback timing, stream format, negotiated rate, device ID, and hardware timestamp information when available. Save Log combines the descriptor/database snapshot, AAudio samples, and direct USB/libusb events. If Android permission is not granted, the log explicitly records that raw descriptors and clock ranges could not be read.

USB Audio Class descriptors do not reliably expose the physical silicon chipset model. Flowery therefore groups entries by VID/PID and descriptor fingerprint and does not label a profile as a confirmed chipset unless that identity is established by an independent source. USB product/manufacturer strings are descriptive metadata, not authoritative chipset identification.

## USB DAC discovery and permission

USB DAC discovery is model-independent. `UsbDacController` enumerates USB Audio Class streaming interfaces and requests Android USB host permission; it does not identify a preferred product by VID/PID or marketing name. The Rust parser derives UAC1 Type-I and UAC2 OUT alternate settings for 1–8 channels, PCM valid-bit/subslot layouts, data and explicit feedback endpoints, packet capacity, synchronization type, clock-source links, and malformed-descriptor diagnostics. The separate encoded-profile validator also checks UAC1 Type-II and Type-III alternate settings, exact format tags, descriptor-advertised source rates, endpoint direction/type, packet capacity, and optional sampling-frequency control. Kotlin performs Android permission, device-connection, and USB control-transfer operations. UAC1 rates are checked against descriptor-advertised rates and use endpoint SET_CUR/GET_CUR when advertised; UAC2 clock RANGE responses are queried from descriptor-linked clock sources and the selected rate is negotiated with SET_CUR/GET_CUR. Asynchronous endpoints with explicit feedback use feedback-derived packet sizing; adaptive and synchronous endpoints use their descriptor-defined nominal scheduling. Descriptor snapshots and observation history are stored locally in `usb_dac_profiles.db`.

Direct USB playback has no WAV-specific path. Every supported source is decoded by Symphonia and enters the shared PCM producer. The Rust reader selects native integer PCM only when the decoder exposes exact integer samples and the source depth is supported (8/16/20/24/32 bits), the negotiated DAC rate/channels/valid bits/subslot match exactly, the channel mapping is unchanged, the EQ is flat, software gain is unity, and no resampler is active. Unsupported typed decoder output falls back to float32 before any native block is published. Lossy sources always use float32. Kotlin reads the producer's actual native-path state and reports `BIT PERFECT` only after the first native integer PCM block has been accepted by USB. Changing DSP or software gain converts any queued native remainder to float32 before processing and withdraws the Bit Perfect status. External digital capture on physical DAC hardware is still required for hardware-level validation.

For non-Bit-Perfect direct USB playback, `FloweryPlayer` uses Symphonia to decode stereo audio to float PCM, configures the stateful Rubato resampler when a supported DAC rate differs from the source rate, applies the configured DSP, and sends the result through the shared format-aware PCM contract. Rust quantizes to the selected profile's valid-bit resolution (8–32 bits) and writes the correct 1–4 byte signed little-endian subslot, including padded layouts such as 24 valid bits in a 32-bit subslot. The same native bounded queue and libusb transport are used for all supported formats. This is processed PCM and is not source-bit-perfect playback.

Direct USB playback selects a per-device volume profile from descriptor capabilities. A standard UAC Feature Unit master Volume Control selects hardware-synchronized volume: Flowery reads/writes the DAC's signed 1/256 dB volume value, and the UI receives periodic GET_CUR updates so hardware buttons and the slider stay synchronized. Without that control, software-only volume is used with a 20% initial gain for a newly seen DAC and a gain ramp before PCM quantization. Software volume and ducking are bypassed for strict Bit Perfect playback. Vendor-specific volume controls are not inferred. The normal Symphonia → Rubato → EQ → AAudio path remains unchanged when USB exclusive mode is disabled.

## Shared PCM format contract

The direct USB routes share one Rust `PcmFormat` contract containing sample rate, channel count, valid-bit resolution, subslot width, signedness, and byte order. All source formats use the Symphonia decoder and publish the resulting PCM through one bounded queue and the same libusb writer. The former WAV-specific byte-copy source has been removed. Bit depth determines sample representation; resampling is required only when the DAC cannot accept the source sample rate or when a different output rate is selected.





## Decoded direct USB pipeline

The decoded direct USB path uses two threads so that the isochronous transport is never starved while the decoder works. The Rust producer reads bounded 32,768-frame chunks. It publishes native integer samples directly when the strict eligibility gate passes; otherwise it uses float32, applies gain and DSP, and quantizes to the negotiated signed little-endian PCM valid-bit/subslot layout. Both modes place blocks in the same bounded queue of six blocks. The writer thread (the player worker, raised to `THREAD_PRIORITY_AUDIO`) waits until three blocks are queued before the first write, then repeatedly takes a block and passes it to `UsbDacSession.writePcm()`. The producer is the only thread that uses the native decoder handle while it runs and is joined before the handle is closed. Seeks are serviced by the producer: it seeks the decoder, discards queued blocks and increments an epoch counter; the writer flushes the USB transport when it receives the first block of a new epoch. The producer remains idle after reaching the end of the stream so that a seek issued while final blocks are queued is honored. A producer failure is reported on the writer thread through the normal playback-error path.

The USB diagnostic log records `USB_PCM_GAIN` (initial gain and queue configuration), `USB_PREBUFFER_READY`, a `queuedBlocks` value on every `USB_WRITE` line, and `USB_PRODUCER_STARVED` whenever the writer waits more than 5 ms for the next decoded block after playback has started. A log without `USB_PRODUCER_STARVED` entries indicates that decoding keeps ahead of the DAC. The reported playback position still reflects submitted audio and can lead audible output by the transport look-ahead.

## Output limitations

The decoded direct USB path supports descriptor-derived UAC1 Type-I and UAC2 PCM profiles with 1–8 channels, signed PCM valid-bit resolutions from 8 through 32 bits, and subslot widths from one through four bytes. When direct USB/Bit Perfect mode is enabled, `FloweryPlayer` detects AC-3 sources and prefers a validated UAC1 Type-III IEC 61937 profile, falling back to a validated UAC1 Type-II AC-3 profile. Type-II requires `wFormatTag=0x1002`, a 1536-sample encoded-frame descriptor, a matching source sample rate, and a descriptor maximum bitrate that covers the source; each complete AC-3 frame is sent in full-size USB packets, an optional short final packet, and a zero-length transfer delimiter, then paced by its demuxed frame duration. Type-III requires `wFormatTag=0x2001`, two 16-bit subframes, and an advertised source rate; Rust validates each AC-3 frame and builds a 6144-byte IEC 61937 burst (`Pa/Pb/Pc/Pd`, AC-3 data type `0x0001` plus the frame `bsmod` bits in `Pc`, payload bit length, 16-bit little-endian payload word ordering, and zero padding). C++ schedules Type-III packets as complete 4-byte stereo sample frames at the source carrier rate. Encoded seek clears staged/queued transfers and resumes from a demuxer packet boundary; pause drains submitted data before holding, and drain waits for the Type-II delimiter/frame transfer or all Type-III carrier packets to complete. The encoded writer is a separate C++/libusb mode and never calls `nativeWriteIsochronous()` or the PCM queue. Asynchronous encoded endpoints are accepted only when the selected alternate setting contains an explicit isochronous feedback IN endpoint. The encoded libusb session owns a separate feedback transfer and event-driven scheduler: Type-III carrier packet sizes use the measured 10.14 (full-speed) or 16.16 (high-speed) samples-per-feedback-interval value with a fractional phase accumulator; Type-II AC-3 frame deadlines are derived from 1536 samples at the measured feedback rate. A stale or missing feedback sample fails the encoded write rather than silently reverting to nominal timing. Adaptive and synchronous endpoints retain nominal scheduling. MPEG, DTS, AAC, and other encoded formats are not enabled. Physical-DAC protocol traces and external digital capture remain outstanding.


## Lyrics parsing

LRC parsing runs in Rust through the native JNI boundary. The parser recognizes one- to two-digit minute fields, two-digit seconds, and optional one- to three-digit fractional seconds; it expands multiple timestamps on a line, removes timestamp tokens from the lyric text, and sorts entries chronologically. Kotlin retains only the UI model adapter and current-line presentation lookup. Sidecar document discovery and file access continue to use Android's Storage Access Framework.


## Playlist parsing

CUE, M3U, and PLS parsing runs in Rust through JNI. The native parser produces structured metadata and playlist entries; Kotlin adapts those values to app models. Charset selection and document-tree path resolution remain in Kotlin because they depend on Android/JVM and SAF APIs.


## Playback queue policy

Next, previous, natural-completion, shuffle, and repeat transition decisions are implemented in `native/src/queue.rs` and exposed through JNI. `PlaybackService` retains Android service state and the URI/metadata arrays required by notifications and media sessions, but delegates queue index transitions to Rust. The native transition function returns an explicit stop result or a replay-current result for repeat-one completion; queue index, queue size, repeat mode, shuffle state, and transition policy are owned by a native Rust queue-state handle. Kotlin retains only an adapter for Android service and notification integration.

## Playback orchestration and queue state

`PlaybackCoordinator` is an Android service adapter backed by a native Rust playback lifecycle-state handle; it forwards platform commands to `FloweryPlayer` and defers native handle destruction until the worker exits. `PlaybackService` routes media-session commands through this coordinator. `PlaybackQueueState` is a thin JNI adapter. Rust owns queue selection, repeat mode, shuffle state, and navigation transitions; the Android service retains URI and metadata collections required by platform notifications and media sessions.

## USB Audio Class descriptor parsing

UAC1/UAC2 playback alternate-setting selection (up to eight channels) and UAC2 playback-terminal clock-source resolution are implemented in `native/src/usb_descriptors.rs`. Android retains USB permission and connection ownership, while the native parser validates descriptor boundaries, endpoint direction/type, channel count, PCM subslot/bit resolution, packet capacity, and terminal-to-clock relationships. Kotlin maps the native JSON result into Android USB session parameters. Descriptor parsing alone does not prove that a device accepts a clock rate or that the resulting signal is bit-perfect; the libusb SET_CUR/GET_CUR check remains mandatory.


### Encoded queue ownership and JNI packet envelope

The encoded queue is reference-counted across JNI handles. A producer retains its own shared queue reference, so releasing the Kotlin queue handle does not invalidate a producer that is still alive. The packet envelope returned to Kotlin is 27 bytes before the opaque payload: framing kind (1 byte), format/data-type code (2-byte little-endian), sequence (8-byte little-endian), presentation timestamp in microseconds (8-byte little-endian; zero means unavailable), and duration in microseconds (8-byte little-endian). The envelope describes metadata only; it does not establish codec compatibility or USB wire framing.


## Native integer PCM and Bit Perfect eligibility

`native/src/native_pcm.rs` extracts integer samples from Symphonia's typed decoded planes without a float conversion, verifies left-aligned padding bits, and packs signed samples into the negotiated little-endian USB subslot. It supports 8-, 16-, 20-, 24-, and 32-bit integer representations, including packed 24-bit and padded 24-bit-in-32 output. The FLAC decoder's S32 representation is restored to its valid-bit width only after checking that unused low bits are zero.

`StreamDecoder::read_usb_pcm()` selects the native path in Rust only when the source is classified as lossless, the decoder exposes a supported integer plane, the source and output rate/channel/valid-bit formats match, the channel mapping is unchanged, the resampler is absent, EQ is flat, and software gain is unity. It sends the resulting bytes through the same Rust bounded USB PCM queue and the existing C++ libusb transport. When a decoder's first output plane is not supported, the stream falls back to the float32 pipeline before any native block is published. Lossy codecs always use float32.

The Rust producer exposes its actual native-path state through JNI. Kotlin reports Bit Perfect only after the first native integer block has been accepted by USB. If DSP or software gain is enabled later, remaining queued native samples are converted into the float pipeline before processing and the status changes accordingly. Exact sample-rate, channel, valid-bit, and USB subslot negotiation remain mandatory. Software volume and ducking therefore disable Bit Perfect unless volume is handled by the DAC's hardware Feature Unit.

Unit tests cover signed/unsigned sample extraction, integer packing, FLAC-style left-aligned 24-bit restoration, non-zero padding rejection, and a WAV PCM decoder-to-USB-byte comparison. These tests validate software sample preservation; physical digital capture is still needed to certify the complete USB DAC hardware path. IEEE-float source data is not eligible for this integer PCM path.
