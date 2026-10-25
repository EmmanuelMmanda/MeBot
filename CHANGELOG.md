# Changelog

## 0.8.2 — 2026-10-25

New `mebot.nguli.com` identity; provider choices; Software Updates; module-scoped feedback; progressive feed; clearer setup, background and detail UI; real sourced captures. [Release notes](docs/RELEASE-0.8.2.md).

## 0.8.1 — 2026-10-25

Accurate anchored activity; scoped refresh; compact MeBot details; Tanzania public-source defaults; verified resource actions. See [release notes](docs/RELEASE-0.8.1.md).

## 0.8.0

Official MeBot package (`app.mynguli`), version code 11. Includes the single top activity panel, compact answers and story resources, independent media/file loading, supported direct-file saving, eight accents and custom colours, and four page styles. This publishes the verified 0.7.1 delivery changes under the requested public version. No account, alarm or recommendation implementation is replaced by the version bump.

[Release details](docs/RELEASE-0.8.0.md) · [Illustrated guide](https://emmanuelmmanda.github.io/MeBot/tour.html).


## Open-source documentation — 2026-10-23

- Added the illustrated GitHub Pages user guide, repository overview, contribution and security reporting instructions, and documentation checks.
- Preserved historical source tags and recorded available release artifacts and checksums. Public APK distributions omit the optional personal starter profile. All retained application entries and the signing identity are preserved; container/signature metadata and APK checksums change.
- This documentation and packaging update does not change application code or increase the app version.

## 0.6.1 — 2026-10-23

- Added a skippable, replayable six-step welcome tour and searchable Help & FAQ.
- Added module search, category/status filters, visible per-kind default briefs, and direct access to disabled modules through Add to your day.
- Exposed context import, JSON paste, preview and a copyable ChatGPT preparation prompt directly in My context.
- Added request-budget presets of 28, 56, 112 and 224, custom positive limits and Unlimited, while preserving existing choices and provider limits.
- Polished horizontal selection rails, feed-tab refresh/return to top, local paging, image-request sharing and redundant storage writes.
- Added original Pulse, Rising chime and Steady bell alarm sounds alongside Classic, phone/file audio selection, preview and optional offline spoken wake-up messages.
- Completed 646 automated tests and debug/release lint with zero errors. Physical-device alarm and live-account checks remain separate.

## 0.6.0 — 2026-10-23

- Added automatic streamed explanations when opening a story, citations, in-place follow-up questions and completed-response reuse.
- Added the central AI workspace with saved conversations, module selection and reviewed text/Markdown attachments.
- Added AI-assisted search across selected enabled factual modules with sourced results and the existing detail/save flow.
- Added module drag ordering with accessible move controls and persisted home/search filter order.
- Expanded module-specific preferences, horoscope setup and data controls while preserving cached reading and existing local tools.
- Hardened stream completion, partial-response handling, account changes, context-scoped caches and cancellation behavior.

## 0.5.1 — 2026-10-23

- Repaired model-check handling, account-specific model state and recovery for a saved ChatGPT account.
- Removed the duplicated primary connection controls and retained explicit account management.
- Improved cached feed paging, stable reading position and module defaults.
- Added controlled streaming/provider and native UI regression coverage; retained the live-account verification boundary.

## 0.5.0 — 2026-10-23

- Added a visual daily edition with publisher media, direct resource actions and a broader modular catalog.
- Added AI-assisted discovery plus public adapters for weather, GitHub and technical news; retained optional RSS fallback.
- Expanded module personalization, source metadata validation, media fallback and per-module context scope.
- Repaired browser-return navigation while keeping authorization data out of Android intents and preserving active app flows.

## 0.4.0 — 2026-10-23

- Added official Continue with ChatGPT for the local personal application, with system-browser PKCE sign-in, a bounded loopback callback, cryptographically verified identity and account-specific model discovery.
- Added encrypted per-account sessions, serialized refresh-token rotation, explicit account switching and sign-out, and one-time plan-use acknowledgement.
- Added subscription-specific streaming Responses requests. Only terminal completed responses are committed; partial text, incomplete streams and limits preserve cached reading. API billing is never selected automatically.
- Added plan-usage links, concise permission/eligibility/limit/retry states, and a collapsed separately billed API-key alternative. Figtree, existing design tokens and English/Kiswahili remain consistent.
- Added persisted automatic-AI pause after actionable plan errors while public feeds continue. The local attempted-request cap also counts connection checks.
- Kept credentials and local account identifiers out of portable backups; feed-backup restore/reset clears all local AI credentials. Existing app/signing identity, offline tools and native alarms remain compatible.
- Added security, protocol, account-race, provider-stream and actual native journey tests with synthetic fixtures. Real owner authorization, provider inference and physical phone behavior require installation-time verification.

## 0.3.0 — 2026-10-23

- Replaced the alarm-led dashboard with Feed, Modules, Saved and You; short onboarding, five-story initial briefing, source labels, saved reading and progressive detail.
- Added configurable news, opportunity, custom-watch and entertainment horoscope modules alongside the existing local tools.
- Added real public RSS/Atom retrieval, bounded HTTPS requests, cached reading, duplicate handling and partial-refresh preservation.
- Added owner-configured OpenAI connection, encrypted Android Keystore credentials, scoped personal context, source-grounded structured feed output and contextual questions.
- Added optional authenticated Python service with SQLite, provider isolation, request budgets, cached generation and scheduled preparation; no public service or push channel is pre-deployed.
- Added opt-in Android background refresh, quiet hours, per-module alert preferences and one feed notification per local day.
- Added private profile configuration, explicit context JSON import/export and separate feed backup without credentials. Public source excludes the personal profile and signing material.
- Preserved application identity, local records, math/QR/tap alarm runtime, focus and check-in behavior. Extended English/Kiswahili copy, native journey coverage and release documentation.

## 0.2.0 — 2026-10-22

- Renamed the product to MeBot while preserving the application/signing/data identity.
- Rebuilt all screens with a shared Figtree/raspberry/white design system, native vector artwork, floating navigation and concise localized copy.
- Replaced profile onboarding with one screen; removed the four-stage daily questionnaire. Mood and notes are optional and a visible next step can be accepted immediately.
- Added configurable Math/QR/Tap alarm dismissal, 1–5 random questions, three difficulties, persisted progress and atomic answer validation.
- Simplified alarm setup, including explicit save-off when precise scheduling access is missing.
- Preserved legacy alarm behavior and old backup support.
- Added native UI/state journeys, challenge tests, optional-mood backup coverage and updated architecture/design documentation.


## 0.1.0 — 2026-10-21

First installable native Android release.

- Added the central daily visit, automatic drafts, one visit per local date, a chosen outcome, actionable first step, and arrival/departure records.
- Added editable profile, language, theme, accent, module switches, and default focus duration.
- Added a persistent focus timer and saved completion history.
- Added native recurring and one-time alarms with capped snooze, gradual sound, vibration, QR checkpoint and follow-up, emergency stop, and reboot restoration.
- Added local JSON backup export, validated import with alarms disabled, and full data erasure.
- Added core, persistence, alarm lifecycle, and native Compose journey tests; build scripts; pinned wrapper; continuous verification workflow; and operational documentation.

Known release boundaries: no cloud sync, external AI service, widget, wearable integration, background focus-completion sound, or physical-device alarm qualification in this build environment. The app does not require any of these services for its implemented daily journey.
