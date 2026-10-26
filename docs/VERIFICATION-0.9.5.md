# 0.9.5 acceptance tracker

Candidate only; no completed release or device acceptance is claimed here.

## Design boundaries
- Preserve existing visual design and accessibility touch targets.
- Card primary action opens in-app detail; publisher attribution remains visible.
- Original source is immediately accessible in detail/overflow, without AI, payment or forced interaction.
- One grounded category tag, not redundant badges or speculative AI taxonomy.
- Suggestions are optional, item-grounded questions; selection prepares an editable scoped draft, never sends automatically.
- Preserve unrelated unsent drafts. Explicit Send remains the provider-request boundary.
- Existing request piggyback only: bounded inputs/outputs, strict known IDs and ASK-only intent; no additional discovery request or quota reservation.
- Cache is content/context/provider/language-bound and expires. Provider payloads omit derived interaction caches.
- No fabricated personalization, dates or source claims; offline local fallback is not labelled AI.
- Current feed ordering remains stable while reading. Do not repeatedly propose a question already asked in the current thread.

## Gates
- [x] Core focused suite initially passed (211 tests); URL hardening adds a regression case for the final gate.
- [x] Android provider/engine/ViewModel focused regressions passed: 46 tests (20 provider, 20 engine, 6 draft ViewModel). Later prompt/validator hardening remains in the final gate.
- [x] Real MainActivity EN/SW feed/detail/chat draft journeys pass, including source access, selected-item binding, no automatic send and detail Back return.
- [x] Normal full-shell EN/SW native captures in `docs/screenshots/v095`; 320dp/130% Kiswahili chip bounds and callback regression passes.
- [x] Independent source/privacy/security review; bare-link validation, safe callback defaults, current-state cache resolution and scoped draft semantics reviewed.
- [x] Final local gate: 212 core, 683 Android and 180 service tests passed (1,075 total; no failures, errors or skips). Release/debug lint passed with zero errors (250/247 warnings and 3 hints each); production and debug assembly passed.
- [ ] Existing-key signed production APK, package/version/signature/alignment checks.
- [ ] Exact-source public CI, develop/main workflow and signed GitHub release.
- [ ] Anonymous asset and installed-parser update checks.
- [ ] Full website version/download/history/screenshots/feature parity and public Pages verification.

Host fixtures are not physical-device, live-account or paid-provider acceptance. Preserve raw attempts and diagnose failures; do not weaken assertions to obtain a pass.

The legacy optional self-hosted service contract does not supply AI-generated suggestion questions in this version; local item-specific suggestions remain available. Unsent editable drafts intentionally survive navigation/rotation behind the existing app-lock gate, like other user-authored drafts.

## Verification history and limits

A focused UI test initially observed the composer before the asynchronous durable thread/draft preparation completed. The final journey waits for the selected item-bound empty thread and exact editable text, then asserts the request budget is unchanged. The final tests do not replace that behavior with sleeps or automatic sending. A real cache-display issue was also fixed: new AI questions are checked against current wall time rather than an earlier minute tick, while selection still revalidates strict content/context/provider/language and age constraints.

The first aggregate test run passed; its later release-lint process was deliberately interrupted to incorporate safe no-op defaults for unwired callbacks and the final large-text regression. The entire final core/Android/lint/assembly stream then passed unchanged runtime source. The service suite uses a local fake provider; it does not exercise a paid account.

The inherited website-promotion CI encountered an intermittent empty-alarm snapshot in an existing legacy QR alarm journey on the same source tree that passed its develop push. A test-only bounded diagnostic preserves the original fail-fast single-alarm assertion and timeout. Its focused nine-test suite and the final local aggregate pass, but the original intermittent cause is not established. No alarm runtime fix is claimed. The diagnostic file's public upload awaits explicit payload approval, and no alternate export route is used.

Source/publication, signing and website completion remain separate gates above. No physical-device, OEM biometric, real subscription, overnight background execution, or live-provider acceptance is claimed by host test results.
