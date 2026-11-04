# Architecture

Flowery separates the Android application layer, Rust audio processing, and native C++ AAudio output. `MainActivity` provides the Compose UI. `LibraryScanner` traverses user-selected Storage Access Framework trees. `PlaybackService` manages foreground playback, media-session integration, queue controls, audio focus, shuffle and repeat. `FloweryPlayer` coordinates the streaming worker, output route, volume and seeking. `NativeAudio` declares the JNI boundary.

The streaming audio path opens a SAF document descriptor, decodes packets incrementally with Symphonia, resamples with a persistent Rubato instance when required, applies the native 31-band ISO equalizer, and writes PCM frames to a bounded single-producer/single-consumer ring buffer. The AAudio callback consumes frames. It does not perform file I/O, decoding, resampling, allocation, or mutex locking.

The streaming decoder supports mono and stereo. The equalizer requires 31 gains and constrains each band to -12 dB through +12 dB. Resampler and per-channel EQ history persist between chunks. Seeking flushes queued output, performs a format-level seek, resets decoder/DSP state, and discards preroll before playback resumes.

The library uses Storage Access Framework and `DocumentFile`; it does not use MediaStore. The foreground service maintains `MediaSessionCompat`, handles notification/media-button actions and audio focus, and responds to noisy-route events.
