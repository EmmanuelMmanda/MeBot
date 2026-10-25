# MeBot 0.8.2

Official Android release. New application identity: **`mebot.nguli.com`**, version code **13**. No iOS/multiplatform changes are included.

## Installation

This is a new package, not an update to `app.mynguli`. Install MeBot afresh, reconnect your provider and review preferences/alarms. Older experimental installations can be removed after preserving anything you need. System alarm registrations and protected credentials do not transfer automatically. Future official releases retain the new identity and certificate.

## Changes

- Provider-neutral AI connection: existing ChatGPT sign-in, OpenAI API, Claude API, Gemini API, OpenRouter PKCE/key, compatible personal inference and the existing personal service. Capabilities are explicit; no silent provider or billing fallback.
- Copy starter prompt works with any assistant that can produce the validated MeBot context schema. Onboarding interests already personalize the app; imports remain optional.
- AI request allowance is on AI connection, with one preserved consumed-request counter, presets, Custom and Unlimited. Provider limits still apply.
- Card-based background settings, selected-module refresh and progressive results. Finished modules commit immediately; reading-position protection uses Show new items.
- Software Updates performs automatic checks and optional Wi-Fi preparation. Package/version, bytes, checksum and signing certificate are checked before Android offers installation.
- Relevant / Not relevant are module-scoped recommendation signals with idempotent votes, decay, undo and separate reset. Reading-history deletion does not silently erase feedback.
- Clear reading history requires a confirmation explaining the scope. Ambiguous Restore Hidden Updates and Local Tools and Data shortcuts have been removed.
- Contextual dismissible setup notices, a skippable/replayable first-use guide, and all three daily tools in onboarding.
- Full-width anchored activity details popup. Stable summary introduction through loading, streaming and completion. Square response card prevents first-line corner clipping without changing unrelated cards.
- Real, credited city/research imagery and dated public-source captures replace the generic product screenshots. README, Start Here, guide and gallery are refreshed.
- Fixed a conversation draft-restoration race during asynchronous thread creation/recreation.

## Verification

Independent JDK17 / Gradle8.13 / AndroidSDK36 / BuildTools35.0.0 build:
**156 core + 440 Android-host + 145 service = 741 tests passed**, no failures, errors or skipped tests. Full release compilation succeeded. Release lint: **0 errors, 194 warnings, 1 hint**.

[Independent verification run](https://github.com/EmmanuelMmanda/MeBot/actions/runs/40170141233).

The official APK is signed privately with the established certificate. Every application ZIP entry is byte-identical to the tested unsigned candidate. Signing keys, provider credentials and personal profiles are excluded from source and release archives.

## Explicit limits

Live provider sign-ins/paid inference, real-device playback, Android file saving, installation approval and overnight alarm delivery have not been tested here. Provider eligibility and limits apply. Claude.ai subscriptions are not reused by third-party MeBot; use Anthropic API access. OpenRouter uses its own credits. The Gemini adapter uses supplied public sources, not Google Search grounding. Compatible servers may lack search, structured outputs or media features. The optional Python service remains an OpenAI-backed service.

Screenshots are native Android host captures of dated public content with controlled provider state, not live personal-account sessions or physical-phone photographs. The Dar es Salaam image is historical. Public file availability may change after checking; protected content is not bypassed. Resource downloads remain bounded to 20 MiB. Software update APKs have a separate 100 MiB ceiling and require Android's installation approval.
