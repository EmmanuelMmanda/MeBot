# MeBot 0.9.3 — external launch repair

**Published corrective release, version code 20.** [MeBot 0.9.3 and its six release files](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.3) were published on 26 October 2026. All six anonymous downloads match the audited local files; APK signatures, alignment and the installed updater’s 17/18/19 → 20 selection were verified.

## Repair

0.9.0–0.9.2 could close when opening a file picker/export dialog or requesting notification permission. Biometric 1.1.0 pulled in Fragment 1.2.5 alongside Activity Result 1.12.2. After the switch to FragmentActivity, modern request codes triggered “Can only use lower 16 bits for requestCode”.

The repair explicitly uses Fragment 1.8.9. It does not truncate request codes, suppress exceptions, remove biometrics or disable app lock. Notification wording now says “Notify me about new updates” / “Nipe arifa za taarifa mpya”; background refresh remains separate. Existing layout and context/settings behavior are preserved.

## Verification

All four actual MainActivity baseline launch cases reproduced the exception. Seven expanded host journeys now pass, covering picker/export, attachment, cancellation/retry, notification denial/retry/grant and save, saved-state parceling and activity recreation.

**1,030 tests passed:** 198 core, 652 Android-host and 180 service; zero failures, errors or skips. Debug/release lint had zero errors, with 247 warnings/3 hints and 243 warnings/2 hints respectively. Production assembly, v2/v3 signature verification, package/version, non-debuggable manifest and 16 KB alignment passed. No physical/OEM phone, live provider or biometric-hardware acceptance is claimed.

The software-only emulator installed the exact 0.9.2 APK and rendered onboarding, but repeated System UI/launcher ANRs prevented reliable picker or permission testing. 0.9.3 was not installed in that emulator run. This is not a target-flow, 0.9.3 device or physical-phone pass.

Fresh PR checks passed: [Android/core/service verification](https://github.com/EmmanuelMmanda/MeBot/actions/runs/40369531163) and [website checks](https://github.com/EmmanuelMmanda/MeBot/actions/runs/40369531229), plus documentation and secret scanning, on verification commit `10d44d44e0140eff83ae7e5bdb676bc0c769b53b`. PR 5 merged into develop and its merge checks passed. Public release/download verification is complete.

The preceding CI run failed once in an existing module-pause journey. A diagnostic-only follow-up retained the original predicate, 15-second bound and saved-content assertions. Subsequent focused, full local and fresh public checks passed. The intermittent cause is unproven, not a claimed runtime fix. The signed APK and exact source/archive checkpoint are unchanged.

## Exact local artifacts

- Public source checkpoint: `a95e6c55209445894b6eb81ed1993c8b25e6fb66`.
- Source tree: `e4621929b381763679bbda7184159c364356f60d`.
- Original build/archive commit: `8fb19e4492bdc4dab52bd395375d955d16a10221`; identical tree.
- Source ZIP SHA-256: `e1f93244c604705640a33118be74ee5513607f1c31719217aadb10d4076d0f2f` (20,054,268 bytes).
- Public APK filename: **`mebot-0.9.3.apk`**. Lowercase naming is required by the installed updater.
- APK SHA-256: `2268874208a354b3dab5d41a4d675cc7832206e5f6b19403c49a22716735f74e` (17,510,568 bytes).
- Signer SHA-256: `c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007`, unchanged from 0.9.0–0.9.2.

Install the verified release over the matching 0.9.x installation; do not uninstall or clear data. Historical 0.9.0–0.9.2 remain superseded due to the reproduced defect, not recommended new installations.

[Signing lineages](PUBLIC-RELEASES.md) · [Installation status](INSTALL.md)
