# Install or update MeBot

**MeBot 0.9.8 is a preview, not a released APK.** Its corrected AI-only five-question and Original Read experience is not included in the currently published 0.9.7 APK. Do not treat source qualification or documentation screenshots as a signed update.

The [published 0.9.7 release page](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.7) provides the existing APK and release files. Android 8.0 or later is required. The production package is `mebot.nguli.com`. Review the release details before installing; wait for a qualified 0.9.8 release if you need the preview improvements.

## Compatible production updates

Open the downloaded APK and let Android update your existing MeBot installation. An update must have the same production signing certificate and package, with a higher version code. **Do not uninstall MeBot or clear its data to update.** The release is signed for the existing production app; automated and parser checks do not replace physical-device or manufacturer-specific acceptance.

MeBot 0.9.6 corrects the rapid story-question scope edge case from 0.9.5. If you keep using 0.9.5, wait until the story conversation is ready before sending. The previous 0.9.3 release corrected crashes during external file selection and notification setup. It could still leave the screen on the import flow after a successful context import. In 0.9.4, check **You → My context** for the notes before trying to import again; that release corrected the return path and displays the saved notes.

## Earlier installations

0.8.x binaries used an earlier signing certificate, so Android will not accept the current production signing identity as an in-place update to those builds. Back up supported personal preferences before considering a different installation. Portable exports do not include protected credentials or app-lock secrets. Never erase the existing app simply to try an update. The old `app.mynguli` package is separate from `mebot.nguli.com`.

## First use

Choose your language and interests, then explore the feed. AI connection is optional for public information and local tools. Reconnect accounts when needed and test configured alarms on your phone.

**You → Software Updates** checks for compatible official releases and verifies downloaded files before Android asks to install. Automatic checks are separate from AI and can be configured.

[Release status](download.html) · [Technical verification](PUBLIC-RELEASES.md) · [Start Here](../START-HERE.md) · [User Guide](USER-GUIDE.md) · [Build Guide](BUILD.md)
