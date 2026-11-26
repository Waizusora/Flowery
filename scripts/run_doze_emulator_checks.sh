#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.waizusora.flowery"
REPORT="$GITHUB_WORKSPACE/build/doze-report"
APK="$GITHUB_WORKSPACE/app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$REPORT"

read_app_pid() {
  adb shell pidof "$PACKAGE" | tr -d '\r' | xargs
}

capture_deviceidle_state() {
  local phase="$1"
  local mode="$2"
  local expected="$3"
  local output="$REPORT/${phase}-state.txt"

  for attempt in 1 2 3 4 5; do
    if adb shell cmd deviceidle get "$mode" >"$output" 2>&1 &&
      grep -Eiq "$expected" "$output"; then
      cat "$output"
      return 0
    fi
    echo "deviceidle $mode state capture for $phase did not match on attempt $attempt/5." >&2
    sleep 2
  done

  cat "$output" >&2
  return 1
}

assert_app_pid_unchanged() {
  local phase="$1"
  local current_pid
  current_pid="$(read_app_pid)"
  printf 'app_pid_%s=%s\n' "$phase" "${current_pid:-not-running}" | tee -a "$REPORT/process.txt"
  if [[ "$current_pid" != "$APP_PID" ]]; then
    echo "Application process changed during $phase: expected PID $APP_PID, got ${current_pid:-not-running}" >&2
    return 1
  fi
}

test -f "$APK"
adb install -r "$APK" | tee "$REPORT/install.txt"
adb shell am start -n com.waizusora.flowery/.MainActivity | tee "$REPORT/launch.txt"

for attempt in $(seq 1 30); do
  if adb shell pidof "$PACKAGE" | tr -d '\r' | grep -q '[0-9]'; then
    break
  fi
  sleep 2
done

APP_PID="$(read_app_pid)"
test -n "$APP_PID"
printf 'app_pid_before=%s\n' "$APP_PID" | tee "$REPORT/process.txt"
adb shell input keyevent 3

echo "Entering forced Light Doze"
adb shell cmd deviceidle force-idle light | tee "$REPORT/light-force.txt"
sleep 5
capture_deviceidle_state "light" "light" '^(IDLE|IDLE_MAINTENANCE)$'
assert_app_pid_unchanged "light_doze"

echo "Exiting forced Light Doze"
adb shell cmd deviceidle unforce | tee "$REPORT/light-unforce.txt"
sleep 2
capture_deviceidle_state "after-light" "light" '^(ACTIVE|INACTIVE|IDLE)$'
assert_app_pid_unchanged "after_light_doze"

echo "Entering forced Deep Doze"
adb shell cmd deviceidle force-idle deep | tee "$REPORT/deep-force.txt"
sleep 5
capture_deviceidle_state "deep" "deep" '^IDLE$'
read_app_pid | tee "$REPORT/process-during-deep-doze.txt"
assert_app_pid_unchanged "deep_doze"

echo "Exiting forced Deep Doze"
adb shell cmd deviceidle unforce | tee "$REPORT/deep-unforce.txt"
sleep 2
capture_deviceidle_state "active" "deep" '^(ACTIVE|INACTIVE)$'
assert_app_pid_unchanged "after_deep_doze"

echo "Doze transition and application process continuity checks passed. Playback continuity is not asserted by this workflow."
