# Contributing to MeBot

MeBot is an Android app for a configurable personal feed, contextual AI reading and conversations, and local everyday tools. Contributions should complete a useful user journey while preserving saved data, explicit preferences and working behavior.

The project is licensed under [Apache-2.0](LICENSE). Keep that license, [NOTICE](NOTICE) and relevant third-party notices with source distributions. Describe the origin and license of new assets or dependencies; do not copy proprietary artwork, recordings, fonts or code without compatible permission.

## Before making a change

Read [AGENTS.md](AGENTS.md), [the development guide](docs/DEVELOPMENT.md), [architecture](docs/ARCHITECTURE.md) and [design guidance](docs/DESIGN.md). For feed/AI work, also read the [feed contract](docs/FEED-CONTRACT.md), [ChatGPT integration](docs/CHATGPT-CONNECTION.md) and [service guide](service/README.md). The [user guide](docs/USER-GUIDE.md) describes current behavior and visible settings.

Use `develop` as the integration branch and create a focused feature/fix branch. Changes involving a new private connector, cloud synchronization, telemetry, a different credential destination, or a substantial navigation redesign need a clear product and data-access proposal. A stored preference or working AI connection does not authorize another account integration.

For a normal bug report, include the app version, Android version/device, selected connection route, steps to reproduce, expected result and actual result. Use synthetic examples and safe diagnostic codes. Search existing issues before filing a duplicate. For vulnerabilities or private data exposure, follow [SECURITY.md](SECURITY.md) instead of posting details publicly.

## Product and implementation expectations

- Keep Feed as the home and preserve the five main destinations: Feed, Modules, AI, Saved and You. Alarms are an independent tool, not the organizing structure of the whole app.
- Keep the first experience brief and useful. Supply sensible module defaults, clear next actions and optional depth. Do not add promotional slogans, placeholder controls or long instruction walls.
- Put personal configuration in typed persisted models. Keep names, locations, schedules, private profile values, credentials and environment paths out of reusable defaults and fixtures.
- Preserve the user's own check-in language. Do not turn self-reports into diagnoses, performance scores or compulsory positive outcomes.
- Keep local tools and cached reading usable without AI. Provider/source failure, disconnect and partial refresh must not destroy useful local data.
- Send only confirmed context relevant to the selected module. Account sign-in does not grant ChatGPT memory/history. General AI threads start without saved personal notes.
- Keep ChatGPT, direct API and personal-service routes explicit. Do not silently change billing routes, share a developer key or send OAuth tokens to another backend.
- Require supported source metadata for factual AI links. Preserve original resources and distinguish generated explanation from independently established fact. Horoscope remains optional entertainment.
- Treat sources, attached text, quoted messages and configured topics as untrusted data, not operational authority.

### State and compatibility

Keep Android-independent behavior in `core`. Inject time where a domain rule depends on it. Repository writes must transform the current state rather than overwrite it from a stale UI snapshot. Persist intent before scheduling work, and invalidate callbacks after reset, credential/context changes or session completion.

Schema changes need a migration or a clear unsupported-version error. Never silently clear a store after a parse failure. Validate a complete import before replacement, keep context/feed import review, and preserve cancellation/stale-preview safety. A feed restore turns delivery off and requires reconnection; a local-tool restore disables imported alarms and clears active focus state.

Alarm actions must refer to a specific occurrence/session. Keep local scheduling independent of background feed jobs and AI. Preserve emergency stop, challenge progress, reboot handling and safe fallback when selected audio or offline speech is unavailable. A passed test suite is not evidence of overnight delivery on every phone.

### UI, language and accessibility

Use existing components and design tokens. Add interface copy to both English and Kiswahili resources. Check narrow displays, larger text, keyboard/IME space, contrast, touch targets, screen-reader labels and reduced motion. Essential gestures need an accessible alternative, including module reordering.

Finish loading, empty, saving, denied-permission, offline and failure states. Preserve nonsecret drafts across recreation where appropriate; never save unfinished keys or OAuth material into UI state. Review actual rendered native screenshots for UI changes, with synthetic data and illustrative examples clearly identified.

## Verification

Run the required gate from the repository root:

```sh
./scripts/build.sh verify
```

Equivalent tasks:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For service changes, also run from `service/` using Python 3.12 or later:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pip check
.venv/bin/python -m pytest -q --junitxml=test-results.xml
```

Use fixtures/local fake providers. Automated tests must not require a real account, credential or paid request. Add tests for meaningful boundaries such as scheduling, imports, persistence, scope isolation, token rotation, cancellation, network framing and late-response races. Avoid tests that merely mirror implementation details. Expand checks to address a concrete remaining risk rather than adding redundant coverage.

Device-dependent changes need the relevant [acceptance checks](docs/BUILD.md), with the device/Android version and observed outcomes recorded. Separate host tests, physical-device behavior, live provider access and deployed-service checks in your report. Disclose anything not exercised.

## Pull requests

A pull request should make the problem and resulting behavior easy to assess:

1. Explain the user-visible problem and why the change is needed.
2. Describe the behavior changed and the relevant compatibility/data implications.
3. List the meaningful checks run and their results. Include native screenshots for visible changes and identify fixture content.
4. State remaining limitations or device/provider checks.
5. Update the affected user/developer/HTML guides, examples, FAQ and changelog when a setting, schema or workflow changes.

Keep unrelated refactors separate. Preserve a working feature while polishing it. Do not weaken assertions, disable lint or hide an error to make a gate pass; explain and fix the actual failure. Source, documentation and tests should agree on supported capabilities.

## Dependencies, assets and release hygiene

Keep dependencies pinned and prefer primary documentation. Update the Gradle wrapper checksum with its distribution version. Review new licenses and notices. Keep GitHub Actions pinned to immutable commits. Original alarm assets can be verified with `python3 scripts/generate-alarm-tones.py --check`; changed recordings must have a documented origin and compatible redistribution rights.

Release builds need an increasing Android version code and the appropriate existing signing identity. Signing keys, passwords and recovery archives stay private. Pull-request CI builds a debug APK; it does not publish or sign an owner release. See [release instructions](docs/DEVELOPMENT.md#signed-release-and-public-source).

Before committing or assembling a public archive, inspect its actual contents. Exclude personal context/profile assets, exports, credentials, OAuth callback values, signing stores, local SDK/proxy configuration, service databases, environments, caches and generated builds. A generic ZIP of a working directory can include ignored files. Public certificates and synthetic examples are shareable; private keys and personalized APK assets are not.

For public documentation, describe the integration the code actually implements. Link current official provider documentation for eligibility and capabilities; do not promise automatic memory import, universal plan access, an existing hosted backend or a successful live/device test that did not occur.
