#!/usr/bin/env python3
"""Prepare 0.8.1 from the hash-pinned, tested CI artifact; no signing secrets."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1];DOCS=ROOT/'docs'
CI=Path(os.environ['MEBOT_CANDIDATE_DIR']);OUT=Path(os.environ['RELEASE_DIRECTORY']);OUT.mkdir(parents=True,exist_ok=True)
VERSION='0.8.1';TAG='v'+VERSION;REPO='https://github.com/EmmanuelMmanda/MeBot'
m=json.loads((ROOT/'release/0.8.1/signed-apk.json').read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def totals(paths):
 result=dict(tests=0,failures=0,errors=0,skipped=0)
 for p in paths:
  root=ET.parse(p).getroot();suites=[root] if root.tag=='testsuite' else root.findall('testsuite')
  for suite in suites:
   for k in result:result[k]+=int(suite.get(k,0))
 return result
counts={'core':totals((CI/'core/build/test-results/test').glob('TEST-*.xml')),'android':totals((CI/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')),'service':totals((CI/'service').glob('test-results.xml'))}
for k,n in [('core',143),('android',405),('service',145)]:
 assert counts[k]==dict(tests=n,failures=0,errors=0,skipped=0),(k,counts[k])
assert (CI/'review-input/SOURCE-COMMIT.txt').read_text().strip()==m['sourceCommit']
assert sha(CI/'review-input/mebot-0.8.1-source-review.zip')=='3fe0fde8dd8ee053ef71d9fc97b144bc516f0a97982931833106e9dbbd5566dc'
assert sha(OUT/f'mebot-{VERSION}.apk')==m['signedSha256']
issues=ET.parse(CI/'app/build/reports/lint-results-release.xml').getroot().findall('issue')
lint={k:sum(x.get('severity')==k for x in issues) for k in ('Error','Fatal','Warning','Hint')}
assert not lint['Error'] and not lint['Fatal']
for p in (CI/'app/build/outputs/screenshots').glob('mebot-0.8.1-*.png'):
 target=DOCS/'screenshots/v081'/p.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
previous=json.loads((DOCS/'assets/release.json').read_text())
r={k:v for k,v in previous.items() if k!='releases'}
r.update(version=VERSION,tag=TAG,status='published',channel='official',versionCode=12,filename=f'mebot-{VERSION}.apk',sourceCommit=m['sourceCommit'],sourceRef=TAG,sourceIntegrityFile='SHA256SUMS.txt',sha256=m['signedSha256'],sizeBytes=m['signedSizeBytes'],sourceFilename=f'mebot-{VERSION}-source.zip',previewsFilename=f'mebot-{VERSION}-resources.zip',verificationFilename=f'mebot-{VERSION}-verification.txt',testsFilename=f'mebot-{VERSION}-resources.zip',documentationFilename=f'mebot-{VERSION}-documentation.zip',publishedDate='2026-10-25',integrityFile=f'releases/{TAG}.json',notes='Official original-certificate MeBot update. Runtime files match CI run 40126322372; release metadata and documentation are finalized separately.')
r.pop('sourceSha256',None);r.pop('sourceSizeBytes',None)
history=[x for x in previous.get('releases',[]) if x['version']!=VERSION]
if previous['version']!=VERSION and previous['version'] not in [x['version'] for x in history]:history.insert(0,{k:v for k,v in previous.items() if k!='releases'})
r['releases']=[{k:v for k,v in r.items() if k!='releases'}]+history
(DOCS/'assets/release.json').write_text(json.dumps(r,indent=2)+'\n')
(DOCS/f'releases/{TAG}.json').write_text(json.dumps(dict(version=VERSION,apk_file=r['filename'],apk_sha256=r['sha256'],apk_size_bytes=r['sizeBytes'],personal_profile_asset_present=False,application_id='app.mynguli',version_code=12,signer_certificate_sha256=m['certificateSha256'],source_code_commit=m['sourceCommit'],source_ref=TAG,source_integrity_file='SHA256SUMS.txt'),indent=2)+'\n')
screens=json.loads((DOCS/'assets/screens.json').read_text())
for s in screens:
 if s['file'].startswith('screenshots/v080/'):
  name=Path(s['file']).name;s['file']=f'screenshots/v081/mebot-0.8.1-{name}';s['version']=VERSION
screens=[s for s in screens if not s['file'].endswith('mebot-0.8.1-activity-details.png')]
extra=dict(file='screenshots/v081/mebot-0.8.1-detail-working.png',title='Read while MeBot searches',alt='An example opportunity stays readable below the fixed activity panel; Show more reveals notes.',caption='The active badges follow this request. The opportunity is example test content.',group='read',version=VERSION)
if not any(s['file']==extra['file'] for s in screens):screens.append(extra)
(DOCS/'assets/screens.json').write_text(json.dumps(screens,indent=2)+'\n')
p=ROOT/'scripts/build-showcase.py';s=p.read_text()
s=s.replace('version code **11**','version code **{r.get("versionCode", 12)}**')
s=s.replace('Four screens are from 0.8.0.','Five current screens are from 0.8.1.')
s=s.replace('The four latest 0.8.0 screens appear first.','Current 0.8.1 screens are shown with their capture version.')
s=s.replace('screenshots/v080/','screenshots/v081/mebot-0.8.1-')
s=s.replace('current test APK','current official APK').replace('"AI → send a question"','"MeBot → send a question"')
s=s.replace('Published testing release" if x["status"] == "published"', 'Published release" if x["status"] == "published"')
s=s.replace('A single top activity panel, cleaner answers and story details, independent media loading, supported file downloads, eight accent presets, a custom colour and four page styles. Existing onboarding, recommendations, modules and local tools remain.','Accurate request-specific activity in a fixed top panel; readable details with Show more; selected-module refresh; editable Tanzania-focused defaults and public fallbacks; verified file availability. Existing appearance choices, media, modules and local tools remain.')
p.write_text(s)
for p in DOCS.rglob('*.html'):
 s=p.read_text().replace('MeBot 0.8.0</a>. See update','MeBot 0.8.1</a>. See update').replace('MeBot 0.8.0 ·','MeBot 0.8.1 ·')
 p.write_text(s)
(DOCS/'screenshots/v081/README.md').write_text('# MeBot 0.8.1 captures\n\nNative Android host renders from controlled provider fixtures, not live news or a physical phone. The activity-details image is an isolated panel fixture; the popup is a separate Android window and is not shown in that capture. Detail-working illustrates the actual page and scoped running badges. No account secrets or private user profile appear.\n')
release_notes='''# MeBot 0.8.1

Official MeBot update, signed with the original release certificate. Android 8.0+.
Install over your existing official MeBot; do not uninstall it. The older MeBot Test package remains separate.

## Changes
- Fixed top activity panel stays outside page scrolling. Expand it for actual request stages and provider-published summaries when available.
- Rounded activity rings follow the associated request and stop when it finishes. Obsolete feed loaders are removed.
- Discovery, search and answers use different status labels. Counts appear only after validation; no invented thoughts or estimates.
- The conversation destination is MeBot. Original details remain readable while an explanation loads, with a compact Show more / Show less action.
- Pull-to-refresh, refresh and Feed reselection respect the selected module and refresh its public-source cache.
- Editable Tanzania defaults use local public sources where appropriate; explicit preferences and custom sources win. Small per-install variation breaks ties, not relevance.
- Public file availability is checked before verified Files counts and Save file. Media checks use independent capacity.

## Installation and verification
Package `app.mynguli`, version code 12. Original certificate SHA-256: `b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9`.

693 tests passed: 143 core, 405 Android-host, 145 service, no failures or skipped tests. Release compilation passed. The full CI lint report contains zero errors, 144 warnings and one hint. The earlier local summary counted 143 warnings; this release reports the independent CI result.

The signed APK is assembled from the exact verified unsigned build plus its locally generated public APK signature block; no private signing key is sent to GitHub. Every application ZIP entry is unchanged and the result is verified with Android apksigner and zipalign.

The public source ZIP is an archive of the release tag. Application sources match tested commit `59ef0bf50c4e7d5a1d66d9221535f5d571eba94f`; later changes are publication scripts, documentation and screenshots only.

Physical-phone alarms, live AI account access, provider playback and Android's file picker still need device checks. File availability can change after checking. Protected files and paywalls are not bypassed. Public downloads remain bounded to 20 MiB.

[Illustrated activity and discovery guide](https://emmanuelmmanda.github.io/MeBot/guides/activity.html) · [Downloads](https://emmanuelmmanda.github.io/MeBot/download.html)
'''
(DOCS/'RELEASE-0.8.1.md').write_text(release_notes)
ch=ROOT/'CHANGELOG.md';text=ch.read_text()
if '## 0.8.1' not in text:
 i=text.find('\n');text=text[:i+1]+'\n## 0.8.1 — 2026-10-25\n\nAccurate anchored activity; scoped refresh; compact MeBot details; Tanzania public-source defaults; verified resource actions. See [release notes](docs/RELEASE-0.8.1.md).\n'+text[i+1:];ch.write_text(text)
(OUT/'test-counts.json').write_text(json.dumps(dict(tests=counts,lint=lint),indent=2)+'\n')
subprocess.run(['python3','scripts/build-showcase.py'],cwd=ROOT,check=True)
shell=(DOCS/'guides/resources.html').read_text();start=shell.index('<main');end=shell.index('</main>')+len('</main>')
body='''<main id="main"><article class="wrap section"><p class="eyebrow">MeBot 0.8.1 · Guide</p><h1>Activity, refresh and useful files</h1><p class="lead">See what MeBot is doing without losing the information you are reading.</p>
<section class="article-section" id="activity"><h2>One fixed activity panel</h2><p>The top panel stays in place while you scroll. Its ring appears only during an active request. Tap it to see the operation history; tap Show less or outside the popover to close it.</p><p>Labels reflect actual work: preparing, connecting, searching, reading, checking results or preparing updates. Writing an answer is used for conversations, not feed discovery. Elapsed time is not a promise of time remaining.</p><p>Provider-published summaries may appear when supplied. MeBot does not show private internal reasoning or invent listing counts.</p></section>
<section class="article-section" id="details"><h2>Keep reading while work continues</h2><p>Original item information stays visible. Use Show more for notes and next steps. Ask MeBot continues with this item and its allowed context. Running badges elsewhere belong only to their own requests.</p><figure class="guide-figure"><a href="../screenshots/v081/mebot-0.8.1-detail-working.png" data-lightbox data-title="Readable details during a request"><img src="../screenshots/v081/mebot-0.8.1-detail-working.png" width="822" height="1782" loading="lazy" alt="Example opportunity with a fixed searching panel and a compact Show more action"></a><figcaption>Native host render with example opportunity text, not a live vacancy.</figcaption></figure></section>
<section class="article-section" id="refresh"><h2>Refresh the topic you selected</h2><p>Select a module, then pull down. The refresh button and tapping Feed again use the same selected module. Feed reselection also returns to the top. Explicit refresh invalidates the relevant public-source cache; overlapping requests are combined.</p><p>Sources sometimes have nothing new. MeBot does not invent items to make a refresh look different.</p></section>
<section class="article-section" id="local"><h2>Local information, with your choices first</h2><p>Tanzania-focused defaults apply when selected, or when no region is selected and the saved timezone is Africa/Dar_es_Salaam. A different chosen region wins. This does not infer nationality, precise location or job eligibility.</p><p>Default local news uses Daily News and BBC Swahili, with business coverage from Daily News. Public sources work without an AI account; they retain the publisher’s language when translation is unavailable. Custom source lists are preserved.</p></section>
<section class="article-section" id="files"><h2>Check before saving</h2><p>A PDF-looking link is not enough. MeBot checks public availability, redirects, size, content type and a small file prefix before showing verified file counts and Save file.</p><p>If a check fails, Open original remains. Check file retries. Saving rechecks the complete file and opens Android’s save picker. Downloads are limited to 20 MiB; authenticated content is not bypassed. File checks have separate capacity and do not block the answer.</p><figure class="guide-figure"><a href="../screenshots/v081/mebot-0.8.1-ai-resources.png" data-lightbox data-title="Answers and file resources"><img src="../screenshots/v081/mebot-0.8.1-ai-resources.png" width="822" height="1782" loading="lazy" alt="MeBot answer and grouped file resource controls"></a><figcaption>Native Android example. A failed resource check does not remove a completed answer.</figcaption></figure></section>
<section class="article-section" id="language"><h2>English and Kiswahili</h2><p>Activity labels follow the app language. Generated information follows the module or app language. Public publisher content is not falsely labelled as translated.</p><p><a href="../download.html">Download MeBot 0.8.1 →</a> · <a href="../tour.html">Illustrated setup tour →</a></p></section></article></main>'''
html=shell[:start]+body+shell[end:]
import re
html=re.sub(r'<title>.*?</title>','<title>Activity, refresh and useful files · MeBot</title>',html)
html=html.replace('guides/resources.html','guides/activity.html').replace('href="#media"','href="#files"').replace('href="#story"','href="#details"')
(DOCS/'guides/activity.html').write_text(html)
for name in ['guide.html','guides/feed-and-ai.html','guides/resources.html']:
 p=DOCS/name;t=p.read_text();href='guides/activity.html' if name=='guide.html' else 'activity.html'
 if 'Activity, refresh and useful files' not in t:t=t.replace('</main>',f'<section class="wrap section"><h2>Activity, refresh and useful files</h2><p><a href="{href}">Read the 0.8.1 illustrated guide →</a></p></section></main>')
 p.write_text(t)
for name in ['README.md','docs/USER-GUIDE.md']:
 p=ROOT/name;t=p.read_text();marker='## MeBot 0.8.1 guide'
 if marker not in t:t+='\n'+marker+'\n\n[Activity, scoped refresh and verified files](https://emmanuelmmanda.github.io/MeBot/guides/activity.html) — illustrated steps for the current release.\n'
 p.write_text(t)
subprocess.run(['python3','scripts/check-docs.py'],cwd=ROOT,check=True)
subprocess.run(['python3','scripts/check-showcase.py'],cwd=ROOT,check=True)
print(json.dumps(dict(tests=counts,lint=lint),indent=2))
