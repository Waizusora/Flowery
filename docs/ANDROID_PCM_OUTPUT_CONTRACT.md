# Android-managed PCM output contract

This contract applies to Flowery's AUX, built-in speaker, and Bluetooth routes
that use Android AAudio. USB DAC playback remains on its separate USB Audio path.

## Flowery's responsibility boundary

Flowery must submit the source PCM sample rate and integer bit depth unchanged
at its AAudio output boundary. Processing performed by Android AudioFlinger,
Audio HAL, vendor DSP, Bluetooth stack, or a remote Bluetooth codec after
Flowery submits the stream is outside Flowery's bit-perfect guarantee.

## Required integer PCM formats

| Source PCM | AAudio output format | Bytes per sample |
| --- | --- | ---: |
| Signed 16-bit PCM | `AAUDIO_FORMAT_PCM_I16` | 2 |
| Signed packed 24-bit PCM | `AAUDIO_FORMAT_PCM_I24_PACKED` | 3 |
| Signed 32-bit PCM | `AAUDIO_FORMAT_PCM_I32` | 4 |

The requested sample rate is the decoder's source sample rate. Flowery must
verify the opened AAudio stream's actual sample rate, channel count, and format.
If the device route cannot open that exact tuple, playback must fail explicitly;
Flowery must not silently fall back to another bit depth or sample rate.

## Prohibited transformations

- Do not normalize integer PCM through `f32` or `f64`.
- Do not resample integer PCM inside Flowery's Android-managed output path.
- Do not reduce 24-bit or 32-bit source PCM to 16-bit.
- Do not reduce 32-bit integer PCM to 24-bit.
- Do not silently substitute float PCM for integer PCM.

The decoder must retain typed integer samples. The AAudio producer packs those
samples into the selected integer PCM byte layout, and the native ring buffer
stores raw bytes. At unity software gain, the AAudio callback copies the PCM
bytes unchanged. Non-unity software gain uses integer fixed-point arithmetic.

## Route scope

AUX, built-in speaker, and Bluetooth use the same AAudio integer PCM pipeline.
This contract describes the format Flowery submits to AAudio, not the format
that Android or a Bluetooth codec may use downstream.

## Unsupported source formats

If a source does not decode to supported integer PCM (16, 24, or 32 valid bits),
Flowery must report an explicit unsupported-format error rather than convert it
silently. Support for native floating-point source files is a separate feature
and must not be confused with integer PCM preservation.
