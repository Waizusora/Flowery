# Gapless Playback — implementation contract

## Non-negotiable PCM behavior

Gapless playback must not modify decoded PCM samples. No resampling, gain,
normalization, dithering, bit-depth conversion, channel remixing, or inserted
silence may be introduced by the transition layer. USB Direct bit-perfect remains
a separate path and must retain the existing sample-preservation contract.

## Transition classes

The native transition planner classifies two adjacent track formats:

- **Reuse output**: route, sample rate, channel count, valid bit depth, and decoded
  sample representation are identical. The existing output session can remain
  open while the next decoder is attached to the producer queue.
- **Reconfigure output**: AAudio or USB DSP remains selected but the PCM contract
  changes. The old stream must be drained before reconfiguration. This is not
  sample-continuous and must not be reported as seamless gapless.
- **Renegotiate USB clock**: USB Direct remains selected but its PCM contract
  changes. The USB interface/alternate setting and UAC clock path must be
  revalidated for the next track. Do not reuse the old negotiated clock blindly.
- **Reopen route**: the output route itself changes and the route owner must
  release/reopen the output session.

## Pipeline requirements for the next implementation phase

1. Keep one output session alive across adjacent tracks with identical PCM
   signatures; swap decoder/producer ownership at the queue boundary.
2. Decode and prebuffer the next track before the current track reaches EOF.
3. Publish the next track's metadata independently from the output-session
   lifetime; do not stop/recreate the output merely to update metadata.
4. Treat CUE segment boundaries as track boundaries, but preserve the exact
   segment start/end frame accounting.
5. If the next track changes sample rate, channel layout, bit depth, or sample
   representation, drain the current queue and perform the transition classified
   above. A DAC clock/interface renegotiation may necessarily create a short
   interruption; do not call it gapless.
6. EOF is not equivalent to an empty queue. The output must continue consuming
   already queued frames until the producer explicitly marks the boundary.
7. Cancellation, seek, pause, route changes, repeat-one, shuffle, and service
   recovery must invalidate stale prefetch work by generation/epoch.

## Current scope

This commit adds and tests the pure native transition contract only. It does not
yet enable gapless playback in the player. Runtime integration, decoder
prefetch, persistent output ownership, and hardware validation remain subsequent
steps.
