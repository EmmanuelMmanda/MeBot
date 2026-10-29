#!/usr/bin/env python3
"""Render current MeBot presentation; release records and dated archives are inputs only."""
from pathlib import Path
from html import escape as esc
from datetime import date
import hashlib, json, re, struct
from urllib.parse import urlsplit
ROOT=Path(__file__).resolve().parents[1]; DOCS=ROOT/'docs'
REPO='https://github.com/EmmanuelMmanda/MeBot'; SITE='https://emmanuelmmanda.github.io/MeBot/'
r=json.loads((DOCS/'assets/release.json').read_text())
public_releases=[x for x in r.get('releases',[]) if x.get('status')=='published' and x.get('apk') is True]
official=r if r.get('status')=='published' and r.get('apk') is True else public_releases[-1]
assert official.get('filename')==f'mebot-{official["version"]}.apk', 'Latest APK must match the verified lowercase release asset name'
artifact_base=f'{REPO}/releases/download/{official["tag"]}/'
apk=f'{REPO}/releases/latest/download/{official["filename"]}'
release_url=f'{REPO}/releases/latest'
size=f'{official["sizeBytes"]/1000000:.1f} MB'
screens=json.loads((DOCS/'assets/screens.json').read_text())
assert len(screens)>=5 and {s.get('locale') for s in screens}>={'en','sw'}, 'Reviewed bilingual capture set required before rendering'
capture_version=screens[0]['version']
capture_prefix='/'.join(Path(screens[0]['file']).parts[:2])+'/'
assert all(s.get('version')==capture_version and s['file'].startswith(capture_prefix) for s in screens), 'One reviewed capture build per gallery is required'
required_groups={'feed','opportunity','reads','ask','about','appearance'}
assert required_groups <= {s.get('group') for s in screens}, 'Capture real Feed, opportunity, AI reading, question and About You screens before rendering'
theme_modes={s.get('theme','').split('·',1)[0].strip().lower() for s in screens}
assert {'light','dark'} <= theme_modes, 'Show real app screens in both light and dark themes'
if capture_version != official['version']:
    assert all(s.get('candidatePreview') is True and s.get('liveProvider') is False for s in screens), 'Unpublished screens must remain clearly separate from release and live-account evidence'
for s in screens:
    assert s['file'].startswith('screenshots/v') and capture_prefix.startswith('screenshots/v'), 'Current imagery must come from a versioned showcase directory'
    p=(DOCS/s['file']).resolve(); assert p.is_relative_to(DOCS.resolve()) and p.is_file(), p
    raw=p.read_bytes(); assert raw[:8]==b'\x89PNG\r\n\x1a\n'; s['width'],s['height']=struct.unpack('>II',raw[16:24])
    assert re.fullmatch(r'[a-f0-9]{64}',s.get('sha256','')) and hashlib.sha256(raw).hexdigest()==s['sha256'], f"Reviewed screenshot SHA-256 required: {s['file']}"
    assert (s.get('sourceUrls') or s['group'] in {'about','appearance'}) and s.get('locale') and s.get('theme'), 'Capture provenance, locale and theme required'
    if s['group']=='opportunity':
        assert 'sourceCheckedAsOf' in s and s.get('deadline') and 'publisherPublicationDate' in s, 'Opportunity sources need a checked date, explicit publication-date status and deadline'
        date.fromisoformat(s['sourceCheckedAsOf']); date.fromisoformat(s['deadline'])
        if s['publisherPublicationDate'] is not None: date.fromisoformat(s['publisherPublicationDate'])
    if s['group']=='appearance':
        assert s.get('captureKind')=='native-dialog-window-pixelcopy' and (s['width'],s['height'])==(822,1212)
        assert s.get('sampleData') is False and s.get('providerFixture') is False
    if s['group']=='reads':
        assert s.get('createdDate') and 'publisherPublicationDate' in s, 'Original Read captures need a Created date distinct from publication date'
        date.fromisoformat(s['createdDate'])
        if s['publisherPublicationDate'] is not None: date.fromisoformat(s['publisherPublicationDate'])
shell=(DOCS/'index.html').read_text()
header=shell[shell.index('<a class="skip-link"'):shell.index('<main')]
header=re.sub(r'<aside class="[^"]*release-hold[^"]*".*?</aside>','',header,flags=re.S).replace(' aria-current="page"','')
header=re.sub(r'(<a class="header-download")[^>]*>.*?</a>',f'\\1 href="{apk}" data-release-apk>Download latest version</a>',header,flags=re.S)
footer=shell[shell.index('<footer'):]
footer=footer.replace('A modular Android feed with connected AI and local everyday tools.','A personal Android feed with optional AI and local everyday tools.')
footer=re.sub(r'MeBot 0\.[0-9.]+(?:-beta\.[0-9]+)?(?: preview)? ·',f'MeBot {official["version"]} ·',footer)
footer=re.sub(r'<span>Native Android host renders.*?<a href="guides/privacy.html#this-website">About this site</a></span>','<span><a href="guides/privacy.html#this-website">Privacy and this website</a></span>',footer)
footer=re.sub(r'<p class="image-note">.*?</p>','<p class="image-note">Check the linked source for current dates or requirements. <a id="image-original">Open image</a></p>',footer,flags=re.S)
notice=''
sample_note='Screen images are examples. Any AI response shown uses sample content, not a live account result.'
identity='Install a compatible update over your existing app. Do not uninstall or clear app data as an update step. If Android reports a signing conflict, check the installation guide before changing installations.'
experience='With an AI provider connected, open the story’s question sheet. Choose Send to ask a question, or Edit to change the draft first. Opening the sheet alone sends nothing.'
reads='For You collects sourced stories around topics you choose. Original Read is a separate, longer AI-authored piece with its Created date—not a publisher date. Optional further reading is separate from the reflection and does not verify it by itself.'
opportunities='Browse source-checked grants, jobs, contracts and scholarships. Publication dates and application deadlines appear separately when available. Confirm current eligibility, dates and requirements on the official listing.'
about_you='Set interests and region under About You. Review which notes each module may use, change those choices, or leave personal context empty.'
appearance='Choose English or Kiswahili under You → Personalization → Language. In Appearance, select system, light or dark mode, an accent colour, and one of four page styles.'
question_screen=next(s for s in screens if s['group']=='ask')
suggested_questions=question_screen.get('suggestedQuestions',[])
assert len(suggested_questions)==5 and all(isinstance(q,str) and q.strip() for q in suggested_questions), 'Show the five actual questions from the controlled provider used in the screenshot'
question_sample=question_screen.get('sampleItem')
assert question_sample, 'Name the story used to generate the question sample'
question_examples='<ol class="plain-list">'+''.join(f'<li><span aria-hidden="true">{i+1:02d}</span>{esc(q)}</li>' for i,q in enumerate(suggested_questions))+'</ol>'
source_names={'emaiconference.com':'EMAICON','fsdt.or.tz':'FSDT','pdpc.go.tz':'PDPC','tcra.go.tz':'TCRA','apicon.or.tz':'APICon','unesco.org':'UNESCO','tanzania.un.org':'UN Tanzania'}
def shot(s,hero=False):
    def label(url):
        host=(urlsplit(url).hostname or '').lower().removeprefix('www.')
        return source_names.get(host,host.split('.')[0].upper() or 'Source')
    links=' · '.join(f'<a href="{esc(u,quote=True)}" rel="external">{esc(label(u))}</a>' for u in s['sourceUrls'])
    caption=s['caption'].strip()
    if not re.match(r'(?i)^(?:example|sample|mfano)(?:\s*[:·—-])',caption): caption='Example: '+caption
    links_html=f'<span>{links}</span>' if links else ''
    return f'<figure class="shot" data-screen-group="{esc(s["group"])}"><a href="{esc(s["file"],quote=True)}" data-lightbox data-title="{esc(s["title"],quote=True)}" aria-label="Enlarge: {esc(s["title"],quote=True)}"><img src="{esc(s["file"],quote=True)}" width="{s["width"]}" height="{s["height"]}" alt="{esc(s["alt"],quote=True)}" {"fetchpriority=\"high\"" if hero else "loading=\"lazy\""} decoding="async"></a><figcaption><strong>{esc(s["title"])}</strong><span>{esc(caption)}</span>{links_html}</figcaption></figure>'
def gallery(items): return '<div class="screen-grid three">'+''.join(shot(s) for s in items)+'</div>'
def selected(group,limit=3): return [s for s in screens if s['group']==group][:limit]
def theme_examples():
    light=next(s for s in screens if s.get('theme','').split('·',1)[0].strip().lower()=='light' and s['group']!='appearance')
    dark=next(s for s in screens if s.get('theme','').split('·',1)[0].strip().lower()=='dark')
    controls=next(s for s in screens if s['group']=='appearance')
    return [light,dark,controls]
def download(label='Download latest version'): return f'<a class="button" data-release-apk href="{apk}">{label}</a>'
def page(name,title,desc,body,current=None):
    nav=header.replace(f'href="{current}"',f'href="{current}" aria-current="page"',1) if current else header
    og=screens[0]['file'] if screens else 'assets/mebot.svg'
    head=f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{esc(title)} · MeBot</title><meta name="description" content="{esc(desc,quote=True)}"><meta name="theme-color" content="#f7f8fa"><meta name="color-scheme" content="light dark"><meta name="referrer" content="strict-origin-when-cross-origin"><link rel="canonical" href="{SITE}{name}"><link rel="icon" href="assets/mebot.svg" type="image/svg+xml"><link rel="preload" href="assets/figtree.ttf" as="font" type="font/ttf" crossorigin><meta property="og:title" content="{esc(title,quote=True)}"><meta property="og:image" content="{SITE}{og}"><meta property="og:image:alt" content="{esc(screens[0]["alt"] if screens else "MeBot robot mark",quote=True)}"><link rel="stylesheet" href="site.css"><link rel="stylesheet" href="showcase.css"><script src="site.js" defer></script><script src="showcase.js" defer></script></head><body data-release-manifest="assets/release.json">'
    (DOCS/name).write_text(head+nav+notice+'<main id="main">'+body+'</main>'+footer)
def pick(*groups,limit=3):
    found=[]
    for group in groups:
        found.extend(selected(group,limit))
    unique={}
    for s in found: unique.setdefault(s['file'],s)
    return list(unique.values())
def opportunity_screens():
    return selected('opportunity') or [s for s in screens if 'opportunity' in s['file'].lower()][:3]
hero_screens=pick('reads','about',limit=1)
hero=''.join(shot(s,True) for s in hero_screens[:2])
hero_action='<a class="button secondary" href="tour.html">See how it works</a>'
hero_title='Personal reading, dated opportunities, and everyday tools.'
hero_lead='Read a personal selection or a longer AI-authored Original Read. Check opportunity dates at their official sources, and set your interests in About You.'
hero_status='Download the latest public Android release.'
hero_eyebrow='Android · English · Kiswahili'
page('index.html','MeBot — personal reading and everyday tools','Follow topics you choose, read AI-authored original pieces, check dated opportunities at their official sources, and set About You preferences. Choose English, Kiswahili, and your theme.',f'''
<section class="hero wrap"><div class="hero-copy"><p class="eyebrow">{hero_eyebrow}</p><h1>{hero_title}</h1><p class="hero-lead">{hero_lead}</p><div class="actions">{download()}{hero_action}</div><p class="micro">{hero_status}</p><p class="micro">{sample_note}</p></div><div class="hero-phones">{hero}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">For You · Original Read</p><h2>Stories selected for your interests.</h2><p>{reads}</p></div><a class="text-link" href="screenshots.html">See app screens</a></div>{gallery(pick('feed','reads',limit=1))}</section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Grants · jobs · contracts · scholarships</p><h2>Check dates and deadlines at the source.</h2><p>{opportunities}</p></div></div>{gallery(opportunity_screens())}</section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">About You</p><h2>Choose what personal context to use.</h2><p>{about_you}</p></div></div>{gallery(selected('about'))}</section>
<section class="section wrap hairline" id="story-questions"><div class="section-heading"><div><p class="eyebrow">Story questions</p><h2>Ask your selected AI about a story.</h2><p>{experience}</p></div></div><p class="micro">Example questions for {esc(question_sample)}. Suggestions depend on that story and the AI connection you choose.</p>{question_examples}{gallery(selected('ask'))}<a class="text-link" href="guides/feed-and-ai.html#story-questions">How story questions work</a></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Language · theme · colour</p><h2>Set your language, colour, and page style.</h2><p>{appearance}</p><p lang="sw">Chagua Kiswahili au English chini ya Wewe → Binafsisha → Lugha. Kwenye Mwonekano, chagua mandhari ya mfumo, mwanga au giza, rangi kuu na mtindo wa kurasa.</p></div></div>{gallery(theme_examples())}<a class="text-link" href="guides/appearance.html">Language and appearance settings</a></section>
<section class="section wrap hairline"><h2>Alarms, check-ins, and Focus</h2><p>Save a story, set an alarm, record a Daily Check-in, or start a Focus session. These local tools work without connecting an AI provider.</p>{gallery(selected('tools'))}</section>
<section class="next-step wrap"><div><h2>Download the latest version.</h2><p>{hero_status}</p></div><div class="actions">{download()}<a class="button secondary" href="download.html">File details</a></div></section>''','index.html')
groups=list(dict.fromkeys(s['group'] for s in screens)); group_titles={'feed':'For You','read':'Stories','opportunity':'Opportunities','ask':'AI questions','reads':'AI Reads and Today','about':'About You','appearance':'Languages and themes','theme':'Languages and themes','tools':'Everyday tools','start':'Getting started','organize':'Your preferences'}
filters=''.join(f'<button type="button" data-screen-filter="{g}" aria-pressed="{str(g=="all").lower()}">{"All screens" if g=="all" else group_titles.get(g,g.title())}</button>' for g in ['all',*groups])
release_status='Only the latest published release is available from this page.'
gallery_dir=capture_prefix.rstrip('/')
capture_record=DOCS/gallery_dir/'README.md'
assert capture_record.is_file(), 'A technical capture record with source/date/fixture provenance is required before rendering current pages'
page('screenshots.html','MeBot screens','See personal reading, dated opportunities, story questions, About You, appearance controls, English and Kiswahili, and light and dark themes.',f'<section class="section wrap"><p class="eyebrow">English · Kiswahili · light · dark</p><h1>MeBot screens</h1><p class="lead">Examples show how to read a story, check its source, set preferences, and ask a follow-up question. {sample_note}</p><div class="screen-filters js-only" role="group" aria-label="Filter app screens">{filters}</div><p id="screen-count" role="status" class="micro">{len(screens)} screens</p><div class="screen-grid" id="screen-gallery">{"".join(shot(s) for s in screens)}</div></section><section class="section wrap hairline" id="image-notes"><h2>Check dates and requirements</h2><p>Dates and deadlines can change. Open the linked official source before acting.</p><p>{release_status} Screen examples do not show a signed-in provider account or live AI response.</p><a href="{gallery_dir}/README.md">Screen sources and capture notes</a></section>','screenshots.html')
resources=[('Matching source','sourceFilename'),('Technical report · prepublication snapshot','verificationFilename'),('Release notes','releaseNotesFilename'),('SHA-256 checksums','checksumFilename'),('Release metadata','releaseMetadataFilename')]
report_note='The verification report records release checks and their limits. The identity receipt contains the matching published artifact details.'
resource_html=''.join(f'<div class="download-row"><div><strong>{title}</strong><p>{report_note if key=="verificationFilename" else "File from the latest published release."}</p></div><a href="{artifact_base}{official[key]}">Download <span class="sr-only">{title}</span></a></div>' for title,key in resources)
download_lead='Download the latest public Android app. Review the matching source, release notes, checksums, and verification record below.'
download_status='<h2>Update in the app</h2><p>On a compatible installation, open You → Software Updates to check for the latest release. Review the download, then approve Android’s installation prompt.</p><a href="guides/install.html">Read the installation guide</a>'
release_benefit='<strong>Latest public release.</strong> This page lists only the artifacts published for the current app.'
page('download.html','Download MeBot','Download the latest published MeBot Android app and its matching source, checksums, release notes, and verification record.',f'''<div class="release-page"><h1>Download MeBot</h1><p class="lead">{download_lead}</p><aside class="beta-note">{release_benefit}</aside><section class="release-card"><span data-release-status>Latest public Android release</span><h2>Android app</h2><div class="actions">{download()}<a class="button secondary" href="{release_url}">Release details</a></div><p>{identity}</p><p>Android {official['minimumAndroid']}+ · <span data-release-size>{size}</span>. JavaScript is not required.</p><details class="release-metadata"><summary>File identity and verification</summary><p>Package <code>{official['applicationId']}</code> · version code {official['versionCode']}.</p><p><code data-release-filename>{official['filename']}</code></p><p>Published source <code>{official['sourceCommit']}</code> · tree <code>{official['sourceTree']}</code>.</p><p>Signer SHA-256 <code>{official['certificateSha256']}</code>.</p><div class="code-block"><div class="code-toolbar"><span>APK SHA-256</span><button type="button" class="copy-button js-only" data-copy-target="apk-checksum">Copy checksum</button></div><pre id="apk-checksum" data-release-sha tabindex="0">{official['sha256']}</pre></div></details></section><section class="article-section" id="resources"><h2>Release files</h2><div class="download-list">{resource_html}</div><p><a href="{official["integrityFile"]}">Published identity and verification receipt</a></p></section><section class="article-section">{download_status}</section><section class="article-section" id="limits"><h2>What the verification covers</h2><p>Automated tests and host screenshots do not establish live provider access, physical-phone playback, biometric behavior, OEM file-picker support, or overnight alarms. Test device-specific features before relying on them.</p></section></div>''','download.html')
page('releases.html','MeBot release files','Download the latest published MeBot Android app and inspect its matching file identity and verification record.',f'<section class="section wrap"><h1>Latest Android release</h1><p class="lead">Download the latest published app or review the identity and verification details for this file.</p><div class="actions">{download()}<a class="button secondary" href="download.html">File identity and checksums</a></div><p><a href="{release_url}">Open the latest GitHub release files</a></p></section>','releases.html')
steps=[('Choose topics','Select the updates you want and narrow them by interest or location.','guides/install.html',None),('Set About You',about_you,'guides/personal-context.html#after-import',None),('Read and check sources',opportunities,'guides/feed-and-ai.html', 'opportunity'),('Ask a follow-up',experience,'guides/feed-and-ai.html#story-questions','ask'),('Open an Original Read',reads,'guides/feed-and-ai.html','reads'),('Choose language and appearance',appearance,'guides/appearance.html','theme'),('Use local tools','Set an alarm or start a Focus session; local tools remain available without an AI connection.','guides/background-and-data.html','tools')]
controls=''.join(f'<button type="button" data-tour-step="{i}" aria-pressed="{str(i==0).lower()}" aria-controls="tour-panel-{i}"><span aria-hidden="true">{i+1:02d}</span>{title}</button>' for i,(title,*_) in enumerate(steps))
panels=''.join(f'<section class="tour-panel" id="tour-panel-{i}" aria-labelledby="tour-heading-{i}"><div class="tour-copy"><p class="eyebrow">Step {i+1} of {len(steps)}</p><h2 id="tour-heading-{i}">{title}</h2><p class="lead">{body}</p><a class="text-link" href="{guide}">Open the guide →</a></div>{shot((theme_examples()[1] if group=="theme" else selected(group,1)[0])) if group and (group=="theme" or selected(group,1)) else ""}</section>' for i,(title,body,guide,group) in enumerate(steps))
page('tour.html','Using MeBot','How to set interests, review sources, ask a story question, choose a language, and use local tools.',f'<section class="section wrap"><p class="eyebrow">MeBot tasks</p><h1>Using MeBot</h1><p class="lead">Choose what you read and which optional tools to use.</p><div class="tour-layout"><nav class="tour-steps js-only" aria-label="App tasks">{controls}</nav><div class="tour-panels">{panels}</div></div><div class="tour-navigation js-only"><button class="button secondary" type="button" data-tour-prev>← Back</button><span id="tour-status" role="status" aria-live="polite">Step 1 of {len(steps)}</span><button class="button" type="button" data-tour-next>Next →</button></div></section>','tour.html')
old_readme=(ROOT/'README.md').read_text(); tail=old_readme[old_readme.index('## Choose your AI connection'):]
# Keep the established useful provider/build guidance; release-specific identity is regenerated below.
tail=tail.split('## Install and use')[0].split('## Build')[0]+'''## Build

JDK **17**, Gradle **8.13**, Android SDK **36**, Build Tools **35.0.0**.

```sh
git clone https://github.com/EmmanuelMmanda/MeBot.git
cd MeBot
git switch develop
python3 scripts/prepare-build.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

Tests use controlled providers. Real account access, physical-phone media, file pickers and overnight alarms require device checks. No private profile, provider credential or signing key belongs in public source. See [Build](docs/BUILD.md), [Contributing](CONTRIBUTING.md), [Privacy](docs/PRIVACY.md), and [Security](SECURITY.md).

[Apache 2.0](LICENSE). Third-party content retains its own [notices](NOTICE) and [credits](docs/IMAGE-CREDITS.md). MeBot is independent, not endorsed by any provider or publisher.
'''
image_rows=[]
for i in range(0,len(screens),3):
    ss=screens[i:i+3]; image_rows.append('<p align="center">'+'\n'.join(f'<a href="docs/{s["file"]}"><img src="docs/{s["file"]}" width="225" alt="{esc(s["alt"],quote=True)}"></a>' for s in ss)+'</p>\n\n'+' · '.join(f'**{s["title"]}**' for s in ss))
(ROOT/'README.md').write_text(f'''<div align="center"><img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo"><h1>MeBot</h1><p>A personal feed for stories, opportunities and everyday tools.</p><p>Android · English · Kiswahili · Apache 2.0</p></div>

[Download latest version]({apk}) · [Installation guide](docs/download.html) · [App screens](docs/screenshots.html)

## Personal reading and story questions

{reads}

{opportunities}

{experience}

## About You and appearance

{about_you}

{appearance}

MeBot also includes Saved reading, Focus, Daily Check-in and alarms. Local tools remain available without an AI connection.

## Screens

{chr(10).join(image_rows)}

{sample_note} Check linked sources for current dates and requirements. Technical capture and provenance are in the [capture record](docs/{gallery_dir}/README.md).

## Install and verify

[Download latest version]({apk}) · [Latest release files]({release_url}) · [Start here](START-HERE.md) · [User guide](docs/USER-GUIDE.md).

{identity}

{tail}''')
(ROOT/'START-HERE.md').write_text(f'# Start here\n\n[Download latest version]({apk}) · [Installation guide](docs/download.html).\n\n'+'\n\n'.join(f'## {i+1}. {title}\n\n{body}\n\n[Open the guide](docs/{guide}).' for i,(title,body,guide,_) in enumerate(steps))+f'\n\n{identity}\n\n[App screens](docs/screenshots.html) · [User guide](docs/USER-GUIDE.md).\n')
print(f'Rendered the latest published release presentation with {len(screens)} native captures; historical pages and release records untouched.')
