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


## Extended stress protocol

### USB DAC hot-plug / hot-unplug

Run on a physical Android device with USB host/OTG support and a class-compliant DAC:

1. Perform at least 100 attach/detach cycles while the player is idle, then 100 cycles while paused, then 100 cycles while playing. Keep the same test track and record each cycle number.
2. During a playing cycle, unplug the DAC at different points: immediately after Play, during steady-state PCM, while seeking, and within the final 2 seconds of a track/CUE segment.
3. After every unplug, require all submitted isochronous and feedback transfers to receive cancellation callbacks before the USB session and file descriptor are released.
4. Reconnect the same DAC and verify the session can be opened cleanly. Also repeat with a second DAC to detect stale device/session identity.
5. Treat a worker join timeout, transfer-drain timeout, panic, or retained-session diagnostic as a failed cycle. Never force-close a session whose transfer ownership is uncertain.

Expected invariants:
- No use-after-free, native crash, ANR, stuck USB permission state, duplicate transfer worker, or permanently wedged route.
- A failed transfer drain must retain the native session/connection rather than free callback-owned buffers.
- Repeated events must settle on the latest eligible route; manual Play remains required after route transitions.

### AAudio teardown race

1. Repeat at least 100 AAudio open/start/pause/stop/close cycles on the built-in speaker.
2. Repeat while switching between speaker, wired/AUX, and Bluetooth. Include rapid device arrival/removal and seek requests issued immediately before teardown.
3. Verify the scheduler thread is joined before the AAudio stream and callback-owned output buffer are released.
4. Verify callbacks stop after stream close, the next stream opens without stale scheduler activity, and no old session can publish a seek after cancellation.
5. Capture AAudio state, device ID, XRun count, scheduler status, and native errors for each cycle.

A cycle fails on a native crash, callback after close, stale PCM from the previous session, duplicate scheduler, unbounded teardown wait, or an unexpected automatic resume.

### Seek / position accuracy

Use the same lossless test file with known duration and deterministic seek points (0 ms, 1 s, 10 s, midpoint, and the final 2 s). Repeat on USB PCM and each AAudio route.

- After seek completes, the reported playback position must be within one PCM frame of the decoder's actual seek position; UI millisecond display may truncate by less than 1 ms.
- Confirm the first audible PCM after seek corresponds to the requested decoder position, not buffered pre-seek samples.
- Seek repeatedly while playing and paused; then unplug the DAC during an in-flight USB seek and switch AAudio routes during an in-flight AAudio seek.
- Confirm CUE seeks remain clamped to the active segment bounds and never leak samples from adjacent tracks.
- Record requested milliseconds, decoder-returned milliseconds, sample rate, frame position, and the first post-seek timestamp.

Useful capture command:

```sh
adb logcat -c
adb logcat -v threadtime FloweryDiagnostics:I FloweryUsbNative:I AAudio:I '*:S'
```

These cycle counts are a manual hardware stress plan, not a claim that CI executed physical USB or AAudio devices.

## CI versus physical validation

- AndroidOutputRouteSelectionTest covers deterministic LIFO selection and route-transition position policy.
- gradle testDebugUnitTest validates the JVM-side routing tests.
- Android native cargo ndk check --tests compiles Android-only AAudio test code; it does not execute it.
- A physical Android device and USB DAC are required to mark the hardware matrix above as passed. No physical DAC result should be inferred from CI.
