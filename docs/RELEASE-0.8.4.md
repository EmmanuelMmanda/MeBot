# MeBot 0.8.4 candidate guide

Status: automated-build-verified candidate following the tested [0.8.3 foundation](RELEASE-0.8.3.md). This is not an official signed APK release. The available official download remains [0.8.2](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.8.2).

## Simpler stars

The story star is an icon beside the bookmark. It follows or unfollows a story without adding a separate text button or management panel to its feed card. To adjust cadence or pause follow-ups, open **Saved → Following** yourself. Saving and following remain independent. Follow-ups still run during normal budgeted refresh; no extra paid job or guaranteed new coverage is implied.

## Fullscreen media

Supported image and media previews open in a native fullscreen shell with a close control. Images, direct video and audio use app fullscreen controls. YouTube uses its own player fullscreen control inside a native shell, retaining the same WebView/player rather than starting a separate playback session. Available quality choices, captions and other options depend on the source/player. There is no synthetic quality selector or guarantee that restricted third-party media will play. The original-source route remains available from the resource card.

## Tour and Help

Finishing onboarding now takes you directly to the feed without immediately opening a second quick-guide modal. Replay the quick guide from Help. Its English and Kiswahili instructions explain save versus follow and source-dependent media controls rather than obsolete feedback buttons.

The library now gives star-specific instructions when Following is empty and accurately labels its search. Kiswahili preferences and item-count wording have been refined. Onboarding guards against going back or saving twice while a save is in progress.

## Verification boundaries

The 0.8.4 gate completed successfully: 829 tests (181 core, 479 Android and 169 service), with zero failures, errors or skips. Both APK variants built. Debug lint reported 215 warnings and one hint; release lint reported 216 warnings and one hint. Both had zero errors, not zero warnings. The [acceptance tracker](RELEASE-TRACKER-0.8.4.md) records the tested boundaries.

The preserved 0.8.3 checkpoint passed 817 automated tests; it remains a separate candidate history.

Native host-rendered screenshots show controlled sample content, not live provider access or streaming acceptance. Physical-phone installation, media playback, notification delivery and overnight alarms still need device checks. The original release-signing identity is required for a compatible official upgrade. Do not uninstall the existing app to try an incompatible signature.

[0.8.4 preview](preview-084.html) · [0.8.3 tested candidate](preview-083.html) · [Official installation guide](INSTALL.md)
