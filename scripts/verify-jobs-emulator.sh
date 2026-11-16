#!/usr/bin/env bash
# Disposable emulator only. Never points at a user's phone, reads accounts or signs a release.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
evidence="$root/device-qa-evidence"
mkdir -p "$evidence"
git rev-parse HEAD > "$evidence/source-commit.txt"
git diff --stat > "$evidence/local-change-summary.txt"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" && -x "$SDK/emulator/emulator" ]] || { echo 'Official Android SDK/emulator is required.' >&2; exit 2; }
# Explicit emulator serial prevents accidentally operating any attached physical device.
export ANDROID_SERIAL=emulator-5580
export ANDROID_AVD_HOME="${RUNNER_TEMP:-$evidence}/mebot-jobs-avd"
mkdir -p "$ANDROID_AVD_HOME"
export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
AVDMANAGER=$(command -v avdmanager || true)
[[ -n "$AVDMANAGER" ]] || { echo 'avdmanager is required.' >&2; exit 2; }
TEST_CLASS=mebot.nguli.com.ui.jobs.JobsDeviceJourneyTest
[[ -f app/src/androidTest/java/mebot/nguli/com/ui/jobs/JobsDeviceJourneyTest.kt ]] || {
  echo 'Device journey test is missing. Do not substitute a host-rendered preview for device verification.' >&2; exit 2;
}
# Report the actual acceleration capability without altering host security or /dev/kvm permissions.
acceleration=on
if ! "$SDK/emulator/emulator" -accel-check > "$evidence/acceleration.txt" 2>&1; then
  # Android's official CPU emulation keeps this a real instrumented Android journey.
  # It is slower, but does not loosen host device permissions or replace assertions.
  acceleration=off
  printf '\nUsing software CPU emulation; host security settings are unchanged.\n' >> "$evidence/acceleration.txt"
fi
python3 scripts/prepare-build.py
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --max-workers=2 --stacktrace > "$evidence/build.log" 2>&1
printf 'no\n' | "$AVDMANAGER" create avd --force --name MeBotJobsQA --package 'system-images;android-35;google_apis;x86_64' --device pixel_2 > "$evidence/avd.log" 2>&1
"$SDK/emulator/emulator" @MeBotJobsQA -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect -accel "$acceleration" -cores 2 > "$evidence/emulator.log" 2>&1 &
emulator_pid=$!
cleanup() {
  adb -s "$ANDROID_SERIAL" logcat -d -t 3000 > "$evidence/logcat.txt" 2>&1 || true
  adb -s "$ANDROID_SERIAL" pull /sdcard/Android/data/mebot.nguli.com.debug/files/jobs-device-qa "$evidence/" >/dev/null 2>&1 || true
  adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
  kill "$emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT
booted=false
for _ in $(seq 1 900); do
  if [[ "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  kill -0 "$emulator_pid" 2>/dev/null || { echo 'Emulator exited before boot.' >&2; exit 3; }
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator boot timeout.' >&2; exit 3; }
adb -s "$ANDROID_SERIAL" shell input keyevent 82
# Disable connectivity only inside this disposable emulator; all fixture data is local.
adb -s "$ANDROID_SERIAL" shell svc wifi disable
adb -s "$ANDROID_SERIAL" shell svc data disable
adb -s "$ANDROID_SERIAL" shell wm size 720x1440
adb -s "$ANDROID_SERIAL" shell wm density 320
# This fixed disposable serial is the only device passed to the Gradle connected test runner.
for variant in phone-light phone-dark-large-text tablet-light; do
  case "$variant" in
    phone-light) adb -s "$ANDROID_SERIAL" shell wm size 720x1440; adb -s "$ANDROID_SERIAL" shell wm density 320; adb -s "$ANDROID_SERIAL" shell settings put system font_scale 1.0 ;;
    phone-dark-large-text) adb -s "$ANDROID_SERIAL" shell wm size 720x1440; adb -s "$ANDROID_SERIAL" shell wm density 320; adb -s "$ANDROID_SERIAL" shell settings put system font_scale 1.5 ;;
    tablet-light) adb -s "$ANDROID_SERIAL" shell wm size 1200x1920; adb -s "$ANDROID_SERIAL" shell wm density 240; adb -s "$ANDROID_SERIAL" shell settings put system font_scale 1.0 ;;
  esac
  gradle :app:connectedDebugAndroidTest --no-daemon --max-workers=2 \
    -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
    -Pandroid.testInstrumentationRunnerArguments.qaVariant="$variant" --stacktrace > "$evidence/instrumentation-$variant.log" 2>&1
done
adb -s "$ANDROID_SERIAL" pull /sdcard/Android/data/mebot.nguli.com.debug/files/jobs-device-qa "$evidence/"
find "$evidence/jobs-device-qa" -name '*.png' -type f | grep -q . || { echo 'No real-device screenshots were produced.' >&2; exit 4; }
printf 'Instrumented journey completed; screenshots still require human/model visual inspection.\n' > "$evidence/RESULT.txt"
