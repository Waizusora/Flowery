#!/usr/bin/env bash
set -euo pipefail

mkdir -p build/doze-report
REPORT="$GITHUB_WORKSPACE/build/doze-report"
APK="$GITHUB_WORKSPACE/app/build/outputs/apk/debug/app-debug.apk"

test -f "$APK"
adb install -r "$APK" | tee "$REPORT/install.txt"
adb shell am start -n com.waizusora.flowery/.MainActivity | tee "$REPORT/launch.txt"

for attempt in $(seq 1 30); do
  if adb shell pidof com.waizusora.flowery | tr -d '\r' | grep -q '[0-9]'; then
    break
  fi
  sleep 2
done
APP_PID="$(adb shell pidof com.waizusora.flowery | tr -d '\r' | xargs)"
test -n "$APP_PID"
printf 'app_pid_before=%s\n' "$APP_PID" | tee "$REPORT/process.txt"

adb shell input keyevent 3

echo "Entering forced Light Doze"
adb shell dumpsys deviceidle force-idle light | tee "$REPORT/light-force.txt"
sleep 5
adb shell dumpsys deviceidle | tee "$REPORT/light-state.txt"
grep -Eq 'mLightState=(IDLE|IDLE_MAINTENANCE)' "$REPORT/light-state.txt"

echo "Exiting forced Light Doze"
adb shell dumpsys deviceidle unforce | tee "$REPORT/light-unforce.txt"

echo "Entering forced Deep Doze"
adb shell dumpsys deviceidle force-idle deep | tee "$REPORT/deep-force.txt"
sleep 5
adb shell dumpsys deviceidle | tee "$REPORT/deep-state.txt"
grep -Eq 'mState=IDLE([[:space:]]|$)' "$REPORT/deep-state.txt"

adb shell pidof com.waizusora.flowery | tr -d '\r' | xargs | tee "$REPORT/process-during-deep-doze.txt"

echo "Exiting forced Deep Doze"
adb shell dumpsys deviceidle unforce | tee "$REPORT/deep-unforce.txt"
sleep 2
adb shell dumpsys deviceidle | tee "$REPORT/active-state.txt"
grep -q 'mForceIdle=false' "$REPORT/active-state.txt"

echo "Doze transition checks passed. Playback continuity is not asserted by this workflow."
