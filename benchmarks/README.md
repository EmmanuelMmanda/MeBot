# 0.9.0 engine measurements — host JVM only

## Result and scope

Real Kotlin `FeedLogic.edition` was measured before behavioral changes at baseline commit
`4ebfd34a151715cf99215c057649158415659d72` (tree `eaac328d60ee48a981bc29390092caa5769f930f`).
This is **not Android phone latency**, launch time, battery use, provider speed or a live-account test.
On this shared x86 host, the final-source repeat of the 250-item dense fixture fell from
**21.549 ms to 5.100 ms at p95 (76.3%)**. Exact scorer equivalence is tested.
The initial optimization experiment below is retained rather than replaced silently;
its 4.844 ms result and the final repeat reflect normal host variability.

### Initial optimization experiment (historical raw result)

| Fixture | Baseline p50 / p95 / p99 ms | Candidate p50 / p95 / p99 ms |
| --- | --- | --- |
| 5 sparse | .111 / .307 / .496 | .094 / .281 / .594 |
| 5 dense | .256 / .574 / .784 | .338 / .584 / .708 |
| 50 sparse | .696 / 1.197 / 2.547 | .487 / .882 / 1.083 |
| 50 dense | 2.480 / 3.419 / 4.488 | .712 / 1.210 / 1.533 |
| 250 sparse | 6.495 / 7.830 / 10.908 | 2.397 / 3.580 / 5.076 |
| 250 dense | 17.202 / 21.549 / 27.256 | 3.677 / 4.844 / 6.529 |

Small-fixture results are mixed/noisy; **not every percentile improved**. The original scorer is
retained for <=10 visible items because snapshot setup regressed five-item fixtures in the first
experiment. That experiment's raw data remains in `results/experiment-snapshot-all` (not silently
discarded). Final five-item dense p95 is 1.7% higher; fork p95 ranges overlap (baseline .472–.611,
candidate .483–.610 ms). Do not claim a universal speedup from these numbers.

Snapshot preparation builds scoped context terms, saved/hidden title terms and per-module feedback
decay once per ranking operation. Candidate text is tokenized once. It retains prior ranking math,
context selection, feedback order and ties. No long-lived mutable cache or privacy scope widening.

## Method and reproducibility

* Three independent JVM forks, 100 warmups and 400 measured observations per scenario per fork:
  1,200 observations per scenario, 7,200 per ranking variant. Successful results are consumed.
* Fixed synthetic EN/SW fixtures, 5/50/250 items; sparse has no notes/signals, dense has 80 notes / 500 signals.
  Save/hide sets are deterministic. Every fixture passes actual domain validation. SHA-256 is in every row.
* Monotonic `System.nanoTime`, no network, OpenJDK 17.0.21+9, Kotlin 2.2.21, 512 MiB heap, Linux x86_64,
  AMD EPYC 9V74 shared host. Detailed environment and baseline/candidate source/class digests are in
  `results/environment.json`. No secrets, prompts, user data or full URLs in timing logs.
* Baseline classes were compiled before changing ranking and copied to ignored local snapshot output;
  baseline forks 2/3 used that exact compiled snapshot. Final candidate used a separate compiled snapshot.
* Nearest-rank percentiles and per-fork p95 ranges: `python3 scripts/summarize-host-benchmarks.py`.
* To reproduce candidate: configure documented JDK/Gradle environment, run `./gradlew :core:testClasses`,
  then `scripts/run-host-benchmarks.sh optimized`. To reproduce baseline, use a separate checkout of
  the recorded baseline plus only the benchmark harness/build task; never replace its domain classes.
* Three forks are not confidence intervals. Iterations within a fork are correlated; p99 is exploratory.
  Shared-host load, JIT and non-randomized sequential baseline/candidate ordering limit inference.
  No absolute timing gate or fabricated phone result is used.

## Storage assessment, not an architecture rewrite

The real unchanged `FeedLogic.validate` and Kotlin JSON serializer were measured on a legal synthetic
800,000-character conversation fixture. Three forks × 400 measurements plus 100 warmups:

| Operation | p50 / p95 / p99 ms |
| --- | --- |
| Validate | .082 / .258 / .518 |
| Serialize | .694 / 1.107 / 3.359 |

These are current-host diagnostics, **not a before/after storage speedup**, disk/fsync latency,
write-frequency measurement or a phone bound. They do not justify a persistence migration. Existing
no-op-write elimination, atomic quota reservation, durable user intent and repository transactions
remain unchanged. Duplicate-refresh changes were deferred without measured evidence.

## Correctness and remaining acceptance

196 core tests passed in the focused gate (including seven new ranking/Today tests). Exact old/new
score comparison covers 1,000 randomized candidates with scoped notes, 500 signals, saves/hides/history;
a five-visible / 500-saved paused-module fixture covers small-edition history. Today tests cover unknown,
old/future dates, verified updates, source-local events, fresh forecast validity, 23/25-hour DST days,
local-midnight rollover, extreme zones, paused sources, canonical duplicates and source-evidence merge.

Today is a derived cache view preserving original item IDs/module scopes. Legacy unknown dates stay
unknown. Forecast evidence freshness uses independent verification time, never a retouched fetch time.
No extra provider request, telemetry, new prompt-bound learning field or cloned item is introduced.

Physical release-like device startup/frame/jank, actual PIN KDF tuning, battery, PDF decoding, live
streams and platform biometrics remain separate device acceptance. Final all-app/service gates and
release signing are owned by the integration lead. Nothing here claims a signed or published release.

## Integration review after measured run

Today now filters temporally eligible candidates **before** source diversification, while scoring still
receives the complete state (including saved/hidden/context history). Normal Feed delegates to the same
unchanged ranking body through `editionEligible`. A regression checks that yesterday's high-ranked source
cannot cause today's lower-ranked different publisher to leapfrog today's stronger candidate. Typed
temporal imports reject inconsistent provenance/field combinations. Source-local date-only events use
overlap between the source calendar day and user calendar day; UI retains the source date/timezone,
not a fabricated event instant. These assertions do not authenticate an externally edited backup's truth.

The original measured source/class digests remain intact. `post_measurement_review` records subsequent
source hashes and scope; these tiny integration edits were not silently relabeled as the measured binary.
The lead's final compile/test gate must exercise the two added regression tests.

## Exact final-source repeat

After extracting the Today eligible-ranking helper (normal scoring body unchanged),
three fresh quiet JVM forks repeated the same 1,200 samples/scenario. Raw files are
`results/final-verification/`; hashes and observations are in environment.json.
Dense 250-item p50/p95/p99 were **3.837 / 5.100 / 6.327 ms**: p95 remains **76.3%
 below** the 21.549 ms baseline. Five-item dense p95 was .469 ms (versus baseline
.574 ms), supporting that the earlier +1.7% observation was not a repeatable
regression. This repeat is preserved alongside the original optimization experiment,
not substituted silently. Host/fork/correlated-tail and device limitations still apply.
