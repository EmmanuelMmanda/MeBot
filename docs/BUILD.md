# Build and verify MeBot 0.8.2

Current Android application ID and Kotlin namespace: **`mebot.nguli.com`**. Display name: **MeBot**. Version code: 13. Older published binaries retain their historical package and signing records.

## Verified toolchain

| Component | Pinned version |
| --- | --- |
| Java | JDK17 for independent CI; JDK21 with `-Pmebot.buildJdk=21` for the checked local environment |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | compile/target36; minimum26 |
| Android Build Tools | 35.0.0 |
| Compose BOM | 2025.12.00 |

Install Android command-line tools and JDK17. Keep `local.properties`, credentials, proxy settings and signing inputs outside Git.

```sh
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
sdkmanager --licenses
python3 scripts/prepare-build.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease --no-daemon --stacktrace -Pkotlin.incremental=false
```

`prepare-build.py` retrieves pinned font resources and generates the original bundled alarm tones. Source archives intentionally exclude font binaries. It needs network access on the first preparation. Do not replace pinned dependencies with arbitrary latest versions during a release.

On constrained machines, run test, assembly and lint sequentially and use `--no-parallel --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`. This changes resource consumption, not test coverage. Do not exclude failing tests to obtain a release.

## Build artifacts

- Debug: `app/build/outputs/apk/debug/app-debug.apk`, package `mebot.nguli.com.debug`.
- Unsigned release without signing inputs: `app/build/outputs/apk/release/app-release-unsigned.apk`. It is not an installable signed release.
- Signed release with configured signing inputs: `app/build/outputs/apk/release/app-release.apk`.
- JUnit XML: `core/build/test-results/test` and `app/build/test-results/testDebugUnitTest`.
- Native captures: `app/build/outputs/screenshots/`.

Provider tests use controlled local responses, not paid inference. Host-rendered UI is not physical-device acceptance. `Release082CaptureTest` uses dated public editorial inputs and optional external test-image files selected through `MEBOT_CAPTURE_ASSETS`; the public APK does not include those publisher test-image files.

## Signing

Configure `MEBOT_KEYSTORE`, `MEBOT_KEYSTORE_PASSWORD`, `MEBOT_KEY_ALIAS` and optionally `MEBOT_KEY_PASSWORD` through private environment variables. The current default alias is `mebot`; the established private signing key has an older alias, so its actual alias is supplied explicitly. Never commit the key or its password, and never put them in release archives or logs.

The established certificate is retained for continuity of future releases, while the package identity changes at 0.8.2. A matching certificate alone does not make two different Android application IDs an in-place update. Release 0.8.2 is a new installation; future `mebot.nguli.com` versions preserve package/certificate and advance versionCode.

```sh
./scripts/build.sh release
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs path/to/mebot.apk
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 path/to/mebot.apk
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging path/to/mebot.apk
```

## Optional service

```sh
cd service
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q --junitxml=test-results.xml
```

The Python service remains an optional OpenAI-backed personal service. Adding native provider adapters to Android does not imply the service has those adapters too.

## Release acceptance

1. Run all core, Android and service tests, release lint and compilation. Report warnings; fail on errors.
2. Check package/version/minimumSDK, non-debuggable flag, signer, archive integrity and alignment. Compare signed APK application entries with the tested unsigned input.
3. Keep runtime sources identical to the tested commit. Package the final tag with documentation and release tooling, not a different untested code snapshot.
4. Publish release metadata with applicationId, versionCode, minimumSdk, signerCertificateSha256 and asset names/byte counts/SHA-256 values. This metadata is consumed by Software Updates.
5. Verify every uploaded asset and re-download the public APK. Do not label a draft or missing file as available.
6. Validate static links, direct download buttons, gallery, keyboard navigation, phone/tablet/desktop widths, dark mode and no-JavaScript downloads.
7. Keep private profiles, keys, generated build directories and standalone font files out of source/resources.

## Device-only checks

On a real phone, test browser authorization and return, account/model changes, streaming, private-endpoint consent, provider playback, file saving and Software Updates installation approval. For alarms, test exact-alarm permission, sound/voice, lock screen, reboot before first unlock, snooze/math/QR challenges, emergency stop and relevant Bluetooth/DND routes. Test subsequent same-package signed updates with retained settings. Do not infer these results from host tests.

[Current release evidence](RELEASE-0.8.2.md) · [User guide](USER-GUIDE.md) · [Provider setup](CHATGPT-CONNECTION.md).
