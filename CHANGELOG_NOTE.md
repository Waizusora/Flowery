# USB Feature Unit volume control hardening

## File changed

- `app/src/main/java/com/waizusora/flowery/UsbDacNative.kt`

## Problem

KT-384/32 HIFI (VID/PID 31B2:0111) advertises a UAC2 Feature Unit master volume
control, but the default GET_RANGE transfer (requestedLength=98) returns
transferred=-1. The startup volume safety check therefore fails and blocks
direct USB playback.

## Changes

1. Claim the Audio Control interface before class-specific Feature Unit requests.
2. Multi-strategy volume range discovery:
   - UAC2 GET_RANGE with the full 16-subrange buffer (existing path)
   - UAC2 GET_RANGE with a single-subrange buffer (devices that stall large reads)
   - UAC1-style GET_MIN + GET_MAX fallback
3. GET_CUR probe recorded in diagnostics when all range strategies fail.
4. Release the control interface claim on session close.
5. Extended hardware volume diagnostics (`control_claimed`, `range_attempts`).

## Safety policy unchanged

Playback still requires a confirmed near-minimum SET_CUR + GET_CUR when a
Feature Unit is advertised. This change only improves the chance of obtaining a
usable volume range on non-compliant firmware.
