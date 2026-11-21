# Third-Party Notices

This notice lists direct production dependencies used by the Flowery Android application and its native Rust runtime. Build plugins, test-only dependencies, and transitive dependencies are excluded from this direct-dependency inventory. Transitive notices must be retained from their upstream distributions.

## Rust native runtime

| Dependency | Version declaration | Application use | License |
| --- | --- | --- | --- |
| Symphonia | 0.6.1 | Audio demuxing and decoding | MPL-2.0 |
| symphonia-opus | 0.1.1 | Pure-Rust Opus decoding | MPL-2.0 |
| Lofty | 0.25.5 | Audio metadata and tag reading/writing | MIT OR Apache-2.0 |
| jni | 0.21 | Rust/JVM native interface | MIT OR Apache-2.0 |
| serde | 1.x | Data serialization | MIT OR Apache-2.0 |
| serde_json | 1.x | JSON serialization | MIT OR Apache-2.0 |
| yaml_serde | 0.10 | Serde-compatible YAML parsing for lyrics | MIT OR Apache-2.0 |
| base64 | 0.22 | Embedded artwork encoding | MIT OR Apache-2.0 |
| libc | 0.2 | Android native system bindings | MIT OR Apache-2.0 |
| rtrb | 0.3.6 | Bounded SPSC PCM transport | MIT OR Apache-2.0 |
| libusb1-sys | 0.7 | Rust USB bindings | MIT |
| libusb (vendored) | bundled by libusb1-sys | USB host transport implementation | LGPL-2.1-or-later |
| ureq | 2.12 | HTTPS client for lyrics lookup | MIT OR Apache-2.0 |
| url | 2.x | URL parsing and form encoding | MIT OR Apache-2.0 |

## Android application runtime

| Dependency | Version declaration | Application use | License |
| --- | --- | --- | --- |
| Jetpack Compose UI | Compose BOM 2024.12.01 | Compose UI, Material 3, and extended icons | Apache-2.0 |
| Activity Compose | 1.9.3 | Android activity integration | Apache-2.0 |
| DocumentFile | 1.0.1 | Document provider access | Apache-2.0 |
| AndroidX Core KTX | 1.15.0 | Android platform compatibility utilities | Apache-2.0 |
| AndroidX Media | 1.7.0 | Media session and media integration | Apache-2.0 |

## Noto Sans

Flowery bundles the Noto Sans variable font for application typography. The Android build downloads the unmodified font from the Google Fonts repository at the immutable revision pinned in `app/build.gradle.kts`.

- Font: Noto Sans variable (width and weight axes), `NotoSans[wdth,wght].ttf`
- Copyright: Copyright 2022 The Noto Project Authors
- License: SIL Open Font License 1.1 (OFL-1.1)
- License text: [LICENSES/NotoSans-OFL.txt](LICENSES/NotoSans-OFL.txt)
- Upstream source: https://github.com/google/fonts/tree/86aced91de05cd4f96a06838124cca45be337b2b/ofl/notosans

The bundled font covers its supported Latin, Greek, and Cyrillic glyphs. Other scripts use Android's platform font fallback.

## Android platform APIs

AAudio is provided by the Android NDK and is an Android platform API. It is not bundled as a third-party application library.

## libusb distribution requirements

Flowery builds the libusb C library through the vendored `libusb1-sys` dependency for Android targets. The Rust bindings are MIT-licensed; the linked libusb library is LGPL-2.1-or-later. APK distribution must include the applicable LGPL license text and corresponding source and satisfy the LGPL requirements for the application's static native linkage, including applicable relinking provisions. Verify the final packaging and source-availability process before publishing APKs.
