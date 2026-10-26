#!/usr/bin/env python3
"""Checks the generated product surface without network dependencies."""
from pathlib import Path
from html.parser import HTMLParser
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
r = json.loads((DOCS / 'assets/release.json').read_text())
screens = json.loads((DOCS / 'assets/screens.json').read_text())
base = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{r['tag']}/"
published = r['status'] == 'published'
expected = base + (r['filename'] or '')
assert len({s['file'] for s in screens}) == len(screens), 'Duplicate screenshots'
assert len(screens) >= 12, 'Incomplete gallery'
for s in screens:
    assert s['alt'] and s['caption'] and s['version']
    assert (DOCS / s['file']).is_file()

class Inspect(HTMLParser):
    def __init__(self):
        super().__init__()
        self.downloads = []
        self.images = []
        self.links = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'a':
            self.links.append(a.get('href', ''))
            if 'data-release-apk' in a:
                self.downloads.append(a)
        if tag == 'img':
            self.images.append(a)

for name in ('index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html'):
    text = (DOCS / name).read_text()
    p = Inspect(); p.feed(text)
    assert '/releases/latest' not in text, 'GitHub latest omits prereleases'
    assert '/blob/master/' not in text, 'Wrong branch link'
    assert r['version'] in text
    if published and name not in ('screenshots.html','tour.html'):
        assert p.downloads, f'Missing direct download: {name}'
    for a in p.downloads:
        assert a.get('href') == expected and 'hidden' not in a, name
    for im in p.images:
        assert 'alt' in im, name
    for url in p.links:
        if '/releases/download/' in url:
            assert any(url.startswith('https://github.com/EmmanuelMmanda/MeBot/releases/download/'+entry['tag']+'/') for entry in r['releases'] if entry['status']=='published'), f'Unpublished release link: {url}'
    if name == 'screenshots.html':
        assert text.count('data-screen-group=') == len(screens)
        assert 'aria-pressed="true"' in text and 'id="screen-count" role="status"' in text
readme = (ROOT / 'README.md').read_text()
preview = (DOCS / 'preview-083.html').read_text()
assert 'Preview, not a published APK' in preview
assert 'lang="sw"' in preview and 'existing release signing identity' in preview
assert '/releases/download/v0.8.3/' not in preview
assert 'not a full-duplex' in preview
p = Inspect(); p.feed(preview)
for im in p.images:
    if im.get('src', '').startswith('screenshots/v083/'):
        assert im.get('alt') and im.get('width') and im.get('height')
for page in DOCS.rglob('*.html'):
    assert 'preview-banner' not in page.read_text(), f'Stale candidate banner: {page}'
assert readme.count('<img ') >= len(screens)
assert '/releases/latest' not in readme
assert 'The current application version is **0.6.1**' not in readme
assert 'Official' in readme or 'official' in readme
print(f'Showcase checks passed: {len(screens)} screenshots, publication-aware download controls, version labels and accessible image descriptions.')

next_preview = (DOCS / 'preview-084.html').read_text()
assert 'Preview, not a published APK' in next_preview
assert 'lang="sw"' in next_preview and '817 automated tests' in next_preview
assert '/releases/download/v0.8.4/' not in next_preview
assert 'preview-083.html' in next_preview and 'Saved → Following' in next_preview

current_preview = (DOCS / 'preview-085.html').read_text()
assert 'Preview, not a published APK' in current_preview
assert '150 words and 1,000 characters' in current_preview
assert '/releases/download/v0.8.5/' not in current_preview
assert 'lang="sw"' in current_preview
assert 'preview-084.html' in current_preview and 'preview-083.html' in current_preview
for current in (ROOT/'README.md', ROOT/'START-HERE.md', DOCS/'USER-GUIDE.md'):
    assert 'offline price comparison tool' not in current.read_text()

engine_preview = (DOCS / 'preview-090.html').read_text()
assert 'not a published official APK' in engine_preview
assert '/releases/download/v0.9.0/' not in engine_preview
assert '5.100 ms' in engine_preview and 'not phone startup' in engine_preview
assert 'onboarding-en-chooser.png' in engine_preview and 'onboarding-sw-chooser.png' in engine_preview
assert 'interests-small-sw-large.png' not in engine_preview
p = Inspect(); p.feed(engine_preview)
for im in p.images:
    if im.get('src', '').startswith('screenshots/v090/'):
        assert im.get('alt') and im.get('width') and im.get('height')

# Publication catchup must preserve source-only history and exact asset casing.
assert r['version'] == '0.9.3'
if not published:
    assert r['privateCandidate'] and r['localArtifactsVerified']
    assert r['filename'] == 'mebot-0.9.3.apk'
    assert r['sha256'] == '2268874208a354b3dab5d41a4d675cc7832206e5f6b19403c49a22716735f74e'
    assert r['sourceCommit'] == 'a95e6c55209445894b6eb81ed1993c8b25e6fb66'
    for name in ('index.html','download.html','releases.html','tour.html'):
        assert 'data-release-apk' not in (DOCS/name).read_text()
        assert '/releases/download/v0.9.' not in (DOCS/name).read_text()
assert r['certificateSha256'] == 'c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007'
for version in ('0.8.3', '0.8.4', '0.8.5'):
    entry = next(e for e in r['releases'] if e['version'] == version)
    assert entry['apk'] is False and entry['filename'] is None and entry['sha256'] is None
    assert 'no production' in entry['notes'].lower()
for version in ('0.9.0', '0.9.1', '0.9.2'):
    entry = next(e for e in r['releases'] if e['version'] == version)
    assert entry['filename'] == f'MeBot-{version}.apk'
    assert entry['sourceFilename'] == f'MeBot-{version}-source.zip'
    assert entry['sourceTree'] and entry['certificateSha256'] == r['certificateSha256']
assert 'Do not uninstall' in (DOCS/'download.html').read_text()
for name in ('preview-083.html', 'preview-084.html', 'preview-085.html', 'preview-090.html'):
    assert (DOCS/name).read_text().count('<aside class="release-archive wrap">') == 1
print('Release catchup checks passed: source-only history, exact filenames, signing lineage and archived previews.')
