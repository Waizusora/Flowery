# USB DAC bit-perfect path audit

This audit covers Flowery's native USB Audio playback path on the `trial` branch.
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
- Clock Source frequency control (`bmControls` bits 1:0) follows the UAC2 bit-pair
  encoding: `0b00` absent, `0b01` present and read-only, `0b11` present and
  host-programmable. `0b10` is not allowed by the specification; it is treated
  as writable, matching the Linux USB audio driver, which tests only the high
  bit of the pair.
  - Host-programmable (`0b11`, or tolerated `0b10`): `SET_CUR` is issued whenever
    `GET_CUR` differs from the required rate and the result is verified with
    `GET_CUR`. A failed write or verification rejects the clock path and
    triggers rollback. This prevents a DAC from retaining a stale rate (for
    example 384 kHz while 96 kHz PCM is streamed), which would consume samples
    at the wrong speed and shift the pitch by the ratio of the two rates.
  - Read-only or absent (`0b01` / `0b00`): `GET_RANGE` is the capability gate. If
    the requested rate is advertised, negotiation succeeds without requiring
    `GET_CUR` to already match and without issuing `SET_CUR`. Rate selection is
    expected to follow the chosen AudioStreaming alternate setting, which is the
    common behavior for fixed-clock class-compliant DACs.
  - `UAC2_CLOCK_READY` logs `sourceCurrentHz` (before negotiation) and
    `sourceActiveHz` (after negotiation). A `sourceActiveHz` that differs from
    `sourceRequiredHz` indicates the clock was not aligned.
  - The same bit-pair rules apply to Clock Selector controls.
- These control-transfer checks are covered by Kotlin fake-control tests;
  actual DAC behavior and host-controller timing still require hardware tests.

## Hardware volume (UAC Feature Unit)

- Volume is read and written only through a descriptor-advertised Feature Unit
  volume control. For UAC2, the per-channel `bmaControls` bit pair uses the same
  encoding as above (`0b01` read-only, `0b11` programmable). No VID/PID
  heuristics are used.
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
- On the first refusal, a read-only probe logs `GET_RANGE` with alternative
  request lengths and a `GET_CUR` request (`USB_VOLUME_CONTROL_TRANSFER
  op=PROBE_*`). No `SET_CUR` is issued by the probe.
- If `GET_CUR` is also unavailable, the volume control is reported as
  unavailable and no write is attempted.
- A second read-only probe runs after clock negotiation and before the
  isochronous stream starts (`PROBE_PRESTREAM_*`). It issues `GET_CUR` for the
  volume of channels 1, 2 and 0, `GET_RANGE` for channel 1, and `GET_CUR` for
  master mute. Comparing it with the post-start requests distinguishes
  interference from active streaming from a device that does not answer Feature
  Unit requests at all. No `SET_CUR` is sent by either probe.
- A read-only sweep (`UAC2_SWEEP`, implemented in `UsbFeatureUnitProbe`) issues
  `GET_CUR` for entity IDs 1..10 (volume channel 1 and Clock Source sampling
  frequency) and for the Feature Unit with alternative interface bytes. It runs
  once per process with the streaming interface idle (`phase=IDLE`) and again
  after clock negotiation (`phase=PRESTREAM`). Entities of the wrong type are
  expected to STALL; the pattern of successes shows which entity IDs and
  addressing the device answers. The sweep never writes.
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
