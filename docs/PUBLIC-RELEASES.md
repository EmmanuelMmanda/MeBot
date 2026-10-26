# Public releases and artifact verification

**0.9.3 is the recommended published corrective release.** Versions 0.9.0–0.9.2 are superseded due to a known external-picker and permission-launch crash. Local and anonymously downloaded release artifacts were verified, with identical checksums and the expected production signer. The [release catalog](assets/release.json) and [release directory](releases.html) distinguish signed APK releases from historical source-only checkpoints. A publication date is the actual date of publication, not a backdated development date.

## Release history

- **0.9.3:** published corrective release, version code 20; 1,030 local tests, fresh public CI and signed public artifacts verified.
- **0.9.2:** historical signed APK, version code 19; known external-launch defect, superseded.
- **0.9.1 / 0.9.0:** exact historical signed APKs, superseded due to the same known external-launch defect.
- **0.8.3 / 0.8.4 / 0.8.5:** archived, tested source checkpoints. No production-signed APK is claimed or substituted with a debug build.
- **0.8.2 and earlier:** retain their original release identities and evidence. Prepared older artifacts marked `pending` in the catalog are not claimed as published.

Source tags identify the actual version tree. Historical verification notes describe the checks performed then; they are not retroactive claims that old code passed the newest suite. Source archives exclude private profiles, credentials, signing keys, local SDK paths and generated build outputs. Archive `gradlew.bat` may use CRLF while Git stores LF, as declared in `.gitattributes`.

## Verified 0.9.3 release

The public filename is `mebot-0.9.3.apk` (lowercase, required by the installed updater). Size: 17,510,568 bytes. SHA-256: `2268874208a354b3dab5d41a4d675cc7832206e5f6b19403c49a22716735f74e`. All six public release downloads matched the audited local bundle. [Exact source and verification](RELEASE-0.9.3.md).

## Verify the download

Use the APK and `SHA256SUMS.txt` from the same release. To check all downloaded files on Linux:

```sh
sha256sum -c SHA256SUMS.txt
```

For a historical 0.9.2 APK on macOS or Windows:

```sh
shasum -a 256 MeBot-0.9.2.apk
```

```powershell
Get-FileHash .\MeBot-0.9.2.apk -Algorithm SHA256
```

Historical 0.9.2 SHA-256 (not a recommended download):

```text
635b7b26498c051cba43485940c2ba33e8ddc8af4890c06b87b748bb3d41c240
```

With Android SDK Build Tools:

```sh
apksigner verify --verbose --print-certs MeBot-0.9.2.apk
```

## Signing lineages and updates

**0.9.0, 0.9.1, 0.9.2 and 0.9.3** use package `mebot.nguli.com` and this production certificate SHA-256:

```text
c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007
```

0.9.3 can update 0.9.0, 0.9.1 or 0.9.2 in place. **Do not uninstall or clear data to update.**

Earlier official releases used certificate SHA-256:

```text
b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9
```

The existing [release-signing-certificate.pem](release-signing-certificate.pem) documents that **older public certificate**, not the 0.9.x signer. The old `app.mynguli` package and the 0.8.2 `mebot.nguli.com` build are not a same-signer update path to 0.9.x. Android will reject incompatible signatures; never bypass this check. Read [installation guidance](INSTALL.md) before changing installations. Portable exports do not contain protected credentials or app-lock secrets.

Checksums identify artifact bytes; certificates identify the Android signer. Neither establishes physical-device installation, live provider eligibility or overnight alarm behavior. The release verification report records those limits separately.

## Release procedure

1. Work on `develop`, run relevant application and service checks, and audit source and release assets for private content.
2. Sign only with authorized production custody; keep keys and passwords private. Verify package, version, signatures, alignment and artifact hashes.
3. Review and promote validated changes to `main`, preserving existing repository history and Pages configuration.
4. Tag the exact version source and attach verified files. Historical source-only entries must state that no production APK is supplied. Never invent binaries or move an old tag to new code.
5. Verify uploaded hashes and release URLs before promoting the website download metadata.
6. Record host tests separately from real-device and live-provider acceptance. Independent forks need their own identity and cannot replace official installations with a different key.
