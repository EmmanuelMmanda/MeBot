#!/usr/bin/env python3
"""Promote the prepared 0.9.6 site catalog after public release verification.

This script changes only local website metadata/docs and renders the static site.
It does not publish a release, write to GitHub, deploy Pages, or contact a server.
Both timestamps and an explicit confirmation are required so no date is guessed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
VERSION = '0.9.6'
TAG = 'v0.9.6'
PACKAGE = 'mebot.nguli.com'
SOURCE_COMMIT = 'b7c85878653321d184377cd4fed6c68502f4cb3b'
SOURCE_TREE = '2cceb13a1c647da3aa1ac6c67b3347a9fb186aef'
CERT = 'c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007'
REPO = 'https://github.com/EmmanuelMmanda/MeBot'


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def parse_utc(value: str, label: str) -> datetime:
    # Require an explicit UTC offset, then canonicalize to ISO-Z for the site.
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f'{label} must be an ISO-8601 timestamp') from exc
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise argparse.ArgumentTypeError(f'{label} must include the UTC offset Z or +00:00')
    return parsed.astimezone(timezone.utc)


def iso_z(value: datetime) -> str:
    return value.isoformat(timespec='microseconds').replace('+00:00', 'Z') if value.microsecond else value.isoformat(timespec='seconds').replace('+00:00', 'Z')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Expected exactly one {label} block, found {count}; refusing partial promotion')
    return text.replace(old, new, 1)


def verify_bundle(assets: Path, manifest: dict) -> dict:
    meta_path = assets / 'release-metadata.json'
    sums_path = assets / 'SHA256SUMS.txt'
    if not meta_path.is_file() or not sums_path.is_file():
        raise SystemExit('Bundle must contain release-metadata.json and SHA256SUMS.txt')
    meta = json.loads(meta_path.read_text())
    if (meta.get('version') != VERSION or meta.get('versionCode') != 23
            or meta.get('applicationId') != PACKAGE
            or meta.get('sourceCommit') != SOURCE_COMMIT
            or meta.get('sourceTree') != SOURCE_TREE
            or meta.get('signerCertificateSha256') != CERT):
        raise SystemExit('Bundle identity is not the expected signed 0.9.6 candidate')
    sums = {}
    for line in sums_path.read_text().splitlines():
        if line.strip():
            checksum, name = line.split(None, 1)
            sums[name.strip()] = checksum
    for name, checksum in sums.items():
        path = assets / name
        if not path.is_file() or sha256(path) != checksum:
            raise SystemExit(f'Bundle checksum mismatch: {name}')
    if sha256(meta_path) != manifest['releaseMetadataSha256']:
        raise SystemExit('Release-metadata digest differs from the pending catalog')
    if sha256(sums_path) != manifest['checksumSha256']:
        raise SystemExit('Checksum-file digest differs from the pending catalog')
    for item in meta.get('assets', []):
        name = item['name']
        path = assets / name
        if (sums.get(name) != item['sha256'] or sha256(path) != item['sha256']
                or path.stat().st_size != item['bytes']):
            raise SystemExit(f'Bundle metadata does not match {name}')
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True,
                        help='The exact local mebot-publication-assets/0.9.6 bundle')
    parser.add_argument('--published-at', required=True,
                        help='Actual public release timestamp in UTC ISO-8601, e.g. 2026-10-27T12:34:56Z')
    parser.add_argument('--public-assets-verified-at', required=True,
                        help='Actual anonymous asset-verification timestamp in UTC ISO-8601')
    parser.add_argument('--public-release-id', required=True, type=int,
                        help='GitHub release ID whose assets were publicly verified')
    parser.add_argument('--confirm-publication', action='store_true',
                        help='Confirm release is public and hosted source, asset, and updater gates passed')
    args = parser.parse_args()
    if not args.confirm_publication:
        raise SystemExit('No changes made: pass --confirm-publication only after all public gates pass')
    if args.public_release_id <= 0:
        raise SystemExit('--public-release-id must be a positive GitHub release ID')

    published_at = parse_utc(args.published_at, '--published-at')
    assets_verified_at = parse_utc(args.public_assets_verified_at, '--public-assets-verified-at')
    if assets_verified_at < published_at:
        raise SystemExit('Anonymous asset verification cannot precede publication')
    published_iso, verified_iso = iso_z(published_at), iso_z(assets_verified_at)

    manifest_path = DOCS / 'assets/release.json'
    integrity_path = DOCS / 'releases/v0.9.6.json'
    manifest = json.loads(manifest_path.read_text())
    integrity = json.loads(integrity_path.read_text())
    if (manifest.get('version') != VERSION or manifest.get('status') != 'pending'
            or manifest.get('sourceCommit') != SOURCE_COMMIT
            or manifest.get('sourceTree') != SOURCE_TREE):
        raise SystemExit('Expected the exact, pending 0.9.6 manifest; refusing to overwrite other release state')
    if integrity.get('publication_status') != 'pending':
        raise SystemExit('Expected pending 0.9.6 integrity metadata; refusing duplicate promotion')
    meta = verify_bundle(args.assets.resolve(), manifest)
    if meta['sourceTargetBranch'] != manifest.get('sourceRef'):
        raise SystemExit('Pending sourceRef no longer matches the exact bundle source target branch')

    release_url = f'{REPO}/releases/tag/{TAG}'
    manifest.update(status='published', privateCandidate=False,
                    publicAssetsAvailable=True, inAppUpdateAvailable=True,
                    publishedDate=published_at.date().isoformat(),
                    publishedAt=published_iso,
                    publicAssetsVerifiedAt=verified_iso)
    previous = next((x for x in manifest['releases'] if x.get('version') == '0.9.5'), None)
    candidate = next((x for x in manifest['releases'] if x.get('version') == VERSION), None)
    if previous is None or candidate is None:
        raise SystemExit('Required 0.9.5 history or 0.9.6 candidate entry is missing')
    previous['recommendation'] = 'superseded'
    previous['notes'] = (
        'Superseded by MeBot 0.9.6. Scope warning: if a question is sent immediately after opening a story, '
        'it may land in a general chat instead of that story; wait for the story conversation to be ready. '
        'The correction is in 0.9.6.'
    )
    candidate.update({key: value for key, value in manifest.items() if key != 'releases'})
    candidate['status'] = 'published'
    manifest['releases'] = [candidate, *[x for x in manifest['releases'] if x.get('version') != VERSION]]

    integrity.update(
        publication_status='published',
        publication_date=published_at.date().isoformat(),
        published_at=published_iso,
        public_assets_available=True,
        in_app_update_available=True,
        public_release_assets_verified=True,
        public_assets_verified_at=verified_iso,
        public_release_id=args.public_release_id,
        public_release_url=release_url,
        public_release_latest=True,
        anonymous_asset_verification_at=verified_iso,
        source_ref=manifest['sourceRef'],
    )

    # Replace candidate-only prose, preserving the historical files and 0.9.5 integrity record.
    public_notes_path = DOCS / 'PUBLIC-RELEASES.md'
    public_notes = public_notes_path.read_text()
    public_notes = replace_once(
        public_notes,
        '**Prepared candidate only:** MeBot 0.9.6 corrects the story-conversation opening, restore and lock/unlock edge case. It is signed locally, but public availability, hosted exact-source checks and site promotion remain pending. The current official release is still 0.9.5; do not sideload candidate files. **Scope warning for 0.9.5:** after opening a story, wait for its conversation to finish opening before sending a question; sending earlier could lose that story\'s scope. The correction is not publicly available yet.',
        f'**MeBot 0.9.6 is the current official release**, published {published_iso} and anonymously asset-verified {verified_iso}. It fixes the story-conversation opening, restore and lock/unlock edge case. **Scope warning for superseded 0.9.5:** after opening a story, wait for its conversation to finish opening before sending a question; sending earlier could land in a general chat instead of that story. The fix is available in 0.9.6.',
        'pending public-release overview')
    public_notes = replace_once(
        public_notes,
        '**MeBot 0.9.5 is the recommended current release**, published for package `mebot.nguli.com` and signed with the established 0.9.x production certificate.',
        '**MeBot 0.9.5 is a superseded historical signed release**; its original APK, source and integrity record remain unchanged.',
        '0.9.5 current-release label')
    public_notes = replace_once(
        public_notes,
        'It updates production 0.9.0–0.9.4 installations in place.',
        'The current 0.9.6 release keeps the `mebot.nguli.com` package and established 0.9.x production certificate, and updates production 0.9.0–0.9.5 installations in place.',
        'current update coverage')
    public_notes = replace_once(public_notes,
                                '## Prepared 0.9.6 candidate — not a public release',
                                '## Current official 0.9.6 release',
                                '0.9.6 candidate heading')
    public_notes = replace_once(public_notes, 'Candidate identity:', 'Release identity:', 'candidate identity label')
    public_notes = replace_once(public_notes,
                                'The exact APK, source archive and report hashes are in [pending site integrity metadata](releases/v0.9.6.json).',
                                'The exact APK, source archive and report hashes are in [site integrity metadata](releases/v0.9.6.json).',
                                'pending integrity link')
    public_notes = replace_once(public_notes,
                                'No publication date is assigned here. Once the release assets and hosted checks are verified, promote the manifest and record the actual publication timestamp; then mark 0.9.5 superseded in the live catalog while leaving its signed APK, source archive and `releases/v0.9.5.json` untouched.',
                                f'Publication timestamp: `{published_iso}`. Anonymous public asset verification: `{verified_iso}` (release ID `{args.public_release_id}`). The 0.9.5 APK, source archive and `releases/v0.9.5.json` remain untouched.',
                                'unpublished release procedure')
    public_notes = replace_once(public_notes, '## Current 0.9.5 release',
                                '## Historical 0.9.5 release (superseded)',
                                '0.9.5 historical heading')
    old_095_sentence = ('MeBot 0.9.5 was published on 26 October 2026 at 23:35:05 UTC. Stories open in MeBot first; '
                        'compact category labels and item-grounded questions keep the next step close. Suggested '
                        'questions are editable and never send automatically. The original source remains available, '
                        'and local questions work offline.')
    new_095_sentence = ('MeBot 0.9.5 was published on 26 October 2026 at 23:35:05 UTC and is superseded by 0.9.6. '
                        'Its story-first flow, compact category labels and item-grounded editable questions remain '
                        'available in the historical release. Scope warning: a question sent before the story '
                        'conversation opens may land in a general chat instead of that story; wait until it is ready, '
                        'or update to 0.9.6. The original source and signed APK remain unchanged.')
    public_notes = replace_once(public_notes, old_095_sentence, new_095_sentence, '0.9.5 superseded scope warning')
    public_notes = replace_once(
        public_notes,
        '**0.9.0 through 0.9.5** use package `mebot.nguli.com` and the same production certificate shown above. MeBot 0.9.5 can update 0.9.0–0.9.4 in place. **Do not uninstall or clear data to update.**',
        '**0.9.0 through 0.9.6** use package `mebot.nguli.com` and the same production certificate shown above. MeBot 0.9.6 can update production 0.9.0–0.9.5 in place. **Do not uninstall or clear data to update.**',
        'signing lineage and update coverage')
    install_path = DOCS / 'INSTALL.md'
    install = install_path.read_text()
    install = replace_once(install, '# Install or update MeBot 0.9.5', '# Install or update MeBot 0.9.6', 'install guide title')
    install = replace_once(install,
                           'Download **mebot-0.9.5.apk** from the [official 0.9.5 release page](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.5).',
                           'Download **mebot-0.9.6.apk** from the [official 0.9.6 release page](https://github.com/EmmanuelMmanda/MeBot/releases/tag/v0.9.6).',
                           'install guide download link')
    install = replace_once(install,
                           '**MeBot 0.9.5 is the current official release.** It keeps the 0.9.x package and production signing identity; Android can update an existing 0.9.0–0.9.4 installation in place. No uninstall or data clear is needed. A 0.9.6 corrective candidate is prepared but is not yet publicly available. Until it is released, if you open a story, wait for its conversation to finish opening before sending a question; an earlier send could lose that story\'s context.',
                           '**MeBot 0.9.6 is the current official release.** It keeps the 0.9.x package and production signing identity; Android can update an existing 0.9.0–0.9.5 installation in place. No uninstall or data clear is needed. If you are still using 0.9.5, wait for the story conversation to finish opening before sending a question; an earlier send could lose that story\'s context. The correction is included in 0.9.6.',
                           'install guide pending scope warning')
    install = replace_once(install, '## Updating from 0.9.0 through 0.9.4', '## Updating from 0.9.0 through 0.9.5', 'install update range')
    install = replace_once(install,
                           'The 0.9.5 rapid story-question edge case is under correction in the pending 0.9.6 candidate; wait for the story conversation to be ready before sending. Its publication and in-app availability are not confirmed yet.',
                           'MeBot 0.9.6 corrects the rapid story-question scope edge case from 0.9.5. If you keep using 0.9.5, wait until the story conversation is ready before sending.',
                           'install guide correction status')
    install = replace_once(install,
                           '0.8.x binaries used an earlier signing certificate, so Android will not accept 0.9.5 as an in-place update to those builds.',
                           '0.8.x binaries used an earlier signing certificate, so Android will not accept 0.9.6 as an in-place update to those builds.',
                           'legacy install lineage copy')
    install = replace_once(install,
                           '[Experience-first release notes](RELEASE-0.9.5.md)',
                           '[Experience-first release notes](RELEASE-0.9.6.md)',
                           'install guide release notes link')
    public_notes_path.write_text(public_notes)
    install_path.write_text(install)

    # Current guide links and updater claims must follow the official catalog too.
    user_guide_path = DOCS / 'USER-GUIDE.md'
    user_guide = user_guide_path.read_text()
    user_guide_replacements = (
        ('> **MeBot 0.9.5:** stories open in MeBot first, with a compact category label and item-grounded questions in Feed, story details and MeBot. Review or edit a prepared question before choosing to send; original sources remain accessible and local questions work offline. [Current release notes](RELEASE-0.9.5.md) · [Verification and device/provider limits](PUBLIC-RELEASES.md).',
         '> **MeBot 0.9.6:** start typing as soon as you open a story; its question stays attached while the conversation prepares, and Send waits for the correct conversation. Rotation, reopening and app lock restore the story without sending a pending question. [Current release notes](RELEASE-0.9.6.md) · [Verification and device/provider limits](PUBLIC-RELEASES.md).',
         'user-guide current overview'),
        ('# MeBot user guide — 0.9.5', '# MeBot user guide — 0.9.6', 'user-guide heading'),
        ('[Start here](../START-HERE.md) · [Illustrated guide](guides/release-082.html) · [Current release notes](RELEASE-0.9.5.md)',
         '[Start here](../START-HERE.md) · [Illustrated guide](guides/release-082.html) · [Current release notes](RELEASE-0.9.6.md)',
         'user-guide release-notes link'),
        ('Install the official `mebot.nguli.com` package. MeBot 0.9.5 updates production 0.9.0–0.9.4 without uninstalling; earlier signer/package installations have different migration requirements. See [installation guidance](INSTALL.md).',
         'Install the official `mebot.nguli.com` package. MeBot 0.9.6 updates production 0.9.0–0.9.5 without uninstalling; earlier signer/package installations have different migration requirements. See [installation guidance](INSTALL.md).',
         'user-guide supported update range'),
        ('Stories open in MeBot first, with the publisher visible and a compact category label. From Feed, story details or MeBot, a suggested question stays tied to the item. Selecting it prepares an editable, item-scoped draft; it never sends automatically. The original source remains a secondary action, and local questions remain available offline. See the [illustrated story-flow guide](preview-095.html).',
         'Stories open in MeBot first, with the publisher visible and a compact category label. In 0.9.6, the selected story stays attached while its conversation opens: start typing immediately, and Send waits until the correct conversation is ready. Rotation, reopening and app lock restore the story without sending a pending question. Suggested questions remain editable; they never send automatically. The original source stays available as a secondary action, and local questions work offline. If you still use 0.9.5, wait until the story conversation is ready before sending; an earlier question could land in a general chat instead. See the [current story-conversation guide](preview-096.html) and the [historical 0.9.5 story-flow guide](preview-095.html).',
         'user-guide story-conversation behavior'),
        ('Automatic checks inspect the trusted public release list; the current 0.9.5 release is offered to production installations on 0.9.0–0.9.4.',
         'Automatic checks inspect the trusted public release list; the current 0.9.6 release is offered to production installations on 0.9.0–0.9.5.',
         'user-guide current updater'),
    )
    for old, new, label in user_guide_replacements:
        user_guide = replace_once(user_guide, old, new, label)
    user_guide_path.write_text(user_guide)

    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    integrity_path.write_text(json.dumps(integrity, indent=2, ensure_ascii=False) + '\n')

    subprocess.run([sys.executable, 'scripts/build-showcase.py'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'scripts/check-docs.py'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'scripts/check-showcase.py'], cwd=ROOT, check=True)
    print(f'Locally prepared published 0.9.6 site metadata for {published_iso}; no remote writes made.')


if __name__ == '__main__':
    main()
