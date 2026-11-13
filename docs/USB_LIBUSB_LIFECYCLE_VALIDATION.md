# USB libusb transfer lifecycle validation

This document records host-testable lifecycle policy and the remaining Android/hardware validation.

## Implemented behavior

- Each asynchronous audio/feedback transfer owns its libusb transfer object, backing buffer, and callback signal for the entire submitted lifetime.
- The callback only publishes completion through an atomic signal. The worker validates results and controls resubmission.
- Audio completion validation checks the overall transfer status and every isochronous packet status and byte count. A short OUT packet is a transfer underrun/error and is not counted as played audio.
- Decoder EOF is handled separately: the final scheduled packet is zero-padded to its declared payload length. Only decoded PCM frames are added to the played-frame counter.
- Pause stops refilling the audio ring. The pause API now waits for an explicit worker acknowledgement that all already-submitted audio transfers have completed, with a 750 ms bound; only then does it report pause success. Resume clears the acknowledgement and permits ring refill. This is a bounded-drain pause, not an instantaneous USB bus halt.
- Stop, seek replacement, and session close request cancellation and keep pumping libusb events until submitted transfers have delivered callbacks.
- If cancellation callbacks cannot be drained within two seconds, outstanding transfer slots and the libusb session/context are intentionally retained. This fail-safe leak prevents callbacks from dereferencing freed transfer buffers or user data.

## Host CI tests

The `native/src/usb_isochronous.rs` tests cover:
- successful transfer completion across multiple packets;
- failed transfer status;
- failed per-packet status;
- short packet detection;
- pause blocks audio-ring refill and resume permits it;
- pause acknowledgement remains false while any audio transfer is outstanding and becomes true only after the ring drains;
- cancellation drain timeout boundary;
- final short decoder chunk is silence-padded without changing frame alignment.

## Not yet established

- Host tests do not execute Android-gated libusb event handling or callback scheduling.
- No physical DAC / USB bus capture has yet confirmed completion timing, pause latency, disconnect behavior, or cancellation callback delivery on Android devices.
- The 750 ms pause timeout returns an error rather than claiming that pause completed while audio packets remain queued.
- The two-second timeout path is a safety fallback, not a successful teardown: it deliberately retains native resources if libusb cannot prove that callbacks are drained.
- Exact underrun behavior under real host-controller load and all DAC feedback implementations remains hardware-unverified.

Acceptance requires Android device tests for repeated play/pause/resume/seek/stop, unplug during active transfers, forced transfer errors, and confirmation that every submitted transfer completes or is safely retained before any buffer/user-data/session memory is released.