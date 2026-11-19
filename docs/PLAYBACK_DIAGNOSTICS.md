# Playback diagnostics

Flowery writes structured playback lifecycle events to Android logcat under the tag `FloweryDiagnostics`.

The events include service creation/destruction/task removal, queue size and selected index, track start and completion transitions, intentional playback shutdown, playback-start exceptions, output-route reopen attempts and failures, and (on Android 11/API 30+) Android's historical process-exit reason plus a memory snapshot when Flowery is opened again.

Output-route changes are reported as:

- `OUTPUT_ROUTE_REOPEN`: the service detected a changed monitored output-device snapshot and is reopening the active track. The record includes the prior playing/paused state and captured playback position.
- `OUTPUT_ROUTE_REOPEN_FAILURE`: the reopen operation failed. The exception is included in the log record.

Historical process-exit information is only available after the app is launched again and may not be available on older Android versions. Native crashes and operating-system process kills may require additional system logs or tombstone information.

## Capture a reproduction

1. Clear the old log buffer:
   ```sh
   adb logcat -c
   ```
2. Start Flowery and reproduce the unexpected closure or output-route switching issue.
3. Launch Flowery again so it can log Android's historical process-exit information when investigating an unexpected closure.
4. Capture relevant records:
   ```sh
   adb logcat -d -v threadtime -s FloweryDiagnostics AndroidRuntime ActivityManager
   ```
5. Share the output and approximate reproduction time.

The diagnostic intentionally avoids recording full content URIs or filesystem paths.

## USB streaming timing diagnostics

The direct USB isochronous path logs a `TRANSFER_SUMMARY` record under the tag `FloweryUsbNative` every five seconds. Fields relevant to intermittent gaps, stutter, or delayed audio:

- `packetErrors`, `expectedBytes`, `actualBytes`: bus-level transfer integrity. Non-zero `packetErrors` or a byte mismatch indicates the device or host controller rejected or truncated packets.
- `ringDryEvents`: number of times no isochronous transfer was submitted while audio should have been flowing, which is an audible gap. A non-zero value means the worker thread was not scheduled in time to keep the transfer ring filled. Each of the first twenty events (and every hundredth afterwards) is also logged as `USB_RING_DRY`, with the time since the last submission.
- `submitGapMaxUs`, `eventLoopMaxUs`, `callbackToResubmitMaxUs`: worst-case scheduling latencies since the stream started. They should stay well below the buffered duration reported by `USB_TRANSFER_RING`.
- `pcmQueuedMinFrames`, `pcmQueueWaits`, `zeroPaddedFrames`: health of the decoder-to-submitter PCM queue. A low `pcmQueuedMinFrames` or non-zero `zeroPaddedFrames` points at the decoder rather than at scheduling.
- `activeSlotsMin`, `pendingMin`: minimum number of submitted transfers observed.

If a completed transfer fails validation, the worker logs `TRANSFER_INVALID` followed by `USB_TRANSFER_INVALID_DETAIL slot=<n> ringSize=<n> completedTransfers=<n> audioFrames=<n> packets(length/actual/status)=<list>`. The list gives, for each of the eight packets, the submitted length, the length the host controller reports as transferred, and the packet status.

At stream start the worker logs:

- `USB_TRANSFER_RING slots=<n> packetsPerTransfer=<n> bufferedUs=<n>`: the number of transfers kept in flight and the audio duration they cover. The ring is currently four transfers (about 4 ms). A 32 ms ring was evaluated on one USB 2.0 DAC and failed deterministically at the 34th completed transfer (see `USB_TRANSFER_INVALID_DETAIL`), so deeper rings are disabled until that behavior is understood.
- `USB_THREAD_PRIORITY thread=<name> requestedNice=<n> result=<n> errno=<n>`: outcome of the best-effort request to raise the scheduling priority of the USB submit and PCM decode threads. `result=0` means the request was accepted; any other value means Android refused it and the thread keeps its default priority.

Capture command:

```sh
adb logcat -c
# reproduce the stutter and note the wall-clock time
adb logcat -d -v threadtime -s FloweryUsbNative FloweryDiagnostics
```
