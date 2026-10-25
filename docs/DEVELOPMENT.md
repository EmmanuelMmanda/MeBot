# Developing MeBot

MeBot is a native Kotlin/Jetpack Compose Android app, an Android-independent Kotlin domain module, and an optional Python service. The public source build needs no personal profile, provider key, signing key or deployed backend. Local tools and public feed retrieval work without connecting AI.

Start with [AGENTS.md](../AGENTS.md), the [user guide](USER-GUIDE.md) and [architecture](ARCHITECTURE.md). This guide covers a development workstation, implementation boundaries and changes to the product. [BUILD.md](BUILD.md) gives additional build and device acceptance detail; [CONTRIBUTING.md](../CONTRIBUTING.md) describes review expectations.

## Repository map

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/mebot/nguli/com/MainActivity.kt` | Five-tab navigation, nested routes, system/browser return and lifecycle wiring. |
| `app/src/main/java/mebot/nguli/com/ui/` | Local-tool screens, shared Compose components, theme and design tokens. |
| `app/src/main/java/mebot/nguli/com/ui/feed/` | Feed, catalog/editor, details, AI workspace, connection/context setup, background settings and FAQ. |
| `app/src/main/java/mebot/nguli/com/feed/` | Feed state orchestration, durable repository, request engine, discovery, source media, budgets and background work. |
| `app/src/main/java/mebot/nguli/com/feed/auth/` | Official ChatGPT authorization, verified accounts, renewable encrypted sessions and model catalog. |
| `app/src/main/java/mebot/nguli/com/alarm/` | Independent native scheduling, operational sessions, ringing, audio/file access and offline speech. |
| `app/src/main/java/mebot/nguli/com/data/` | Local-tool repository and backup integration. |
| `core/src/main/kotlin/mebot/nguli/com/core/` | Typed schemas, validation, module defaults/preferences, ranking, context rules, conversation limits, alarms and local-tool logic. |
| `app/src/main/res/values/`, `values-sw/` | English and Kiswahili interface resources. |
| `app/src/test/`, `core/src/test/` | Host-side integration/native Compose tests and pure domain tests. |
| `service/` | Optional single-person FastAPI/SQLite service, tests, synthetic requests and deployment instructions. |
| `docs/` | User/developer/protocol documentation, screenshots and the static documentation site. |
| `scripts/` | Repeatable verification/build commands and the original alarm-tone generator. |

The `mebot.nguli.com` namespace and release application ID are retained for compatibility with earlier installations. The product name is MeBot. A package rename would create a different Android installation and change browser-return identity; it is not a cosmetic rename.

## Workstation and toolchain

Use the checked-in Gradle wrapper rather than a separately installed Gradle version.

| Component | Pinned version |
| --- | --- |
| Java | JDK 17 |
| Gradle wrapper | 8.13, with distribution checksum |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / serialization / Compose compiler plugin | 2.2.21 |
| Android compile and target SDK | 36 |
| Minimum Android SDK | 26, Android 8.0 |
| Android SDK Build Tools | 35.0.0, AGP's default for this configuration |
| Compose BOM | 2025.12.00 |
| WorkManager | 2.11.2 |
| Optional service | Python 3.12 or later; dependencies pinned in its requirements files |

The source of truth is [root build configuration](../build.gradle.kts), [app configuration](../app/build.gradle.kts), [core configuration](../core/build.gradle.kts) and [wrapper properties](../gradle/wrapper/gradle-wrapper.properties). SDK compatibility is documented in the [official AGP release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

Install Android Studio or Android SDK command-line tools. Configure `JAVA_HOME` and `ANDROID_HOME` for your own workstation, then install:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
sdkmanager --licenses
```

Review and accept the applicable SDK licenses. Android Studio's SDK Manager can perform the same installation. An untracked `local.properties` may contain your local `sdk.dir`; never commit that file, machine-specific paths, proxy credentials or certificate-store secrets.

Clone the repository, or extract the public source archive:

```sh
git clone https://github.com/EmmanuelMmanda/MeBot.git
cd MeBot
git switch develop
```

The first build downloads public dependencies. Dependency resolution requires network access; later builds can use the local Gradle cache. A failure to fetch a dependency is different from a source or test failure. Keep any required network configuration outside the repository.

## Build, install and verify

Run the required Android gate from the repository root:

```sh
./scripts/build.sh verify
```

It executes:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows can use the same tasks with the batch wrapper:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For an iteration that only needs an APK, `./scripts/build.sh debug` assembles the debug variant. The review gate still applies before merging a change. Install the debug result on a connected emulator or development device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug uses `mebot.nguli.com.debug` and a `-debug` version suffix. Release uses `mebot.nguli.com`. They can coexist; debug is not an in-place update of release. No live account or provider key is required by the automated tests.

| Output | Location |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Core/App HTML test reports | `core/build/reports/tests/`, `app/build/reports/tests/` |
| JUnit XML | Each module's `build/test-results/` |
| Android lint | `app/build/reports/lint-results-*.html` and XML |
| Native rendered UI artifacts | `app/build/outputs/screenshots/` |

Native Compose journeys use Robolectric's native graphics, real app/ViewModel/repository wiring where appropriate, and synthetic auth/provider fixtures. They exercise navigation, persistence, cancellation, model-access checks, local tools and accessible compact layouts. Screenshots are review artifacts, not a pixel-baseline test suite. They must not be presented as screenshots of a live user account or actual provider success.

Keep focused checks tied to meaningful risks. A protocol parser, atomic import, rotating credential, scheduling transition or lifecycle cancellation needs tests for failure as well as success. A minor copy edit does not need tests that repeat its implementation. Never introduce provider billing into a test suite.

The [CI workflow](../.github/workflows/android.yml) verifies Android and the optional service in separate jobs for pull requests and configured development/release branches. It uploads a debug APK and reports. It does not sign a release, deploy a backend, publish a store listing or prove physical-device alarm behavior. Third-party actions are pinned to immutable commit hashes.

## Configuration and secrets

### Public defaults and personal context

The reusable defaults are in `core`. Personal state belongs in typed persisted models, not Kotlin constants. A clean source build starts without a person's name, location, history or API credential.

The preferred personalization flow is runtime import under **You → My context**, using `mebot-context-v1`. The [empty example](context-example.json) and [user guide prompt](USER-GUIDE.md#your-preferences) describe this contract. Import validates and reviews the replacement; `modules: []` preserves module choices.

An optional private build may supply `app/src/main/assets/mebot-profile.json`. It is a one-time editable seed, excluded from public source. Anyone receiving a personalized APK can inspect its assets, so it must not contain keys or confidential documents. A reset does not reapply the seed. Prefer the runtime import flow for public distributions.

### AI routes

| Route | Configuration boundary |
| --- | --- |
| ChatGPT | Official browser authorization, loopback callback, verified identity and account-returned models. No client secret embedded in the APK. |
| Direct OpenAI | User-supplied key in the Android Keystore-backed vault; editable model field; public Responses API. |
| Personal service | Explicit public HTTPS URL and separate service token; provider key remains in the operator's server environment. |

These are distinct credential and billing routes. The selected route changes only after its real inference check succeeds. Keep failed/cancelled changes from replacing the working connection. Never implement silent fallback from ChatGPT to API billing, send OAuth tokens to the personal service, or ship a shared developer API key.

The ChatGPT route uses the documented public integration rather than private website/backend endpoints. OpenAI's [quickstart](https://developers.openai.com/siwc/quickstart) documents eligible Plus/Pro plan use in participating local/open-source apps with separate inference consent. The account's authenticated `GET /v1/models` determines visible models; `POST /v1/responses` uses `store: false` and `stream: true`. These provider facts were checked on 2026-10-23 and are not guarantees of eligibility for every account or distribution.

This route does not expose ChatGPT conversations, saved memory, private connectors, image-generation/audio/video/Files APIs or hosted MCP. Web-search access is conditional. Android voice features and reviewed text attached to a request are separate from those APIs. Review [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [current limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) before extending it. See [CHATGPT-CONNECTION.md](CHATGPT-CONNECTION.md) for scopes, identity validation, session rotation, safe diagnostics, model discovery and inference framing.

### Optional service

Run its own tests from `service/` in an isolated environment:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pip check
.venv/bin/python -m pytest -q --junitxml=test-results.xml
```

The tests use a local fake HTTP provider. Production setup requires a private `OPENAI_API_KEY`, a different `MEBOT_SERVICE_TOKEN`, protected persistent storage and HTTPS routing. The supplied Docker Compose configuration listens on host loopback; the Android app requires an externally reachable public HTTPS hostname. A local test deployment is not automatically accessible from Android.

The service is one person per deployment, with one Bearer-token boundary, not a multi-tenant account service. Its model is `MEBOT_MODEL`; an app request naming another model is rejected. Its default attempted-call cap is 48 per UTC day, independent of the app's local-day default of 28. Its API currently returns completed answers, not streaming or voice. Scheduled preparation does not imply an installed push channel. Use the [service guide](../service/README.md) for all environment settings, endpoint contracts, retention and deployment checks.

## Implementation boundaries

### State, transactions and lifecycle

`AppState` contains local tools; `FeedState` contains feed/context/conversations and nonsecret connection selection. Their repositories are separate. Operational alarm state uses a separate device-protected store so scheduled occurrences can be restored after boot without copying diary/profile records there.

Use a repository transform against the current state for a write. Do not persist an old full UI snapshot over a concurrent change. Persist user intent before scheduling or launching work. Pending operations must be invalidated when credentials, context, configuration or reset generation change. A late callback must not re-create erased data or commit a result for a no-longer-selected account.

Context import, feed restore and local-tool restore have different replacement semantics. Validate the complete input and supported format/version before replacement; a parse failure must not erase a store. Preserve reviewed-import cancellation and stale-preview guards. Feed restore disables delivery and clears credentials after durable state replacement. Local-tool restore disables imported alarms and clears active focus state. Keep migrations compatible with the documented formats in [FEED-CONTRACT.md](FEED-CONTRACT.md) and [BackupCodec.kt](../core/src/main/kotlin/mebot/nguli/com/core/BackupCodec.kt).

### Personalization and source evidence

Module purpose/default guidance is reusable editorial configuration. It grants no access to extra personal data. Only confirmed, relevant notes are submitted for the selected kind, with at most 32 per Android module request. A general AI thread uses no saved personal context until a module or story supplies the scope. Custom watches share the `watch` kind; be explicit about that in the interface.

Treat fetched pages, attached files, prior messages and user-configured focus as untrusted data. They cannot grant tools, authorize unrelated actions, broaden context or change system requirements. Factual main links must occur in provider search-source metadata or validated URL citations. URLs merely written in prose do not establish provenance. This check does not prove every statement's truth, so retain original links and supported uncertainty. Horoscope remains optional entertainment.

Publisher media is obtained from the cited page's metadata and validated retrieval paths, not invented model output. Preserve HTTPS/public-address protections, bounded downloads, timeouts and redirect handling. Do not forward authorization to a redirect destination. Review any new source adapter's host policy and public-data contract.

### Streaming, cache and budgets

Direct API and ChatGPT explanations/conversations use real streaming. Visible deltas are provisional; only a completed, valid terminal response may become a saved answer or reusable explanation. Do not convert an interrupted stream, HTTP 200 alone, or an incomplete response into success. The subscription and API transports have different supported request controls; follow their existing adapters rather than copying parameters between them.

The explanation fingerprint includes source content, scoped notes, module settings, language, region/timezone and connection/model. Reuse requires a matching fingerprint and a result younger than six hours. Keep the 24-entry completed cache bounded. Preserve prior text on refresh/provider errors.

Reserve attempted requests atomically before upstream use. `RequestBudget.UNLIMITED` is zero; custom user-entered limits are positive, and existing chosen values survive upgrades. Unlimited still records usage and does not disable upstream quotas, provider billing or a service cap. Do not count local pagination, public-source retrieval or cache reuse as inference.

### Alarm runtime and audio

Keep AlarmManager/foreground ringing independent of AI, network and WorkManager feed delivery. Bind actions to a specific occurrence/session and challenge question. Persist math progress, snooze count and deadlines. Old notification actions, callbacks and follow-ups must not affect a newer alarm session. Device tests remain required after changing permissions, scheduling, direct-boot or audio-route behavior.

Alarm audio uses bundled resources, a selected system URI or a selected document URI. Retain only the document READ grants acquired by this picker while referenced by plans, operational occurrences or protected editor drafts. A missing selection falls back to bundled Pulse; old plans lacking new fields retain Classic. Preview uses current phone alarm volume, while scheduled ringing uses saved volume/ramp. Offline TTS supplements the tone and must never be required for audible ringing. Stop preview/speech on session or lifecycle exit. See [alarm challenges](ALARM_CHALLENGES.md) and [alarm audio](ALARM-AUDIO.md).

Original tones are reproducible with the Python standard library:

```sh
python3 scripts/generate-alarm-tones.py --check
```

Run the generator without `--check` only when deliberately changing those assets. It does not replace the legacy tone.

## Add a module or source

A module is a typed discovery configuration with a complete UI and request contract. Adding a card alone is insufficient.

1. Add a reusable kind/preset in [ModuleCatalog](../core/src/main/kotlin/mebot/nguli/com/core/FeedModels.kt): category, purpose, editorial requirements, output type and whether it has a real public adapter. Keep defaults general and supported by implemented retrieval.
2. Add validated kind-specific choices in [ModulePreferences.kt](../core/src/main/kotlin/mebot/nguli/com/core/ModulePreferences.kt). Preserve empty-map compatibility. Only persist explicit, typed choice values; do not accept arbitrary privileged instruction fields.
3. Add English and Kiswahili name, description, default-focus and preference resources. Wire icons, grouping and editor controls. Verify catalog discovery, disabled filtering, module settings, context selection, ordering and feed/detail actions on a narrow screen with larger text.
4. Add a direct public adapter only when the endpoint is intended for public retrieval. Otherwise use the explicit AI contract. Keep factual URL evidence, safe networking, errors and partial-refresh preservation intact. If the Python service supports the new kind, update its validated schema/default guidance and tests too.
5. Add meaningful core/provider tests for validation, defaults, scope isolation and source evidence. Add a native journey only where it verifies a distinct user or lifecycle risk. Use fixtures and local fake providers.
6. Update the user module table, FAQ where relevant, protocol docs, source/media docs and changelog. Catalog upgrades should add new kinds paused without rewriting saved choices.

For a new private connector, first define the user authorization, minimal scopes, credential lifecycle, data destination and disconnect behavior. Existing AI access or a context note is not permission to access another account. A connector or telemetry feature requires its own product/privacy decision.

## UI and accessibility changes

Use the tokens, components and typography in [Theme.kt](../app/src/main/java/mebot/nguli/com/ui/Theme.kt) and [Components.kt](../app/src/main/java/mebot/nguli/com/ui/Components.kt), following [DESIGN.md](DESIGN.md). Keep the five main destinations and short default feed intact unless the change explicitly calls for a navigation redesign.

All interface copy belongs in both resource locales. Preserve minimum touch targets, semantics, heading labels, contrast, large-text wrapping, keyboard/IME reachability and reduced-motion behavior. A gesture must have an accessible alternative when it is essential, such as module reordering. Loading, saving, empty, offline, denied-permission and failure states are part of the feature.

Use `rememberSaveable` for appropriate nonsecret drafts and restore navigation/state deliberately. Do not save keys, OAuth authorization URLs or pending token material in UI state. In composition, observe configuration through `LocalConfiguration`/appropriate composition locals rather than unobserved context resource reads.

Update native screenshots only for a real behavior/design change and review the rendered files. Capture a dialog's own native window when the main activity does not contain it. Do not weaken functional assertions to make an image capture pass. Keep examples synthetic and mark illustrative onboarding content as examples.

## Signed release and public source

The v0.6.1 source uses `versionCode = 8`. Every distributed update needs a higher version code and the correct existing signing identity. A newly generated key cannot update an existing release installation signed by another key.

Supply signing settings through your secret manager or CI environment:

| Variable | Meaning |
| --- | --- |
| `MEBOT_KEYSTORE` | Path to the private keystore outside public source. |
| `MEBOT_KEYSTORE_PASSWORD` | Keystore password. |
| `MEBOT_KEY_ALIAS` | Alias; defaults to `mynguli` for compatibility. |
| `MEBOT_KEY_PASSWORD` | Optional key password; otherwise the store password. |

Legacy `MYNGULI_*` equivalents remain accepted by the build, but use `MEBOT_*` for new configuration. For your own independent signing identity, set `MEBOT_KEYSTORE` to a protected destination and create the key interactively:

```sh
keytool -genkeypair -alias mynguli -keyalg RSA -keysize 3072 \
  -validity 10000 -storetype PKCS12 -keystore "$MEBOT_KEYSTORE"
```

Back up the key and passwords privately. Do not use that command to replace the key of an existing release you need to update. Once signing configuration is present, run:

```sh
./scripts/build.sh release
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The release script requires signing configuration, runs core/app tests and release lint, then assembles. Direct Gradle assembly without signing variables may produce an unsigned artifact; do not label it an installable signed release. Android's [signing documentation](https://developer.android.com/studio/publish/app-signing) explains upgrade identity.

Before publication, review the actual diff and archive contents, not just ignore rules. Exclude personal profile assets, private exports, API/OAuth credentials, signing stores, local SDK/proxy configuration, service databases, virtual environments, caches and generated build directories from source archives. Publish a signed APK and public certificate/checksum separately when intended; never include the signing key. Retain [LICENSE](../LICENSE), [NOTICE](../NOTICE) and third-party notices.

Record the required test/lint/build results and device/live-provider gaps in [VERIFICATION.md](VERIFICATION.md). Install a signed update on a controlled device and verify persistence, browser consent/return, a real model request and the alarm routes in [BUILD.md](BUILD.md). Host fixtures do not establish live OpenAI eligibility, a deployed service, physical Keystore behavior, or overnight ringing reliability.

## Documentation changes

Keep user instructions tied to visible controls and developer documentation tied to executable contracts. The Markdown guides remain useful without JavaScript; the static site links to the repository and these references. Use relative documentation links, descriptive image text and synthetic native screenshots. Do not turn a fixture into a claim of a live provider result.

When changing account integration, verify against current official OpenAI documentation and distinguish implemented behavior, provider requirements, and physically/live-tested outcomes. When changing a schema, update examples and both Android/service validation where applicable. When changing a setting or label, update the relevant user/HTML guide and FAQ rather than leaving a second obsolete instruction path.
