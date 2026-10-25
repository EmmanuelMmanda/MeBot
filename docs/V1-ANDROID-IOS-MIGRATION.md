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
| Android v1 application ID | **`mebot.nguli.com`** — a new application, installed alongside legacy `app.mynguli` |
| iOS v1 bundle identifier | **`mebot.nguli.com`** — validate availability and register before first App Store Connect upload |
| Shared source namespace | **`mebot.nguli.com`** for new shared Kotlin code; migrate source packages separately from legacy runtime storage and component identifiers |
| Version | Target `1.0.0`; do not tag before BOTH platform gates pass |

`mebot.nguli.com` is the **literal application/package identifier** selected by the owner, not an HTTP hostname for MeBot. The conventional reverse-domain ordering `com.nguli.mebot` is *not* the approved identifier. Android and iOS accept the characters used here, although Apple recommends reverse-DNS ordering. Validate final iOS App ID registration before shipping.

**Migration consequence:** `mebot.nguli.com` is not an update to `app.mynguli`. Existing Android users must retain their original installation while exporting selected personal data and importing it into v1. User-controlled backups cannot automatically transfer Android Keystore credentials, active accounts, OS alarm registrations, or all protected operational state. Reconnect accounts, review alarm plans and permissions, and test delivery. Never silently uninstall or delete the legacy app.

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

1. **Baseline lock:** Record v0.8.1 Git tag, signing fingerprint, app ID, current test results and data migration fixtures. Ensure Android build and alarm tests remain green, and preserve legacy backup compatibility.
2. **Core portability:** Convert `core` to `kotlin("multiplatform")` with Android/JVM + iOS targets. Remove JVM-specific dependencies from `commonMain`, retaining platform-specific implementations. Test domain rules on both targets.
3. **First iOS vertical slice:** A real Xcode project displays onboarding -> personalized offline feed -> sourced detail -> MeBot conversation, with persistence and accessible layout. Build/run in iOS Simulator using macOS and Xcode.
4. **AI/media and local data:** Validate streaming and a working signed-in request, verified source links, images, video embed/playback, PDF/document open/save, cache/refresh, background jobs, locale and account switching on iOS.
5. **Alarm and platform acceptance:** Deliver supported iOS AlarmKit features and honest fallback; physical iPhone testing, permissions, sound, lock-screen, process termination/restart, Apple review and device-specific accessibility.
6. **Release candidate:** Matching default topics/recommendations on both devices for controlled fixtures, no profile/key leakage, **explicit Android legacy-to-v1 export/import migration**, no lost portable saved items, verified reauthorization of alarms/accounts, and documented iOS onboarding and feature differences. CI for Android and macOS/iOS.
7. **Version 1.0:** Archive signed Android release and iOS/TestFlight build with checksums and screenshots; keep the documentation hostname separate from package identity; create immutable `v1.0.0` tag only after checks pass.

## Build and account prerequisites

iOS compile, simulator, provisioning, TestFlight and App Store operations require **macOS + Xcode**. GitHub-hosted macOS CI is an option. App Store/TestFlight distribution needs Apple Developer Program membership. Never put signing credentials in git, source archives, CI logs or public artifacts.

## v1 definition of done

- Android v1 installs independently as `mebot.nguli.com`; tested export/import transfers allowed data from legacy `app.mynguli`, without uninstalling the existing app or promising automatic token/alarm transfer.
- iOS v1 is independently installed as `mebot.nguli.com`, registered in Apple Developer and tested on a real iPhone.
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
