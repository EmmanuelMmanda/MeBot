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
expected = base + r['filename']
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
    if name not in ('screenshots.html','tour.html'):
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
assert readme.count('<img ') >= len(screens)
assert '/releases/latest' not in readme
assert 'The current application version is **0.6.1**' not in readme
assert 'Official' in readme or 'official' in readme
print(f'Showcase checks passed: {len(screens)} screenshots, direct APK links, version labels and accessible image descriptions.')
