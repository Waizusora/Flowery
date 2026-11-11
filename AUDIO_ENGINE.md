# Audio Engine

## Streaming pipeline

`SAF URI → ParcelFileDescriptor → Rust/Symphonia → Rust/Rubato → Rust 31-band EQ → Rust bounded PCM ring → Rust AAudio NDK FFI`

`FloweryPlayer.kt` coordinates Android document access, route selection, playback-generation lifecycle, user seek/pause commands, native session handles, and service-facing state. For AAudio, the Rust playback session owns decode-worker and output-scheduler lifecycle; `native/src/streaming.rs` performs incremental Symphonia decoding, Rubato resampling, EQ and format-level seeking, then transfers decoded frames into the Rust ring without creating a Java float array. `native/src/aaudio.rs` owns the AAudio NDK stream, PCM ring buffer, callback, output lifecycle, and ring-write API. For direct USB, Kotlin retains Android permission, device connection/session setup and the transport submission loops. Rust owns USB descriptor parsing, the shared `PcmFormat` contract, Symphonia PCM decoding and packing, bounded queues, and PCM/encoded producer workers.

The JNI surface intentionally exposes the incremental decoder and bounded output operations only. The former whole-file `nativeDecode` API and stateless array-based `nativeResample`/`nativeApplyEqualizer` APIs have been removed, along with their Kotlin declarations and convenience wrapper. This prevents a second, state-resetting DSP route from bypassing the persistent streaming resampler and equalizer state.

## Decode and resampling

Symphonia probes the selected document and decodes the default audio track packet by packet. The streaming path retains pending samples rather than assembling the entire decoded track in one PCM array. For ALAC in MP4/M4A, Flowery resolves channel count from the track channel bitmask or channel layout when present, then falls back to the validated ALAC magic-cookie configuration (24-byte or 48-byte form); it rejects missing or out-of-range channel metadata rather than assuming a layout. Mono and stereo are supported by the current output path. The supported filename-extension registry is maintained in `native/src/library.rs` and queried through JNI by the Kotlin SAF scanner. This is a discovery filter only; it does not guarantee that every corresponding codec can be decoded.

Rubato 5 uses a fixed-ratio synchronous FFT resampler. Its instance and filter history persist across chunks. When source and output sample rates match, conversion is bypassed. The normal AAudio route selects rates from Android output-device capabilities and validates the result when opening AAudio. Direct USB instead derives supported rates from UAC2 clock RANGE responses and confirms the selected rate through native SET_CUR/GET_CUR negotiation.

## 31-band ISO graphic equalizer

The DSP screen exposes 31 independent vertical gain controls at the ISO preferred one-third-octave center frequencies: 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000 and 20000 Hz. Each band has an independent gain from -12 dB through +12 dB. The controls are horizontally scrollable so all 31 bands remain available on narrow displays. The enable/bypass switch retains the configured gains while sending a flat 31-value array to the streaming decoder when bypassed.

Each native band uses a peaking biquad with Q = 1 / (2^(1/6) - 2^(-1/6)) ≈ 4.318, the standard one-third-octave bandwidth relation. Filter state is retained per channel and band across chunks. Runtime gain updates replace coefficients without clearing filter history. The 31 per-band gains are persisted independently in Android preferences. On first launch after the three-control UI migration, the former Bass/Mid/Treble values are expanded once across the 31 bands and saved as the initial independent settings; subsequent edits affect only the selected band.

The native integer USB path remains eligible only when all 31 gains are flat, alongside the existing source-format, resampling, volume, and channel-mapping requirements. Enabling any non-zero EQ gain routes playback through float processing and is not labelled Bit Perfect. Native tests check chunk-boundary continuity, strict ISO one-third-octave Q, center-frequency gain, and coefficient finiteness at supported sample rates.
## Ring buffer and callback

The AAudio ring capacity is selected from the negotiated output rate: at least two seconds of PCM and at least 32,768 frames, rounded up to a power of two. Ring capacity is retained as scheduling headroom, not treated as a target fill level. The Rust decode producer limits queued PCM to approximately 250 ms at normal music sample rates (the target is the greater of one quarter of the negotiated rate or 8,192 frames, capped by ring capacity), and sleeps while that target is reached. Output starts after a low-water mark of approximately 100 ms (minimum 2,048 frames, bounded by half the ring capacity and the queue target). This bounds the amount of already-processed audio ahead of the output, so EQ changes do not wait behind seconds of stale PCM, while retaining additional ring capacity for scheduling jitter. The Rust AAudio callback copies available interleaved float frames, applies output gain, clamps samples, converts to the selected PCM output format, fills underruns with silence, and stops after EOF and queue drain. It does not perform file I/O, decoding, resampling, dynamic allocation, or mutex locking. The producer and callback share a bounded frame-indexed ring buffer; flush pauses the stream before resetting queue indices. AAudio seek is coordinated by the native output scheduler: it pauses output, joins the old decode worker, performs a format-level Symphonia seek, flushes the ring, starts a new decode worker, and restarts output after the low-water mark is reached. Native seek failures retain their original diagnostic detail and are surfaced through Kotlin playback diagnostics; a generic scheduler error code alone is not considered a root-cause diagnosis.

AAudio exclusive sharing is requested first for the Android fallback route. If the exclusive stream cannot be opened, the native AAudio layer retries the same device, sample rate, channel count, and PCM format in shared mode. Runtime diagnostics retain the requested and actual sharing modes and mark that a shared retry occurred. Shared mode may be mixed or resampled by Android. USB Direct remains a separate libusb route and is never represented as AAudio exclusive.

## Direct USB PCM processing

## Unified direct USB PCM path

"PCM Passthrough" describes the common PCM output path, not a promise that every source file can be copied byte-for-byte. Every supported source adapter must produce the negotiated PCM representation and enter the same Rust-owned `PcmFormat`, bounded queue, and C++ libusb isochronous transport.

All supported source formats use Symphonia as the demuxer and decoder. PCM containers and supported lossless codecs enter the same PCM producer, bounded queue, and USB transport. The native integer path extracts typed decoder samples and packs them directly; lossy or unsupported decoder representations use the float32 processing path. The native path is not a raw compressed-file copy and does not bypass decoding.

All decoder-supported containers and codecs use Symphonia to decode into PCM. The decoded source adapter can apply Rubato when the DAC does not accept the source rate, apply DSP/gain as configured, and quantize to the selected signed little-endian valid-bit/subslot layout. This is PCM output through the same engine, but it is not source-bit-perfect when the source is lossy or samples have been transformed. Codec support remains bounded by the configured Symphonia features; a recognized file extension alone does not guarantee decodability.

`native/src/usb_pcm.rs` owns the shared `PcmFormat` contract and bounded six-block queue used by the Symphonia PCM producer and USB writer. Queue capacity, ordering, timeout behavior, clear-on-seek, and close signaling are implemented in Rust. Rust also owns the PCM producer worker, including decode scheduling, seek-epoch handling and gain updates. Kotlin's `UsbPcmPlaybackPipeline` owns the transport writer loop: it polls complete blocks, validates epochs, flushes stale USB output, submits format-checked blocks to the libusb session, handles pause/prebuffer/drain, and coordinates teardown. The queue is bounded by block count; the current producer uses at most 32,768 stereo frames per block.

The retained C++ translation unit remains exclusively responsible for the libusb isochronous transport. No AAudio implementation is moved into or retained in C++.

## Seeking

Seeking flushes the output ring, performs a format-level Symphonia seek, resets decoder and DSP state, reconfigures the resampler, retains decoder preroll where needed to rebuild Rubato history, and discards output before the requested presentation timestamp. Playback paused/playing state is preserved.

## Output volume policy

Flowery uses three output-volume profiles. Profile 1 routes speaker, wired AUX/headphones, and Bluetooth through Android `STREAM_MUSIC`; Android remains responsible for safe-listening limits and system warning UI. Profile 2 is selected when the USB DAC advertises a master-channel UAC Feature Unit Volume Control. Flowery reads and writes the Feature Unit's signed 1/256 dB volume value, and the UI polls the current hardware value so physical DAC volume buttons and the software slider remain synchronized. Profile 3 is used when no usable Feature Unit volume control is advertised; it applies a per-DAC software gain, initially 20% for a new DAC, and persists later changes. Profile 3 changes PCM samples and therefore cannot be labelled Bit Perfect. When the native USB PCM producer confirms an unchanged source representation, Flowery bypasses software gain and ducking automatically; profile 2 may still adjust the DAC's own output volume without changing the PCM payload. Vendor-specific volume controls that are not exposed as standard UAC Feature Units are not inferred.

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

If direct USB device/profile discovery or format negotiation fails before the first USB transfer, Flowery records the cause and continues through the Android AAudio route. AAudio requests EXCLUSIVE sharing first and retries SHARED only when the exclusive stream cannot be opened. A transport failure after USB playback has started is reported rather than silently switching routes mid-track, which could duplicate or skip audio. Direct USB playback has no WAV-specific path. Every supported source is decoded by Symphonia and enters the shared PCM producer. The Rust reader selects native integer PCM only when the decoder exposes exact integer samples and the source depth is supported (8/16/20/24/32 bits), the negotiated DAC rate/channels/valid bits/subslot match exactly, the channel mapping is unchanged, the EQ is flat, software gain is unity, and no resampler is active. Unsupported typed decoder output falls back to float32 before any native block is published. Lossy sources always use float32. Kotlin reads the producer's actual native-path state and reports `BIT PERFECT` only after the first native integer PCM block has been accepted by USB. Changing DSP or software gain converts any queued native remainder to float32 before processing and withdraws the Bit Perfect status. External digital capture on physical DAC hardware is still required for hardware-level validation.

For non-Bit-Perfect direct USB playback, `FloweryPlayer` uses Symphonia to decode supported mono-to-eight-channel audio to float32 PCM, configures the stateful Rubato resampler when a supported DAC rate differs from the source rate, applies the configured DSP, and sends the result through the same Rust producer, bounded queue, and C++ libusb transport. Rust quantizes to the selected profile's valid-bit resolution (8–32 bits) and writes the correct 1–4 byte signed little-endian subslot, including padded layouts such as 24 valid bits in a 32-bit subslot. The same native bounded queue and libusb transport are used for all supported formats. This is processed PCM and is not source-bit-perfect playback.

Direct USB playback selects a per-device volume profile from descriptor capabilities. A standard UAC Feature Unit master Volume Control selects hardware-synchronized volume: Flowery reads/writes the DAC's signed 1/256 dB volume value, and the UI receives periodic GET_CUR updates so hardware buttons and the slider stay synchronized. Without that control, software-only volume is used with a 20% initial gain for a newly seen DAC and a gain ramp before PCM quantization. Software volume and ducking are bypassed for strict Bit Perfect playback. Vendor-specific volume controls are not inferred. The normal Symphonia → Rubato → EQ → AAudio path remains unchanged when USB exclusive mode is disabled.

## Shared PCM format contract

The direct USB routes share one Rust `PcmFormat` contract containing sample rate, channel count, valid-bit resolution, subslot width, signedness, and byte order. All source formats use the Symphonia decoder and publish the resulting PCM through one bounded queue and the same libusb writer. The former WAV-specific byte-copy source has been removed. Bit depth determines sample representation; resampling is required only when the DAC cannot accept the source sample rate or when a different output rate is selected.





## Decoded direct USB pipeline

The decoded direct USB path separates the Rust producer worker from the Kotlin USB transport writer loop so that transport submission can continue while decoding proceeds. The Rust producer reads bounded 32,768-frame chunks. It publishes native integer samples directly when the strict eligibility gate passes; otherwise it uses float32, applies gain and DSP, and quantizes to the negotiated signed little-endian PCM valid-bit/subslot layout. Both modes place blocks in the same bounded queue of six blocks. The Kotlin writer loop in `UsbPcmPlaybackPipeline` waits until three blocks are queued before the first write, then polls blocks and passes them to `UsbDacSession.writePcm()`. The Rust producer worker is stopped and joined before its decoder/queue resources are destroyed. Seeks are serviced by the Rust producer: it seeks the decoder, invalidates stale queued blocks through an epoch counter; the Kotlin writer flushes the USB transport when it observes a new epoch. The producer remains idle after reaching the end of the stream so that a seek issued while final blocks are queued is honored. Producer failures are surfaced through the writer loop's normal playback-error path.

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

`native/src/native_pcm.rs` extracts integer samples from Symphonia's typed decoded planes without a float conversion, verifies left-aligned decoder padding bits, and packs signed samples into the negotiated little-endian USB subslot. Valid sample bits are left-justified within the USB subslot; unused least-significant padding bits are zero-filled. It supports 8-, 16-, 20-, 24-, and 32-bit integer representations, including packed 24-bit and padded 24-bit-in-32 output. The FLAC decoder's S32 representation is restored to its valid-bit width only after checking that unused low bits are zero.

`StreamDecoder::read_usb_pcm()` selects the native path in Rust only when the source is classified as lossless, the decoder exposes a supported integer plane, the source and output rate/channel/valid-bit formats match, the channel mapping is unchanged, the resampler is absent, EQ is flat, and software gain is unity. It sends the resulting bytes through the same Rust bounded USB PCM queue and the existing C++ libusb transport. When a decoder's first output plane is not supported, the stream falls back to the float32 pipeline before any native block is published. Lossy codecs always use float32.

The Rust producer exposes its actual native-path state through JNI. Kotlin reports Bit Perfect only after the first native integer block has been accepted by USB. If DSP or software gain is enabled later, remaining queued native samples are converted into the float pipeline before processing and the status changes accordingly. Exact sample-rate, channel, valid-bit, and USB subslot negotiation remain mandatory. Software volume and ducking therefore disable Bit Perfect unless volume is handled by the DAC's hardware Feature Unit.

Unit tests cover signed/unsigned sample extraction, integer packing, FLAC-style left-aligned 24-bit restoration, non-zero padding rejection, and a WAV PCM decoder-to-USB-byte comparison. These tests validate software sample preservation; physical digital capture is still needed to certify the complete USB DAC hardware path. IEEE-float source data is not eligible for this integer PCM path; IEEE Float32 WAV uses the separate float-preserving path when a matching DAC profile is available.


## IEEE Float32 USB playback

Direct USB PCM profiles carry an explicit sample encoding. UAC1 Type-I profiles are classified as IEEE Float when their AudioStreaming General descriptor advertises `wFormatTag=0x0003`; UAC2 Type-I profiles are classified from the IEEE_FLOAT bit in the AudioStreaming General `bmFormats` field when the PCM bit is not also advertised; dual-format alternates retain the established integer selection until explicit UAC2 format-control negotiation is implemented. Float playback is selected only for a four-byte, 32-bit profile. The Symphonia IEEE Float32 source path copies each decoded `f32` bit pattern directly to little-endian USB payload bytes, without normalization, quantization, resampling, EQ, software gain, or channel remapping. The integer-native path remains separate.

Both integer and IEEE Float32 payloads use the existing Rust `UsbPcmProducer`, bounded PCM queue, and C++ libusb isochronous writer. If a DAC does not advertise a matching Float32 profile, Flowery falls back to the existing integer PCM negotiation and float32 decode/quantization path; it does not label that fallback Bit Perfect. Speaker, wired AUX/headphones, and Bluetooth continue to use the Android AAudio Float32 output path and Android system volume. IEEE Float32 Bit Perfect status is reported only after the producer confirms an eligible source-to-DAC route; software volume, EQ, resampling, or format conversion disables that status.


## In-app USB DAC connect and release controls

Settings → Audio exposes Sambungkan DAC and Lepas DAC. Sambungkan DAC enumerates connected USB Audio Class devices and uses Android's public USB host permission request dialog in the foreground Activity; no external Settings screen is required. A successful grant is remembered as a preference so Flowery can request permission again on a later launch if a connected DAC is no longer permitted. Android's USB permission grant is temporary and scoped to the requesting package/device connection.

Lepas DAC sends a release command to the playback service. The playback coordinator stops the player and waits for its worker thread to finish cleanup before reporting success. The worker cleanup closes the active UAC1 encoded pipeline or PCM UsbDacSession; the native libusb session is therefore given the opportunity to release claimed interfaces and close its handle before another app tries to use the DAC. This action does not and cannot revoke Android's USB permission grant through the public SDK. Disconnecting the DAC ends the temporary grant. Release completion is tied to worker cleanup rather than merely to a UI state change.

## Runtime audio information view

Settings → Audio → Information Audio displays two vertical signal-flow diagrams. The source-preserving path shows source file → Symphonia → native integer/IEEE Float32 representation → Rust bounded USB PCM producer → C++ libusb → USB DAC. The decoded/DSP path shows source file → Symphonia float32 decode → Rubato when rate conversion is required → 31-band EQ and gain processing → either AAudio float32 output for speaker/AUX/Bluetooth or USB integer PCM quantization and libusb output. The highlighted path follows the current playback status; it is not a substitute for external digital capture of the DAC output.

## Native ownership and JNI bridge audit

Rust is the sole owner of UAC descriptor interpretation. This includes Feature Unit master-channel volume capability detection; `native/src/usb_descriptors.rs` returns the unit ID, owning AudioControl interface and protocol through JNI. Kotlin's `UsbFeatureUnitParser` only maps that result into an Android-facing value. Kotlin still issues the class-specific control transfer through the Android-authorized `UsbDeviceConnection`, then passes the returned bytes to Rust parsing functions where applicable.

The Android UI is split between `FloweryNavigation.kt` (destination navigation) and `SettingsContent.kt` (settings pages and their presentation helpers). Android screens remain Kotlin/Compose code.

`FloweryPlayer` remains an Android-facing coordinator rather than a command-only wrapper. It owns playback-generation lifecycle, SAF descriptor lifetime, Android route discovery, user commands and service-facing state. Rust owns AAudio session scheduling and decode/output workers, plus direct USB PCM and encoded producer workers. Kotlin's USB pipeline adapters own transport-side polling/pumping and pass bounded payloads to the C++ libusb writer as JNI byte arrays. Removing these intermediate arrays requires a separately tested native queue-consumer contract and cancellation/teardown ordering; decoding or DSP must not be moved into C++ to achieve it.

The C++ transport is limited to libusb resource management, interface/alternate-setting setup, USB class control requests needed for transport negotiation, isochronous URB scheduling, feedback endpoint handling, completion processing, cancellation and teardown. Transfer buffers remain owned until libusb completion/cancellation is reaped. If the event thread cannot be joined, the current close path deliberately avoids freeing the session to prevent a use-after-free; the resulting resource-retention failure must be surfaced and tested rather than treated as a successful release.

The native descriptor parser regression suite includes UAC1 and UAC2 Feature Unit master-volume capability cases and a negative case where the volume bit is not advertised. Android unit tests validate direct USB PCM block-envelope decoding, explicit end-of-stream markers, and rejection of truncated or empty non-terminal blocks.

## Android playback adapter boundaries

Android-specific SAF extension lookup and USB diagnostic capture are implemented in `AndroidPlaybackAdapters.kt`, rather than inside `FloweryPlayer`. Direct USB PCM transport polling, block-envelope decoding, epoch validation, prebuffering, pause/drain and writer teardown are isolated in `UsbPcmPlaybackPipeline.kt`. Encoded USB packet pumping, packet-boundary seek and transport drain are isolated in `UsbEncodedPlaybackPipeline.kt`. These Kotlin adapters do not decode audio or pack PCM; Rust producer workers retain those responsibilities.

`FloweryPlayer` coordinates the Android playback generation and delegates route-specific transport loops to the USB pipeline adapters. AAudio decode/output scheduling and USB PCM/encoded producer work are Rust-owned; USB transport submission remains Kotlin-to-C++ because Android device permission and connection ownership remain platform responsibilities. Future transport-boundary optimization must preserve Android SAF descriptor ownership, USB permission and interface lifecycle, output-device discovery, and C++ libusb transport ownership.


## Rust-owned playback route selection

The route eligibility policy is centralized in `native/src/playback_policy.rs`. Before creating a playback session, Kotlin checks whether a connected USB Audio Class device has an active Android USB permission grant. That detected availability, together with source facts obtained from the decoder, is passed to Rust; there is no user-facing Bit-perfect enable switch. Rust returns one of four route decisions: Android Float32, encoded AC-3 USB, source-native integer PCM USB candidate, or decoded Float32 USB. Kotlin remains responsible for performing the Android and USB operations required by the selected route. The route policy has native unit coverage, and CI verifies that all `NativeAudio` and `UsbDacNative` external declarations have matching Rust or C++ exports.


## Native playback session and producer lifetime

Each playback generation owns one registry-backed Rust session shared by its decoder and active output producer. The session is the authority for route identity, lifecycle phase, cancellation, and a checked monotonic seek epoch. Seek, phase changes, cancellation, and close are serialized so a seek cannot begin after teardown has committed. Closing a session always marks it cancelled before its registry entry is released; decoder storage remains alive while any producer or in-flight JNI call retains an `Arc`.

The direct USB PCM producer is cancelled and its Rust worker is joined before its state is released. Destruction also synchronizes with any in-flight manual pump. The encoded producer uses a Rust worker to prepare packets in a bounded queue; the Kotlin transport loop incrementally pumps available packets to C++. It observes the shared session epoch: a seek stops and joins the producer worker, flushes old USB transfers, seeks the decoder successfully, and only then advances the session epoch and clears pending and queued packets. A failed decoder seek leaves the epoch unchanged and does not restart the producer; the playback owner must treat the seek as failed and tear down the session. The replacement worker starts only after the seek transition commits. Encoded producer destruction rejects new work, waits for in-flight producer work, clears pending and queued packets, and closes the queue. The encoded queue remains bounded by both packet count and payload bytes.

Kotlin continues to own Android document descriptors, USB permission and device connection lifetimes, and the calls that submit packets to the C++ libusb transport. C++ continues to own libusb transfer scheduling and reaping. These platform-owned resources must be stopped or drained before their handles are closed. AAudio decode/output scheduling and USB PCM/encoded producer workers are Rust-owned. The USB transport submission loops remain Kotlin-orchestrated and must preserve bounded polling, pause/seek behavior, EOF/drain handling, cancellation, and transport reaping.

## Automatic Bit-perfect status

There is no manual Bit-perfect toggle. When a USB Audio Class device is connected and Flowery has Android USB permission, playback route selection automatically evaluates the source and negotiates a compatible DAC profile. The UI reports Bit-perfect as active only after the native USB PCM producer confirms source-preserving output. A decoded or converted USB PCM route is reported as not Bit-perfect; when direct USB permission is unavailable, playback uses the Android-managed route. The Connect DAC action remains the explicit Android permission step, while the Bit-perfect decision itself is automatic. Descriptor compatibility and producer confirmation are runtime indicators, not a substitute for external digital capture when validating the physical signal path.


## AAudio decode-worker error propagation

The Rust-owned AAudio stream-pump retains a diagnostic message when channel validation, incremental Symphonia decoding, or bounded ring-buffer submission fails. JNI exposes both the worker status and its error detail. The Kotlin AAudio playback loop checks the stream-pump status independently of the output-scheduler status and terminates the playback session with the decoder error instead of waiting indefinitely for PCM that will never arrive. End-of-stream and session-cancellation states remain distinct from worker failure.
\n\n## Synchronized lyrics\n\nThe native LRC parser accepts standard square-bracket line timestamps and enhanced angle-bracket word timestamps. Timestamp markers are excluded from visible lyric text. Enhanced word timestamps drive word-level highlighting, while standard LRC uses line-level highlighting. The Now Playing list scrolls the active or upcoming lyric line to the vertical center. A valid `[offset:+/-milliseconds]` metadata tag shifts line and word timestamps together.\n

## Playback queue completion

Natural completion of the final queue item transitions playback to `STATE_STOPPED` and releases audio focus without stopping the playback service or removing its foreground notification. The media session, notification controls, current queue selection, and Now Playing screen remain available so the user can replay the final item or select another track. Explicit user stop remains a separate lifecycle operation and continues to stop the player service.


## USB PCM real-time scheduling and end-of-stream drain

USB diagnostic events are enqueued from the playback worker and persisted in bounded batches on a dedicated daemon thread. The playback worker does not read or rewrite the diagnostic SharedPreferences payload for each PCM block. The pending event queue is bounded, and the persisted diagnostic history remains capped at 1,200 lines.

At natural end of a PCM stream, the transport completes the final USB service interval using the next scheduled frame count. If the decoded stream ends partway through that interval, the remaining frames are filled with digital silence before the final isochronous packet is submitted. This preserves the endpoint's service-interval cadence while ensuring that no source audio frames are fabricated or counted as decoded playback.
