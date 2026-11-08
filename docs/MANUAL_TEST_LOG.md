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

**Status: PASS — user-confirmed (2026-11-08)**

User confirmed that all TEST 8 checks functioned as expected:
- Track completion without repeat behaved correctly.
- Playback did not unexpectedly restart.
- Playback progress and displayed track state behaved correctly.

The exact track, elapsed position, and detailed queue transition were not separately recorded.

### TEST 9 — Playback across supported file formats

**Status: PASS for the available test samples; format coverage remains partial**

User-confirmed results:
- FLAC: **PASS for playback**. Audio played normally.
- MP3: **PASS for playback**. User confirmed normal playback.
- M4A (ALAC): **PASS for sample playback and progress**. The original Tipe-X file and a separate free 1 MB ALAC sample initially produced no audible output, and playback duration/progress did not advance. After the ALAC magic-cookie channel-count fix was installed, the user confirmed that the 1 MB ALAC sample plays and its duration advances normally. Seeking and playback completion have not yet been separately reconfirmed.
- AAC: **PASS for sample playback**. User confirmed that a 256 kbps AAC sample from the Files-Sample AAC page plays. Progress advancement and seeking were not separately recorded.

Investigation and code changes (2026-11-08):
- Android identifies the source as `audio/mp4` with codec MIME `audio/alac`, and `libflowery_audio.so` loads successfully.
- The diagnostic reports `PLAYBACK_ERROR`, `IllegalArgumentException`, with the message `Audio channel layout is unavailable`. Playback fails before the AAudio output session begins.
- The Kotlin AAudio loop previously polled only the output scheduler and did not inspect Rust stream-pump status. The stream-pump now retains its error detail, exposes it through JNI, and the Kotlin playback loop fails promptly when the decode worker reports an error.
- The first metadata fallback checked Symphonia's explicit channel bitmask and channel-layout fields. Both remain unset for the tested ALAC tracks, so that fallback did not resolve the failure.
- Symphonia 0.5.4's ALAC decoder reads the channel count from its mandatory 24-byte or extended 48-byte ALAC magic-cookie configuration, but its decoder parameters remain a clone of the original track parameters. Flowery now reads the declared channel count from byte offset 9 of that validated ALAC configuration when the track channel fields are absent. It accepts only declared counts from 1 through 8 and does not assume stereo.
- The new helper has unit coverage for 24-byte and 48-byte configurations and rejects missing, malformed, and out-of-range channel data.
- Earlier diagnostic fixes: `80352ac`, `06194ca`, `1cbe4d5`. Channel metadata fallback: `8f49bc5`. ALAC magic-cookie channel-count fix: `b9aa04c`.
- The ALAC magic-cookie fix was installed and the user subsequently confirmed that the 1 MB ALAC sample plays and its duration advances normally. Seeking and completion still need confirmation.

Unavailable in the user's test library:
- WAV: **PASS for playback**. User confirmed that the 5-minute WAV sample plays.
- M4A (AAC): **PASS for sample playback**. User confirmed that a 256 kbps AAC sample plays; broader AAC profile coverage remains unverified.

Remaining:
- Verify seeking on ALAC, AAC, and WAV where supported.
- Record sample rate, bit depth (if known), and any errors.
- Broader profile and codec-container combinations remain unverified; do not claim support for every possible variant.

Do not treat the overall format test as complete until the available formats have been checked.

Do not claim support for every possible format based only on this sample.

### TEST 10 — Playback stability

**Status: PASS — seek, pause/resume, and next/previous confirmed by user (2026-11-08)**

Confirmed:
- 10-minute continuous playback: PASS. User reported normal audio with no interruption.
- Seek: FAIL on both built-in speaker and AUX. When the user moved the duration slider, playback position returned to 0:00 and audio became silent. This confirms the issue is not limited to the AUX route.
- The AAudio seek scheduler previously discarded the underlying native seek error and exposed only a generic error code. A diagnostic change now preserves the native failure detail for the next controlled verification; this change is not itself proof that seek has been fixed.
- Pause/resume and track navigation: NOT TESTED.

1. Play a track continuously for at least 10 minutes.
2. Seek several times.
3. Pause and resume several times.
4. Navigate between tracks.
5. Expected: no crash, stuck progress, unintended silence, or persistent speed/pitch change.


Continuation checkpoint (2026-11-08):
- Repository review on `main` confirmed that `FloweryPlayer.seekTo()` submits a bounded seek request to the native AAudio playback session and rejects a negative native epoch. The playback loop also checks the output-scheduler status and reports the native seek-error detail when the scheduler fails.
- This is a source-code inspection only. It does not demonstrate that a seek completes successfully on a physical device, and it does not change the existing **FAIL** result for speaker/AUX seeking.
- No Android device-control or instrumentation session was available during this continuation, so the required installed-build retest could not be executed. **TEST 10 remains PARTIAL; seek retest is still required.**
- Next controlled check: install the latest `main` build, start a track on the built-in speaker, seek to a middle position, and capture the exact Flowery playback error/diagnostic if audio stops. Repeat on AUX only after the speaker check passes. Then verify pause/resume and track navigation before proceeding to TEST 11.

Diagnostic evidence received (2026-11-08; source: `Waizusora/Log/flowery-usb-diagnostic-1794111375694.txt`, recorded at 2026-11-08 11:16:15 +0700):
- The diagnostic captured two FLAC playback requests. Both ended with `IllegalStateException: The Rust audio decode worker is unavailable.` (11:16:02.935 and 11:16:07.371).
- The track metadata was available (`durationMs=231826`), but playback remained stopped at `positionMs=0`; the final snapshot reported `playing=false`.
- The user confirmed this diagnostic was recorded while using the phone's built-in speaker. Therefore, `connected=false`, `permissionGranted=false`, and `No USB Audio Class DAC detected` are expected and are not a USB DAC failure. The Android-route fallback was the expected speaker route. This run does not test DAC playback or exclusive USB output.
- AAudio telemetry briefly reported a shared 44.1 kHz stream on actual device ID 3, but `callbackCount=0`, `framesWritten=0`, and hardware frame/timestamp counters remained zero. This is not evidence of successful audio output.
- Source inspection found that the Kotlin playback loop independently polled `nativeGetStreamPumpStatus()` and treated a missing worker-registry entry (`-1`) as an immediate playback failure. The native AAudio output scheduler already monitors decode-worker health; its source comment explicitly says Kotlin should observe the scheduler rather than poll the worker a second time. During a seek, the native seek path temporarily removes and replaces the worker registry entry, so the extra Kotlin poll can observe a transient absence and report a false worker-unavailable error. This is a credible code-level race; the supplied diagnostic alone does not prove that a seek-triggered replacement caused these two startup failures.
- Code change committed to `main` in `c80ca90c3d46a086c9f2559579ac9690f9de3108`: removed the redundant Kotlin worker-status poll and left worker lifecycle/error monitoring to the native AAudio scheduler. A genuinely missing worker is still handled by the native scheduler and surfaced as a playback-session failure; the change does not suppress native failure handling.
- The updated code has not yet been built or retested on the user's phone. TEST 10 remains PARTIAL; speaker playback and seek require a controlled retest. The earlier speaker/AUX seek result remains FAIL until verified. Pause/resume and track navigation remain NOT TESTED.

Retest after the decode-worker polling fix (2026-11-08):
- **Seek: PASS (user-confirmed).** The user reported that seeking now succeeds after installing/testing the updated build. This supersedes the earlier speaker/AUX seek failures for the tested configuration; the exact output route and number of repeated seeks were not specified in the report.
- Diagnostic file received: `Waizusora/Log/flowery-usb-diagnostic-1794112393872.txt`, recorded at 2026-11-08 11:33:13 +0700. It identifies the FLAC track `As I Lay Dying - The Darkest Nights` (duration 231826 ms) and a final snapshot of `playing=false`, `positionMs=68526`, route status `Paused`.
- The diagnostic shows three Android-route FLAC play requests and a shared 44.1 kHz AAudio stream (actual device ID 910). No USB DAC was detected; consistent with the earlier speaker-route clarification, this is not evidence of a USB DAC fault and this file does not test direct USB output.
- **Diagnostic limitation:** sampled playback snapshots mostly report `positionMs=0` and `isPlaying=false`; the final header reports a paused position of 68526 ms. The file contains no explicit seek-request/completion event, and its AAudio telemetry reports `callbackCount=0` and `framesWritten=0` while queued PCM remains near capacity. Consequently, this diagnostic does not independently prove audible output or seek completion. The user's successful manual retest is recorded as the seek result; the telemetry discrepancy remains an observation, not a confirmed playback defect.
- At the time of this diagnostic review, pause/resume and next/previous had not yet been confirmed. This historical status is superseded by the user-confirmed retest below.

Final manual retest (2026-11-08; user-confirmed):
- **Seek: PASS.** Moving the playback position successfully seeks to the selected position.
- **Pause/resume: PASS.** Pause stops playback and resume continues playback successfully.
- **Next/previous: PASS.** Next advances to the next track and Previous returns to the preceding track successfully.
- These results complete the playback-stability checks in TEST 10. Earlier seek failures and diagnostic limitations above are retained as historical records; they are superseded for the tested build/configuration by this successful user retest.
- No claim is made here about USB DAC or exclusive/bit-perfect output; those remain covered by TEST 16.

### TEST 11 — Equalizer interaction and audio processing

**Status: PARTIAL — response latency and bypass verified; perceived EQ strength still needs investigation**

Manual observation (user-confirmed, 2026-11-08):
- The user raised and lowered an individual EQ band to **+12 dB** and **-12 dB** during playback.
- An audible change was present in both directions, confirming that the EQ adjustment affects the sound.
- The perceived change was subtle, even at these settings.
- The audible change began approximately **3 seconds** after the adjustment.
- Follow-up clarification: the sound does **not** change gradually during those 3 seconds; it remains unchanged, then the new EQ setting becomes audible after approximately 3 seconds.
- In a follow-up listening check, the EQ effect was perceived as **slightly stronger / more noticeable**, rather than merely faint; the response delay remained present.
- Output route for this EQ test: not specified; USB DAC was not in use.

Not yet verified:
- Whether all 31 bands respond correctly across their frequency ranges.
- EQ ON/OFF bypass: **NOT AVAILABLE IN THE CURRENT MENU**. The user confirmed that no EQ ON/OFF control is present in the menu they tested; this item cannot currently be tested from that UI.
- Whether band values persist after leaving and reopening the EQ screen or restarting playback.
- Whether the approximately 3-second response delay is intentional smoothing/buffering or an implementation latency issue.

Next checks:
1. Repeat a controlled adjustment on a low, mid, and high frequency band.
2. Check whether the audible response delay is consistently around 3 seconds.
3. Toggle EQ OFF/ON and verify the bypass effect.
4. Reopen the EQ screen and verify the selected values remain intact.

Do not mark the full EQ test PASS until the remaining checks are completed. The audible response is user-confirmed; the subtle effect and observed delay are recorded as findings, not yet diagnosed as defects.
- Source inspection (2026-11-08): the DSP screen currently exposes 31 band sliders and output volume, but its `DspDestination` composable has no EQ enable/bypass control or callback. The absence of EQ ON/OFF is consistent with the current UI implementation, not a missed user interaction.
- Source inspection also found that Android-route playback updates the Rust streaming decoder's EQ gains on the decode worker, before it reads and queues subsequent PCM chunks. PCM already decoded and placed in the output ring buffer retains the old EQ processing; changing decoder coefficients cannot retroactively change those queued frames. The latest supplied playback diagnostic showed the output queue near 131072 frames. At 44.1 kHz, 131072 frames represent approximately 2.97 seconds of audio, which closely matches the user's observed approximately 3-second delay. This is a strong, evidence-based explanation for the delay, but the exact queue occupancy at the moment of the EQ adjustment was not captured, so it is not yet proven as the sole cause.
- Implementation update (2026-11-08): the Rust AAudio decode producer now caps queued PCM to approximately 250 ms at normal music sample rates, while retaining the larger ring as spare capacity. The output scheduler starts after an approximately 100 ms prebuffer. This prevents the producer from filling nearly three seconds of already-processed PCM ahead of playback. Rust policy tests cover queue-target and low-water calculations.
- Implementation update (2026-11-08): the DSP screen now has an EQ enable/bypass switch. Disabling EQ sends a flat gain configuration to the playback service; the 31 slider values remain visible and are retained in the UI, and enabling EQ reapplies those values. The enabled state is saved in app preferences.
- User retest after the latency/bypass changes (2026-11-08): **response latency: PASS** (EQ changes are audible promptly); **bypass: PASS** (the user can hear the difference between EQ enabled and disabled); **playback stability: PASS** (the user reports no dropouts or interruptions during this test). The route was not explicitly restated in this retest; USB DAC operation is not claimed.
- Remaining finding: **EQ effect strength is still perceived as too weak**, including when using the available positive gain adjustment.
- Implementation update (2026-11-08): following the user's selection to retain the ±12 dB gain limit while widening each band's frequency response, the native peaking-biquad Q was changed from approximately 4.318 (strict one-third-octave bandwidth) to **Q=3.0**. The 31 ISO-aligned center frequencies and gain clamp are unchanged. This broadens the response around each center without increasing the maximum boost/cut. A native unit test checks the center gain and verifies meaningful response at nearby frequencies. This is an intentional departure from strict ISO one-third-octave bandwidth; documentation and README wording were updated accordingly.
- **Retest required:** install the updated build and compare a single-band boost/cut at low, mid, and high frequencies. Check for excessive overlap or tonal coloration, verify EQ bypass still removes the effect, and confirm playback remains free of dropouts. The source/test change is not a substitute for listening verification. This is a subjective listening observation and does not by itself establish an incorrect dB implementation. Further inspection of the Rust peaking-filter coefficient calculation, band center frequencies/Q, and gain-update path is required before changing gain behavior.
- TEST 11 remains PARTIAL until the perceived strength is investigated and the complete band/persistence checks are performed. The latest user retest confirms that the queue-latency fix, bypass control, and playback stability work on the tested configuration.

Design revision (2026-11-08; user-approved):
- The DSP UI is simplified from 31 visible sliders to three vertical controls: **Bass, Mid, and Treble**. The slider style is retained.
- The three controls are mapped onto all 31 native ISO preferred center frequencies. Bass remains flat through 200 Hz and smoothly transitions to Mid by 500 Hz; Mid remains flat from 500 Hz through 2 kHz and smoothly transitions to Treble by 5 kHz; Treble remains flat above 5 kHz. Interpolation uses log-frequency smoothstep.
- The native filters are restored from Q=3.0 to the strict ISO one-third-octave Q relation, approximately **4.318**. The 31 ISO center frequencies and ±12 dB per-band gain clamp remain unchanged.
- Bass, Mid, and Treble values are persisted independently. EQ ON/OFF continues to bypass or restore the mapped 31-band gain array without discarding the three control values.
- The prior Q=3.0 listening retest does not validate this revised response. **Retest required** after installing the updated build: compare Bass, Mid, and Treble independently at positive and negative gain; verify smooth tonal transitions, bypass, persisted values, prompt response, and absence of dropouts. TEST 11 remains PARTIAL until the user confirms these checks.

Final EQ retest after the three-control / strict ISO-band revision (2026-11-08; user-confirmed):
- **Bass, Mid, and Treble controls: PASS.** The user confirmed that all three EQ controls function.
- **Perceived EQ strength: PASS (subjective listening confirmation).** The user confirmed that the tonal adjustment strength is now satisfactory.
- The user’s confirmation applies to the latest three-control build. It supersedes the earlier concern that EQ boost/cut felt too weak.
- EQ response latency and bypass were already reported as working in the preceding manual retest. The user has not separately reported a new regression in those behaviors.
- **Remaining coverage:** persistence after app restart, systematic frequency-response measurement, and exhaustive checks across all 31 internal ISO filters have not been independently verified. USB DAC behavior remains outside this test and is tracked under TEST 16.
- TEST 11 is recorded as **PASS for the user-facing three-band EQ behavior**, with the remaining engineering/route coverage limitations retained above.

### TEST 12 — Lyrics display

**Status: PARTIAL — sidecar LRC display confirmed; enhanced-LRC rendering and centered scrolling require manual retest**

Initial manual observation (user-confirmed, 2026-11-08):
- A local `.lrc` sidecar file is detected and lyrics appear during playback.
- The user reports that lyric changes appear slightly early by an estimated few milliseconds; the exact offset has not been measured.
- The lyric list does not keep the active line centered before and during scrolling. The requested behavior is to place the upcoming/current line in the center, keep it centered as playback advances, and scroll in sync with each line's timing.
- The supplied screenshot shows inline enhanced-LRC word timestamps such as `<00:31.59>` being displayed as visible lyric text. These are timing markers and must be parsed and hidden.
- The screenshot's format uses per-word timestamps. The desired behavior is to highlight each word at its timestamp while keeping the active lyric line centered. Plain line-timed LRC must continue to work.
- The user's final clarification is interpreted as: timestamp metadata must not appear in displayed lyric text.

Implementation committed for retest (2026-11-08):
- The native LRC parser now recognizes standard square-bracket line timestamps and enhanced angle-bracket word timestamps. Angle-bracket markers are removed from display text; word timing is retained for word-level highlighting. Files containing word timestamps without a square-bracket line timestamp use the first word timestamp as the line start.
- The parser applies a valid `[offset:+/-milliseconds]` metadata value to line and word timestamps. No arbitrary default offset is applied because the user's reported early timing has not been measured.
- The Now Playing lyric list centers the upcoming/active line and keeps it centered as the active line changes. Enhanced-LRC words highlight according to their own timestamps; standard LRC continues to highlight the active line.
- Top/bottom scroll space allows the first and final lyric lines to reach the centered position.
- Automated parser tests cover enhanced word timestamps, word-only timestamps, offset metadata, standard LRC, and current-line lookup. Device playback timing, visual centering, and the user's actual LRC file still require manual retest.

Additional manual observation — track without available lyrics (user-reported, 2026-11-08):
- A track with no readable sidecar or embedded lyrics displays the `No synchronized lyric found` empty state.
- No crash or other playback/display issue was observed. **No-lyrics empty-state handling: PASS.**
- This is expected with the current feature scope: automatic lyric discovery/download and embedded-lyrics extraction have not yet been implemented. The empty state does not indicate a failure of an implemented lyric search feature.
- Automatic online `.lrc` retrieval and embedded lyric extraction remain future implementation work and are not counted as failed tests.

Retest required:
1. Install the latest build and play the same track/LRC file.
2. Confirm no `<mm:ss.xx>` markers are visible in lyric text.
3. Confirm the active line stays centered from before the first lyric through subsequent lines.
4. Confirm enhanced word highlighting follows the vocal timing and standard LRC remains line-synchronized.
5. Report whether the lyrics still lead the vocal; if so, estimate the offset or use the `[offset:]` metadata for calibration.
6. Confirm that a track with no readable/synchronized LRC is handled cleanly without showing raw timestamp text or crashing.

### TEST 13 — Library update and folder management

**Status: PASS — user-confirmed (2026-11-08)**

Manual result:
- The user confirmed the folder/library update test works normally.
- Result recorded as PASS based on the user's report. Individual sub-step observations (second folder, update scan, newly added file, duplicate handling, folder removal, and source-file preservation) were not reported separately.

Test procedure:

1. Add a second music folder.
2. Run an update scan.
3. Add a new audio file to an included folder and run update scan again.
4. Verify the new track appears.
5. Remove a folder from the library.
6. Expected: library membership updates correctly without deleting the user's original audio files.

### TEST 14 — Background playback and interruption behavior

**Status: PASS — background playback, screen lock, and incoming-call pause verified**

Manual result (user-confirmed, 2026-11-08):
- Background playback and screen-lock behavior were reported as normal.
- An incoming phone call successfully caused Flowery to pause playback.
- After the call ended, playback remained paused until the user manually pressed Play. This is the observed behavior; automatic resume after a call is not implemented/verified by this test.
- No unexpected playback restart was reported.

Test procedure:
1. Start playback.
2. Send Flowery to the background or lock the screen.
3. Verify playback continues as designed.
4. Return to the app and verify progress and track information.
5. Receive an incoming phone call and verify playback pauses.
6. After the call ends, verify playback remains paused until manually resumed.

### TEST 15 — Output routing: speaker, wired AUX, and Bluetooth

**Status: PARTIAL — built-in speaker PASS; AUX and Bluetooth not yet tested (2026-11-08)**

User-confirmed result:
- Built-in speaker: **PASS**. Audio output through the phone's internal speaker was reported normal.
- Wired AUX: **PASS**. User confirmed normal wired AUX output (2026-11-08).
- Bluetooth: **BLOCKED** — the user does not have a Bluetooth audio device available for testing (2026-11-08).

Remaining procedure:
1. Test wired AUX if the device supports it.
2. Test Bluetooth headphones or speaker.
3. Change system volume for each remaining route.
4. Expected: audio uses the selected route and system volume behaves as intended for non-exclusive routes.

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

**Status: PARTIAL — Stages 1–2 PASS; remaining regression stages not yet tested (2026-11-08)**

User-confirmed results:
- **Stage 1 — Installation and app launch: PASS.** Flowery reopened successfully; Library and Now Playing screens displayed normally.
- **Stage 2 — Playback controls: PASS.** Playback progress advanced, seeking worked, Pause/Play worked, and Next/Previous worked. The user confirmed audio continued without interruption.
- Stages 3–4: **NOT RUN**.

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

### TEST 18 — Unexpected application/service termination during playback

**Status: PASS — user-confirmed retest after defensive fix (2026-11-08)**

User-reported observation (2026-11-08):
- When a track finishes, Flowery sometimes closes and the music notification disappears at the same time.
- The same unexpected closure has also occurred when selecting a track.
- The missing notification suggests the foreground playback service or app process stopped. It does not distinguish an intentional app stop from an Android process kill, Java crash, native crash, or ANR.

Source inspection:
- The service removes its foreground notification and calls `stopSelf()` when native queue transition returns `-1`.
- Native queue policy returns `-1` at the end of the loaded queue when Repeat is Off. This is expected only if the selected item is actually the final item in the loaded queue.
- The activity builds the queue from the `folderTracks` list supplied by the selected library screen (or the full scanned list for the resume path). The actual queue size/index during the incident has not yet been captured.
- The service declares `foregroundServiceType="mediaPlayback"` and currently returns `START_NOT_STICKY`; this does not prove Android caused the termination.

Diagnostic instrumentation committed:
- Added `FloweryDiagnostics` log events for service lifecycle/task removal, queue size/index, track start, natural completion transition, intentional stop, and playback-start exceptions.
- On Android 11 (API 30+) app launch logs Android's recent `ApplicationExitInfo` reason/status/importance/PSS/RSS and a memory snapshot.
- No queue policy, service restart policy, decoder, DSP, or output behavior was changed.
- Capture instructions are in `docs/PLAYBACK_DIAGNOSTICS.md`.

ADB log review (2026-11-08; user-supplied `Waizusora/Log/logcat.txt`):
- The captured incident is a confirmed Java/Kotlin process crash, not an Android low-memory kill.
- At 16:48:24, the service loaded a queue of 72 tracks at index 2 (Repeat Off, Shuffle Off) and began `Through Struggle`.
- At 16:48:25, the main thread crashed with `java.lang.ArithmeticException: divide by zero` in `FloweryPlayer.positionMs(FloweryPlayer.kt:691)`, called by `PlaybackCoordinator.positionMs()` and the `PlaybackService` progress ticker.
- On the next launch, `ApplicationExitInfo` reported `reason=4` and `description=crash`. The log also showed a later new service start and playback of another track.
- The stack trace points to the position calculation's sample-rate denominator. Source inspection found that `stop()` and USB playback teardown can reset the volatile `outputSampleRate` to zero while the progress ticker reads it. The previous expression checked the field and then read it again for division, allowing a teardown race between those reads.
- Defensive fix committed: `positionMs()` now snapshots the sample rate once, returns zero when the snapshot is non-positive, and uses that same snapshot for the frame-to-millisecond conversion. The encoded USB pipeline continues to supply its own position.
- This fix addresses the exact crash path shown in the log. It does not yet prove all unexpected termination scenarios are resolved.

Retest procedure:
1. Install the diagnostic build and clear logcat.
2. Play a track that is not the final visible item in its folder.
3. If Flowery closes, relaunch it once to record historical process-exit details.
4. Capture `FloweryDiagnostics`, `AndroidRuntime`, and `ActivityManager` logcat records.
5. Include whether Repeat was Off/All/One and the active output route.

Do not classify the cause or mark this test PASS until queue size/index and process-exit/service lifecycle evidence are reviewed.

CI follow-up (2026-11-08):
- Workflow run #1442 (`42464600678`) failed at `:app:compileDebugKotlin` in `NowPlayingDestination.kt`.
- Root cause: three Compose text imports had been written as one source line containing literal `\n` characters. Kotlin consequently treated the following imports as invalid top-level syntax and could not resolve `buildAnnotatedString`/`withStyle`.
- Corrected the imports to three actual Kotlin import lines. Awaiting the workflow result for the corrective commit.


CI follow-up update (2026-11-08):
- Corrective workflow run #1443 (`42464929664`) for commit `fc931a9f049a7342f21cfc03832eeedaa07e5533` completed with **success**.
- All workflow steps passed, including Rust native unit tests, Kotlin/JNI declaration verification, Android unit tests, native library builds, and debug/release APK builds.
- This confirms the source compiles and automated tests pass for this commit. It does not replace physical-device retesting of lyrics or playback behavior.


Physical-device retest after fix (2026-11-08; user-confirmed):
- Installed build containing defensive fix commit `360c1f7`; CI workflow #1447 completed successfully and produced the tested APK artifact.
- Scenario A — repeated track selection (5–10 changes): **PASS**. Flowery remained open and playback behavior remained normal.
- Scenario B — natural track completion with Repeat Off, using a track that was not the final visible item: **PASS**. No unexpected application closure was observed; queue completion behaved normally.
- Scenario C — sequential selection of multiple different tracks: **PASS**. Audio and playback notification remained normal.
- These results verify the reported reproduction scenarios on the user's device. They do not prove that every possible crash or service-termination path is eliminated.
