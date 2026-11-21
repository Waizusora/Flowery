# USB PCM & AAudio Hardware Validation

This checklist validates the real Android audio-device lifecycle. Host CI can compile and unit-test routing policy, but it cannot validate USB host timing, physical DAC clocks, Android device callbacks, or audible playback.

## Expected route-transition policy

| Event | Selected output | Position after reopen | Playback |
|---|---|---:|---|
| USB DAC attached while AAudio is active | USB Direct | Track start (00:00, including the active CUE segment start) | Paused; user must press Play |
| USB DAC detached while USB Direct is active | Newest eligible AAudio output (LIFO) | Preserve elapsed track position | Paused; user must press Play |
| Switch among AAudio outputs (speaker, wired/AUX, Bluetooth) | Most recently connected eligible output (LIFO) | Preserve elapsed track position | Paused; user must press Play |
| Newest external output detached | Previous eligible output in arrival order | Preserve elapsed track position | Paused; user must press Play |
| No eligible external output remains | Built-in speaker | Preserve elapsed track position | Paused; user must press Play |

A route transition must never auto-resume playback. The old stream/session must be torn down before the replacement starts. The replacement stream is opened paused.

## Device test matrix

1. Start a 44.1 kHz track on the built-in speaker. Connect a USB DAC while playing.
   - Confirm OUTPUT_ROUTE_REOPEN reports target USB.
   - Confirm the player becomes paused and reports position 0 for the track.
   - Confirm there is no USB PCM until Play is pressed.
   - Press Play and verify playback starts from the beginning.
2. While USB Direct is playing, disconnect the DAC.
   - Confirm USB transfer cancellation and teardown complete.
   - Confirm fallback to the newest eligible AAudio output.
   - Confirm the player is paused and its elapsed position is retained.
   - Press Play and verify playback resumes at that position.
3. While AAudio is playing, connect wired/AUX, then Bluetooth.
   - Confirm the latest eligible connection wins (LIFO).
   - Confirm every transition pauses playback and preserves the elapsed position.
   - Confirm each transition requires a manual Play action.
4. Disconnect the newest external output.
   - Confirm the previous eligible output becomes selected.
   - Confirm no automatic playback occurs.
5. Repeat with 44.1, 48, 88.2, and 96 kHz sources where supported by the DAC; include 16-, 24-, and 32-bit PCM sources.
6. Repeat attach/detach while paused, during seek, near EOF, and during a CUE segment.
   - Verify there is no duplicate worker, stale callback, PCM reordering, or playback beyond the segment boundary.
7. Verify repeated rapid attach/detach events settle on the latest connected eligible output and do not leave a stale USB session or AAudio stream.

## Diagnostics to capture

Capture adb logcat -v threadtime filtered to:

- FloweryDiagnostics
- FloweryUsbNative
- AAudio

For each transition, record device name/type/ID, selected backend, sample rate, bit depth, playback position, USB transfer status, AAudio XRun count, and whether Play was pressed manually.

## CI versus physical validation

- AndroidOutputRouteSelectionTest covers deterministic LIFO selection and route-transition position policy.
- gradle testDebugUnitTest validates the JVM-side routing tests.
- Android native cargo ndk check --tests compiles Android-only AAudio test code; it does not execute it.
- A physical Android device and USB DAC are required to mark the hardware matrix above as passed. No physical DAC result should be inferred from CI.
