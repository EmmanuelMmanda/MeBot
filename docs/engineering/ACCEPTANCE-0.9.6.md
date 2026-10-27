# 0.9.6 conversation-scope repair acceptance

## Direction
Keep the polished interface and all prior functionality. Opening a story question must bind the intended story before sending is possible. Preserve typed text while the conversation is opening; never silently turn an item-scoped question into a general conversation.

## Reproduced evidence
The post-merge main run 40423889063 at commit 301123c98574ccee7d7391c85639ab92d3cc2d72 failed `connectionFailureCanBeCorrectedAndGroundedAskSurvivesRotation`. Its persisted synthetic fixture contained an empty toolkit/news conversation and a second unscoped conversation with the expected question and answer. Earlier exact-tree PR/develop runs passed. The failing assertion is valid and must not be weakened.

The detail action navigates before asynchronous thread creation finishes. The composer previously allowed sending during this interval, and a missing active thread caused the sender to create an unscoped conversation. The artifact does not prove that a normal configuration change lost an already-selected ViewModel; asynchronous opening and stale repository snapshots must be tested explicitly. Process-death restoration is a separate boundary.

## Deterministic baseline
A separate worktree at the immutable public 0.9.5 source commit `eb6d2a12815cbf68decb2996eed9c1f0e0581e41` adds only a no-op pre-open test hook and a synthetic regression. Holding that hook and immediately calling Send fails the VM invariant that sending must be rejected until the captured item thread is ready. The baseline run at 2026-10-27 00:38:44 UTC has one test and one expected assertion failure; it is not an environment or provider error. Corrected focused coverage passed 26/26 before the additional lock fence. The final corrected aggregate then passed 212 core and 693 Android tests, with zero failures, errors or skips, including all nine conversation-opening/restore/lock tests and all eight full-shell feed journeys. The separate service suite passed 180 tests (one existing dependency deprecation warning). The final local gate completed on 27 October 2026 at approximately 01:33 UTC: release/debug assembly and lint succeeded. Release lint reports zero errors, 246 warnings and two hints; debug lint reports zero errors, 244 warnings and two hints. The preceding infrastructure-interrupted run has no terminal result and is not counted as a pass.

The tested runtime commit is `233d1a9d96df6ec4cd5ce358b7050029a7d4301b`; this acceptance update changes documentation only. Detailed raw XML/log evidence is retained separately. Saved-state tests use AndroidX SavedStateHandle's Bundle provider and a fresh ViewModel, while normal Activity recreation is covered by the native journey. This is not a full OS-killed Activity or physical-device acceptance claim.

The final lock barrier holds an optional-prompt story opening, applies the same cancellation ordering as the lock screen, and verifies zero conversation turns, provider calls and request usage. Unlocking resumes only the story scope, never the deferred prompt.

## Required gates
- Deterministically delay thread creation, type immediately, and attempt Send. No unscoped request or extra conversation may occur; typed text remains available.
- Release the delay, verify durable target selection, then send exactly once with the original item and module context.
- A newer thread selection, new conversation, reset, account removal or cancellation must invalidate stale opening completion.
- Preserve item identity and draft through recreation; distinguish actual process-death coverage from host recreation.
- A missing/deleted explicit target must fail closed, not silently create a general thread.
- Preserve deliberate general conversations, existing unsent drafts, attachments, app lock, source access and request budgets.
- Run focused regressions, full core/Android/service gates, lint and release assembly. Sign only final verified bytes using the existing production identity.

## Publication
0.9.5 source, tag and APK remain immutable. This is a new corrective candidate until all gates pass. Experience-first release notes remain separate from technical evidence. Do not include private owner JSON, credentials, keystores or local state in source or reports. Physical/OEM and live-provider acceptance are not implied by host tests.
