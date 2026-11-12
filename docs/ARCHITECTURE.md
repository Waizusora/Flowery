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
| `native/src/aaudio.rs` | Android AAudio output backend, including requested-device selection and verification |
| `native/src/playback_session.rs` | Playback lifecycle, seek coordination, and worker ownership |

## Data flow

`SAF descriptor → Symphonia → source-rate PCM conversion → bounded PCM ring → AAudio → Android audio system`

Flowery detects the source sample rate and bit depth from the decoded stream. It does not run an application-side resampler. Android may resample or otherwise convert audio downstream according to the active route.

## Android media-session controls

`PlaybackService` handles SystemUI media-session commands on the service callback thread. Embedded audio metadata is read asynchronously because SAF document providers may block while opening or extracting tags. A monotonically increasing playback request generation prevents a delayed metadata result from replacing the metadata or playback request for a newer queue selection. Media-session metadata is published only when the selected title, artist, or duration changes; the periodic progress task updates playback position and state without repeatedly replacing session metadata or reposting the notification.

## Output routing

The application exposes three Android-managed targets: AUX, built-in speaker, and Bluetooth. On playback start, the route resolver detects connected devices; AUX has priority over Bluetooth, and speaker is the fallback.

The playback service monitors Android output-device additions and removals. If the monitored device snapshot changes while playback is active or paused, it reopens the current track on the newly resolved output, retaining the playback position and play/pause state. The current track or folder does not need to be selected again. Android continues to own system routing and downstream conversion.

## USB audio discovery

`UsbAudioDeviceScanner` enumerates devices exposed by Android USB Host and retains devices with Audio Class interfaces (or an Audio Class device-level declaration). It records Audio Control and Audio Streaming interface presence, interface protocol, alternate setting, and endpoint address, direction, transfer type, and maximum packet size. Audio outputs exposed by `AudioManager` are collected separately.

The two inventories are not joined by name or list position: `AudioDeviceInfo` does not expose USB VID/PID, so a reliable public-API association is unavailable. The presence of an isochronous OUT endpoint is reported only as a potential exclusive-output candidate. It is not proof of exclusive access, a supported streaming format, or bit-perfect playback.

Android's public USB Host transfer API does not expose isochronous transfers. This implementation therefore provides discovery only; it does not claim USB Audio Class streaming, interface exclusivity, sample-rate negotiation, or bit-perfect verification. A future direct USB backend requires a native USB transport/driver with isochronous support and device-level validation. The existing AAudio backend remains unchanged.

## PCM boundary

The decoder may use floating-point working samples internally. The AAudio stream is configured for signed-integer PCM. The PCM format contract is generic and is not coupled to a specific transport.
