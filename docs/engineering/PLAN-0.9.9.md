# MeBot 0.9.9 — connected guidance and usable answers

Status: local tests, lint and packaging qualified; hosted qualification, signing and publication pending. Version 0.9.9 / code 26.
Baseline: published 0.9.8 runtime, stable DataStore 1.3.0 and its cold-collector/persistence regressions. Existing accounts, context, drafts, sources and settings are preserved.

## Design and ownership
Keep the established Figtree/theme system, compact feed and progressive disclosure. One concise ChatGPT-first introduction leads to connection, confirmed context, then the personalised feed; other providers and independent public feeds remain available. The integrator alone edits MainActivity wiring. Separate lanes own suggestions/star, setup/tips, and audio/response components. Shared persisted schema changes are integrated centrally.

## Acceptance checklist
- [ ] Star persists before a bottom snackbar confirms following/unfollowing; natural English/Kiswahili and accessible announcement. Copy reflects actual connection/background state, not guaranteed background delivery. Bookmark remains distinct.
- [ ] Every applicable main/detail/chat suggestion icon reaches the real AI sheet. The first five provider-authored angles are prepared ahead of opening at bounded, coalesced active-matter readiness events, preferably piggybacking existing inference. Cached sets are instant; explicit opening is a cache-miss fallback. The main new-chat landing also prepares a genuinely personalized starter set without creating a conversation. Rendering/scrolling never generates. Load more requests genuinely new deduplicated angles while preserving earlier results and failures; five is not a total cap. No canned padding or fallback.
- [ ] Repeated taps, close/back, lock, account/context/locale/item changes, budget errors and incomplete responses preserve cancellation/stale guards. Primary Send and pencil Edit remain distinct; pending attachments never leak.
- [ ] New users see ChatGPT as primary, one concise introduction and three-step progress. Existing provider selection never changes silently. Continue without AI retains useful public content.
- [ ] Only verified connection queues context import once. Copy prompt, open ChatGPT, paste/import and manual context are explicit; no automatic history/entitlement claims. Import success displays durable notes and next-feed action; cancellation, Back and process death do not reset or repeat completed setup.
- [ ] Optional in-app tips derive missing connection/context, background disabled, an eligible software update and optional voice setup from real state. Manual confirmed context counts; completed tips clear, skips/snoozes persist, no system nags or fabricated counts.
- [ ] Read-aloud handles initialization, language/voice, audio focus, lifecycle, errors and stop/restart while respecting system media volume and settings. TTS is not advertised as live two-way voice.
- [ ] Answers remain readable/selectable during streaming/media loads; whole-answer copy/share/read-aloud at bottom, code-copy and safe Markdown links/lists/tables. Fullscreen media and cancellation retain coverage.
- [ ] Native English/Kiswahili captures demonstrate the actual new journey and current themes with honest provenance. Website stays truthful at public 0.9.8 until 0.9.9 is verified.

## Qualification
Focused state/transition tests precede the complete core/app/service, debug/release lint and assembly gates. Preserve DataStore race and 1.1.7→1.3.0 on-disk compatibility tests. Independently review privacy, scope, localization and actual rendered pixels. Freeze exact source; verify hosted PR and merged-head checks; sign with the existing production certificate only. Publish a new immutable v0.9.9/code26 release, then anonymously verify all assets, certificate/hash and actual updater eligibility/download. Website promotion is independently checked and deployed. No security-setting changes.

## Confirmed voice scope (owner clarification)
- [ ] Implement genuine two-way voice through a supported official OpenAI Realtime/WebRTC path, with ICE, media/data-channel readiness, interruption, reconnect and deterministic cleanup. ChatGPT OAuth/text entitlements do not imply Realtime API access. Report actual credential/billing prerequisites before live paid tests; no new keys or paid activation.
- [ ] Replace external recognizer popups with MeBot-owned native recording: explicit permission, timer/waveform, processing, editable transcript, send, error/retry/cancel. Preserve unsent text/recording on recoverable failure; no automatic send or background microphone/upload; lock/lifecycle stops capture.
- [ ] Replace unclear voice slider with compact labelled vertical plus/minus control anchored to the appropriate audio icon. Distinguish playback volume, speech rate and microphone gain; respect system volume.
- [ ] All AI reading/questions/voice use current confirmed About You and correctly scoped module notes; factual claims remain source-grounded independently of personalization.
Official transport research and existing credential compatibility must be resolved, not silently replaced with TTS or a pretend live-call control.

## Voice activation boundary
The implementation and fake-transport/service tests are authorized. Live API project/server configuration, credential provisioning, deployment and paid acceptance remain pending the owner’s choice. ChatGPT sign-in remains the primary supported text setup; voice uses a separate broker capability and must not replace that connection. Before each live call, disclose the microphone recipient, selected item, confirmed scoped context/recent history, and separate API billing. Disabled or unavailable capability must be actionable and honest, not an inert recording dead end. Enforce shared and feature budgets, bounded session/turn/output limits, and lock/background/context/account cleanup. Do not describe fake transport results as live voice verification.

## Website and editorial acceptance
- Verify the actual app font asset and all browser font loads/computed faces, weights and fallbacks; a CSS family declaration alone is not proof of correct rendering.
- Remove slogans, vague claims and developer-facing version/debug/theme badges from current customer presentation. Every heading and sentence must explain a real capability, relevant benefit or next action in natural English/Kiswahili.
- Show substantive personalised reading, About You, useful AI angles and dated opportunities as well as sourced news. Native sample captures must not imply a live provider/account; retain full capture provenance in technical evidence and concise example labels where needed.
- Preserve actual source publication dates, preparation dates and opportunity deadlines as distinct values. Choose meaningful dated examples rather than inventing dates to hide unknown publication times.
- Keep README, landing, galleries and inner guides coherent; preserve immutable historical release evidence. Confirm browser font loading, responsive layout, image decoding, keyboard navigation and links before promotion.

## Platform voice implementation boundary
The Android adapter uses bundled local WebView assets, a strict trusted origin and narrowly scoped audio permission after explicit user action. Broker credentials remain native/server-side; JavaScript receives only protocol/media data, not account tokens or personal context. The community native WebRTC AAR is not part of the release dependency graph. Fake adapter tests and service tests do not establish real-device microphone, ICE or playback behavior. Real-device/emulator and live-provider acceptance remain required before claiming operational two-way voice. The owner explicitly accepts release without configured optional voice: hide conversation call controls (and the generic mic when no usable audio action exists), expose dismissible setup guidance outside the conversation, and keep text/public-feed use independent. Recreated routes and capability removal must not restore unusable controls. Server-owned call termination and daily/session limits are safeguards, not guaranteed monetary caps when provider/network termination fails.

## Latest narrow release checks
- [ ] Erase local data removes saved audio draft ownership and all owned final/staging audio; lifecycle cancellation cannot suppress the erase continuation. Old unreferenced crash files are swept with bounded age/count rules while referenced drafts remain intact.
- [ ] A verified opportunity deadline is shown separately from publication/selection time, with full readable date, appropriate timezone only when known, and a cautious past-deadline status.
- [ ] Current-facing version appears only in the docs footer; the primary Download latest version button resolves directly to the actual latest APK. Technical manifests and immutable release records retain exact identities.

## Local qualification checkpoint

The final local aggregate passed 877 Android and 263 core tests with no failures, errors or skips, plus debug/release lint and both assemblies. Service coverage is 220 tests (the 204-test baseline plus 16 new regressions), and the fake WebView WebRTC contract passed. The optional compact topic chooser remains reachable from About You and guided context import, with search, categories, more topics and autosaved scoped notes; actual route and persistence tests cover Back, rotation and preservation of unrelated drafts.

The earlier full-run Settings092 test stall did not recur in an identical focused class run or the final aggregate; its cause is not established. No test timeout or behavior assertion was relaxed to resolve it. Lint retains warnings/hints but no errors. Real-device audio/ICE and paid live-provider calls remain unverified, and missing optional voice configuration is not presented as an available conversation action.
