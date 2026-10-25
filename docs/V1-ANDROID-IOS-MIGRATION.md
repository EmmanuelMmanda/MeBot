# MeBot v1 — Android + iOS migration decision

Status: **Approved direction / preparation only**. This branch does not claim an iOS build or change the released Android app. Baseline: official MeBot 0.8.1.

## Decision

Keep this repository and its release history. Introduce **Kotlin Multiplatform (KMP)** for domain/network/data logic and **Compose Multiplatform** for reusable UI, retaining native platform implementations where OS integration or quality requires it. Do not rewrite the functioning app in Flutter.

The existing `core/` is **Kotlin/JVM, not yet KMP**. Android `app/` owns Jetpack Compose UI, AlarmManager, AndroidX DataStore, Android Keystore, Android-specific browser sign-in and media/notification integration. Their platform dependencies must be separated intentionally; renaming folders is not a port.

## Identity and product

| Surface | v1 decision |
| --- | --- |
| User-facing name | **MeBot** / **MeBot Daily** (no MyNguli or Demo) |
| Product website | **https://mebot.nguli.com/**, only after DNS/ownership and TLS are verified |
| GitHub | **EmmanuelMmanda/MeBot**; no replacement repository |
| Stable Android application ID | **`app.mynguli`** (must remain for in-place upgrades) |
| New iOS bundle ID | **`com.nguli.mebot`** (reserve before first App Store Connect upload) |
| Shared source namespace | Gradually adopt `com.nguli.mebot` where safe; do **not** couple namespace rename to Android application ID |
| Version | Target `1.0.0`; do not tag before BOTH platform gates pass |

The domain is a website, not an app's visible name, and is not the Android application ID. If a new Android application ID is forced, existing installations cannot receive an in-place update, and a documented export/import migration is required. Avoid that.

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

1. **Baseline lock:** Record v0.8.1 Git tag, signing fingerprint, app ID, current test results and data migration fixtures. Ensure Android build and alarm tests remain green.
2. **Core portability:** Convert `core` to `kotlin("multiplatform")` with Android/JVM + iOS targets. Remove JVM-specific dependencies from `commonMain`, retaining platform-specific implementations. Test domain rules on both targets.
3. **First iOS vertical slice:** A real Xcode project displays onboarding -> personalized offline feed -> sourced detail -> MeBot conversation, with persistence and accessible layout. Build/run in iOS Simulator using macOS and Xcode.
4. **AI/media and local data:** Validate streaming and a working signed-in request, verified source links, images, video embed/playback, PDF/document open/save, cache/refresh, background jobs, locale and account switching on iOS.
5. **Alarm and platform acceptance:** Deliver supported iOS AlarmKit features and honest fallback; physical iPhone testing, permissions, sound, lock-screen, process termination/restart, Apple review and device-specific accessibility.
6. **Release candidate:** Matching default topics/recommendations on both devices for controlled fixtures, no profile/key leakage, app update migration on Android, no lost saved items, and documented iOS onboarding and feature differences. CI for Android and macOS/iOS.
7. **Version 1.0:** Archive signed Android release and iOS/TestFlight build with checksums and screenshots; set documentation website and repository About URL only after public URL is live; create immutable `v1.0.0` tag only after checks pass.

## Build and account prerequisites

iOS compile, simulator, provisioning, TestFlight and App Store operations require **macOS + Xcode**. GitHub-hosted macOS CI is an option. App Store/TestFlight distribution needs Apple Developer Program membership. Never put signing credentials in git, source archives, CI logs or public artifacts.

## v1 definition of done

- Android v1 updates the installed `app.mynguli` signed package without data loss.
- iOS v1 is independently installed as `com.nguli.mebot`, tested on a real iPhone.
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
