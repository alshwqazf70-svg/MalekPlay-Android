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

timeout 60s adb wait-for-device
booted=false
for attempt in $(seq 1 30); do
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

# Verify the Android-test Kotlin source set as an explicit CI gate.
# CI prebuilds the x86_64 APKs before emulator startup; keep this gate as a no-op when outputs exist.
if [ ! -s app/build/outputs/apk/debug/app-debug.apk ] || [ ! -s app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ]; then
  ./gradlew --build-cache --stacktrace --max-workers=2 -PtargetAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
fi
./gradlew --build-cache --stacktrace --max-workers=2 -PtargetAbi=x86_64 :app:compileDebugAndroidTestKotlin

# Build both APKs, then install/run through adb directly. Some hosted emulator/ddmlib
# combinations incorrectly route a single universal APK through SplitApkInstaller
# and reject it even on API 29+. Direct adb install avoids that false CI failure.
./gradlew --build-cache --stacktrace --max-workers=2 \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest

app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$app_apk"
test -s "$test_apk"

adb install -r "$app_apk"
adb install -r "$test_apk"

instrumentation="$(adb shell pm list instrumentation | tr -d '\r' | awk '
  /^instrumentation:/ {
    sub(/^instrumentation:/, "")
    sub(/ .*/, "")
    if (index($0, "androidx.test.runner.AndroidJUnitRunner") > 0) {
      print
      exit
    }
  }
')"
if [ -z "$instrumentation" ]; then
  echo "AndroidX test instrumentation was not registered after APK installation."
  adb shell pm list instrumentation || true
  exit 1
fi

set +e
adb shell am instrument -w -r "$instrumentation" 2>&1 | \
  tee "ultrashare-diagnostics/instrumentation-api-$expected_api.txt"
instrumentation_status=${PIPESTATUS[0]}
set -e

if [ "$instrumentation_status" -ne 0 ] || \
   ! grep -Fq 'OK (' "ultrashare-diagnostics/instrumentation-api-$expected_api.txt" || \
   ! grep -Eq '[0-9]+ tests?' "ultrashare-diagnostics/instrumentation-api-$expected_api.txt"; then
  echo "Android instrumented tests failed on API $expected_api."
  exit 1
fi
