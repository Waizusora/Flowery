# Architecture

This file is the repository-level entry point for the maintained architecture description.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the authoritative component map, Kotlin/Rust/C++ ownership boundaries, AAudio and direct USB data paths, JNI handle invariants, USB descriptor policy, teardown requirements, and staged migration target.

The design rule is:

- **Kotlin** owns Android APIs, Compose UI, SAF, USB permission, Android lifecycle and system integration.
- **Rust** owns audio decoding, DSP, PCM/encoded producers, format policy, USB descriptor parsing, queue policy and AAudio.
- **C++** owns the libusb transport, isochronous transfers, feedback handling and USB resource teardown.

Do not add a second decoder or DSP implementation in Kotlin/C++. Keep the Rust engine as the single source of truth for audio and descriptor policy, and keep Android-specific operations in Kotlin.
