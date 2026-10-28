#!/usr/bin/env python3
"""Validate current website copy, screenshot evidence, publication identity and archives."""
from datetime import date, datetime, timezone
from html.parser import HTMLParser
from html import unescape
from pathlib import Path
from urllib.parse import urlsplit
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
REPO = 'https://github.com/EmmanuelMmanda/MeBot'
r = json.loads((DOCS / 'assets/release.json').read_text())
screens = json.loads((DOCS / 'assets/screens.json').read_text())
published = r.get('status') == 'published' and r.get('apk') is True
public_releases = [x for x in r.get('releases', []) if x.get('status') == 'published' and x.get('apk') is True]
official = r if published else public_releases[-1]
assert official.get('filename') == f"mebot-{official['version']}.apk", 'Latest APK filename must match verified lowercase public asset'
latest_apk = f"{REPO}/releases/latest/download/{official['filename']}"
release_base = f"{REPO}/releases/download/{official['tag']}/"
latest_release = f'{REPO}/releases/latest'
capture_version = screens[0]['version']
capture_dir = '/'.join(Path(screens[0]['file']).parts[:2])
capture_prefix = capture_dir + '/'
capture_record = DOCS / capture_dir / 'README.md'
assert capture_record.is_file() and capture_record.stat().st_size > 0, 'Current screenshots must retain a readable technical capture/source record'

# The public top-level record is immutable publication evidence. A pending newer candidate may not replace it.
assert official['tag'] == 'v' + official['version']
public_integrity = json.loads((DOCS / official['integrityFile']).read_text())
for field, proof in [
    ('sourceCommit', 'source_code_commit'), ('sourceTree', 'source_tree'),
    ('applicationId', 'application_id'), ('versionCode', 'version_code'),
    ('filename', 'apk_file'), ('sha256', 'apk_sha256'), ('sizeBytes', 'apk_size_bytes'),
    ('certificateSha256', 'signer_certificate_sha256'), ('sourceFilename', 'source_file'),
    ('sourceSha256', 'source_sha256'), ('sourceSizeBytes', 'source_size_bytes'),
]:
    assert official[field] == public_integrity[proof], f'Published identity mismatch: {field}'
assert public_integrity['publication_status'] == 'published'
assert official['publicAssetsAvailable'] is True and official['inAppUpdateAvailable'] is True
assert official['publicAssetsAvailable'] == public_integrity['public_assets_available']
assert official['inAppUpdateAvailable'] == public_integrity['in_app_update_available']
assert public_integrity.get('public_release_url') == f"{REPO}/releases/tag/{official['tag']}"
assert re.fullmatch(r'[a-f0-9]{64}', official['sha256']) and re.fullmatch(r'[a-f0-9]{40}', official['sourceCommit'])
assert official['sizeBytes'] > 0 and official['sourceSizeBytes'] > 0
published_at = datetime.fromisoformat(official['publishedAt'].replace('Z', '+00:00'))
assert published_at.tzinfo is not None and published_at.utcoffset() == timezone.utc.utcoffset(published_at)
assert published_at.date().isoformat() == official['publishedDate']
assert public_integrity['published_at'] == official['publishedAt']
assert public_integrity.get('personal_profile_asset_present') is False
if published:
    assert r['publicAssetsAvailable'] and r['inAppUpdateAvailable'] and not r.get('privateCandidate')
else:
    assert r.get('privateCandidate') is True and r.get('publicAssetsAvailable') is False and r.get('inAppUpdateAvailable') is False
    assert r['status'] == 'pending'

assets = public_integrity.get('assets', [])
expected_assets = [official[k] for k in ('filename', 'sourceFilename', 'verificationFilename', 'releaseNotesFilename', 'checksumFilename', 'releaseMetadataFilename')]
assert sorted(a.get('name', '') for a in assets) == sorted(expected_assets), 'Public receipt must enumerate exactly the release assets'
for name, digest_key, size_key in [
    ('filename', 'sha256', 'sizeBytes'), ('sourceFilename', 'sourceSha256', 'sourceSizeBytes'),
    ('verificationFilename', 'verificationSha256', 'verificationSizeBytes'),
    ('releaseNotesFilename', 'releaseNotesSha256', 'releaseNotesSizeBytes'),
    ('checksumFilename', 'checksumSha256', 'checksumSizeBytes'),
    ('releaseMetadataFilename', 'releaseMetadataSha256', 'releaseMetadataSizeBytes'),
]:
    item = next(a for a in assets if a.get('name') == official[name])
    assert item.get('digest') == 'sha256:' + official[digest_key] and item.get('size') == official[size_key]
    assert item.get('browser_download_url') == release_base + official[name]

# Every screenshot is a full native PNG with sufficient source, capture, language and theme provenance.
assert len(screens) >= 6
assert len({s['file'] for s in screens}) == len(screens)
assert {'en', 'sw'} <= {s['locale'] for s in screens}
assert len({s['theme'] for s in screens}) >= 2
theme_modes = {s['theme'].split('·', 1)[0].strip().lower() for s in screens}
assert {'light', 'dark'} <= theme_modes, 'Examples must include real light and dark app renders'
assert {'feed', 'opportunity', 'reads', 'ask', 'about', 'appearance'} <= {s['group'] for s in screens}
question_screen = next(s for s in screens if s['group'] == 'ask')
questions = question_screen.get('suggestedQuestions', [])
assert len(questions) == 5 and all(isinstance(q, str) and q.strip() for q in questions)
assert question_screen.get('sampleItem'), 'Question sample must name the story used by the captured sheet'
opportunity_screens = [s for s in screens if s['group'] == 'opportunity']
assert opportunity_screens, 'At least one real opportunity screen must be shown'
for s in opportunity_screens:
    assert 'sourceCheckedAsOf' in s, f"Opportunity source-check date missing: {s['file']}"
    date.fromisoformat(s['sourceCheckedAsOf'])
    assert 'publisherPublicationDate' in s, f"State whether the source shows a publication date: {s['file']}"
    if s['publisherPublicationDate'] is not None:
        date.fromisoformat(s['publisherPublicationDate'])
    assert s.get('deadline'), f"Opportunity deadline missing: {s['file']}"
    date.fromisoformat(s['deadline'])
    assert s.get('sourceUrls'), f"Official opportunity source missing: {s['file']}"
read_screens = [s for s in screens if s['group'] == 'reads']
assert read_screens, 'Show an AI-authored Original Read'
for s in read_screens:
    assert s.get('createdDate'), f"Original Read must retain its Created date: {s['file']}"
    date.fromisoformat(s['createdDate'])
    assert 'publisherPublicationDate' in s, f"Keep AI Created date distinct from publication date: {s['file']}"
    if s['publisherPublicationDate'] is not None:
        date.fromisoformat(s['publisherPublicationDate'])
for s in screens:
    assert s['version'] == capture_version and s['file'].startswith(capture_prefix)
    p = (DOCS / s['file']).resolve()
    assert p.is_relative_to(DOCS.resolve()) and p.is_file()
    data = p.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert s.get('captureKind') and s.get('captureDate') and s.get('locale') and s.get('theme')
    assert all(s.get(k) for k in ('title', 'alt', 'caption'))
    assert s.get('sourceUrls') or s['group'] in {'appearance', 'about'}
    assert s.get('liveProvider') is False, 'A controlled fake provider is not live-account evidence'
    if capture_version != official['version']:
        assert s.get('candidatePreview') is True
    assert re.fullmatch(r'[a-f0-9]{64}', s.get('sha256', '')), f"Reviewed screenshot SHA-256 required: {s['file']}"
    assert hashlib.sha256(data).hexdigest() == s['sha256']
    date.fromisoformat(s['captureDate'])
    assert all(u.startswith('https://') for u in s['sourceUrls'])
    if s['group'] == 'appearance':
        assert s['captureKind'] == 'native-dialog-window-pixelcopy'
        assert (s['width'], s['height']) == (822, 1212)
        assert s.get('sampleData') is False and s.get('providerFixture') is False
    words = ' '.join(str(s.get(k, '')) for k in ('title', 'alt', 'caption')).lower()
    for stale in ('read what matters', 'read with purpose', 'choose your next step', 'time unavailable', 'static editorial fixture', 'debug host capture', '0.9.9 is available'):
        assert stale not in words, (s['file'], stale)

# The website uses the exact app font; preserve the font license notice.
font = DOCS / 'assets/figtree.ttf'
app_font = ROOT / 'app/src/main/res/font/figtree.ttf'
assert font.is_file() and app_font.is_file()
assert hashlib.sha256(font.read_bytes()).digest() == hashlib.sha256(app_font.read_bytes()).digest()
css = (DOCS / 'site.css').read_text()
assert '@font-face' in css and 'assets/figtree.ttf' in css and 'font-weight:300 900' in css
assert 'line-height:24px' in css and 'line-height:18px' in css
assert (DOCS / 'FONT-LICENSE.txt').is_file()

class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.anchors = []
        self.images = []
        self.groups = 0
        self.body_text = []
        self.footer_text = []
        self.technical_text = []
        self.current_anchor = None
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        self.stack.append((tag, attrs))
        if tag == 'a':
            self.current_anchor = {'href': attrs.get('href', ''), 'class': attrs.get('class', ''), 'attrs': attrs, 'text': ''}
            self.anchors.append(self.current_anchor)
        if tag == 'img': self.images.append(attrs)
        if 'data-screen-group' in attrs: self.groups += 1
    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break
        self.current_anchor = next((a for a in reversed(self.anchors) if a.get('_open')), None)
        # Parser need not maintain nested anchors (invalid HTML); close current link on each end anchor.
        if tag == 'a': self.current_anchor = None
    def handle_data(self, data):
        if self.current_anchor is not None:
            self.current_anchor['text'] += data
        attrs = [attrs for _, attrs in self.stack]
        if any(tag == 'footer' for tag, _ in self.stack): self.footer_text.append(data)
        elif any('data-release-filename' in a for a in attrs): self.technical_text.append(data)
        else: self.body_text.append(data)

def inspect(path):
    p = Page()
    p.feed(path.read_text())
    return p

current_guides = [DOCS / 'guide.html'] + [p for p in (DOCS / 'guides').glob('*.html') if p.name != 'release-082.html']
current_pages = [DOCS / x for x in ('index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html')] + current_guides
for path in current_pages:
    p = inspect(path)
    assert 'shot-version' not in path.read_text() and 'version-pill' not in path.read_text().lower(), path
    assert not re.search(r'(?i)(debug host capture|static editorial fixture|test-provider|time unavailable)', path.read_text()), path
    primary = [a for a in p.anchors if a['class'] in ('header-download', 'button') or 'data-release-apk' in a['attrs']]
    assert any(a['text'].strip() == 'Download latest version' and a['href'] == latest_apk for a in primary), f'Exact primary direct-download CTA missing: {path}'
    for a in primary:
        if 'data-release-apk' in a['attrs']:
            assert a['text'].strip() == 'Download latest version' and a['href'] == latest_apk, (path, a)
    body = unescape(' '.join(p.body_text))
    footer = unescape(' '.join(p.footer_text))
    technical = unescape(' '.join(p.technical_text))
    assert f'MeBot {official["version"]}' not in body, f'App version appears in current page body: {path}'
    assert f'MeBot {official["version"]}' in footer, f'Public release version belongs in the site footer: {path}'
    assert capture_version not in body or capture_version == official['version'], f'Unpublished capture build leaks into copy: {path}'
    for phrase in ('read what matters', 'read with purpose', 'choose your next step', 'your next reading experience is ready'):
        assert phrase not in body.lower(), (path, phrase)
    for image in p.images:
        if 'screenshots/' in image.get('src', ''):
            assert image.get('alt', '').strip(), (path, image)
            assert image['src'].startswith(capture_prefix), (path, image['src'])
    for a in p.anchors:
        href = a['href']
        if '/releases/latest/download/' in href:
            assert href == latest_apk and a['text'].strip() == 'Download latest version', (path, a)
        elif '/releases/download/' in href:
            assert href.startswith(release_base), (path, href)
            assert href.rsplit('/', 1)[-1] in expected_assets, (path, href)
            if href.lower().endswith('.apk'):
                assert href == latest_apk and a['text'].strip() == 'Download latest version', (path, a)
    if path.name == 'screenshots.html':
        text = path.read_text()
        assert p.groups == len(screens)
        assert 'id="screen-count" role="status"' in text
        assert 'data-screen-filter="all" aria-pressed="true"' in text
        assert {im['src'] for im in p.images if 'screenshots/' in im.get('src', '')} == {s['file'] for s in screens}
        examples = re.findall(r'<figcaption><strong>.*?</strong><span>(?:Example|Sample|Mfano)\b', text, flags=re.I | re.S)
        assert len(examples) == len(screens), 'Every capture needs a concise example caption'
        assert 'screen images are examples' in body.lower() and 'sample content' in body.lower() and 'not a live account result' in body.lower()
    if path.name == 'index.html':
        for term in ('about you', 'five', 'kiswahili', 'opportunities', 'theme', 'original read'):
            assert term in body.lower(), (term, path)
        assert 'read what matters' not in body.lower()
        assert '0.9.9 is available' not in body.lower()
        for question in questions:
            assert question in body, question
        assert question_screen['sampleItem'] in body
    if path.name == 'download.html':
        for field in ('filename', 'sourceFilename', 'verificationFilename', 'sourceCommit', 'sourceTree', 'applicationId', 'certificateSha256', 'sha256', 'releaseNotesFilename', 'checksumFilename', 'releaseMetadataFilename'):
            assert official[field] in path.read_text(), field
        assert 'Do not uninstall' in body and 'physical-phone' in body
        assert f'{official["filename"]}' in technical
    if path.name == 'releases.html':
        assert 'latest published' in body.lower()
        assert f'{official["version"]}' not in body

# Main README and start page stay current without body-version claims or stale fallback directions.
for path in (ROOT / 'README.md', ROOT / 'START-HERE.md'):
    text = path.read_text()
    visible = re.sub(r'https?://\S+', '', text)
    visible = re.sub(r'\[[^]]*\]\([^)]*\)', lambda m: m.group(0).split(']')[0] + ']', visible)
    assert '0.9.9 is available' not in visible.lower()
    assert f'MeBot {official["version"]}' not in visible
    assert '[Download latest version]' in text
    assert latest_apk in text
    for phrase in ('read what matters', 'read with purpose', 'choose your next step', 'time unavailable', 'static editorial fixture', 'debug preview build', 'unpublished app build', 'simulated provider content'):
        assert phrase not in visible.lower(), (path, phrase)
if capture_version != official['version']:
    for path in (ROOT / 'README.md', ROOT / 'START-HERE.md'):
        assert capture_version not in re.sub(r'https?://\S+', '', path.read_text()), path
readme = (ROOT / 'README.md').read_text().lower()
assert 'screen images are examples' in readme and 'sample content' in readme and 'not a live account result' in readme
assert 'edit to change the draft first' in readme and 'created date' in readme and 'not a publisher date' in readme
assert (DOCS / 'assets/README.md').is_file()

# Candidate guides describe the real three-stage setup, explicit context boundary,
# questions/load-more behavior, and conditional voice-note service without a release claim.
install = (DOCS / 'guides/install.html').read_text()
assert 'With ChatGPT, first-run setup guides you through three stages' in install
assert 'Continue with public feeds' in install and 'Start my feed' in install
assert 'Choose feed topics' in install and 'You → About you' in install
assert 'six-step welcome tour' not in install
context = (DOCS / 'guides/personal-context.html').read_text()
assert 'does not automatically read or sync ChatGPT memory or conversation history after sign-in' in context
assert 'Prepare in ChatGPT' in context and 'Review import' in context and 'manual, reviewed import' in context
assert 'Choose feed topics' in context and 'Find your interests' in context
assert 'Show more topics' in context and 'Show fewer topics' in context
assert 'do not start an AI request' in context and 'confirmed local note' in context
assert '0.9.3' not in context and '0.9.4' not in context
feed = (DOCS / 'guides/feed-and-ai.html').read_text()
assert 'up to eight public stories per module' in feed
assert 'five distinct questions' in feed and 'More angles' in feed and 'appended rather than replacing' in feed
assert 'Opening the sheet alone does not call AI' not in feed
assert 'not sent as a message until you choose' in feed
voice = (DOCS / 'guides/privacy.html').read_text()
assert 'M4A' in voice and 'asks you to confirm' in voice and 'uploaded automatically' in voice
assert 'General voice input' not in voice and 'latest public app' not in voice
trouble = (DOCS / 'guides/troubleshooting.html').read_text()
assert 'After import, I do not see my notes.' in trouble and '0.9.3' not in trouble and '0.9.4' not in trouble
connection = (DOCS / 'guides/ai-connections.html').read_text()
assert 'does not give MeBot your conversations or saved memory' in connection
assert 'never switches from plan usage to API billing automatically' in connection
assert 'Voice-note transcription, when offered, requires a separately configured MeBot service' in connection
privacy = (DOCS / 'PRIVACY.md').read_text()
assert 'There is no automatic connection to the owner\'s ChatGPT history or memory' in privacy
assert 'asks for confirmation' in privacy and 'uploaded automatically' in privacy
assert 'latest public download' not in privacy
assert '0.9.8' not in (DOCS / 'USER-GUIDE.md').read_text() and '0.9.8' not in privacy

# Existing immutable release evidence and historical showcases remain untouched.
assert hashlib.sha256((DOCS / 'releases/v0.9.5.json').read_bytes()).hexdigest() == '3eb625d5bbd60b89aa1a91d674c6c8625ba380554f8a05daa43028714b4b47be'
assert hashlib.sha256((DOCS / 'RELEASE-0.9.5.md').read_bytes()).hexdigest() == '19808c5b6c1cf2e8b0ea1ff0242e14799a0a563df31ffeadc9fb7375b08cd7cf'
assert hashlib.sha256((DOCS / 'VERIFICATION-0.9.8.md').read_bytes()).hexdigest() == official['verificationSha256']
assert (DOCS / 'guides/release-082.html').is_file()
assert (DOCS / 'preview-083.html').is_file() and (DOCS / 'preview-084.html').is_file()
assert (DOCS / 'preview-085.html').is_file() and (DOCS / 'preview-090.html').is_file()
assert 'Preview, not a published APK' in (DOCS / 'preview-083.html').read_text()
assert not re.search(r'/(?:workspace|home/agent|tmp)/', (ROOT / 'README.md').read_text())
print(f'Showcase checks passed: {len(screens)} native screens, latest direct APK, current copy, app font, source/date provenance and immutable release evidence checked.')
