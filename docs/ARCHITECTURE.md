# Architecture

## Scope

Flowery is an Android application with a Kotlin application layer, a Rust audio-processing library, and a C++ AAudio output layer. The Android layer owns user interaction, document permissions, playback orchestration, and lifecycle integration. Rust owns compressed-audio decoding and DSP. C++ owns the real-time PCM output stream.

## Component map

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Compose UI, folder selection and management, library presentation, player controls, EQ controls, metadata display and tag editor |
| `LibraryScanner` | Recursive traversal of configured SAF document trees and sidecar LRC association |
| `LrcParser` | Convert native LRC results into UI models and request current-line lookup from Rust |
| `PlaybackService` | Foreground playback lifecycle, media session, notification actions, audio focus, queue and playback modes |
| `FloweryPlayer` | Open document descriptors, coordinate the streaming worker, manage output selection, volume, pause/resume and seeking |
| `NativeAudio` | Kotlin JNI declarations for native decoding, streaming, metadata and AAudio operations |
| `native/src/streaming.rs` | Symphonia streaming decoder, Rubato resampler, stateful 31-band EQ and format-level seek |
| `native/src/lib.rs` | JNI exports and Lofty tag read/write operations |
| `native/src/aaudio.rs` | AAudio stream lifecycle, bounded PCM ring buffer and callback |
 
## Playback sequence

1. The UI selects a track represented by a SAF content URI.
2. The playback service receives the request and delegates playback to `FloweryPlayer`.
3. `FloweryPlayer` opens a read-only `ParcelFileDescriptor` and passes its file descriptor and file extension to the native streaming decoder.
4. Rust probes the container, selects the default audio track, creates the Symphonia decoder, and reports source format information.
5. The Android layer chooses an output sample rate using available output-device information and AAudio probing.
6. Rust configures the output conversion and 31-band EQ. If the rates match, no sample-rate conversion is needed.
7. A worker thread reads decoded PCM chunks and writes available frames into the native ring buffer.
8. The AAudio callback consumes frames and writes them to the active output stream.
9. Playback state, position, duration and media metadata are published to the UI and Android media session.

## Threading and ownership

- The playback worker owns calls that read from and seek the native streaming decoder.
- The AAudio callback is the ring-buffer consumer.
- The worker/JNI producer is the ring-buffer producer.
- The ring uses monotonically increasing read/write frame counters and a power-of-two capacity.
- The callback must remain bounded and real-time friendly. It does not decode, resample, access files, allocate memory, or acquire a mutex.
- Playback generation values are used to prevent stale worker threads from stopping or updating a replacement track.
- Native decoder handles must be closed when their playback session ends.

## Playback service

`PlaybackService` is a non-exported foreground service with the `mediaPlayback` service type. It maintains a `MediaSessionCompat`, publishes playback metadata and state, exposes notification/media-button actions, and manages audio focus.

Transient focus loss pauses playback and can resume it when focus returns. Ducking applies a reduced output gain without overwriting the user's stored software-volume setting. The service also handles noisy-route events.

## Storage model

The library uses the Android Storage Access Framework rather than MediaStore. Kotlin traverses enabled roots through `DocumentFile` and reads CUE/playlist text because document access and provider permissions are Android responsibilities. Rust owns audio classification, same-directory sidecar matching, CUE/M3U/M3U8/PLS parsing and association, root-confined relative-path resolution, virtual-track creation, CUE time calculations, and catalog deduplication. Kotlin maps returned document URI values to Android `Uri` models.

## Native boundary

JNI methods are declared in `NativeAudio.kt`. Rust exports the decoder, seek, chunk-read, DSP, library policy, playlist, lyric-timeline and tag operations. The Rust AAudio module owns the AAudio stream and ring-buffer operations; C++ is reserved for the libusb transport. Keep JNI signatures synchronized between Kotlin declarations and native exported names.

## Important invariants

- The streaming decoder currently accepts mono and stereo layouts for the AAudio streaming path.
- The streaming equalizer requires exactly 31 gains.
- Equalizer gains are clamped to the supported range when coefficients are generated.
- Resampler and per-channel filter histories persist between chunks.
- A seek resets decoder/DSP state and discards seek preroll before exposing samples to the output queue.
- Ring-buffer flush must invalidate frames already queued before the seek.
- The AAudio callback must not perform work with unbounded execution time.
