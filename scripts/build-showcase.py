#!/usr/bin/env python3
"""Render MeBot's static product surface from reviewed release data and captures.
No external requests; download links and image originals work without JavaScript.
"""
from pathlib import Path
from html import escape as esc
import json,re,struct
ROOT=Path(__file__).resolve().parents[1];DOCS=ROOT/'docs'
REPO='https://github.com/EmmanuelMmanda/MeBot';SITE='https://emmanuelmmanda.github.io/MeBot/'
r=json.loads((DOCS/'assets/release.json').read_text());v=r['version'];tag=r['tag']
assert r['status'] in ('published','pending') and tag=='v'+v and re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?',v)
published=r['status']=='published'
if published:
 assert r['filename'] and r['sha256'] and r['sourceCommit'] and r['sizeBytes']
base=f'{REPO}/releases/download/{tag}/';apk=base+(r['filename'] or r.get('plannedApkFilename',''));notes=f'{REPO}/releases/tag/{tag}'
source=base+r['sourceFilename'];size=f"{r['sizeBytes']/1000000:.1f} MB" if r.get('sizeBytes') else "verification pending"
screens=json.loads((DOCS/'assets/screens.json').read_text())
for s in screens:
 p=(DOCS/s['file']).resolve();assert p.is_relative_to(DOCS.resolve()) and p.is_file()
 raw=p.read_bytes();assert raw[:8]==b'\x89PNG\r\n\x1a\n';s['width'],s['height']=struct.unpack('>II',raw[16:24])
by={Path(s['file']).stem:s for s in screens}
shell=(DOCS/'index.html').read_text();footer=shell[shell.index('<footer'):]
footer=re.sub(r'MeBot 0\.[0-9.]+(?:-beta\.[0-9]+)? ·',f'MeBot {v} ·',footer)
footer=footer.replace('Native Android host render. Example content is not a live account result.','Native Android host capture with dated public source content and controlled request state.').replace('Native screens with example content','Dated public content · native captures')
shell=re.sub(r'<aside class="wrap release-hold">.*?</aside>', '', shell, flags=re.S)
shell=re.sub(r'<!-- preview-[0-9]+:start -->.*?<!-- preview-[0-9]+:end -->', '', shell, flags=re.S)
header=shell[shell.index('<a class="skip-link"'):shell.index('<main')].replace(' aria-current="page"','')

def page(name,title,desc,body,current=None):
 nav=header
 if current:nav=nav.replace(f'href="{current}"',f'href="{current}" aria-current="page"',1)
 head=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>{esc(title)} · MeBot</title><meta name="description" content="{esc(desc,quote=True)}"><meta name="theme-color" content="#f7f8fa"><meta name="color-scheme" content="light dark"><meta name="referrer" content="strict-origin-when-cross-origin"><link rel="canonical" href="{SITE}{name}"><link rel="icon" href="assets/mebot.svg" type="image/svg+xml"><meta property="og:type" content="website"><meta property="og:title" content="{esc(title,quote=True)} · MeBot"><meta property="og:description" content="{esc(desc,quote=True)}"><meta property="og:url" content="{SITE}{name}"><meta property="og:image" content="{SITE}screenshots/v082/feed.png"><meta property="og:image:alt" content="MeBot feed with dated public reporting"><link rel="stylesheet" href="site.css"><link rel="stylesheet" href="showcase.css"><script src="site.js" defer></script><script src="showcase.js" defer></script></head><body data-release-manifest="assets/release.json">'''
 (DOCS/name).write_text(head+nav+'<main id="main">'+body+'</main>'+footer)

def shot(key,hero=False):
 s=by[key] if isinstance(key,str) else key;load='fetchpriority="high"' if hero else 'loading="lazy"'
 return f'''<figure class="shot" data-screen-group="{s['group']}"><a href="{s['file']}" data-lightbox data-title="{esc(s['title'],quote=True)}" aria-label="Enlarge: {esc(s['title'],quote=True)}"><img src="{s['file']}" width="{s['width']}" height="{s['height']}" alt="{esc(s['alt'],quote=True)}" {load} decoding="async"></a><figcaption><span class="shot-version">{esc(s['version'])} · {esc(s['captureDate'])}</span><strong>{esc(s['title'])}</strong><span>{esc(s['caption'])}</span></figcaption></figure>'''

def download(label='Download MeBot APK ↓'):
 return f'<a class="button" data-release-apk href="{apk}">{label}</a>' if published else '<span class="button secondary" aria-disabled="true">0.9.3 verification pending</span>'
identity='<p class="beta-note"><strong>Updating from 0.9.0, 0.9.1 or 0.9.2?</strong> Install this APK over your existing app. Do not uninstall or clear data. Older 0.8.x builds used a different signing identity; Android will not accept this as an in-place update to those builds.</p>'
provenance='<p class="image-provenance wrap">Actual Android UI, dated public content, controlled provider states. These are not screenshots of a live personal account or proof that an application window is open. <a href="screenshots.html#image-notes">Sources and image credits →</a></p>'
page('index.html','MeBot Daily for Android','Explore your interests, read sourced information, ask MeBot and keep useful resources. Download the official Android release.',f'''
<section class="hero wrap"><div class="hero-copy"><p class="eyebrow">Android · Open source <span class="version">{v}</span></p><h1>MeBot Daily</h1><p class="hero-lead">Explore what matters to you. Read local and global updates, ask a closer question, and keep useful sources.</p><div class="actions">{download()}<a class="button secondary" href="tour.html">Take a look inside →</a></div><p class="micro">Android 8.0+ · {size} · English &amp; Kiswahili</p>{identity}<div class="connection-intro"><h2>Your choice of AI connection</h2><p>Connect an eligible ChatGPT account, use a supported provider key, or connect a personal inference server. Public information and local tools remain available without AI.</p><a class="text-link" href="guides/release-082.html#connections">Providers and capabilities →</a></div></div><div class="hero-phones">{shot('news',True)}{shot('detail-answer')}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Make it yours</p><h2>Start with your interests.</h2><p>Choose topics. Add a city when it helps. No JSON file is needed to begin.</p></div><a class="text-link" href="tour.html">A short guided start →</a></div><div class="screen-grid three">{''.join(shot(k) for k in ['onboarding-en-chooser','onboarding-sw-chooser','context-import-sw'])}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Read, ask and refine</p><h2>Keep the useful information in view.</h2><p>Answers stream beneath a stable introduction. Sources, media and files have their own place.</p></div></div><div class="feature-pair">{shot('mebot')}<div class="feature-copy"><h3>More useful with your feedback</h3><div class="feature-line"><strong>Relevant / Not relevant</strong><p>Refine the topics within a module. Change your choice, undo it or reset feedback separately.</p></div><div class="feature-line"><strong>New results as they arrive</strong><p>Completed modules update the feed while others continue. New information does not move the item you are reading.</p></div><div class="feature-line"><strong>One activity area</strong><p>Expand the top panel for actual request events. The detail panel follows the same width as its anchor.</p></div><a class="text-link" href="screenshots.html">Explore all {len(screens)} screens →</a></div></div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">You stay in control</p><h2>Clear settings. Useful actions.</h2><p>Choose a connection, keep usage in view and check for verified software updates.</p></div></div><div class="screen-grid three">{''.join(shot(k) for k in ['connection-sw','starred-sw','personalization-sw'])}</div></section>
<section class="next-step wrap"><div><h2>Get MeBot {v}</h2><p>The Android APK, source, screenshots and verification files are published together.</p></div><div class="actions">{download()}<a class="button secondary" href="releases.html">Release files →</a></div></section>{provenance}''','index.html')
filters=''.join(f'<button type="button" data-screen-filter="{k}" aria-pressed="{str(k=="all").lower()}">{t}</button>' for k,t in [('all','All screens'),('start','Getting started'),('read','Feed & MeBot'),('organize','Your preferences'),('tools','Everyday tools')])
page('screenshots.html','Inside MeBot','Current Android screens, full-size previews, captions and public-source credits.',f'''
<section class="section wrap"><p class="eyebrow">Current app gallery · {v}</p><h1>Inside MeBot</h1><p class="lead">The actual interface, with concrete reporting and resources. Select an image for its original size.</p><div class="screen-filters js-only" role="group" aria-label="Filter app screenshots">{filters}</div><p id="screen-count" role="status" class="micro">{len(screens)} screens</p><div class="screen-grid" id="screen-gallery">{''.join(shot(s) for s in screens)}</div></section>
<section class="section wrap hairline" id="image-notes"><h2>About these captures</h2><p>Each capture is labelled with its actual app version and date. New 0.9.2 settings and import screens use synthetic fixtures. Older reading screens retain their dated public reporting; they are not current news or weather.</p><p>The screenshot host uses controlled request and account state for repeatable testing. It is not a live account recording or a physical-phone test. Software Updates may show a debug suffix in the host capture; the downloadable APK is the signed official release. Photography has separate credits and capture dates.</p><div class="actions"><a class="button secondary" href="screenshots/v092/README.md">0.9.2 capture notes →</a><a class="text-link" href="{REPO}/blob/main/docs/screenshots/v082/README.md">Capture provenance →</a><a class="text-link" href="{REPO}/blob/main/docs/IMAGE-CREDITS.md">Image credits →</a></div></section>''','screenshots.html')
resources=[('Matching source','Exact tested application source snapshot.',r['sourceFilename']),('Verification report','Executed checks and explicit device-testing limits.',r['verificationFilename']),('SHA-256 checksums','Digests for published assets.','SHA256SUMS.txt')]
resource_html=''.join(f'<div class="download-row"><div><strong>{t}</strong><p>{desc}</p></div><a href="{base}{f}">Download <span class="sr-only">{t}</span> ↓</a></div>' for t,desc,f in resources) if published else '<p>Source, signed APK and verification files will be linked after release verification.</p>'
page('download.html','Download MeBot','Official MeBot APK, source, verification and installation instructions.',f'''
<div class="release-page"><p class="eyebrow">Official Android release</p><h1>Download MeBot</h1><p class="lead">MeBot {v} · Android 8.0+ · {size}.</p><section class="release-card"><span class="release-state" data-release-status>Version {v} · Official release</span><h2>MeBot <span data-release-version>{v}</span></h2><div class="actions">{download()}<a class="button secondary" href="{notes}">Release notes ↗</a></div>{identity}<p class="micro">Direct APK download. JavaScript is not required.</p><details class="release-metadata"><summary>File identity and verification</summary><p>Package: <code>mebot.nguli.com</code> · Version code {r['versionCode']}.</p><p><code data-release-filename>{r['filename']}</code> · <span data-release-size>{size}</span></p><div class="code-block"><div class="code-toolbar"><span>SHA-256</span><button type="button" class="copy-button js-only" data-copy-target="apk-checksum">Copy checksum</button></div><pre id="apk-checksum" data-release-sha tabindex="0">{r['sha256']}</pre></div></details></section>
<section class="article-section" id="install"><h2>Install or update</h2><ol><li>Open the downloaded APK on Android. Allow your browser or file manager to install when Android asks.</li><li>If updating from 0.9.0, 0.9.1 or 0.9.2, keep the app installed and allow Android to update it. Your existing app data stays in place.</li><li>On a first installation, choose your language and interests, then explore your feed.</li><li>Connect an AI provider when ready. Public-source information and daily tools do not require an AI account.</li><li>For future releases, open <strong>You → Software Updates</strong>. Automatic checks are available; Android asks before installation.</li></ol><p>For 0.8.x installations with the earlier signer, back up supported personal preferences before changing installations. Protected credentials do not transfer in portable exports. Reconnect accounts and test configured alarms. Do not erase your current app just to try an update.</p></section>
<section class="article-section" id="resources"><h2>Release resources</h2><div class="download-list">{resource_html}</div></section><section class="article-section" id="limits"><h2>What has and has not been tested</h2><p>See the verification report for exact test counts, lint findings and signing checks. Automated provider responses and Android host tests are not substitutes for a real account login, physical-phone media, save-picker or overnight alarm testing.</p><p>Software Updates verifies the trusted release, downloaded bytes, package, version and certificate. It does not bypass Android installation approval. API keys have separate provider billing; subscription access remains subject to eligibility and limits.</p></section><section class="article-section" id="context"><h2>Optional preferences import</h2><p>Onboarding interests are enough to start. Later, use <strong>Copy starter prompt</strong> with your preferred assistant, then import or paste its JSON and review the changes.</p><a class="text-link" href="guides/release-082.html#personalization">Personalization guide →</a></section></div>''','download.html')
history=''.join(f'<tr><th scope="row">{esc(x["version"])}</th><td>{("Historical signed · superseded" if x.get("apk",True) else "Source only · superseded") if x["status"]=="published" else ("Historical · known defect" if x.get("recommendation")=="superseded" else "Historical · publication pending")}</td><td>{("<a href="+REPO+"/releases/tag/"+x["tag"]+">Release files ↗</a>") if x["status"]=="published" else "Archive publication pending"}</td></tr>' for x in r['releases'] if x['version']!=v)
page('releases.html','Releases and files','Versioned Android releases, source snapshots and verification evidence.',f'''<div class="release-page"><p class="eyebrow">Release directory</p><h1>Releases &amp; files</h1><section class="release-card"><span class="release-state">Official · {r['publishedDate']}</span><h2>MeBot {v}</h2><p>Recommended Android release · <code>mebot.nguli.com</code>.</p><div class="actions">{download()}<a class="button secondary" href="{notes}">Full release notes ↗</a></div></section><section class="article-section"><h2>Files for this release</h2><div class="download-list">{resource_html}</div></section><section class="article-section"><h2>What changed</h2><p>Choose files to import your context, export your data, and turn on notifications without the app closing. A clearer notification label distinguishes alerts from background refresh. Your familiar layout, notes, saved content and app-lock settings stay in place.</p><p><a href="guides/release-082.html">Illustrated usage guide →</a></p></section><section class="article-section"><h2>Earlier releases</h2><p>0.9.3 is the recommended corrective release. Versions 0.9.0–0.9.2 can close during file selection or notification setup; use 0.9.3 instead. Versions 0.8.3–0.8.5 are historical source checkpoints without production APKs.</p><div class="table-wrap"><table><thead><tr><th scope="col">Version</th><th scope="col">Status</th><th scope="col">Files</th></tr></thead><tbody>{history}</tbody></table></div></section></div>''','releases.html')
steps=[('Choose your interests','Pick topics, not technical settings.','onboarding-en-chooser','First launch','Choose topics and an optional city. Skip anything you do not need. Onboarding preferences already personalize the feed; a JSON import is not required.','personalization'),('Read and refine','Keep the introduction in view.','detail-answer','Feed → open an item','Read the source-backed introduction while MeBot prepares more detail. Relevant or Not relevant adjusts future recommendations within that module; Undo reverses the signal.','feedback'),('Connect your provider','Choose the account or server you use.','connection-sw','You → AI connection','ChatGPT and OpenRouter have different authorization flows. Claude and Gemini use API keys. A compatible personal server is explicit. The screen shows the actual capabilities and local request allowance.','connections'),('Set your pace','Decide what happens in the background.','background','My MeBot → background settings','Choose intervals, module preparation, network conditions and quiet hours. Usage limits are managed in AI connection. Completed modules publish without waiting for the rest.','background'),('Keep MeBot current','Verify, then install.','software-updates','You → Software Updates','Automatic checks find eligible releases for this package. Download verification checks integrity and signing. Android asks you to approve installation; your current app is not silently replaced.','updates'),('Use the tools you need','Alarm, Focus and Check-in.','daily-tools','Modules → daily tools','Configure alarms, start a focus session or make a short check-in. These remain optional and work independently of the AI connection. Test alarm sound on your device.','tools')]
controls=''.join(f'<button type="button" data-tour-step="{i}" aria-pressed="{str(i==0).lower()}" aria-controls="tour-panel-{i}"><span aria-hidden="true">{i+1:02d}</span>{t}</button>' for i,(t,*_) in enumerate(steps))
panels=''.join(f'<section class="tour-panel" id="tour-panel-{i}" aria-labelledby="tour-heading-{i}"><div class="tour-copy"><p class="eyebrow">{esc(where)}</p><h2 id="tour-heading-{i}">{esc(t)}</h2><p class="lead">{esc(lead)}</p><p>{esc(body)}</p><a class="text-link" href="guides/release-082.html#{anchor}">Detailed guide →</a></div>{shot(key)}</section>' for i,(t,lead,key,where,body,anchor) in enumerate(steps))
page('tour.html','Start here','A six-step visual guide to MeBot, from interests to verified software updates.',f'<section class="section wrap"><p class="eyebrow">MeBot {v} · Start here</p><h1>A short walk through MeBot</h1><p class="lead">The important actions, with real app screens. Move at your own pace.</p><div class="tour-layout"><nav class="tour-steps js-only" aria-label="Guide steps">{controls}</nav><div class="tour-panels">{panels}</div></div><div class="tour-navigation js-only"><button class="button secondary" type="button" data-tour-prev>← Back</button><span id="tour-status" role="status" aria-live="polite">Step 1 of 6</span><button class="button" type="button" data-tour-next>Next →</button></div></section><section class="next-step wrap"><div><h2>Explore MeBot</h2><p>The in-app quick guide is skippable and replayable from Help.</p></div><div class="actions">{download()}<a class="button secondary" href="guide.html">All guides →</a></div></section>','tour.html')
image_rows=[]
for i in range(0,len(screens),3):
 group=screens[i:i+3]
 image_rows.append('<p align="center">\n'+'\n'.join(f'<a href="docs/{s["file"]}"><img src="docs/{s["file"]}" width="225" alt="{esc(s["alt"],quote=True)}"></a>' for s in group)+'\n</p>\n\n'+' · '.join(f'**{s["title"]}**' for s in group)+'\n')
README=f'''<div align="center">
<img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo">
<h1>MeBot Daily</h1><p>Your topics, source-linked answers and optional everyday tools.</p>
<p><a href="{apk}"><strong>Download APK</strong></a> · <a href="{REPO}/releases/tag/{tag}">Release files</a> · <a href="START-HERE.md">Start here</a> · <a href="docs/USER-GUIDE.md">User guide</a></p>
<p>Android 8.0+ · English &amp; Kiswahili · Kotlin + Jetpack Compose · Apache 2.0</p></div>

> **Official MeBot {v} · {size} · `mebot.nguli.com`.** Install over 0.9.0, 0.9.1 or 0.9.2 without uninstalling or clearing data. Earlier 0.8.x binaries used a different signer and cannot receive this as an in-place update. Review the [installation guidance](docs/INSTALL.md).

Choose interests during onboarding, read a sourced feed, search, ask follow-ups and keep useful resources. Public information and local tools stay available without AI. No personal starter profile is baked into the app.

## What’s new in 0.9.3

- Choose files for context import and export your data without the app closing.
- Turn on notifications without the app closing, with a clearer label for update alerts.
- Keep your familiar layout, notes, saved content and app-lock settings.

[Full release notes](docs/RELEASE-0.9.3.md).

## See the current app

{''.join(image_rows)}
Captured 25 October 2026 from the actual Android UI. Reporting and photographs have recorded public sources; provider/update states are controlled for testing. These are **not live account sessions or physical-device photographs**. [Sources and capture notes](docs/screenshots/v082/README.md) · [Image credits](docs/IMAGE-CREDITS.md).

## Choose your AI connection

| Connection | What it means |
| --- | --- |
| ChatGPT | Existing provider-authorized sign-in for eligible accounts; account/model policy applies. No access to ChatGPT memory or chats is implied. |
| OpenAI API | Explicit API key with separate API billing. |
| Claude | Anthropic API key. Claude.ai subscription credentials are not used by third-party MeBot. |
| Gemini | Gemini API key. This adapter uses the public source material supplied by MeBot; it does not claim native Google Search grounding. |
| OpenRouter | PKCE authorization with a one-time code return or API key; uses OpenRouter credits, not an imported Claude subscription. |
| Personal server | Explicit OpenAI-compatible endpoint and model. Features depend on the endpoint; a saved key is never sent over HTTP. |
| Personal service | Optional self-hosted Python service. The service currently uses its own OpenAI API adapter. |

Provider selection is explicit. No automatic switching to a separately billed provider. Model lists, completed-response checks, streaming support and source discovery follow each adapter’s capabilities. Provider credentials stay outside portable exports.

**Copy starter prompt** prepares the exact preferences JSON schema for any suitable assistant. Review imports before applying. Onboarding interests already count as personalization.

## What is included

| Area | Behavior |
| --- | --- |
| Feed | Completed modules publish immediately; new results wait behind Show new items while you read. Cached content survives partial failures. |
| Feedback | Relevant / Not relevant improves recommendations within the module, with undo, decay and an independent reset. Not social likes. |
| Item detail | Stable introduction, unclipped square answer surface, streaming explanations and grouped sources/media/files. |
| Activity | One fixed top panel with an anchor-width popover; real events and provider-published summaries, never invented thoughts. |
| Connection | Provider and model choice, one persisted request budget, presets/custom/unlimited and clear capability limits. |
| Background | Timing, preparation batches, Wi-Fi/battery conditions, notifications and quiet hours in actionable cards. |
| Software Updates | Automatic checks and optional Wi-Fi preparation, verified package/version/certificate/hash, and Android’s installer approval. |
| Guidance | One relevant dismissible setup notice, a short first-use tour and replay from Help. |
| Daily tools | Alarm, Focus and Daily Check-in; independent of AI. |
| Data | Clear reading history explains its scope before confirmation; saved items, conversations and relevance feedback are separate. |

## Install and use

[Download the official APK]({apk}). Updating from 0.9.0, 0.9.1 or 0.9.2 preserves your existing installation; do not uninstall. Choose interests, connect a provider when ready, then open Feed. Test any configured alarm on your phone before overnight reliance. [Start here](START-HERE.md).

## Verification and release files

[Verification report]({base}{r['verificationFilename']}) · [Source]({source}) · [Native gallery](docs/screenshots/v092/README.md) · [Checksums]({base}SHA256SUMS.txt).

Tests use controlled providers and native Android host journeys. Real account eligibility, physical playback, file-picker behavior and overnight alarms need device checks. Historical releases are preserved. No private profile or signing key is published.

## Build

JDK **17**, Gradle **8.13**, Android SDK **36**, Build Tools **35.0.0**.

```sh
git clone {REPO}.git
cd MeBot
git switch develop
python3 scripts/prepare-build.py
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
```

The preparation script retrieves pinned typography and generates original alarm tones. Release signing uses private `MEBOT_*` environment values; debug builds need no key. Use `mebot.buildJdk=21` only for a local JDK21 environment; official CI uses17. Source archives intentionally omit font binaries and build outputs.

```sh
cd service
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
```

## Project structure

`core/` contains domain models, validation, feedback and recommendation policy. `app/` contains Compose UI, Android lifecycle, providers, local tools, secure storage and software updates. `service/` is an optional backend. `docs/` includes illustrated guides, capture provenance and the static website. `.github/` contains verification and publication workflows.

Work on develop, verify, then promote to main. iOS/multiplatform implementation is **not included** in this release.

[Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Privacy](docs/PRIVACY.md) · [Architecture](docs/ARCHITECTURE.md) · [Build guide](docs/BUILD.md) · [Current repair notes](docs/RELEASE-0.9.3.md).

## License

[Apache 2.0](LICENSE). Third-party material retains its own [notices](NOTICE) and [image credits](docs/IMAGE-CREDITS.md). MeBot is independent and is not endorsed by OpenAI, Anthropic, Google or any publisher. Documentation hosting is independent of the package identifier.
'''
(ROOT/'README.md').write_text(README)
start=f'''# Start here — MeBot {v}

[Download Android APK]({apk}) · [Release files]({notes}) · [Full user guide](docs/USER-GUIDE.md)

## 1. Choose interests
Pick topics and, optionally, a city. The app does not require an imported context file.

![Choose interests](docs/screenshots/v090/onboarding-en-chooser.png)

## 2. Read, ask and refine
Open an item for the introduction and an automatic explanation when AI is connected. Sources remain accessible. Relevant / Not relevant tunes that module; Undo changes your choice.

![A sourced item and its explanation](docs/screenshots/v082/detail-answer.png)

## 3. Choose a connection
Open You → AI connection. Choose an eligible sign-in, an API key or your personal endpoint. Set the request allowance here. Public information stays usable without AI.

![Provider choices](docs/screenshots/v092/connection-sw.png)

## 4. Set background behavior
Choose timing, preparation, connection/battery conditions and quiet hours. New results arrive as modules complete.

![Background controls](docs/screenshots/v082/background.png)

## 5. Keep MeBot current
Open You → Software Updates. Automatic checks and optional Wi-Fi downloads prepare eligible releases; Android requires installation approval. This package is `mebot.nguli.com`, separate from older development builds.

![Software Updates](docs/screenshots/v082/software-updates.png)

## 6. Use optional tools
Alarm, Focus and Daily Check-in are together during onboarding and remain available independently of AI. Run a short alarm test before relying on it overnight.

![Daily tools](docs/screenshots/v082/daily-tools.png)

The in-app guide is skippable and replayable from Help. Captures use dated public content and controlled provider state; [source and image notes](docs/screenshots/v082/README.md).
'''
(ROOT/'START-HERE.md').write_text(start)
print(f'Rendered {v}: five product pages, {len(screens)} captures, README and Start Here.')

# Historical preview pages stay available as dated records, not current candidate banners.
for document in DOCS.rglob('*.html'):
    content=document.read_text()
    content=re.sub(r'<aside class="wrap release-hold">.*?</aside>', '', content, flags=re.S)
    content=re.sub(r'<!-- preview-[0-9]+:start -->.*?<!-- preview-[0-9]+:end -->', '', content, flags=re.S)
    if document.name.startswith('preview-'):
        content=re.sub(r'<aside class="release-archive(?: wrap)?">.*?</aside>', '', content, flags=re.S)
        content=content.replace('<main', '<aside class="release-archive wrap"><p><strong>Historical preview.</strong> The candidate status below records the original verification stage. <a href="releases.html">See published historical files</a> or <a href="download.html">get recommended MeBot 0.9.3</a>.</p></aside><main',1)
    document.write_text(content)

# Fail closed for a private candidate: no download link, invented identity or release claim.
if not published:
    for output in [*DOCS.rglob('*.html'), ROOT/'README.md', ROOT/'START-HERE.md']:
        content=output.read_text()
        content=re.sub(r'<a\b[^>]*href="https://github.com/EmmanuelMmanda/MeBot/releases/(?:download|tag)/v0\.9\.[0-3](?:/[^"<>]*)?"[^>]*>(.*?)</a>', r'<span>\1 (publication pending)</span>', content, flags=re.S)
        content=re.sub(r'\[([^]]+)\]\(https://github.com/EmmanuelMmanda/MeBot/releases/(?:download|tag)/v0\.9\.[0-3](?:/[^)]+)?\)', r'\1 (publication pending)', content)
        if not r.get('localArtifactsVerified'):
            content=re.sub(r'<details class="release-metadata">.*?</details>', '<p>Final APK checksum, size and source commit are not available yet.</p>', content, flags=re.S)
        content=content.replace('Install this APK over your existing app.', 'Wait for the verified corrected APK; do not install a superseded build.').replace('Official Android release','Corrective release candidate').replace('Official MeBot 0.9.3','MeBot 0.9.3 candidate').replace('Version 0.9.3 · Official release','Version 0.9.3 · Verification pending').replace('Official · None','Publication pending').replace('Recommended Android release','Corrective Android candidate')
        content=content.replace('The Android APK, source, screenshots and verification files are published together.','The signed APK and matching source will be linked after verification.').replace('Direct APK download. JavaScript is not required.','No candidate download is enabled before verification.').replace('the downloadable APK is the signed official release','screenshots do not establish a verified downloadable release')
        if output.suffix=='.html':
            content=content.replace('<main', '<aside class="wrap release-hold"><p><strong>0.9.3 publication verification pending.</strong> Versions 0.9.0–0.9.2 have a known external-picker and permission-launch defect. No new APK is published from this candidate page.</p></aside><main',1)
        else:
            content='> **Private 0.9.3 candidate — do not deploy.** Public artifact verification is pending; 0.9.0–0.9.2 are superseded due to a known external-picker and permission-launch crash.\n\n'+content
        output.write_text(content)
