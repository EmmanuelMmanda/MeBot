# MeBot v1 — Android + iOS migration decision

Status: **Approved direction / preparation only**. This branch does not claim an iOS build or change the released Android app. Baseline: official MeBot 0.8.1.

## Decision

Keep this repository and its release history. Introduce **Kotlin Multiplatform (KMP)** for domain/network/data logic and **Compose Multiplatform** for reusable UI, retaining native platform implementations where OS integration or quality requires it. Do not rewrite the functioning app in Flutter.

The existing `core/` is **Kotlin/JVM, not yet KMP**. Android `app/` owns Jetpack Compose UI, AlarmManager, AndroidX DataStore, Android Keystore, Android-specific browser sign-in and media/notification integration. Their platform dependencies must be separated intentionally; renaming folders is not a port.

## Identity and product

| Surface | v1 decision |
| --- | --- |
| User-facing name | **MeBot** / **MeBot Daily** (no MyNguli or Demo) |
| Website | **Not selected.** `mebot.nguli.com` is a package identifier, **not** a website address. Documentation hosting is an independent decision. |
| GitHub | **EmmanuelMmanda/MeBot**; no replacement repository |
| Android v1 application ID | **`mebot.nguli.com`** — the sole v1 production package; existing development installs can be discarded |
| iOS v1 bundle identifier | **`mebot.nguli.com`** — use when creating the actual Apple app record; no advance reservation milestone |
| Shared source namespace | **`mebot.nguli.com`** across new Kotlin and platform code; eliminate MyNguli names rather than carry legacy aliases |
| Version | Target `1.0.0`; do not tag before BOTH platform gates pass |

`mebot.nguli.com` is the **literal application/package identifier** selected by the owner, not an HTTP hostname for MeBot. The conventional reverse-domain ordering `com.nguli.mebot` is *not* the approved identifier. Android and iOS support the chosen characters, although Apple generally recommends reverse-DNS ordering. Verify the App ID when configuring actual iOS signing; no advance reservation is required.

**Pre-release reset decision (owner confirmed):** MeBot has **not been distributed to public users**. There is no existing production install base or public-user upgrade requirement. We may change the Android application ID, namespace, package paths, database/file names, component names, deep links and signing setup before the first v1 release. The owner accepts reinstalling test builds and resetting test data. Existing personal test content can be exported manually if wanted; compatibility import of historical MyNguli state is **not a v1 release gate**. Never modify or delete public historical Git tags or falsely relabel old binary packages.

**Renaming must be comprehensive:** do not leave misleading MyNguli branding, JVM packages, import statements, manifest component paths, `Theme.MyNguli`, service names, callback schemes, notification channels, backup configuration, auth redirect allowlists, tests, diagnostics, CI package assertions or release scripts in active v1 code. Rewrite references consistently in one controlled migration, validate with a full-text/manifest audit, and test sign-in, reboot alarms, notifications, downloads and upgrades of subsequent v1 versions.

## Complete identity replacement checklist

This is a **v1 refactor task**, not a change to the already published 0.8.1 branch.

- Change `applicationId`, Android `namespace`, Gradle package assumptions, Kotlin `package` statements/imports, test packages and generated entry points to `mebot.nguli.com`.
- Rename `MyNguliApplication`, `MyNguliViewModel`, themes/styles, file names and user-visible debug labels to MeBot equivalents. Keep semantics and behavior, not old names.
- Update manifest relative component names, direct-boot receivers/services, WorkManager initialization, foreground audio, alarm IDs, notification channel IDs, deep links/URI permissions, `FileProvider` authorities and on-device saved-state keys together.
- Register matching sign-in callback schemes and exact redirect URI shapes for the new package; validate browser return, account reconnect, session renewal and model selection.
- Define new secure-storage aliases, app-scoped preferences/cache identifiers, backup/data-extraction rules, local database names and portable export schema; do **not** attempt to read old app-private data automatically.
- Replace stale MyNguli names in active docs, scripts, screenshots where appropriate, privacy copy, static URL metadata, Play/App Store listings and CI assertions. Historical release records must remain truthful.
- Decide iOS team ID, App ID, capabilities and provisioning **when configuring iOS**, rather than running a separate early identifier-reservation task.
- Verify APK package with `aapt`, signing with `apksigner`, all active names with a repo-wide audit, and Android UI/alarm/network tests. Publish only after verified Android and iOS artifacts exist.

## Proposed structure

```
MeBot/
  androidApp/           # Existing app, migrated incrementally from app/
  iosApp/               # Swift/Xcode host, app lifecycle, iOS entitlements
  shared/
    domain/             # Modules, recommendations, context policies, alarm rules
    data/               # Feed repository, serialization, offline caching, migrations
    ai/                 # Provider adapters, streaming, status events, request priorities
    design/             # Semantic tokens, localization, common components
    features/           # Compose screens gradually extracted from Android UI
  platform/
    android/            # AlarmManager, Android Keystore, Android media/file save
    ios/                # AlarmKit, Keychain, AVKit, Photos/Files, background work
  service/              # Existing optional Python service
  docs/
  .github/workflows/
```

Incrementally move original code; use common source sets `commonMain`, `androidMain`, `iosMain` and corresponding tests. Avoid simultaneous broad changes to recommendation behavior and UI design during migration.

## Portable features

- Shared: feed catalog, personalization and scopes, relevance/freshness/diversity ranking, request budgets, module ordering, deduplication, recommendation rationale, search, validation, AI activity state machine and language selection.
- Adapted: HTTP clients, secure key storage, stream decoding, SQL/local persistence, image caches, links, downloads, media, sign-in browser redirects, push/background refresh and app lifecycle.
- UI: preserve MeBot visual tokens; migrate Compose screens one flow at a time and validate iOS keyboard, safe area, VoiceOver, back/gesture navigation, loading, empty/offline/error states, portrait and landscape.
- Privacy: do not merge child/module/private contexts automatically. Profile exports remain explicitly reviewable. Keys stay in Android Keystore or Apple Keychain, never JSON exports or app logs.
- AI: ChatGPT plan sign-in must be verified independently on iOS and Android against currently permitted account/model workflows; preserve explicit API-key alternative. No automatic switch to separately billed calls.

## Alarm parity is not automatic

Android retains its tested AlarmManager, foreground sound and math/QR dismissal. iOS 26+ provides **AlarmKit** for scheduled prominent alarms, consent, system UI and custom actions. It does **not** mean we can guarantee that users must finish a math/QR challenge before system dismissal. Prototype and verify the exact stop/snooze/challenge behavior against Apple APIs and review rules. If unavailable, show accurate capability differences in-app; do not fake parity. Older supported iOS versions can provide the feed and other modules, with an explicitly limited alarm mode.

## Release gates (no silent substitutions)

1. **Baseline lock:** Preserve the v0.8.1 tag and current tests for regression comparison, but make v1 a **clean new product identity**. No public-user migration, legacy keystore or old-signing continuity requirement.
2. **Core portability:** Convert `core` to `kotlin("multiplatform")` with Android/JVM + iOS targets. Remove JVM-specific dependencies from `commonMain`, retaining platform-specific implementations. Test domain rules on both targets.
3. **First iOS vertical slice:** A real Xcode project displays onboarding -> personalized offline feed -> sourced detail -> MeBot conversation, with persistence and accessible layout. Build/run in iOS Simulator using macOS and Xcode.
4. **AI/media and local data:** Validate streaming and a working signed-in request, verified source links, images, video embed/playback, PDF/document open/save, cache/refresh, background jobs, locale and account switching on iOS.
5. **Alarm and platform acceptance:** Deliver supported iOS AlarmKit features and honest fallback; physical iPhone testing, permissions, sound, lock-screen, process termination/restart, Apple review and device-specific accessibility.
6. **Release candidate:** Matching default topics/recommendations on both devices for controlled fixtures, no profile/key leakage, clean v1 onboarding and fresh authentication on both platforms, checked alarm scheduling and permissions, and documented iOS feature differences. CI for Android and macOS/iOS.
7. **Version 1.0:** Archive signed Android release and iOS/TestFlight build with checksums and screenshots; keep the documentation hostname separate from package identity; create immutable `v1.0.0` tag only after checks pass.

## Build and account prerequisites

iOS compile, simulator, provisioning, TestFlight and App Store operations require **macOS + Xcode**. GitHub-hosted macOS CI is an option. App Store/TestFlight distribution needs Apple Developer Program membership. Never put signing credentials in git, source archives, CI logs or public artifacts.

## v1 definition of done

- Android v1 has production application ID `mebot.nguli.com`, clean source namespace and manifest components, a fresh signing setup, and normal in-place update continuity **from v1 onward**. Reinstalling pre-release prototypes is acceptable.
- iOS v1 uses bundle ID `mebot.nguli.com` in its first configured Apple Developer app record and is tested on a real iPhone; there is no earlier iOS bundle or signing identity to migrate.
- Both have the same core feed, AI and personalization workflows with localized English/Kiswahili and accessible controls.
- At least one real end-to-end AI interaction and public-source fallback succeed on each platform.
- Media failure cannot block text; background jobs cannot block interactive requests.
- Alarm parity and iOS restrictions are explained and verified rather than assumed.
- Release history, screenshots, installation guides and clear versioned downloads are available.
- No private profiles, API keys, signing keys, or unlicensed media are published.

## References

- [Kotlin Multiplatform migration from an existing Compose app](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)
- [Kotlin Multiplatform FAQ](https://kotlinlang.org/docs/multiplatform/faq.html)
- [Apple AlarmKit](https://developer.apple.com/documentation/alarmkit)
- [Android app update identity](https://developer.android.com/google/play/app-updates)
- [Apple bundle identifier](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleidentifier)
