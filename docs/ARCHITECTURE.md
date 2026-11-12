# Architecture Reference

## Components

| Component | Responsibility |
| --- | --- |
| `app/src/main/java` | Android UI, library access, playback service, and route selection |
| `native/src/lib.rs` | JNI entry points and native module registration |
| `native/src/streaming.rs` | Incremental Symphonia decode and source-rate PCM frame production |
| `native/src/pcm_format.rs` | Generic signed-integer PCM format and quantization contract |
| `native/src/aaudio.rs` | Android AAudio output backend |
| `native/src/playback_session.rs` | Playback lifecycle, seek coordination, and worker ownership |

## Data flow

`SAF descriptor → Symphonia → source-rate PCM conversion → bounded PCM ring → AAudio → Android audio system`

Flowery detects the source sample rate and bit depth from the decoded stream. It does not run an application-side resampler. Android may resample or otherwise convert audio downstream according to the active route.

## Output routing

The only selectable application output targets are AUX, built-in speaker, and Bluetooth. Android manages device routing and any subsequent audio processing.

## PCM boundary

The decoder may use floating-point working samples internally. The AAudio stream is configured for signed-integer PCM. The PCM format contract is generic and is not coupled to a specific transport.
