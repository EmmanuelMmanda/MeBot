#!/usr/bin/env python3
"""Validate the repository's static documentation without network or dependencies."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
errors = []
references = []
ids_by_file = {}


def error(path, message):
    errors.append(f'{path.relative_to(ROOT)}: {message}')


class Page(HTMLParser):
    def __init__(self, path, full_page=True):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.ids = set()
        self.has_lang = False
        self.has_viewport = False
        self.has_title = False
        self.h1_count = 0
        self.full_page = full_page

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            if attrs['id'] in self.ids:
                error(self.path, f'duplicate id {attrs["id"]!r}')
            self.ids.add(attrs['id'])
        if tag == 'html':
            self.has_lang = bool(attrs.get('lang'))
        if tag == 'meta' and attrs.get('name') == 'viewport':
            self.has_viewport = True
        if tag == 'title':
            self.has_title = True
        if tag == 'h1':
            self.h1_count += 1
        if tag == 'img' and 'alt' not in attrs:
            error(self.path, 'image has no alt attribute')
        for name in ('href', 'src', 'poster'):
            if attrs.get(name):
                references.append((self.path, attrs[name], self.full_page))
        if attrs.get('srcset'):
            for candidate in attrs['srcset'].split(','):
                references.append((self.path, candidate.strip().split()[0], self.full_page))

    handle_startendtag = handle_starttag


def markdown_ids(text):
    result = set()
    occurrences = {}
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', text, re.M):
        heading = re.sub(r'\[([^]]+)\]\([^)]+\)', r'\1', heading)
        slug = re.sub(r'[^\w\-\s]', '', heading.lower()).replace(' ', '-')
        n = occurrences.get(slug, 0)
        occurrences[slug] = n + 1
        result.add(slug if n == 0 else f'{slug}-{n}')
    return result


def inspect_files():
    html_files = sorted(DOCS.rglob('*.html'))
    for path in html_files:
        parser = Page(path)
        parser.feed(path.read_text())
        ids_by_file[path] = parser.ids
        if not parser.has_lang or not parser.has_viewport or not parser.has_title:
            error(path, 'missing document language, viewport or title')
        if parser.h1_count != 1:
            error(path, f'expected one h1, found {parser.h1_count}')
    markdown = sorted(set(DOCS.rglob('*.md')) | set(ROOT.glob('*.md')) | {ROOT / 'service/README.md'})
    for path in markdown:
        text = re.sub(r'```.*?```', '', path.read_text(), flags=re.S)
        ids_by_file[path] = markdown_ids(text)
        parser = Page(path, full_page=False)
        parser.feed(text)
        ids_by_file[path] |= parser.ids
        for match in re.finditer(r'!?\[[^\]\n]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)', text):
            references.append((path, match.group(1).strip('<>'), False))
    for path in DOCS.rglob('*.css'):
        for reference in re.findall(r'url\([\'\"]?([^\)\'\"]+)', path.read_text()):
            references.append((path, reference.strip(), True))
    return len(html_files), len(markdown)


def check_references():
    for path, reference, site_only in references:
        parts = urlsplit(reference)
        if parts.scheme or parts.netloc:
            if parts.scheme.lower() in {'javascript', 'file'}:
                error(path, f'unsafe/nonportable URL {reference}')
            continue
        if parts.path.startswith('/'):
            error(path, f'root-relative URL breaks project-site hosting: {reference}')
            continue
        target = (path.parent / unquote(parts.path)).resolve() if parts.path else path
        boundary = DOCS if site_only else ROOT
        if not target.is_relative_to(boundary):
            error(path, f'local link escapes {boundary.name}: {reference}')
            continue
        if target.is_dir():
            if site_only:
                target /= 'index.html'
            else:
                continue
        if not target.is_file():
            error(path, f'missing local target {reference}')
        elif parts.fragment and target in ids_by_file:
            if unquote(parts.fragment) not in ids_by_file[target]:
                error(path, f'missing anchor {reference}')


def check_catalog():
    path = DOCS / 'assets/release.json'
    if not path.is_file():
        error(path, 'release catalog is missing')
        return
    try:
        catalog = json.loads(path.read_text())
        integrity_path = (DOCS / catalog.get('integrityFile', 'release-integrity.json')).resolve()
        if not integrity_path.is_relative_to(DOCS.resolve()):
            raise ValueError('Integrity file must stay inside docs')
        integrity = json.loads(integrity_path.read_text())
        entries = catalog['releases']
        # A source-qualified preview is not a signed public APK. Validate its
        # absence of publication claims, then run all public checks on the
        # actual published release rather than weakening those contracts.
        if catalog.get('status') == 'pending' and catalog.get('apk') is False:
            for field in ('publicAssetsAvailable', 'inAppUpdateAvailable'):
                if catalog.get(field) is not False:
                    error(path, f'unsigned preview must set {field} false')
            if catalog.get('localArtifactsVerified') is not True or not isinstance(catalog.get('sourceSizeBytes'), int) or catalog['sourceSizeBytes'] <= 0:
                error(path, 'unsigned preview requires verified source archive bytes')
            if catalog.get('privateCandidate') is not True:
                error(path, 'unsigned preview must remain a private candidate')
            for field in ('filename', 'sha256', 'sizeBytes', 'certificateSha256'):
                if catalog.get(field) is not None:
                    error(path, f'unsigned preview cannot advertise {field}')
            if catalog.get('publishedAt') or catalog.get('publishedDate'):
                error(path, 'unsigned preview cannot advertise a publication date')
            for field in ('sourceCommit', 'sourceTree'):
                if not re.fullmatch(r'[0-9a-f]{40}', catalog.get(field) or ''):
                    error(path, f'unsigned preview requires exact {field}')
            if not re.fullmatch(r'[0-9a-f]{64}', catalog.get('sourceSha256') or ''):
                error(path, 'unsigned preview requires source archive SHA-256')
            for field, proof in (('version','version'),('sourceCommit','source_code_commit'),('sourceTree','source_tree'),('sourceSha256','source_sha256'),('sourceSizeBytes','source_size_bytes'),('filename','apk_file'),('sha256','apk_sha256'),('sizeBytes','apk_size_bytes'),('certificateSha256','signer_certificate_sha256')):
                if catalog.get(field) != integrity.get(proof):
                    error(path, f'unsigned preview {field} differs from integrity')
            if integrity.get('publication_status') != 'pending' or integrity.get('apk_signature_verified') is not False or integrity.get('public_assets_available') is not False or integrity.get('in_app_update_available') is not False:
                error(path, 'unsigned preview integrity must not claim signing or publication')
            if integrity.get('published_at') or integrity.get('publication_date'):
                error(path, 'unsigned preview integrity cannot advertise publication date')
            public = next((e for e in entries if e.get('status') == 'published' and e.get('apk') is True), None)
            if public is None:
                raise ValueError('Preview requires an explicitly identified published download')
            catalog = public
            integrity_path = (DOCS / catalog['integrityFile']).resolve()
            if not integrity_path.is_relative_to(DOCS.resolve()):
                raise ValueError('Published integrity file must stay inside docs')
            integrity = json.loads(integrity_path.read_text())
        versions = [entry['version'] for entry in entries]
        if len(versions) != len(set(versions)):
            error(path, 'duplicate versions')
        if catalog['version'] != integrity['version']:
            error(path, 'latest version differs from release integrity')
        latest = next((entry for entry in entries if entry['version'] == catalog['version']), None)
        if latest is None:
            error(path, 'latest release has no matching history entry')
        for catalog_field, integrity_field in (
            ('applicationId', 'application_id'),
            ('package', 'package'),
            ('versionCode', 'version_code'),
            ('sourceCommit', 'source_code_commit'),
            ('sourceTree', 'source_tree'),
            ('certificateSha256', 'signer_certificate_sha256'),
            ('filename', 'apk_file'),
            ('sha256', 'apk_sha256'),
            ('sizeBytes', 'apk_size_bytes'),
            ('sourceFilename', 'source_file'),
            ('sourceSha256', 'source_sha256'),
            ('sourceSizeBytes', 'source_size_bytes'),
            ('verificationFilename', 'verification_file'),
            ('verificationSha256', 'verification_sha256'),
            ('releaseNotesFilename', 'release_notes_file'),
            ('releaseNotesSha256', 'release_notes_sha256'),
            ('releaseNotesSizeBytes', 'release_notes_size_bytes'),
            ('checksumFilename', 'checksums_file'),
            ('checksumSha256', 'checksums_sha256'),
            ('checksumSizeBytes', 'checksums_size_bytes'),
            ('releaseMetadataFilename', 'release_metadata_file'),
            ('releaseMetadataSha256', 'release_metadata_sha256'),
            ('releaseMetadataSizeBytes', 'release_metadata_size_bytes'),
        ):
            if catalog.get(catalog_field) != integrity.get(integrity_field):
                error(path, f'latest {catalog_field} differs from release integrity')
        if catalog.get('status') != integrity.get('publication_status'):
            error(path, 'publication status differs from release integrity')
        if 'assets' in integrity:
            assets = integrity['assets']
            expected = []
            for name, digest, size in [('filename','sha256','sizeBytes'),('sourceFilename','sourceSha256','sourceSizeBytes'),('verificationFilename','verificationSha256','verificationSizeBytes'),('releaseNotesFilename','releaseNotesSha256','releaseNotesSizeBytes'),('checksumFilename','checksumSha256','checksumSizeBytes'),('releaseMetadataFilename','releaseMetadataSha256','releaseMetadataSizeBytes')]:
                expected.append(catalog[name])
                asset = next((a for a in assets if a.get('name') == catalog[name]), {})
                url = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{catalog['tag']}/{catalog[name]}"
                if asset.get('digest') != 'sha256:' + catalog[digest] or asset.get('size') != catalog[size] or asset.get('browser_download_url') != url:
                    error(path, f'published asset proof differs: {catalog[name]}')
            if sorted(a.get('name','') for a in assets) != sorted(expected):
                error(path, 'published proof must enumerate exactly the six catalogued assets')
        if integrity.get('personal_profile_asset_present') is not False:
            error(path, 'current public integrity must exclude a personal profile')
        for entry in [catalog, *entries]:
            version = entry['version']
            if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?', version) or entry['tag'] != 'v' + version:
                error(path, f'invalid version/tag {version!r}')
            if entry['status'] not in {'pending', 'published'}:
                error(path, f'unknown publication state for {version}')
            private_pending = entry.get('status') == 'pending' and entry.get('privateCandidate') is True
            if private_pending and not entry.get('localArtifactsVerified') and any(entry.get(k) is not None for k in ('sourceCommit', 'sourceTree', 'filename', 'sha256', 'sizeBytes')):
                error(path, f'private candidate {version} contains unverified release identity')
            if (not private_pending or entry.get('localArtifactsVerified')) and not re.fullmatch(r'[0-9a-f]{40}', entry.get('sourceCommit') or ''):
                error(path, f'missing authentic source commit for {version}')
            if entry.get('apk', True):
                if entry.get('filename') not in {f'mebot-{version}.apk', f'MeBot-{version}.apk'}:
                    error(path, f'invalid APK filename for {version}')
                if not re.fullmatch(r'[0-9a-f]{64}', entry.get('sha256') or ''):
                    error(path, f'missing APK checksum for {version}')
                if not isinstance(entry.get('sizeBytes'), int) or entry['sizeBytes'] <= 0:
                    error(path, f'invalid APK byte count for {version}')
            elif entry.get('filename') or entry.get('sha256') or entry.get('sizeBytes'):
                error(path, f'source-only version {version} advertises an APK')
        for field, integrity_field in [('sha256', 'apk_sha256'), ('sizeBytes', 'apk_size_bytes'), ('filename', 'apk_file')]:
            if catalog.get(field) != integrity.get(integrity_field):
                error(path, f'latest {field} differs from public integrity')
        if catalog.get('filename') != f"mebot-{catalog.get('version')}.apk":
            error(path, 'latest public APK filename must be lowercase and version-matched')
        for field in ('sha256', 'sourceSha256', 'verificationSha256', 'releaseNotesSha256', 'checksumSha256', 'releaseMetadataSha256'):
            if not re.fullmatch(r'[0-9a-f]{64}', catalog.get(field) or ''):
                error(path, f'latest {field} must be a lowercase SHA-256 digest')
        for field in ('sourceCommit', 'sourceTree'):
            if not re.fullmatch(r'[0-9a-f]{40}', catalog.get(field) or ''):
                error(path, f'latest {field} must be a full lowercase git object id')
        latest_entry = latest
        if latest_entry is None or any(latest_entry.get(key) != catalog.get(key) for key in ['tag', 'status', 'filename', 'sha256', 'sizeBytes', 'sourceCommit']):
            error(path, 'latest entry and top-level release metadata disagree')
    except (ValueError, KeyError, TypeError) as exc:
        error(path, f'malformed release metadata: {exc}')


def main():
    html_count, markdown_count = inspect_files()
    check_references()
    check_catalog()
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        print(f'{len(errors)} documentation errors.', file=sys.stderr)
        return 1
    print(f'Documentation checks passed: {html_count} HTML pages, {markdown_count} Markdown files, {len(references)} local/external references, public release metadata consistent. External URLs require publication verification.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
