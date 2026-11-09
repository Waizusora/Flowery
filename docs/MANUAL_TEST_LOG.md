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
2. Seek several times.3. Pause and resume several times.
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

**Status: PASS for the final regression checks (2026-11-08); broader lyric-source coverage remains incomplete**

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

Final regression retest (2026-11-08; user-confirmed):
- The user confirmed all four lyric checks passed on the tested build: lyrics display, active-line centering and timing, enhanced-LRC timestamp markers hidden from displayed text, and clean handling of a track without lyrics.
- This confirms the tested lyric UI and rendering behavior. Automatic online lyric retrieval and embedded-lyrics extraction remain unimplemented and are not covered by this PASS.
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

Initial USB DAC test (2026-11-08; user-confirmed):

- USB DAC detected by Flowery: **PASS**.
- Android USB permission prompt appeared and permission was granted: **PASS**.
- DAC was not shown in Now Playing: **NOT VERIFIED / UI GAP**. No Bit-perfect status was visible there.
- Audio output through the USB DAC: **PASS**.
- Built-in phone speaker remained silent during USB DAC playback: **PASS**.
- Playback continued without audible clicks, pops, crackling, or dropouts during the observed interval: **PASS**.
- Playback progress advanced normally: **PASS**.
- User perceived a small, persistent delay during playback: **ISSUE REPORTED; cause not established**.
- Selecting a track took approximately 3 seconds before audio began: **ISSUE REPORTED; cause not established**.
- Output volume was perceived as excessively loud: **SAFETY-RELEVANT ISSUE; further volume testing paused**. Do not increase volume until the active hardware/software volume path is verified.
- User confirmed the DAC has **no physical volume buttons or knob**. Software volume is therefore required for safe level control on this device.
- Source inspection: the per-device USB software-volume default is 20%, but previously saved values are restored and can be higher. The current saved value and the value actually applied during this test have not yet been captured.

These observations confirm that audio is reaching the connected DAC, but they do not establish bit-perfect output, exclusive ownership, sample-rate preservation, or correct volume control. The delay and excessive volume require investigation before proceeding with the remaining TEST 16 checks.

Deferred until software playback tests are complete and compatible hardware is available.

1. Connect a supported USB DAC.2. Use **Sambungkan DAC** once to grant Android USB access if prompted; this is a connection permission action, not a Bit-perfect switch.
3. Start playback and verify Flowery automatically detects the permissioned DAC and selects a compatible output route.
4. Verify the Bit-perfect status changes to ACTIVE only when the native producer confirms source-preserving output; otherwise verify that the UI reports NOT ACTIVE or UNAVAILABLE with a reason.
5. Verify selected output route.
6. Test supported sample rates and channel formats.
7. Verify bit-perfect/exclusive behavior using suitable measurement or device-reported information; listening alone cannot prove bit-perfect output.
8. Test hardware DAC volume controls where available.
9. Test software volume when the DAC has no hardware volume control.
10. Disconnect and reconnect the DAC; verify safe recovery.

### TEST 17 — Final regression

**Status: PASS — all final regression stages completed (2026-11-08)**

User-confirmed results:
- **Stage 1 — Installation and app launch: PASS.** Flowery reopened successfully; Library and Now Playing screens displayed normally.
- **Stage 2 — Playback controls: PASS.** Playback progress advanced, seeking worked, Pause/Play worked, and Next/Previous worked. The user confirmed audio continued without interruption.
- **Stage 3 — Playback modes: PASS.**
  - Repeat One: **PASS** (user-confirmed, 2026-11-08).
  - Repeat All: **PASS** (user-confirmed, 2026-11-08).
  - Shuffle: **PASS** (user-confirmed, 2026-11-08).
- **Stage 4 — EQ: PASS (user-confirmed, 2026-11-08).** The user reported checks 1–4 passed. This confirms the EQ regression checks performed in this stage; the exact individual observations were not separately itemized.
- **Library regression: PASS (user-confirmed, 2026-11-08).** The user reported that the Library check behaved normally.
- **Lyrics regression: PASS (user-confirmed, 2026-11-08).** The user confirmed all four checks passed: synchronized lyrics display, active-line centering/timing, hidden enhanced-LRC timestamp markers, and clean handling of tracks without lyrics.

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

USB DAC Pause defect and fix (2026-11-08):
- User reported that pressing Pause changed the UI icon to the paused state, but audio from the USB DAC continued playing continuously.
- Source inspection confirmed that the PCM transport loop stopped polling new producer blocks while paused, but did not cancel isochronous USB transfers already submitted to libusb. The encoded AC-3 path called `drainTransport()` on pause, which explicitly waited for queued audio to finish rather than stopping it.
- Fix committed: both PCM and encoded USB playback paths now call the existing synchronized USB-session `flush()` once when entering the paused state. This cancels and reaps queued endpoint transfers; the playback loop remains paused until resume. The PCM/encoded producer queues are retained for continuation, although a small amount of audio already handed to the transport may be discarded at the pause boundary.
- Updated `UsbDacNative.kt` documentation to describe flush usage for pause as well as seek.
- **Status: RETEST REQUIRED.** The code change has not yet been built or physically verified on the user's DAC. Do not mark Pause as PASS until the user confirms that audio stops promptly and resumes correctly.

USB DAC natural track-completion report (2026-11-08; user-confirmed):
- During playback through the USB DAC, a track that was not the last track in the folder reached its end and playback stopped instead of advancing to the next track.
- The playback notification remained visible, but the playback position stopped.
- This is a distinct observation from the earlier unexpected app/service termination in TEST 18: the user reports that the notification remains present, so this report does not establish that the service or process terminated.
- Source path to verify: USB pipeline end-of-stream / `Playback finished` callback, followed by `PlaybackService.handleCompletion()`, native queue transition, and the next `playCurrent()` request.
- Existing diagnostics to inspect during reproduction: `DIRECT_USB_FINISHED`, `DECODER_EOF`, `TRACK_COMPLETION`, `PLAY_CURRENT`, and `PLAYBACK_START_FAILURE`. The expected queue size, completed index, next index, Repeat mode, and exact USB route must be captured before assigning a root cause.
- **Status: INVESTIGATION REQUIRED / RETEST REQUIRED.** No code change has been made for this report; the cause is not yet established. The earlier non-USB track-completion retest remains PASS and does not verify USB DAC completion.


USB DAC natural-completion diagnostic capture (2026-11-08; user-supplied `flowery-usb-diagnostic-1794139484748.txt`):

- DAC: KTMicro KT-384/32 HIFI, VID/PID `31B2:0111`; active output endpoint `0x04`; negotiated sample rate 96,000 Hz.
- The diagnostic snapshot identifies the current source as `YOASOBI - 02. Idol`, duration 212,477 ms, and reports `playing=false` with a USB transport error.
- Playback telemetry records 623 PCM chunks and 20,397,862 frames submitted. The final partial chunk was accepted; the decoder then emitted `DECODER_EOF` at 19:04:22.911.
- At 19:04:24.508, the direct USB path emitted `DIRECT_USB_ERROR`; at 19:04:24.684, the player emitted `PLAYBACK_ERROR`. The error was the native transport's own stall watchdog: `LIBUSB_ERROR_TIMEOUT (-7)`, reason `stalled`, oldest outstanding sequence 26746, 23 transfers in flight, and no reported progress for approximately 1,503 ms.
- Source inspection confirms that the PCM pipeline calls `dacSession.drain()` after decoder EOF. The native `nativeDrain()` waits for all queued isochronous transfers to complete and calls `wait_for_progress()`; that function raises the reported timeout when outstanding transfers make no progress for `kStallTimeoutMs = 1500`.
- Consequently, decoder EOF is reached, but the transport drain throws before `DIRECT_USB_FINISHED` and before the player reports `Playback finished`. The playback service therefore does not receive a normal completion event to advance the queue. This explains the stopped position and lack of automatic next-track transition in this capture.
- The log does not establish why the USB host/device stopped completing the outstanding isochronous transfers. In particular, it does not yet distinguish a DAC/endpoint behavior, host-controller/libusb event-delivery issue, or a scheduling/packetization defect in the transport implementation. The descriptor snapshot reports an adaptive OUT endpoint and does not advertise supported sample rates in the selected profile; this alone is not proof of incompatibility at 96 kHz.
- No transport code change is made based on this capture alone. Do not suppress the watchdog or treat a failed drain as successful completion: doing so could truncate queued audio and conceal a transport failure.
- **Status: ROOT CAUSE OF THE QUEUE-ADVANCE FAILURE IDENTIFIED; UNDERLYING ISOCHRONOUS TRANSPORT STALL INVESTIGATION REQUIRED.** USB DAC physical retest remains required after a transport fix. Earlier non-USB track-completion PASS remains valid only for the non-USB route.


USB DAC isochronous queue-depth mitigation (2026-11-08; source change committed):

- Reduced the native isochronous scheduling target from 192 ms to 32 ms. With 64 packets per URB and a 125 us service interval, each URB spans approximately 8 ms, so the target normally permits about four URBs in flight instead of up to 24.
- Reduced the hard in-flight URB cap from 24 to 8. The existing 1,500 ms no-progress watchdog remains enabled; a failed drain is not treated as successful playback completion.
- Added native transport counters to future stall exceptions: successful URB submissions, transfer callbacks received, event-loop iterations, libusb event-loop error, time since the last event-loop iteration, configured queue target, and hard URB cap.
- The change is a bounded scheduling-depth mitigation and diagnostic experiment. The supplied log proves that the prior transport window had 23 outstanding URBs when progress stopped, but it does not prove that queue depth alone caused the endpoint stall.
- **Status: IMPLEMENTED; BUILD/CI AND PHYSICAL USB DAC RETEST REQUIRED.** No claim is made yet that the DAC stall or automatic next-track transition is fixed. Preserve the user's previously confirmed safe software-volume setting before testing.

USB DAC retest after reduced isochronous scheduling window (2026-11-08; user report and diagnostic capture `flowery-usb-diagnostic-1794144861130.txt`):

- User reports that playback still stops instead of advancing, the notification remains visible, and audio is stuttering with audible delay.
- The capture confirms that the 32 ms queue target / 8-URB cap from the mitigation build is active. GitHub Actions workflow #1471 for the corresponding main-branch commit completed successfully; this confirms the build, not physical USB correctness.
- DAC remains KTMicro KT-384/32 HIFI (VID/PID `31B2:0111`), output endpoint `0x04`, PCM at 96,000 Hz. The active USB volume profile is `software_only`, with `initialVolume=0.2` and no physical volume control detected. Do not perform further listening tests until the USB software volume is verified and reduced to a safe low level.
- The decoder reached EOF after 623 chunks and 20,397,862 frames. At 96 kHz this is 212,477.7 ms, matching the reported track duration of 212,477 ms. The final USB transport drain then failed with the stall watchdog: 3 URBs remained outstanding and no completion progress was recorded for 1,504 ms. The diagnostic reports `queue_target_us=32000`, `max_urbs=8`, `event_loops=33563`, `event_loop_error=0`, and `event_loop_idle_ms=0`; therefore, the event thread was still running and the smaller queue did not prevent the endpoint from stalling.
- The reported submitted/callback counters are equal in the final exception record because the failure path aborts outstanding URBs and receives their cancellation callbacks after the watchdog has already recorded the failure. This must not be interpreted as proof that the final URBs completed normally.
- No normal `DIRECT_USB_FINISHED` event is produced after the failed drain, so the playback service does not receive the normal end-of-track completion signal. This remains the direct reason automatic queue advancement is skipped in this capture.
- Across 622 full PCM chunks, the median interval between logged writes is approximately 456 ms while each full chunk represents 341.33 ms of audio. This is consistent with the user's report of audible pacing gaps, although the log alone does not isolate whether the extra interval originates in decode production, Kotlin queue scheduling, or native USB submission.
- **Status: MITIGATION DID NOT RESOLVE THE USB DAC STALL.** Root cause remains within the isochronous transport / final-drain path. No further queue-depth-only changes should be made without investigating packet completion/reaping and the end-of-stream scheduling behavior. USB DAC next-track behavior remains FAIL / INVESTIGATION REQUIRED; non-USB completion tests remain independently PASS.

USB DAC software-volume persistence report (2026-11-08):

- User confirms the DSP output-volume slider is visible and can be adjusted to 5%, but when another track is played the active USB DAC volume returns to 20%.
- Source inspection: the DAC has no hardware volume control; the per-device software-volume preference is keyed by VID/PID and defaults to 20% only when no stored value exists. The playback attach path reads this preference for each new USB session.
- The existing preference write used asynchronous `SharedPreferences.apply()`. Updated it to synchronously commit the requested per-device fraction and verify the stored value before returning. A persistence failure now raises an explicit error instead of silently continuing with an unconfirmed value.
- Added diagnostics for the USB volume request, applied value, per-device key, and value restored when a new USB session attaches. This will distinguish a failed persistence write from a request that never reaches the USB volume policy or a key mismatch.
- **Status: CODE UPDATED; CI AND PHYSICAL RETEST REQUIRED.** The new implementation is not yet verified on the user's DAC. Do not resume prolonged listening tests until the slider displays 5% after starting another track and the diagnostic confirms `initialVolume=0.05` with a matching `persistedVolume=0.05`.


USB DAC unified volume control and UAC2 Feature Unit correction (2026-11-08):

- User proposes one Output volume control in Flowery, with the implementation selecting the DAC's own USB volume mechanism when the connected DAC exposes a usable Feature Unit. A separate UI control for DACs with or without physical buttons is not required.
- Source inspection found the UAC2 playback-session constructor parsed UsbFeatureUnit from the descriptor but did not pass it into UsbDacSession. The UAC1 path already passed the parsed unit. Consequently, the KTMicro device's advertised UAC2 master-channel volume control was discarded before the active session was created, causing the policy to fall back to software gain.
- Corrected the UAC2 session construction to pass the descriptor-derived Feature Unit into UsbDacSession. The existing policy then attempts GET_RANGE and GET_CUR before selecting hardware volume; if the control is not readable, software gain remains the fallback.
- Updated the DSP screen explanation to describe one slider and its automatic hardware/software backend. Added separate diagnostics for Feature Unit advertisement and successful active hardware-volume read.
- This is a source-level correction only. The descriptor advertising a Feature Unit does not prove that GET_RANGE, GET_CUR, and SET_CUR work on this device.
- **Status: IMPLEMENTED; CI AND PHYSICAL USB DAC RETEST REQUIRED.** Do not resume prolonged listening until the slider is confirmed at a safe low setting and diagnostics report `featureUnitAdvertised=true` and `hardwareVolumeActive=true`. USB transport stutter/end-of-track failure remains a separate unresolved issue.

USB DAC volume and end-of-track retest report (2026-11-08, diagnostic file recorded 21:28 +0700):

- User performed two stages. Stage 1: played the current track through to its end; playback stopped instead of advancing, audible stutter/delay remained, and the Output volume slider appeared to move by itself before settling. Stage 2: changed volume to 6%, then changed tracks; the displayed volume remained at 6%. The user confirms the value no longer resets to 20% on track change.
- The supplied diagnostic is for the first stage and contains one USB playback session for YOASOBI "02. Idol" at 96 kHz on endpoint 0x04. It ends with DECODER_EOF followed by DIRECT_USB_ERROR and PLAYBACK_ERROR: LIBUSB_ERROR_TIMEOUT, 3 isochronous transfers outstanding, 1,504 ms without progress, queue target 32,000 us, max 8 URBs. There is no successful DIRECT_USB_FINISHED event. The end-of-track failure therefore reproduces the previously observed final-drain stall; the smaller queue did not resolve it.
- The same log reports USB_VOLUME_PROFILE ... featureUnitAdvertised=true hardwareVolumeActive=false initialVolume=0.2 persistedVolume=0.2. The descriptor is recognized, but the active session still cannot read the hardware volume range/current value, so Flowery remains on software gain for this run. This is not yet a successful test of the DAC's own volume control.
- During the first session, many USB_VOLUME_REQUEST / USB_VOLUME_APPLIED pairs show the requested software volume changing repeatedly (roughly 0.08-0.17), with hardware=false. These are Flowery slider change requests, not evidence that the DAC itself is changing its volume. The log does not identify whether the repeated UI input came from touch interaction, touch noise, or another UI event; further UI event instrumentation is needed before assigning a cause.
- The stage-2 report that 6% remains after track change is PASS for software-volume persistence across track changes. It does not establish hardware volume operation because the supplied stage-1 log shows hardwareVolumeActive=false, and stage 2 has no corresponding diagnostic capture.
- Status: software-volume persistence across track changes PASS (user-confirmed); USB hardware-volume control NOT VERIFIED; USB end-of-track transport drain FAIL / INVESTIGATION REQUIRED; audible pacing stutter remains. Avoid prolonged DAC listening until the volume behavior is stable and confirmed at a safe level.


USB DAC volume-slider movement diagnostics (2026-11-08):

- User reports that during the first playback session the initial output level was too high; they manually reduced it to approximately 8–10%, after which the displayed slider value rose slightly and then moved down to approximately 4% without a deliberate adjustment. On a later test, setting 6% and changing tracks preserved 6%.
- Added source-labelled UI diagnostics in MainActivity:
  - `source=slider_on_value_change` records values delivered by the Compose volume slider callback, including the previous UI value and requested value.
  - `source=playback_broadcast` records volume values received from PlaybackService broadcasts, including the previous UI value and playback status.
  - `source=initial_system_restore`, `source=initial_usb_restore`, and `source=initial_usb_readback` identify startup restoration and asynchronous USB hardware/software volume resolution.
- These records distinguish slider callback activity from service-driven state updates and startup restoration. They do not by themselves prove physical touch input; correlate timestamps with the user's interaction and the Android input timeline if needed.
- No volume policy, gain mapping, DAC Feature Unit request, or playback transport behavior was changed.
- **Status: DIAGNOSTIC INSTRUMENTATION ADDED; CI AND A CONTROLLED PHYSICAL RETEST REQUIRED.** Keep the DAC volume at a confirmed low level. During retest, do not touch the slider after setting it to a safe value; capture the diagnostic log if the displayed value changes.


USB DAC 16-bit / 44.1 kHz routed-playback comparison (2026-11-08, diagnostic recorded 21:50 +0700):

- User reports that a FLAC 16-bit / 44.1 kHz track played smoothly through the connected DAC, advanced automatically to the next track in the folder, and continued smoothly until the user pressed Pause. This is a positive user-observed result for the tested 16/44.1 playback scenario.
- Important route qualification from the supplied diagnostic `flowery-usb-diagnostic-1794149450582.txt`: `usbPermissionGranted=false`, `usbDirectAvailable=false`, and `USB_AUTO_DETECTION result=direct_transport_unavailable fallback=android_route`. The active AAudio stream reports 44,100 Hz, device ID 1829, and `SHARED` mode. The DAC is therefore reached through Android's USB audio routing in this capture; this is not evidence that Flowery's direct libusb/AAudio-exclusive USB transport was active.
- The diagnostic was captured with playback paused at approximately 84,256 ms of a 304,106 ms track. It does not contain a direct USB transfer session, decoder EOF, or direct-transport completion events. It cannot validate direct-USB pause/resume, bit-perfect output, or the previously failing direct-USB end-of-track drain.
- The contrast with the earlier 24-bit / 96 kHz test is diagnostically useful: the 16/44.1 test succeeded on Android's shared USB route, while the earlier 24/96 test exercised Flowery's direct libusb route and stalled during final isochronous drain. These tests use different output paths, so the improvement cannot be attributed to sample rate or bit depth alone.
- **Status: ANDROID-ROUTED USB PLAYBACK AT 16/44.1 USER-REPORTED PASS; DIRECT USB TRANSPORT STILL UNRESOLVED.** A controlled comparison must confirm the route in each capture before drawing conclusions about format compatibility.

USB DAC direct-route FLAC 16-bit / 44.1 kHz retest (2026-11-08; user-reported):

- After granting Flowery USB permission, the user attempted to play a FLAC 16-bit / 44.1 kHz track through the direct USB DAC route. Playback stopped immediately, before the track duration/progress could begin advancing.
- This is a different result from the earlier 16/44.1 test, which played smoothly through Android's AAudio shared USB route while Flowery's direct USB permission was not granted.
- The new observation confirms that the failure is not limited to 24-bit / 96 kHz content. It does not yet identify whether the direct USB session fails during device/interface initialization, alternate-setting or sample-rate setup, the first PCM submission, or a subsequent transport error.
- No diagnostic capture for this exact immediate-stop attempt has been reviewed yet. Do not infer a decoder-format incompatibility or change transport behavior until the direct USB error and session events are captured.
- **Status: DIRECT USB FLAC 16/44.1 FAIL — IMMEDIATE STOP (USER-REPORTED); ROOT CAUSE UNKNOWN; DIAGNOSTIC CAPTURE REQUIRED.** Keep the USB software volume at the previously verified safe setting and avoid prolonged listening until output level and transport stability are confirmed.
Three-route USB DAC diagnostic comparison from latest CI build (2026-11-08; user-supplied files in `Waizusora/Log`):

1. `flowery-usb-diagnostic-24bit96shared.txt` (user label: `usb-diagnostic-shared24bit`):
   - Flowery USB permission was not granted; the log reports `usbDirectAvailable=false` and `fallback=android_route`.
   - AAudio reports 96,000 Hz and `SHARED` mode. The user reports audible delay/stutter.
   - This is Android-routed USB audio, not Flowery's direct libusb session. The log does not show direct USB endpoint transfers or a direct-transport error.
2. `flowery-usb-diagnostic-permission16bit44.txt`:
   - USB permission was granted and the direct path entered three times for FLAC PCM at 44,100 Hz, stereo, `PCM_INTEGER_S16_LE`, endpoint `0x04`.
   - Descriptor snapshot identifies UAC2 interface 2, alternate setting 1 as 16-bit stereo PCM, 2-byte subslot, 196-byte maximum packet, 125 us service interval, adaptive synchronization. The descriptor does not advertise a rate list; clock-source GET_RANGE requests were unavailable.
   - All three attempts fail within approximately 0.4 seconds of direct-session entry with the same error: `reason=packet_error`, transfer status COMPLETED, packet status COMPLETED, packet index 8, `actual_length=20`, `requested_length=24`, at 44,100 Hz. Only seven URBs had been submitted. Decoder configuration completed successfully at 44.1 kHz and selected integer S16 PCM before the transport error.
   - This is a repeatable short isochronous OUT packet report, not a decoder EOF or final-drain timeout. The log does not establish whether the short packet originates in host-controller/USBFS reporting, endpoint scheduling, or packet construction/timing. Do not ignore the short packet or mark the session healthy without evidence; doing so could conceal dropped PCM data.
3. `flowery-usb-diagnostic-1794150606280.txt` (user label: 24-bit / 96 kHz):
   - USB permission was granted; direct USB path entered at 96,000 Hz, stereo, endpoint `0x04`.
   - The decoder produced 623 chunks and 20,397,862 frames, then reported `DECODER_EOF`.
   - Final transport drain failed with `LIBUSB_ERROR_TIMEOUT (-7)`: three URBs outstanding and no progress for 1,503 ms. The event loop was still running (`event_loop_error=0`, `event_loop_idle_ms=0`), with the 32 ms queue target and 8-URB cap active.
   - No `DIRECT_USB_FINISHED` event was emitted; the player reports a playback error and the queue cannot advance normally. This reproduces the previously observed end-of-track stall. The file reports 96,000 Hz, not 97,000 Hz.
   
Cross-test conclusions:
- The 16/44.1 shared-route success from the earlier diagnostic does not predict direct USB behavior: that earlier run had no Flowery USB permission and used AAudio SHARED.
- The latest direct USB 16/44.1 failure occurs at the beginning of transfer and has a different signature from the 24/96 final-drain stall. The problem is therefore not confined to 24-bit/96 kHz files, and the two failures should be investigated separately.
- The 24-bit/96 kHz shared-route log also reports audible stutter by user observation, but its diagnostic cannot attribute that to libusb because direct USB was unavailable.
- Source review confirms the current native reaper treats any isochronous packet whose `actual_length` differs from its requested `length` as a fatal `packet_error`. libusb documents per-packet actual length as the amount actually transferred and notes that an isochronous transfer may have overall status COMPLETED while individual packet descriptors carry the meaningful completion status/length. The short report must be investigated rather than suppressed.
- **Status: DIRECT USB 16/44.1 FAIL — REPRODUCIBLE EARLY SHORT-PACKET ERROR; DIRECT USB 24/96 FAIL — FINAL-DRAIN STALL; ANDROID SHARED 24/96 — USER-REPORTED STUTTER.** No transport fix is claimed. Next engineering investigation: trace the first URB's per-packet requested/actual lengths, frame-phase progression, endpoint descriptor fields, and host-controller completion timing before changing packetization or failure policy.

USB DAC safety report — loud high-pitched hum on direct USB 16-bit / 44.1 kHz playback (2026-11-08; user-reported):

- During the direct USB test using a 16-bit / 44.1 kHz FLAC file, pressing Play produced a loud high-pitched humming/buzzing sound ("nging").
- Treat this as a safety-critical output fault. Direct USB playback testing is suspended pending investigation. Do not ask the user to repeat playback or increase volume.
- The prior diagnostic for this mode showed repeated isochronous packet errors on endpoint 0x04 (packet 8: actual_length=20, requested_length=24) shortly after direct USB session startup. This is a plausible transport/data-integrity concern, but the log does not prove the short-packet report caused the audible hum.
- Immediate guidance: stop playback, disconnect the USB DAC from the phone if the noise continues, and do not retry direct USB output until a code-level investigation and a safe low-level validation plan are available. Use the normal Android-routed output only if it remains clean and at a low volume.
- **Status: SAFETY HOLD — DIRECT USB OUTPUT MUST NOT BE PHYSICALLY RETESTED UNTIL INVESTIGATION AND MITIGATION.**


USB Direct code-only investigation — PCM packet phase and completion telemetry (2026-11-08):

- The KT-384/32 HIFI direct PCM profile in the reviewed capture is UAC2, interface 2 alternate setting 1, endpoint 0x04 OUT, adaptive synchronization, high-speed 125 us service interval, stereo S16 with a two-byte subslot. The selected 44.1 kHz rate is accepted by the current clock setup path even though descriptor GET_RANGE is unavailable.
- The native packet planner computes each service-interval frame count from a carried fractional phase: `frames = (phase + sample_rate * interval_us) / 1,000,000`. At 44,100 Hz and 125 us this produces a 5/6-frame cadence (20/24 bytes per stereo S16 packet); the phase is carried between submitted URBs.
- The reviewed capture reports failure on transfer sequence 3, packet index 8: requested 24 bytes, actual 20 bytes, both transfer and packet status COMPLETED. This is not enough telemetry to prove the packet planner generated an incorrect length: the current diagnostic omits each URB's packet count, starting/ending frame phase, and neighboring requested packet lengths. The exact sequence must be reconstructed from those values before changing the planner or accepting short completions.
- The source currently marks any OUT isochronous packet with `actual_length != length` as fatal, cancels queued transfers, and throws. This strict check must not be relaxed merely to hide the error. The USB isochronous OUT path has no delivery retry guarantee; host-side timing and scheduling must be examined as well as packet lengths.
- The Rust S16 packer left-aligns valid sample bits in the USB subslot and emits little-endian bytes; for a 16-bit sample in a two-byte subslot the shift is zero. Source inspection did not establish a packing defect. The capture also reports software gain 0.2, so the reported loud hum cannot be explained as an intentionally high software-volume setting from that capture alone.
- The user reports that the loud hum continued after pressing Pause and stopped only after physically disconnecting the DAC. Do not ask for or perform another physical USB Direct test. Treat this as a safety-critical fault; code/log-only investigation remains mandatory.
- Next code-only action: add bounded failure telemetry for each submitted URB (sequence, packet count, frame phase before/after planning, and requested lengths around the failing packet), then review URB scheduling flags/start-frame behavior and the UAC2 adaptive endpoint's clock relationship. No transport fix or safety clearance is claimed by this note.
- **Status: SAFETY HOLD — DIRECT USB OUTPUT MUST NOT BE PHYSICALLY RETESTED.**


USB Direct diagnostic instrumentation update (2026-11-08):

- Native PCM URB metadata now records the requested packet-length vector, packet count, fractional frame phase before and after planning, endpoint device speed, libusb transfer flags, and the host-scheduled start-frame value when a packet completion mismatch is detected.
- The thrown transport diagnostic includes the requested packet lengths surrounding the failed packet. This is diagnostic-only: packet sizing, transfer scheduling, completion validation, cancellation policy, and playback routing are unchanged.
- The new fields are intended to determine whether the reported sequence 3 / packet 8 mismatch is already present in Flowery's packet plan or appears only after host-controller submission. They also provide evidence for reviewing scheduling phase and endpoint timing.
- No DAC was connected and no physical USB test was performed for this change. Compilation/CI status must be checked independently; this change does not resolve the hum and does not lift the safety hold.
- **Status: CODE-ONLY DIAGNOSTIC CHANGE; DIRECT USB SAFETY HOLD REMAINS IN FORCE.**

### TEST 21 — Settings > Audio USB DAC controls removal

**Status: PASS — Stages 1 and 2 user-confirmed (2026-11-08)**

Build reference:
- Main-branch commit: `3390e972b72aa49e75e04b29db1cfdc54716fd89` (`3390e97`).
- GitHub Actions workflow #1491 (`42500172069`): **SUCCESS**. Debug and Release APK build steps and the listed automated checks completed successfully.

Stage 1 — Settings > Audio UI (user-confirmed, 2026-11-08): **PASS**
- USB DAC card remains visible and presents its device name/status.
- The manual **Sambungkan DAC** and **Lepas DAC** controls are absent.
- Information Audio can be opened.
- USB Diagnostics can be opened.
- The user reported the complete Stage 1 checklist as PASS.

Stage 2 — non-USB playback regression (user-confirmed, 2026-11-08): **PASS**
- Built-in Speaker playback: **PASS**.
- Pause/Resume: **PASS**.
- Next track: **PASS**.
- User reported the complete Stage 2 as PASS; no separate audio-quality detail was supplied beyond the stage result.
- The USB DAC was not part of this stage. Direct USB playback remains under the separate safety hold recorded above.

**Current result:** TEST 21 **PASS**. Both the Settings > Audio UI checks and the non-USB playback regression are user-confirmed. This result does not change or clear any USB DAC transport/safety status.


### Direct USB safety investigation — playback lifecycle and Pause dispatch (2026-11-09)

Source review at main commit `045d23d` identified a lifecycle-dispatch defect that matches the reported Pause behavior:

- `PlaybackCoordinator` transitioned its native lifecycle controller to `PLAYING` only when `FloweryPlayer` emitted a status beginning with `Playing`. Both direct USB playback routes instead emit route-specific startup statuses beginning with `USB exclusive` and do not emit a `Playing` status before entering their USB playback loops.
- The native lifecycle controller therefore remained in `PREPARING` during direct USB playback. `PlaybackCoordinator.pause()` only forwarded Pause while its state was `PLAYING`, so the Pause command could be ignored even though `PlaybackService` updated the user-facing playback state to Paused. This is a confirmed source-level explanation for the UI/audio state mismatch; it does not, by itself, prove the sole cause of the reported sustained hum.
- The first implementation treated every `USB exclusive` progress status as `EVENT_START`; this was narrowed to avoid interpreting arbitrary route-progress messages as lifecycle events. The final implementation emits an explicit `Playing · USB exclusive · preparing …` event immediately after the direct route is selected and its native playback session enters the running phase. It also rejects a stale generation before route dispatch. The existing coordinator can therefore retain its explicit `Playing` event contract while Pause/Resume become available during direct USB session preparation.
- PCM pause handling checks the paused flag at the playback-loop boundary and flushes the endpoint after the current `writePcm` call returns. Therefore, Pause may wait for an in-progress native write to finish before the flush is reached. The native writer uses a 1,500 ms no-progress watchdog and bounded in-flight URB scheduling; this source inspection does not establish a worst-case audible tail under every controller behavior.
- Repeated-start review: `PlaybackService` submits `playCurrent()` work to a single-thread executor. `FloweryPlayer.play()` signals stop on the previous worker, starts the replacement worker, and makes that worker join the previous worker for up to 4,000 ms before opening its next source/session. This is evidence against an intentional parallel-session start in the normal path, but it is not runtime proof that every teardown race is resolved.
- Native USB teardown calls `abort_in_flight()` before stopping the event thread and releasing the alternate setting/interfaces. It waits up to 2,000 ms for cancellation callbacks; if transfers cannot be reaped, it retains the session rather than freeing buffers still potentially referenced by the host controller. Kotlin retries native close up to three times. This is conservative resource-lifetime handling, but a failed reap can leave the connection/session retained and does not establish acoustic safety.
- No USB DAC was connected and no physical USB playback test was performed during this investigation. The packet short-completion at 16/44.1 kHz and final-drain stall at 24/96 kHz remain separate unresolved transport defects.

**Code changes:** `5e05743` (initial lifecycle mapping), `a221301` (investigation record), `bec3da1` (restore explicit lifecycle-message classification), and the follow-up explicit Direct USB start-event commit.

**Status: SOURCE-LEVEL PAUSE DISPATCH DEFECT ADDRESSED WITH AN EXPLICIT, GENERATION-CHECKED START EVENT; CI VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS IN FORCE.** Do not reconnect the DAC or perform Direct USB playback tests until the packet/transport and hum investigations are resolved and mitigation is reviewed.

USB transport diagnostic snapshot correction (2026-11-09; source-only investigation):

- Review of `flowery-usb-diagnostic-1794150606280.txt` showed the 24-bit/96 kHz watchdog failure recorded three URBs outstanding at the moment of timeout, but the `submitted`, `callbacks`, and event-loop counters were read only when the exception was formatted. The caller first ran `abort_in_flight()`, which can deliver cancellation callbacks and change those counters. Therefore the logged equality `submitted=26769; callbacks=26769` did not represent the state at the timeout boundary and must not be interpreted as proof that all queued transfers had completed normally.
- Updated native transport failure reporting to snapshot submitted-transfer count, completion-callback count, event-loop iteration count, event-loop error, and event-loop idle age before abort/cancellation begins. The exception now reports the captured failure-time values rather than teardown-time counters. This is diagnostic-only; packet sizing, transfer scheduling, cancellation policy, and playback behavior are unchanged.
- The 16-bit/44.1 kHz logs consistently report the first packet failure at transfer sequence 3, packet index 8: requested 24 bytes, actual 20 bytes, with transfer and packet status both COMPLETED. The captured legacy logs do not include the newer packet-length/phase fields, so the precise host-controller/endpoint cause remains unresolved.
- No DAC was connected and no physical USB playback test was performed. The direct USB safety hold remains active.

**Code change:** `04ba572` — `fix: snapshot USB transport diagnostics before teardown`.

**Status: DIAGNOSTIC TIMING CORRECTED IN SOURCE; CI #1500 SUCCESS; DIRECT USB TRANSPORT DEFECTS AND SAFETY HOLD REMAIN UNRESOLVED.**


Direct USB write cancellation review (2026-11-09; source-only investigation):

- Confirmed that `UsbDacSession.writePcm()`, `flush()`, and `close()` are synchronized on the same session monitor. The PCM playback worker calls `writePcm()` synchronously from `UsbPcmPlaybackPipeline.pollAndWrite()`.
- In native `nativeWriteIsochronous()`, the writer can block in `wait_for_progress()` while the in-flight URB window is full. That wait polls the event condition in 5 ms intervals and can continue until the 1,500 ms no-progress watchdog fires.
- `FloweryPlayer.pause()` sets the paused flag and pauses the decoder session, but it does not signal the active native USB writer. The PCM pipeline checks the paused flag only after `pollAndWrite()` returns; only then can it call `dacSession.flush()`. Consequently, the synchronized flush cannot preempt a write currently holding the session monitor.
- `FloweryPlayer.stop()` marks playback stopped, cancels the decoder session, calls `nativeStopOutput()`, and interrupts the worker thread. A thread interrupt does not safely cancel a synchronous JNI/libusb operation already in progress. The worker's finalizer closes the USB pipeline only after the write returns.
- This is a confirmed control-path limitation and a plausible explanation for delayed Pause/Stop response during transport stalls. It is not proof of the reported loud hum's cause, and it does not explain the independent 16-bit/44.1 kHz short-packet completion or 24-bit/96 kHz drain timeout.
- A safe follow-up design requires a cancellation signal that can be requested without mutating the in-flight queue from a second thread. Native queue submission, completion reaping, transfer cancellation, and buffer ownership must remain single-owner; session-handle lifetime must also be protected against a concurrent close. The cancellation result must distinguish a user-requested pause/stop from a transport failure and must define how unsent PCM and packet phase are preserved or discarded. No concurrent `nativeFlush()` call was added because it would race with the playback worker's `in_flight` vector and transfer ownership.
- No source code behavior was changed in this review. No DAC was connected and no physical USB playback test was performed. The Direct USB safety hold remains active.

**Status: PAUSE/STOP NON-PREEMPTIBLE WRITE PATH CONFIRMED BY SOURCE INSPECTION; SAFE CANCELLATION DESIGN REQUIRED BEFORE IMPLEMENTATION; DIRECT USB SAFETY HOLD REMAINS IN FORCE.**


Direct USB cancellation design refinement (2026-11-09; source-only):

- Further inspection of `nativeWriteIsochronous()` confirms it appends the full Kotlin PCM block to `pending_pcm`, then submits complete service-interval packets until no complete packet remains or the in-flight URB window is full. A call can return while submitted URBs are still outstanding; the normal window is bounded by eight URBs and a 32 ms target queue.
- When the window is full, `wait_for_progress()` blocks in 5 ms condition-variable waits and checks the 1,500 ms no-progress watchdog. The worker cannot reach the Kotlin pause branch or its synchronized `flush()` until this JNI call returns.
- A cancellation request must not call `abort_in_flight()`, `reap_completed()`, mutate `in_flight`, or clear PCM buffers from the requesting thread. Those operations must remain on the writer thread because the callback and in-flight owner lifetimes are tied to that queue.
- Candidate safe split: a cross-thread request only publishes an atomic control intent and wakes the condition variable; the writer thread observes it, performs any required transfer cancellation/reaping itself, then returns a typed result. Session close must wait until both the writer and cancellation requester have released their native-handle leases. A Kotlin-side lifecycle read/write lock (read lease for write and request, exclusive lease for close) or a native shared-handle registry could provide that lifetime guarantee; the raw pointer must never be dereferenced concurrently with close.
- Pause and Stop have different data semantics. Stop may discard pending PCM and abort outstanding URBs. Pause should preserve the producer/decoder position and avoid replaying or skipping frames; simply cancelling a block after some of its URBs were submitted is not sufficient. A pause design must define whether it drains the already-submitted bounded window before returning, and how pending unsubmitted PCM is retained.
- No code behavior was changed in this refinement. CI #1501 for the preceding documentation commit completed successfully. No DAC was connected and no physical USB test was performed; Direct USB Safety Hold remains active.

**Status: SINGLE-WRITER CANCELLATION ARCHITECTURE DEFINED AT DESIGN LEVEL; IMPLEMENTATION NOT STARTED; DIRECT USB SAFETY HOLD REMAINS IN FORCE.**

Direct USB pause/cancellation design review (2026-11-09; source-only investigation):

- Confirmed that the native PCM writer appends each complete Kotlin PCM block to `pending_pcm`, then advances `pending_offset` as service-interval packets are submitted. The JNI write returns after the block has been staged/submitted into the bounded window; it does not wait for every submitted URB to complete.
- The current pause path cannot interrupt a blocked JNI write because Kotlin serializes `writePcm()`, `flush()`, and `close()` on the same monitor. The playback loop only observes the paused flag after the current JNI write returns.
- A pause implementation must not simply call the existing native flush operation: flush cancels in-flight URBs and clears both pending PCM and packet-phase state. Because the producer has already advanced past the polled block, clearing that state during pause would discard audio and can cause a discontinuity on resume.
- A lossless pause design should publish a thread-safe pause request without dereferencing a session that may concurrently be closed; wake the native writer's condition variable; stop submitting additional URBs; allow the already-submitted bounded window to complete; and return while retaining the unsent suffix of `pending_pcm` and its frame-phase state. Resume should clear the pause request before the writer submits more packets.
- Stop remains a distinct operation: it may discard pending audio, but must request cancellation through a lifetime-safe session handle and let the writer-owning thread reap or abort URBs. Session close must wait until both the writer and any cancellation request have released their session leases before freeing the native session.
- The current JNI API exposes the native session address as a raw pointer and each JNI entry point casts the supplied handle directly. Adding an unsynchronized cross-thread cancel JNI call against that raw pointer would introduce a use-after-free race with `nativeClose()`. A registry-backed, reference-counted native handle (or an equivalent lifetime gate) is required before concurrent cancellation can be introduced safely.
- No code or transport behavior was changed in this review. This is an implementation constraint derived from source inspection, not a verified fix for the reported high-pitched hum. Direct USB Safety Hold remains active; no DAC connection or physical retest was performed.


Direct USB native session-handle registry implementation (2026-11-09; source change):

- Replaced JNI-visible native pointer values with monotonically allocated opaque session IDs stored in a native registry.
- Added per-session shared lifecycle locking. PCM write, encoded write, drain, and flush JNI entry points acquire a registry lease before dereferencing a session.
- Native close prevents new leases, waits for active operations to release their shared leases, then tears down libusb resources and deletes the session. If transfer teardown cannot be confirmed, the handle remains registered and can be retried.
- Encoded-session setup resolves the opaque handle through the registry and closes the registered session on post-open validation failures.
- This change establishes native handle lifetime protection only. It does not add cross-thread cancellation, alter packet scheduling, fix the observed packet-length mismatch, or establish that Direct USB output is safe. Direct USB Safety Hold remains active; no DAC connection or physical retest was performed.
- CI and native compilation are pending. Do not treat source-level changes as validated until CI completes.

**Status: REGISTRY-BACKED JNI SESSION HANDLES IMPLEMENTED; VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS IN FORCE.**


Direct USB PCM cancellation implementation — Stage 2 (2026-11-09; source change):

- Added registry-leased JNI control requests for Pause, Resume, and Stop. The request path only updates an atomic session control state and notifies the event condition variable; it does not access or mutate the in-flight URB queue.
- The PCM writer remains the sole owner of packet submission, completion reaping, URB cancellation, and pending-PCM mutation.
- Pause stops further URB submissions, waits for the already-submitted bounded window to complete, and returns with the unsent PCM suffix and fractional packet phase retained. Resume clears the pause request; the next PCM write continues from the retained suffix before appending later data.
- Stop wakes a blocked PCM writer. The writer thread cancels/reaps its own in-flight transfers, clears pending PCM and packet phase, and returns a distinct control result rather than reporting a transport exception. Session close still waits for the JNI lease before releasing native resources.
- The Kotlin PCM pipeline no longer calls destructive nativeFlush() for Pause. nativeFlush() remains reserved for seek/epoch changes. FloweryPlayer forwards Pause/Resume/Stop requests through the active PCM USB session.
- Encoded USB transport was intentionally not modified in this stage.
- Automated validation is pending. No DAC was connected and no physical playback test was performed. This change does not fix the independent 16-bit/44.1 kHz short-packet issue, the 24-bit/96 kHz drain timeout, or establish acoustic safety. Direct USB Safety Hold remains active.

**Status: PCM PAUSE/RESUME/STOP REQUEST PATH IMPLEMENTED; CI VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS IN FORCE.**


Stage 2 follow-up correction (2026-11-09):

- Restored the Kotlin JNI declaration of nativeClose to its Boolean return type; the initial Stage 2 edit had accidentally omitted the explicit return type.
- Extended Stop handling into the PCM nativeDrain path so a Stop request also wakes and cancels a writer that is finishing the final drain, rather than only interrupting nativeWriteIsochronous.
- The stop result is distinct from a transport failure; transfer-reap failure still returns an error and retains native resources according to the existing teardown policy.
- CI validation is pending. No DAC was connected or physically tested. Direct USB Safety Hold remains active.


Stage 2 integration correction (2026-11-09):

- Re-applied the PCM pipeline and FloweryPlayer call-site integration on top of the follow-up native drain commit. Pause no longer flushes the PCM transport; the player forwards non-blocking Pause/Resume/Stop requests to the active PCM DAC session.
- The PCM pipeline recognizes the native Stop control result and exits its current poll/write iteration without treating it as a partial-write transport failure.
- This correction preserves both native drain cancellation and Kotlin control dispatch in the final branch tree.
- CI validation is pending. No physical USB DAC test was performed; Direct USB Safety Hold remains active.


Native USB session-control unit tests (2026-11-09; source change):

- Extracted the production cross-thread control state and session lifetime gate into the header-only usb_session_control.h, shared by the JNI implementation and a host-native C++ test executable.
- Added host-native tests for a writer blocked on its condition variable receiving Pause without submitting more work, Resume waking a paused writer, repeated Stop requests remaining idempotent and terminal, and Close waiting for an active lease while rejecting new leases.
- The production JNI session now uses the same ControlState and SessionLifetimeGate primitives exercised by these tests. The native writer continues to own all libusb transfer queue operations.
- Added the C++ test executable to GitHub Actions with strict compiler warnings (-Wall -Wextra -Werror).
- These tests cover host-native control/lifetime primitives. They do not emulate a libusb controller, validate acoustic output, or resolve the observed packet-size and drain defects. No DAC was connected or physically tested; Direct USB Safety Hold remains active.

**Status: FOUR HOST-NATIVE CONTROL/LIFETIME TESTS ADDED; CI VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS ACTIVE.**


Native session close-gate test hardening (2026-11-09):

- The first local execution exposed a close-time contention issue: a new lease attempt could block acquiring the shared mutex while Close was waiting for an existing lease to drain.
- Updated SessionLifetimeGate::acquire() to reject immediately when the closing flag is already set, while retaining the post-lock check to close the race with a concurrent begin_close().
- Recompiled and ran the four host-native tests locally with C++17, pthreads, and -Wall -Wextra -Werror; all four scenarios passed.
- GitHub Actions validation for this correction is pending. No USB DAC was connected or physically tested; Direct USB Safety Hold remains active.


Playback completion cleanup crash analysis and native command fix (2026-11-09):

- The supplied `logcat.txt` records a fatal `IllegalStateException` from `nativePlaybackSessionCommand` during `FloweryPlayer.stop()`, called by `PlaybackService.handleCompletion()` immediately after `TRACK_COMPLETION`.
- Source inspection confirms the playback worker can move the native session to `Completed` (phase 5) before the service performs ordinary queue cleanup. That cleanup then sends `Cancelled` (command 6), which was rejected as an invalid terminal transition and escaped on the main thread.
- Updated the native command handler to treat `Cancelled` issued after `Completed` as a successful no-op. The session remains `Completed`; other invalid terminal transitions remain rejected.
- Added a Rust unit test for the completed-then-stop case and preservation of the terminal phase.
- This is a source-level correction for the observed crash. CI validation and an updated APK are still required before considering the crash fixed in a build. No DAC connection or physical USB playback test was performed; Direct USB Safety Hold remains active.

**Status: COMPLETION-CLEANUP CRASH FIX IMPLEMENTED IN SOURCE; CI VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS ACTIVE.**


CI #1511 compile correction (2026-11-09):

- CI confirmed all Rust unit tests (127), host-native USB session-control tests, and Kotlin/native JNI declaration checks passed. The Android arm64-v8a C++ build then failed because encoded-session setup attempted to call `unlock()` on the `SessionLease::lock` wrapper, while the underlying `std::shared_lock` is intentionally private inside `SessionLifetimeGate::Lease`.
- Added an explicit `Lease::release()` operation that releases its shared lifecycle lock and invalidates the lease, then updated both encoded-session setup failure paths to use it before closing the registered session. This preserves the required lock-release-before-close ordering without exposing the internal mutex lock.
- CI must be rerun to validate the correction. No USB DAC was connected or physically tested; Direct USB Safety Hold remains active.

**Status: CI #1511 FAILURE DIAGNOSED; LEASE RELEASE API CORRECTION COMMITTED; VALIDATION PENDING.**

### TEST 22 — Playback completion and Stop cleanup regression (2026-11-09)

**Status: PASS — user-confirmed on the APK produced by CI #1513**

User-confirmed observations using the built-in Speaker or AUX output, with no USB DAC connected:

- Track completed and the next track started automatically: **PASS**.
- Manual Stop during playback: **PASS**.
- Play after Stop: **PASS**.
- Application crash during these checks: **NO**.

This retest covers the completion-cleanup path associated with the previously observed invalid native playback-session transition. It confirms the reported scenarios on the tested build; it does not establish broader playback stability or validate USB Direct output.

**Build:** CI #1513 — https://github.com/Waizusora/Flowery/actions/runs/42546444838  
**Safety note:** Direct USB DAC playback was not tested. The Direct USB Safety Hold remains active.

Native session-control test synchronization hardening (2026-11-09; source change):

- Tightened the host-native Pause and Resume tests so the requesting thread must acquire the writer's mutex before issuing the control request. Since the writer holds that mutex until entering its condition-variable wait, this handshake proves the writer has actually entered the wait and released the mutex; the earlier promise-only handshake could signal readiness immediately before the wait began.
- The tests continue to exercise the production ControlState and SessionLifetimeGate primitives. They remain host-native synchronization tests, not libusb-controller or physical-output tests.
- Source audit also confirmed that the current native Stop handling is implemented in the PCM write/drain paths; encoded transport has not yet been brought under the same Pause/Stop control contract. This is retained as a follow-up scope item rather than being represented as covered by the PCM tests.
- CI validation is pending. No USB DAC was connected or physically tested. Direct USB Safety Hold remains active.

**Status: PAUSE/RESUME TEST HANDSHAKE MADE DETERMINISTIC AT THE CONDITION-VARIABLE BOUNDARY; CI VALIDATION PENDING; DIRECT USB SAFETY HOLD REMAINS ACTIVE.**

USB session contract documentation correction (2026-11-09):

- Corrected the UsbDacSession API documentation: Pause is a non-destructive control request and does not call native flush; flush is reserved for playback-position changes such as seek. This aligns the KDoc with the current PCM implementation and avoids documenting a destructive Pause operation.
- This is a documentation-only correction. It does not extend Pause/Stop handling to encoded transport or change native runtime behavior. Direct USB Safety Hold remains active.

Encoded USB lifecycle control implementation (2026-11-09; source change):

- Extended the native encoded USB writer to observe the session control state on the writer-owning JNI thread. UAC1 Type-II encoded frames are retained while waiting for Pause/Resume and are not split; Pause waits for the current frame's submitted transfer to complete before blocking for Resume.
- Extended IEC 61937 Type-III packet scheduling to honor Pause at URB boundaries. The writer drains the already-submitted bounded window, retains the unsubmitted encoded suffix and encoded-rate/feedback phase, and waits for Resume before submitting further URBs.
- Stop handling now aborts and reaps in-flight encoded transfers on the writer thread, then clears staged encoded bytes and encoded pacing state. A stop during an encoded write is returned as a normal stopped write result so a packet already removed from the Rust queue is not misreported as a transport error.
- Encoded nativeDrain now checks Stop and Pause. Stop aborts and clears staged data; Pause drains the current submitted window and returns without submitting the remaining staged tail. Kotlin treats the native stopped result as normal cancellation.
- Added non-blocking Pause/Resume/Stop forwarding from FloweryPlayer to the encoded USB session. Removed the encoded play loop's destructive flush-on-pause behavior; flush remains for seek/position changes.
- The JNI writer remains the sole owner of in-flight transfer queues, encoded offsets, and cancellation/reaping. The control request path only publishes the atomic request and wakes the condition variable.
- Validation is pending on CI #1524. The existing host-native tests exercise the shared ControlState and session lifetime primitives; they do not yet emulate encoded frame scheduling or a libusb controller. No USB DAC was connected and no physical playback test was performed. Direct USB Safety Hold remains active.

**Status: ENCODED USB PAUSE/RESUME/STOP PATH IMPLEMENTED IN SOURCE; CI VALIDATION PENDING; ENCODED CONTROLLER-LEVEL TEST COVERAGE AND DIRECT USB SAFETY REVIEW STILL REQUIRED.**

Encoded USB boundary-policy tests (2026-11-09; source change):

- Extracted the encoded writer's control decision into the shared native encoded_control_action policy used by the production encoded writer and encoded drain: Run permits submission; Pause drains outstanding URBs and then waits for Resume; Stop takes priority and requests abort. The final encoded submit check and control-request publication now share event_mutex, serializing Pause/Stop against libusb submission so a request cannot slip between the check and submit.
- Added host-native scenarios for UAC1 Type-II frame completion before Pause, IEC 61937 Type-III URB-window drain while preserving the unsent cursor and rate/feedback phase, Stop priority over Pause, Close waiting for an active encoded-writer lease after Stop, and serialization of Pause publication with encoded submission.
- Native Close now publishes terminal Stop under the same serialized control path before it begins waiting for active JNI leases. This wakes a writer blocked at Pause and prevents Close from waiting indefinitely for a lease that only Resume/Stop could release.
- These tests exercise the production control-decision policy and lifetime gate with deterministic in-memory transfer counts. They do not invoke libusb, emulate USB controller completion/error behavior, or prove endpoint electrical/audio safety.
- CI validation is pending for the current main revision. Direct USB remains under Safety Hold; no physical DAC test was performed.

**Status: ENCODED CONTROL POLICY TESTS ADDED; CI VALIDATION PENDING; LIBUSB CONTROLLER-LEVEL COVERAGE AND DIRECT USB SAFETY REVIEW STILL REQUIRED.**


libusb event-loop failure handling hardening (2026-11-09; source change):

- Source audit found that a fatal return from `libusb_handle_events_timeout_completed()` ended the event thread but left `event_thread_running=true`. A feedback completion callback could therefore still satisfy the old resubmission guard and queue another feedback transfer after the dispatcher had terminated.
- The event thread now publishes `event_thread_running=false` on every exit and marks feedback stopped on fatal libusb errors. The feedback callback uses a shared, host-testable predicate that also requires `event_loop_error==LIBUSB_SUCCESS` before resubmitting.
- Added a host-native test for the feedback-resubmission guard across normal operation, teardown, stopped dispatcher, and fatal event-loop states. This validates the policy predicate only; it does not emulate libusb callback delivery or prove that outstanding transfers can always be reaped after a fatal dispatcher error.
- A fatal event-loop error can still prevent cancellation callbacks from being serviced. The existing bounded teardown therefore retains the session and transfer buffers rather than freeing memory still potentially owned by libusb/the host controller. Recovery/reaping after dispatcher failure remains an explicit unresolved audit item.
- CI validation is pending. No DAC was connected or physically tested. Direct USB Safety Hold remains active.

**Status: FATAL EVENT-LOOP FEEDBACK RESUBMISSION GUARD HARDENED IN SOURCE; CI VALIDATION PENDING; POST-FAILURE TRANSFER REAP RECOVERY STILL UNDER REVIEW; DIRECT USB SAFETY HOLD REMAINS ACTIVE.**


## 2026-11-09 — Bounded libusb teardown event pumping after dispatcher failure

- Added a teardown-only event-pump fallback for the case where the dedicated libusb event thread has exited after a fatal event-handler error.
- The fallback first verifies that the dispatcher is no longer running, joins the thread, and only then calls libusb event handling from the teardown caller. This prevents concurrent event dispatchers from owning the same context.
- Pending audio transfers are cancelled and reaped through their normal completion callbacks. The feedback transfer is also allowed to complete its cancellation callback; feedback resubmission remains disabled after the recorded event-loop error.
- The fallback is bounded by the existing teardown timeout. If libusb does not return all transfer ownership, teardown still fails closed and retains the session, transfer objects, buffers, and callback contexts. It does not force-free possibly active transfers.
- Added a host-native policy test for the exclusive-event-ownership precondition. The native test suite now reports 11 policy scenarios.
- Scope limitation: this host test validates the eligibility predicate only. It does not simulate libusb callbacks, kernel/controller ownership, or prove that every platform will recover after a fatal event-handler error. CI verification is pending.
- Direct USB playback remains under the existing safety hold. No physical DAC test was performed or requested.


## 2026-11-09 — Callback-dispatch completion barrier for USB transfer reclamation

- Tightened audio-transfer reclamation: a callback now publishes its completion iteration, and the writer may free the transfer only after the corresponding `libusb_handle_events_timeout_completed()` call has returned and the event-loop iteration counter has advanced.
- Applied the same barrier to the feedback transfer. Its in-flight state is cleared only after the callback has decided not to resubmit and has published the required dispatch iteration. Teardown waits for both conditions before freeing the feedback transfer.
- The dedicated event thread and the bounded failed-loop teardown pump both advance the same dispatch counter after each libusb event-handler call.
- Added a host-native policy test covering incomplete callbacks, completion before dispatch return, completion after dispatch return, and missing callback barriers. The native control suite now reports 12 scenarios.
- This narrows the callback/teardown race window and prevents reclamation based solely on a completion flag published from inside a callback. The test validates the reclamation predicate, not libusb's real callback delivery or USB controller behavior.
- CI verification is pending. Direct USB playback remains under the existing safety hold; no physical DAC test was performed.


## 2026-11-09 — Initial asynchronous feedback submission ownership ordering

- A follow-up lifecycle audit found a race during session initialization: the dedicated event thread is already running when the initial feedback transfer is submitted. The callback could run and clear `feedback_in_flight` after a failed resubmission before the opening thread performed its old post-submit `true` store. That late store could leave a completed transfer falsely marked in flight and make teardown wait until timeout.
- The initial submission path now publishes `feedback_in_flight=true` before calling `libusb_submit_transfer()`, then rolls it back if submission fails. It no longer writes the in-flight flag after a successful submit, so it cannot overwrite a callback's state transition.
- Added a host-native ordering scenario to the USB session control test suite. The suite now reports 13 scenarios. This test models the state ordering; it does not invoke libusb or reproduce scheduler/controller timing.
- CI validation is pending. Direct USB remains under the existing Safety Hold; no physical DAC test was performed.


## 2026-11-09 — PCM submission serialized with Pause and Stop

- The PCM writer previously checked the control state before calling `libusb_submit_transfer()` without holding the same mutex used by Pause/Stop publication. A control request could therefore land after the check but before submission, allowing an additional URB to be queued after Pause or Stop had been requested.
- Added a PCM submission gate that holds `event_mutex` across the final Run-state check and the libusb submit call. Pause/Stop publication uses the same mutex, making the decision and submission linearizable. The encoded path now uses the same shared submission predicate.
- Applied the gate to normal PCM writes and the final staged PCM packet submitted by drain. If drain observes Pause before the tail submission, it drains only already-submitted URBs and preserves the unsent PCM suffix and phase for Resume. Stop retains its existing abort-and-clear behavior.
- Added a host-native concurrency scenario for PCM submission versus Pause publication. The USB session control suite now reports 14 scenarios. The host test exercises the shared policy/lock ordering; it does not call libusb or model hardware scheduling.
- CI validation is pending. Direct USB remains under the existing Safety Hold; no physical DAC test was performed.


## 2026-11-09 — Session lease move-state hardening

- The native session lifetime lease used default move operations. Moving its shared lock transferred mutex ownership, but the source object's separate validity flag remained set; the JNI SessionLease wrapper also retained its raw session pointer after a move.
- Added explicit move construction and move assignment that transfer ownership and invalidate the source lease. The JNI wrapper now also clears the moved-from session pointer. Boolean lease validity additionally verifies that the underlying shared lock is still owned.
- Added a host-native regression scenario covering move construction and move assignment, including source invalidation and destination validity. The USB session-control suite now contains 15 scenarios.
- This is lifecycle-state correctness hardening. The host test exercises the production lease primitive but does not emulate libusb callbacks, a USB controller, or physical audio output. CI validation is required; Direct USB Safety Hold remains active and no physical DAC test was performed.


### 2026-11-09 — Encoded USB Stop result contract

Source audit found that the native encoded USB writer returned the accepted payload byte count at several Stop checkpoints, even after the writer had aborted in-flight transfers and discarded the staged encoded packet. This differed from the PCM writer contract, which reports the dedicated cancellation result `-2`. The Rust JNI packet scheduler consequently treated an intentional Stop as a short/failed write and raised an exception.

Changes committed to `main`:
- The UAC1 Type-II and IEC 61937 Type-III native encoded writer paths now return `-2` when Stop cancels the current packet. Successful writes continue to return the exact accepted byte count.
- The Rust JNI scheduler distinguishes accepted, stopped, and rejected write results. A stopped result is returned to Kotlin as the explicit scheduler sentinel `-2`; it is not converted into a transport exception or a successful packet count.
- Kotlin shares the encoded Stop sentinel across the transport session and playback pipeline. The pipeline records a local Stop request, stops pumping, exits its playback loop without starting a natural-end drain, and treats Stop during the final producer drain as normal cancellation.
- A Rust unit test covers successful full writes, the Stop sentinel, negative failures, and short writes.

Validation scope:
- This is a source-level contract correction plus a host-side Rust policy test. It does not invoke libusb, emulate USB controller behavior, establish transfer cancellation completion, or demonstrate electrical/audio safety.
- CI is required to validate compilation and the registered test suites. No physical DAC test is authorized or requested. Direct USB remains on Safety Hold pending independent root-cause and fail-safe review.


### 2026-11-09 — Retryable encoded USB pipeline teardown

- The encoded pipeline previously marked itself closed before calling `UsbEncodedDacSession.close()`. If native teardown could not confirm that every libusb transfer had been reaped, the session intentionally remained open for a safe retry, but the pipeline's early closed flag caused subsequent `close()` calls to return without retrying.
- Added an explicit lifecycle gate. Playback and control requests are rejected as soon as teardown starts, while repeated `close()` calls remain permitted until native session teardown succeeds. The gate becomes terminal only after producer, queue, and USB session cleanup complete.
- Producer and queue cleanup operations are safe to repeat through their existing stale-handle/no-op behavior; the session retains its handle and connection when native close has not succeeded.
- Added Kotlin unit tests for retry after an incomplete close attempt, rejection of playback/control while closing, and idempotent successful close.
- Validation scope: the tests cover the lifecycle gate, not actual libusb cancellation or controller ownership. CI validation is required. Direct USB remains under Safety Hold; no physical DAC test was performed.


### 2026-11-09 — Retain and retry failed encoded USB teardown at the playback owner

- A further ownership audit found that the encoded pipeline's retryable close state alone was insufficient: `FloweryPlayer.playUsbEncodedDirect()` cleared its active pipeline reference before closing it. If close exhausted its bounded native retries, the pipeline became unreachable to the playback owner.
- The player now retains the active pipeline reference when close fails and retries it from `stopAndJoin()` after the playback worker has terminated. The reference is cleared only after a successful close.
- `PlaybackCoordinator` now checks the teardown result. USB release and controller shutdown remain pending and retry at a bounded interval while native teardown is unconfirmed; the playback controller is not destroyed and completion callbacks are not issued prematurely.
- This is fail-closed resource ownership behavior: it may intentionally keep cleanup pending if libusb cannot confirm transfer reclamation. It does not force-free transfers or claim that hardware has stopped producing audio.
- Validation scope: source-level lifecycle behavior and Kotlin tests for the retryable state gate; CI is required. No libusb controller simulation or physical DAC test was performed. Direct USB Safety Hold remains active.


### 2026-11-09 — CI #1586 Kotlin compilation correction

- CI #1586 passed native unit tests, USB session-control tests, and Kotlin/JNI declaration verification, then failed at Android unit-test compilation.
- The compiler identified three stale references to the removed `closed` field in `UsbEncodedPlaybackPipeline.seekTo()`, `drainTransport()`, and `drain()`.
- Replaced all three checks with the new lifecycle gate's `checkOpen()` method so every playback operation consistently rejects calls after teardown begins.
- CI #1587 was triggered for the correction. No hardware validation was performed; Direct USB Safety Hold remains active.


### 2026-11-09 — Step-aware encoded USB teardown retries

- A follow-up audit of retryable pipeline teardown found that a failed final USB-session close caused the pipeline to repeat producer stop/destruction and queue close/destruction on every retry. Those operations were expected to tolerate stale handles, but repeating destructive cleanup was unnecessary and made ownership harder to reason about.
- EncodedPipelineLifecycle now records each successful teardown step independently. A step that throws remains eligible for retry; steps that returned successfully are skipped on subsequent close attempts.
- UsbEncodedPlaybackPipeline.close() now performs producer stop, producer destruction, queue close, queue destruction, and USB-session close through the step tracker. The USB session remains the final retryable step because its native handle and Android connection must remain owned until libusb confirms transfer reclamation.
- Added Kotlin unit tests verifying that completed cleanup steps are not repeated and that a failed step is retried until it succeeds.
- Validation scope: deterministic lifecycle unit tests and CI compilation/test execution. This does not simulate libusb or prove controller-side transfer ownership. Direct USB Safety Hold remains active; no physical DAC test was performed.

### 2026-11-09 — Retain encoded USB sessions after failed open-time cleanup

- The teardown audit found an ownership gap in the encoded pipeline's construction-failure path: if the USB session had opened but its close could not confirm transfer reclamation, the local session reference could be lost when `open()` propagated the failure. The native registry intentionally retained the native session, but Kotlin did not retain the Android `UsbDeviceConnection` owner for a later retry.
- `UsbEncodedDacSession` now keeps failed-close instances in an identity-based process-lifetime pending-close registry. The session remains strongly referenced, including its Android connection, until both native close and connection close succeed.
- Pending closes are retried during engine teardown and before opening another encoded USB transport. A new encoded USB session is rejected while any prior retained session remains unclosed. Interrupted close attempts are retained as well. Failed retries remain retained and are reported as incomplete teardown; they are not treated as successful release.
- This change addresses Kotlin-side ownership retention only. It does not prove libusb/controller behavior, force-free resources, or establish physical audio safety. CI validation is required. Direct USB Safety Hold remains active; no physical DAC test was performed.


### 2026-11-09 — Separate native and Android encoded-session close state

- A follow-up review found that UsbEncodedDacSession.close() could successfully destroy the native handle, then fail while closing the Android UsbDeviceConnection. The old retry path left the session marked open and could call nativeClose() again with a stale native handle.
- Native teardown and Android connection release now have distinct completion state. A confirmed native close is recorded immediately; retries then skip all native calls and retry only Android connection release. Control requests are also rejected after native ownership ends, preventing requests against a stale handle.
- The pending-session registry continues to retain the Kotlin session and Android connection until the full close sequence succeeds. open() also retries pending sessions and fails closed if any remain unresolved.
- Validation scope: source-level state/ownership correction plus CI compilation and unit tests. This does not simulate libusb callback behavior or establish physical audio safety. Direct USB Safety Hold remains active; no physical DAC test was performed.
- A subsequent retry-path check added a separate `connectionClosed` state. If Android connection release itself throws, later retries skip both the already-completed native close and any connection close that has already returned successfully; the session is removed from the pending registry only after both ownership steps are confirmed.

### 2026-11-09 — Serialize encoded producer worker lifecycle and contain panics

- The Rust worker audit found that worker replacement was split across registry removal/join and later insertion. Concurrent start calls could interleave those operations and overwrite a live worker entry, losing the handle needed to stop and join that worker.
- Worker stop now retains the registry lock through removal and join. Worker start serializes prior-worker stop/join, thread creation, and registry insertion under the same registry lock. The worker body does not acquire that registry lock, so joining while holding it does not create a worker-to-registry lock cycle.
- The producer thread body is now guarded by `catch_unwind`. A panic is contained at the worker boundary and publishes terminal status `-3`, rather than silently leaving the worker status at its initial value. The worker is still joined during teardown before its producer is destroyed.
- Validation scope: source-level synchronization and panic-boundary hardening; CI native/Kotlin/Android tests required. This does not simulate Android scheduling, libusb callbacks, or USB controller behavior. Direct USB Safety Hold remains active; no physical DAC test was performed.

### 2026-11-09 — Serialize encoded producer seek and destruction

- A follow-up lifecycle audit found that `nativeSeekUsbEncodedSession` stopped and joined the producer worker, released the worker-registry lock, performed the flush/seek, and later inserted a replacement worker. Concurrent start or destroy calls could interleave with that gap and leave a worker registered for a removed producer or replace a worker without joining it.
- Encoded worker creation is now shared through one panic-contained spawn helper. Start validates the producer and performs replacement/spawn/insertion while holding the worker-registry lock. Seek holds the same lock for the complete stop/flush/epoch/decoder-seek/restart transition. Destroy holds it through worker join and producer removal.
- Seek now flushes the transport before advancing the playback epoch. If transfer reclamation fails, the decoder epoch is not advanced and no replacement worker is started.
- Lock-order review: producer workers resolve producer handles but never acquire the worker-registry lock. The serialized lifecycle therefore does not introduce a worker-to-registry join cycle. JNI flush uses the existing native USB session path and does not call back into the Rust worker registry.
- Validation scope: source-level lifecycle and lock-order correction; CI native, USB-control, Kotlin/JNI, and Android checks required. No test here simulates Android scheduling, libusb callback delivery, or USB controller behavior. Direct USB Safety Hold remains active; no physical DAC test was performed.

### 2026-11-09 — Remove the legacy encoded seek bypass

- The producer API audit found an obsolete JNI seek entry point that changed the decoder and queue directly without stopping the registered producer worker, flushing the USB transport, or using the session epoch transition. That path could leave already-submitted transport data from the previous position in flight and did not provide the same lifecycle guarantees as `nativeSeekUsbEncodedSession`.
- Repository-wide code search found no callers beyond the Kotlin external declaration and its native implementation. The unused declaration and JNI entry point have been removed. Encoded playback now exposes the transport-aware session seek path as its only seek operation.
- Validation scope: repository code search, source/API removal, and CI JNI/native/Android checks. This does not simulate libusb callback delivery or USB controller behavior. Direct USB Safety Hold remains active; no physical DAC test was performed.

### 2026-11-09 — Stop encoded producer when its queue is closed

- The producer lifecycle audit found that explicitly closing an encoded packet queue while its producer worker was still registered could leave the worker retrying the same pending packet indefinitely. The queue correctly rejected new offers, but the producer treated every rejection as temporary backpressure.
- Queue closure now discards buffered packets and resets byte accounting. The producer checks the queue's terminal closed state and exits through its cancellation status rather than spinning against a queue that cannot accept data.
- Added native tests for the closed-state signal, buffered-packet discard, zeroed capacity accounting, waiter wake-up, and rejection of new packets.
- Registry removal still preserves already-cloned Arc references; this change applies to explicit queue closure and does not invalidate memory that an in-flight native caller still owns.
- Validation scope: native unit tests and CI. This does not simulate Android scheduling, libusb callback delivery, USB controller behavior, or electrical/audio safety. Direct USB Safety Hold remains active; no physical DAC test was performed.


### 2026-11-09 — Commit encoded seek epoch after decoder success

- A seek-order review found that the native encoded seek path advanced the shared playback epoch immediately after the USB transport flush, before calling the decoder's stream initialization and seek operations. If either decoder operation failed, the old worker had already been stopped and the epoch had changed even though the decoder had not committed to the requested position.
- Reordered the transition: stop and join the producer worker, flush the USB transport, initialize and seek the decoder, then advance the session epoch and clear pending/queued packets. The replacement worker is spawned only after this sequence succeeds.
- A decoder initialization or seek error now leaves the session epoch unchanged. The producer worker remains stopped and the JNI call reports failure; the playback owner must treat this as a failed seek and proceed through normal teardown rather than assuming playback resumed.
- Validation scope: source-level transaction ordering plus CI native/Kotlin/Android checks. This does not simulate libusb callback delivery, USB controller behavior, or physical output. Direct USB Safety Hold remains active; no physical DAC test was performed.


### 2026-11-09 — Make encoded producer terminal-state handling explicit

- The worker lifecycle review confirmed that end-of-stream must remain distinct from cancellation, worker failure, and a contained panic. The transport scheduler must continue draining packets already queued at EOF, while rejecting a stopped or failed worker.
- Replaced numeric worker-state literals with named constants and centralized the transport-availability mapping. Running and EOF permit queue draining; cancellation produces the cancellation error; panic, worker failure, and unknown states fail closed as unavailable.
- Added a native unit test covering running, EOF, cancellation, ordinary failure, panic, and unknown status values. This verifies the scheduler's state policy; it does not simulate thread scheduling, libusb callbacks, USB controller behavior, or physical output.
- CI validation is required. Direct USB Safety Hold remains active; no physical DAC test was performed.


### 2026-11-09 — Prevent encoded packet sequence wraparound

- Replaced the producer's wrapping sequence increment with checked arithmetic. Sequence exhaustion now terminates the producer with an explicit error rather than allowing a packet sequence to wrap to zero and potentially become ambiguous.
- Reused the named stopped-worker status constant for the worker's explicit stop path; no raw status literal remains in that lifecycle transition.
- Added a native boundary test for normal increment, the final valid increment, and exhaustion at the maximum sequence value.
- Validation is source/unit-test/CI only. This does not simulate USB controller behavior or prove physical output safety; Direct USB Safety Hold remains active.


### 2026-11-09 — Serialize encoded producer terminal failures with packet submission

- The encoded transport audit found a race in which the JNI scheduler sampled the producer status once, then continued draining queued packets. The producer could subsequently enter a failed or panicked terminal state while the scheduler still submitted packets from that queue.
- Added a per-worker submission gate shared by the producer worker and JNI packet scheduler. Producer progress and terminal-state publication are serialized against the complete packet submission call; the scheduler rechecks worker status under the same gate before dequeuing each packet. Failed, panicked, stopped, and unknown states therefore reject remaining queued packets rather than allowing a previously sampled RUNNING state to authorize later writes.
- EOF remains a distinct drainable state so packets successfully queued before normal end-of-stream can complete. The gate is not a hardware-level emergency stop and does not establish that already-submitted USB transfers have been cancelled or reaped.
- Added a native concurrency regression test proving terminal failure publication cannot pass an active submission gate and is observable as a terminal transport error after the gate is released.
- Validation scope: native synchronization policy and regression test, followed by CI. No physical DAC test was performed. Direct USB Safety Hold remains active; no claim of hardware safety or resolution of the prior high-pitched noise is made.
