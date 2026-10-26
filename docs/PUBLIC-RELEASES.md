# Public releases and artifact verification

**MeBot 0.9.5 is the recommended current release**, published for package `mebot.nguli.com` and signed with the established 0.9.x production certificate. It updates production 0.9.0–0.9.4 installations in place. MeBot 0.9.4 is superseded but its verified historical release remains available. Versions 0.9.0–0.9.2 had an external-launch defect; 0.9.3 corrected those crashes, and 0.9.4 improved the return after context import. The [release catalog](assets/release.json) and [release directory](releases.html) distinguish signed APK releases from historical source-only checkpoints.

## Current 0.9.5 release

MeBot 0.9.5 was published on 26 October 2026 at 23:35:05 UTC. Stories open in MeBot first; compact category labels and item-grounded questions keep the next step close. Suggested questions are editable and never send automatically. The original source remains available, and local questions work offline.

The public source is commit `eb6d2a12815cbf68decb2996eed9c1f0e0581e41`, tree `aa67a099156d52cce6677fedf9d01bad36878237`. The signed APK retains package `mebot.nguli.com` and the current production certificate. See the [official release](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.5), [experience-first notes](RELEASE-0.9.5.md), and [integrity record](releases/v0.9.5.json). All public release assets were anonymously downloaded and matched to their verified bytes and digests before the site switch.

The technical verification report records automated checks and explicitly separates host-test evidence from physical-device, OEM, live-account/provider and overnight-alarm acceptance. Story screenshots use synthetic story/provider fixtures, not live response evidence.

## Release history

- **0.9.4:** historical signed release, superseded by 0.9.5. It corrected the context-import return/visible-note flow; its original verification and artifact record remain unchanged.
- **0.9.3:** superseded, version code 20; corrected external file selection and notification-permission launch crashes. A context import could still leave the user on the import screen after confirmation.
- **0.9.2:** historical signed build, version code 19; external-launch defect, superseded.
- **0.9.1 / 0.9.0:** historical signed artifacts, superseded due to the same external-launch defect. Their pending catalog entries are not public release claims.
- **0.8.3:** published historical **source-only** release; no production-signed APK was supplied.
- **0.8.4 / 0.8.5:** archived source checkpoints; no production-signed APK is claimed or substituted with a debug build.
- **0.8.2 and earlier:** retain their original release identities and evidence. The `app.mynguli` package and older signer are not an update path to 0.9.x.

Source tags identify the actual version tree. Historical verification notes describe the checks performed then; they are not retroactive claims that old code passed the newest suite. Source archives exclude private profiles, credentials, signing keys, local SDK paths and generated build outputs. Archive `gradlew.bat` may use CRLF while Git stores LF, as declared in `.gitattributes`.

## Verified historical 0.9.4 release (superseded)

The previous public filename is `mebot-0.9.4.apk` (lowercase, required by the installed updater). Size: 17,543,336 bytes. SHA-256: `d0479d41d9971fb27e7dfc1067aa1c2565276f2f78d7698e517c5be7a1d99084`. Package: `mebot.nguli.com`; version code 21; minimum Android 8.0 (API 26), target API 36. All six public release assets were checked against the local bundle before publication and anonymously downloaded hashes match.

Exact audited source: public commit `54ca9e0f86f5616f7f5a8138bec4ea1e97f2fa8e`, tree `6c51dc2758c89fb93830169c535a2993b72ec700`. The downloadable source archive retains original local build commit `dd1b5b97a2954b5d5e10012f91f37a5a8796a757`; the archive and GitHub source tree were compared. [Official files and release notes](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.4) · [Exact source and technical report](RELEASE-0.9.4.md).

The APK is signed with the existing production certificate SHA-256:

```text
c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007
```

The installed Software Updates parser offers version code 21 to production installations at codes 17–20. A code-21 installation is already current and correctly sees no update. Android still asks the user to approve installation. This does not establish physical-device/OEM install acceptance, live provider eligibility or overnight alarm behavior.

## Verify the download

Use the APK and `SHA256SUMS.txt` from the same release. To check the release files on Linux:

```sh
sha256sum -c SHA256SUMS.txt
```

For a locally retained historical 0.9.2 APK on macOS or Windows:

```sh
shasum -a 256 MeBot-0.9.2.apk
```

```powershell
Get-FileHash .\MeBot-0.9.2.apk -Algorithm SHA256
```

Historical 0.9.2 SHA-256 (not a recommended or current download):

```text
635b7b26498c051cba43485940c2ba33e8ddc8af4890c06b87b748bb3d41c240
```

With Android SDK Build Tools:

```sh
apksigner verify --verbose --print-certs MeBot-0.9.2.apk
```

## Signing lineages and updates

**0.9.0 through 0.9.5** use package `mebot.nguli.com` and the same production certificate shown above. MeBot 0.9.5 can update 0.9.0–0.9.4 in place. **Do not uninstall or clear data to update.**

Earlier public releases used certificate SHA-256:

```text
b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9
```

The existing [release-signing-certificate.pem](release-signing-certificate.pem) documents that **older public certificate**, not the 0.9.x signer. The old `app.mynguli` package and the 0.8.2 `mebot.nguli.com` build are not a same-signer update path to 0.9.x. Android will reject incompatible signatures; never bypass this check. Read [installation guidance](INSTALL.md) before changing installations. Portable exports do not contain protected credentials or app-lock secrets.

Checksums identify artifact bytes; certificates identify the Android signer. Neither establishes physical-device installation, live provider eligibility or overnight alarm behavior. The verification report records those limits separately.

## Release procedure

1. Work on `develop`, run relevant application and service checks, and audit source and release assets for private content.
2. Sign only with authorized production custody; keep keys and passwords private. Verify package, version, signatures, alignment and artifact hashes.
3. Review and promote validated changes to `main`, preserving existing repository history and Pages configuration.
4. Tag the exact version source and attach verified files. Historical source-only entries must state that no production APK is supplied. Never invent binaries or move an old tag to new code.
5. Verify uploaded hashes and release URLs before promoting the website download metadata.
6. Record host tests separately from real-device and live-provider acceptance. Independent forks need their own identity and cannot replace official installations with a different key.
