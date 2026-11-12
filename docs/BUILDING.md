# Building Flowery

## Prerequisites

Install the following tools:

- JDK 17
- Gradle 8.9
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Rust stable toolchain with rustc 1.87 or newer
- Rust targets:
  - `aarch64-linux-android`
  - `armv7-linux-androideabi`
  - `x86_64-linux-android`
- `cargo-ndk`

The Android Gradle configuration sets minSdk 26, targetSdk 35, and Java/Kotlin target 17.

## Build the Rust library

From the repository root:

```bash
cd native
cargo test --locked
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release
cd ..
```

If `Cargo.lock` is not present or is intentionally being refreshed, generate it before using `--locked`:

```bash
cd native
cargo generate-lockfile
cargo test --locked
```

## Build Android APKs

With Android SDK, NDK, and CMake installed:

```bash
gradle --no-daemon assembleDebug assembleRelease
```

Typical output locations:

- Debug APK: `app/build/outputs/apk/debug/`
- Release APK: `app/build/outputs/apk/release/`

A release build is unsigned unless signing is configured.

## Configure release signing

The Gradle build reads these environment variables:

- `FLOWERY_KEYSTORE_PATH`: filesystem path to a JKS or PKCS12 keystore.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: signing key password.

Do not place signing material in source control. Keep keystore files and passwords in a secure secret store.

## GitHub Actions

The workflow at `.github/workflows/build.yml` runs for:

- Pushes to `main` and `trial`.
- Pull requests.
- Tags matching `v*`.
- Manual workflow dispatch.

The build job configures JDK 17 and Gradle 8.9, installs Android SDK Platform 35, Build Tools 35.0.0, and NDK 27.2.12479018, then installs Rust stable with Android targets. It generates the native Cargo lockfile, runs native Rust unit tests, checks the Kotlin/native JNI contract, installs `cargo-ndk`, runs Android JVM unit tests, builds native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, and assembles debug and release APKs.

The workflow uploads APKs as the `flowery-apks` artifact. A tag matching `v*` also publishes the release APK through GitHub Releases.

Configure these repository secrets for signed CI releases:

- `KEYSTORE_BASE64`: Base64-encoded keystore contents.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: signing key password.

If the secrets are absent, CI still assembles the release variant but does not sign it.

## Troubleshooting

### Rust target is missing

Install the Android targets with rustup:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

### cargo-ndk is unavailable

Install it with:

```bash
cargo install cargo-ndk --locked
```

### Native build cannot find the Android NDK

Confirm that Android Studio/SDK Manager installed NDK version `27.2.12479018` and that the Android Gradle Plugin can locate the SDK.

### Release APK is unsigned

This is expected unless a keystore path and all signing credentials are configured. Never commit credentials to resolve this warning.
