# USB PCM validation matrix

This document separates byte-exact software evidence from formats and transport behavior that have not yet been verified.

## Evidence levels

- **CI byte-exact (previous passing test run):** the decoder-to-PCM-encoder test completed successfully in GitHub Actions. This is software evidence only; it is not a physical DAC or USB bus capture.
- **Pending CI:** a test or implementation was added after the last completed test run, or a previous test failed and has since been corrected. Do not treat it as verified until CI passes on the current commit.
- **Not verified:** no end-to-end byte-exact test currently proves the complete path.
- **Hardware unverified:** no physical DAC validation or USB bus capture has been performed.

## Format matrix

| Source format | Decoder representation under test | USB subslot | Status |
|---|---|---:|---|
| WAV PCM 16-bit | Symphonia typed output | 2 bytes | CI byte-exact on earlier test run |
| WAV PCM 24-bit | Symphonia typed output | 3 bytes | CI byte-exact on earlier test run |
| WAV PCM 32-bit | Symphonia typed output | 4 bytes | CI byte-exact on earlier test run |
| WAV IEEE Float 32-bit | F32 | 4 bytes | CI byte-exact on earlier test run |
| FLAC 16-bit | S32, valid bits left-aligned | 2 and 4 bytes | Pending CI; prior assertion incorrectly expected S16 |
| FLAC 24-bit | S32, valid bits left-aligned | 3 bytes | CI byte-exact on earlier test run |
| FLAC 24-bit | S32, valid bits left-aligned | 4 bytes | Pending CI |
| FLAC 32-bit | Not applicable to standard FLAC integer stream depth | — | Not a supported FLAC source depth |
| ALAC 16-bit | S32, valid bits left-aligned | 2 and 4 bytes | Pending CI; prior assertion incorrectly expected S16 |
| ALAC 24-bit | S32, valid bits left-aligned | 3 bytes | CI byte-exact on earlier test run |
| ALAC 24-bit | S32, valid bits left-aligned | 4 bytes | Pending CI |
| ALAC 32-bit | Must be confirmed against actual Symphonia decoder output | 4 bytes | Not verified |
| Other Symphonia codecs / sample formats | Must be measured per codec and bit depth | — | Not verified |

## USB packet payload path

The native worker now routes each decoded PCM chunk through `copy_pcm_into_packet` before submitting the libusb isochronous transfer. The helper validates whole-frame alignment, rejects chunks larger than the remaining packet capacity, and zero-pads a short final packet.

A host-side test exercises encoder output for WAV PCM 16/24/32 and Float32 through the packet payload helper. This validates the software payload assembly stage; it does not validate libusb URB completion, actual wire contents, endpoint clock behavior, or DAC rendering.

## Bit-perfect acceptance criteria

Do not label a format or device bit-perfect until all applicable checks pass:

1. The exact source file's decoded sample codes are known and asserted.
2. Symphonia's concrete output variant and alignment are asserted for that codec and bit depth.
3. The USB encoder output matches the expected PCM bytes exactly.
4. The isochronous packet payload preserves those bytes and frame boundaries.
5. Runtime transfer completion reports full packet lengths without underruns or timeouts.
6. A physical DAC / USB bus capture confirms the negotiated format, sample rate, channel layout, and transmitted payload.

Until criteria 5-6 are met, report the result as **software byte-exact**, not end-to-end hardware bit-perfect.
