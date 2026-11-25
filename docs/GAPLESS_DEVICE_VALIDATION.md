# Gapless Android Device Validation

This checklist is the final physical-device gate for AAudio gapless playback. Emulator CI and native callback tests are necessary but cannot establish audible continuity on a real Android audio device.

## Capture diagnostics

Connect the Android device with USB debugging enabled, then start a capture before playback:

```sh
adb logcat -c
adb logcat -v threadtime -s FloweryDiagnostics:I '*:S'
```

Save the output for the entire test sequence. Keep the device, Android audio route, app version, and power mode unchanged during each comparison.

## Test A — same-signature adjacent tracks

Use two local PCM tracks with identical sample rate, channel count, and sample format. Prefer two tracks long enough to reach steady-state playback, then repeat with a short second track.

1. Start track A and queue track B.
2. Repeat at least 20 boundaries.
3. Repeat with the order reversed.
4. Repeat once with a short track whose decoded PCM may reach EOF before the normal prebuffer watermark.

For each boundary, correlate:
- `AAUDIO_EARLY_TRACK_BOUNDARY generation=N`
- `AAUDIO_NEXT_PRODUCER_ATTACH_COMPLETE generation=N+1`
- `AAUDIO_NEXT_PRODUCER_PREBUFFER_READY` or `AAUDIO_NEXT_PRODUCER_PREBUFFER_TIMEOUT`
- `AAUDIO_NEXT_PRODUCER_PUMP_STARTED`
- `AAUDIO_NEXT_PRODUCER_SCHEDULER_STARTED`
- `AAUDIO_TRACK_BOUNDARY_DRAINED`

The next-generation records must report `handoffFromGeneration=N`. Record `boundaryTailBudgetMs`, `handoffElapsedMs`, `tailBudgetStatus`, `callbackUnderrunDelta`, `xrunDelta`, `handoffQueuedFrames`, and `handoffQueuedDurationMs`.

Pass criteria for a candidate clean boundary:
- Prebuffer reports ready, not timeout.
- Scheduler starts within the boundary tail budget.
- `callbackUnderrunDelta=0` and `xrunDelta=0`.
- The handoff queue is non-empty when the new scheduler starts.
- No audible silence, click, repeated fragment, or missing PCM is heard at the boundary.

A zero counter delta is not proof on its own. Listening or captured loopback is required. If possible, record the output with an external recorder or an Android-supported loopback capture and inspect the boundary waveform for a zero-filled gap.

## Test B — AAudio output reconfiguration

Use an AAudio-managed route (built-in speaker or a wired analog route handled by Android). USB Direct is a separate libusb path and must be validated separately.

Prepare adjacent files that force each signature change:
- 48 kHz stereo PCM16 → 44.1 kHz stereo PCM16 (sample-rate change).
- 48 kHz stereo PCM16 → 48 kHz mono PCM16 (channel-count change, if the device path supports it).
- 48 kHz stereo PCM16 → 48 kHz stereo PCM24 (sample-format/bit-depth change).
- 48 kHz stereo PCM16 → 48 kHz stereo Float32 (sample-format change, where supported).

For each pair:
1. Queue the second file before the first reaches EOF.
2. Confirm `AAUDIO_QUEUE_OUTPUT_REUSE_REJECTED` appears for the changed signature.
3. Confirm a new `AAUDIO_OUTPUT_SIGNATURE_READY` record has `reused=false` and reports the actual opened rate, format, channel count, device ID, and sharing mode.
4. Confirm playback continues on the new output without a crash or stale PCM.
5. Repeat at least five times per supported transition.

The output must not be reused when any signature field changes. A changed signature is an explicit reconfiguration boundary; it is not counted as sample-continuous gapless playback.

If Android cannot open a requested source format, record the exact playback error and native open diagnostics. Do not count an unsupported format as a pass.

## Test C — report results

For each run, record:
- Device model and Android version.
- Flowery version/commit.
- Output route and device ID.
- Source formats and order.
- Number of boundaries tested.
- Count of prebuffer timeouts.
- Maximum observed handoff latency and minimum observed boundary tail budget.
- Maximum callback-underrun and XRun deltas.
- Whether any audible or captured-loopback gap/click occurred.
- Whether each output reconfiguration opened the expected actual signature.

A physical validation pass requires repeated same-signature boundaries with no observed audible/loopback gap and no underrun/XRun increase, plus successful explicit reopen for each supported changed signature. Keep raw logcat and recordings with the report.

## Current limitation

The repository and CI environment cannot operate the user's physical Android device or listen to its DAC output. This checklist must be run on the target handset and route before gapless can be marked complete or ReplayGain work can begin.
