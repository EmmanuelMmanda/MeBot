#!/usr/bin/env python3
"""Render static product pages and README from reviewed release and screen data.
No network requests. Existing illustrated guides are retained. Generated downloads
and image links work without JavaScript. Run before checking or publishing docs.
"""
from pathlib import Path
from html import escape as esc
from datetime import date
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
REPO = 'https://github.com/EmmanuelMmanda/MeBot'
SITE = 'https://emmanuelmmanda.github.io/MeBot/'
r = json.loads((DOCS / 'assets/release.json').read_text())
screens = json.loads((DOCS / 'assets/screens.json').read_text())
v = r['version']
assert r['status'] == 'published' and r['tag'] == 'v' + v
assert re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?', v)
base = f"{REPO}/releases/download/{r['tag']}/"
notes = f"{REPO}/releases/tag/{r['tag']}"
apk = base + r['filename']
source = base + r['sourceFilename']
previews = base + r['previewsFilename']
size = f"{r['sizeBytes'] / 1_000_000:.1f} MB"
published = date.fromisoformat(r['publishedDate']).strftime('%d %B %Y')
for s in screens:
    path = (DOCS / s['file']).resolve()
    assert path.is_relative_to(DOCS.resolve()) and path.is_file(), s['file']
    assert path.read_bytes()[:8] == b'\x89PNG\r\n\x1a\n', s['file']
    s['width'], s['height'] = struct.unpack('>II', path.read_bytes()[16:24])

# Shared navigation fixes preserve existing guide content and anchors.
for path in DOCS.rglob('*.html'):
    text = path.read_text()
    text = text.replace(REPO + '/blob/master/', REPO + '/blob/main/')
    text = text.replace(REPO + '/tree/master/', REPO + '/tree/main/')
    text = text.replace(REPO + '/releases/latest', notes)
    text = re.sub(r'<span>MeBot 0\.6\.1 ·', f'<span>MeBot {v} ·', text)
    if 'showcase.css' not in text:
        prefix = '../' if path.parent.name == 'guides' else ''
        text = text.replace('</head>', f'<link rel="stylesheet" href="{prefix}showcase.css"><script src="{prefix}showcase.js" defer></script>\n</head>')
    if '<!-- beta-notice -->' not in text and path.parent.name == 'guides':
        text = text.replace('</header>', f'</header>\n<!-- beta-notice --><aside class="beta-banner">Current download: <a href="../download.html">MeBot Test {v}</a>. Installs separately; keep your existing MeBot.</aside>', 1)
    path.write_text(text)

shell = (DOCS / 'index.html').read_text()
footer = shell[shell.index('<footer'):]
header = shell[shell.index('<a class="skip-link"'):shell.index('<main')]
header = header.replace(' aria-current="page"', '')
if 'href="screenshots.html"' not in header:
    header = header.replace('<a href="guide.html">Guide</a>', '<a href="screenshots.html">Screenshots</a><a href="guide.html">Guide</a>')


def page(filename, title, desc, content, current=None):
    nav = header
    if current:
        nav = nav.replace(f'href="{current}"', f'href="{current}" aria-current="page"', 1)
    head = f'''<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} · MeBot</title><meta name="description" content="{esc(desc, quote=True)}">
<meta name="theme-color" content="#f7f8fa"><meta name="color-scheme" content="light dark"><meta name="referrer" content="strict-origin-when-cross-origin">
<link rel="canonical" href="{SITE}{filename}"><link rel="icon" href="assets/mebot.svg" type="image/svg+xml">
<meta property="og:type" content="website"><meta property="og:title" content="{esc(title, quote=True)} · MeBot"><meta property="og:description" content="{esc(desc, quote=True)}"><meta property="og:url" content="{SITE}{filename}"><meta property="og:image" content="{SITE}screenshots/v070/feed.png"><meta property="og:image:alt" content="MeBot Android feed with module filters and a sample story">
<link rel="stylesheet" href="site.css"><link rel="stylesheet" href="showcase.css"><script src="site.js" defer></script><script src="showcase.js" defer></script>
</head><body data-release-manifest="assets/release.json">
'''
    (DOCS / filename).write_text(head + nav + '<main id="main">\n' + content + '\n</main>\n' + footer)


def shot(s, hero=False):
    load = 'fetchpriority="high"' if hero else 'loading="lazy"'
    return f'''<figure class="shot" data-screen-group="{s['group']}"><a href="{s['file']}" data-lightbox data-title="{esc(s['title'], quote=True)}" aria-label="Enlarge: {esc(s['title'], quote=True)}"><img src="{s['file']}" width="{s['width']}" height="{s['height']}" alt="{esc(s['alt'], quote=True)}" {load} decoding="async"></a><figcaption><span class="shot-version">{esc(s['version'])}</span><strong>{esc(s['title'])}</strong><span>{esc(s['caption'])}</span></figcaption></figure>'''


def buttons():
    return f'<div class="actions"><a class="button" data-release-apk href="{apk}">Download test APK ↓</a><a class="button secondary" href="screenshots.html">See the app →</a></div>'


beta = f'<p class="beta-note"><strong>Testing release {v}.</strong> Installs as <strong>MeBot Test</strong>, alongside your existing MeBot. <a href="{notes}">Read release notes</a>.</p>'
page('index.html', 'MeBot Daily for Android', 'A personal Android feed, sourced AI answers and optional everyday tools. See actual app screens and download the current test APK.', f'''
<section class="hero wrap"><div class="hero-copy"><p class="eyebrow">Android · Open source <span class="version">{v}</span></p><h1>MeBot Daily</h1><p class="hero-lead">News, learning, music and useful finds, chosen around your interests. Open a story, ask a question, or explore a topic.</p>{buttons()}<p class="micro">Android 8.0+ · {size} · English &amp; Kiswahili</p>{beta}<div class="connection-intro"><h2>Connect the AI account you use</h2><p>Use MeBot’s ChatGPT sign-in where your account supports it, or choose an API key. Model access and usage limits depend on your provider.</p><a class="text-link" href="guides/ai-connections.html">Account setup and limits →</a></div></div><div class="hero-phones">{shot(screens[0], True)}{shot(screens[1])}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Start with what you like</p><h2>A few choices. Your first feed.</h2><p>Pick topics, add an optional city or interest, then open your feed. No context file required.</p></div><a class="text-link" href="guides/install.html">Setup guide →</a></div><div class="screen-grid three">{''.join(shot(screens[i]) for i in (3,4,5))}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Read and ask</p><h2>Keep the story and the answer together.</h2><p>Read sourced explanations, ask follow-up questions and switch the topic of a conversation.</p></div><a class="text-link" href="guides/feed-and-ai.html">Explore Feed &amp; AI →</a></div><div class="feature-pair">{shot(screens[2])}<div class="feature-copy"><h3>Choose how you use it</h3><div class="feature-line"><strong>Follow your topics</strong><p>News, technology, opportunities, learning, music, weather and more.</p></div><div class="feature-line"><strong>Ask a closer question</strong><p>Read an explanation with source links, then keep the conversation going.</p></div><div class="feature-line"><strong>Keep useful finds</strong><p>Save a story, reorder modules or change the information MeBot uses about you.</p></div><a class="text-link" href="screenshots.html">View all {len(screens)} screens →</a></div></div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Optional tools</p><h2>Make room for the rest of your day.</h2><p>Alarms, focus and check-in sit alongside your feed. You decide which modules stay enabled.</p></div><a class="text-link" href="guides/modules.html">All modules →</a></div><div class="screen-grid three">{''.join(shot(screens[i]) for i in (7,9,6))}</div></section>
<section class="next-step wrap"><div><p class="eyebrow">Available on GitHub</p><h2>Try {v}</h2><p>The APK, matching source, screen previews and verification files are published together.</p></div><div class="actions"><a class="button" data-release-apk href="{apk}">Download test APK ↓</a><a class="button secondary" href="releases.html">Releases &amp; files →</a></div></section>
<p class="image-provenance wrap">Screens are native Android renders with example content, not live account results. Each image is labelled by version. <a href="screenshots.html#image-notes">Image notes</a>.</p>
''', 'index.html')

filter_html = ''.join(f'<button type="button" data-screen-filter="{key}" aria-pressed="{str(key=="all").lower()}">{label}</button>' for key,label in [('all','All screens'),('start','Getting started'),('read','Feed & AI'),('organize','Your preferences'),('tools','Everyday tools')])
page('screenshots.html', 'Inside the app', 'Explore actual MeBot Android screens, with full-size previews, captions, version labels and accessible image descriptions.', f'''
<section class="section wrap"><p class="eyebrow">App gallery</p><h1>Inside MeBot</h1><p class="lead">See what you can do before installing. Choose a screen to open it full-size.</p><div class="screen-filters js-only" role="group" aria-label="Filter app screenshots">{filter_html}</div><p id="screen-count" role="status" class="micro">{len(screens)} screens</p><div class="screen-grid" id="screen-gallery">{''.join(shot(s) for s in screens)}</div></section>
<section class="section wrap hairline" id="image-notes"><h2>About these images</h2><p>Seven screens are from the 0.7.0 beta. Supporting screens from 0.6.1, 0.6.0 and 0.5.1 are labelled individually. They are native Android host renders with synthetic example content, not photographs of a physical phone or proof of a live AI connection.</p><p>Images open at their original resolution. Use Tab and Enter to select an image, Escape to close the viewer, or the original-image link to inspect it in your browser.</p><div class="actions"><a class="button secondary" href="{previews}">Download beta screen previews ↓</a><a class="text-link" href="{REPO}/blob/main/docs/screenshots/README.md">Capture notes &amp; credits →</a></div></section>
''', 'screenshots.html')

resources = [('Matching source ZIP', 'The exact source archive published with this beta.', r['sourceFilename']), ('Native screen previews', 'Seven Android renders with example content.', r['previewsFilename']), ('Verification report', 'Build evidence, test results and remaining device checks.', r['verificationFilename']), ('Test-result archive', 'The test evidence attached to this release.', r['testsFilename']), ('SHA-256 checksums', 'Compare downloaded files with the published digests.', 'SHA256SUMS.txt')]
resource_html = ''.join(f'<div class="download-row"><div><strong>{title}</strong><p>{desc}</p></div><a href="{base}{file}">Download <span class="sr-only">{title}</span> ↓</a></div>' for title,desc,file in resources)
page('download.html', 'Download MeBot Test', 'Download the published MeBot Android test APK, matching source, screenshots and verification files.', f'''
<div class="release-page"><p class="eyebrow">Published testing build</p><h1>Download MeBot Test</h1><p class="lead">The current public download is {v}. Keep your existing MeBot installed.</p><section class="release-card" aria-labelledby="current-build"><span class="release-state" data-release-status>{v} · Testing release</span><h2 id="current-build">MeBot <span data-release-version>{v}</span></h2><p>Android 8.0+ · <span data-release-size>{size}</span> · English &amp; Kiswahili</p><div class="actions"><a class="button" data-release-apk href="{apk}">Download test APK ↓</a><a class="button secondary" href="{notes}">Release notes ↗</a></div>{beta}<p class="micro">This button downloads the published APK directly. JavaScript is not required.</p><div class="release-metadata"><p>File: <code data-release-filename>{r['filename']}</code></p><p>Separate package: <code>app.mynguli.beta</code> · Test signing certificate</p><details><summary>Verify the APK checksum</summary><div class="code-block"><div class="code-toolbar"><span>SHA-256</span><button type="button" class="copy-button js-only" data-copy-target="apk-checksum">Copy checksum</button></div><pre id="apk-checksum" data-release-sha tabindex="0">{r['sha256']}</pre></div><p><a href="{base}SHA256SUMS.txt">All release checksums</a></p></details></div></section>
<section class="article-section" id="install"><h2>Install without disturbing your existing app</h2><ol><li>Open the APK on your Android phone. Allow installation from that browser or file manager when Android asks.</li><li>Open <strong>MeBot Test</strong>. It is separate from your existing MeBot and has its own data.</li><li>Choose your interests and reconnect your AI account. Sign-in tokens are not copied from the old app.</li><li>To bring preferences across, export them in the original app and review the import inside MeBot Test.</li></ol><p>Keep only the alarms you intend to use enabled across the two apps. Test sound and alarm delivery on your phone before relying on them overnight.</p><p><a href="guides/install.html">Full setup guide →</a></p></section>
<section class="article-section" id="resources"><h2>Source and release files</h2><div class="download-list">{resource_html}</div></section>
<section class="article-section" id="limits"><h2>What to know about this beta</h2><p>This is a testing release, not a stable upgrade. Follow-up Android verification has one unresolved sign-in cancellation check. Live account access, physical-phone alarm behavior and media availability still need device testing.</p><p>Future stable updates must retain the original package and signing key. The stable public certificate in the source tree is not this beta’s signing certificate.</p><p><a href="{REPO}/actions/runs/40102091361">Follow-up verification run ↗</a> · <a href="{notes}">Release details and limits ↗</a></p></section>
<section class="article-section" id="context"><h2>Optional context import</h2><p>Onboarding is enough to get started. Existing users can also import reviewed preferences.</p><div class="actions"><a class="button secondary" href="context-example.json" download="mebot-context.json">Example JSON ↓</a><a class="text-link" href="guides/personal-context.html">Import guide →</a></div></section></div>
''', 'download.html')

history = ''.join(f'<tr><th scope="row">{esc(x["version"])}</th><td>Earlier development milestone</td><td>Not published in this repository’s release list</td></tr>' for x in r.get('releases', []) if x['version'] != v)
page('releases.html', 'Releases and downloads', 'Published MeBot releases, download files, checksums and clearly labelled earlier milestones.', f'''
<div class="release-page"><p class="eyebrow">Release directory</p><h1>Releases &amp; files</h1><p class="lead">Choose a published build and see exactly what comes with it.</p><section class="release-card" id="latest"><span class="release-state">Published {published} · Beta</span><h2>{v}</h2><p>Installs separately as MeBot Test. Android 8.0+ · {size}.</p><div class="actions"><a class="button" data-release-apk href="{apk}">Download test APK ↓</a><a class="button secondary" href="download.html">Installation &amp; checksums →</a><a class="text-link" href="{notes}">View on GitHub ↗</a></div></section>
<section class="article-section" id="directory"><h2>Files for this release</h2><div class="download-list">{resource_html}</div></section>
<section class="article-section"><h2>In this beta</h2><p>Interest-based onboarding, per-story scrolling, direct AI suggestions, in-place model selection, rich answers, source cards, activity updates, a bounded request queue and configurable discovery batches.</p><p>The beta remains a test candidate, with an unresolved follow-up sign-in cancellation check. Read the <a href="{notes}">full notes and limits</a> before testing.</p><p><a href="screenshots.html">See the app screens →</a></p></section>
<section class="article-section"><h2>Earlier milestones</h2><p>The project’s changelog records earlier versions. The entries below are not advertised as downloadable GitHub releases until their actual tags and assets are published.</p><div class="table-wrap"><table><caption class="sr-only">Earlier recorded versions and their publication status</caption><thead><tr><th scope="col">Version</th><th scope="col">Record</th><th scope="col">Download status</th></tr></thead><tbody>{history}</tbody></table></div><p><a href="{REPO}/blob/main/CHANGELOG.md">Read the changelog ↗</a> · <a href="{REPO}/releases">All GitHub releases ↗</a></p></section></div>
''', 'releases.html')

# Retain hand-maintained connection, technical and contribution sections.
readme = (ROOT / 'README.md').read_text()
readme = readme[readme.index('## Use your ChatGPT subscription'):]
old_start = readme.index('## Preferences that stay yours') if '## Preferences that stay yours' in readme else readme.index('## Releases and verification')
old_end = readme.index('## Build from source')
readme = readme[:old_start] + f'''## Releases and verification

The published test build is **{v}**, installed as **MeBot Test** (`app.mynguli.beta`). It does not replace the stable app or copy account tokens. Keep your existing MeBot installed.

[Download the APK]({apk}) · [Source ZIP]({source}) · [Screen previews]({previews}) · [Verification report]({base}{r['verificationFilename']}) · [SHA-256 checksums]({base}SHA256SUMS.txt)

This remains a beta. The follow-up Android verification run has one unresolved sign-in cancellation check. Live account eligibility, device alarm delivery and media playback still require phone checks. [Follow-up CI evidence]({REPO}/actions/runs/40102091361) · [Release notes and limits]({notes}).

Older versions are development history, not claimed as published downloads. See the [release directory]({SITE}releases.html) and [changelog](CHANGELOG.md).

Personal profiles, credentials and private signing keys must not be published. See [privacy](docs/PRIVACY.md), [source distribution](docs/PUBLIC-RELEASES.md) and [security reporting](SECURITY.md).

''' + readme[old_end:]
readme = readme.replace(REPO + '/releases/latest', notes)
readme = readme.replace('promote to **master**', 'promote to **main**')
readme = readme.replace('**Open the six-step tour or skip it.**', '**Choose your interests, or skip the tour.**')
readme = readme.replace(REPO + '/blob/master/', REPO + '/blob/main/')
rows = []
for i in range(0, len(screens), 3):
    group = screens[i:i+3]
    rows.append('<p align="center">\n' + '\n'.join(f'  <a href="docs/{s["file"]}"><img src="docs/{s["file"]}" width="225" alt="{esc(s["alt"] + " — " + s["version"], quote=True)}"></a>' for s in group) + '\n</p>\n\n' + ' · '.join(f'**{s["title"]}** ({s["version"]})' for s in group) + '\n')
intro = f'''<div align="center">
<img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo">
<h1>MeBot Daily</h1>
<p>A native Android app for your topics, sourced AI answers and everyday tools.</p>
<p><a href="{SITE}"><strong>Website &amp; user guide</strong></a> · <a href="{apk}"><strong>Download test APK</strong></a> · <a href="{REPO}/releases">Releases</a> · <a href="{SITE}screenshots.html">Screenshot gallery</a></p>
<p>Android 8.0+ · English &amp; Kiswahili · Kotlin + Jetpack Compose · Apache 2.0</p>
</div>

> **Current download: {v} · {size}.** This beta installs separately as **MeBot Test**. Keep your existing MeBot installed. [Installation and limits]({SITE}download.html).

Choose the subjects you care about. Read a source-linked feed, open a story for an explanation, search, or ask a follow-up. Alarms, focus and check-in remain optional tools, not the whole app.

## See the app

{''.join(rows)}
These are **actual native Android renders with synthetic example content**, not live AI responses or physical-phone photographs. Seven current beta screens appear first; supplementary screens are labelled by their capture version. Select any image for the full-size original. The [web gallery]({SITE}screenshots.html) includes captions, filters and keyboard-accessible enlargement. [Capture notes and image credits](docs/screenshots/README.md).

'''
(ROOT / 'README.md').write_text(intro + readme)
print(f'Rendered homepage, {len(screens)}-screen gallery, download page, release directory and README for {v}.')
