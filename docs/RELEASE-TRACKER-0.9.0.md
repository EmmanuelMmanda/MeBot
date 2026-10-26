# 0.9.0 candidate acceptance tracker

Status: local implementation, not a published release. Based on verified 0.8.5
tree `eaac328d60ee48a981bc29390092caa5769f930f` (local commit
`4ebfd34a151715cf99215c057649158415659d72`). The 0.8.5 checkout and artifacts
remain frozen. Official signed publication requires separately approved signing
custody and final review; no production signing identity has been generated.

## Design direction

Keep the established native theme, typography, spacing and five destinations.
Feed remains home. Today is a compact feed filter, not a second ingestion engine.
Keep source attribution and existing item actions; distinguish publication,
verified substantive update, forecast validity and event relevance. Empty Today
must never substitute stale content. Onboarding preserves the prior equal-width two-column icon tiles at compact density,
with 48dp minimum targets, accessible large-text adaptation, search and optional
custom text after the chooser. Normal EN/SW full-shell previews are distinct from
the narrow 130% text stress fixture. Security is optional and uses a
standard explicit PIN/biometric setup, with no preselected PIN or deceptive
claim that the feed database has become encrypted.

## Gates

- [x] Actual Kotlin baseline, fixture hashes and raw multi-fork host measurements.
- [x] Exact-equivalence ranking tests and measured optimized comparison.
- [x] Today date/provenance validation, local midnight/DST and enabled-source rules.
- [x] No extra inference for cached Today; original item/context ownership retained.
- [x] Onboarding retry/revisit persistence and compact EN/SW native previews.
- [x] App PIN/biometrics host/source gates, stale callbacks, lifecycle and import isolation; hardware acceptance remains pending.
- [x] Full core/app/service tests, lint, release assembly and artifact inspection.
- [x] Native rendered UI review; physical-device gaps explicitly recorded.
- [x] Accurate docs/site candidate copy and static checks; browser/site deployment not performed.
- [x] Parent source/visual review and specific owner approval for public source publication; remote verification remains separate.
- [ ] Approved signing custody, signed APK, release/tag/publication verification.

Host-JVM timings are not phone startup, battery or provider measurements. Real
biometric hardware, Android lock-screen/recents behavior and native PDF/live
streaming acceptance remain device checks until actually exercised.

## Earlier measurement evidence

- 196 core tests passed in the focused domain gate; 180 unchanged service tests
  passed (one upstream Starlette/httpx deprecation warning).
- Dense 250-item host Kotlin ranking: p50 17.202→3.677 ms, p95 21.549→4.844 ms,
  p99 27.256→6.529 ms. Three JVM forks, 1,200 samples/scenario; mixed small-fixture
  results and shared-host limits disclosed in `benchmarks/README.md`.
- Current legal 800k-conversation-text validation p95 .258 ms and serialization
  p95 1.107 ms do not establish a disk/phone bottleneck; persistence unchanged.
- Initial dependency/resource and constructor issues were fixed; the final
  aggregate gate below passed on the corrected implementation.

Final frozen-source correctness gate: **198 core + 575 Android + 180 service =
953 tests**, zero failures/errors/skips. Includes 48 security tests, full-shell
Today/save/follow/collection/original-ownership journeys, source metadata, and
second-offset/DST local-midnight scheduling. Final debug/release lint and assembly passed: zero errors, 230 debug / 230 release
warnings, two hints each. Non-debug release is unsigned, aligned and version 0.9.0 / code 17. Exact-source host repeat retained large dense p95 **5.100 ms**
(76.3% below baseline); earlier 4.844 ms observation remains in raw evidence.

## Terminal release-readiness evidence

Final aggregate gate completed successfully on 2026-10-25. Production APK:
`mebot.nguli.com`, minimum API 26, target 36, version 0.9.0 / code 17,
non-debuggable, **unsigned**, 17,370,274 bytes. SHA-256:
`7de1b4f64e5c879547da14dca6a5768fd30b9fd81eb47f69db2a0142a828956a`.
ZIP alignment passes (including 16 KiB native-library alignment). Signature
verification correctly fails because no production signer has been configured.
This is not an installable official release; no debug APK is offered in its place.

Native host captures include an actual full-shell Today journey proving star
beside bookmark, five destinations and durable original-item actions. Source/docs
static checks pass. New website browser/remote CI, actual biometric/Keystore and
physical-device compositor/PDF/streaming acceptance remain unverified.

## Onboarding preview correction

The initial primary Kiswahili preview mistakenly used an isolated 320dp/130%
accessibility fixture. Primary previews now show the actual full onboarding
Activity at 411dp and ordinary text size in English and Kiswahili. The earlier
equal-width two-column icon tiles are restored at compact density, with 18dp
icons, 48dp minimum targets and user-font-aware adaptation. Optional custom text
follows the chooser. Three additional real-Activity journeys cover search,
category filters, Show more, selection, language, Back, activity recreation,
explicit landscape/portrait changes and durable scoped custom-note saving.
Custom text is geometrically verified fully above the pinned footer.
