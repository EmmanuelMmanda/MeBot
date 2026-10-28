# 0.9.8 acceptance tracker

Status: corrected runtime qualification is in progress; 0.9.8 is not published or available in-app. This source includes the corrected AI-only suggestions and the reading-engine slice. Earlier signed draft bytes do not contain the persistence-library correction below and must not be published.

## Implemented slice

- Additive acquisition/authorship, bounded Original Read sections and separate further-reading links; legacy decoding remains supported.
- One optional Original Read in the existing authorized module discovery response, independently of the five-question suggestion contract. No extra fill request or fabricated source/date.
- Actual web-search acquisition is distinct from cached-source AI transformation. Model date strings alone do not establish publication time.
- Stable local daily selection, separate from verified source-day records; explicit refresh can rerank. Saved/source content is not deleted by selection.
- Public GitHub Releases for an explicit repository focus, with bounded results, actual publication metadata, cache, cancellation and unauthenticated transport.
- Original Read detail sections and honest authorship/creation labels; evidence and further-reading links are separate.
- Self-hosted follow-up schema accepts validated Read bodies. Self-hosted discovery does not yet generate Original Reads.

## Earlier reading-slice qualification (historical checkpoint)

- Exact runtime qualification commit: `83f9a6a0eea6f548e5ae95b06025bd29235b5fa9`.
- Full Android 757 + core 245 + service 204 tests passed: 1,206 total, zero failures/errors/skips. Service retains one existing Starlette/httpx deprecation warning.
- Final combined gate `final-complete-gate.log`: BUILD SUCCESSFUL in 12m 2s; full tests, debug/release lint and debug/production assemblies. Archived test XML is `final-tests-3`; service XML is `service-final.xml` in the external qualification evidence directory.
- Debug lint: zero errors, 281 warnings and 3 hints. Release lint: zero errors, 277 warnings and 3 hints. No baseline suppression was introduced.
- Unsigned production APK SHA-256 `a854183f2b7bafb43071aed1a2f2f988bc0c0e08cbfc7dda654595054a56ddac`, 17,644,967 bytes, package `mebot.nguli.com`, version 0.9.8/code 25, minimum API 26/target 36; non-debug and 16 KiB alignment verified. It is not an installable signed release.
- Provider transport fixtures cover one-request Original Read generation with five questions, source-date evidence, public coexistence, durable Read preservation on disconnect and count budgets. Service feed/search cannot self-assert publisher acquisition or dates; valid older cited search responses can derive a missing display label from the actual source URL host.
- Actual ViewModel tests cover daily-selection persistence/recreation/reset with zero provider/source calls and unchanged source timestamps.
- Native full-shell EN/SW daily-selection and Original Read captures are under `docs/screenshots/v098/`; all content is synthetic, UTC and not a live provider result. Source publication metadata is inside its own card and never applied to an Original Read.
- Documentation/showcase checks pass. Earlier qualification failures were corrected without weakened assertions: lazy-list fixture queries, legacy service display attribution, carried 0.9.7 test-only fixtures, and explicit braces for a conditional detail layout.

## Concurrent persistence observation correction — 28 October 2026

Hosted qualification exposed intermittent cold-flow observation timeouts despite eventual durable state restoration. A test-only IO collector did not eliminate the failures. The resolved AndroidX DataStore 1.1.7 implementation contains the upstream concurrent read/write versioning race documented in [AndroidX issue 431787506](https://issuetracker.google.com/issues/431787506) and [fix 1ef22b89](https://android.googlesource.com/platform/frameworks/support/+/1ef22b89a34bd4027d7c50684101a62daf27b07b).

An Apache-attributed adaptation of the upstream serializer-barrier regression receives the initial value but misses the committed update on 1.1.7; the identical regression passes on stable DataStore 1.3.0. The dependency correction is narrow: no storage schema, migration, application API or permission change. The diagnostic-only reflection trace and targeted CI workflow are excluded from this release source. This establishes the dependency defect and fix; it does not retrospectively prove every earlier UI/PDF synchronization failure had that cause.

The corrected runtime passed 759 Android and 245 core tests plus debug/release lint and assembly. Debug lint reported 276 warnings and 2 hints; release lint reported 277 warnings and 3 hints, with no errors. Resolved dependencies are DataStore 1.3.0, coroutines 1.10.2 and Kotlin stdlib 2.2.21. App minimum API remains 26; DataStore minimum API is 24. All four packaged native ABIs have 16 KiB ELF LOAD alignment and APK ZIP alignment passes.

Actual DataStore 1.1.7 FeedRepository/AppRepository writers produced the checked-in synthetic compatibility fixtures and closed their scopes. The 1.3.0 reader regression verified exact fixture hashes, preserved feed/context/Read/conversation and language/theme/settings data, then updated, closed and reopened both stores without losing the checked values. Writer and reader focused tests passed. The final full suite including this additional compatibility regression and hosted gates remain required.

Corrected unsigned APK checkpoint: SHA-256 `5878ea579f070aee46b855225fb34ffe815b166bb6125accfea5fc9b4dd9705a`, 17,731,844 bytes. Same-key signing and anonymous updater verification are required again for these changed runtime bytes. Earlier APK hashes above identify historical checkpoints, not this corrected build. Stable release provenance: [official DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore#1.3.0).

## Remaining gates

- Corrected full test/lint/assembly qualification, final source archive identity/privacy audit and rebuilt artifact freeze.
- Exact hosted checks, existing-key signing, public asset/updater verification and separate full website promotion. Protected-workflow security activation remains separately held; no settings or credential change is included.
- Physical/OEM behavior, real provider/account limits and process-kill acceptance are not established by host tests.

## Deliberate limits

AndroidX, arXiv and World Bank adapter proposals are not implemented in this slice. No private connector, inferred religion, new subscription, silent paid retry, external telemetry or owner profile data is introduced. Provenance is application metadata about the acquisition path, not a cryptographic publisher attestation or a guarantee that AI prose is correct.
