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
| `native/src/usb_audio.rs` | Vendor-independent UAC1/UAC2 configuration descriptor parsing and playback alternate discovery |
| `native/src/usb_isochronous.rs` | Full/high-speed isochronous PCM packet scheduling primitives |
| `native/src/usb_libusb.rs` | Android USB file-descriptor wrapping and native interface ownership through libusb |
| `native/src/aaudio.rs` | Android AAudio output backend, including requested-device selection, sharing-mode verification, and fail-closed exclusive mode |
| `native/src/playback_session.rs` | Playback lifecycle, seek coordination, and worker ownership |

## Data flow

`SAF descriptor → Symphonia → source-rate PCM conversion → bounded PCM ring → AAudio → Android audio system`

Flowery detects the source sample rate and bit depth from the decoded stream. It does not run an application-side resampler. Android may resample or otherwise convert audio downstream according to the active route.

## Android media-session controls

`PlaybackService` handles SystemUI media-session commands on the service callback thread. Embedded audio metadata is read asynchronously because SAF document providers may block while opening or extracting tags. A monotonically increasing playback request generation prevents a delayed metadata result from replacing the metadata or playback request for a newer queue selection. Media-session metadata is published only when the selected title, artist, or duration changes; the periodic progress task updates playback position and state without repeatedly replacing session metadata or reposting the notification.

## Output routing

The application resolves Android-managed output targets: USB DAC, AUX, built-in speaker, and Bluetooth. On playback start, the route resolver detects connected devices; USB has priority, followed by AUX, Bluetooth, and speaker as the fallback.

The playback service monitors Android output-device additions and removals. If the monitored device snapshot changes while playback is active or paused, it reopens the current track on the newly resolved output, retaining the playback position and play/pause state. The current track or folder does not need to be selected again. Android continues to own system routing and downstream conversion.

## USB audio discovery

`UsbAudioDeviceScanner` enumerates devices exposed by Android USB Host and retains devices with Audio Class interfaces (or an Audio Class device-level declaration). It records Audio Control and Audio Streaming interface presence, interface protocol, alternate setting, and endpoint address, direction, transfer type, and maximum packet size. Audio outputs exposed by `AudioManager` are collected separately.

The native `usb_audio` module parses raw configuration descriptor bytes without VID/PID-specific tables. It extracts UAC protocol version, AudioStreaming alternate settings, PCM Type I channel/subslot/bit resolution, UAC1 discrete or continuous sample rates, and isochronous OUT endpoint metadata. UAC1 Type I channel/subslot/bit fields are read from their class-defined FORMAT_TYPE descriptor offsets. `plan_pcm_playback` selects a descriptor-advertised alternate matching the requested PCM parameters and exposes the result through JNI. The Android scanner can read raw descriptors after USB permission is granted and invoke the native parser/planner. UAC2 sample-rate selection still requires clock-source entity parsing and class-specific control requests; a plan is not a configured or active stream.\n\nOn Android, `UsbLibusbSession` keeps the permission-granted `UsbDeviceConnection` alive and passes its file descriptor to `libusb_wrap_sys_device`. The native session claims the selected AudioStreaming interface and sets its alternate setting; libusb releases the interface and closes its wrapper before Kotlin closes the Android connection. Do not use `UsbAudioControlSession` on the same interface concurrently. `libusb_wrap_sys_device` can return NOT_SUPPORTED on a platform backend that does not implement fd wrapping; this is surfaced as an error rather than silently falling back. This bridge does not yet submit transfers. The JNI `nativeReadUsbPcmChunk` path now pulls bounded float PCM chunks from the existing Symphonia decoder and packs them into signed little-endian USB Type I subslots with left-aligned valid bits. The transfer worker is not connected to this producer yet, so this path is a buffer-producer primitive, not playback.\n\nThe UAC parser now preserves the complete `wMaxPacketSize` field (including high-speed transaction multiplier bits) and associates explicit feedback IN endpoints or an OUT endpoint's synchronization address with the playback alternate. `usb_feedback` decodes full-speed 10.14 and high-speed 16.16 feedback packets and provides a bounded low-pass rate estimate. Feedback is parsed and decoded but is not yet polled by a live transfer worker.\n\nThe native `usb_isochronous` module now calculates service intervals and per-packet PCM frame/byte counts for full-speed frames and high-speed microframes. It accounts for the high-speed transaction multiplier in `wMaxPacketSize`, rejects reserved multiplier encodings, and distributes fractional rates such as 44.1 kHz without cumulative drift. These are tested scheduling primitives only: no URB is submitted and no PCM reaches the DAC yet. The bus speed must be established by the future native transport rather than guessed from VID/PID.\n\nThe two inventories are not joined by name or list position: `AudioDeviceInfo` does not expose USB VID/PID, so a reliable public-API association is unavailable. The presence of an isochronous OUT endpoint is reported only as a potential direct-USB streaming candidate. It is not proof of exclusive access, a supported streaming format, or bit-perfect playback.

When Android exposes a USB DAC through AudioManager, Flowery selects its Android output device ID and requests AAudio EXCLUSIVE sharing mode with the source sample rate and integer PCM format. For USB routes, failure to obtain EXCLUSIVE mode is terminal: the native backend does not retry in SHARED mode. The opened stream's actual device ID, sharing mode, sample rate, channel count, and PCM format are checked before playback.

This is Android-managed USB audio output, not direct USB Audio Class transport. Android's public USB Host transfer API does not expose isochronous transfers. The application does not claim the USB interface, submit isochronous transfers, negotiate UAC clocks, or bypass Android audio policy. AAudio EXCLUSIVE mode is not proof of bit-perfect output because downstream vendor DSP, system policy, USB scheduling, and DAC implementation still require physical-device validation. The descriptor parser is the first device-independent layer, not a complete USB playback engine. `UsbAudioControlSession` now owns a permission-checked Android USB connection, claims the selected AudioStreaming interface, selects the requested alternate, and provides explicit UAC1 endpoint sampling-frequency and UAC2 Clock Source SET_CUR control operations. It restores alternate zero on close when that alternate is exposed by Android. These control operations are preparation primitives only; callers must use descriptor-derived control capabilities and a valid UAC2 clock entity ID. They do not submit PCM data. The remaining critical transport work is to submit/cancel asynchronous isochronous OUT transfers through the wrapped libusb handle, refill transfer slots from `nativeReadUsbPcmChunk`, consume feedback endpoint packets in the event loop, and apply the feedback estimate to packet scheduling. It must It must support feedback endpoints for asynchronous devices, packet sizing for full/high-speed buses, suspend/disconnect recovery, and per-device validation. A native libusb/usbfs transport is the planned implementation path; it must not fall back to AAudio while reporting direct USB or bit-perfect status.

## PCM boundary

The decoder may use floating-point working samples internally. The AAudio stream is configured for signed-integer PCM. The PCM format contract is generic and is not coupled to a specific transport.


## Experimental asynchronous USB OUT transport

The Android native transport now owns a bounded ring of four libusb isochronous OUT transfers, each containing eight service packets. Completion callbacks only publish an atomic completion flag; the worker thread validates transfer and per-packet status, refills PCM, resubmits transfers, and drains cancellations before the session releases the interface. The worker validates decoder sample rate/channel count against the selected plan and rejects endpoint packet capacities that cannot carry the nominal PCM payload.

This is not yet a complete generic UAC implementation. The current worker uses the nominal sample-rate schedule and does not poll explicit feedback endpoints, negotiate UAC2 clock sources/ranges, or implement implicit feedback. UAC2 devices that require clock negotiation and asynchronous endpoints requiring feedback must not be assumed to stream correctly. The PCM producer currently uses the existing floating-point decoder path, so bit-perfect preservation is not guaranteed. No physical DAC validation has been completed.
