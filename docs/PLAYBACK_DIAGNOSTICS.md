# Playback diagnostics

Flowery writes structured playback lifecycle events to Android logcat under the tag `FloweryDiagnostics`.

The events include service creation/destruction/task removal, queue size and selected index, track start and completion transitions, intentional playback shutdown, playback-start exceptions, and (on Android 11/API 30+) Android's historical process-exit reason plus a memory snapshot when Flowery is opened again.

Historical process-exit information is only available after the app is launched again and may not be available on older Android versions. Native crashes and operating-system process kills may require additional system logs or tombstone information.

## Capture a reproduction

1. Clear the old log buffer:
   ```sh
   adb logcat -c
   ```
2. Start Flowery and reproduce the unexpected closure.
3. Launch Flowery again so it can log Android's historical process-exit information.
4. Capture relevant records:
   ```sh
   adb logcat -d -v threadtime -s FloweryDiagnostics AndroidRuntime ActivityManager
   ```
5. Share the output and approximate reproduction time.

The diagnostic intentionally avoids recording full content URIs or filesystem paths.
