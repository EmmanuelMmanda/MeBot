#!/usr/bin/env bash
# Keep the normal gate unchanged; collect bounded thread evidence if it stops progressing.
set -euo pipefail
mkdir -p ci-diagnostics
gradle :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease \
  --no-daemon --stacktrace -Pkotlin.incremental=false \
  --init-script scripts/ci-test-observability.gradle &
build_pid=$!
(
  for sample in 1 2; do
    sleep 480
    kill -0 "$build_pid" 2>/dev/null || exit 0
    # No environment, system properties, credentials or heap data are collected.
    while read -r java_pid; do
      [[ "$java_pid" =~ ^[0-9]+$ ]] || continue
      timeout 20s jcmd "$java_pid" Thread.print 2>&1 | head -c 4194304 \
        > "ci-diagnostics/threads-${sample}-${java_pid}.txt" || true
    done < <(jps -q)
  done
) &
observer_pid=$!
trap 'kill "$observer_pid" 2>/dev/null || true' EXIT
status=0
wait "$build_pid" || status=$?
kill "$observer_pid" 2>/dev/null || true
wait "$observer_pid" 2>/dev/null || true
# Capture Software Updates with release BuildConfig so the screenshot never shows a debug suffix.
if (( status == 0 )); then
  gradle :app:testReleaseUnitTest --tests 'mebot.nguli.com.ui.Release082CaptureTest.softwareUpdatesExplainsAutomaticChecksAndConsent' \
    --no-daemon --stacktrace -Pkotlin.incremental=false \
    --init-script scripts/ci-test-observability.gradle
fi
exit "$status"
