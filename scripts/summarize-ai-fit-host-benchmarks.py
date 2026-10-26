#!/usr/bin/env python3
"""Summarize isolated synthetic MeBot 0.9.4 AI-fit ranking host samples."""
import csv
import glob
import math
import sys
from collections import defaultdict

root = sys.argv[1] if len(sys.argv) > 1 else "../mebot-094-evidence/benchmarks"
groups = defaultdict(list)
forks = defaultdict(lambda: defaultdict(list))
for path in sorted(glob.glob(f"{root}/ai-fit-*.csv")):
    with open(path, encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            if row["outcome"] != "success":
                raise SystemExit(f"Non-success observation in {path}: {row['outcome']}")
            value = int(row["duration_ns"]) / 1e6
            groups[row["scenario"]].append(value)
            forks[row["scenario"]][row["fork"]].append(value)

def nearest_rank(values, percentile):
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * percentile) - 1]

print("scenario,n,p50_ms,p95_ms,p99_ms,fork_p95_min_ms,fork_p95_max_ms")
for scenario, values in sorted(groups.items()):
    per_fork_p95 = [nearest_rank(samples, 0.95) for samples in forks[scenario].values()]
    quantiles = [nearest_rank(values, p) for p in (0.50, 0.95, 0.99)]
    print(",".join([scenario, str(len(values))] + [f"{value:.4f}" for value in (
        *quantiles, min(per_fork_p95), max(per_fork_p95))]))
