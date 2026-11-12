# Third-Party Notices

This file identifies the direct third-party dependencies used by Flowery. Transitive dependency versions and notices are resolved by Cargo and Gradle during the build; consult their generated dependency reports for the complete transitive inventory.

## Rust dependencies

| Dependency | Version declaration | License |
| --- | --- | --- |
| Symphonia | 0.5.4 | MPL-2.0 |
| Lofty | 0.25.5 | MIT OR Apache-2.0 |
| jni | 0.21 | MIT OR Apache-2.0 |
| serde | 1.x | MIT OR Apache-2.0 |
| serde_json | 1.x | MIT OR Apache-2.0 |
| base64 | 0.22 | MIT OR Apache-2.0 |
| libc | 0.2 | MIT OR Apache-2.0 |

Rubato and audioadapter-buffers are no longer dependencies. Flowery does not perform application-side sample-rate conversion; it requests the decoded source rate from AAudio and leaves downstream conversion to Android.

## Android dependencies

The Android application uses AndroidX and Jetpack Compose libraries, including Activity Compose, Compose UI and Material 3, Lifecycle, Room, DataStore, Core KTX, Media, and DocumentFile. These libraries are distributed under the Apache License 2.0. Android platform APIs, including AAudio from the Android NDK, are platform components.

## Noto Sans

Flowery uses the Noto Sans variable font for its application typography. The Android build downloads the unmodified font from the Google Fonts repository at the immutable revision pinned in `app/build.gradle.kts`.

- Font: Noto Sans variable (width and weight axes), `NotoSans[wdth,wght].ttf`
- Copyright: Copyright 2022 The Noto Project Authors
- License: SIL Open Font License 1.1 (OFL-1.1)
- License text: [LICENSES/NotoSans-OFL.txt](LICENSES/NotoSans-OFL.txt)
- Upstream source: https://github.com/google/fonts/tree/86aced91de05cd4f96a06838124cca45be337b2b/ofl/notosans

The bundled font covers its supported Latin, Greek, and Cyrillic glyphs. Other scripts use Android's platform font fallback; the Noto collection is modular and no single Noto Sans file covers every Unicode script.
