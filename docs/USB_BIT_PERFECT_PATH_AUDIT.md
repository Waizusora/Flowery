# USB DAC bit-perfect path audit

This audit covers Flowery's native USB Audio playback path on the `dev` branch.
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
  not an integer-PCM conversion. `F64` source audio is not supported by the
  exposed USB data-format contract and must not be treated as standard USB
  Audio Type I PCM.

## Scope and remaining verification

The code audit finds no software gain/DSP stage in the USB transfer path. The
integer source path does not convert samples to `f32` or `f64`, and no
resampler is present in this path.

This is not yet a complete end-to-end bit-perfect certification. Remaining
work includes:

- Run the Rust test suite and Android target builds in CI.
- Verify Android/Kotlin routing never sends USB playback through the AAudio
  software-volume callback or another processing path.
- Exercise real UAC1/UAC2 DACs at multiple rates and bit depths, including
  asynchronous feedback, and capture/compare USB payloads against source PCM.
- Confirm the DAC's negotiated clock, accepted format, and downstream DSP
  configuration. Flowery cannot guarantee processing performed inside the DAC.

A strict bit-perfect claim should be made only for supported integer source
formats after those software and hardware checks pass.
