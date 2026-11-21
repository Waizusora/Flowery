#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.waizusora.flowery"
SERIAL="${ANDROID_SERIAL:-}"
RUN_DOZE=0
RUN_TRIM=0
CRASH_PROCESS=0
RESTART_WAIT=30
DURATION=900
OUT_DIR="${PWD}/flowery-background-validation-$(date +%Y%m%d-%H%M%S)"

usage() {
  cat <<'EOF'
Usage: validate_android_background_playback.sh [options]

Options:
  --serial SERIAL   Select an ADB device (default: ANDROID_SERIAL / sole device)
  --doze            Force device-idle while capturing diagnostics
  --trim            Send RUNNING_LOW and RUNNING_CRITICAL trim-memory signals
  --crash-process   Inject an app crash (destructive; not a normal process-kill test)
  --restart-wait SEC Seconds to observe process/service recovery (default: 30)
  --duration SEC    Doze duration in seconds (default: 900)
  --out DIR         Output directory
  -h, --help        Show this help

The --crash-process option uses 'am crash'. Android/MIUI may stop the foreground
service as part of this injected crash, so it does not prove ordinary low-memory
process death or guarantee START_STICKY recreation. The report keeps those
outcomes distinct. Use only on a dedicated test device after saving other work.
The script never force-stops the app, changes standby buckets, requests battery
exemptions, or modifies battery settings. Start playback manually before running.
EOF
}

while (($#)); do
  case "$1" in
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --doze) RUN_DOZE=1; shift ;;
    --trim) RUN_TRIM=1; shift ;;
    --crash-process) CRASH_PROCESS=1; shift ;;
    --restart-wait) RESTART_WAIT="${2:?missing restart wait}"; shift 2 ;;
    --duration) DURATION="${2:?missing duration}"; shift 2 ;;
    --out) OUT_DIR="${2:?missing output directory}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! [[ "$DURATION" =~ ^[1-9][0-9]*$ ]] || ! [[ "$RESTART_WAIT" =~ ^[1-9][0-9]*$ ]]; then
  echo "Duration and restart wait must be positive integers." >&2
  exit 2
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB+=( -s "$SERIAL" ); fi
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No usable ADB device. Set ANDROID_SERIAL or pass --serial." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
LOG_PID=""
DOZE_FORCED=0
PID_BEFORE=""
PID_AFTER=""
PROCESS_EXIT_OBSERVED=0
NEW_PROCESS_OBSERVED=0
RECOVERY_OBSERVATION_STATE="not-started"
CRASH_COMMAND_STATUS="not-run"

capture_post_event_diagnostics() {
  ((CRASH_PROCESS)) || return 0
  [[ -n "$PID_BEFORE" ]] || return 0

  if [[ -z "$PID_AFTER" ]]; then
    PID_AFTER="$("${ADB[@]}" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | xargs || true)"
  fi
  printf '%s\n' "${PID_AFTER:-not-running}" > "$OUT_DIR/pid-after.txt"
  "${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-after-process-event.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys media_session > "$OUT_DIR/media-session-after-process-event.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys notification --noredact > "$OUT_DIR/notifications-after-process-event.txt" 2>&1 || true
}

write_recovery_report() {
  local report="$OUT_DIR/process-recovery-report.txt"
  local exit_observed="$PROCESS_EXIT_OBSERVED"
  if ((CRASH_PROCESS)) && [[ -n "$PID_BEFORE" ]] && [[ -s "$OUT_DIR/full-logcat.txt" ]]; then
    for pid in $PID_BEFORE; do
      if grep -Fq "am_proc_died: [0,$pid,$PACKAGE," "$OUT_DIR/full-logcat.txt"; then
        exit_observed=1
        break
      fi
    done
  fi

  {
    echo "package=$PACKAGE"
    echo "test_mode=$([[ "$CRASH_PROCESS" -eq 1 ]] && echo injected-am-crash || echo background-diagnostics)"
    echo "pid_before=${PID_BEFORE:-not-recorded}"
    echo "pid_after=${PID_AFTER:-not-recorded}"
    echo "process_exit_observed=$exit_observed"
    echo "new_process_observed=$NEW_PROCESS_OBSERVED"
    echo "recovery_observation_state=$RECOVERY_OBSERVATION_STATE"
    echo "am_crash_command_status=$CRASH_COMMAND_STATUS"
    echo
    echo "Recovery markers (if present):"
    if [[ -f "$OUT_DIR/full-logcat.txt" ]]; then
      grep -E 'RECOVERY_(SERVICE_RESTART|SNAPSHOT_RESTORED|MANUAL_PLAY)|am_proc_died|am_create_service|am_foreground_service_start|am_foreground_service_stop|FATAL EXCEPTION|CrashedByAdbException' \
        "$OUT_DIR/full-logcat.txt" || true
    else
      echo "full-logcat.txt not available"
    fi
  } > "$report"
}

cleanup() {
  if ((DOZE_FORCED)); then
    "${ADB[@]}" shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  fi
  capture_post_event_diagnostics
  if [[ -n "$LOG_PID" ]]; then
    kill "$LOG_PID" >/dev/null 2>&1 || true
    wait "$LOG_PID" 2>/dev/null || true
    LOG_PID=""
  fi
  write_recovery_report
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"${ADB[@]}" shell getprop ro.product.manufacturer > "$OUT_DIR/manufacturer.txt"
"${ADB[@]}" shell getprop ro.product.model > "$OUT_DIR/model.txt"
"${ADB[@]}" shell getprop ro.build.version.release > "$OUT_DIR/android-version.txt"
"${ADB[@]}" shell getprop ro.build.version.sdk > "$OUT_DIR/android-sdk.txt"
"${ADB[@]}" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-before.txt"
"${ADB[@]}" shell am get-standby-bucket "$PACKAGE" > "$OUT_DIR/standby-bucket.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-before.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys media_session > "$OUT_DIR/media-session-before.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys notification --noredact > "$OUT_DIR/notifications-before.txt" 2>&1 || true
"${ADB[@]}" shell pidof "$PACKAGE" > "$OUT_DIR/pid-before.txt" 2>&1 || true

# Preserve AndroidRuntime and ActivityManager evidence; do not clear the device log buffers.
"${ADB[@]}" logcat -b all -v threadtime -T 1 > "$OUT_DIR/full-logcat.txt" 2> "$OUT_DIR/logcat-errors.txt" &
LOG_PID=$!

if ((RUN_TRIM)); then
  echo "Sending RUNNING_LOW memory trim signal..."
  "${ADB[@]}" shell am send-trim-memory "$PACKAGE" RUNNING_LOW
  sleep 3
  "${ADB[@]}" shell dumpsys activity processes > "$OUT_DIR/processes-running-low.txt"
  echo "Sending RUNNING_CRITICAL memory trim signal..."
  "${ADB[@]}" shell am send-trim-memory "$PACKAGE" RUNNING_CRITICAL
  sleep 3
  "${ADB[@]}" shell dumpsys activity processes > "$OUT_DIR/processes-running-critical.txt"
fi

if ((CRASH_PROCESS)); then
  PID_BEFORE="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r' | xargs)"
  if [[ -z "$PID_BEFORE" ]]; then
    echo "Flowery is not running. Start playback and retry --crash-process." >&2
    exit 1
  fi
  echo "Injecting an app crash into Flowery PID(s): $PID_BEFORE"
  echo "This is an injected crash, not a normal Android low-memory process kill."
  RECOVERY_OBSERVATION_STATE="running"
  "${ADB[@]}" shell am crash "$PACKAGE" > "$OUT_DIR/am-crash.txt" 2>&1 || CRASH_COMMAND_STATUS=$?
  if [[ "$CRASH_COMMAND_STATUS" == "not-run" ]]; then CRASH_COMMAND_STATUS=0; fi

  for ((elapsed=0; elapsed<RESTART_WAIT; elapsed+=2)); do
    sleep 2
    "${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-after-process-event.txt" 2>&1 || true
    PID_AFTER="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r' | xargs)"
    if [[ -z "$PID_AFTER" ]]; then
      PROCESS_EXIT_OBSERVED=1
    elif [[ "$PID_AFTER" != "$PID_BEFORE" ]]; then
      NEW_PROCESS_OBSERVED=1
      break
    fi
  done
  RECOVERY_OBSERVATION_STATE="complete"
  capture_post_event_diagnostics
fi

if ((RUN_DOZE)); then
  echo "Forcing device-idle for up to ${DURATION}s. Press Ctrl+C to end early."
  "${ADB[@]}" shell dumpsys deviceidle force-idle
  DOZE_FORCED=1
  "${ADB[@]}" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-during.txt"
  "${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-during-doze.txt" 2>&1 || true
  sleep "$DURATION"
  "${ADB[@]}" shell dumpsys deviceidle unforce
  DOZE_FORCED=0
fi

"${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-after.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys deviceidle > "$OUT_DIR/deviceidle-after.txt"
"${ADB[@]}" shell dumpsys media_session > "$OUT_DIR/media-session-after.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys notification --noredact > "$OUT_DIR/notifications-after.txt" 2>&1 || true
echo "Diagnostics are being captured in: $OUT_DIR"
echo "Press Ctrl+C after the observation window; cleanup will stop logcat and write process-recovery-report.txt."
while kill -0 "$LOG_PID" 2>/dev/null; do sleep 1; done
