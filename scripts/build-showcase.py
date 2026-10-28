#!/usr/bin/env python3
"""Render current MeBot presentation; release records and dated archives are inputs only."""
from pathlib import Path
from html import escape as esc
import json, re, struct
ROOT=Path(__file__).resolve().parents[1]; DOCS=ROOT/'docs'
REPO='https://github.com/EmmanuelMmanda/MeBot'; SITE='https://emmanuelmmanda.github.io/MeBot/'
r=json.loads((DOCS/'assets/release.json').read_text()); v=r['version']; pending=r['status']!='published'
official=r if not pending else next(x for x in r['releases'] if x.get('status')=='published' and x.get('apk') is True)
base=f'{REPO}/releases/download/{official["tag"]}/'; apk=base+official['filename']; release_url=f'{REPO}/releases/tag/{official["tag"]}'
preview=f'preview-{v.replace(".","")}.html'; size=f'{official["sizeBytes"]/1000000:.1f} MB'
screens=json.loads((DOCS/'assets/screens.json').read_text())
assert len(screens)>=5 and {s.get('locale') for s in screens}>={'en','sw'}, 'Reviewed bilingual capture set required before rendering'
for s in screens:
    assert s['file'].startswith('screenshots/v098-showcase/'), 'Current imagery must come from the reviewed fresh showcase'
    p=(DOCS/s['file']).resolve(); assert p.is_relative_to(DOCS.resolve()) and p.is_file(), p
    raw=p.read_bytes(); assert raw[:8]==b'\x89PNG\r\n\x1a\n'; s['width'],s['height']=struct.unpack('>II',raw[16:24])
    assert (s.get('sourceUrls') or s['group']=='appearance') and s.get('locale') and s.get('theme'), 'Capture provenance, locale and theme required'
shell=(DOCS/'index.html').read_text()
header=shell[shell.index('<a class="skip-link"'):shell.index('<main')]
header=re.sub(r'<aside class="[^"]*release-hold[^"]*".*?</aside>','',header,flags=re.S).replace(' aria-current="page"','')
header=re.sub(r'Official 0\.\d+\.\d+',f'Published {official["version"]}',header)
footer=shell[shell.index('<footer'):]
footer=re.sub(r'MeBot 0\.[0-9.]+(?:-beta\.[0-9]+)?(?: preview)? ·',f'MeBot {v} preview ·' if pending else f'MeBot {v} ·',footer)
footer=re.sub(r'Native Android host captures.*?(?= · <a href=|</span>)','Native Android host renders with source-verified public examples. Not live accounts or physical-phone acceptance evidence.',footer)
footer=re.sub(r'<p class="image-note">.*?</p>','<p class="image-note">Native Android host render. Public source examples keep their dates; no live account or physical-phone capture is implied. <a id="image-original">Open original image</a></p>',footer,flags=re.S)
notice=(f'<aside class="wrap release-hold beta-note"><strong>MeBot {v} preview — not yet a public download.</strong> The published {official["version"]} APK does not include this corrected five-question and Original Read experience. <a href="download.html">Check release availability →</a></aside>' if pending else '')
provenance='Native Android host renders of the 0.9.8 debug preview build using a static editorial fixture built from source-verified public facts; no live feed refresh is shown. Sources were checked on 28 October 2026; a checked date is not a publication date. These are not live provider responses, personal accounts or physical-device acceptance evidence.'
identity='Install an eligible update over your existing production 0.9.x app. Do not uninstall or clear data. Older separately installed builds have different package or signing identities; see the installation guide before replacing them.'
experience='Open Suggestions when you want another angle. Explicit generation prepares five distinct questions with your connected AI. Send asks the selected question; the pencil lets you edit first. Opening or closing the sheet never sends a question. Without AI, questions are unavailable, while public reading and saved items remain usable.'
reads='Original Reads are clearly labelled AI-authored, with Created dates rather than publisher dates. Read the complete sections, inspect evidence, and explore optional further reading separately. Today keeps your personal selection distinct from verified source-day stories; an undated source is not relabelled as published today.'
def shot(s,hero=False):
    label=f'{s["version"]} debug host capture · {s["locale"]} · {s["theme"]}'
    links=' · '.join(f'<a href="{esc(u,quote=True)}">Source {i+1}</a>' for i,u in enumerate(s['sourceUrls']))
    return f'<figure class="shot" data-screen-group="{esc(s["group"])}"><a href="{s["file"]}" data-lightbox data-title="{esc(s["title"],quote=True)}" aria-label="Enlarge: {esc(s["title"],quote=True)}"><img src="{s["file"]}" width="{s["width"]}" height="{s["height"]}" alt="{esc(s["alt"],quote=True)}" {"fetchpriority=\"high\"" if hero else "loading=\"lazy\""} decoding="async"></a><figcaption><span class="shot-version">{esc(label)}</span><strong>{esc(s["title"])}</strong><span>{esc(s["caption"])}</span><span>{links}</span></figcaption></figure>'
def gallery(items): return '<div class="screen-grid three">'+''.join(shot(s) for s in items)+'</div>'
def selected(group,limit=3): return [s for s in screens if s['group']==group][:limit]
def download(label=None): return f'<a class="button" data-release-apk href="{apk}">{label or "Download published "+official["version"]+" APK ↓"}</a>'
def publication_copy(text):
    """Promote availability copy only; capture provenance and historical evidence stay dated."""
    if pending:
        return text
    substitutions = {
        f'{v} is a preview. Published {official["version"]} is an earlier experience and does not contain the corrections illustrated here.': f'MeBot {v} is available as the official Android release and through in-app update checks.',
        f'The preview is {v}. The published APK is {official["version"]}.': f'MeBot {v} is the current official Android release.',
        f'<aside class="beta-note"><strong>Wait for the next verified update for the experience shown here.</strong> The earlier {official["version"]} APK includes the older suggestion flow, not the corrected AI-only five-question flow or Original Reads. Its original verification report must be read with that limitation; it is not evidence for the preview.</aside>': '<aside class="beta-note"><strong>Your next reading experience is ready.</strong> Update for five AI-prepared question angles, Original Reads, clearer source dates and your preferred colours. Your phone still asks you to approve installation.</aside>',
        f'Published {official["version"]} · earlier experience': f'Official {official["version"]} · available now',
        f'<h2>Preview availability</h2><p>There is no public {v} APK or in-app update yet. Source review and release qualification are separate from installation.</p><a href="{REPO}/pull/19">Follow source review →</a>': f'<h2>Update in the app</h2><p>MeBot {v} is available through Software Updates for compatible production installations. Check for updates, review the download, then approve Android’s installation prompt. Your existing data stays in place.</p><a href="guides/install.html">Installation and update guide →</a>',
        '<strong>Not offered as a public APK yet.</strong> No historical asset is relabelled as the new preview.': f'<strong>Available in the official {v} release.</strong> Historical files retain their original identities.',
        '<h2>Not yet a public download</h2><p>The source is under review. This page does not offer a signed preview APK or claim physical-device acceptance. The earlier published APK has a different experience.</p><a href="'+REPO+'/pull/19">Source review →</a>': '<h2>Available in the official release</h2><p>Install the current release or use Software Updates in your compatible production app. Host screenshots remain illustrative, not physical-device acceptance evidence.</p>'+download(),
        'These screens show the preview, not the older published APK.': 'These are native debug-build captures of the 0.9.8 experience, not photographs of the signed release on a physical phone.',
        f'> **MeBot {v} preview — not yet a public download.** Published {official["version"]} is an earlier experience, without the corrected AI-only five-question flow or Original Reads shown here. [Version availability](docs/download.html) · [Source review]({REPO}/pull/19).': f'> **MeBot {v} is available now.** Update in the app or [download the official APK]({apk}). [Installation and verification](docs/download.html).',
        f'**Not yet a public download.** The [published {official["version"]} APK]({apk}) does not contain the full preview experience. [Version availability](docs/download.html).': f'**MeBot {v} is available now.** [Download the official APK]({apk}) or check Software Updates in your compatible production app. [Installation and verification](docs/download.html).',
        'This identifies the earlier APK, not the preview images.': 'This identifies the published APK; the gallery retains its separate debug-build capture provenance.',
        '## Explore the next experience': '## Explore MeBot',
        '## See the current preview': '## See MeBot',
        'The next MeBot reading experience': 'The MeBot reading experience',
        'Preview five deliberate question angles': 'Explore five deliberate question angles',
        'MeBot preview availability': 'MeBot release availability',
        'Separate the unreleased MeBot preview from the existing published APK and its exact release files.': 'Download the official MeBot Android release and verify its matching source and release files.',
        'MeBot preview screens': 'MeBot debug-build screens',
        'in the MeBot preview.': 'in MeBot.',
        'Explore the preview →': 'Explore the experience →',
        f'Explore the {v} preview →': f'Explore MeBot {v} →',
        f'What changed in the {v} preview': f'What changed in MeBot {v}',
        f'MeBot {v} preview · new native captures': f'MeBot {v} · native debug-build captures',
        f'MeBot {v} preview</p>': f'MeBot {v}</p>',
        f'{v} preview</p>': f'MeBot {v}</p>',
        f'{v} preview · step': f'{v} · step',
        f'# Start here — MeBot {v} preview': f'# Start here — MeBot {v}',
        f'Explore the MeBot {v} preview:': f'Explore MeBot {v}:',
    }
    for before, after in substitutions.items():
        text=text.replace(before,after)
    return text

def page(name,title,desc,body,current=None):
    title,desc,body=map(publication_copy,(title,desc,body))
    nav=header.replace(f'href="{current}"',f'href="{current}" aria-current="page"',1) if current else header
    og=screens[0]['file'] if screens else 'assets/mebot.svg'
    head=f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{esc(title)} · MeBot</title><meta name="description" content="{esc(desc,quote=True)}"><meta name="theme-color" content="#f7f8fa"><meta name="color-scheme" content="light dark"><meta name="referrer" content="strict-origin-when-cross-origin"><link rel="canonical" href="{SITE}{name}"><link rel="icon" href="assets/mebot.svg" type="image/svg+xml"><meta property="og:title" content="{esc(title,quote=True)}"><meta property="og:image" content="{SITE}{og}"><meta property="og:image:alt" content="{esc(screens[0]["alt"] if screens else "MeBot robot mark",quote=True)}"><link rel="stylesheet" href="site.css"><link rel="stylesheet" href="showcase.css"><script src="site.js" defer></script><script src="showcase.js" defer></script></head><body data-release-manifest="assets/release.json">'
    (DOCS/name).write_text(head+nav+notice+'<main id="main">'+body+'</main>'+footer)
hero=''.join(shot(s,True) for s in screens[:2])
page('index.html','MeBot — reading with a next step','Explore the MeBot 0.9.8 preview: sourced reading, thoughtful questions, English and Kiswahili, and your colours.',f'''
<section class="hero wrap"><div class="hero-copy"><p class="eyebrow">Android · English &amp; Kiswahili · {v} {'preview' if pending else ''}</p><h1>Read with purpose.<br>Choose your next step.</h1><p class="hero-lead">News, opportunities and useful ideas, with their sources close at hand. Read in your language. Ask when you want to go further.</p><div class="actions"><a class="button" href="{preview}">Explore the {v} preview →</a><a class="button secondary" href="download.html">Published downloads</a></div><p class="micro">{('The preview shown here is not an installable update.' if pending else 'The current release is available below.')}</p></div><div class="hero-phones">{hero}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Sources, not invented freshness</p><h2>Useful in Tanzania. Open to the world.</h2><p>Explore verified opportunities, local reporting and technology or AI news. Publisher dates and deadlines keep their meaning; check the original requirements before applying.</p></div><a class="text-link" href="screenshots.html">See the complete gallery →</a></div>{gallery(selected('read'))}</section>
<section class="section wrap hairline" id="story-questions"><h2>Five angles. Your decision.</h2><p class="lead">{experience}</p>{gallery(selected('ask'))}<a class="text-link" href="guides/feed-and-ai.html">Reading and suggestions guide →</a></section>
<section class="section wrap hairline"><h2>Room to read beyond a headline.</h2><p>{reads}</p><p class="micro">The images below illustrate Today with public source items. Original Read is described here without presenting a synthetic article as editorial proof.</p>{gallery(selected('reads'))}</section>
<section class="section wrap hairline"><h2>Make it comfortable to read.</h2><p>Choose eight accent colours or your own, with Original, Warm, Soft colour and Deep black page styles. Follow your phone’s theme or choose light or dark. Deep black applies in dark mode.</p>{gallery(selected('appearance'))}<a class="text-link" href="guides/appearance.html">Appearance controls →</a></section>
<section class="next-step wrap"><div><h2>Know which version you are getting.</h2><p>{v} is a preview. Published {official['version']} is an earlier experience and does not contain the corrections illustrated here.</p></div><div class="actions">{download()}<a class="button secondary" href="download.html">Read the version notice →</a></div></section><p class="image-provenance wrap">{provenance}</p>''','index.html')
groups=list(dict.fromkeys(s['group'] for s in screens)); group_titles={'read':'News & opportunities','ask':'Questions','reads':'Today','appearance':'Colours & themes','start':'Getting started','organize':'Your preferences','tools':'Everyday tools'}
filters=''.join(f'<button type="button" data-screen-filter="{g}" aria-pressed="{str(g=="all").lower()}">{"All screens" if g=="all" else group_titles.get(g,g.title())}</button>' for g in ['all',*groups])
page('screenshots.html','Inside MeBot','Fresh source-verified MeBot preview screens in English and Kiswahili, with actual colour and page-style choices.',f'<section class="section wrap"><p class="eyebrow">MeBot {v} preview · new native captures</p><h1>Inside MeBot</h1><p class="lead">Real public topics, clear sources and room for your own reading style. These screens show the preview, not the older published APK.</p><div class="screen-filters js-only" role="group" aria-label="Filter app screenshots">{filters}</div><p id="screen-count" role="status" class="micro">{len(screens)} screens</p><div class="screen-grid" id="screen-gallery">{"".join(shot(s) for s in screens)}</div></section><section class="section wrap hairline" id="image-notes"><h2>What these images show</h2><p>{provenance}</p><p>English and Kiswahili examples use the same source facts. An AI-authored Original Read is not a publisher article; its evidence and Created date are labelled separately. Screen colour comes from the app’s real appearance settings, not image recolouring.</p><a href="screenshots/v098-showcase/README.md">Capture details and source record →</a></section>','screenshots.html')
resources=[('Matching source','sourceFilename'),('Technical report · prepublication snapshot','verificationFilename'),('Release notes','releaseNotesFilename'),('SHA-256 checksums','checksumFilename'),('Release metadata','releaseMetadataFilename')]
report_note='The original report records prepublication qualification. Its pending-publication wording is historical; the published identity record establishes the completed release and download checks.'
resource_html=''.join(f'<div class="download-row"><div><strong>{title}</strong><p>{report_note if key=="verificationFilename" else "Published "+official["version"]+" release file."}</p></div><a href="{base}{official[key]}">Download <span class="sr-only">{title}</span> ↓</a></div>' for title,key in resources)
page('download.html','Downloads and version availability','Separate the unreleased MeBot preview from the existing published APK and its exact release files.',f'''<div class="release-page"><p class="eyebrow">Published {official['publishedDate']}</p><h1>Downloads &amp; availability</h1><p class="lead">The preview is {v}. The published APK is {official['version']}.</p><aside class="beta-note"><strong>Wait for the next verified update for the experience shown here.</strong> The earlier {official['version']} APK includes the older suggestion flow, not the corrected AI-only five-question flow or Original Reads. Its original verification report must be read with that limitation; it is not evidence for the preview.</aside><section class="release-card"><span data-release-status>Published {official['version']} · earlier experience</span><h2>MeBot <span data-release-version>{official['version']}</span></h2><div class="actions">{download()}<a class="button secondary" href="{release_url}">Published release notes ↗</a></div><p>{identity}</p><p>Android 8.0+ · <span data-release-size>{size}</span>. JavaScript is not required.</p><details class="release-metadata"><summary>File identity and verification</summary><p>Package <code>{official['applicationId']}</code> · version code {official['versionCode']}.</p><p><code data-release-filename>{official['filename']}</code></p><p>Published source <code>{official['sourceCommit']}</code> · tree <code>{official['sourceTree']}</code>.</p><p>Signer SHA-256 <code>{official['certificateSha256']}</code>.</p><div class="code-block"><div class="code-toolbar"><span>APK SHA-256</span><button type="button" class="copy-button js-only" data-copy-target="apk-checksum">Copy checksum</button></div><pre id="apk-checksum" data-release-sha tabindex="0">{official['sha256']}</pre></div></details></section><section class="article-section" id="resources"><h2>Published release resources</h2><div class="download-list">{resource_html}</div><p><a href="{official["integrityFile"]}">Published identity and verification receipt →</a></p></section><section class="article-section"><h2>Preview availability</h2><p>There is no public {v} APK or in-app update yet. Source review and release qualification are separate from installation.</p><a href="{REPO}/pull/19">Follow source review →</a></section><section class="article-section" id="limits"><h2>Verification limits</h2><p>Host tests and screenshots do not establish live provider access, physical-phone playback, biometric behavior, OEM file-picker support or overnight alarms. Keep your existing data and test device-specific features before relying on them.</p></section></div>''','download.html')
rows=[]
for item in r.get('releases',[]):
    label=('Current official release' if not pending else 'Published earlier experience') if item['version']==official['version'] else ('Historical published release' if item.get('status')=='published' else 'Historical record · not a public APK')
    link=f'<a href="{REPO}/releases/tag/{item["tag"]}">Release files ↗</a>' if item.get('status')=='published' else ''
    warning = ' Not recommended: known launch failures when opening file selection or notifications. Superseded by later releases.' if item['version'] in ('0.9.0','0.9.1','0.9.2') else ''
    rows.append(f'<div class="download-row"><div><strong>MeBot {item["version"]}</strong><p>{label}{warning}</p></div>{link}</div>')
page('releases.html','Release directory','MeBot preview availability and dated published release records.',f'<section class="section wrap"><h1>Release directory</h1><h2>What changed in the {v} preview</h2><p>{experience}</p><p>{reads}</p><p><strong>Not offered as a public APK yet.</strong> No historical asset is relabelled as the new preview.</p><a class="text-link" href="{preview}">Explore the preview →</a><div class="actions">{download()}</div><h2>Dated release records</h2><p>Older files preserve their original identity and verification limits. Their screenshots are not used in the current showcase.</p><div class="download-list">{"".join(rows)}</div></section>','releases.html')
steps=[('Choose interests','Pick your topics and optionally a city. A context file is not required.','guides/install.html',None),('Bring only useful context','Review imported JSON before replacing notes. After confirmation, your saved notes appear in your context details. Cancel leaves the draft in place.','guides/personal-context.html#after-import',None),('Read with the source nearby','Keep publisher dates, deadlines and original links in view. Saved stories stay available without AI.','guides/feed-and-ai.html', 'read'),('Ask when you choose',experience,'guides/feed-and-ai.html#follow-up','ask'),('Explore Original Reads',reads,'guides/feed-and-ai.html','reads'),('Choose your colours','Change accent and page style in Appearance. Deep black requires dark mode; your choice is saved.','guides/appearance.html','appearance'),('Keep your data and tools','Background timing remains under Android control. Review update identity before installation. Alarm, Focus and Daily Check-in work independently of AI; test alarms on your own phone.','guides/background-and-data.html',None)]
controls=''.join(f'<button type="button" data-tour-step="{i}" aria-pressed="{str(i==0).lower()}" aria-controls="tour-panel-{i}"><span aria-hidden="true">{i+1:02d}</span>{title}</button>' for i,(title,*_) in enumerate(steps))
panels=''.join(f'<section class="tour-panel" id="tour-panel-{i}" aria-labelledby="tour-heading-{i}"><div class="tour-copy"><p class="eyebrow">{v} preview · step {i+1}</p><h2 id="tour-heading-{i}">{title}</h2><p class="lead">{body}</p><a class="text-link" href="{guide}">Detailed guide →</a></div>{shot(selected(group,1)[0]) if group and selected(group,1) else ""}</section>' for i,(title,body,guide,group) in enumerate(steps))
page('tour.html','Start with MeBot','A practical guide to sourced reading, optional AI and personal settings in the MeBot preview.',f'<section class="section wrap"><p class="eyebrow">{v} preview</p><h1>A short walk through MeBot</h1><p class="lead">Read first. Add context and AI only where they help.</p><div class="tour-layout"><nav class="tour-steps js-only" aria-label="Guide steps">{controls}</nav><div class="tour-panels">{panels}</div></div><div class="tour-navigation js-only"><button class="button secondary" type="button" data-tour-prev>← Back</button><span id="tour-status" role="status" aria-live="polite">Step 1 of {len(steps)}</span><button class="button" type="button" data-tour-next>Next →</button></div></section>','tour.html')
page(preview,'The next MeBot reading experience','Preview five deliberate question angles, complete Original Reads and a personal Today selection.',f'<section class="section wrap"><p class="eyebrow">MeBot {v} preview</p><h1>More to read. A clearer next step.</h1><p class="lead">{experience}</p>{gallery(selected("ask"))}<h2>Original Reads and a useful Today</h2><p>{reads}</p><p>The screenshots below show Today, not a completed Original Read. Long-form Original Read images are omitted until suitable content is reviewed.</p>{gallery(selected("reads"))}<section class="release-card"><h2>Not yet a public download</h2><p>The source is under review. This page does not offer a signed preview APK or claim physical-device acceptance. The earlier published APK has a different experience.</p><a href="{REPO}/pull/19">Source review →</a></section></section>')
old_readme=(ROOT/'README.md').read_text(); tail=old_readme[old_readme.index('## Choose your AI connection'):]
tail=re.sub(r'https://github.com/EmmanuelMmanda/MeBot/releases/download/v0\.9\.\d+/[^)\s]+',lambda m: m.group(0),tail)
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
    ss=screens[i:i+3]; image_rows.append('<p align="center">'+'\n'.join(f'<a href="docs/{s["file"]}"><img src="docs/{s["file"]}" width="225" alt="{esc(s["alt"],quote=True)}"></a>' for s in ss)+'</p>\n\n'+' · '.join(f'**{s["title"]}** — {s["locale"]}, {s["theme"]}' for s in ss))
(ROOT/'README.md').write_text(publication_copy(f'''<div align="center"><img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo"><h1>MeBot</h1><p>Read with purpose. Choose your next step.</p><p>Android · English &amp; Kiswahili · Apache 2.0</p></div>

> **MeBot {v} preview — not yet a public download.** Published {official['version']} is an earlier experience, without the corrected AI-only five-question flow or Original Reads shown here. [Version availability](docs/download.html) · [Source review]({REPO}/pull/19).

## Explore the next experience

{experience}

{reads}

Choose eight accent colours or your own, with Original, Warm, Soft colour and Deep black page styles. Your phone’s theme, light and dark are supported; Deep black applies in dark mode.

## See the current preview

{chr(10).join(image_rows)}

{provenance} [Capture sources](docs/screenshots/v098-showcase/README.md). Historical screenshots remain evidence only, outside this current gallery.

## Installation and release identity

[Published {official['version']} APK]({apk}) · [Published release files]({release_url}) · [Start here](START-HERE.md) · [User guide](docs/USER-GUIDE.md).

{identity} Package: `mebot.nguli.com`. The published APK source is `{official['sourceCommit']}`, tree `{official['sourceTree']}`. This identifies the earlier APK, not the preview images. Technical evidence is separate from experience-first release notes.

{tail}'''))
(ROOT/'START-HERE.md').write_text(publication_copy(f'# Start here — MeBot {v} preview\n\n**Not yet a public download.** The [published {official["version"]} APK]({apk}) does not contain the full preview experience. [Version availability](docs/download.html).\n\n'+'\n\n'.join(f'## {i+1}. {title}\n\n{body}\n\n[Detailed guide](docs/{guide}).' for i,(title,body,guide,_) in enumerate(steps))+f'\n\n{identity}\n\n[Fresh bilingual gallery](docs/screenshots.html) · [User guide](docs/USER-GUIDE.md).\n'))
print(f'Rendered current {v} presentation with {len(screens)} fresh captures; historical pages and release records untouched.')
