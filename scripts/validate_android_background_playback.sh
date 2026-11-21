#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.waizusora.flowery"
SERIAL="${ANDROID_SERIAL:-}"
RUN_DOZE=0
RUN_TRIM=0
DURATION=900
OUT_DIR="${PWD}/flowery-background-validation-$(date +%Y%m%d-%H%M%S)"

usage() {
  cat <<'EOF'
Usage: validate_android_background_playback.sh [options]

Options:
  --serial SERIAL   Select an ADB device (default: ANDROID_SERIAL / sole device)
  --doze            Force device-idle while capturing diagnostics
  --trim            Send RUNNING_LOW and RUNNING_CRITICAL trim-memory signals
  --duration SEC    Doze duration in seconds (default: 900)
  --out DIR         Output directory
  -h, --help        Show this help

The script does not force-stop Flowery, kill its process, change app standby
buckets, request battery-optimization exemptions, or modify battery settings.
Start playback manually before running it. Use a dedicated test device.
EOF
}

while (($#)); do
  case "$1" in
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --doze) RUN_DOZE=1; shift ;;
    --trim) RUN_TRIM=1; shift ;;
    --duration) DURATION="${2:?missing duration}"; shift 2 ;;
    --out) OUT_DIR="${2:?missing output directory}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! [[ "$DURATION" =~ ^[1-9][0-9]*$ ]]; then
  echo "Duration must be a positive integer." >&2
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
echo "Diagnostics are being captured in: $OUT_DIR"
echo "Stop this script with Ctrl+C after the playback observation window; logcat will be finalized."
while kill -0 "$LOG_PID" 2>/dev/null; do sleep 1; done
