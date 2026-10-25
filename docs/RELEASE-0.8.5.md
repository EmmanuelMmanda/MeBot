# MeBot 0.8.5 candidate guide

Version 0.8.5, code 16. Automated-build-verified candidate, not an official signed APK release. The available official download remains [0.8.2](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.2) until signing and release acceptance are verified.

## Custom interests

Add interests in your own words during onboarding. The live counter enforces both 150 whitespace-separated words (including Unicode whitespace) and the existing 1,000-character limit. Text is not silently truncated. Saved interests retain explicit module scope.

## Simpler personal context

About you has one primary textarea for work, interests and what to follow. Optional Help offers an English or Kiswahili example. Inserting it requires an explicit action; replacing existing text requires confirmation. The example is never automatically saved as personal context.

Earlier individual context records are preserved losslessly in collapsed Saved details. Location, time zone, import and export sit in collapsed Location, time zone & transfer; Save details remains prominent.

## Navigation and tools

Modules has compact search and horizontal filter rows. Saved and Following have a clearer hierarchy. The existing icon-only star, separate bookmark and manual follow-up management remain.

Compare prices is removed from 0.8.5, including its entry and implementation. An obsolete restored calculator route falls back safely to Modules. This does not remove unrelated everyday utilities. References in 0.8.3/0.8.4 release history describe those older candidates, not the current feature set.

## Verification and release limits

The final local gate passed 841 tests: 182 core, 490 Android and 169 service, with zero failures, errors or skips. Release assembly and ZIP alignment passed. The APK is unsigned and non-debug, with the production package `mebot.nguli.com`. Both lint variants had zero errors; debug had 221 warnings and release had 222 warnings, with one hint each. This is not a warning-free release. Candidate CI and signing remain pending.

Current acceptance is recorded in the [0.8.5 tracker](RELEASE-TRACKER-0.8.5.md). Native host captures show controlled examples, not live provider access, streaming, device installation or overnight alarm acceptance. Physical-phone checks and secure release signing remain separate gates. If a new signing certificate is used for the same production package, it cannot update the old 0.8.2 installation in place. Export portable preferences before any owner-controlled reinstall. A preferences export is not a promise that every kind of app data is portable. There must be no automatic uninstall or silent data loss. Do not uninstall an existing app to try an incompatible signature.

[0.8.5 illustrated guide](preview-085.html) · [0.8.4 history](RELEASE-0.8.4.md) · [0.8.3 history](RELEASE-0.8.3.md) · [Official installation guide](INSTALL.md)
