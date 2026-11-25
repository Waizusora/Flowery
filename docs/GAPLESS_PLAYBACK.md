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


## Queue-boundary state machine

The native `GaplessBoundary` contract distinguishes decoder EOF from queue EOF:

- `TrackEof` moves the current generation to `AwaitingNext`; it must not finish playback or tear down the output.
- `NextTrackReady` returns either `AttachNextTrack` for an identical PCM signature or `ReconfigureOutput` for a changed route/format contract.
- `QueueEof` is the only event that finishes the queue.
- Events from an older playback generation are ignored. Cancellation makes later events for that generation inert.

The state machine is deliberately pure and unit-tested. It defines coordination semantics but is not yet wired into the Android player or native output workers.


## Prepared decoder ownership foundation

The Android layer now has a `PreparedPlaybackSlot` ownership primitive for a
single prefetched decoder/session resource bundle. It is generation- and URI-keyed,
transfers ownership exactly once, closes replaced or stale resources, and can be
invalidated on seek/skip/route changes. Unit tests cover stale publication,
generation replacement, one-shot transfer, mismatched keys, and cleanup.

This is an ownership primitive only: it is not yet called by `PlaybackService`
or `FloweryPlayer`, and it does not yet create decoders or keep an output alive.
Runtime prefetch and producer handoff remain unimplemented.

## Current scope

The native transition planner and generation-safe queue-boundary contract are implemented and unit-tested. Gapless playback is **not yet enabled** in the player. Runtime integration, decoder prefetch, persistent output ownership, and hardware validation remain subsequent steps.

### Same-signature AAudio producer handoff primitive

A native handoff operation now advances the producer epoch while preserving the
existing SPSC ring, queued PCM tail, played-frame counter, and AAudio callback.
It validates the PCM tuple and actual output device/sharing identity. Old
producer writes become stale; the next producer can use the same ring without
replacing its consumer endpoint. The queue output owner now calls this attach
operation instead of resetting the ring.

This primitive is not sufficient by itself for gapless playback. The old
producer must be joined before attachment, and natural track EOF must not mark
the shared output as queue EOF. The prefetched next producer must start while
enough old-track PCM remains buffered. Runtime wiring and continuity tests are
still pending.


### Prepared resource bundle

A prepared decoder/session is represented by `PreparedPlaybackResources`. It
owns the native session handle, decoder handle, and SAF source descriptor as one
transferable unit. Teardown is idempotent and follows dependency order: native
session, decoder, then source descriptor. Each teardown step is attempted even
if an earlier native release fails. Unit tests cover ordering, idempotence, and
best-effort cleanup.

The wrapper is now used by the service's prefetch slot and can be transferred
to `FloweryPlayer`. Output lifetime and producer handoff remain separate
implementation work.


The preparation factory opens the SAF descriptor, creates the native decoder,
primes decoder metadata, creates a native playback session in Prepared phase,
and returns all handles in the owned bundle. Failure at any preparation step
releases every resource already acquired. The factory does not start AAudio or
USB output workers. The generation-keyed slot now publishes and transfers this
bundle to `FloweryPlayer`; if no matching prepared bundle is ready, the player
falls back to its normal open path.

## Playback prefetch coordinator foundation

Android now has a generation-scoped `PlaybackPrefetchCoordinator<T>` around `PreparedPlaybackSlot<T>`. It schedules one preparation task away from the playback/output thread, accepts results only for the current playback generation and latest prefetch request, and disposes resources produced by superseded or invalidated work. A successful `take(generation, key)` transfers ownership exactly once; closing the coordinator disposes any resource that was never claimed.

Unit tests cover successful ownership transfer, generation invalidation, rejection of stale-generation requests, cleanup on close, and preparation failure. `PlaybackService` now selects the matching prepared URI/CUE resource from the previous generation before advancing the coordinator generation, then passes ownership to `FloweryPlayer`. This removes the duplicate decoder/session preparation when prefetch has completed; it does not keep the output alive or hand a producer into a running output worker. Runtime gapless playback therefore remains disabled.

Prefetch preparation failures are reported through an optional callback only while the request is still current. Failures from superseded generations are suppressed, and a failed preparation never publishes a partial resource. Cancellation and thread interruption are treated as normal invalidation rather than user-visible decoder errors.

## Native queue lookahead

The native playback queue now exposes a non-advancing next-index peek. In shuffle mode, the selected candidate is cached so repeated prefetch checks and the eventual NEXT or natural COMPLETION transition consume the same candidate rather than drawing a different random item. Queue selection, repeat-mode changes, and shuffle changes invalidate the cached candidate. Repeat-One keeps its existing natural-completion behavior (replay current item); peeking does not change that behavior. A queue with no next item returns -1 and is not prefetched.

This is queue-policy plumbing only. It does not yet schedule `PreparedPlaybackResources` from the service or connect them to an active output producer.

## Service-side decoder prefetch wiring

`PlaybackService` now starts preparation for the native queue's peeked next item when a playback generation is started. It captures the target URI and CUE segment identity, opens and primes a decoder/session on the dedicated prefetch executor, and leaves the prepared resource owned by the generation-scoped slot. Stop, service destruction, output-route changes, and generation replacement cancel or dispose pending/unclaimed resources. Repeat/shuffle changes refresh the selected candidate; clearing a candidate does not invalidate the active generation.

This wiring now takes a prepared resource only when its generation and URI/CUE key match the selected queue item. The resource is passed to `FloweryPlayer`, which starts the already-prepared native session and closes the owned session, decoder, and source descriptor together after playback. If the slot is empty, stale, or keyed to another item, the player uses its normal open path. The output is still opened and closed per track, so this reduces duplicate preparation but does not provide runtime gapless playback or guarantee a shorter audible gap.

## Prefetched resource consumption in FloweryPlayer

`PlaybackService` now consumes the next-track prefetch by matching the exact URI
and CUE segment key against the previous playback generation. It takes the
resource before starting the new generation, because generation replacement
disposes anything that remains unclaimed in the slot. The resource is then handed
to `FloweryPlayer`; stale requests, audio-focus rejection, call interruption,
and asynchronous metadata cancellation close an unclaimed bundle.

`FloweryPlayer` accepts an optional `PreparedPlaybackResources` bundle. When
present, it reuses the already-created decoder and Prepared native session,
skips reopening the SAF descriptor and recreating decoder/session state, and
starts the prepared session. Its `finally` path delegates teardown to the bundle,
which releases the native session, decoder, and descriptor exactly once. If no
matching prefetch is available, the existing open-and-prepare path remains the
fallback.

This is the first runtime consumption of prefetched resources, not runtime
gapless playback. AAudio still opens a new output stream for each track, and USB
Direct still owns its per-track libusb session. Persistent output ownership,
producer handoff at an early queue boundary, and continuity/underrun validation
remain outstanding.

### Early AAudio track-boundary notification

The native output scheduler now publishes status `3` when the decoder reaches track EOF while buffered PCM remains. This status is non-terminal: the scheduler continues draining the current ring and eventually publishes status `1` when the track is fully drained. Single-track playback ignores status `3` and waits for normal completion. When the queue has a next item, `FloweryPlayer` may exit its current track worker on status `3`, retain the output lease, and let the service begin preparing/attaching the next track while the old PCM tail is still being consumed.

This reduces the handoff delay by the remaining ring-drain interval. It does not guarantee gapless output: service scheduling, metadata work, decoder attachment, and producer refill must all finish before the ring empties. Runtime underrun counters and boundary continuity tests are still required. USB Direct is unchanged.

## FloweryPlayer persistent AAudio output integration

`FloweryPlayer` now owns an `AAudioQueueOutputOwner` for the active queue. At
normal track completion, it retains the open AAudio device stream only when the
service reports a next queue item. The native session is then closed while the
native persistence lease keeps the AAudio stream and SPSC output ring alive.

When the next track starts, the player compares its requested sample rate,
channel count, sample format, device ID, and sharing mode with the retained
output. An exact match advances the native producer epoch and reuses the
existing stream/ring; the next segmented decoder pump is attached to that
producer before the output scheduler is restarted. A mismatch, failed attach,
route change, queue end, or explicit stop closes the retained output and uses
the normal output-open path.

This is persistent output reuse, not yet verified gapless playback. Track EOF
currently completes after the ring drains, and the service starts the next
producer only after that completion callback. The callback may therefore emit
silence between tracks even though the AAudio device stream remains open. A
future queue-boundary signal must start/attach the next producer before the
buffer drains, and continuity/underrun instrumentation must verify the result.
USB Direct remains on its independent libusb lifecycle and is not affected.

## Persistent output lifetime contract

The native gapless module now models output lifetime separately from decoder lifetime. A track EOF detaches the current producer but keeps the output open. A same-signature next track attaches a new producer epoch without reopening output. A changed PCM signature requires an explicit reconfiguration completion before the next producer is considered attached. Only queue EOF or cancellation closes the output; stale generations cannot attach or close it.

This is a tested lifecycle contract, not yet wired to the AAudio engine or libusb transfer workers. Current AAudio still owns one global output ring/producer and its scheduler is tied to one playback session. USB Direct still starts a libusb stream worker per decoder and closes it at track completion. Runtime producer handoff remains the next implementation task.

## AAudio scheduler teardown separation

The AAudio output scheduler no longer calls `Engine::stop()` when its decoder session reaches the completed phase. Scheduler completion now reports the track/session as finished without directly destroying the device stream or ring allocation. The AAudio callback may still return its normal stop signal after the current track's EOF buffer drains; this change preserves the stream object and ring allocation, not uninterrupted playback by itself.

`FloweryPlayer` now retains the output lease for queued playback and reuses the stream/ring for a matching PCM/device signature. This preserves the callback and queued tail across session teardown. It is still not gapless by itself: the next producer must be attached before the tail drains.

## AAudio persistent-output lease and ring handoff API

The native AAudio layer now exposes two queue-owner APIs through `NativeAudio`:

- `nativeSetOutputPersistence(true)` establishes an explicit lease. Closing a completed decoder session then stops/joins its session scheduler and decoder worker but retains the opened AAudio stream and output allocation. Cancellation/failure and `nativeStopOutput()` still revoke the lease and close output.
- `nativePrepareStreamOutputForNextTrack()` requires that lease and a stopped/open/paused stream. It replaces the SPSC ring endpoints, resets per-track output position and EOF/error state, and leaves the same AAudio stream object available for the next producer. It rejects a running callback rather than mutating a live SPSC consumer.

The scheduler can now request start from AAudio's STOPPED state after the next producer has filled the ring to its low-watermark. This is required because the callback returns its normal stop signal after draining a track EOF.

`FloweryPlayer` now calls the producer-attach API for a retained output only after exact PCM/device signature matching. The ring and already queued PCM are preserved. A mismatch releases the retained output and follows the ordinary output-open path; this does not attempt an in-place format change.

### PCM signature guard for AAudio handoff

The next-track ring reset now takes the expected sample rate, channel count, and AAudio output format. Native code validates all three against the currently opened stream before replacing either SPSC endpoint. A mismatch returns an explicit invalid-format result and leaves the existing ring untouched; the caller must use the output reconfiguration path instead. Unit tests cover sample-rate, channel-count, and sample-format mismatches.

This prevents a future queue owner from accidentally treating a different PCM signature as a same-format continuation. `FloweryPlayer` invokes the handoff API after a compatible next decoder has been prepared. This is a same-signature producer handoff foundation; continuity is not yet verified.

### AAudio producer epoch isolation

Each opened AAudio output ring now owns a monotonically advancing producer epoch. A segmented decode worker captures the epoch when it starts. Queue-budget reads, PCM writes, and all track-EOF signals are accepted only while that epoch still owns the ring.

Replacing the ring for a same-signature next track advances the epoch before a new producer starts. Stale workers detect the mismatch and exit; a late PCM chunk or EOF notification from an older track is rejected and cannot contaminate or prematurely finish the next track's ring. Closing output also invalidates the previous epoch.

This is a native race-safety prerequisite for queue-level handoff. It does not itself retain output, attach the next decoder, or enable gapless playback in the Kotlin player.

### AAudio output identity guard

The persistent same-format handoff API now also requires the expected AAudio device ID and sharing mode. Native code reads both values from the still-open stream and rejects a handoff if either differs. This prevents a queue owner from reusing an output opened for another Android device or silently changing between EXCLUSIVE and SHARED operation.

The identity guard is in addition to the existing sample-rate, channel-count, and output-format checks. Tests cover matching identity, device mismatch, sharing-mode mismatch, invalid sharing mode, and invalid expected device ID. `FloweryPlayer` now uses this identity guard before reusing a retained output.

### Kotlin queue-level AAudio lease coordinator

`AAudioQueueOutputOwner` now models the queue's ownership of an already-open AAudio stream. It adopts the opened output signature, enables the native persistence lease at a track boundary, advances the producer epoch without replacing the ring only for an identical sample-rate/channel/format/device-ID/sharing-mode signature, and confirms producer attachment as a separate transition. A native attach failure leaves the owner retained but not prepared; a PCM or device mismatch does not touch the ring and must be handled by an explicit output reconfiguration path. Queue completion or cancellation revokes persistence and stops output exactly once.

Unit tests use a fake native bridge to cover same-signature handoff, PCM/device/sharing mismatches, native attach failure, duplicate adoption, and idempotent close. `FloweryPlayer` now uses this coordinator to retain and reuse a compatible AAudio output across sessions. Runtime gapless remains unverified until the early boundary signal results in a new producer being attached and refilling the ring before its tail drains. The player now records AAudio pipeline snapshots at both the early boundary and drained boundary, including callback underruns, AAudio XRun count, queued frames, ring capacity, stream buffer size/capacity, actual stream rate, device, sharing mode, format, and channel count. Compare the two snapshots for the same physical transition: an underrun or XRun increase, or queued frames reaching zero before the next producer is attached, indicates a continuity failure. These counters are cumulative for the lifetime of the current output and must be compared as deltas; a zero delta alone is not proof of sample-perfect continuity.

### Queue EOF cleanup

`PlaybackService.finishQueuePlayback()` now invalidates the generation-scoped prefetch coordinator before stopping the player. A prepared decoder/session that was waiting for a next track is therefore closed when the queue actually ends, rather than remaining held by a queue that has no successor. Explicit stop and service destruction continue to invalidate/close the same coordinator.


### Track EOF versus queue EOF

The AAudio streaming producer signals **track EOF** independently from
**queue/output EOF**. At track EOF, the producer becomes inactive but the
callback remains alive and drains queued PCM. The scheduler now publishes
status `3` as soon as it observes track EOF while buffered PCM remains; this is
non-terminal and remains visible until the scheduler publishes status `4`
after the tail drains. Status `1` remains ordinary terminal completion when no
early boundary was observed. Queue EOF remains terminal and is still the
condition that lets the callback stop after draining.

When the queue has a successor, `FloweryPlayer` exits its current worker on
status `3`, retains the output lease, and notifies the service while the PCM
tail is still being consumed. Without a successor it waits for terminal
completion. This moves queue advancement earlier, but service dispatch,
metadata work, decoder setup, and producer refill can still exceed the
remaining buffered duration. Silence-free continuity is not yet verified.

### AAudio producer handoff telemetry

The player now emits three additional snapshots for same-signature queue reuse:

- `AAUDIO_NEXT_PRODUCER_ATTACH_COMPLETE`: after the native producer epoch is attached, before the next decode pump starts.
- `AAUDIO_NEXT_PRODUCER_PUMP_STARTED`: after the segmented decode pump starts and the queue owner confirms the producer handoff.
- `AAUDIO_NEXT_PRODUCER_SCHEDULER_STARTED`: after the output scheduler is restarted for the next track.

Each record includes the same output diagnostics as the boundary snapshots, including queued frames, callback underruns, XRun count, output format, stream rate, and device identity. For physical validation, correlate these records by playback generation with `AAUDIO_EARLY_TRACK_BOUNDARY` and `AAUDIO_TRACK_BOUNDARY_DRAINED`. The attach and pump records are lifecycle timestamps, not proof that a minimum PCM prebuffer has already accumulated. A valid continuity result still requires queued PCM to remain nonzero across the handoff, no underrun/XRun increase, and listening or captured-loopback verification on a physical Android device.

The AAudio owner tests also cover a thrown exception from the native attach bridge, persistence-enable failure, persistence-revoke failure, and cleanup after producer startup failure. A failed attach must not advance the owner to PREPARED; cleanup must stop the output and clear the owner state even if lease revocation reports an error.

### Cross-boundary PCM callback tests

Native tests now enqueue the next track's PCM before the output callback consumes the previous track's tail. A single callback request spans the boundary and must receive the old track bytes followed immediately by the next track bytes, with exact byte ordering. The test matrix covers short and longer prebuffer combinations (1, 2, and 8 frames per side), in addition to the two-frame handoff case.

These tests verify ring preservation and callback ordering when the next PCM is already available. They do not simulate Android decoder startup latency, AAudio scheduling jitter, or a real device callback racing with the next producer. They therefore do not prove that Flowery can always deliver the next PCM before the old tail drains. Device-level continuity remains a required validation gate.

### Remaining-tail time budget

AAudio boundary snapshots now include `queuedDurationMs`, calculated from queued output frames and the actual AAudio stream rate. This is the remaining PCM runway available while service dispatch, decoder startup, and next-producer prebuffering complete. Compare the value at `AAUDIO_EARLY_TRACK_BOUNDARY` with the elapsed time to `AAUDIO_NEXT_PRODUCER_SCHEDULER_STARTED` for the same generation. If the measured handoff latency approaches or exceeds the available tail duration, that transition is at risk of underrun. The duration is telemetry, not a continuity guarantee; device callback scheduling and concurrent consumption still affect the result. Invalid rate or frame values are reported as unknown.

### Next-track prebuffer barrier

When reusing a retained AAudio output, Flowery now starts the next segmented decode worker and waits up to 350 ms for the ring to reach the configured low-watermark (currently 100 ms of PCM at common rates) before restarting the output scheduler. A short track is considered ready when its decoder has reached EOF and its remaining PCM is already queued, even if it cannot reach the full watermark. An empty completed track reports zero queued frames. A timeout is logged as `AAUDIO_NEXT_PRODUCER_PREBUFFER_TIMEOUT`; playback continues through the existing output path, but that handoff is not counted as prebuffer-ready. A producer failure aborts the handoff.

This barrier runs on the playback dispatch worker, not the Android UI thread. The old AAudio callback continues draining the previous track while the next producer fills the shared ring. The barrier reduces the race window but cannot guarantee continuity if decoding takes longer than the previous track's remaining buffered duration. Device-level validation must still correlate queued-frame snapshots and underrun/XRun deltas across the boundary.
