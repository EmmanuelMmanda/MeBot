#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

mode="${1:-verify}"
if [[ $# -gt 0 ]]; then shift; fi

case "$mode" in
  verify)
    tasks=(:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug)
    ;;
  debug)
    tasks=(:app:assembleDebug)
    ;;
  release)
    release_keystore="${MEBOT_KEYSTORE:-${MEBOT_KEYSTORE:-}}"
    release_password="${MEBOT_KEYSTORE_PASSWORD:-${MEBOT_KEYSTORE_PASSWORD:-}}"
    if [[ -z "$release_keystore" || -z "$release_password" ]]; then
      printf 'Set MEBOT_KEYSTORE and MEBOT_KEYSTORE_PASSWORD through your local secrets manager.\n' >&2
      exit 2
    fi
    if [[ ! -f "$release_keystore" ]]; then
      printf 'Release keystore not found.\n' >&2
      exit 2
    fi
    tasks=(:core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease)
    ;;
  *)
    printf 'Usage: %s [verify|debug|release] [additional Gradle options]\n' "$0" >&2
    exit 2
    ;;
esac

exec ./gradlew "${tasks[@]}" "$@"
