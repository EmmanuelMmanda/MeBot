#!/usr/bin/env python3
"""Nearest-rank quantiles; emit per-fork spread rather than hide host variability."""
import csv, glob, math, sys
from collections import defaultdict
root = sys.argv[1] if len(sys.argv) > 1 else 'benchmarks/results'
for label in ('baseline', 'optimized', 'storage'):
    groups = defaultdict(list)
    forks = defaultdict(lambda: defaultdict(list))
    for path in sorted(glob.glob(f'{root}/{label}-*.csv')):
        for row in csv.DictReader(open(path, encoding='utf-8')):
            if row['outcome'] != 'success':
                raise SystemExit(f'Non-success observation in {path}: {row["outcome"]}')
            value = int(row['duration_ns']) / 1e6
            groups[row['scenario']].append(value)
            forks[row['scenario']][row['fork']].append(value)
    def q(values, percentile):
        values = sorted(values)
        return values[math.ceil(len(values) * percentile) - 1]
    print(f'\n{label}: scenario,n,p50_ms,p95_ms,p99_ms,fork_p95_min_ms,fork_p95_max_ms')
    for scenario, values in groups.items():
        tails = [q(v, .95) for v in forks[scenario].values()]
        print(','.join([scenario, str(len(values))] + [f'{x:.4f}' for x in (q(values,.5),q(values,.95),q(values,.99),min(tails),max(tails))]))
