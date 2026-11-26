# AAudio streaming performance audit

## Objective

Establish a reproducible device-side baseline for the AAudio streaming path after the
ReplayGain and reusable PCM-buffer changes. This stage is measurement-only: do not tune
ring capacity, worker polling, callback behavior, thread priority, or decoder chunk size
until the baseline has been captured and reviewed.

USB Direct is a separate transport and is not part of the AAudio measurements in this
document. Its existing validation remains a regression guard.

## Current implementation inventory

The AAudio decoder worker owns and reuses two output buffers for its lifetime:

- `pcm_samples: UsbPcmSamples` retains the typed PCM vector capacity between chunks.
- `pcm_buffer: Vec<u8>` retains the encoded output-byte capacity between chunks.

The typed pending decoder buffer is also retained after draining. Capacity may still grow
when a larger chunk or format is encountered; the code does not currently expose a count
of such growth events. This is a code-path inventory, not a measured allocation profile.

The callback is the sole SPSC consumer. It pops into AAudio's supplied destination,
applies output gain in place, zero-fills any missing tail, and updates atomic playback
counters. It does not acquire the engine mutex or allocate a PCM staging buffer.

## Existing runtime counters

`FloweryPlayer.audioPipelineDiagnostics()` reports AAudio counters as:

| Field | Meaning |
|---|---|
| `callbackUnderruns` | Callback requests for which the ring did not contain the full requested frame count, excluding normal EOF tail padding. |
| `xrunCount` | AAudio stream XRun count from `AAudioStream_getXRunCount`. |
| `queuedFrames` | Current frames queued in the producer/consumer ring. |
| `ringCapacityFrames` | Configured SPSC ring capacity in frames. |
| `bufferSizeFrames` | Current AAudio hardware buffer size. |
| `framesPerBurst` | Device-reported AAudio burst size. |
| `bufferCapacityFrames` | Maximum AAudio hardware buffer size. |
| `actualRateHz` | Actual opened stream sample rate. |

Take snapshots at the start and end of each run. Compare counter deltas, not only final
absolute values. A non-zero callback underrun or XRun delta during steady playback needs
investigation; expected EOF zero-padding is not counted as an underrun.

## Device test protocol

Keep the same device, Android build, output route, DAC (if any), track files, output
format, and screen/power conditions between runs. Record the device model, Android
version, Flowery commit, output sample rate/format, channel count, route, and whether
ReplayGain is enabled.

For each workload:

1. Force-stop Flowery, launch it, and start the selected AAudio track or queue.
2. Capture the initial diagnostic snapshot from the app's audio-pipeline diagnostics.
3. Record process memory and CPU after the stream has stabilized.
4. Run the workload without interacting with the UI except where the workload requires
   next/previous or seek.
5. Capture the final diagnostic snapshot and process memory/CPU.
6. Record audible dropouts, stalls, clicks, unexpected gaps, and any process restart.

Suggested Android shell observations (replace the package name only if it changes):

```sh
adb shell dumpsys meminfo com.waizusora.flowery
adb shell top -b -n 1 | grep -i flowery
```

Use the same sampling interval and observation duration for all comparison runs. Android
process memory and CPU are device-level observations; they are not substitutes for a
native allocation profiler.

## Workload matrix

| Run | Workload | Minimum observation |
|---|---|---:|
| A | Continuous 44.1 kHz stereo playback | 30 minutes |
| B | Continuous 48 kHz stereo playback | 30 minutes |
| C | Continuous high-rate playback (96 kHz if supported by the device/route) | 20 minutes |
| D | Gapless queue with repeated track transitions | 30 transitions |
| E | Repeated seek forward/backward during playback | 20 seeks |
| F | ReplayGain Track and Album playback, including Prevent Clipping | 10 minutes per mode |
| G | Screen off / background playback under normal power conditions | 30 minutes |

Run each supported workload at least twice. Mark unsupported sample rates/routes as
not applicable rather than silently substituting another configuration.

## Result sheet

For every run, record:

- Commit SHA and device/Android build.
- Input codec, sample rate, bit depth, channels, output format and output rate.
- Route and sharing mode.
- Duration, number of track transitions, and number of seeks.
- Initial/final callback-underrun and XRun counters (and their deltas).
- Initial/final queued frames, ring capacity, AAudio buffer size/capacity, and burst size.
- Initial/final process PSS/total memory and observed CPU range.
- Audible result and any lifecycle/recovery events.

## Interpretation and next gate

Do not infer allocation counts from stable RSS/PSS. To quantify allocation/reallocation
frequency, use a native allocation profiler or add narrowly scoped worker-buffer capacity
telemetry in a separate measurement change; do not add per-chunk logging or allocation
work to the real-time callback.

Do not change queue sizing or worker scheduling based on one run. First compare repeated
runs and separate decoder starvation (ring queue depletion / callback underruns) from
device scheduling (AAudio XRuns). Only propose an optimization when a repeatable metric
identifies the bottleneck, and rerun the same workload matrix afterward.

## Current baseline status

- Source-level buffer ownership reviewed.
- Existing AAudio underrun, XRun, queue, and hardware-buffer diagnostics identified.
- Device-side measurements: pending.
- Native allocation profile: pending.
- No performance tuning has been applied in this audit stage.
