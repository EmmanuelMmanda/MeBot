# MeBot 0.8.5 candidate guide

Version 0.8.5, code 16. Automated-build-verified candidate, not an official signed APK release. The available official download remains [0.8.2](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.2) until signing and release acceptance are verified.

## Custom interests

Add interests in your own words during onboarding. The live counter enforces both 150 whitespace-separated words (including Unicode whitespace) and the existing 1,000-character limit. Text is not silently truncated. Saved interests retain explicit module scope.

## Simpler personal context

About you has one primary textarea for work, interests and what to follow. It is a global profile for all modules only after an explicit Save. Optional Help offers an English or Kiswahili example about a person in Tanzania who owns several small businesses. Inserting it requires an explicit action; replacing existing text requires confirmation. The example is never automatically saved as personal context.

Separate notes are visibly listed below the global profile, with add, edit and remove actions and per-note module access. Earlier records and onboarding interests retain their existing scoped-note status and are not automatically promoted to the global profile. Imported context does not acquire a global grant; an explicit About-you Save is required. Location, time zone, import and export sit in collapsed Location, time zone & transfer; Save details remains prominent.

## Navigation and tools

Modules has compact search and horizontal filter rows. Saved and Following have a clearer hierarchy. The existing icon-only star, separate bookmark and manual follow-up management remain.

Compare prices is removed from 0.8.5, including its entry and implementation. An obsolete restored calculator route falls back safely to Modules. This does not remove unrelated everyday utilities. References in 0.8.3/0.8.4 release history describe those older candidates, not the current feature set.

## Fullscreen documents and media

The PDF extension uses the native renderer with fullscreen page navigation, fit and zoom controls. Host UI tests cover controls and page/zoom restoration. Its screenshot uses locally pre-rendered synthetic PDF pages because the host PDF decoder is unsupported; it is not physical-phone decoding acceptance.

YouTube keeps its provider fullscreen and quality controls where offered. Direct MP4, WebM and HLS resources use the Android player and supported device codecs. MeBot does not promise DASH, DRM playback, universal stream compatibility or app-forced resolution levels. Unsupported resources and playback errors retain an original-link fallback. Live streaming and physical-device playback remain separate acceptance checks.

## Connected providers

If you use the self-hosted MeBot service, update it together with the app so global profile and scoped-note handling match. Direct provider connections do not require that service. Automated contract checks do not establish live provider authorization or successful live inference.

## Verification and release limits

The final media-inclusive local gate passed 878 tests: 189 core, 509 Android and 180 service, with zero failures, errors or skips. Release assembly and ZIP alignment passed. Both lint variants had zero errors; debug had 225 warnings and release had 226 warnings, with one hint each. This is not a warning-free release. The resulting APK is unsigned and non-debug, package `mebot.nguli.com`, version 0.8.5/code 16. Updated candidate CI and signing remain pending. Earlier baseline browser CI passed 70 scenarios; it does not certify the latest revision.

Current acceptance is recorded in the [0.8.5 tracker](RELEASE-TRACKER-0.8.5.md). Native host captures show controlled examples, not live provider access, streaming, device installation or overnight alarm acceptance. Physical-phone checks and secure release signing remain separate gates. If a new signing certificate is used for the same production package, it cannot update the old 0.8.2 installation in place. Export portable preferences before any owner-controlled reinstall. A preferences export is not a promise that every kind of app data is portable. There must be no automatic uninstall or silent data loss. Do not uninstall an existing app to try an incompatible signature.

[0.8.5 illustrated guide](preview-085.html) · [0.8.4 history](RELEASE-0.8.4.md) · [0.8.3 history](RELEASE-0.8.3.md) · [Official installation guide](INSTALL.md)
