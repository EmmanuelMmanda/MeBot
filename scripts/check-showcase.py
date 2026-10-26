#!/usr/bin/env python3
"""Check public site/release parity without network dependencies."""
from pathlib import Path
from html.parser import HTMLParser
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
r = json.loads((DOCS / 'assets/release.json').read_text())
screens = json.loads((DOCS / 'assets/screens.json').read_text())
published = r['status'] == 'published'
official = r if published else next(x for x in r['releases'] if x.get('status') == 'published' and x.get('apk') is True)
base = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{official['tag']}/"
expected_apk = base + official['filename']
assert r['tag'] == 'v' + r['version']
assert official['tag'] == 'v' + official['version']
assert r['package'] == r['applicationId']
assert r['applicationId'] and r['versionCode'] and r['sourceCommit'] and r['sourceTree']
assert r['filename'] and r['sha256'] and r['sizeBytes']
assert r['sourceFilename'] and r['verificationFilename']
integrity = json.loads((DOCS / r['integrityFile']).read_text())
assert r['certificateSha256'] and integrity['signer_certificate_sha256'] == r['certificateSha256']
assert len({s['file'] for s in screens}) == len(screens), 'Duplicate screenshots'
assert len(screens) >= 12, 'Incomplete gallery'
for s in screens:
    assert s['alt'] and s['caption'] and s['version']
    assert (DOCS / s['file']).is_file()
    assert 'evidence' not in s['file'], 'Stress evidence must remain outside the normal gallery'
current_screens = [s for s in screens if s['version'] == r['version']]
assert current_screens, 'The current version needs at least one explicitly versioned capture'

class Inspect(HTMLParser):
    def __init__(self):
        super().__init__()
        self.downloads = []
        self.images = []
        self.links = []
        self.groups = 0
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'a':
            self.links.append(a.get('href', ''))
            if 'data-release-apk' in a:
                self.downloads.append(a)
        if tag == 'img':
            self.images.append(a)
        if 'data-screen-group' in a:
            self.groups += 1

main_pages = ('index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html')
for name in main_pages:
    text = (DOCS / name).read_text()
    p = Inspect(); p.feed(text)
    assert '/releases/latest' not in text, 'GitHub latest omits prereleases'
    assert '/blob/master/' not in text, 'Wrong branch link'
    assert r['version'] in text
    if name not in ('screenshots.html', 'tour.html'):
        assert p.downloads, f'Missing official download: {name}'
    for a in p.downloads:
        assert a.get('href') == expected_apk and 'hidden' not in a, name
    for im in p.images:
        assert 'alt' in im, name
    for url in p.links:
        if '/releases/download/' in url:
            assert url.startswith(base), f'Current product page links outside the selected official release: {url}'
            asset = url.rsplit('/',1)[-1]
            allowed = {official['filename'],official['sourceFilename'],official['verificationFilename'],official['releaseNotesFilename'],official['checksumFilename'],official['releaseMetadataFilename']}
            assert asset in allowed, f'Uncatalogued current release asset: {asset}'
    if name == 'screenshots.html':
        assert p.groups == len(screens)
        assert 'aria-pressed="true"' in text and 'id="screen-count" role="status"' in text
        assert 'Evidence only' not in text or 'Accessibility stress evidence' in text
        assert '320 dp' in text and '130% font scale' in text
    if name == 'download.html':
        for field in (r['filename'], r['sourceFilename'], r['verificationFilename'], r['sourceCommit'], r['sourceTree'], r['applicationId'], r['certificateSha256']):
            assert field in text, f'{field} missing from current download details'
        for field in (r['releaseNotesFilename'], r['checksumFilename'], r['releaseMetadataFilename']):
            assert field in text, f'{field} absent from asset list'
    if name == 'releases.html':
        assert r['version'] in text and 'What changed in' in text
        assert 'A successful context import could still leave the screen on the import flow' in text
        assert 'Historical signed · superseded' in text

readme = (ROOT / 'README.md').read_text()
start = (ROOT / 'START-HERE.md').read_text()
assert readme.count('<img ') >= len(screens)
assert '/releases/latest' not in readme
assert 'The current application version is **0.6.1**' not in readme
assert official['tag'] in readme and expected_apk in readme
assert r['version'] in readme and r['sourceCommit'] in readme
assert 'imported notes' in readme.lower() and 'expired' in readme.lower()
assert 'synthetic fixtures' in readme
assert expected_apk in start and official['tag'] in start
assert 'imported-notes-en.png' in start and 'synthetic notes' in start
assert 'after-import' in start
for current in (ROOT/'README.md', ROOT/'START-HERE.md', DOCS/'USER-GUIDE.md'):
    assert 'offline price comparison tool' not in current.read_text().lower()

# Current guides, footer/navigation, and support copy remain aligned with the manifest.
for name in ('guide.html', 'guides/install.html', 'guides/personal-context.html', 'guides/feed-and-ai.html', 'guides/troubleshooting.html'):
    text = (DOCS / name).read_text()
    assert 'Official 0.8.2' not in text, f'{name} has stale current-release navigation'
    assert 'MeBot 0.7.0-beta.1 ·' not in text, f'{name} has stale website footer'
assert r['version'] in (DOCS / 'guide.html').read_text()
personal = (DOCS / 'guides/personal-context.html').read_text()
feed = (DOCS / 'guides/feed-and-ai.html').read_text()
faq = (DOCS / 'guides/troubleshooting.html').read_text()
assert 'after-import' in personal and 'imported-notes-en.png' in personal and 'synthetic example content' in personal.lower()
assert 'not passive reading history' in feed and 'up to eight public stories per module' in feed
assert 'After import, I do not see my notes.' in faq and 'A forecast or event disappeared from my feed.' in faq
assert r['version'] in (DOCS / 'INSTALL.md').read_text()
assert r['version'] in (DOCS / 'PUBLIC-RELEASES.md').read_text()
assert '0.9.3 fixed that crash but could remain on the context-import screen after a successful import' in (DOCS / 'PUBLIC-RELEASES.md').read_text()
for page in DOCS.rglob('*.html'):
    html = page.read_text()
    assert 'preview-banner' not in html, f'Stale candidate banner: {page}'
    if '<footer' in html:
        assert f'MeBot {r["version"]} ·' in html[html.find('<footer'):], f'Stale shared footer version: {page}'
    if page.relative_to(DOCS).as_posix().startswith('guides/'):
        assert f'Official {official["version"]}' in html, f'Stale current release navigation: {page}'

# Existing historical preview articles remain dated, non-APK records.
preview = (DOCS / 'preview-083.html').read_text()
assert 'Preview, not a published APK' in preview
assert 'lang="sw"' in preview and 'existing release signing identity' in preview
assert '/releases/download/v0.8.3/' not in preview
assert 'not a full-duplex' in preview
p = Inspect(); p.feed(preview)
for im in p.images:
    if im.get('src', '').startswith('screenshots/v083/'):
        assert im.get('alt') and im.get('width') and im.get('height')
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

# Release history is conservative: only published entries can generate public links.
for version in ('0.8.3', '0.8.4', '0.8.5'):
    entry = next(e for e in r['releases'] if e['version'] == version)
    if version != '0.8.3':
        assert entry['apk'] is False and entry['filename'] is None and entry['sha256'] is None
        assert 'no production' in entry['notes'].lower()
source_only = next(e for e in r['releases'] if e['version'] == '0.8.3')
assert source_only['status'] == 'published' and source_only['apk'] is False and source_only['filename'] is None
production_history_cert = next(e for e in r['releases'] if e['version'] == '0.9.3')['certificateSha256']
for version in ('0.9.0', '0.9.1', '0.9.2'):
    entry = next(e for e in r['releases'] if e['version'] == version)
    assert entry['filename'] == f'MeBot-{version}.apk'
    assert entry['sourceFilename'] == f'MeBot-{version}-source.zip'
    assert entry['sourceTree'] and entry['certificateSha256'] == production_history_cert
assert 'Do not uninstall' in (DOCS/'download.html').read_text()
assert official['version'] == r['version'] if published else True
for name in ('preview-083.html', 'preview-084.html', 'preview-085.html', 'preview-090.html'):
    assert (DOCS/name).read_text().count('<aside class="release-archive wrap">') == 1
print(f'Showcase checks passed: {len(screens)} normal screenshots, manifest-driven downloads/assets, current guide parity, source-only history and accessible image descriptions.')
