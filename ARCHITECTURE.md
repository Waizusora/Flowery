# Architecture

Flowery separates the Android application layer, Rust audio processing, and native C++ AAudio output. `MainActivity` provides the Compose UI. `LibraryScanner` traverses user-selected Storage Access Framework trees. `PlaybackService` manages foreground playback, media-session integration, queue controls, audio focus, shuffle and repeat. `FloweryPlayer` coordinates the streaming worker, output route, volume and seeking. `NativeAudio` declares the JNI boundary.

The streaming audio path opens a SAF document descriptor, decodes packets incrementally with Symphonia, resamples with a persistent Rubato instance when required, applies the native 31-band ISO equalizer, and writes PCM frames to a bounded single-producer/single-consumer ring buffer. The AAudio callback consumes frames. It does not perform file I/O, decoding, resampling, allocation, or mutex locking.

The streaming decoder supports mono and stereo. The equalizer requires 31 gains and constrains each band to -12 dB through +12 dB. Resampler and per-channel EQ history persist between chunks. Seeking flushes queued output, performs a format-level seek, resets decoder/DSP state, and discards preroll before playback resumes.

The library uses Storage Access Framework and `DocumentFile`; it does not use MediaStore. The foreground service maintains `MediaSessionCompat`, handles notification/media-button actions and audio focus, and responds to noisy-route events.

## USB DAC access

`UsbDacController` discovers attached USB Audio Class streaming interfaces and requests Android USB host permission. The permission result is shown to the user. This is only device access authorization; no USB isochronous audio transport is implemented, and the existing AAudio float output remains the playback path. The application does not claim bit-perfect playback or physical DAC button-volume control.

## Navigation and settings UI

`FloweryNavigation.kt` provides the four-icon navigation surface: Folders, Lyrics, DSP, and Settings. The Folders destination shows configured SAF roots and opens a folder-scoped track list. Playback requests from that list construct the queue from the selected folder's scanned tracks. Removing selected roots removes their app configuration and releases persisted URI permissions; it does not delete source documents.

Lyrics toggles the sidecar LRC presentation in the artwork area. DSP exposes software volume and the 31-band equalizer. Ordinary speaker, wired AUX/headphone, and Bluetooth volume follow Android's `STREAM_MUSIC` volume and its system safe-listening policy. USB output has a separate per-device software gain preference, initialized to 20% for a newly seen output. The current AAudio float pipeline does not provide hardware DAC button control or a bit-perfect USB transport. Settings groups audio information, library guidance, appearance, and other application options. The Other section contains battery-optimization access and an About entry. About contains Open Source, Translator, Contributor, and Update pages. The settings cards display category icons, and the content remains vertically scrollable. Audio information includes the current playback status and the implemented decode-to-output pipeline; per-stage timing and live DSP meters are not exposed. The Open Source page lists direct application and native dependencies with their declared versions where available. Auto appearance follows Android's system dark-mode setting. Language and translator/contributor credits remain concept pages until localization and confirmed credit records are available.
