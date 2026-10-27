#!/usr/bin/env python3
"""Prepare the exact, local-only MeBot 0.9.6 pending website catalog.

This consumes a verified publication bundle but never copies assets into Pages,
claims publication, contacts GitHub, or changes any upstream state.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
VERSION = '0.9.6'
PACKAGE = 'mebot.nguli.com'
CERT = 'c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007'


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def write_json(path: Path, obj: dict) -> None:
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + '\n')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True,
                        help='Path to the local mebot-publication-assets/0.9.6 bundle')
    args = parser.parse_args()
    assets = args.assets.resolve()
    metadata_path = assets / 'release-metadata.json'
    sums_path = assets / 'SHA256SUMS.txt'
    if not metadata_path.is_file() or not sums_path.is_file():
        raise SystemExit('Expected release-metadata.json and SHA256SUMS.txt in the bundle')
    metadata = json.loads(metadata_path.read_text())
    if metadata.get('version') != VERSION or metadata.get('versionCode') != 23:
        raise SystemExit('Unexpected release version identity')
    if metadata.get('applicationId') != PACKAGE or metadata.get('signerCertificateSha256') != CERT:
        raise SystemExit('Unexpected package or signer identity')
    if metadata.get('sourceCommit') != 'b7c85878653321d184377cd4fed6c68502f4cb3b':
        raise SystemExit('Unexpected public source commit')
    if metadata.get('sourceTree') != '2cceb13a1c647da3aa1ac6c67b3347a9fb186aef':
        raise SystemExit('Unexpected source tree')
    if metadata.get('sourceArchiveOriginalVerifiedCommit') != '0ec24f495dd013b815678dbac24d19b7f5887399':
        raise SystemExit('Unexpected original source archive commit')

    expected = {}
    for line in sums_path.read_text().splitlines():
        if not line.strip():
            continue
        sha, filename = line.split(None, 1)
        expected[filename.strip()] = sha
    for filename, sha in expected.items():
        path = assets / filename
        if not path.is_file() or digest(path) != sha:
            raise SystemExit(f'Bundle asset hash mismatch: {filename}')
    for item in metadata.get('assets', []):
        filename = item['name']
        path = assets / filename
        if filename not in expected or digest(path) != item['sha256'] or path.stat().st_size != item['bytes']:
            raise SystemExit(f'Metadata does not match bundle asset: {filename}')

    current_manifest = json.loads((DOCS / 'assets/release.json').read_text())
    history = current_manifest['releases']
    existing = next((x for x in history if x.get('version') == VERSION), None)
    if existing and existing.get('sourceCommit') != metadata['sourceCommit']:
        raise SystemExit('Refusing to replace a different 0.9.6 identity')

    by_name = {item['name']: item for item in metadata['assets']}
    apk = by_name['mebot-0.9.6.apk']
    source = by_name['MeBot-0.9.6-source.zip']
    verification = by_name['MeBot-0.9.6-verification.md']
    release_notes_asset = by_name['RELEASE-NOTES.md']
    checksum_size = (assets / 'SHA256SUMS.txt').stat().st_size
    candidate_notes = (
        'Keeps a question with its selected story while the conversation opens, restores the story '
        'after rotation or reopening, and prevents a lock/unlock cycle from sending a pending draft.'
    )
    entry = {
        'schemaVersion': 1,
        'version': VERSION,
        'tag': 'v' + VERSION,
        'status': 'pending',
        'privateCandidate': True,
        'localArtifactsVerified': True,
        'publicAssetsAvailable': False,
        'inAppUpdateAvailable': False,
        'channel': 'official',
        'publicationKind': 'current-production',
        'apk': True,
        'filename': apk['name'],
        'plannedApkFilename': apk['name'],
        'sourceCommit': metadata['sourceCommit'],
        'sourceTree': metadata['sourceTree'],
        'sourceRef': metadata['sourceTargetBranch'],
        'sourceArchiveOriginalVerifiedCommit': metadata['sourceArchiveOriginalVerifiedCommit'],
        'sourceIntegrityFile': 'SHA256SUMS.txt',
        'versionCode': metadata['versionCode'],
        'applicationId': PACKAGE,
        'package': PACKAGE,
        'minimumSdk': metadata['minimumSdk'],
        'minimumAndroid': '8.0',
        'targetSdk': metadata['targetSdk'],
        'sha256': apk['sha256'],
        'sizeBytes': apk['bytes'],
        'sourceFilename': source['name'],
        'sourceSha256': source['sha256'],
        'sourceSizeBytes': source['bytes'],
        'verificationFilename': verification['name'],
        'verificationSha256': verification['sha256'],
        'verificationSizeBytes': verification['bytes'],
        'releaseNotesFilename': release_notes_asset['name'],
        'releaseNotesSha256': release_notes_asset['sha256'],
        'releaseNotesSizeBytes': release_notes_asset['bytes'],
        'checksumFilename': 'SHA256SUMS.txt',
        'checksumSha256': digest(sums_path),
        'checksumSizeBytes': checksum_size,
        'releaseMetadataFilename': metadata_path.name,
        'releaseMetadataSha256': digest(metadata_path),
        'releaseMetadataSizeBytes': metadata_path.stat().st_size,
        'integrityFile': 'releases/v0.9.6.json',
        'certificateSha256': CERT,
        'notes': candidate_notes,
    }

    # Preserve the immutable 0.9.5 integrity document and public release files.
    # Only its live catalog entry receives a small warning about the known edge case.
    history = [x for x in history if x.get('version') != VERSION]
    for previous in history:
        if previous.get('version') == '0.9.5':
            previous['notes'] = (
                'Current official release. Scope warning: if a question is sent immediately after opening '
                'a story, it may not stay with that story; wait for its conversation to open before sending. '
                'A fix is prepared in 0.9.6, not yet publicly available.'
            )
            previous.pop('recommendation', None)
    current_manifest.update(entry)
    current_manifest['releases'] = [entry.copy(), *history]
    current_manifest.pop('publishedDate', None)
    current_manifest.pop('publishedAt', None)
    current_manifest.pop('publicAssetsVerifiedAt', None)
    write_json(DOCS / 'assets/release.json', current_manifest)

    integrity = {
        'version': VERSION,
        'publication_status': 'pending',
        'publication_kind': 'current-production',
        'publication_date': None,
        'published_at': None,
        'public_assets_available': False,
        'in_app_update_available': False,
        'application_id': PACKAGE,
        'package': PACKAGE,
        'label': 'MeBot',
        'version_code': metadata['versionCode'],
        'min_sdk': metadata['minimumSdk'],
        'target_sdk': metadata['targetSdk'],
        'apk_file': apk['name'],
        'apk_size_bytes': apk['bytes'],
        'apk_sha256': apk['sha256'],
        'apk_signature_verified': True,
        'signature_schemes_verified': ['v2', 'v3'],
        'zip_alignment_16k_verified': True,
        'debuggable': False,
        'test_only': False,
        'same_certificate_as_previous_release': True,
        'signer_certificate_sha256': CERT,
        'source_code_commit': metadata['sourceCommit'],
        'source_tree': metadata['sourceTree'],
        'source_archive_original_verified_commit': metadata['sourceArchiveOriginalVerifiedCommit'],
        'source_file': source['name'],
        'source_sha256': source['sha256'],
        'source_size_bytes': source['bytes'],
        'verification_file': verification['name'],
        'verification_sha256': verification['sha256'],
        'verification_size_bytes': verification['bytes'],
        'release_notes_file': release_notes_asset['name'],
        'release_notes_sha256': release_notes_asset['sha256'],
        'release_notes_size_bytes': release_notes_asset['bytes'],
        'checksums_file': 'SHA256SUMS.txt',
        'checksums_sha256': digest(sums_path),
        'checksums_size_bytes': checksum_size,
        'release_metadata_file': metadata_path.name,
        'release_metadata_sha256': digest(metadata_path),
        'release_metadata_size_bytes': metadata_path.stat().st_size,
        'local_artifacts_verified': True,
        'public_release_assets_verified': False,
        'personal_profile_asset_present': False,
        'public_assets_verified_at': None,
        'public_release_url': None,
        'public_release_latest': False,
        'anonymous_asset_verification_at': None,
    }
    write_json(DOCS / 'releases/v0.9.6.json', integrity)
    (DOCS / 'RELEASE-0.9.6.md').write_text((assets / 'RELEASE-NOTES.md').read_text())

    # The six 0.9.5 story captures are now released examples, not candidate captures.
    screens_path = DOCS / 'assets/screens.json'
    screens = json.loads(screens_path.read_text())
    for screen in screens:
        if screen.get('version') == '0.9.5':
            screen['candidatePreview'] = False
    write_json(screens_path, screens)
    print('Prepared local MeBot 0.9.6 pending catalog; public availability remains false.')

if __name__ == '__main__':
    main()
