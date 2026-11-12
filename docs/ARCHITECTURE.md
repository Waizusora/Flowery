# Architecture Reference

## Components

| Component | Responsibility |
| --- | --- |
| `app/src/main/java` | Android UI, library access, playback service, route selection, and device-change handling |
| `AndroidOutputRouteResolver.kt` | Detects connected Android outputs and resolves AUX, speaker, or Bluetooth device IDs |
| `PlaybackService.kt` | Owns the media session, queue, audio-device callback, and output-route reopen orchestration |
| `PlaybackCoordinator.kt` | Coordinates playback lifecycle and reopens the active track at a captured position |
| `native/src/lib.rs` | JNI entry points and native module registration |
| `native/src/streaming.rs` | Incremental Symphonia decode and source-rate PCM frame production |
| `native/src/pcm_format.rs` | Generic signed-integer PCM format and quantization contract |
| `native/src/aaudio.rs` | Android AAudio output backend, including requested-device selection and verification |
| `native/src/playback_session.rs` | Playback lifecycle, seek coordination, and worker ownership |

## Data flow

`SAF descriptor → Symphonia → source-rate PCM conversion → bounded PCM ring → AAudio → Android audio system`

Flowery detects the source sample rate and bit depth from the decoded stream. It does not run an application-side resampler. Android may resample or otherwise convert audio downstream according to the active route.

## Output routing

The application exposes three Android-managed targets: AUX, built-in speaker, and Bluetooth. On playback start, the route resolver detects connected devices; AUX has priority over Bluetooth, and speaker is the fallback.

The playback service monitors Android output-device additions and removals. If the monitored device snapshot changes while playback is active or paused, it reopens the current track on the newly resolved output, retaining the playback position and play/pause state. The current track or folder does not need to be selected again. Android continues to own system routing and downstream conversion.

## PCM boundary

The decoder may use floating-point working samples internally. The AAudio stream is configured for signed-integer PCM. The PCM format contract is generic and is not coupled to a specific transport.
