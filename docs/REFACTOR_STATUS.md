# Architecture Refactor Status

This document records the verified implementation state of the seven architecture workstreams. It distinguishes existing implementation from remaining migration work; a target design is not evidence that a migration is complete.

## Ownership invariants

- Kotlin owns Android framework integration: Activity and Compose lifecycle, Storage Access Framework descriptors and URI grants, USB permission and Android control transfers, output-device discovery, audio focus, MediaSession, notifications, and platform volume.
- Rust owns audio-domain policy and processing: demuxing and decoding, typed source samples, resampling, equalization, PCM and encoded producers, bounded audio queues, UAC descriptor interpretation, playback route eligibility, playback queue policy, playback lifecycle state, and the AAudio backend.
- C++ owns the direct USB transport: libusb context and handles, interface claims, endpoint setup, isochronous transfers, feedback handling, completion/cancellation, and transport teardown.
- Do not add a second decoder, DSP implementation, PCM format policy, or UAC descriptor parser in Kotlin or C++.

## Seven workstreams

| # | Workstream | Verified state | Remaining acceptance criteria |
| --- | --- | --- | --- |
| 1 | Single Rust USB descriptor parser | Implemented. UAC descriptor structure, profile selection, endpoint validation, clock linking/ranges, and Feature Unit capability interpretation are implemented in `native/src/usb_descriptors.rs`. Kotlin adapters map Rust results and perform Android-authorized control transfers. | Keep parser regression tests for malformed lengths, endpoint direction/type, UAC1/UAC2 profiles, synchronization, clock links, and volume capability. No independent Kotlin/C++ descriptor parser may be introduced. |
| 2 | Thin Kotlin playback adapter | Partially implemented. SAF extension resolution, USB diagnostics, USB host inventory, and USB PCM producer worker/envelope handling have been extracted into focused Kotlin adapters. | `FloweryPlayer.kt` still owns the cross-route worker lifecycle, Android route discovery, SAF descriptor lifetime, USB writer orchestration, and producer polling. Reduce this responsibility without moving Android APIs or native handle destruction across unsafe lifetime boundaries. |
| 3 | Rust-owned playback orchestration | Partially implemented. Rust owns route eligibility, playback lifecycle state, queue transitions, PCM/encoded producer policy, and output-buffer policy. | A complete native session scheduler is not yet implemented. It must own decoder/producer sequencing, seek epochs, cancellation, and output-state transitions while retaining explicit Kotlin adapters for SAF, USB permission/control transfers, Android output discovery, and the C++ transport. Do not claim a single cross-route engine until the same lifecycle contract is used by AAudio, PCM USB, and encoded USB. |
| 4 | JNI contract and lifetime safety | Partially implemented. CI checks Kotlin external declarations against Rust JNI and C++ exports. The Rust playback-controller handle now uses a registry of reference-counted states rather than exposing a raw allocation pointer; stale, invalid, duplicate-destroy, and unique-handle behavior has native unit coverage. | The signature check does not prove runtime handle safety. Other decoder, producer, queue, and AAudio handles still require an explicit lifetime audit and safe stale-handle rejection. Preserve exactly-once close/destroy and worker shutdown ordering before replacing the existing JNI envelope or handle contract. |
| 5 | Compose/UI decomposition | Partially implemented. Settings presentation, equalizer controls, tag dialogs, and Android playback adapters have focused source files. | Continue extracting destination-specific presentation from `FloweryNavigation.kt` and keep Activity/service state out of composables. UI refactoring must not change playback commands or persisted settings. |
| 6 | C++ transport isolation | Implemented as an ownership boundary. The application-owned C++ translation unit is `app/src/main/cpp/usb_audio_jni.cpp`, which implements libusb transport and transfer scheduling. AAudio is implemented in Rust. | Keep C++ limited to transport mechanics. Preserve bounded URBs, transfer-buffer lifetime until completion/reap, feedback validation, cancellation, interface release, and explicit close failures. Do not move decoding, DSP, PCM packing, route policy, or descriptor interpretation into C++. |
| 7 | Tests, documentation, and hardware verification | Software coverage and CI checks are present for Rust audio policy, descriptor parsing, PCM packing/queues, playback state, Android adapters, and JNI symbol consistency. | Require successful native tests, Android tests, native Android library build, and debug/release APK build. Separately validate real devices with USB protocol traces and external digital capture before making hardware-level bit-exact claims. |

## Safe migration order

1. Preserve and extend the Rust-only UAC descriptor parser and its fixtures.
2. Define an explicit native session ownership contract before moving more work out of `FloweryPlayer`.
3. Make native session shutdown wait for producer completion and transport cancellation/reaping; do not destroy JNI handles while a worker can still use them.
4. Move one route at a time behind the same session API. Keep Android permission, SAF descriptor acquisition, system output discovery, and platform callbacks in Kotlin.
5. Keep C++ as a transport consumer. Any future Rust-to-C++ queue bridge must specify ownership, backpressure, cancellation, and teardown before implementation; it must not expose borrowed Rust memory beyond its lock/lifetime.
6. Extract Compose destinations independently from the audio migration.
7. Require CI and device-level evidence at each milestone.

## Verification notes

A passing JNI declaration check verifies symbol presence and signature consistency only. Native unit tests verify software behavior only. CI packaging verifies that Android native libraries and APKs can be built. None of these alone proves a physical DAC accepts every advertised profile or that the DAC output is bit-exact. Hardware claims require external digital capture and, where applicable, USB protocol traces.
