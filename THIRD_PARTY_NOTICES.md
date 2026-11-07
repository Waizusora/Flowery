# Third-Party Notices

## libusb

Flowery integrates libusb through the community-maintained libusb-cmake CMake build repository, pinned to the `v1.0.30-0` tag. The bundled upstream libusb library is licensed under the GNU Lesser General Public License, version 2.1 or (at your option) any later version.

- Upstream project: https://github.com/libusb/libusb
- CMake integration: https://github.com/libusb/libusb-cmake
- License text: https://github.com/libusb/libusb/blob/master/COPYING

Flowery builds libusb as a shared library for Android. The exact upstream license text and corresponding source revision should be included with distributed application packages.


## Noto Sans

Flowery uses the Noto Sans variable font for its application typography. The Android build downloads the unmodified font from the Google Fonts repository at the immutable revision pinned in `app/build.gradle.kts` and packages it as an Android font resource. The build requires network access on a clean checkout unless the generated font resource is already present in the Gradle build directory.

- Font: Noto Sans variable (width and weight axes), `NotoSans[wdth,wght].ttf`
- Copyright: Copyright 2022 The Noto Project Authors
- License: SIL Open Font License 1.1 (OFL-1.1)
- License text: [LICENSES/NotoSans-OFL.txt](LICENSES/NotoSans-OFL.txt)
- Upstream source: https://github.com/google/fonts/tree/86aced91de05cd4f96a06838124cca45be337b2b/ofl/notosans

The bundled font covers its supported Latin, Greek, and Cyrillic glyphs. Other scripts use Android's platform font fallback; the Noto collection is modular and no single Noto Sans file covers every Unicode script.
