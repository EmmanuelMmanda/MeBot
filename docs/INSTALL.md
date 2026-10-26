# Install or update MeBot 0.9.4

Download **mebot-0.9.4.apk** from the [official 0.9.4 release page](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.4). Android 8.0 or later is required. MeBot uses package `mebot.nguli.com`.

## Updating from 0.9.0, 0.9.1, 0.9.2 or 0.9.3

Open the downloaded APK and let Android update your existing MeBot installation. These releases use the same production signing certificate. **Do not uninstall MeBot or clear its data to update.** The release is signed for the existing production app; automated and parser checks do not replace physical-device or manufacturer-specific acceptance.

The previous 0.9.3 release corrected crashes during external file selection and notification setup. It could still leave the screen on the import flow after a successful context import. In 0.9.4, check **You → My context** for the notes before trying to import again; the corrected flow returns there and displays them.

## Earlier installations

0.8.x binaries used an earlier signing certificate, so Android will not accept 0.9.4 as an in-place update to those builds. Back up supported personal preferences before considering a different installation. Portable exports do not include protected credentials or app-lock secrets. Never erase the existing app simply to try an update. The old `app.mynguli` package is separate from `mebot.nguli.com`.

## First use

Choose your language and interests, then explore the feed. AI connection is optional for public information and local tools. Reconnect accounts when needed and test configured alarms on your phone.

**You → Software Updates** checks for compatible official releases and verifies downloaded files before Android asks to install. Automatic checks are separate from AI and can be configured.

[Experience-first release notes](RELEASE-0.9.4.md) · [Technical verification](PUBLIC-RELEASES.md) · [Start Here](../START-HERE.md) · [User Guide](USER-GUIDE.md) · [Build Guide](BUILD.md)
