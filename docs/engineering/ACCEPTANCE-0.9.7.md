# 0.9.7 suggestions sheet acceptance

## Direction
Keep the established MeBot visual design and public-first feed. Replace intrusive suggestion rails with a compact bottom Suggestions icon and an accessible bottom sheet. Show prepared next steps grounded in the selected story, current conversation, selected module or approved overall context. Opening and dismissing the sheet never sends or starts inference.

## Explicit actions
The primary action clearly sends the selected suggestion to its captured chat scope. The pencil action opens an editable draft without sending. This intentionally replaces the earlier all-taps-prepare-only behavior. Preserve other drafts, prevent duplicate sends and revalidate content, context, module, conversation and provider identity at action time. A stale or missing target must fail closed, never become a general question accidentally.

## Privacy and offline
Use cached AI suggestions when valid and useful deterministic local suggestions otherwise, labelled honestly. No new background inference or undisclosed provider request. Confirmed context remains scoped; no extra private connectors or access to account memory. Disconnected users can read public/RSS/cached content immediately. Source access remains secondary but independently available.

## Gates
- Primary Send versus pencil Edit; sheet open/cancel/Back/repeated taps.
- Existing chat and story/module/general target identity; stale content/context and deleted targets.
- Lock, navigation, recreation and saved-state fencing; no deferred automatic send on unlock. Cancel a pending suggestion before dispatch and restore only its exact provisional turn, without overwriting newer conversation selections.
- Revalidate the captured option at quota reservation and again after any provider-concurrency wait. An explicit attempt already reserved may still count toward the local conservative budget; opening or editing never reserves a request.
- Existing unsent text preserved; offline/unauthenticated public feed remains usable.
- Normal native EN/SW renders, narrow/large-text accessibility and minimum48dp targets.
- Core/Android/service tests, release/debug lint and production assembly; existing signing identity only.
- Exact public source and signed APK, installed updater verification, full website parity and experience-first changelog.

Focused evidence: 71 engine/VM tests and 34 UI tests pass; the final four EN/SW capture journeys also pass (these overlap the UI suite). The isolated service suite passes 180 tests with one dependency deprecation warning. The final local suites pass 713 Android, 212 core and 180 service tests (1,105 total), with no failures, errors or skips. Debug and release lint pass with zero errors (252/248 warnings respectively, plus two hints each); debug and production-release assembly pass. The first combined build lost its daemon while tests and lint overlapped; unchanged tests then passed sequentially with one worker, followed by successful lint/assembly. The cause was not established as a product defect or proven memory exhaustion. Evidence logs: `full-tests-sequential.log`, `lint-assembly-sequential.log` and `service-test.log` in the external 0.9.7 verification bundle. Hosted exact-source CI, signing and publication gates remain pending. Host tests do not prove physical/OEM or live-provider behavior.

## Publication follow-through
After the exact source and signed APK qualify, update the current website guide and feature descriptions to distinguish primary Send from pencil Edit. Keep earlier release notes historical rather than silently rewriting their original interaction contract. Verify the release catalog, download names, update metadata, current guide, normal-size screenshots and live Pages bytes against the published version. A private attachment or unpublished draft is not a completed release.

## Controlled screenshot fixture
The final source also sets an explicit UTC profile timezone in the two EN/SW suggestion capture journeys rather than inheriting the runner timezone. Both focused journeys pass with the UTC assertion; the affected detail capture was regenerated. This is a test/image-only follow-up to the full local gate, with no production or signed APK change. Hosted checks must qualify the final source tree.

## Hosted cancellation follow-up
PR #19 run 40471842096 failed the strict queued-send navigation cleanup assertion after the local full gate passed. The initial signed candidate is held and must not be released. A deterministic additional assertion proved a separate real ordering defect: cancellation could synchronously clear the stored child-job reference before that child was cancelled. The narrow correction captures the child reference first. The strict cleanup/no-provider checks remain, with bounded failure diagnostics; attribution of the original cleanup timeout is not yet proven. Full local and hosted qualification and a rebuilt same-key APK are required for the corrected candidate.
