#!/usr/bin/env bash
set -Eeuo pipefail

expected_api="${1:?Expected Android API level argument}"
mkdir -p ultrashare-diagnostics

capture_diagnostics() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "Collecting emulator diagnostics after failure (exit $status)."
    adb devices -l > ultrashare-diagnostics/adb-devices.txt 2>&1 || true
    adb logcat -d -v threadtime > ultrashare-diagnostics/logcat.txt 2>&1 || true
    adb shell getprop > ultrashare-diagnostics/properties.txt 2>&1 || true
    adb shell dumpsys activity processes > ultrashare-diagnostics/activity-processes.txt 2>&1 || true
  fi
  exit "$status"
}
trap capture_diagnostics EXIT

adb wait-for-device
booted=false
for attempt in $(seq 1 60); do
  sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  boot="$(adb shell getprop sys.boot_completed | tr -d '\r')"
  if [ "$sdk" = "$expected_api" ] && [ "$boot" = "1" ]; then
    echo "Emulator is stable: API $sdk"
    booted=true
    break
  fi
  sleep 2
done

if [ "$booted" != true ]; then
  echo "Emulator did not reach a stable Android boot state (expected API $expected_api)."
  adb devices -l || true
  adb shell getprop || true
  exit 1
fi

./gradlew --build-cache --stacktrace --max-workers=1 :app:connectedDebugAndroidTest
