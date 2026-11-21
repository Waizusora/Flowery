# Kotlin stdlib dependency alignment

The Android module is compiled with Kotlin Gradle Plugin 2.0.21. Some older transitive AndroidX/coroutines metadata requests the legacy `kotlin-stdlib-jdk7` and `kotlin-stdlib-jdk8` artifacts at 1.8.20.

The module dependency constraints align those compatibility artifacts to 2.0.21, matching the compiler/runtime already selected by the project. This avoids resolving an older stdlib line alongside Kotlin 2.0.21 while preserving the transitive libraries that request the compatibility artifacts.

CI must validate this through `gradle --no-daemon testDebugUnitTest`; a successful native Rust test run does not cover Android Gradle dependency resolution.
