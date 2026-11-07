# Flowery Manual Test Log

This document records manual tests performed on a physical Android device. Results are based on user-observed behavior and are not a substitute for automated tests or hardware certification.

## Test environment

- Application: Flowery 0.1.0
- Package: `com.waizusora.flowery`
- Device: Android device (model and OS version not recorded)
- Library used during testing: selected `Music` folder containing 14 audio files
- Test method: manual, one test at a time
- USB DAC and other external hardware tests: deferred until software playback tests are complete

## Result legend

- **PASS** — all stated expected results were observed.
- **FAIL** — at least one expected result was not observed.
- **BLOCKED** — test could not be performed because a prerequisite was unavailable.
- **NOT RUN** — test has not yet been performed.
- **RETEST REQUIRED** — a fix has been made or is planned, but the test has not yet passed again.

## Completed test results

### TEST 1 — Installation, launch, navigation, and library scan

**Status: PASS**

Steps and observations:

1. Build the Android application using the repository CI workflow.
   - Result: Build Flowery workflow completed successfully.
2. Install the generated APK on the Android device.
   - Result: Android reported that Flowery 0.1.0 was installed.
3. Launch Flowery.
   - Result: Welcome screen appeared; no launch crash was observed.
4. Navigate through the main UI, Settings, DSP/EQ, Lyrics, Now Playing, and Folders.
   - Result: Screens rendered and navigation worked.
5. Open Settings > Library.
   - Result: Library controls were visible.
6. Add the device's `Music` folder through the system folder picker.
   - Result: Folder was added and shown as included in the library.
7. Run a library scan.
   - Result: Scan completed and found 14 audio files. Song titles, including Japanese characters, were displayed.

Notes:
- The empty synchronized-LRC message was expected for tracks without synchronized lyrics.
- The no-USB-Audio-Class-device message was expected because no USB DAC was connected.
- UI visibility does not verify actual DSP processing.

### TEST 2 — Playback progress and seek

**Status: PASS after application fix**

Steps and observations:

1. Start playback of a library track.
   - Result: Audio was audible and track information and album art were displayed.
2. Observe the playback progress indicator.
   - Initial result: FAIL. Progress remained at `0:00` while audio played.
3. The progress ticker issue was corrected in `PlaybackService.kt` by removing the route-check callback cancellation of the progress update callback.
4. Install the updated application and repeat the progress check.
   - Result: PASS. Progress advanced (for example, to `0:16 / 4:00`).
5. Drag the seek slider to approximately `1:00`.
   - Result: PASS. Playback moved to the requested position and played the corresponding audio segment.
   - Observation: Audio seemed faster while the slider was being dragged, but returned to normal speed after release.

### TEST 3 — Pause and resume

**Status: PASS**

Steps and observations:

1. Play a track until approximately `1:20`.
2. Press Pause.
   - Result: Playback paused successfully.
3. Wait approximately 10 seconds.
   - Result: Playback duration stopped advancing.
4. Press Play.
   - Result: Playback resumed from the previous position.
5. Verify audio.
   - Result: Audio sounded normal.

### TEST 4 — Next and previous track

**Status: PASS**

Steps and observations:

1. Start playback of a track.
2. Press Next.
   - Result: Playback moved to the next track.
3. Verify the displayed title.
   - Result: Title matched the playing track.
4. Press Previous.
   - Result: Playback returned to the previous track.
5. Verify audio.
   - Result: Audio sounded normal.

### TEST 5 — Repeat One

**Status: PASS — retest completed after fix**

Initial failure:
- Repeat One mode appeared active, but the track did not restart automatically at the end and playback did not return to `0:00`.
- The audio result was not evaluated during the initial failed test.

Fix:
- Code inspection found that the playback completion callback handled the queue transition synchronously on the audio worker thread. The Repeat One path called `player.stop()`, interrupting the worker while it was unwinding and closing its native playback session.
- Updated `PlaybackService.kt` to post completion handling to the main thread, allowing the completed worker to finish cleanup before playback is restarted.
- Fix commit: `6e64ff807f9d4363021870ff4dc92ceb9af8b306`.

Retest observations (user-confirmed):
1. Repeat One mode active: **Yes**.
2. Track restarted automatically: **Yes**.
3. Playback position returned to `0:00`: **Yes**.
4. Audio sounded normal: **Yes**.

Result: all expected Repeat One behaviors were observed on retest.

### TEST 6 — Repeat All

**Status: PASS**

User-confirmed observations:
1. Repeat All mode active: **Yes**.
2. Playback returned to the first track after the last track: **Yes**.
3. Playback restarted at `0:00`: **Yes**.
4. Audio sounded normal: **Yes**.

Result: all expected Repeat All behaviors were observed.

### TEST 7 — Shuffle

**Status: PASS**

User-confirmed observation:
- Shuffle functioned during the manual test: **Yes**.

Result: shuffle operation was confirmed by the user. The exact sequence of tracks and restoration of normal queue order after disabling Shuffle were not separately recorded.

## Remaining manual test plan

Perform each test separately. Record the observed result and update this log after each test. Do not mark a test as PASS without user-observed confirmation.

### TEST 8 — Track completion without repeat

1. Turn repeat off.
2. Play a short track or seek close to the end.
3. Let the track finish.
4. Expected: playback stops or follows the documented queue behavior; it must not unexpectedly restart.
5. Verify progress and displayed track state.

### TEST 9 — Playback across supported file formats

1. Select one file of each available format in the test library.
2. Play each file for at least 15–30 seconds.
3. Seek within each file where supported.
4. Expected: supported files decode, play without crashes, and report sensible duration and progress.
5. Record each tested extension, sample rate, bit depth (if known), and any error.

Do not claim support for every possible format based only on this sample.

### TEST 10 — Playback stability

1. Play a track continuously for at least 10 minutes.
2. Seek several times.
3. Pause and resume several times.
4. Navigate between tracks.
5. Expected: no crash, stuck progress, unintended silence, or persistent speed/pitch change.

### TEST 11 — Equalizer interaction and audio processing

1. Open DSP/EQ.
2. Change one band by a clearly noticeable amount.
3. Return to playback and listen at a safe volume.
4. Toggle EQ off and on.
5. Expected: controls retain their selected values and audible processing changes consistently.
6. Record whether the change is audible; UI movement alone is not proof that DSP is active.

### TEST 12 — Lyrics display

1. Play a track with a known local `.lrc` file.
2. Verify that lyrics appear and synchronize with playback.
3. Test a track with embedded lyrics if available.
4. Test a track with no lyrics.
5. Expected: available lyrics are displayed; missing lyrics are handled without a crash.

### TEST 13 — Library update and folder management

1. Add a second music folder.
2. Run an update scan.
3. Add a new audio file to an included folder and run update scan again.
4. Verify the new track appears.
5. Remove a folder from the library.
6. Expected: library membership updates correctly without deleting the user's original audio files.

### TEST 14 — Background playback and interruption behavior

1. Start playback.
2. Send Flowery to the background or lock the screen.
3. Verify playback continues as designed.
4. Return to the app and verify progress and track information.
5. If practical, test a normal audio interruption (for example, a phone call) and verify recovery.

### TEST 15 — Output routing: speaker, wired AUX, and Bluetooth

1. Test the built-in speaker.
2. Test wired AUX if the device supports it.
3. Test Bluetooth headphones or speaker.
4. Change system volume for each route.
5. Expected: audio uses the selected route and system volume behaves as intended for non-exclusive routes.

### TEST 16 — USB DAC and exclusive output

Deferred until software playback tests are complete and compatible hardware is available.

1. Connect a supported USB DAC.
2. Use **Sambungkan DAC** once to grant Android USB access if prompted; this is a connection permission action, not a Bit-perfect switch.
3. Start playback and verify Flowery automatically detects the permissioned DAC and selects a compatible output route.
4. Verify the Bit-perfect status changes to ACTIVE only when the native producer confirms source-preserving output; otherwise verify that the UI reports NOT ACTIVE or UNAVAILABLE with a reason.
5. Verify selected output route.
6. Test supported sample rates and channel formats.
7. Verify bit-perfect/exclusive behavior using suitable measurement or device-reported information; listening alone cannot prove bit-perfect output.
8. Test hardware DAC volume controls where available.
9. Test software volume when the DAC has no hardware volume control.
10. Disconnect and reconnect the DAC; verify safe recovery.

### TEST 17 — Final regression

1. Repeat installation/launch.
2. Repeat playback, progress, seek, pause/resume, next/previous, repeat, and shuffle.
3. Verify library and lyrics screens.
4. Verify there are no regressions introduced by fixes.
5. Record application version, commit, CI run, device, and Android version.

## Ongoing log maintenance

- Append a dated result under the relevant test whenever a test is completed.
- Update the test status and observed behavior; retain failure history rather than silently rewriting failures as passes.
- When a fix is made, record its commit and mark the affected test **RETEST REQUIRED** until the user verifies it on an installed build.
- Keep hardware-only tests deferred until software tests are complete.
