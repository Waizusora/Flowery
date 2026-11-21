# Android Background Playback Validation

This plan validates Flowery's media foreground service, process recovery, and playback persistence.
Battery optimization remains a user-controlled setting in Flowery Settings. No test in this plan changes
the app's battery-optimization exemption or requests the exemption automatically.

## Automated coverage

Run the normal CI workflow. It covers:
- playback recovery snapshot serialization and schema validation;
- queue metadata/CUE arrays normalization;
- persisted queue index, repeat/shuffle state, and track-relative position;
- persisted call-interruption latch;
- manual-resume policy after process recovery.

The service recovery contract is deliberately conservative:
- A restored session is always `PAUSED`, even if the previous snapshot says `wasPlaying=true`.
- The restored queue and CUE segment are retained.
- The next explicit Play command prepares a new engine session and seeks to the persisted track-relative position.
- A persisted call interruption never starts playback. The call-mode latch is cleared only when Android reports that call mode has ended; playback remains paused until Play is pressed.

## Device test prerequisites

- Install the same CI-built APK on a physical Android device.
- Grant access to a music folder and select a track; for CUE validation, use a multi-track CUE sheet.
- Confirm the media notification is visible and playback is active.
- Connect ADB over USB or wireless debugging.
- Record Android version, device model, OEM battery-management mode, output route, and logcat.

Package: `com.waizusora.flowery`

## Screen-off and Doze

1. Start playback and verify the notification shows Pause.
2. Turn the screen off for at least 15 minutes; verify playback and elapsed position continue.
3. With a test device, force Doze:

```sh
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle
adb shell dumpsys activity services com.waizusora.flowery
```

4. Verify the service remains a media playback foreground service and the track continues.
5. Exit forced idle:

```sh
adb shell dumpsys deviceidle unforce
adb shell dumpsys deviceidle
```

Do not run forced-idle tests during an important call or on a device whose current work must not be interrupted.

## Memory pressure

While playback is active:

```sh
adb shell am send-trim-memory com.waizusora.flowery RUNNING_LOW
adb shell am send-trim-memory com.waizusora.flowery RUNNING_CRITICAL
adb shell dumpsys activity processes | grep -A 8 com.waizusora.flowery
```

Verify that the playback service continues, the notification remains available, and playback position is monotonic.
Capture logcat around the test:

```sh
adb logcat -c
adb logcat -v threadtime -s FloweryDiagnostics
```

## Process-death recovery

Do not use Android Settings' Force stop for this test: force-stop is an explicit user stop and Android intentionally prevents sticky service restart.

1. Start playback and wait at least 5 seconds so the periodic recovery snapshot is written.
2. Record the current track, CUE segment, and approximate position.
3. Use a controlled process-kill test on a dedicated test device/emulator (for example, a test harness with permission to terminate the app process). Do not kill the process on a device used for active work.
4. Confirm Android recreates the sticky foreground service.
5. Confirm the service notification reports a restored paused session.
6. Confirm the track does not start automatically.
7. Press Play and verify the same queue index, CUE bounds, and approximate saved position are restored.
8. Repeat while the call-interruption latch is set; after restart, playback must remain paused and require manual Play after call mode has ended.

Expected diagnostic markers:
- `RECOVERY_SNAPSHOT_RESTORED`
- `RECOVERY_SERVICE_RESTART`
- `RECOVERY_MANUAL_PLAY`
- `CALL_INTERRUPTION_STARTED`
- `CALL_INTERRUPTION_ENDED manual_play_required=true`

## OEM battery restrictions

Battery optimization is intentionally not changed by Flowery. On each target OEM, test both the default setting and any user-selected restricted/background-limited mode through Android Settings:

- Xiaomi/Redmi/POCO: battery saver / background autostart controls.
- Samsung: Background usage limits / sleeping apps.
- OPPO/realme/OnePlus: app battery management / background activity.
- Vivo: background power management.

Record whether the OEM permits a media foreground service to continue. If an OEM explicitly restricts or force-stops the app, document the behavior and provide only a user-directed Settings path; do not silently change battery settings or claim immunity from OEM process management.

## Acceptance criteria

- No automatic playback after process recreation.
- Manual Play restores the saved queue item and position.
- CUE segment start/end and playlist metadata remain associated with the restored queue index.
- A call interruption survives process death as a paused state.
- Screen-off and ordinary Doze do not cause playback-position corruption.
- Memory pressure does not corrupt the persisted snapshot.
- A failed/invalid snapshot is ignored safely; the service must not start an arbitrary URI or auto-play.
- No test modifies battery-optimization settings.


## ADB diagnostic harness

Use the repository helper to collect reproducible device metadata, service state,
memory-trim observations, and Flowery diagnostic logs:

```sh
chmod +x scripts/validate_android_background_playback.sh
# Capture baseline and memory-pressure signals
scripts/validate_android_background_playback.sh --trim
# On a dedicated test device, also force Doze for 15 minutes
scripts/validate_android_background_playback.sh --trim --doze --duration 900
```

Set `ANDROID_SERIAL` or pass `--serial` when more than one device is connected.
Start playback manually before running the harness. It writes device model,
Android version, standby bucket, device-idle state, service dumps, and
`FloweryDiagnostics` logcat output into a timestamped output directory.

The helper deliberately does not force-stop or kill Flowery, change standby
buckets, request battery-optimization exemptions, or modify battery settings.
Do not interpret a successful script exit as proof that audio remained audible:
correlate the captured logs and service state with a human-observed playback
result. Process-death recovery still requires a separate controlled test on a
dedicated emulator/device; do not use Settings' Force stop.
