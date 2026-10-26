#!/usr/bin/env bash
# Run after ./gradlew :core:testClasses; no Android or paid network calls.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRADLE_USER_HOME:=$HOME/.gradle}"
label=${1:-candidate}
output=${2:-benchmarks/results}
mkdir -p "$output"
cp="core/build/classes/kotlin/main:core/build/classes/kotlin/test"
while IFS= read -r jar; do cp="$cp:$jar"; done < <(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1" -name '*.jar' | grep -E '(kotlin-stdlib-2.2.21|kotlinx-serialization-(core|json)-jvm-1.9.0)')
for fork in 1 2 3; do
  java -Xmx512m -cp "$cp" mebot.nguli.com.core.RankingBenchmark "$output/$label-$fork.csv" "$fork"
  java -Xmx512m -cp "$cp" mebot.nguli.com.core.StorageBenchmark "$output/storage-$label-$fork.csv" "$fork"
done
