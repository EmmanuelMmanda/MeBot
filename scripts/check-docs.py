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
        versions = [entry['version'] for entry in entries]
        if len(versions) != len(set(versions)):
            error(path, 'duplicate versions')
        if catalog['version'] != integrity['version']:
            error(path, 'latest version differs from release integrity')
        if integrity.get('personal_profile_asset_present') is not False:
            error(path, 'current public integrity must exclude a personal profile')
        for entry in [catalog, *entries]:
            version = entry['version']
            if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?', version) or entry['tag'] != 'v' + version:
                error(path, f'invalid version/tag {version!r}')
            if entry['status'] not in {'pending', 'published'}:
                error(path, f'unknown publication state for {version}')
            if not re.fullmatch(r'[0-9a-f]{40}', entry.get('sourceCommit') or ''):
                error(path, f'missing authentic source commit for {version}')
            if entry.get('apk', True):
                if entry.get('filename') != f'mebot-{version}.apk':
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
        latest_entry = next((entry for entry in entries if entry['version'] == catalog['version']), None)
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
