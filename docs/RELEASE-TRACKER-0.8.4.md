# MeBot 0.8.4 candidate acceptance

Incremental source on the verified 0.8.3 checkpoint `cee6b59055859afacb61dc028c5f292460679c29`, itself based on official 0.8.2. Version code15; production package remains `mebot.nguli.com`. The 0.8.3 branch, APKs, source archive and verification receipt are preserved separately.

## Requested refinements

| Area | Implemented behavior and checks |
| --- | --- |
| Quiet, compact stars | Icon-only star beside the bookmark in story action rows and detail controls. 48dp targets, selected state and localized accessibility labels; follow settings remain in Saved → Following. No large Starred chip or management text on cards. |
| Fullscreen images | Native full-window image view, bounded pinch/pan, reset, retry and close/Back. Known publisher images work without a filename extension. Explicit retry clears only that image's negative-cache entry; automatic loading remains throttled. |
| Fullscreen playback | Direct-file media can expand and retain seek position. YouTube uses its own fullscreen control and native custom-view shell, retaining the same WebView. No destructive app transition or invented forced quality setting. |
| Capability clarity | Quality/playback controls depend on the source and provider. Single-source files have no fabricated resolution selector. Real-device playback remains an acceptance gap. |
| First-run and help | Completed tour opens the feed without a second automatic guide. Help replays updated star/save/media guidance. While preferences save, Back and language changes cannot mutate the submitted tour state; failure supports retry. |
| Library comprehension | Saved and Following have distinct empty-state instructions. Search no longer promises nonexistent user notes. Reserved collection names and restored drafts retain 0.8.3 regressions. |
| Wording and accessibility | Corrected plain-language Kiswahili preferences and neutral count labels. Retain tested native theme/large-text behavior rather than redesigning working alarm tools. |

## Verification status

Final complete Gradle gate succeeded on 25 October 2026 in **4m22s** using JDK17.0.21 / Gradle8.13 / SDK36 / Build Tools35.0.0.

- **181 core +479 Android host +169 service tests passed (829 total)**; zero failures, errors or skips. The service rerun took1.99s with one upstream Starlette/httpx deprecation warning.
- Debug and release assemblies passed. Debug/release lint passed with **zero errors**;215/216 warnings respectively and one hint each, not warning-free.
- Package/version/minSDK/targetSDK and ZIP alignment verified. Preview: `mebot.nguli.com.debug`,0.8.4-debug/code15. Unsigned release: `mebot.nguli.com`,0.8.4/code15. Both minSDK26 /targetSDK36. Preview certificate matches the0.8.3 debug checkpoint, not the official release identity.
- Regression coverage includes icon placement/48dp targets/selected accessibility, tab-specific empty guidance, collection restoration, onboarding pending-save Back/recovery, updated EN/SW guide, suffixless publisher images, explicit per-URL image retry, fullscreen close/Back/cancellation and YouTube retained WebView/custom-view callback.
- Screenshots are actual native host renders with public or explicitly sample fixtures, not phone execution or live-provider acceptance. Fullscreen screenshot uses a visibly labeled local image fixture; no real video-playback screenshot is claimed.
- Static website checks pass; browser rendering remains a supported-CI gate because the local browser launch was restricted.

## Release boundaries

Original release signing remains unavailable; no replacement identity is permitted. A `.debug` preview installs separately from official MeBot and does not access its private data. The unsigned production-package APK is not installable. No official release, published tag or production website change is implied by this local candidate. Physical-device playback, downloads, notification/alarm delivery, original-key upgrade and owner-authorized provider sign-in remain separate checks.
