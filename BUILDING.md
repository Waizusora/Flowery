# Building Flowery

## Requirements

- JDK 17
- Gradle 8.9
- Android SDK Platform 35 and Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Rust toolchain compatible with Rubato 5 (rustc 1.87 or newer)
- Rust targets `aarch64-linux-android`, `armv7-linux-androideabi`, and `x86_64-linux-android`
- `cargo-ndk`

The Android project uses minSdk 26, targetSdk 35, and Java/Kotlin target 17.

## Native build and tests

From the repository root:

```bash
cd native
cargo test --locked
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..
```

The CI workflow generates the Cargo lockfile before running locked tests. If you need to generate it locally, run `cargo generate-lockfile` from `native/` first.

## Android APKs

With the Android SDK, NDK and CMake installed:

```bash
gradle --no-daemon assembleDebug assembleRelease
```

APK outputs are under `app/build/outputs/apk/`. Release builds are unsigned unless signing is configured.

## Signing

Gradle reads `FLOWERY_KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Do not commit keystores or signing credentials.

## GitHub Actions

`.github/workflows/build.yml` runs on pushes to `main`, pull requests, tags matching `v*`, and manual dispatch. It configures JDK 17, Android SDK/NDK/CMake and Rust Android targets, runs native unit tests, builds native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, then assembles debug and release APKs. APKs are uploaded as the `flowery-apks` artifact. A `v*` tag also publishes a GitHub Release.

For signed CI releases, configure repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Without them, CI still builds an unsigned release APK.

## Troubleshooting

Install missing Rust targets with:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

Install cargo-ndk with:

```bash
cargo install cargo-ndk --locked
```

If the release APK is unsigned, verify that all signing environment variables and the keystore path are configured. Never commit credentials to resolve signing failures.

## Battery optimization prompt

The application declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and can open Android's per-app exemption request screen. The user must make the choice in Android system UI. The application does not silently change battery policy.
