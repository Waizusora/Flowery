# Adaptive DAC Compatibility

## Scope of the first implementation stage

The native output compatibility planner is descriptor-driven and read-only. It
selects a USB Audio Class playback alternate and output PCM tuple from the
formats, channel count, sample rates, bit resolution, and subslot size exposed
by the DAC descriptors. It does not claim an interface, negotiate a clock, or
modify decoded samples.

The planner is exposed to Android through
`NativeAudio.nativePlanUsbOutputCompatibility` and
`UsbAudioDeviceScanner.planNativeCompatibility`. Planning emits a
`DAC_COMPATIBILITY_PLAN` record through Android Logcat under the
`FloweryDiagnostics` tag.

## Selection policy

1. Require an exact channel-count match; channel mapping is not silently
   invented by the planner.
2. Avoid bit-depth reduction whenever a higher-precision compatible alternate
   exists, even when that requires sample-rate conversion.
3. Among candidates with equivalent precision loss, preserve the source sample
   rate whenever the DAC advertises it.
4. Prefer the decoder's native sample representation and exact valid bit depth.
5. If no exact sample rate exists, select the closest advertised rate and mark
   high-quality resampling as required.
6. Reducing integer precision requires TPDF dithering; expansion must preserve
   sample values and must not claim additional source resolution.
7. Preserve the decoder's typed PCM queue. The planner describes output work;
   it does not convert the decoder's samples.

For example, a 24-bit/96 kHz stereo source planned against a DAC that advertises
only 24-bit/48 kHz produces an output target of 24-bit/48 kHz and sets
`requires_resampling=true`. Against a 16-bit/48 kHz-only DAC, it also sets
`requires_bit_depth_conversion=true`.

## Bit-perfect assessment

The planner reports `candidate` only when sample rate, channel count, sample
representation, and valid bit depth match the decoded source tuple. This is
not a certification: the USB clock negotiation, packet transport, device
behavior, and hardware output still need validation.

Any resampling, bit-depth conversion, sample-format conversion, channel
mapping, software gain, EQ, mixing, or other sample processing makes the
stream not bit-perfect relative to the decoded PCM. Lossy source formats must
also not be described as bit-perfect copies of an original uncompressed master;
the relevant comparison is the decoded PCM at Flowery's output boundary.

Bit-perfect is a USB Direct-only feature and diagnostic. AAudio does not
participate in bit-perfect evaluation: it uses Android-managed output, and
Info Audio reports its actual stream characteristics without displaying a
bit-perfect status. Android's AudioFlinger, Audio HAL, route DSP, and downstream
device processing remain outside Flowery's control.

## Validation and follow-up

Adaptive tuple selection is now connected to the USB Direct startup path. The
compatibility planner selects the DAC tuple first; the transport planner then
resolves the exact interface, alternate, endpoint, packet schedule, feedback,
and UAC2 clock topology for that tuple. The selected output rate is passed to
UAC1/UAC2 clock negotiation, USB packet scheduling, playback-frame accounting,
and seek restart.

The live USB PCM producer now owns a persistent output pipeline. Exact native
tuples bypass conversion and preserve the typed integer path. Mismatched tuples
are converted through normalized high-precision samples; rate changes use the
stateful 64-tap windowed-sinc resampler, bit-depth reduction uses TPDF dither,
and the resampler tail is flushed at EOF. A new pipeline is created after seek,
resetting filter history and dither state.

Remaining work before considering adaptive playback production-ready:

- Add frequency-response, alias-rejection, latency, CPU, seek-reset, and
  chunk-boundary integration tests across the live producer boundary.
- Validate 24/96-to-24/48, 24/96-to-16/48, 16/44.1-to-24/48, and lossy
  Float32-to-integer paths on physical DACs, including UAC1/UAC2 clock and
  feedback behavior.
- Expose the actual negotiated USB clock and feedback state separately from
  the selected output tuple, including readback confidence for UAC1/UAC2.
- Define and test the independent AAudio compatibility policy using the
  actual opened AAudio stream format and sample rate; do not infer the
  negotiated format from the requested format.
- Promote bit-perfect from candidate to verified only after all Flowery-side
  processing and transport invariants are checked. Hardware/analog output
  cannot be certified by software telemetry alone.
No CI, log, or text-output files are created by this feature.
