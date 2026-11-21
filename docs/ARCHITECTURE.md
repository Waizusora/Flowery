# Architecture Reference

## Components

| Component | Responsibility |
| --- | --- |
| `app/src/main/java` | Android UI, library access, playback service, route selection, and device-change handling |
| `AndroidOutputRouteResolver.kt` | Detects connected Android outputs and resolves AUX, speaker, or Bluetooth device IDs |
| `UsbAudioDeviceScanner.kt` | Enumerates USB Audio Class devices and reports interface/endpoint metadata separately from Android USB output routes |
| `PlaybackService.kt` | Owns the media session, queue, audio-device callback, output-route reopen orchestration, and event-driven media metadata publication |
| `PlaybackCoordinator.kt` | Coordinates playback lifecycle and reopens the active track at a captured position |
| `native/src/lib.rs` | JNI entry points and native module registration |
| `native/src/streaming.rs` | Incremental Symphonia decode and source-rate PCM frame production |
| `native/src/pcm_format.rs` | Generic signed-integer PCM format and quantization contract |
| `native/src/pcm_transport.rs` | Shared bounded SPSC PCM byte-ring construction for independent backend producer/consumer endpoints |
| `native/src/usb_audio.rs` | Vendor-independent UAC1/UAC2 configuration descriptor parsing and playback alternate discovery |
| `native/src/usb_isochronous.rs` | Full/high-speed isochronous PCM packet scheduling and transfer lifecycle validation helpers |
| `native/src/usb_feedback.rs` | Explicit feedback packet decoding, clock tracking, outlier rejection, and frame scheduling |
| `native/src/usb_stream.rs` | Asynchronous libusb isochronous OUT worker, transfer ring, pause/seek/stop, cancellation, and teardown |
| `native/src/usb_libusb.rs` | Android USB file-descriptor wrapping and native interface ownership through libusb |
| `native/src/aaudio.rs` | Android AAudio output backend, including the Balanced performance-mode request, requested-device selection, sharing-mode verification, and fail-closed exclusive mode |
| `native/src/playback_session.rs` | Playback lifecycle, seek coordination, and worker ownership |

## Data flow

Android-managed output:

`SAF descriptor → Symphonia → source-rate PCM conversion → bounded rtrb SPSC PCM ring → AAudio → Android audio system`

Direct USB output:

`SAF descriptor → Symphonia → typed source PCM chunks → bounded rtrb SPSC PCM ring → USB Type I subslot packing → isochronous OUT transfer ring → USB DAC`

Flowery validates source rate, channel count, source precision, and data format against the selected USB alternate. It does not intentionally resample the direct USB stream. The direct path is not advertised as bit-perfect without end-to-end validation of decoder behavior, source representation, USB transport, and DAC output.

## PCM transport ownership

The AAudio and direct USB routes each own an independent `rtrb` SPSC PCM ring, constructed through the shared `pcm_transport` module. The decoder-side worker is the sole producer and the output callback or USB submit worker is the sole consumer. The routes do not share one ring: simultaneous fan-out requires a separate ring for each consumer. PCM capacity and transfer operations are byte-oriented internally but are validated and accounted in complete interleaved frames. EOF and decoder errors are sideband state and do not consume PCM slots.

The AAudio callback reads directly from its consumer endpoint without acquiring the engine mutex or allocating a temporary PCM buffer. Its producer endpoint is owned by the AAudio engine. The USB queue wraps separate producer and consumer endpoints so the decoder worker and isochronous submit worker do not contend on one queue-state mutex. The libusb transfer ring, packet scheduler, explicit-feedback transfer, packet validation, and bounded cancellation/drain rules are unchanged by the PCM transport migration.

AAudio requests `AAUDIO_PERFORMANCE_MODE_NONE` (Balanced) as its only performance mode. The requested performance mode is independent of AAudio sharing mode; Exclusive and Shared remain separate route compatibility choices. Android may negotiate the final stream configuration, so diagnostics must be used to inspect the actual stream.

## Android media-session controls

`PlaybackService` handles SystemUI media-session commands on the service callback thread. Embedded audio metadata is read asynchronously because SAF document providers may block while opening or extracting tags. A monotonically increasing playback request generation prevents a delayed metadata result from replacing the metadata or playback request for a newer queue selection. Media-session metadata is published only when the selected title, artist, or duration changes; the periodic progress task updates playback position and state without repeatedly replacing session metadata or reposting the notification.

## Output routing

The application resolves Android-managed output targets and a separate direct USB Audio Class path. Android-managed outputs include AUX, built-in speaker, and Bluetooth. The direct USB path requires USB Host permission, a descriptor-derived AudioStreaming alternate, and successful libusb fd wrapping. AAudio remains the Android-managed backend; it is not used as a silent fallback when direct USB startup fails.

The playback service monitors Android output-device additions and removals. If the monitored Android output snapshot changes while playback is active or paused, it reopens the current track on the newly resolved output, retaining the playback position and play/pause state. USB detach and transport errors must be treated as terminal for the active direct USB session; successful teardown is required before the Android-owned USB connection can be closed.

## USB audio discovery and configuration

`UsbAudioDeviceScanner` enumerates devices exposed by Android USB Host and retains devices with Audio Class interfaces (or an Audio Class device-level declaration). It records Audio Control and Audio Streaming interface presence, interface protocol, alternate setting, and endpoint address, direction, transfer type, and maximum packet size.

The native `usb_audio` module parses raw configuration descriptor bytes without VID/PID-specific tables. It extracts UAC protocol version, AudioStreaming alternate settings, PCM Type I channel/subslot/bit resolution, UAC1 discrete or continuous sample rates, isochronous OUT endpoint metadata, and explicit feedback endpoint metadata. `plan_pcm_playback` selects a descriptor-advertised alternate matching the requested PCM parameters.

On Android, `UsbLibusbSession` keeps the permission-granted `UsbDeviceConnection` alive and passes its file descriptor to `libusb_wrap_sys_device`. Before the native session starts, Kotlin force-claims the selected AudioStreaming interface on the same file descriptor so that the kernel `snd-usb-audio` driver is detached and the interface is owned by usbfs; the native session then claims it (a no-op for the kernel on the shared descriptor, with one explicit detach-and-retry on `LIBUSB_ERROR_BUSY`) and sets its alternate setting. For UAC2 clock negotiation, Kotlin also force-claims the AudioControl interface on the same descriptor, because usbfs rejects interface-recipient control requests for interfaces the file does not own. libusb auto-detach is intentionally not enabled because it uses `USBDEVFS_DISCONNECT_CLAIM` excluding usbfs, which returns `EBUSY` for an interface already owned by usbfs. Detaching the kernel driver removes the interface from Android's managed audio routing for the duration of the session. The libusb wrapper releases the interface and closes its handle before Kotlin closes the Android connection. Do not use another USB control/session owner on the same interface concurrently. If the platform backend does not support fd wrapping, startup fails explicitly rather than silently switching to AAudio.

UAC1 endpoint sampling frequency is configured through the endpoint control request. UAC2 clock negotiation uses descriptor-derived clock topology and advertised clock ranges; selector and multiplier paths are validated before applying changes. When startup fails after a clock change, the previous clock state is restored and verified. These controls are performed before starting the native stream.

## Direct USB transfer worker

The Android native transport owns a bounded ring of four libusb isochronous OUT transfers, each containing eight service packets. The worker also owns an explicit feedback IN transfer when the selected alternate advertises one. Full-speed and high-speed service intervals and payload capacities are calculated from bus speed, `bInterval`, and the complete `wMaxPacketSize` transaction encoding. SuperSpeed is rejected until companion-descriptor scheduling is implemented.

Transfer callbacks only publish an atomic completion flag. The worker performs packet-status and actual-length validation, updates played-frame accounting, refills PCM buffers, resubmits transfers, and processes feedback clock estimates. Full-speed 10.14 and high-speed 16.16 explicit feedback formats are decoded; estimates are range-checked, smoothed, guarded against abrupt outliers, and used for packet frame scheduling. Implicit feedback is not implemented. Devices that require implicit feedback must not be assumed to work.

The PCM producer validates decoder metadata at the final encoding boundary and packs the decoder's typed samples into signed little-endian USB Type I subslots with left-aligned valid bits. The USB worker does not apply application volume, EQ, normalization, mixing, or sample-rate conversion. This does not by itself prove bit-perfect output: source decoding and quantization behavior, DAC clocking, and physical transport still require device-level validation.

## USB lifecycle and failure containment

The worker is the sole owner of submitted transfer processing. Stop, seek, close, and stream errors request cancellation and continue pumping libusb events until every submitted transfer callback has been observed. A cancellation request returning `LIBUSB_ERROR_NOT_FOUND` is not treated as callback completion; the worker still waits for the completion signal. A disconnected device or another terminal event error moves the worker into cancellation and drain.

Transfer drain is bounded. If callbacks do not arrive before the drain deadline, or the worker cannot exit before its bounded join deadline, the session is marked unsafe. Submitted transfer memory, callback state, libusb session/context, and the Android USB connection are deliberately retained rather than risking use-after-free. An unsafe session cannot be restarted or closed as if teardown had succeeded. This is a fail-safe resource retention strategy, not successful disconnect recovery.

Pause stops refilling audio transfers and acknowledges only after outstanding audio transfers drain; explicit feedback may continue while paused. Resume restarts audio submission. Seek stops and joins the worker before mutating decoder position, then starts a new worker with the existing stream configuration. Close uses the same lifecycle gate as start and seek, drains the worker, and removes the native session only after safe teardown. Stop and close failures must remain visible to the Kotlin owner.

## Validation status and limitations

Native unit tests cover packet scheduling, feedback decoding/tracking, transfer completion validation, cancellation classification, bounded drain decisions, and resource retention helpers. CI builds native Android libraries and debug/release APKs and runs native and Android unit tests. CI success does not replace physical testing.

Required hardware validation remains: sustained playback across supported sample rates and channel layouts; pause/resume and repeated seek; natural EOF; stop and close during active transfers; unplug while playing; rapid detach/reattach; and verification that no stale session or file descriptor is reused. SuperSpeed scheduling and implicit feedback remain unsupported. No generic claim of bit-perfect playback or universal UAC device compatibility is made.

## PCM boundary

The decoder may use floating-point working samples internally for formats that require conversion. The USB encoder then packs the resulting typed samples into the descriptor-selected signed-integer subslot format. AAudio is configured for signed-integer PCM. These format boundaries are separate and must not be conflated with proof of bit-perfect source preservation.
