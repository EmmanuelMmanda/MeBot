# 0.9.8 acceptance tracker

Status: local qualification passed; not signed, published, or available in-app. The 0.9.7 AI-only patch remains a separate frozen qualification/release task.

## Implemented slice

- Additive acquisition/authorship, bounded Original Read sections and separate further-reading links; legacy decoding remains supported.
- One optional Original Read in the existing authorized module discovery response, independently of the five-question suggestion contract. No extra fill request or fabricated source/date.
- Actual web-search acquisition is distinct from cached-source AI transformation. Model date strings alone do not establish publication time.
- Stable local daily selection, separate from verified source-day records; explicit refresh can rerank. Saved/source content is not deleted by selection.
- Public GitHub Releases for an explicit repository focus, with bounded results, actual publication metadata, cache, cancellation and unauthenticated transport.
- Original Read detail sections and honest authorship/creation labels; evidence and further-reading links are separate.
- Self-hosted follow-up schema accepts validated Read bodies. Self-hosted discovery does not yet generate Original Reads.

## Current evidence

- Exact runtime qualification commit: `83f9a6a0eea6f548e5ae95b06025bd29235b5fa9`.
- Full Android 757 + core 245 + service 204 tests passed: 1,206 total, zero failures/errors/skips. Service retains one existing Starlette/httpx deprecation warning.
- Final combined gate `final-complete-gate.log`: BUILD SUCCESSFUL in 12m 2s; full tests, debug/release lint and debug/production assemblies. Archived test XML is `final-tests-3`; service XML is `service-final.xml` in the external qualification evidence directory.
- Debug lint: zero errors, 281 warnings and 3 hints. Release lint: zero errors, 277 warnings and 3 hints. No baseline suppression was introduced.
- Unsigned production APK SHA-256 `a854183f2b7bafb43071aed1a2f2f988bc0c0e08cbfc7dda654595054a56ddac`, 17,644,967 bytes, package `mebot.nguli.com`, version 0.9.8/code 25, minimum API 26/target 36; non-debug and 16 KiB alignment verified. It is not an installable signed release.
- Provider transport fixtures cover one-request Original Read generation with five questions, source-date evidence, public coexistence, durable Read preservation on disconnect and count budgets. Service feed/search cannot self-assert publisher acquisition or dates; valid older cited search responses can derive a missing display label from the actual source URL host.
- Actual ViewModel tests cover daily-selection persistence/recreation/reset with zero provider/source calls and unchanged source timestamps.
- Native full-shell EN/SW daily-selection and Original Read captures are under `docs/screenshots/v098/`; all content is synthetic, UTC and not a live provider result. Source publication metadata is inside its own card and never applied to an Original Read.
- Documentation/showcase checks pass. Earlier qualification failures were corrected without weakened assertions: lazy-list fixture queries, legacy service display attribution, carried 0.9.7 test-only fixtures, and explicit braces for a conditional detail layout.

## Remaining gates

- Final source archive identity/privacy audit and private evidence preservation; runtime and unsigned qualification artifact are frozen.
- Approved release-workflow security prerequisites; exact hosted checks, same-key signing, public asset/updater verification and separate full website promotion.
- Physical/OEM behavior, real provider/account limits and process-kill acceptance are not established by host tests.

## Deliberate limits

AndroidX, arXiv and World Bank adapter proposals are not implemented in this slice. No private connector, inferred religion, new subscription, silent paid retry, external telemetry or owner profile data is introduced. Provenance is application metadata about the acquisition path, not a cryptographic publisher attestation or a guarantee that AI prose is correct.
