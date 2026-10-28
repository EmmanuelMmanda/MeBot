# 0.9.7 suggestions sheet acceptance

## Direction
Keep the established MeBot visual design and public-first feed. Replace intrusive suggestion rails with a compact bottom Suggestions icon and an accessible bottom sheet. Show prepared next steps grounded in the selected story, current conversation, selected module or approved overall context. Opening and dismissing the sheet never sends or starts inference.

## Explicit actions
The primary action clearly sends the selected suggestion to its captured chat scope. The pencil action opens an editable draft without sending. This intentionally replaces the earlier all-taps-prepare-only behavior. Preserve other drafts, prevent duplicate sends and revalidate content, context, module, conversation and provider identity at action time. A stale or missing target must fail closed, never become a general question accidentally.

## Privacy and offline
Show only a complete set of five actual provider-generated, distinct questions. Never display canned/local fallback suggestions or pad a partial response. Reuse valid prepared sets; structured discovery prepares them within its existing request. Missing or stale sets have an explicit Generate/Retry action, with one disclosed request against the selected provider and existing budget. Opening the sheet never generates. Streaming chat currently has no structured suggestion envelope, so chat/module/general sets are explicitly generated and cached rather than hidden in prose or fetched automatically. Confirmed context remains scoped; no extra private connectors or access to account memory. Disconnected users can read public/RSS/cached content immediately. Source access remains secondary but independently available.

## Gates
- Primary Send versus pencil Edit; sheet open/cancel/Back/repeated taps.
- Existing chat and story/module/general target identity; stale content/context and deleted targets.
- Lock, navigation, recreation and saved-state fencing; no deferred automatic send on unlock. Cancel a pending suggestion before dispatch and restore only its exact provisional turn, without overwriting newer conversation selections.
- Revalidate the captured option at quota reservation and again after any provider-concurrency wait. An explicit attempt already reserved may still count toward the local conservative budget; opening or editing never reserves a request.
- Existing unsent text preserved; offline/unauthenticated public feed remains usable.
- Normal native EN/SW renders, narrow/large-text accessibility and minimum48dp targets.
- Core/Android/service tests, release/debug lint and production assembly; existing signing identity only.
- Exact public source and signed APK, installed updater verification, full website parity and experience-first changelog.

Prior-scope evidence (not qualification of the new AI-only revision): 71 engine/VM tests and 34 UI tests pass; the final four EN/SW capture journeys also pass (these overlap the UI suite). The isolated service suite passes 180 tests with one dependency deprecation warning. The final local suites pass 713 Android, 212 core and 180 service tests (1,105 total), with no failures, errors or skips. Debug and release lint pass with zero errors (252/248 warnings respectively, plus two hints each); debug and production-release assembly pass. The first combined build lost its daemon while tests and lint overlapped; unchanged tests then passed sequentially with one worker, followed by successful lint/assembly. The cause was not established as a product defect or proven memory exhaustion. Evidence logs: `full-tests-sequential.log`, `lint-assembly-sequential.log` and `service-test.log` in the external 0.9.7 verification bundle. Hosted exact-source CI, signing and publication gates remain pending. Host tests do not prove physical/OEM or live-provider behavior.

## Publication follow-through
After the exact source and signed APK qualify, update the current website guide and feature descriptions to distinguish primary Send from pencil Edit. Keep earlier release notes historical rather than silently rewriting their original interaction contract. Verify the release catalog, download names, update metadata, current guide, normal-size screenshots and live Pages bytes against the published version. A private attachment or unpublished draft is not a completed release.

## Prior controlled screenshot fixture
The final source also sets an explicit UTC profile timezone in the two EN/SW suggestion capture journeys rather than inheriting the runner timezone. Both focused journeys pass with the UTC assertion; the affected detail capture was regenerated. This is a test/image-only follow-up to the full local gate, with no production or signed APK change. Hosted checks must qualify the final source tree.

## Hosted cancellation follow-up
PR #19 run 40471842096 failed the strict queued-send navigation cleanup assertion after the local full gate passed. The initial signed candidate is held and must not be released. A deterministic additional assertion proved a separate real ordering defect: cancellation could synchronously clear the stored child-job reference before that child was cancelled. The narrow correction captures the child reference first. The strict cleanup/no-provider checks remain, with bounded failure diagnostics; attribution of the original cleanup timeout is not yet proven. Full local and hosted qualification and a rebuilt same-key APK are required for the corrected candidate.

## AI-only revision gates (pending)
Current qualification preserves the existing weather/horoscope exclusion for item-, module- and conversation-bound suggestion targets. These cards remain readable; an explicitly excluded module must not silently become a General target. Broader reading/discovery support is a separate engine milestone, not a hidden scope expansion in this patch.
- Complete provider-only five-question set; exact and obvious near-duplicate rejection, no fabricated padding. Lexical validation is not proof of semantic diversity; grounded instructions and representative quality fixtures remain necessary.
- Legacy partial cached sets decode without losing feed, notes or saved data, but do not appear as a prepared set.
- Existing discovery request prepares item questions; explicit missing-set generation reserves exactly one request, never a silent retry loop. Incomplete/invalid responses leave the public feed intact and show an honest retry state.
- Generation validates exact target, provider, language and confirmed context before dispatch and before durable cache commit; cancel, lock, logout, import and newer navigation fence late responses.
- Pending attachments are never sent by suggestion generation or primary suggestion Send. Persisted attachment drafts remain unchanged.
- Real fake-provider pipeline fixtures supply five substantive synthetic questions for normal EN/SW native captures; no direct canned UI injection or claim of live-provider testing.
- New final tests, lint, package, same-key signing, hosted checks and public verification must qualify the revised bytes; old signed draft stays held.

## Cancellation diagnostic evidence
The narrow child-cancellation fix passed the corrected local suites. A subsequent hosted run failed a separate existing-chat rollback observation, without identifying its cause. Diagnostic run 40477910823 then passed all 925 Android/core tests, including 11 suggestion-action ViewModel tests. This passing rerun does not establish the cause of the earlier timeouts. Cleanup is a separate job from the cancelled outer action; tests must observe the exact repository state and actual cleanup completion rather than treating the outer action's join as cleanup completion. Keep the original state and no-provider assertions and bounded deadline. Temporary phase tracing is diagnostic evidence, not a feature or required production telemetry.

## Key Points readability correction (pending qualification)
The detail page previously passed each provider key-point string directly to plain Text, exposing Markdown link syntax and provider citation markers. Display-only parsing now keeps prose/link labels, makes safe HTTP(S) references secondary controls, and never mutates the stored source text. Unsafe schemes/private destinations are not clickable. Focused tests cover balanced URL parentheses, missing closing markup, ordinary bracketed facts, raw URLs, duplicate references and citation tokens; native detail rendering/click tests remain required. This is not a general HTML renderer or a claim that arbitrary malformed model output is always repairable.

## Revised focused qualification
The AI-only revision compiles. Core tests pass (224); focused engine (31), provider/network (25), suggestion action ViewModel (15), draft ViewModel (6), and connected-chat journey (4) suites pass. Connected EN/SW journeys exercise one explicit generation request, a complete five-question fake-provider response, primary Send, pencil Edit and safe source access. Service regression tests pass (180, one existing test-client deprecation warning). These are focused results, not the final aggregate.

Two disconnected-sheet tests initially failed because the edge-positioned icon's test pointer did not invoke its callback. Exact-node semantic activation and a controlled centered real-pointer activation both open the expected unavailable/Connect sheet without inference; the final EN/SW pointer regressions and active-generation Close/Back cancellation tests pass. No product workaround was added for that test hit-target issue, and temporary probes were removed. A separate connected-generation wait now drives Compose idle while retaining its original 15-second deadline and strict five-question/request-count predicate; the provider had already returned once while the host Main continuation was pending. Physical device touch/permission/provider behavior remains outside this host evidence.
