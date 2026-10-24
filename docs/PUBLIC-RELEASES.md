> Current test candidate: see [0.7.0-beta.1](RELEASE-0.7.0-beta.1.md). Older figures below describe archived releases, not new beta verification.

# Public releases and artifact verification

The application source and version history are licensed under Apache-2.0. Public APK packages start without a personal starter profile. Maintainers keep the original personal builds and all signing material outside the public repository and release assets.

## Historical inventory

| Version | Authentic source commit | Prepared distribution |
| --- | --- | --- |
| 0.6.1 | `440718f1210a` | Public APK + original source ZIP |
| 0.6.0 | `f75f13407810` | Public APK + original source ZIP |
| 0.5.1 | `fcd3b4ebdb3d` | Public APK + original source ZIP |
| 0.5.0 | `0660d84d11e7` | Public APK + original source ZIP |
| 0.4.0 | `0552a395122b` | Public APK + original source ZIP |
| 0.3.0 | `7f774b086f62` | Public APK + original source ZIP |
| 0.2.0 | `95ea2c53c254` | Original source ZIP; no recovered APK |

The existing annotated tags for 0.3.0–0.6.1 remain at their original commits. The restored 0.2.0 source snapshot is the oldest recoverable Git commit and receives its own tag. Release dates in the changelog describe the development history; the date GitHub publishes an archived release may be later.

Version **0.1.0** is documented in the changelog, but no authentic source snapshot, commit or APK was recovered. It is recorded as unavailable instead of assigning its tag to the code of another version. The old private signing recovery archive is not an application release and is not public.

The release catalog at [assets/release.json](assets/release.json) keeps publication status separate from artifact availability. `pending` means the prepared artifact has not yet been verified on GitHub. A download button is enabled only for a published entry with a filename, checksum and byte count. The [release archive](releases.html) and [GitHub Releases](https://github.com/EmmanuelMmanda/MeBot/releases) provide the available downloads.

## Profile-free historical APKs

Saved APKs for 0.3.0 through 0.6.1 originally included an optional `assets/mebot-profile.json` starter profile. That file must not be redistributed. Each affected version already handles the absent asset using its normal defaults.

For the public distribution, the optional asset is removed, the ZIP container is realigned, and the APK is signed with the original release key. This is a packaging change. **Every retained ZIP entry is byte-identical to its historical APK**, including the manifest, DEX code, resources, native libraries, other assets, dependency metadata and license files. Compression methods are retained. New signatures and container layout produce a new artifact checksum.

This distinction is stated in each release's integrity record. Checksums in old historical source documentation may describe the original personal build; use the checksums attached to the public release when verifying its download. The [current integrity record](release-integrity.json) describes the public 0.6.1 distribution.

The source ZIPs are the original audited archives. Each contains every file from its matching tag, with no extra or missing paths. `gradlew.bat` has CRLF line endings in those archives and LF in Git; normalized contents are identical. On Unix, the oldest source snapshots may require `chmod +x gradlew scripts/build.sh` before running the build scripts. Later tags preserve executable bits.

## Checks completed on the prepared packages

- All eight original reachable commits and 629 unique blobs were inspected for private profile content, credential patterns and forbidden tracked files. The 124 historical screenshot blobs were also OCR-scanned. Pattern/OCR checks are evidence of the inspected cases, not proof that all conceivable secrets are detectable.
- All six public APKs have no starter-profile asset. Their complete retained entry sets, uncompressed SHA-256 hashes and compression methods match the audited historical APKs.
- APK Signature Scheme v2 verifies with the same release certificate for every version. ZIP CRC and `zipalign -c -P 16 4` checks pass. Native library contents are unchanged.
- Manifest package, version name, version code, Android minimum and target SDK match each historical APK. Bundled Apache, Figtree and NOTICE files match their corresponding source version.
- Source ZIPs exclude personal profiles, credentials, private exports, local SDK paths, build products and private signing keys.

The current application test record remains [646 passing tests](VERIFICATION.md). Packaging verification does not imply that all old versions were rerun through the current test suite. It also does not establish live provider eligibility, installation on a physical phone, or overnight alarm behavior.

## Verify a downloaded release

Download the APK and `SHA256SUMS.txt` from the same version's release. On Linux or macOS:

```sh
sha256sum -c SHA256SUMS.txt
```

On macOS without `sha256sum`, use `shasum -a 256 mebot-0.6.1.apk` and compare the exact output with the release checksum. On Windows PowerShell:

```powershell
Get-FileHash .\mebot-0.6.1.apk -Algorithm SHA256
```

The checksum file may list source and metadata assets too. Download those alongside it for a complete `-c` check, or compare the APK's line explicitly.

With Android SDK Build Tools installed, also inspect the signing certificate:

```sh
apksigner verify --verbose --print-certs mebot-0.6.1.apk
```

Official public APKs use this certificate SHA-256:

```text
b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9
```

The public certificate is available as [release-signing-certificate.pem](release-signing-certificate.pem). A checksum verifies bytes; the signing certificate identifies the Android update signer. Keep both checks distinct.

## Updates and independent builds

Install an official signed update over the existing `app.mynguli` app to retain local data. Removing the optional seed from the public package does not erase existing saved context. Downgrades can be rejected by Android and are not a supported migration path; the archive exists for provenance and development.

A fork can build and sign its own APK under Apache-2.0, but its key cannot replace the official installation. The development ID `app.mynguli.debug` supports a separate test installation. Never publish or request the maintainer's signing key. [Build instructions →](BUILD.md)

## Future release procedure

1. Work on `develop`, update application version/changelog when app behavior changes, and complete the required application and service checks.
2. Confirm that no personal seed, secret, export or local configuration is tracked or included in the public APK. Keep signing credentials in private configuration.
3. Build/sign the public APK, verify its actual manifest, certificate and assets, and create a source archive from the reviewed commit.
4. Promote validated work to `master`, create an annotated immutable version tag and attach the APK, source ZIP, integrity record, checksums and release notes to that exact tag. A source-only release must say so explicitly.
5. Verify uploaded bytes and release links, update `assets/release.json` and the current integrity record, then publish the documentation. Do not enable download links for unverified uploads.
6. Record device/live-provider acceptance separately from host tests. Do not invent an artifact, backdate a tag or move an old version tag to newer code.
