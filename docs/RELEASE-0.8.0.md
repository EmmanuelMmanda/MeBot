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
See the accompanying `mebot-0.8.0-verification.txt` for this delivery's exact test
counts, lint results, certificate, checksums and limitations. Native host-rendered
screens are examples, not live provider results or evidence of overnight delivery.
Physical phone alarms, the Android save picker with each storage provider, live
subscription authorization, and third-party playback need device checks.

The offline environment has an Android 35 Robolectric runtime. Six existing
Android 34 alarm suites were not rerun against their declared runtime; they are
unchanged and remain in the source. This limitation is not a passing test result.
