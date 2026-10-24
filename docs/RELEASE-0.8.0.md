# MeBot 0.8.0

## Installation
This release is the official `app.mynguli` package, named **MeBot**, version code 11.
Its release certificate must match the original official MeBot installation.
Install it over official MeBot; do not uninstall your existing app.

The earlier `app.mynguli.beta` build is a separate installation. Android does not
automatically move its data to the official package. Keep it installed until any
preferences or saved data you need have been exported and reviewed in MeBot.
Portable backups do not contain account tokens; reconnect accounts when needed.

## Changes
- A single fixed top activity panel reports active AI work and independent file
  transfers. The duplicate AI spinners and progress lines inside answers, search,
  the feed and connection screens are removed.
- Completed answers remain separate from optional resource previews. Source lists
  group media and files, initially show a compact selection, and preserve access
  to every source.
- The existing story headline, source, explanation, follow-up, save and share
  actions remain. Original notes and next steps can be expanded; they have not
  been deleted.
- A single expanded media player is shared across resource cards and the story
  hero. Media starts only after a tap and stops when its view is removed.
- A verified public PDF or supported file link has a **Save file** action. The
  app downloads it separately, then asks where to save through Android's picker.
- Eight accent presets and a custom six-digit color are available in Appearance.
  Page styles are Original, Warm, Soft color and Deep black (for dark mode).
  Accent foregrounds are adjusted for readable contrast; the saved custom color
  remains unchanged.
- Existing settings load with defaults for the new appearance fields. Account
  transport, recommendation rules and alarm logic are not replaced in this patch.
- New labels use plain English and short Kiswahili.

## Documents and media
A source must provide an actual supported public HTTPS file URL. The app does not
invent PDFs, bypass sign-in or paywalls, or download extracted YouTube streams.
Supported direct file types: PDF, TXT, CSV, DOCX, XLSX, PPTX, PNG, JPG, JPEG and WEBP.
Transfers are limited to 20 MiB with timeouts, content-type/signature checks and
redirect validation. Office documents must contain the expected archive parts.
Each destination keeps **Open original** for sources with different requirements.

Resources use their own IO path and transfer slot, not the AI request queue.
A failed file or media preview does not turn a valid AI response into a failure.
Only one media preview is expanded at once. Official embedded players may still
show their own network/buffering controls.

## Privacy and reproducibility
No private starter profile, account credentials or signing key belongs in the
public source. Files are downloaded without AI-provider authorization headers.
Temporary downloads stay in the app cache and are deleted after saving or failure.
Unclaimed temporary files are cleaned up after an hour on subsequent downloads.

The source contains the original font licence and a pinned resource preparation
script instead of downloadable font binaries. Run:

```sh
python3 scripts/prepare-build.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

Configure release signing through private `MEBOT_*` environment variables. Without
the original release key, a fork cannot replace an official installation.

## Verification
Full CI completed **678 application tests**: 137 core, 396 Android and 145 service,
with no failures, errors, exclusions or skipped tests. This includes the Android 34
alarm suites that were unavailable in the earlier local offline run. The website
passed **45 browser scenarios**. Release lint has **zero errors**, with 133 warnings
and one hint preserved in its report.

[Verification run](https://github.com/EmmanuelMmanda/MeBot/actions/runs/40119881960)
completed all build, test and browser checks. Its final publishing step encountered
a workflow-write permission restriction; the verified artifacts were retained and
published separately without rebuilding or changing the application code.

The accompanying `mebot-0.8.0-verification.txt` records the tested code commit,
release commit, original certificate, file checksums and remaining device checks.
Native host-rendered screens use example content. They are not live AI responses
or evidence of physical overnight delivery. Physical phone alarms, the Android
save picker with each storage provider, real account authorization/inference and
third-party playback still require device checks.
