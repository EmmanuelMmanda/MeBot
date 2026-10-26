<!-- preview-085:start -->
> **0.8.5 candidate:** Custom interests, a simpler About you screen and clearer navigation; 878 local automated tests passed. [Current candidate guide](docs/RELEASE-0.8.5.md). The official download remains **0.8.2**. Earlier candidate release notes are preserved as history.
<!-- preview-085:end -->

<div align="center">
<img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo">
<h1>MeBot Daily</h1><p>Your topics, source-linked answers and optional everyday tools.</p>
<p><a href="https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/mebot-0.8.2.apk"><strong>Download APK</strong></a> · <a href="https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.2">Release files</a> · <a href="START-HERE.md">Start here</a> · <a href="docs/USER-GUIDE.md">User guide</a></p>
<p>Android 8.0+ · English &amp; Kiswahili · Kotlin + Jetpack Compose · Apache 2.0</p></div>

> **Official MeBot 0.8.2 · 16.1 MB · `mebot.nguli.com`.** This is a new installation identity, not an in-place update to `app.mynguli`. Reconnect accounts and review your settings. Future updates use **Software Updates**.

Choose interests during onboarding, read a sourced feed, search, ask follow-ups and keep useful resources. Public information and local tools stay available without AI. No personal starter profile is baked into the app.

## See the current app

<p align="center">
<a href="docs/screenshots/v082/feed.png"><img src="docs/screenshots/v082/feed.png" width="225" alt="Personalized feed containing dated public reporting and source links."></a>
<a href="docs/screenshots/v082/news.png"><img src="docs/screenshots/v082/news.png" width="225" alt="Tanzania news filter showing the Daily News report on Ubungo school furniture repairs."></a>
<a href="docs/screenshots/v082/detail-answer.png"><img src="docs/screenshots/v082/detail-answer.png" width="225" alt="Source-backed story introduction and a square, unclipped streaming answer surface."></a>
</p>

**Your first edition** · **News near you** · **Read and keep exploring**
<p align="center">
<a href="docs/screenshots/v082/detail-streaming.png"><img src="docs/screenshots/v082/detail-streaming.png" width="225" alt="Story details with an original photo, relevance controls and the stable summary introduction."></a>
<a href="docs/screenshots/v082/mebot.png"><img src="docs/screenshots/v082/mebot.png" width="225" alt="MeBot conversation about CRDB Bank Foundation with source and resource controls."></a>
<a href="docs/screenshots/v082/welcome.png"><img src="docs/screenshots/v082/welcome.png" width="225" alt="MeBot welcome with a licensed photograph of Dar es Salaam."></a>
</p>

**A clear start to each story** · **Ask MeBot** · **A familiar starting point**
<p align="center">
<a href="docs/screenshots/v082/interests.png"><img src="docs/screenshots/v082/interests.png" width="225" alt="Interactive MeBot interest tiles in onboarding."></a>
<a href="docs/screenshots/v082/daily-tools.png"><img src="docs/screenshots/v082/daily-tools.png" width="225" alt="Alarm, Focus and Daily Check-in presented together in onboarding."></a>
<a href="docs/screenshots/v082/modules.png"><img src="docs/screenshots/v082/modules.png" width="225" alt="MeBot module catalog with searchable categories and module controls."></a>
</p>

**Choose what matters** · **Three optional daily tools** · **Explore the catalog**
<p align="center">
<a href="docs/screenshots/v082/providers.png"><img src="docs/screenshots/v082/providers.png" width="225" alt="AI connection with provider choices and capability-specific setup."></a>
<a href="docs/screenshots/v082/usage.png"><img src="docs/screenshots/v082/usage.png" width="225" alt="AI request allowance alongside provider connection settings."></a>
<a href="docs/screenshots/v082/background.png"><img src="docs/screenshots/v082/background.png" width="225" alt="Card-based background settings for timing, network conditions, notifications and preparation."></a>
</p>

**Choose your connection** · **Keep usage in view** · **Set the pace**
<p align="center">
<a href="docs/screenshots/v082/software-updates.png"><img src="docs/screenshots/v082/software-updates.png" width="225" alt="Software Updates screen showing automatic-check controls and release state."></a>
<a href="docs/screenshots/v082/my-mebot.png"><img src="docs/screenshots/v082/my-mebot.png" width="225" alt="My MeBot area with setup, guide and software update actions."></a>
<a href="docs/screenshots/v082/learning.png"><img src="docs/screenshots/v082/learning.png" width="225" alt="Learning module showing a public NASA resource about RS-25 tests at Stennis."></a>
</p>

**Software Updates** · **Your own setup** · **Discover beyond headlines**
<p align="center">
<a href="docs/screenshots/v082/about.png"><img src="docs/screenshots/v082/about.png" width="225" alt="About screen with app identity, real imagery, credits and open-source information."></a>
</p>

**About MeBot**

Captured 25 October 2026 from the actual Android UI. Reporting and photographs have recorded public sources; provider/update states are controlled for testing. These are **not live account sessions or physical-device photographs**. [Sources and capture notes](docs/screenshots/v082/README.md) · [Image credits](docs/IMAGE-CREDITS.md).

## Choose your AI connection

| Connection | What it means |
| --- | --- |
| ChatGPT | Existing provider-authorized sign-in for eligible accounts; account/model policy applies. No access to ChatGPT memory or chats is implied. |
| OpenAI API | Explicit API key with separate API billing. |
| Claude | Anthropic API key. Claude.ai subscription credentials are not used by third-party MeBot. |
| Gemini | Gemini API key. This adapter uses the public source material supplied by MeBot; it does not claim native Google Search grounding. |
| OpenRouter | PKCE authorization with a one-time code return or API key; uses OpenRouter credits, not an imported Claude subscription. |
| Personal server | Explicit OpenAI-compatible endpoint and model. Features depend on the endpoint; a saved key is never sent over HTTP. |
| Personal service | Optional self-hosted Python service. The service currently uses its own OpenAI API adapter. |

Provider selection is explicit. No automatic switching to a separately billed provider. Model lists, completed-response checks, streaming support and source discovery follow each adapter’s capabilities. Provider credentials stay outside portable exports.

**Copy starter prompt** prepares the exact preferences JSON schema for any suitable assistant. Review imports before applying. Onboarding interests already count as personalization.

## What is included

| Area | Behavior |
| --- | --- |
| Feed | Completed modules publish immediately; new results wait behind Show new items while you read. Cached content survives partial failures. |
| Feedback | Relevant / Not relevant improves recommendations within the module, with undo, decay and an independent reset. Not social likes. |
| Item detail | Stable introduction, unclipped square answer surface, streaming explanations and grouped sources/media/files. |
| Activity | One fixed top panel with an anchor-width popover; real events and provider-published summaries, never invented thoughts. |
| Connection | Provider and model choice, one persisted request budget, presets/custom/unlimited and clear capability limits. |
| Background | Timing, preparation batches, Wi-Fi/battery conditions, notifications and quiet hours in actionable cards. |
| Software Updates | Automatic checks and optional Wi-Fi preparation, verified package/version/certificate/hash, and Android’s installer approval. |
| Guidance | One relevant dismissible setup notice, a short first-use tour and replay from Help. |
| Daily tools | Alarm, Focus and Daily Check-in; independent of AI. |
| Data | Clear reading history explains its scope before confirmation; saved items, conversations and relevance feedback are separate. |

## Install and use

[Download the official APK](https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/mebot-0.8.2.apk). The new package installs separately from pre-0.8.2 development builds. Choose interests, connect a provider when ready, then open Feed. Test any configured alarm on your phone before overnight reliance. [Start here](START-HERE.md).

## Verification and release files

[Verification report](https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/mebot-0.8.2-verification.txt) · [Source](https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/mebot-0.8.2-source.zip) · [Resources](https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/mebot-0.8.2-resources.zip) · [Checksums](https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.2/SHA256SUMS.txt).

Tests use controlled providers and native Android host journeys. Real account eligibility, physical playback, file-picker behavior and overnight alarms need device checks. Historical releases are preserved. No private profile or signing key is published.

## Build

JDK **17**, Gradle **8.13**, Android SDK **36**, Build Tools **35.0.0**.

```sh
git clone https://github.com/EmmanuelMmanda/MeBot.git
cd MeBot
git switch develop
python3 scripts/prepare-build.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

The preparation script retrieves pinned typography and generates original alarm tones. Release signing uses private `MEBOT_*` environment values; debug builds need no key. Use `mebot.buildJdk=21` only for a local JDK21 environment; official CI uses17. Source archives intentionally omit font binaries and build outputs.

```sh
cd service
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

## Project structure

`core/` contains domain models, validation, feedback and recommendation policy. `app/` contains Compose UI, Android lifecycle, providers, local tools, secure storage and software updates. `service/` is an optional backend. `docs/` includes illustrated guides, capture provenance and the static website. `.github/` contains verification and publication workflows.

Work on develop, verify, then promote to main. iOS/multiplatform implementation is **not included** in 0.8.2.

[Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Privacy](docs/PRIVACY.md) · [Architecture](docs/ARCHITECTURE.md) · [Build guide](docs/BUILD.md) · [Complete change tracker](docs/RELEASE-TRACKER-0.8.2.md).

## License

[Apache 2.0](LICENSE). Third-party material retains its own [notices](NOTICE) and [image credits](docs/IMAGE-CREDITS.md). MeBot is independent and is not endorsed by OpenAI, Anthropic, Google or any publisher. Documentation hosting is independent of the package identifier.
