# Architecture

## Scope and ownership

Flowery uses three native/application layers with deliberately separate responsibilities:

| Layer | Owns | Must not own |
| --- | --- | --- |
| Kotlin / Android | Compose UI, Android lifecycle, SAF and URI grants, USB permission/device discovery, Android audio focus and system volume, MediaSession, notifications, platform control transfers, presentation models | Audio decoding, DSP algorithms, PCM format policy, USB descriptor interpretation, sample packing |
| Rust | Symphonia demuxing/decoding, typed integer and Float32 sample handling, Rubato, 31-band EQ, gain and PCM packing, Bit Perfect eligibility, PCM/encoded producer queues, UAC descriptor parsing, playback policy and queue transitions, AAudio NDK backend | Compose UI, Android permission prompts, SAF tree traversal, libusb transfer submission |
| C++ | libusb context/device handles, interface claims, alternate-setting transport setup, isochronous URBs, feedback endpoint event processing, transfer completion/cancellation and native teardown | Audio decoding, DSP, sample conversion, PCM policy, descriptor interpretation or playback queue policy |

The only application-owned C++ translation unit is `app/src/main/cpp/usb_audio_jni.cpp`. AAudio is implemented in Rust in `native/src/aaudio.rs` using Android NDK FFI; it is not a C++ audio backend.

## Component map

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Android Activity, app state, SAF launchers, service commands and permission callbacks |
| `FloweryNavigation.kt` | Top-level Compose destinations and navigation |
| `SettingsContent.kt` | Settings pages, USB DAC controls, audio information flow, diagnostics and open-source information |
| `AudioTag.kt` | Shared audio metadata presentation model |
| `TagEditorDialogs.kt` | Compose tag inspection and editing dialogs |
| `EqualizerComponents.kt` | Reusable 31-band vertical EQ slider component |
| `AndroidPlaybackAdapters.kt` | SAF extension resolution, bounded USB diagnostics recording and Android USB-host inventory |
| `UsbPcmPlaybackPipeline.kt` | Android transport adapter that polls Rust-produced PCM blocks, validates seek epochs, writes to libusb, handles prebuffer/drain and coordinates teardown |
| `UsbEncodedPlaybackPipeline.kt` | Android transport adapter for bounded encoded packet pumping, packet-boundary seek, pause and drain |
| `PlaybackService` | Foreground playback service, MediaSession, notifications, audio focus and Android media events |
| `PlaybackCoordinator` | Android adapter around the Rust playback lifecycle state handle |
| `FloweryPlayer` | Current Android playback adapter: opens SAF descriptors, chooses Android output devices, owns playback worker lifetime and coordinates Rust producer handles with the USB transport |
| `LibraryScanner` | SAF traversal, playlist text reading/charset decoding and URI-to-model mapping |
| `UsbDacController` | Android USB device discovery/permission, Android control transfers, native transport session setup |
| `NativeAudio.kt` | Kotlin declarations for the Rust JNI API |
| `UsbDacNative.kt` | Kotlin declarations and session wrapper for the C++ libusb transport |
| `native/src/streaming.rs` | Symphonia streaming decoder, integer/float source extraction, Rubato and stateful 31-band EQ |
| `native/src/native_pcm.rs` | Native typed-sample extraction and source representation helpers |
| `native/src/usb_pcm.rs` | Generic PCM format contract and bounded PCM block queue |
| `native/src/usb_producer.rs` | USB PCM producer, seek epochs, source-segment boundaries, format conversion and producer eligibility |
| `native/src/usb_encoded.rs` | Encoded packet queue and producer |
| `native/src/usb_encoded_formats.rs` | Encoded format and transport framing validation |
| `native/src/usb_descriptors.rs` | UAC1/UAC2 descriptors, Feature Unit controls, clock links/ranges, profiles and diagnostic traces |
| `native/src/aaudio.rs` | AAudio stream lifecycle, bounded PCM ring and real-time callback |
| `native/src/playback_policy.rs` | Output route eligibility, rate selection, ring sizing, frame conversion and watermarks |
| `native/src/queue.rs` | Queue index, shuffle, repeat and next/previous/completion policy |
| `native/src/playback_controller.rs` | Rust playback lifecycle state machine |
| `app/src/main/cpp/usb_audio_jni.cpp` | libusb transport and endpoint scheduling only |

## Playback route policy

The route eligibility decision is implemented in Rust through `nativeSelectPlaybackRoute` in `native/src/playback_policy.rs`. It selects Android Float32 output when direct USB mode is not requested, the UAC1 encoded AC-3 path for eligible AC-3 input when direct USB is requested, the source-preserving sample candidate for supported lossless integer sources, and the decoded Float32 USB path for other direct-USB sources. Kotlin performs the Android-specific work for the selected route, including opening SAF descriptors, discovering Android output devices, requesting USB permission and coordinating the transport. The Rust decision is covered by native unit tests and its JNI declaration is checked by the CI bridge-contract script.

## Playback data paths

### Android-managed output

1. Kotlin opens the selected SAF document and passes its file descriptor and extension to Rust.
2. Rust probes the container and decodes packets incrementally with Symphonia.
3. Rust configures persistent Rubato and EQ state; sample-rate conversion is skipped when source and target rates match.
4. Rust pumps bounded PCM chunks into its ring buffer.
5. The Rust AAudio callback consumes frames and submits them to the Android output stream.
6. Kotlin and the foreground service publish position, metadata and Android playback state.

Speaker, AUX/headphone and Bluetooth output use Float32 AAudio and Android's system volume policy. The AAudio callback must not perform file access, decoding, resampling, dynamic allocation or mutex locking.

### Direct USB output

1. Kotlin obtains Android USB host permission and opens the Android-authorized device connection.
2. Rust parses raw descriptors, identifies compatible profiles and determines the applicable PCM/encoded format policy.
3. Kotlin performs Android class-specific control transfers when Android owns the USB device connection; Rust parses their returned bytes.
4. C++ opens libusb, claims the selected interfaces, configures the endpoint and verifies the requested sample rate/clock through the advertised USB controls.
5. Rust decodes and produces bounded PCM or encoded packets.
6. The current Kotlin adapter polls bounded native queue blocks and passes them to the C++ libusb writer. C++ schedules isochronous transfers and handles feedback/completions.
7. Teardown drains or cancels transfers, releases claimed interfaces and closes native handles.

The current direct USB bridge still copies PCM/encoded blocks across JNI into Kotlin byte arrays before submitting them to C++. This is an identified optimization boundary, not a reason to move decoding or DSP into C++.

## USB descriptor and volume ownership

Rust is the sole parser for UAC descriptor structure and capability interpretation, including Feature Unit master-volume advertisement. Kotlin's `UsbFeatureUnitParser` is only an adapter over the Rust JNI result. Kotlin retains the Android control-transfer operation because it must use the Android-authorized `UsbDeviceConnection`; Rust parses the response and makes capability decisions.

UAC1/UAC2 profile parsing, clock-source linking, endpoint validation and sample-rate candidate selection belong in `native/src/usb_descriptors.rs`. C++ performs the transport-level SET_CUR/GET_CUR operation needed to configure and verify the selected rate. The DAC profile database stores raw descriptor evidence, parsed reports and descriptor fingerprints; a USB product name is not treated as proof of a chipset identity.

## Threading, handles and JNI contract

- Rust owns decoder, resampler, equalizer, PCM packing and bounded producer/ring state.
- The AAudio callback is a bounded real-time consumer and never calls back into Kotlin.
- Worker ownership is route-specific: Rust owns the AAudio decode/output schedulers and the direct USB PCM/encoded producer workers. Kotlin owns the Android-facing playback worker/lifecycle and the direct USB transport writer/polling loops; C++ owns libusb event and transfer scheduling.
- The direct USB producer queue and C++ transport session are separate resources and must be closed in producer-before-transport order after the Rust producer worker has stopped.
- JNI handles returned by native create functions are opaque, non-zero values. Their matching destroy/close operation must run exactly once after all users have stopped.
- Do not access a decoder, producer, queue, AAudio output or libusb session concurrently with its destroy/close operation.
- JNI byte arrays are transfer envelopes, not long-lived shared buffers. Keep queue capacity bounded and avoid whole-track PCM arrays.
- Keep Kotlin external declarations, Rust JNI symbol names, and C++ JNI exports synchronized. Any signature change must be covered by the native and Android build jobs.

## Current Kotlin boundary and completed orchestration

`FloweryPlayer` is now an Android-facing playback coordinator. It retains SAF descriptor acquisition, route selection, playback-generation lifecycle, Android service state, user commands, and coordination of native session handles. The AAudio decode/output lifecycle, seek scheduling, worker health and teardown are owned by the Rust playback session and schedulers.

For direct USB PCM and encoded playback, Rust owns decoding/encoding eligibility, producer scheduling, seek epochs and bounded producer queues. `UsbPcmPlaybackPipeline.kt` and `UsbEncodedPlaybackPipeline.kt` own the Android-side transport loops: they poll or pump bounded queue data, submit it to the C++ libusb session, coordinate pause/drain, report transport state and close resources in the required order. Kotlin still owns USB permission, Android device connection and control-transfer access; C++ owns libusb URBs and transport event handling.

This is a completed software migration boundary, not a claim that `FloweryPlayer` has no orchestration responsibilities. The remaining JNI byte-array bridge for queued USB blocks is an optimization boundary; it must not be described as a Kotlin-owned decoder or producer worker. Any future native queue-to-transport bridge must preserve bounded backpressure, cancellation, buffer lifetime and teardown guarantees.

## UI structure

The navigation surface is in `FloweryNavigation.kt`; settings presentation is extracted into `SettingsContent.kt`. Keep Compose presentation in Kotlin. Split additional destinations into focused files when they grow, but do not move Android UI into native code.

## Storage and metadata

The library uses Storage Access Framework and `DocumentFile`, not MediaStore. Kotlin traverses the selected SAF trees and decodes playlist text using the configured charset. Rust owns audio classification, sidecar matching, CUE/M3U/M3U8/PLS parsing and association, root-confined relative-path resolution, virtual-track creation, CUE time calculations and catalog deduplication. Kotlin maps Rust URI values into Android document models.

For tag operations, Kotlin passes SAF file descriptors through JNI. Rust stages descriptor contents in the app-private cache for Lofty processing; edited staged files are streamed back through the SAF output stream. Lyrics parsing and current-line lookup are Rust-owned; Kotlin maps results to UI models.

## C++ transport invariants

- The event thread owns libusb event handling while a session is active.
- Transfer buffers must remain alive until their callbacks have completed or cancellation has been reaped.
- In-flight URBs are bounded; packet sizes must not exceed the descriptor-derived endpoint capacity.
- Feedback values must be recent and valid before asynchronous packet scheduling uses them.
- PCM and encoded sessions are mutually exclusive on a transport handle.
- Teardown cancels and reaps transfers before releasing interfaces and closing the device handle.
- Native flush and close return explicit status to Kotlin. The session wrapper retries a bounded number of close attempts and does not close the Android USB connection when libusb has not confirmed transfer reaping.
- The current teardown deliberately avoids freeing a session if its event thread cannot be joined; this favors avoiding use-after-free over guaranteed reclamation. Such a failure must be surfaced and investigated rather than silently treated as a successful close.

## Verification boundaries

Native unit tests validate descriptor parsing, profile selection, PCM packing, decoder behavior, producer queues and playback policy. Android unit tests validate Kotlin adapters and application logic. The CI JNI contract check compares every Kotlin external declaration against its Rust or C++ exported symbol, catching missing or stale bridge methods before native packaging. CI builds the native Android libraries and debug/release APKs. These checks do not replace physical DAC testing, USB protocol capture or external digital loopback/capture for a hardware bit-exact claim.
