#!/usr/bin/env bash
set -Eeuo pipefail

expected_api="${1:?Expected Android API level argument}"
diagnostics_dir="ultrashare-diagnostics"
mkdir -p "$diagnostics_dir"

capture_diagnostics() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "Collecting emulator diagnostics after failure (exit $status)."
    adb devices -l > "$diagnostics_dir/adb-devices.txt" 2>&1 || true
    adb logcat -d -v threadtime > "$diagnostics_dir/logcat.txt" 2>&1 || true
    adb shell getprop > "$diagnostics_dir/properties.txt" 2>&1 || true
    adb shell dumpsys activity processes > "$diagnostics_dir/activity-processes.txt" 2>&1 || true
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

# The APKs are built once in android-test-apks and downloaded by every API job.
# Never trigger an accidental Gradle rebuild after the emulator has started.
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

run_suite() {
  suite_name="$1"
  class_filter="$2"
  output="$diagnostics_dir/${suite_name}-api-${expected_api}.txt"
  echo "===== Running ${suite_name} tests on API ${expected_api} ====="
  set +e
  timeout 300s adb shell am instrument -w -r -e class "$class_filter" "$instrumentation" 2>&1 | tee "$output"
  suite_status=${PIPESTATUS[0]}
  set -e
  if [ "$suite_status" -ne 0 ] || ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$output"; then
    echo "Android ${suite_name} tests failed or timed out on API ${expected_api}."
    return 1
  fi
}

# Keep the fast app/database and networking checks separate from the GPU/video suite.
# Every group still runs on API 23, 29, and 36; no instrumented test class is omitted.
run_suite "app-smoke-database" \
  "com.malekplay.MainNavigationSmokeTest,com.malekplay.RoomMigrationInstrumentedTest"

run_suite "network" \
  "com.malekplay.network.NetworkCredentialVaultInstrumentedTest,com.malekplay.network.UltraShareNetworkRuntimeInstrumentedTest"

run_suite "gpu-video" \
  "com.malekplay.player.effects.AssVideoEffectRegistryLifecycleInstrumentedTest,com.malekplay.player.effects.VideoEnhancementCompatibilityInstrumentedTest,com.malekplay.player.effects.VideoEnhancementExoPlayerMediaCodecInstrumentedTest,com.malekplay.player.effects.VideoEnhancementMedia3FrameProcessorInstrumentedTest,com.malekplay.player.effects.VideoEnhancementPipelineGlesInstrumentedTest,com.malekplay.player.effects.VideoEnhancementPipelinePixelInstrumentedTest"

echo "All Android runtime test groups passed on API ${expected_api}."
