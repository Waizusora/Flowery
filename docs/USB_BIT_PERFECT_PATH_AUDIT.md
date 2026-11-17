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
- Clock Source frequency control encodings other than host-programmable
  (`bmControls` frequency bits != `0b10`) are treated as read-only. For those
  sources, `GET_RANGE` is the capability gate: if the requested rate is
  advertised, negotiation succeeds without requiring `GET_CUR` to already match
  and without issuing `SET_CUR`. Rate selection is expected to follow the chosen
  AudioStreaming alternate setting, which is the common behavior for fixed-clock
  class-compliant DACs.
- These control-transfer checks are covered by Kotlin fake-control tests;
  actual DAC behavior and host-controller timing still require hardware tests.

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
