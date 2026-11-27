# USB DAC bit-perfect path audit

This audit covers Flowery's native USB Audio playback path on the `try` branch.
It describes transformations performed by Flowery before submitting isochronous
USB transfers. It does not certify the DAC hardware, USB host controller, or
analog output.

## Data path

1. `StreamDecoder` records the source sample rate, channel count, precision,
   and Symphonia sample representation when opening the track.
2. Decoded audio remains in the typed `UsbPcmSamples` variants. Integer
   `U8`, `S16`, `S24`, and `S32` samples are not normalized through
   floating-point PCM.
3. `validate_usb_stream_plan` requires the selected USB plan's sample rate,
   channel count, data-format code, and valid bit resolution to match the
   decoder metadata.
4. `read_usb_pcm_chunk` repeats the source precision and data-format checks at
   the final encoding boundary, then packs the typed samples into the selected
   USB subslot layout.
5. `fill_and_submit` copies those encoded bytes into isochronous packet
   buffers and submits them through libusb. It does not apply software volume,
   normalization, EQ, mixing, or resampling.

## Format behavior

- Integer PCM 16/24/32-bit remains on integer sample variants through packing.
- Symphonia FLAC/ALAC output represented as left-aligned `i32` is packed to
  the source valid-bit width, including 24-bit data in either 3-byte or
  4-byte subslots.
- The decoder's source sample rate is used for the USB PCM format and must
  equal the selected endpoint plan's rate. The packet scheduler changes packet
  frame counts to follow the USB service interval and clock; it does not
  resample audio.
- `f64` used by USB feedback handling is clock-rate arithmetic only, not an
  audio sample representation.
- Native `F32` source audio has a separate USB IEEE-float packing path. It is
  selected only for Float32 source samples and must match a DAC descriptor that
  advertises the corresponding IEEE-float format; it is not an integer-PCM
  conversion. `F64` source audio is not supported by the exposed USB data-format
  contract and must not be treated as standard USB Audio Type I PCM.
- `f64` in the feedback controller represents clock feedback / packet scheduling
  values. It is deliberately separate from the audio sample buffer and does not
  imply Float64 PCM support.

## USB format and clock negotiation

- UAC1 format tags are treated as a single advertised Type I format. UAC2
  `bmFormats` is treated as a bitmask, so one alternate may advertise PCM,
  PCM8, and/or IEEE Float32 simultaneously. The planner selects only the
  requested format when its bit is present.
- UAC2 playback resolves the selected streaming terminal's clock topology,
  reads Clock Source `GET_RANGE` before changing frequency, checks selector
  and multiplier paths, verifies `GET_CUR` after `SET_CUR` when the source is
  host-programmable, and restores the previous clock/selector state if native
  stream startup fails.
- Clock Source frequency control (`bmControls` bits 1:0) follows the UAC2
  bit-pair encoding: `0b00` absent, `0b01` present and read-only, and
  `0b11` present and host-programmable. The specification reserves `0b10`;
  Flowery deliberately tolerates it as writable for compatibility with the
  Linux USB audio driver's high-bit test. This compatibility exception applies
  to Clock Source frequency control only; it must not be generalized to other
  descriptor controls.
  - Host-programmable (`0b11`, or compatibility-tolerated `0b10`): `SET_CUR` is issued whenever
    `GET_CUR` differs from the required rate and the result is verified with
    `GET_CUR`. A failed write or verification rejects the clock path and
    triggers rollback. This prevents a DAC from retaining a stale rate (for
    example 384 kHz while 96 kHz PCM is streamed), which would consume samples
    at the wrong speed and shift the pitch by the ratio of the two rates.
  - Read-only (`0b01`): `GET_RANGE` is the capability gate. If the requested
    rate is advertised, negotiation succeeds without requiring `GET_CUR` to
    already match and without issuing `SET_CUR`; the active rate is expected to
    follow the chosen AudioStreaming alternate setting.
  - Absent (`0b00`): no Clock Frequency control exists, so Flowery must not send
    `GET_RANGE` or `GET_CUR`. Because UAC2 AudioStreaming descriptors do not
    provide a substitute discrete-rate list, the engine fails closed rather than
    claiming that the requested rate is supported.
  - `UAC2_CLOCK_READY` logs `sourceCurrentHz` (before negotiation) and
    `sourceActiveHz` (after negotiation). A `sourceActiveHz` that differs from
    `sourceRequiredHz` indicates the clock was not aligned.
  - The same bit-pair rules apply to Clock Selector controls.
- These control-transfer checks are covered by Kotlin fake-control tests;
  actual DAC behavior and host-controller timing still require hardware tests.

## Isochronous service interval

- The runtime and pure packet scheduler share one USB 2.0 `bInterval`
  conversion: the period is `2^(bInterval-1)` bus intervals.
- Full-Speed uses 1 ms frames; High-Speed uses 125 μs microframes. Therefore,
  Full-Speed `bInterval=3` is 4 ms, not 3 ms. Values outside 1–16 are rejected.
- Regression tests cover the Full-Speed exponent (including the upper descriptor
  value) and the High-Speed microframe period. This is descriptor/scheduler
  validation; actual controller scheduling still requires hardware testing.

## Asynchronous playback feedback association

- For asynchronous USB Audio OUT endpoints, the parser reads
  `bSynchAddress` from the standard 9-byte endpoint descriptor. The explicit
  feedback IN endpoint is accepted only when its endpoint address exactly
  matches that value and its descriptor is present in the same alternate
  setting.
- The feedback endpoint's address, maximum packet size, and service interval
  are carried into the stream plan only after that association succeeds.
  Merely finding an IN feedback endpoint in the same alternate setting is not
  sufficient.
- An asynchronous OUT endpoint with no usable `bSynchAddress`, no matching
  feedback endpoint, or invalid feedback parameters is rejected. Implicit
  feedback is not implemented and is not advertised as supported.
- Parser/planner regression tests cover a valid association, a mismatched
  feedback address, and a legacy 7-byte OUT endpoint descriptor that cannot
  provide the association. These tests validate descriptor handling, not
  feedback timing on a physical DAC or host controller.

## Explicit feedback transfer lifecycle

- The feedback IN transfer is kept in flight independently of the audio OUT
  ring. The worker consumes its completion signal, validates transfer status,
  packet status, and exact packet length, decodes one clock sample, then
  resubmits the feedback transfer. Only the worker thread resubmits; callbacks
  publish completion state and do not mutate the estimator.
- The first valid feedback sample is required before asynchronous audio OUT
  packets are submitted. The parser carries the associated feedback endpoint's
  own `bInterval` into the playback plan. Runtime converts it with the USB 2.0
  isochronous service-interval rule for the negotiated bus speed; it does not
  substitute the audio OUT endpoint interval or guess from device identity.
- Feedback clock staleness is evaluated after at least two advertised feedback
  polling periods (with a 250 ms minimum). The feedback transfer deadline is
  at least three polling periods (with a 500 ms minimum). This avoids treating
  a descriptor-advertised slow feedback endpoint as stalled merely because
  its next legal poll is later than a fixed timeout.
- If a valid clock sample becomes stale while the feedback transfer itself is
  still completing normally, the worker enters `Quiescing`: it stops new audio
  OUT submissions and lets every already-submitted audio transfer complete.
  Only after the audio ring is empty does it reset the packet scheduler's
  fractional phase and enter `AwaitingFreshFeedback`.
- Feedback samples observed during `Quiescing` are validated but not used to
  seed the new clock epoch. The first valid sample after the ring drains
  initializes a reset estimator; `Repriming` then allows the audio ring to be
  filled again. The first successful audio submission returns the lifecycle
  to `Running`. No stale estimate or old fractional scheduler phase is reused.
- A failed feedback transfer, invalid sample, repeated abrupt clock outliers,
  or event-loop failure remains fail-closed. The reacquisition lifecycle has an
  absorbing `Failed` state: the worker latches it whenever the stream status
  becomes terminal, and ring-drain notifications or late feedback cannot move
  it back to `AwaitingFreshFeedback`, `Repriming`, or `Running`. Audio
  submission is permitted only in `Running` or `Repriming`, in addition to
  the worker's independent stop/error/EOF gates. Cancellation/drain ownership
  rules are unchanged; if draining times out, callback-visible buffers and
  the libusb session are retained to prevent use-after-free.
- Pure tests cover the reacquisition transitions, estimator and scheduler
  reset, interval-derived deadlines, packet completion validation, and
  feedback sample validation. Deterministic integration simulations additionally
  cover a delayed-but-not-stale sample, stale feedback followed by ring drain
  and successful fresh-clock recovery, out-of-order audio transfer completion,
  and a disconnected feedback transfer that latches the absorbing terminal
  state and refuses further audio submissions even after ring drain and a
  late valid-looking feedback sample. The
  simulations inject transfer events without USB hardware; they do not replace
  timing validation with physical DACs and Android host controllers.

## Hardware volume (UAC Feature Unit)

- Volume is read and written only through a descriptor-advertised Feature Unit
  volume control. For UAC2, the per-channel `bmaControls` bit pair uses the same
  encoding as above (`0b01` read-only, `0b11` programmable). No VID/PID
  heuristics are used.
- Request codes are protocol-specific. UAC1 uses `GET_CUR=0x81` and discrete
  `GET_MIN/MAX/RES` (`0x82`–`0x84`). UAC2 uses `CUR=0x01` and `RANGE=0x02`
  (the same codes as Clock Source negotiation). Sending UAC1 codes to a UAC2
  Feature Unit causes every volume transfer to fail (`transferred=-1`).
- Two control paths are supported:
  1. **USB Direct session** (`UsbLibusbSession` / `UsbNativePlaybackSession`):
     Feature Unit transfers share the open libusb connection. Multi-channel
     writes are transactional: a failed `SET_CUR` verification rolls back
     channels already changed.
  2. **Standalone control** (`UsbDacHardwareVolume`): short-lived
     `UsbDeviceConnection` control transfers on the Audio Control interface
     only. No AudioStreaming interface is claimed. This path enables Settings →
     Volume when a permitted USB DAC is present even if USB Direct playback is
     not active (including AAudio USB routing). Physical DAC keys, when present,
     typically adjust the same Feature Unit; the host reads the current level
     via `GET_CUR`.
- Path selection in `FloweryPlayer.setVolume`: USB Direct session when active;
  otherwise standalone Feature Unit control when a writable unit is detected;
  otherwise Android `STREAM_MUSIC` system volume.
- Auto-detection: `PlaybackService` periodically calls
  `refreshStandaloneUsbVolumeDetection()` so attach/detach of a permitted DAC
  updates Settings without requiring playback start. Detection requires USB
  Host permission for the device.
- Every `SET_CUR` is verified with `GET_CUR`. On the USB Direct path a mismatch
  rolls back the channels already written and is reported as a failed command;
  the UI then shows the value the device actually reports.
- `GET_RANGE` is preferred. Some DAC firmware advertises a programmable volume
  control but STALLs `GET_RANGE` while honouring `GET_CUR`/`SET_CUR`. On the
  USB Direct path only, an attenuation-only range may be assumed: the maximum
  is the greater of 0 dB and the current value (the host never boosts above
  unity gain) and the minimum is -90 dB. The assumption is logged as
  `USB_VOLUME_RANGE_ASSUMED`, and the refusal is cached for the session so that
  periodic polling does not repeat a request known to STALL.
- If `GET_CUR` is also unavailable, the volume control is reported as
  unavailable and no write is attempted.
- Actual DAC behavior still requires hardware validation.

## Android routing and volume isolation

- `FloweryPlayer` routes a detected USB output to `UsbNativePlaybackSession`
  and returns before opening the AAudio stream. AUX, speaker, and Bluetooth
  continue through Android-managed AAudio output.
- The USB route does not call the AAudio output-volume callback. System media
  volume changes and software ducking are bypassed while direct USB playback is
  active; the reported USB volume is unity gain.
- Unit tests cover the route-aware volume policy, including unity gain during
  ducking on USB and the existing ducking behavior on Android-managed routes.

## Scope and remaining verification

The code audit finds no software gain/DSP stage in the USB transfer path. The
integer source path does not convert samples to `f32` or `f64`, and no
resampler is present in this path.

This is not yet a complete end-to-end bit-perfect certification. Remaining
work includes:

- Confirm the current CI run completes successfully, including Rust tests,
  Kotlin unit tests, JNI contract validation, and Android native builds.
- Exercise real UAC1/UAC2 DACs at multiple rates and bit depths, including
  asynchronous feedback, and capture/compare USB payloads against source PCM.
- Confirm the DAC's negotiated clock, accepted format, and downstream DSP
  configuration. Flowery cannot guarantee processing performed inside the DAC.

A strict bit-perfect claim should be made only for supported integer source
formats after those software and hardware checks pass.


### Pause/stop concurrency

Pause requests release the lifecycle gate while waiting for submitted audio
transfers to drain, allowing stop or close to proceed. The waiter observes the
worker status as well as the drain acknowledgement: a worker that fails or
stops before acknowledging pause now terminates the wait with an explicit
error instead of waiting until the pause deadline. After drain acknowledgement,
the waiter reacquires the lifecycle gate and verifies that the same worker is
still registered and running before reporting success. This covers the
pause-versus-stop/close ordering at the state-machine level; device-level
concurrency stress testing remains outstanding.

### Transfer allocation ownership and cleanup

`TransferSlot` and `FeedbackSlot` are the single owners of their
`libusb_transfer` allocations. Their destructors now apply one invariant:
an unsubmitted transfer is freed together with its slot-owned buffer, callback
signal, and session reference; a submitted transfer is not freed, and its
callback-visible buffer, signal, and session reference are deliberately
retained until callback completion has been observed. Explicit free calls were
removed from startup-error, normal-shutdown, and timeout cleanup paths so a
transfer cannot be freed twice or leak merely because a worker unwinds before
reaching its normal cleanup block. Timeout teardown still intentionally retains
submitted slots and the session. The pure ownership-policy test covers both
submitted and unsubmitted states; Android/libusb runtime behavior still
requires device-level validation.

### Cancellation callback drain ordering

The runtime now uses one shared `all_transfer_callbacks_observed` gate before treating the audio/feedback transfer set as drained. A successful `libusb_cancel_transfer` request and `LIBUSB_ERROR_NOT_FOUND` are both requests/results, not proof that the callback has been observed. Each slot remains marked submitted until its callback signal is consumed by the worker. The drain gate therefore remains closed while any audio or feedback slot is still submitted, independent of callback completion order. Deterministic unit tests cover out-of-order audio completions, a pending feedback callback after all audio callbacks, and cancellation results that must not be mistaken for completion. This is software-level lifecycle validation, not physical USB hardware validation. The worker now classifies the drain as `Draining`, `Drained`, or `TimedOutRetain`; observing every callback takes precedence even if the deadline has elapsed. A timeout with any callback outstanding enters a distinct terminal status and exits through slot drop guards, which intentionally retain the transfer allocation, callback-visible buffer and signal, and an `Arc` reference to the libusb session. Tests cover the timeout boundary, callback completion at/after the deadline, and drop probes proving those allocations remain alive after the timeout decision. This validates the software ownership policy only; it does not emulate a real libusb host-controller callback that never arrives. The lifecycle guard is sticky: a worker join timeout, worker panic, transfer-drain timeout, or previously unsafe session all classify the Android USB connection as requiring retention. `stop_worker_locked` keeps the libusb registry entry and Java-owned fd in that state; repeated `close_session` calls continue to fail without removing the session, and restart/seek are rejected. Unit tests exercise the sticky classification across initial stop and repeated close conditions. This remains a pure lifecycle-policy test rather than an Android fd or physical-device integration test.
