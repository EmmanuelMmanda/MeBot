# Install or update MeBot 0.9.6

Download **mebot-0.9.6.apk** from the [official 0.9.6 release page](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.6). Android 8.0 or later is required. MeBot uses package `mebot.nguli.com`.

**MeBot 0.9.6 is the current official release.** It keeps the 0.9.x package and production signing identity; Android can update an existing 0.9.0–0.9.5 installation in place. No uninstall or data clear is needed. If you are still using 0.9.5, wait for the story conversation to finish opening before sending a question; an earlier send could lose that story's context. The correction is included in 0.9.6.

## Updating from 0.9.0 through 0.9.5

Open the downloaded APK and let Android update your existing MeBot installation. These releases use the same production signing certificate. **Do not uninstall MeBot or clear its data to update.** The release is signed for the existing production app; automated and parser checks do not replace physical-device or manufacturer-specific acceptance.

MeBot 0.9.6 corrects the rapid story-question scope edge case from 0.9.5. If you keep using 0.9.5, wait until the story conversation is ready before sending. The previous 0.9.3 release corrected crashes during external file selection and notification setup. It could still leave the screen on the import flow after a successful context import. In 0.9.4, check **You → My context** for the notes before trying to import again; that release corrected the return path and displays the saved notes.

## Earlier installations

0.8.x binaries used an earlier signing certificate, so Android will not accept 0.9.6 as an in-place update to those builds. Back up supported personal preferences before considering a different installation. Portable exports do not include protected credentials or app-lock secrets. Never erase the existing app simply to try an update. The old `app.mynguli` package is separate from `mebot.nguli.com`.

## First use

Choose your language and interests, then explore the feed. AI connection is optional for public information and local tools. Reconnect accounts when needed and test configured alarms on your phone.

**You → Software Updates** checks for compatible official releases and verifies downloaded files before Android asks to install. Automatic checks are separate from AI and can be configured.

[Experience-first release notes](RELEASE-0.9.6.md) · [Technical verification](PUBLIC-RELEASES.md) · [Start Here](../START-HERE.md) · [User Guide](USER-GUIDE.md) · [Build Guide](BUILD.md)
