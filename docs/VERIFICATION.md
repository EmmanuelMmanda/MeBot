> **Historical verification record.** The figures below describe MeBot 0.6.1, not the current preview. As of 28 October 2026, published **0.9.7** is an earlier experience and **0.9.8** remains a source-qualified preview without a public APK. See [current release availability](download.html) and [release identity evidence](PUBLIC-RELEASES.md). The [0.7.0-beta.1 record](RELEASE-0.7.0-beta.1.md) is also historical. No old test totals establish acceptance of newer source.

# Verification — MeBot 0.6.1

Verified on 2026-10-23. This release polishes the existing app: a six-step optional welcome, searchable module discovery, configurable request budgets, flat context setup, Help & FAQ, feed navigation, bounded image storage and selectable alarm audio with optional speech. The test totals below come from completed reports. The previews use native Android host rendering and controlled fixtures; the owner's phone and live account were not used in these tests.

The automated application checks below predate the public profile-free repackaging. See [public distribution verification](PUBLIC-RELEASES.md) for the separate byte, signature and privacy checks; the app code has not changed.

## Completed gates

| Area | Passed | Failed / errors / skipped |
| --- | ---: | ---: |
| Kotlin domain, configuration, ranking and local tools | 116 | 0 / 0 / 0 |
| Android transport, persistence, alarms and native UI | 385 | 0 / 0 / 0 |
| Optional Python service | 145 | 0 / 0 / 0 |
| **Total** | **646** | **0 / 0 / 0** |

Exact suite names and timestamps are recorded in `test-results-summary.json` and `service-test-results-summary.json`. The Python suite retains one existing Starlette test-client deprecation warning. Controlled transports replace paid AI providers in automated tests.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --no-build-cache
./gradlew :app:lintRelease :app:assembleRelease --no-daemon --no-build-cache
python -m pytest -q
```

The Android build uses an isolated copy. SHA-256 comparison confirmed that all 227 Android/domain/build inputs match the reviewed source, including the private profile asset retained from the previous personal APK. That profile, credentials, signing material and local SDK paths are excluded from the source ZIP.

## Welcome and discoverability

The centered six-step welcome presents the feed, module choices, automatic explanations, search and conversations, local tools, and personal setup. Examples are labelled as examples. It has explicit Next/Back controls and Skip/Open MeBot throughout. System Back revisits the preceding step. A saved step survives rotation, theme and language changes. Existing users are not forced through it again; Help & FAQ provides Quick tour.

Native tests exercise all six steps, immediate skip, connection versus direct entry, restoration, English/light and narrow Kiswahili/dark at larger text size. Screenshots 63–66 document the rendered tour.

The module catalog has search, All/Enabled/Disabled status and category filters. All modules on Feed opens the catalog; Add to your day opens disabled options. Built-in and custom topic modules retain their IDs, preferences and enabled state. Filtered reordering preserves hidden slots while updating the persisted order used by home and search filters. The module editor displays its default focus and allows custom focus without requiring personal data first. Narrow preference rows remain reachable with enlarged text.

## Feed navigation, context and recommendations

Horizontal rails have stable item identity, bounded edge hints and selected-chip visibility. Feed, search, suggested questions and related stories use the same behavior. Selecting a module moves its rail without moving the enclosing reading page. A native journey checks the far-end Travel selection after navigation and activity restoration.

The existing recommendation ranking is preserved. Explicit module preferences and confirmed scoped context accompany the module's purpose-specific default guidance in direct/ChatGPT feed, search and detail requests. Missing personal facts are not inferred as preferences. General AI conversations remain unscoped unless a module is selected. The optional service validates a supplied module against the question/item and supplies its own per-kind guidance; mismatched or unsupported settings are rejected before provider work. Tests cover all 17 module kinds and legacy requests.

The home feed still starts with five stories and reveals additional cached items in groups. A native journey opens 21 fixture stories across five pages, taps the selected Feed tab from the bottom, verifies return to the header and reset to five, and holds a source request while repeated taps share one refresh. Filter selection and reading position remain covered by the existing journeys.

## Request budgets and background settings

New profiles default to 28 attempted AI requests per local day. Presets are 28, 56, 112 and 224; Custom accepts a positive integer and Unlimited is stored as zero. Existing saved finite choices are preserved, including a legacy value of 12. Unlimited removes the Android app's local cap; provider plan, rate, credit and billing limits, and any separately configured self-hosted service cap, still apply.

Reservations occur inside the repository's atomic update. Tests cover finite concurrency, crossing the previous 100/10,000 ceilings, unlimited counter saturation without integer wrap, and timezone-day rollover. Cached reading and valid explanation reuse consume no additional AI attempt. Background controls include refresh interval, quiet hours and notifications. Android schedules work after the settings persist. The form waits for the actual save, preserves invalid drafts, and recovers interrupted intents. Saving settings acknowledges those fields while preserving newer feed items and usage from concurrent repository updates.

## Personal setup and help

My context exposes Import file, Paste JSON, Prepare in ChatGPT, Copy prompt and View prompt without an Advanced section. The single copyable prompt uses the real import contract and catalog scopes; it contains a format example rather than the user's private notes. Prepare in ChatGPT copies the prompt and opens the official ChatGPT page. The user submits it there, then imports or pastes the resulting JSON and reviews it before committing.

Signing in does not grant this app access to ChatGPT memory or conversation history. No automatic history-import endpoint is claimed or invented. See the official [Sign in with ChatGPT quickstart](https://developers.openai.com/siwc/quickstart) and `CHATGPT-CONNECTION.md` for the distinction between identity, plan-backed inference and personal context.

Help & FAQ starts with setup and includes searchable answers about context, connection, modules, explanations, sources, request limits, background updates, offline reading, privacy, imports, phone voice services and alarms. Native tests verify copied prompt content, the official destination, search/restoration, context navigation and large-text reachability.

Pasted context is bounded and parsed before a review. One copied JSON code fence is accepted. Invalid, oversized, cancelled or superseded preparations cannot overwrite a newer preview or mutate durable data. Confirmation applies the reviewed candidate. An empty modules list preserves existing module choices; context-only imports preserve saved reading, credentials and independent local tools. The file import and backup paths retain strict validation and their existing size bounds.

## Storage and preserved behavior

Equal repository updates avoid redundant validation and serialization, and changed snapshots are validated once at the repository boundary before atomic DataStore writes. Reopening the same story within five minutes avoids repeated reading-history writes. The existing feed/conversation limits and the 8 MiB serialized-state guard remain in place; Unlimited does not mean unbounded disk retention.

Publisher images retain a 12 MiB memory budget, 32 MiB disk budget, seven-day disk age and 4 MiB payload limit. Same-URL callers share an in-flight fetch/decode or failure cooldown; unrelated URLs may still use two concurrent downloads. Corrupt disk images are replaced during the same load. Temporary files are removed after failed writes, and cancelled leaders or waiters cannot poison a remaining request. Seven cache tests cover these cases, concurrent work, expiry and unsafe/oversized inputs.

Existing regression suites continue to cover automatic streamed post explanations, saved conversations, follow-ups, source metadata, module search, partial-refresh preservation, selected account/model and API routes, request accounting, credential/session cancellation, imported data, local alarm scheduling and independent daily tools. No paid-provider request is used as a release gate.

## Alarm sound and optional voice

The alarm editor offers Pulse, Rising chime, Steady bell and Classic, a phone alarm sound, and a selected audio file. Pulse is selected for a new alarm; decode defaults retain Classic and speech off for an existing alarm. Volume and ramp remain independent saved settings. The existing 80% volume and 20-second ramp defaults are unchanged, and the controller's prior-volume restoration policy remains in place. Preview uses the phone's current alarm volume, ends within 12 seconds, and stops on backgrounding, a changed selection or an actual alarm becoming audible.

Optional speech uses a short localized default or an explicit message up to 240 characters. It uses an installed offline phone voice and no AI request. During a real alarm, speech waits for the configured volume ramp to finish and stops when focus or the ringing session ends. It supplements the recurring alarm tone; failure to initialize a suitable voice does not end the alarm. A selected external sound can be unavailable after reboot before unlock, after its document changes, or when a backup is moved to a different phone. A bundled tone plays while the external source prepares and remains as fallback on failure. A destination phone may require the file to be selected again.

The three added WAV files are original deterministic synthesis, accompanied by a Python standard-library generator. Header, duration, normalized sample peak, nonzero energy, absence of clipping, DC offset, loop seams and exact regenerated hashes are checked with `python3 scripts/generate-alarm-tones.py --check`. The assets total 529,332 bytes before APK compression. Their common peak is -1.31 dBFS; Pulse has the most sustained energy. These signal measurements do not establish sound pressure or waking effectiveness on a phone speaker. The signed APK is checked for the exact bundled sound bytes and matching project license notice.

Alarm sound metadata and optional speech text travel with local-tool backups and the independent device-protected runtime store. The exported backup does not contain selected audio bytes or transferable Android grants. File access is retained while saved plans, runtime occurrences or a live editor draft need it; cleanup follows durable changes and cannot invalidate another alarm's selection. The sound and speech controls do not introduce AI dependency into local alarms.

## Native visual review and release integrity

The new native previews are 54–58, 60–67 and 69–70 in `docs/screenshots`. They show module discovery/defaults, background settings, feed navigation, flat context and its paste dialog, FAQ, the welcome tour and alarm audio. They use synthetic content and Robolectric API 35. They are not photographs of a physical phone, live model results or a physical-device frame-rate benchmark.

| Lint variant | Errors | Warnings | Informational |
| --- | ---: | ---: | ---: |
| Debug | 0 | 117 | 1 |
| Release | 0 | 117 | 1 |

Advisory warnings remain recorded in the lint reports. The signed release keeps package `app.mynguli`, version code 8, label MeBot, minimum SDK 26 and target SDK 36. It uses the same verified certificate as the previous APK. Signature, ZIP CRC, ZIP alignment, 64-bit native segment alignment, manifest privacy flags, bundled licenses and absence of development activities are checked against the actual APK. `release-integrity.json` now contains the public distribution artifact SHA-256 and certificate fingerprint. The public packaging checks are recorded in [PUBLIC-RELEASES.md](PUBLIC-RELEASES.md); all retained application entries match the originally tested APK.

## Practical boundaries

Install the signed APK over the existing MeBot without uninstalling to retain local data. Real account eligibility, model/web-search availability, browser return/session renewal and provider quotas still require the installed connection. Microphone, read-aloud, background delivery, physical scrolling performance and the independent alarm's lock-screen/audio/Doze/reboot/overnight behavior remain device checks. The application verification gate does not deploy a hosted service or submit to the Play Store. Repository publication and documentation deployment have their own recorded status.
