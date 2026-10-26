> **Superseded — known issue:** This version can close when choosing or exporting a file, or turning on notifications. Use the [published 0.9.3 update](RELEASE-0.9.3.md) instead. Do not uninstall or clear your data.

# MeBot 0.9.2 — document and settings repair

Version code 19 updates 0.9.1/code18 using the same production signer. Do not uninstall or erase data to update.

The design keeps the existing MeBot shell and controls while restoring visible import/location, a guided context-import screen, a clearer AI connection layout, local-only saved-account removal, dedicated starred follow-up settings and Personalization-owned app lock. Ordinary app use does not silently enable background updates, biometrics or a new provider connection.

Document handling covers launch failure, cancellation, returned result, recreation, locked return and stale target/reset isolation. Parsing/review remains separate from committing imported data; invalid content must not replace existing information. Tests cannot guarantee that an app will never crash, and no owner-device stack trace or live provider success is claimed.

See [repair acceptance and scheduling evidence](diagnostics/REPAIR-0.9.2.md), [document-picker diagnostics](diagnostics/DOCUMENT-PICKERS-0.9.2.md) and [journey design](design/journeys-092.md). Final test, lint, native-render and signature results accompany the delivered artifact. New previews use synthetic fixtures, not the owner's accounts, data or PIN.

## Historical host verification (does not cover the discovered defect)

[Signed APK and versioned files](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.2). The production APK is non-debuggable and uses the same signer as 0.9.0 and 0.9.1. Install over those versions without uninstalling or clearing data.

The verified host gate passed 198 core, 645 Android-host and 180 service tests: **1,023 total**, no failures, errors or skips. Debug/release assembly and lint passed with zero lint errors; existing warnings remain. Signature v2/v3, package/version, alignment and SHA-256 were verified. These are not physical-device picker or live-provider acceptance results.

APK SHA-256: `635b7b26498c051cba43485940c2ba33e8ddc8af4890c06b87b748bb3d41c240`.

[Signing lineages and verification](PUBLIC-RELEASES.md) · [Install or update](INSTALL.md).
