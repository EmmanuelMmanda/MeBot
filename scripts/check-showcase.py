#!/usr/bin/env python3
"""Validate current preview presentation, published asset identity and preserved archives."""
from pathlib import Path
from html.parser import HTMLParser
import json, re, hashlib
from datetime import date, datetime, timezone
ROOT=Path(__file__).resolve().parents[1]; DOCS=ROOT/'docs'
r=json.loads((DOCS/'assets/release.json').read_text())
screens=json.loads((DOCS/'assets/screens.json').read_text())
published=r['status']=='published'
official=r if published else next(x for x in r['releases'] if x.get('status')=='published' and x.get('apk') is True)
base=f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{official['tag']}/"
expected_apk=base+official['filename']
assert r['tag']=='v'+r['version'] and official['tag']=='v'+official['version']
integrity=json.loads((DOCS/r['integrityFile']).read_text())
for a,b in [('sourceCommit','source_code_commit'),('sourceTree','source_tree'),('applicationId','application_id'),('versionCode','version_code'),('filename','apk_file'),('sha256','apk_sha256'),('sizeBytes','apk_size_bytes'),('certificateSha256','signer_certificate_sha256')]:
    assert r[a]==integrity[b], f'Preview identity mismatch: {a}'
if not published:
    assert r['privateCandidate'] and not r['publicAssetsAvailable'] and not r['inAppUpdateAvailable']
    assert integrity['publication_status']=='pending' and integrity['published_at'] is None
    assert not integrity['public_assets_available'] and not integrity['in_app_update_available']
    if not r['apk']:
        assert all(r[k] is None for k in ('filename','sha256','sizeBytes','certificateSha256'))
        assert not integrity['apk_signature_verified']
    assert 'publishedDate' not in r and 'publishedAt' not in r
else:
    assert r['publicAssetsAvailable'] and r['inAppUpdateAvailable'] and not r['privateCandidate']
public_integrity=json.loads((DOCS/official['integrityFile']).read_text())
for a,b in [('sourceCommit','source_code_commit'),('sourceTree','source_tree'),('filename','apk_file'),('sha256','apk_sha256'),('sizeBytes','apk_size_bytes'),('certificateSha256','signer_certificate_sha256')]:
    assert official[a]==public_integrity[b], f'Published identity mismatch: {a}'
assert public_integrity['publication_status']=='published'
for a,b in [('sourceFilename','source_file'),('sourceSha256','source_sha256'),('sourceSizeBytes','source_size_bytes')]:
    assert official[a]==public_integrity[b], f'Published source identity mismatch: {a}'
for key in ('sha256','sourceSha256','verificationSha256','releaseNotesSha256','checksumSha256','releaseMetadataSha256','certificateSha256'):
    assert re.fullmatch('[a-f0-9]{64}',official[key]), key
assert re.fullmatch('[a-f0-9]{40}',official['sourceCommit']) and re.fullmatch('[a-f0-9]{40}',official['sourceTree'])
assert official['sizeBytes']>0 and official['sourceSizeBytes']>0
published_at=datetime.fromisoformat(official['publishedAt'].replace('Z','+00:00'))
assert published_at.tzinfo is not None and published_at.utcoffset()==timezone.utc.utcoffset(published_at)
assert published_at.date().isoformat()==official['publishedDate']
assert official['publishedAt']==public_integrity['published_at']
assert public_integrity['public_release_url']==f"https://github.com/EmmanuelMmanda/MeBot/releases/tag/{official['tag']}"
assert official['publicAssetsAvailable'] is True and official['inAppUpdateAvailable'] is True
for public_key,proof_key in [('publicAssetsAvailable','public_assets_available'),('inAppUpdateAvailable','in_app_update_available')]:
    if proof_key in public_integrity: assert official[public_key]==public_integrity[proof_key]
if 'public_release_latest' in public_integrity: assert public_integrity['public_release_latest'] is True
assert len(screens)>=5, 'Require meaningful feed, Today, detail, opportunity and appearance coverage'
assert len({s['file'] for s in screens})==len(screens)
assert {s['locale'] for s in screens} >= {'en','sw'}
assert len({s['theme'] for s in screens})>=3, 'Actual app theme variety, not a single recoloured image'
assert {s['group'] for s in screens} >= {'read','reads','appearance'}
assert {s['file'] for s in screens if s['group']=='appearance'} >= {'screenshots/v098-showcase/en-appearance-light-violet.png','screenshots/v098-showcase/en-appearance-page-style-light-violet.png'}, 'Show both actual theme/accent and scrolled page-style controls'
for s in screens:
    assert s['version']==r['version'] and s['file'].startswith('screenshots/v098-showcase/')
    assert (DOCS/s['file']).is_file()
    assert all(s.get(k) for k in ('alt','caption','title','captureDate','locale','theme'))
    assert s['sourceUrls'] or s['group']=='appearance'
    date.fromisoformat(s['captureDate'])
    assert all(u.startswith('https://') for u in s['sourceUrls'])
    if s.get('sha256'): assert hashlib.sha256((DOCS/s['file']).read_bytes()).hexdigest()==s['sha256']
class Inspect(HTMLParser):
    def __init__(self):
        super().__init__(); self.downloads=[]; self.images=[]; self.links=[]; self.groups=0
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=='a':
            self.links.append(a.get('href',''))
            if 'data-release-apk' in a:self.downloads.append(a)
        if tag=='img':self.images.append(a)
        if 'data-screen-group' in a:self.groups+=1
candidate_page=f"preview-{r['version'].replace('.','')}.html"
main_pages=('index.html','screenshots.html','download.html','releases.html','tour.html',candidate_page)
current=[DOCS/n for n in main_pages]+[DOCS/'guide.html']+[p for p in (DOCS/'guides').glob('*.html') if p.name!='release-082.html']
for path in current:
    text=path.read_text(); p=Inspect(); p.feed(text)
    assert r['version'] in text and official['version'] in text, path
    assert '/releases/latest' not in text and '/blob/master/' not in text
    if not published:
        assert f"/releases/download/{r['tag']}/" not in text
        assert 'preview' in text.lower(), path
    for im in p.images:
        assert 'alt' in im, path
        src=im.get('src','')
        if 'screenshots/' in src:
            assert im['alt'].strip(), (path,src)
            assert 'screenshots/v098-showcase/' in src, (path,src)
    for a in p.downloads: assert a['href']==expected_apk and 'hidden' not in a, path
    if path.name in main_pages:
        for url in p.links:
            if '/releases/download/' in url:
                assert url.startswith(base), (path,url)
                assert url.rsplit('/',1)[-1] in {official[k] for k in ('filename','sourceFilename','verificationFilename','releaseNotesFilename','checksumFilename','releaseMetadataFilename')}
    if path.name=='screenshots.html':
        assert p.groups==len(screens)
        assert 'id="screen-count" role="status"' in text
        assert 'data-screen-filter="all" aria-pressed="true"' in text
        assert {im['src'] for im in p.images if 'screenshots/' in im.get('src','')}=={s['file'] for s in screens}
    if path.name=='download.html':
        for k in ('filename','sourceFilename','verificationFilename','sourceCommit','sourceTree','applicationId','certificateSha256','releaseNotesFilename','checksumFilename','releaseMetadataFilename'):
            assert official[k] in text, k
        assert 'Do not uninstall' in text and 'physical-phone' in text
for name in ('README.md','START-HERE.md'):
    text=(ROOT/name).read_text()
    assert expected_apk in text and r['version'] in text and 'not yet a public download' in text.lower()
    assert not re.search(r'screenshots/(?!v098-showcase/)',text)
for name in ('USER-GUIDE.md','INSTALL.md','PRIVACY.md','ACTIVITY-AND-DISCOVERY.md'):
    text=(DOCS/name).read_text()
    assert not re.search(r'!\[[^\]]*\]\(screenshots/(?!v098-showcase/)',text), name
    assert r['version'] in text, name
readme=(ROOT/'README.md').read_text()
assert readme.count('<img ')>=len(screens) and official['sourceCommit'] in readme
assert 'five distinct questions' in readme and 'pencil' in readme and 'Without AI' in readme
assert 'Created dates' in readme and 'not a publication date' in readme
for path in (ROOT/'README.md',ROOT/'START-HERE.md',DOCS/'USER-GUIDE.md'):
    assert 'offline price comparison tool' not in path.read_text().lower()
assert 'after-import' in (DOCS/'guides/personal-context.html').read_text()
assert 'up to eight public stories per module' in (DOCS/'guides/feed-and-ai.html').read_text()
assert 'After import, I do not see my notes.' in (DOCS/'guides/troubleshooting.html').read_text()
provider=(DOCS/'guides/ai-connections.html').read_text()
assert 'billed separately from a ChatGPT subscription' in provider
assert 'does not give MeBot your conversations or saved memory' in provider
assert 'never switches from plan usage to API billing automatically' in provider
assert 'Keys stay out of context and backup exports' in provider
assert 'separate API billing' in readme and 'Claude.ai subscription credentials are not used' in readme
assert 'known launch failures' in (DOCS/'releases.html').read_text()
assert 'MeBot 0.9.6 is the current official release' not in (DOCS/'PUBLIC-RELEASES.md').read_text()
# Frozen historical evidence remains byte-identical; do not rewrite old release claims as new proof.
assert hashlib.sha256((DOCS/'releases/v0.9.5.json').read_bytes()).hexdigest()=='3eb625d5bbd60b89aa1a91d674c6c8625ba380554f8a05daa43028714b4b47be'
assert hashlib.sha256((DOCS/'RELEASE-0.9.5.md').read_bytes()).hexdigest()=='19808c5b6c1cf2e8b0ea1ff0242e14799a0a563df31ffeadc9fb7375b08cd7cf'
for presentation in [*current,ROOT/'README.md',ROOT/'START-HERE.md',DOCS/'screenshots/v098-showcase/README.md']:
    text=presentation.read_text()
    assert not re.search(r'/(?:workspace|home/agent|tmp)/',text), f'Executor path leaked: {presentation}'
# Archive bodies remain dated; shared current navigation must not misidentify an older APK.
for archive in [*DOCS.glob('preview-*.html'),DOCS/'guides/release-082.html']:
    text=archive.read_text()
    assert 'MeBot 0.9.6 is the current official' not in text, archive
    assert 'get the current official MeBot 0.9.6' not in text, archive
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
print(f'Showcase checks passed: {len(screens)} fresh bilingual themed captures; unsigned preview, exact published assets and historical records checked.')
