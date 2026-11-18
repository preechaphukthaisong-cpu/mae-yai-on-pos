#!/usr/bin/env bash
# Run as one command from android-emulator-runner. Keep shell control flow here:
# android-emulator-runner executes each line of its "script" input separately.
set -Eeuo pipefail

LOG_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
LOG_FILE="$LOG_DIR/emulator-logcat.txt"
mkdir -p "$LOG_DIR"

capture_logcat() {
  adb logcat -d -b all > "$LOG_FILE" 2>/dev/null || true
}
trap capture_logcat EXIT

adb wait-for-device
echo "Waiting for Android package service (maximum 180 seconds)..."
ready=0
for ((attempt = 1; attempt <= 90; attempt++)); do
  if adb shell service check package 2>&1 | grep -q "found"; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "$ready" -ne 1 ]]; then
  echo "::error::Android package service did not become ready within 180 seconds"
  adb shell getprop sys.boot_completed || true
  adb shell service list || true
  capture_logcat
  tail -n 200 "$LOG_FILE" || true
  exit 1
fi

echo "Android package service is ready."
adb shell settings put global package_verifier_enable 0 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell settings put global package_verifier_user_consent -1 || true

gradle --no-daemon :app:connectedDebugAndroidTest
