#!/usr/bin/env python3
"""Check public site/release parity without network dependencies."""
from pathlib import Path
from html.parser import HTMLParser
import json
import re
import hashlib
from datetime import date, datetime, timezone

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
if published:
    assert r.get('privateCandidate') is False
    assert r.get('publicAssetsAvailable') is True and r.get('inAppUpdateAvailable') is True
    published_at = datetime.fromisoformat(r['publishedAt'].replace('Z', '+00:00'))
    assert published_at.tzinfo is not None and published_at.utcoffset() == timezone.utc.utcoffset(published_at)
    publication_date = date.fromisoformat(r['publishedDate'])
    assert publication_date.isoformat() == integrity.get('publication_date') == published_at.astimezone(timezone.utc).date().isoformat()
    assert r['publishedAt'] == integrity.get('published_at')
    assert integrity['publication_status'] == 'published'
    assert integrity['public_assets_available'] is True and integrity['in_app_update_available'] is True
    assert integrity['public_release_assets_verified'] is True and integrity['public_release_latest'] is True
    assert integrity['public_release_url'] == f'https://github.com/EmmanuelMmanda/MeBot/releases/tag/{r["tag"]}'
    for catalog_field, integrity_field in (
        ('sourceCommit', 'source_code_commit'), ('sourceTree', 'source_tree'),
        ('filename', 'apk_file'), ('sha256', 'apk_sha256'), ('sizeBytes', 'apk_size_bytes'),
        ('sourceFilename', 'source_file'), ('sourceSha256', 'source_sha256'),
        ('sourceSizeBytes', 'source_size_bytes'), ('certificateSha256', 'signer_certificate_sha256'),
    ):
        assert r[catalog_field] == integrity[integrity_field], f'Published identity mismatch: {catalog_field}'
if not published:
    assert r.get('privateCandidate') is True and r.get('localArtifactsVerified') is True
    assert official['version'] == '0.9.5' and official['status'] == 'published'
    assert 'publishedDate' not in r and 'publishedAt' not in r
    assert integrity['publication_status'] == 'pending'
    assert integrity['publication_date'] is None and integrity['published_at'] is None
    assert integrity['public_assets_available'] is False and integrity['in_app_update_available'] is False
    for catalog_field, integrity_field in (
        ('sourceCommit', 'source_code_commit'), ('sourceTree', 'source_tree'),
        ('filename', 'apk_file'), ('sha256', 'apk_sha256'), ('sizeBytes', 'apk_size_bytes'),
        ('sourceFilename', 'source_file'), ('sourceSha256', 'source_sha256'),
        ('sourceSizeBytes', 'source_size_bytes'), ('certificateSha256', 'signer_certificate_sha256'),
    ):
        assert r[catalog_field] == integrity[integrity_field], f'Candidate identity mismatch: {catalog_field}'
assert len({s['file'] for s in screens}) == len(screens), 'Duplicate screenshots'
assert len(screens) >= 12, 'Incomplete gallery'
for s in screens:
    assert s['alt'] and s['caption'] and s['version']
    assert (DOCS / s['file']).is_file()
    assert 'evidence' not in s['file'], 'Stress evidence must remain outside the normal gallery'
candidate_page = f"preview-{r['version'].replace('.', '')}.html"
visual_release_order = [official, *r.get('releases', [])]
visual_versions = {s['version'] for s in screens}
visual_release_version = next((entry['version'] for entry in visual_release_order if entry.get('status')=='published' and entry.get('version') in visual_versions), None)
assert visual_release_version, 'At least one published version must have labelled app captures'
current_screens = [s for s in screens if s['version'] == visual_release_version]

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

main_pages = ('index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html', candidate_page)
for name in main_pages:
    text = (DOCS / name).read_text()
    p = Inspect(); p.feed(text)
    assert '/releases/latest' not in text, 'GitHub latest omits prereleases'
    assert '/blob/master/' not in text, 'Wrong branch link'
    assert r['version'] in text
    if name not in ('screenshots.html', 'tour.html', candidate_page):
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
        for field in (official['filename'], official['sourceFilename'], official['verificationFilename'], official['sourceCommit'], official['sourceTree'], official['applicationId'], official['certificateSha256']):
            assert field in text, f'{field} missing from current download details'
        for field in (official['releaseNotesFilename'], official['checksumFilename'], official['releaseMetadataFilename']):
            assert field in text, f'{field} absent from asset list'
    if name == 'releases.html':
        assert r['version'] in text and 'What changed in' in text
        assert 'Historical signed · superseded' in text
        if published and r['version'] == '0.9.6':
            assert 'MeBot 0.9.5 is superseded by 0.9.6' in text and 'general chat' in text
        elif published and r['version'] == '0.9.5':
            assert 'MeBot 0.9.4 is superseded by 0.9.5' in text
        elif not published:
            assert 'MeBot 0.9.6' in text and 'not offered' in text

readme = (ROOT / 'README.md').read_text()
start = (ROOT / 'START-HERE.md').read_text()
assert readme.count('<img ') >= len(screens)
assert '/releases/latest' not in readme
assert 'The current application version is **0.6.1**' not in readme
assert official['tag'] in readme and expected_apk in readme
assert official['version'] in readme and official['sourceCommit'] in readme
if official['version'] == '0.9.4':
    assert 'imported notes' in readme.lower() and 'expired' in readme.lower()
elif official['version'] == '0.9.5':
    assert 'category label' in readme.lower() and 'story-specific questions' in readme.lower() and 'offline' in readme.lower()
elif official['version'] == '0.9.6':
    assert 'correct story conversation' in readme.lower() and 'send only when' in readme.lower()
assert 'synthetic fixtures' in readme
assert expected_apk in start and official['tag'] in start
assert 'imported-notes-en.png' in start and 'synthetic notes' in start
assert 'after-import' in start
if published and r['version'] == '0.9.5':
    current_pages = [DOCS / name for name in (*main_pages, 'guide.html', 'guides/feed-and-ai.html')]
    for page_path in current_pages:
        page_text = page_path.read_text().lower()
        for stale in ('is not yet released', 'not publicly released', 'in preparation · 0.9.5', 'no candidate download', 'still being prepared', 'candidate preview'):
            assert stale not in page_text, f'Stale published-release copy in {page_path.name}: {stale}'
    assert 'story-flow guide' in (DOCS/'preview-095.html').read_text().lower()
    assert 'sample content' in (DOCS/'screenshots.html').read_text().lower()
    assert all(s.get('sampleFixture') is True and not s.get('candidatePreview') for s in current_screens)
    previous_entry = next(e for e in r['releases'] if e['version']=='0.9.4')
    assert previous_entry.get('recommendation') == 'superseded' and 'Superseded by MeBot 0.9.5' in previous_entry['notes']
    assert 'Official MeBot 0.9.5' in (ROOT/'README.md').read_text()
    gallery = (ROOT/'README.md').read_text().split('## See the current app',1)[1].split('## Choose your AI connection',1)[0]
    assert re.findall(r'href="(docs/screenshots/[^"]+)"', gallery)[:3] == ['docs/screenshots/v095/en-feed.png', 'docs/screenshots/v095/sw-feed.png', 'docs/screenshots/v095/en-detail.png']
    assert 'candidate' not in (ROOT/'README.md').read_text().lower()
    assert 'not yet released' not in (ROOT/'START-HERE.md').read_text().lower()
if published and r['version']=='0.9.6':
    current_pages = [DOCS / name for name in (*main_pages, 'guide.html', 'guides/feed-and-ai.html')]
    for page_path in current_pages:
        page_text=page_path.read_text().lower()
        for stale in ('not yet publicly released', 'no candidate download', 'in preparation · 0.9.6', 'prepared candidate'):
            assert stale not in page_text, f'Stale candidate copy in {page_path.name}: {stale}'
    assert '0.9.5 is superseded by 0.9.6' in (DOCS/'releases.html').read_text()
    assert 'could land in a general chat' in (DOCS/'releases.html').read_text().lower()
    assert '0.9.6 · story-conversation update' in (DOCS/candidate_page).read_text().lower()
    assert 'not yet a public download' not in (DOCS/candidate_page).read_text().lower()
    assert 'MeBot 0.9.6' in (ROOT/'README.md').read_text()
    hub = (DOCS/'guide.html').read_text()
    assert 'New in the current release · 0.9.6' in hub
    assert 'New in the current release · 0.9.5' not in hub
    assert 'href="preview-096.html">Read the 0.9.6 story-conversation guide' in hub
    user_guide = (DOCS/'USER-GUIDE.md').read_text()
    assert '# MeBot user guide — 0.9.6' in user_guide
    assert '[Current release notes](RELEASE-0.9.6.md)' in user_guide
    assert 'MeBot 0.9.6 updates production 0.9.0–0.9.5' in user_guide
    assert 'the current 0.9.6 release is offered to production installations on 0.9.0–0.9.5' in user_guide
    assert 'the current 0.9.5 release is offered' not in user_guide
    assert 'current story-conversation guide](preview-096.html)' in user_guide
    public_notes = (DOCS/'PUBLIC-RELEASES.md').read_text()
    assert 'The current 0.9.6 release keeps the `mebot.nguli.com` package and established 0.9.x production certificate, and updates production 0.9.0–0.9.5' in public_notes
    assert '**0.9.0 through 0.9.6** use package' in public_notes
    assert 'MeBot 0.9.6 can update production 0.9.0–0.9.5 in place.' in public_notes
    assert 'MeBot 0.9.5 can update 0.9.0–0.9.4 in place.' not in public_notes
    historical_095 = (DOCS/'preview-095.html').read_text()
    historical_095_lower = historical_095.lower()
    assert 'historical signed release · 2026-10-26' in historical_095_lower
    assert 'mebot 0.9.5 is superseded by mebot 0.9.6' in historical_095_lower
    assert 'could land in a general chat instead of that story' in historical_095_lower
    assert 'view historical 0.9.5 release files' in historical_095_lower
    assert 'the current official release is mebot 0.9.5' not in historical_095_lower
    assert 'mebot 0.9.5 is the current official android release' not in historical_095_lower
    assert 'mebot 0.9.6 is the current official release' in historical_095_lower
    previous_entry=next(e for e in r['releases'] if e['version']=='0.9.5')
    assert previous_entry.get('recommendation')=='superseded'
    assert 'superseded by mebot 0.9.6' in previous_entry['notes'].lower()
    assert previous_entry['filename']=='mebot-0.9.5.apk' and previous_entry['sourceFilename']=='MeBot-0.9.5-source.zip'
    assert (DOCS/'releases/v0.9.5.json').is_file()
    integrity_095=json.loads((DOCS/'releases/v0.9.5.json').read_text())
    assert integrity_095['version']=='0.9.5' and integrity_095['publication_status']=='published'
    assert hashlib.sha256((DOCS/'releases/v0.9.5.json').read_bytes()).hexdigest() == '3eb625d5bbd60b89aa1a91d674c6c8625ba380554f8a05daa43028714b4b47be'
    assert hashlib.sha256((DOCS/'RELEASE-0.9.5.md').read_bytes()).hexdigest() == '19808c5b6c1cf2e8b0ea1ff0242e14799a0a563df31ffeadc9fb7375b08cd7cf'
    assert visual_release_version=='0.9.5', 'Use the verified 0.9.5 story captures without implying new 0.9.6 UI'
if r['version']=='0.9.6':
    assert r['versionCode']==23 and r['applicationId']=='mebot.nguli.com'
    assert r['sourceCommit']=='b7c85878653321d184377cd4fed6c68502f4cb3b'
    assert r['sourceTree']=='2cceb13a1c647da3aa1ac6c67b3347a9fb186aef'
    assert r.get('sourceArchiveOriginalVerifiedCommit')=='0ec24f495dd013b815678dbac24d19b7f5887399'
    assert r['certificateSha256']=='c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007'
if not published and r['version']=='0.9.6':
    candidate_preview = (DOCS / candidate_page).read_text()
    assert 'not yet publicly released' in candidate_preview.lower()
    assert 'send waits until it is ready' in candidate_preview.lower()
    assert 'rotation or reopening restores the same story conversation' in candidate_preview.lower()
    assert 'locking stops a pending question' in candidate_preview.lower()
    assert 'not yet a public download' in candidate_preview.lower()
    assert 'do not sideload local candidate files' in candidate_preview.lower()
    assert f'/releases/download/v{r["version"]}/' not in candidate_preview
    assert 'historical mebot 0.9.5' not in candidate_preview.lower()
    assert official['version']=='0.9.5' and 'Get MeBot 0.9.5' in (DOCS/'index.html').read_text()
    assert 'Scope warning for 0.9.5' in (DOCS/'index.html').read_text()
    assert 'general chat' in (DOCS/'download.html').read_text().lower()
    assert 'MeBot 0.9.5 is superseded by 0.9.6' not in (DOCS/'releases.html').read_text()
    assert 'Current official' in (DOCS/'releases.html').read_text()
    assert r['publishedDate'] if 'publishedDate' in r else True
    assert 'publishedDate' not in r and 'publishedAt' not in r and 'publishedDate' not in integrity
    assert integrity['publication_status']=='pending'
    assert integrity['publication_date'] is None and integrity['published_at'] is None
    assert integrity['public_assets_available'] is False and integrity['in_app_update_available'] is False
    assert integrity['public_release_assets_verified'] is False
    assert r.get('publicAssetsAvailable') is False and r.get('inAppUpdateAvailable') is False
    assert 'pending' in (DOCS/'RELEASE-0.9.6.md').read_text().lower() or 'not yet public' in (DOCS/'RELEASE-0.9.6.md').read_text().lower()
    assert '2026-' not in (DOCS/'RELEASE-0.9.6.md').read_text()
    assert r['sourceCommit']==integrity['source_code_commit']
    assert r['sourceTree']==integrity['source_tree']
    assert r['sha256']==integrity['apk_sha256'] and r['sizeBytes']==integrity['apk_size_bytes']
    assert r['sourceSha256']==integrity['source_sha256'] and r['sourceSizeBytes']==integrity['source_size_bytes']
    assert r['verificationSha256']==integrity['verification_sha256']
    assert r['releaseNotesSha256']==integrity['release_notes_sha256']
    assert r['checksumSha256']==integrity['checksums_sha256']
    assert r['releaseMetadataSha256']==integrity['release_metadata_sha256']
    assert 'not publicly available yet' in (DOCS/'PUBLIC-RELEASES.md').read_text().lower()
    assert 'wait for its conversation' in (DOCS/'INSTALL.md').read_text().lower()
    sample_screens = [s for s in screens if s.get('candidatePreview') is True]
    assert sample_screens == [], 'The published 0.9.5 captures are not 0.9.6 candidate captures'
    assert all(s['version']==official['version'] for s in current_screens)
    for name in main_pages:
        page_text = (DOCS / name).read_text()
        assert f'/releases/download/v{r["version"]}/' not in page_text, f'{name} links to an unreleased candidate asset'
    assert '0.9.5 captures show the earlier visual flow' in candidate_preview.lower()
    assert 'scope warning' in (ROOT/'README.md').read_text().lower()
elif not published:
    candidate_preview = (DOCS / candidate_page).read_text()
    assert 'not yet publicly released' in candidate_preview.lower()
    assert f'/releases/download/v{r["version"]}/' not in candidate_preview
    for name in main_pages:
        assert f'/releases/download/v{r["version"]}/' not in (DOCS/name).read_text(), f'{name} links to an unreleased candidate asset'
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
assert '0.9.3 corrected those crashes' in (DOCS / 'PUBLIC-RELEASES.md').read_text()
for page in DOCS.rglob('*.html'):
    html = page.read_text()
    if 'class="image-note"' in html:
        assert 'Native Android host captures with dated public stories and labelled synthetic examples.' in html, f'Missing clear screenshot provenance: {page}'
    assert 'preview-banner' not in html, f'Stale candidate banner: {page}'
    if '<footer' in html:
        assert f'MeBot {official["version"]} ·' in html[html.find('<footer'):], f'Stale shared footer version: {page}'
        assert 'Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.' in html[html.find('<footer'):], f'Stale shared footer provenance: {page}'
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
