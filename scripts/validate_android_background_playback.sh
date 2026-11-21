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
  --crash-process   Crash the app process to test sticky-service recreation (destructive)
  --restart-wait SEC Seconds to observe service recreation (default: 30)
  --duration SEC    Doze duration in seconds (default: 900)
  --out DIR         Output directory
  -h, --help        Show this help

The --crash-process option deliberately crashes Flowery and is destructive.
Use it only on a dedicated test device after saving other work. It does not use
Force Stop. The script never changes standby buckets, requests battery exemptions,
or modifies battery settings. Start playback manually before running it.
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
cleanup() {
  if ((DOZE_FORCED)); then
    "${ADB[@]}" shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  fi
  if [[ -n "$LOG_PID" ]]; then
    kill "$LOG_PID" >/dev/null 2>&1 || true
    wait "$LOG_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

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

"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v threadtime -s FloweryDiagnostics:I FloweryDiagnostics:W FloweryDiagnostics:E '*:S' > "$OUT_DIR/flowery-logcat.txt" &
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
  PID_BEFORE="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\\r')"
  if [[ -z "$PID_BEFORE" ]]; then
    echo "Flowery is not running. Start playback and retry --crash-process." >&2
    exit 1
  fi
  echo "Crashing Flowery process PID $PID_BEFORE to exercise sticky service recreation..."
  "${ADB[@]}" shell am crash "$PACKAGE"
  for ((elapsed=0; elapsed<RESTART_WAIT; elapsed+=2)); do
    sleep 2
    "${ADB[@]}" shell dumpsys activity services "$PACKAGE" > "$OUT_DIR/services-after-process-death.txt" 2>&1 || true
    PID_AFTER="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\\r')"
    if [[ -n "$PID_AFTER" && "$PID_AFTER" != "$PID_BEFORE" ]]; then
      break
    fi
  done
  printf '%s\\n' "${PID_AFTER:-not-running}" > "$OUT_DIR/pid-after.txt"
  "${ADB[@]}" shell dumpsys media_session > "$OUT_DIR/media-session-after-process-death.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys notification --noredact > "$OUT_DIR/notifications-after-process-death.txt" 2>&1 || true
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
echo "Stop this script with Ctrl+C after the playback observation window; logcat will be finalized."
while kill -0 "$LOG_PID" 2>/dev/null; do sleep 1; done
