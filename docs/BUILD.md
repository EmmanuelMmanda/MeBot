# Build and verify MeBot

The project is a native Android application with a separate Kotlin domain module and an optional Python personal service. No account, API key, remote service or private configuration is required to build the app, use its local utilities or read its public RSS feed. AI calls require an owner-configured connection.

## Pinned toolchain

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle wrapper | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin and Compose compiler plugin | 2.2.21 |
| Android compile / target SDK | 36 |
| Android minimum SDK | 26 |
| Android SDK Build Tools | 35.0.0, the AGP default |
| Compose BOM | 2025.12.00 |
| WorkManager | 2.11.2 |

The Gradle distribution is pinned to its SHA-256 checksum in `gradle/wrapper/gradle-wrapper.properties`. The official Android [AGP 8.13 compatibility table](https://developer.android.com/build/releases/agp-8-13-0-release-notes) documents support for Gradle 8.13, JDK 17 and Android API levels through 36.1.

## Prepare a workstation

Install JDK 17 and the [Android SDK command-line tools](https://developer.android.com/studio). Android Studio can install and manage the same SDK.

Set `JAVA_HOME` to the JDK and `ANDROID_HOME` to the SDK. Put the SDK's `cmdline-tools/latest/bin` and `platform-tools` directories on your command path if you want to run their tools directly. Then install the project packages:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
sdkmanager --licenses
```

Accept the applicable SDK licences during installation. Alternatively, install those packages using Android Studio's SDK Manager.

If your editor cannot find the SDK, create an untracked `local.properties` at the project root:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Do not commit local paths or machine-specific proxy settings. If a corporate proxy is required, keep the Gradle proxy and certificate configuration in your user Gradle configuration. This repository does not contain environment-specific network credentials.

## Build and run the automated checks

On Linux or macOS:

```sh
./scripts/build.sh verify
```

On Windows, or when invoking Gradle directly:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The equivalent Unix command is:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The first build downloads public dependencies. Later builds can reuse the local Gradle cache. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It uses application ID `app.mynguli.debug` so that development and release installations can coexist.

Core tests exercise feed validation/ranking/context rules, daily visits, configuration and backup validation, focus timing and alarm recurrence without Android. App tests use Robolectric for Android storage, native Compose journeys, source/provider contracts, cancellation, credential changes, budgets and background delivery. Provider and key-store test doubles avoid real credentials and paid inference. Test reports appear under each module's `build/reports/tests` directory; Android lint reports appear under `app/build/reports`.

ChatGPT tests cover PKCE and callback validation, signed identity fixtures, account/workspace separation, rotating refresh tokens, cancellation and reset races, bounded HTTPS and SSE, terminal-response checks, subscription-only routing, usage-limit pauses and cached-reading preservation. `ChatGptConnectedJourneyTest` drives the real MainActivity, ViewModel, engine and repository through a controlled browser/authentication transport. `MeBotChatGptUiTest` exercises connection states, accessible controls, plan notices and compact Kiswahili rendering. `ChatGptReturnJourneyTest` checks cold/warm browser return and recreation with untrusted intent extras; its explicit launch action matches AndroidX ActivityScenario's lifecycle tracking. These suites do not use a live ChatGPT account or prove provider authorization on a physical Android device.

`MeBotFeedJourneyTest` covers onboarding, source refresh, saved reading, retry after failure, module editing/pausing, per-entry context editing, connection correction, key non-retention across rotation, contextual Ask and a small Kiswahili dark layout. `MyNguliJourneyTest` checks existing local tools through the new navigation. `MeBotNavigationJourneyTest` covers alarm and feed notification shortcuts and recreation. `MeBotAlarmJourneyTest` drives the ringing activity through wrong/correct math answers, recreation and emergency stop. Native graphics write reviewable PNGs to `app/build/outputs/screenshots/`. Generic fixtures use synthetic identities and example.com sources. These images are rendered review artifacts, not a pixel-baseline comparison suite. CI uploads them with reports.

These are host-side tests. They do not establish that an actual phone will ring overnight, play through a particular Bluetooth route, or present a full-screen alarm under its manufacturer settings.

## Install a development APK

On a connected development device or running emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For manual installation, transfer the APK to the Android device and use Android's installation flow. Follow the device's permission prompts. Feed and check-in work without alarm permissions; enable and configure the alarm module when you intend to use it.

## Optional personal profile and service

A public source build needs no private asset. For a personal build, place a reviewed `mebot-context-v1` JSON file at `app/src/main/assets/mebot-profile.json`, using [context-example.json](context-example.json) as the schema example. The asset is ignored by Git and imported once into editable state. Do not put keys or confidential documents in it: APK assets can be read by recipients. A device reset does not reapply the seed. Exclude the asset, private directory and build products from public source archives.

The optional [personal service](../service/README.md) has its own pinned Python dependencies and tests:

```sh
cd service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pip check
.venv/bin/python -m pytest -q
```

Its test provider is local and synthetic. Running tests requires no real API credential. Deploying the service and an HTTPS reverse proxy is a separate operation; a compiled APK does not imply a deployed backend or Android push channel.

## Build a signed release

A release uses application ID `app.mynguli`. Keep its signing key outside this repository. Future updates to the same installation need the appropriate signing identity; back it up privately.

For a new, independent installation identity you can generate a private key with the JDK tool below. To update the existing mynguli/MeBot installation, keep using the original supplied key; a newly generated key will not be a compatible update.

```sh
keytool -genkeypair -alias mynguli -keyalg RSA -keysize 3072 \
  -validity 10000 -storetype PKCS12 \
  -keystore /private/path/mynguli-release.p12
```

Supply signing settings through your local secret manager or CI secret environment:

| Environment variable | Meaning |
| --- | --- |
| `MEBOT_KEYSTORE` | Absolute path to the private keystore. |
| `MEBOT_KEYSTORE_PASSWORD` | Keystore password. |
| `MEBOT_KEY_ALIAS` | Optional alias; defaults to `mynguli`. |
| `MEBOT_KEY_PASSWORD` | Optional key password; defaults to the keystore password. |

Then run:

```sh
./scripts/build.sh release
```

The script requires a keystore and password before starting a release. It runs tests and release lint before assembling. The expected signed output is `app/build/outputs/apk/release/app-release.apk`. Directly running Gradle without signing variables can produce an unsigned release; do not present that output as an installable signed release.

Verify a signed APK using the installed build tools:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Never commit a keystore, password file, personal backup, or signing archive. Release signing is deliberately absent from the default pull-request workflow. See Android's [app signing guide](https://developer.android.com/studio/publish/app-signing) for signing and upgrade requirements.

## Continuous integration

`.github/workflows/android.yml` verifies pushes to `develop` and `master`, pull requests, and manual runs. It installs the pinned SDK, runs Android verification, and uploads the debug APK and reports. A separate job creates a Python virtual environment and runs the service checks without provider credentials. Third-party actions are pinned to immutable commit hashes. Update those references deliberately when reviewing dependencies.

The workflow builds a development APK. It does not publish the app, create a store listing, use personal signing secrets, or claim device-test coverage. Run checks on `develop` before reconciling a release into `master`.

## Device acceptance for the ChatGPT connection

On the owner's phone, open **You → AI connection → Continue with ChatGPT**. Complete the provider's consent flow in the system browser, return to MeBot and wait for the app's connection check. Confirm that the displayed account and model are correct, then refresh one enabled AI module and ask one contextual question. Check a cancelled sign-in, reopening the app, account switching, **Manage usage** and sign-out. A second account is optional; never create one solely for a test.

The provider controls eligibility, available models and plan limits. No fixed allowance or reset time is shown. Reaching a plan limit must preserve cached reading, pause automatic AI requests and leave API billing inactive. Physical Android browser return behavior, Android Keystore persistence and live subscription inference require this device/account acceptance. See [CHATGPT-CONNECTION.md](CHATGPT-CONNECTION.md) for protocol boundaries and [INSTALL.md](INSTALL.md) for the owner flow.

## Device acceptance before relying on alarms

Use a physical device to verify the complete journey:

1. Finish onboarding, record a daily visit, close the interface, and reopen it. Check that the saved plan and preferences remain available.
2. Start, pause, resume, and finish a focus session. Check the persisted state after closing and reopening the app.
3. Export a backup and import it into a development installation. Review imported alarms before enabling them; importing intentionally disables them and clears active focus sessions.
4. Configure alarm access and notifications, run a test alarm, and check the selected volume and vibration.
5. Test a scheduled alarm with the screen locked, the app interface closed, and network connectivity disabled.
6. Reboot before a scheduled alarm and test the behaviour before first unlock. Verify device-specific battery settings and notification/full-screen access.
7. Verify wrong and correct math answers, challenge progress after recreation and snooze, the snooze limit, QR checkpoint, follow-up, and emergency stop. A finished or stopped session must not ring again from an old callback.
8. Test the audio routes you actually use, including Bluetooth, headphones, and Do Not Disturb configured to allow alarms.
9. Install a signed update and confirm that preferences, visits, and alarm plans remain intact.

Record the device model, Android version, permission settings, expected result, actual result, and date. A successful APK build and a passing emulator test do not replace these checks. Keep an established alarm active during initial overnight qualification.

Force-stop and power-off are separate unsupported ringing conditions. Do not use force-stop as a substitute for ordinary process-death testing. After reopening a force-stopped installation, review that upcoming alarms have been restored.
