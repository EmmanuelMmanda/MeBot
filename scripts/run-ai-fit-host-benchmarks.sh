#!/usr/bin/env bash
# Run after :core:testClasses; uses only synthetic local data and no provider calls.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRADLE_USER_HOME:=$HOME/.gradle}"
output=${1:-../mebot-094-evidence/benchmarks}
mkdir -p "$output"
output=$(cd "$output" && pwd)
cp="core/build/classes/kotlin/main:core/build/classes/kotlin/test"
while IFS= read -r jar; do cp="$cp:$jar"; done < <(
  find "$GRADLE_USER_HOME/caches/modules-2/files-2.1" -name '*.jar' |
    grep -E '(kotlin-stdlib-2\.2\.21|kotlinx-serialization-(core|json)-jvm-1\.9\.0)'
)
for fork in 1 2 3; do
  java -Xmx512m -cp "$cp" mebot.nguli.com.core.AiRelevanceRankingBenchmark094 \
    "$output/ai-fit-$fork.csv" "$fork"
done
