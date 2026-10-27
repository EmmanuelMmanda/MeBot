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
official=r if published else next(x for x in r['releases'] if x.get('status')=='published' and x.get('apk') is True)
candidate_preview=not published and r.get('privateCandidate') is True
site_footer_version=v if published else official['version']
base=f'{REPO}/releases/download/{official["tag"]}/';apk=base+official['filename'];notes=f'{REPO}/releases/tag/{official["tag"]}'
source=base+official['sourceFilename'];verification=base+official['verificationFilename'];checksums=base+official.get('checksumFilename','SHA256SUMS.txt')
size=f"{official['sizeBytes']/1000000:.1f} MB";candidate_notes=f'RELEASE-{v}.md'
screens=json.loads((DOCS/'assets/screens.json').read_text())
for s in screens:
 p=(DOCS/s['file']).resolve();assert p.is_relative_to(DOCS.resolve()) and p.is_file()
 raw=p.read_bytes();assert raw[:8]==b'\x89PNG\r\n\x1a\n';s['width'],s['height']=struct.unpack('>II',raw[16:24])
by={Path(s['file']).stem:s for s in screens}
shell=(DOCS/'index.html').read_text();footer=shell[shell.index('<footer'):]
footer=re.sub(r'MeBot 0\.[0-9.]+(?:-beta\.[0-9]+)? ·',f'MeBot {site_footer_version} ·',footer)
footer=footer.replace('Native screens with example content','Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.').replace('Dated public content · native captures','Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.').replace('Dated public stories · labelled synthetic examples · native captures','Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.')
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
 label=(f"{s['version']} · sample content" if s.get('sampleFixture') else f"{s['version']} · {s['captureDate']}")
 if s.get('candidatePreview') and not published: label=f"{s['version']} · candidate preview"
 return f'''<figure class="shot" data-screen-group="{s['group']}"><a href="{s['file']}" data-lightbox data-title="{esc(s['title'],quote=True)}" aria-label="Enlarge: {esc(s['title'],quote=True)}"><img src="{s['file']}" width="{s['width']}" height="{s['height']}" alt="{esc(s['alt'],quote=True)}" {load} decoding="async"></a><figcaption><span class="shot-version">{esc(label)}</span><strong>{esc(s['title'])}</strong><span>{esc(s['caption'])}</span></figcaption></figure>'''

def download(label='Download MeBot APK ↓'):
 return f'<a class="button" data-release-apk href="{apk}">{label}</a>'
identity='<p class="beta-note"><strong>Updating an existing MeBot 0.9.x installation?</strong> Install this APK over your current app. Do not uninstall or clear data. Older 0.8.x builds used a different signing identity; Android will not accept this as an in-place update to those builds.</p>'
provenance='<p class="image-provenance wrap">Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied. <a href="screenshots.html#image-notes">Capture notes and image credits →</a></p>'
candidate_gallery_ids=['en-feed','en-detail','en-mebot-prefilled']
candidate_copy=('Stories stay in MeBot first: a compact, story-grounded category label helps you recognize the item, while the original source remains one tap away from details or the card menu. Item-grounded questions can follow a story from the feed to its detail and into MeBot. Review or edit the prepared question before sending; choosing it never sends automatically. Local questions remain available offline.' if candidate_preview else 'Open a story in MeBot first, with its publisher visible and the original source close at hand. Suggested questions follow that item from the feed to details and MeBot; you can edit the draft before choosing to send. Local questions still work offline.')
preview_status='<p class="micro">Prepared 0.9.5 preview · not yet publicly released. The download above remains the official 0.9.4 APK.</p>' if candidate_preview else ''
preview_section=f'''<section class="section wrap hairline" id="story-questions"><div class="section-heading"><div><p class="eyebrow">{'In preparation · ' if candidate_preview else 'A better next step · '}0.9.5</p><h2>A question that stays with the story.</h2><p>{candidate_copy}</p>{preview_status}</div><a class="text-link" href="preview-095.html">Explore the story flow →</a></div><div class="screen-grid three">{''.join(shot(k) for k in candidate_gallery_ids)}</div></section>'''
next_step_title=f"Get MeBot {official['version']}" if candidate_preview else f"Get MeBot {v}"
next_step_copy=(f"The official {official['version']} APK and its source, release notes and verification files are available." if candidate_preview else "The signed APK, matching source, release notes, checksums and verification report are linked together.")
page('index.html','MeBot Daily for Android','Explore your interests, read sourced information, ask MeBot and keep useful resources. Download the official Android release.',f'''
<section class="hero wrap"><div class="hero-copy"><p class="eyebrow">Android · Open source <span class="version">{official['version']}</span></p><h1>MeBot Daily</h1><p class="hero-lead">Your interests shape a useful feed. Bring context when you want it, see imported notes right away, and keep stories you still need.</p><div class="actions">{download()}<a class="button secondary" href="tour.html">Take a look inside →</a></div><p class="micro">Android 8.0+ · {size} · English &amp; Kiswahili · Package <code>{official['applicationId']}</code></p>{identity}<div class="connection-intro"><h2>AI is your choice</h2><p>Connect an eligible provider or personal service when you want help finding relevant public stories. Public-source reading, saved items and local tools remain useful without AI.</p><a class="text-link" href="guides/ai-connections.html">Providers and capabilities →</a></div></div><div class="hero-phones">{shot('news',True)}{shot('detail-answer')}</div></section>
{preview_section}
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">A smoother context import</p><h2>Your notes are visible when you return.</h2><p>Review the replacement, confirm, and land back on your details with the imported notes in view. Invalid files and a cancelled review leave your draft alone.</p></div><a class="text-link" href="guides/personal-context.html#after-import">See how imports work →</a></div><div class="screen-grid three">{''.join(shot(k) for k in ['imported-notes-en','imported-notes-sw','context-import-sw'])}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Start with what matters</p><h2>Choose interests. Add context only if it helps.</h2><p>Onboarding preferences are enough to start; a JSON file is optional.</p></div><a class="text-link" href="tour.html">A short guided start →</a></div><div class="screen-grid three">{''.join(shot(k) for k in ['onboarding-en-chooser','onboarding-sw-chooser','personalization-sw'])}</div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">Read, ask and refine</p><h2>A fresher feed without losing your place.</h2><p>Expired source-verified forecasts and events leave active reading. Saved and starred items stay yours; useful evergreen stories have more room.</p></div></div><div class="feature-pair">{shot('mebot')}<div class="feature-copy"><h3>Keep relevance in your hands</h3><div class="feature-line"><strong>Relevant / Not relevant</strong><p>Adjust a module's future suggestions. Change a choice, undo it or reset feedback separately.</p></div><div class="feature-line"><strong>Optional AI assistance</strong><p>Your selected AI can help match a small shortlist of public stories to confirmed, in-scope notes and explicit feedback during the existing refresh. It is a qualitative hint, not a promise or probability.</p></div><div class="feature-line"><strong>Steady reading order</strong><p>Completed modules update as they finish. Results do not interrupt the item you are reading or remove saved and starred records.</p></div><a class="text-link" href="screenshots.html">Explore all {len(screens)} screens →</a></div></div></section>
<section class="section wrap hairline"><div class="section-heading"><div><p class="eyebrow">You stay in control</p><h2>Clear settings. Useful actions.</h2><p>Choose a connection, keep usage in view and check the release identity before Android installs.</p></div></div><div class="screen-grid three">{''.join(shot(k) for k in ['connection-sw','starred-sw','software-updates'])}</div></section>
<section class="next-step wrap"><div><h2>{next_step_title}</h2><p>{next_step_copy}</p></div><div class="actions">{download()}<a class="button secondary" href="releases.html">Release files →</a></div></section>{provenance}''','index.html')
filters=''.join(f'<button type="button" data-screen-filter="{k}" aria-pressed="{str(k=="all").lower()}">{t}</button>' for k,t in [('all','All screens'),('start','Getting started'),('read','Feed & MeBot'),('organize','Your preferences'),('tools','Everyday tools')])
page('screenshots.html','Inside MeBot','MeBot Android screens, full-size previews, captions and image provenance.',f'''
<section class="section wrap"><p class="eyebrow">Current app gallery · {official['version']}{' · with a 0.9.5 prepared preview' if candidate_preview else ''}</p><h1>Inside MeBot</h1><p class="lead">See setup, reading and story questions in the app. {'Candidate screenshots are labelled as sample previews; ' if candidate_preview else 'Story examples are labelled as sample content; '}select an image for its original size.</p><div class="screen-filters js-only" role="group" aria-label="Filter app screenshots">{filters}</div><p id="screen-count" role="status" class="micro">{len(screens)} screens</p><div class="screen-grid" id="screen-gallery">{''.join(shot(s) for s in screens)}</div></section>
<section class="section wrap hairline" id="image-notes"><h2>About these captures</h2><p>Each capture is labelled with its app version; dated public examples keep their capture dates. The English and Kiswahili 0.9.4 imported-note examples use synthetic fixtures; the note text and name are examples, not account data. The 0.9.5 story captures also use synthetic story and provider fixtures; feed and detail views are deliberately scrolled to the question area, and the MeBot view shows an editable draft before Send.</p><p>These host captures are not live articles, live provider responses, owner account sessions, or physical-phone acceptance evidence. The original source remains available in the story; selecting a suggested question prepares a draft and does not send it automatically. <a href="screenshots/README.md">Full capture notes and sources →</a> · <a href="preview-095.html">0.9.5 story-flow preview →</a></p><section class="evidence-only"><h3>Accessibility stress evidence — not a normal-use screenshot</h3><p>The separate 0.9.4 capture tests a narrow 320 dp layout at 130% font scale. It is evidence of a stress configuration, not a standard phone-size example.</p><a href="screenshots/v094/evidence/imported-notes-sw-320dp-130percent.png"><img src="screenshots/v094/evidence/imported-notes-sw-320dp-130percent.png" width="640" height="1600" alt="Evidence-only MeBot 0.9.4 accessibility stress capture: Kiswahili imported notes at 320 dp width and 130 percent text scale; not a normal-use screenshot." loading="lazy" decoding="async">Open evidence image</a></section><div class="actions"><a class="text-link" href="{REPO}/blob/main/docs/IMAGE-CREDITS.md">Image credits →</a></div></section>''','screenshots.html')
resources=[('Matching source','Exact tested application source snapshot.',official['sourceFilename']),('Verification report','Executed checks and explicit device-testing limits.',official['verificationFilename']),('Release notes','Experience-first changes for this version.',official['releaseNotesFilename']),('SHA-256 checksums','Digests for all release assets.',official.get('checksumFilename','SHA256SUMS.txt')),('Release metadata','Package, source, version and signer identity.',official.get('releaseMetadataFilename','release-metadata.json'))]
resource_html=''.join(f'<div class="download-row"><div><strong>{t}</strong><p>{desc}</p></div><a href="{base}{f}">Download <span class="sr-only">{t}</span> ↓</a></div>' for t,desc,f in resources)
candidate_download_note=(f'''<aside class="beta-note"><strong>MeBot {v} is in preparation, not a public download.</strong> The signed APK below is the current official MeBot {official['version']}. The candidate is not available through the website or in-app updates. <a href="preview-095.html">Preview its story-question flow →</a></aside>''' if candidate_preview else '')
page('download.html','Download MeBot','Official signed MeBot Android APK, matching source, release notes and verification files.',f'''
<div class="release-page"><p class="eyebrow">Official Android release · Published {official['publishedDate']}</p><h1>Download MeBot</h1><p class="lead">MeBot {official['version']} · Android 8.0+ · {size}.</p>{candidate_download_note}<section class="release-card"><span class="release-state" data-release-status>Version {official['version']} · Official release</span><h2>MeBot <span data-release-version>{official['version']}</span></h2><div class="actions">{download()}<a class="button secondary" href="{notes}">Release notes ↗</a></div>{identity}<p class="micro">Direct signed APK download. JavaScript is not required.</p><details class="release-metadata"><summary>File identity and verification</summary><p>Package: <code>{official['applicationId']}</code> · Version code {official['versionCode']}.</p><p><code data-release-filename>{official['filename']}</code> · <span data-release-size>{size}</span>.</p><p>Exact application source: <code>{official['sourceCommit']}</code> · tree <code>{official['sourceTree']}</code>.</p><p>Signer certificate SHA-256: <code>{official['certificateSha256']}</code>.</p><div class="code-block"><div class="code-toolbar"><span>APK SHA-256</span><button type="button" class="copy-button js-only" data-copy-target="apk-checksum">Copy checksum</button></div><pre id="apk-checksum" data-release-sha tabindex="0">{official['sha256']}</pre></div></details></section>
<section class="article-section" id="install"><h2>Install or update</h2><ol><li>Download and open the APK on Android. Allow your browser or file manager to install when Android asks.</li><li>For existing 0.9.x installations, install over MeBot. Your package and signing identity match; keep the app installed and do not clear its data.</li><li>On first install, choose your language and interests, then explore the feed. Importing a context file is optional.</li><li>Connect an AI provider when ready. Public-source reading, saved items and local tools do not require an AI account.</li><li>For future releases, open <strong>You → Software Updates</strong>. The installer verifies the package, version, bytes and certificate; Android still asks before installation.</li></ol><p>Earlier 0.8.x binaries used a different signing identity; they are not an in-place update path to this release. Back up supported preferences before changing installation. Protected credentials do not transfer in portable exports. Do not erase your current app to try an update.</p></section>
<section class="article-section" id="resources"><h2>Release resources</h2><p>The exact source snapshot, APK, user-facing notes, checksum list, release metadata and technical report are distributed together.</p><div class="download-list">{resource_html}</div></section><section class="article-section" id="limits"><h2>Verification limits</h2><p>See the separate verification report for exact test counts, lint findings and signing checks. Automated provider responses and Android host tests are not substitutes for a real account login, physical-phone media, OEM file picker or overnight alarm testing.</p><p>AI ranking remains optional and does not promise a particular story. Provider billing and subscription eligibility follow the selected service.</p></section><section class="article-section" id="context"><h2>Import your preferences</h2><p>Onboarding interests are enough to start. If you import JSON, review the replacement before saving; a confirmed import returns to your details with the saved notes in view.</p><a class="text-link" href="guides/personal-context.html#after-import">See the context guide →</a></section></div>''','download.html')
candidate_card=(f'''<section class="release-card" id="prepared-candidate"><span class="release-state">In preparation · not yet released</span><h2>MeBot {v}</h2><p>Story questions follow the item through Feed, story details and MeBot. Original sources stay easy to open, and every suggested question remains an editable draft.</p><p>The candidate is not offered for download or in-app updates. The official release below remains MeBot {official['version']}.</p><div class="actions"><a class="button secondary" href="preview-095.html">Preview the story flow →</a><a class="text-link" href="{candidate_notes}">Candidate notes</a></div></section>''' if candidate_preview else '')
what_changed=(f'''<h2>What changed in {official['version']}</h2><p>Stories open in MeBot first, with publisher attribution and a compact category label. Story-grounded questions appear in the feed, story details and MeBot. Review or edit a prepared question before deciding to send it; the original source stays available as a secondary action, and local questions remain useful offline.</p><p>Connected-AI suggestions are reused when suitable for the current story and approved context. They complement—not replace—the original source.</p>''' if official['version']=='0.9.5' else f'''<h2>What changed in {official['version']}</h2><p>Confirm a context import and return to your details with the notes you just saved. If a file is invalid or you cancel, your draft stays put. Source-verified forecasts and events leave the active feed when they expire, while saved and starred records remain available. Useful evergreen stories age more slowly.</p><p>Your selected AI can help match a bounded shortlist of public stories within its existing feed refresh. The score is a qualitative signal, not a calibrated prediction or a guarantee. It does not use passive reading history or reorder the item you are reading.</p>''')
history=''.join(f'<tr><th scope="row">{esc(x["version"])}</th><td>{("Current official" if x["version"]==official["version"] else ("Historical signed · superseded" if x.get("apk",True) else "Source only · superseded")) if x["status"]=="published" else ("Historical · known defect" if x.get("recommendation")=="superseded" else "Historical · publication pending")}</td><td>{("<a href="+REPO+"/releases/tag/"+x["tag"]+">Release files ↗</a>") if x["status"]=="published" else "Archive publication pending"}</td></tr>' for x in r['releases'] if x['version']!=v)
page('releases.html','Releases and files','Official MeBot versions, exact source snapshots and release verification evidence.',f'''<div class="release-page"><p class="eyebrow">Release directory</p><h1>Releases &amp; files</h1>{candidate_card}<section class="release-card"><span class="release-state">Official · {official['publishedDate']}</span><h2>MeBot {official['version']}</h2><p>Recommended Android release · package <code>{official['applicationId']}</code>.</p><div class="actions">{download()}<a class="button secondary" href="{notes}">Full release notes ↗</a></div></section><section class="article-section"><h2>Files for MeBot {official['version']}</h2><div class="download-list">{resource_html}</div></section><section class="article-section">{what_changed}<p><a href="RELEASE-{official['version']}.md">Experience-first release notes →</a> · <a href="guides/personal-context.html#after-import">Illustrated context guide →</a></p></section><section class="article-section"><h2>Earlier releases</h2><p>MeBot 0.9.4 is superseded by 0.9.5 and remains available as a historical signed release. Versions 0.9.3 and earlier are also superseded; 0.9.0–0.9.2 had an external-launch defect. The 0.8.x package/signer lineage is separate from current 0.9.x updates. Versions 0.8.3–0.8.5 are historical source checkpoints without production APKs.</p><div class="table-wrap"><table><thead><tr><th scope="col">Version</th><th scope="col">Status</th><th scope="col">Files</th></tr></thead><tbody>{history}</tbody></table></div></section></div>''','releases.html')
steps=[('Choose your interests','Pick topics, not technical settings.','onboarding-en-chooser','First launch','Choose topics and an optional city. Skip anything you do not need. Onboarding preferences personalize your feed; a JSON import is optional.','personalization'),('Import only what you want','Review first, then see your notes.','imported-notes-en','You → My context','Review the replacement before saving. After confirmation, return to your details and see the imported notes. Cancel or correct a file without losing your draft.','after-import'),('Read and refine','Keep the introduction in view.','detail-answer','Feed → open an item','Read the source-backed introduction while MeBot prepares more detail. Relevant or Not relevant adjusts future recommendations within that module; Undo reverses the signal.','feedback'),('Connect your provider','Choose the account or server you use.','connection-sw','You → AI connection','ChatGPT and OpenRouter have different authorization flows. Claude and Gemini use API keys. A compatible personal server is explicit. The screen shows the actual capabilities and local request allowance.','connections'),('Set your pace','Decide what happens in the background.','background','My MeBot → background settings','Choose intervals, module preparation, network conditions and quiet hours. Usage limits are managed in AI connection. Completed modules publish without waiting for the rest.','background'),('Keep MeBot current','Verify, then install.','software-updates','You → Software Updates','Automatic checks find eligible releases for this package. Download verification checks integrity and signing. Android asks you to approve installation; your current app is not silently replaced.','updates'),('Use the tools you need','Alarm, Focus and Check-in.','daily-tools','Modules → daily tools','Configure alarms, start a focus session or make a short check-in. These remain optional and work independently of the AI connection. Test alarm sound on your device.','tools')]
if published and official['version']=='0.9.5':
 steps[2]=('Ask about the story you opened','Keep the next question grounded.','en-detail','Feed → story details → MeBot','Read with the publisher visible, open the original source whenever you want, and choose an item-specific question in Feed or story details. MeBot prepares an editable draft; you decide whether to send it. Local questions remain available offline, and Relevant / Not relevant still tunes future results.','story-questions')
controls=''.join(f'<button type="button" data-tour-step="{i}" aria-pressed="{str(i==0).lower()}" aria-controls="tour-panel-{i}"><span aria-hidden="true">{i+1:02d}</span>{t}</button>' for i,(t,*_) in enumerate(steps))
panels=''.join(f'<section class="tour-panel" id="tour-panel-{i}" aria-labelledby="tour-heading-{i}"><div class="tour-copy"><p class="eyebrow">{esc(where)}</p><h2 id="tour-heading-{i}">{esc(t)}</h2><p class="lead">{esc(lead)}</p><p>{esc(body)}</p><a class="text-link" href="{("guides/personal-context.html#after-import" if anchor=="after-import" else ("guides/feed-and-ai.html#follow-up" if anchor=="story-questions" else "guides/release-082.html#"+anchor))}">Detailed guide →</a></div>{shot(key)}</section>' for i,(t,lead,key,where,body,anchor) in enumerate(steps))
tour_preview=(f'''<section class="section wrap hairline"><p class="eyebrow">In preparation · 0.9.5</p><h2>A useful question, still in context.</h2><p>Story-specific suggestions can travel from Feed to the story details and into MeBot as an editable draft. The original source remains available; nothing sends until you choose to send.</p><div class="actions"><a class="button secondary" href="preview-095.html">Preview the story flow →</a><a class="text-link" href="screenshots/README.md">How to read the sample screenshots</a></div></section>''' if candidate_preview else '')
page('tour.html','Start here','A seven-step visual guide to MeBot, from interests to context import, reading and verified software updates.',f'<section class="section wrap"><p class="eyebrow">MeBot {official["version"]} · Start here</p><h1>A short walk through MeBot</h1><p class="lead">The important actions, with sample app screens. Move at your own pace.</p><div class="tour-layout"><nav class="tour-steps js-only" aria-label="Guide steps">{controls}</nav><div class="tour-panels">{panels}</div></div><div class="tour-navigation js-only"><button class="button secondary" type="button" data-tour-prev>← Back</button><span id="tour-status" role="status" aria-live="polite">Step 1 of {len(steps)}</span><button class="button" type="button" data-tour-next>Next →</button></div></section>{tour_preview}<section class="next-step wrap"><div><h2>Explore MeBot</h2><p>The in-app quick guide is skippable and replayable from Help.</p></div><div class="actions">{download()}<a class="button secondary" href="guide.html">All guides →</a></div></section>','tour.html')
current_screens=[s for s in screens if not (candidate_preview and s.get('candidatePreview'))]
current_screens=sorted(current_screens,key=lambda s:s['version']!=official['version'])
image_rows=[]
for i in range(0,len(current_screens),3):
 group=current_screens[i:i+3]
 image_rows.append('<p align="center">\n'+'\n'.join(f'<a href="docs/{s["file"]}"><img src="docs/{s["file"]}" width="225" alt="{esc(s["alt"],quote=True)}"></a>' for s in group)+'\n</p>\n\n'+' · '.join(f'**{s["title"]}**' for s in group)+'\n')
release_summary=(f'**Official MeBot {v} · {size} · `mebot.nguli.com`.** Install over the existing 0.9.x app without uninstalling or clearing data.' if published else f'**MeBot {v} is not yet released.** The current official download is MeBot {official["version"]} · {size}. Wait for the verified update; do not install a draft candidate.')
if published:
 whats_new=f'''## What’s new in {official['version']}

- Stories open in MeBot first, with the publisher visible and a compact category label.
- Story-specific questions appear in Feed, story details and MeBot. Review or edit the prepared question before sending.
- The original source stays available from the story and card menu; local questions remain useful offline.

[Full experience-first release notes](docs/RELEASE-{official['version']}.md). Technical verification and device/provider limits are in the separate [verification report]({verification}).'''
else:
 whats_new=f'''## What’s new in {official['version']}

- After confirming a context import, return to your details and see the notes you just saved. An invalid file or cancelled review leaves your draft alone.
- Expired source-verified forecasts and events leave the active feed; saved and starred items remain available, and evergreen reading gets more time.
- Optional AI can help match a short list of public stories to your confirmed interests during the existing feed refresh. Your reading position and explicit feedback stay in control.

[Full experience-first release notes](docs/RELEASE-{official['version']}.md). Technical verification, source identity and test limits are in the separate [verification report]({verification}).'''
candidate_readme=(f'''## In preparation: MeBot {v}

Stories open in MeBot first, with a compact, story-grounded category label and the publisher still visible. Suggested questions follow the item from Feed to story details and MeBot. Review or edit a prepared question before you choose to send it. The original source remains easy to open, and local questions remain available offline.

This is a prepared preview, not a public release. The official download remains MeBot {official['version']}; no {v} APK is offered here or through in-app updates. The screenshots use synthetic story/provider fixtures and are not live-provider or physical-phone acceptance evidence.

<p align="center"><img src="docs/screenshots/v095/en-feed.png" width="145" alt="English feed with a sample story question"><img src="docs/screenshots/v095/sw-feed.png" width="145" alt="Kiswahili feed with a sample story question"><img src="docs/screenshots/v095/en-detail.png" width="145" alt="English story detail with source and question"><img src="docs/screenshots/v095/sw-detail.png" width="145" alt="Kiswahili story detail with source and question"><img src="docs/screenshots/v095/en-mebot-prefilled.png" width="145" alt="Editable English MeBot draft, not sent"><img src="docs/screenshots/v095/sw-mebot-prefilled.png" width="145" alt="Editable Kiswahili MeBot draft, not sent"></p>

[Open the illustrated story-flow preview](docs/preview-095.html) · [Candidate notes](docs/RELEASE-{v}.md).
''' if candidate_preview else '')
README=f'''<div align="center">
<img src="docs/assets/mebot.svg" width="64" height="64" alt="MeBot robot logo">
<h1>MeBot Daily</h1><p>Your topics, source-linked answers and optional everyday tools.</p>
<p><a href="{apk}"><strong>Download official APK</strong></a> · <a href="{notes}">Release files</a> · <a href="START-HERE.md">Start here</a> · <a href="docs/USER-GUIDE.md">User guide</a></p>
<p>Android 8.0+ · English &amp; Kiswahili · Kotlin + Jetpack Compose · Apache 2.0</p></div>

> {release_summary} Earlier 0.8.x binaries used a different signer and cannot receive this as an in-place update. Review the [installation guidance](docs/INSTALL.md).

Choose interests during onboarding, read a sourced feed, search, ask follow-ups and keep useful resources. Public information and local tools stay available without AI. No personal starter profile is baked into the app.

{whats_new}

{candidate_readme}

## See the current app

{''.join(image_rows)}
Released-version images retain their dated source context. Imported-note and story-question examples use synthetic fixtures; they are **not live account sessions, live provider responses or physical-device acceptance evidence**. [Sources and capture notes](docs/screenshots/README.md) · [Image credits](docs/IMAGE-CREDITS.md).

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
| Feed | Completed modules publish immediately; new results wait behind Show new items while you read. Expired source-verified forecasts/events leave active reading; saved and starred records stay. |
| Feedback & fit | Relevant / Not relevant improves module suggestions with undo and reset. Optional bounded AI fit can help match up to eight public stories per module; it is not a probability or promise. |
| Item detail | Stable introduction, unclipped square answer surface, streaming explanations and grouped sources/media/files. |
| Activity | One fixed top panel with an anchor-width popover; real events and provider-published summaries, never invented thoughts. |
| Connection | Provider and model choice, one persisted request budget, presets/custom/unlimited and clear capability limits. |
| Background | Timing, preparation batches, Wi-Fi/battery conditions, notifications and quiet hours in actionable cards. |
| Software Updates | Automatic checks and optional Wi-Fi preparation, verified package/version/certificate/hash, and Android’s installer approval. |
| Guidance | One relevant dismissible setup notice, a short first-use tour and replay from Help. |
| Daily tools | Alarm, Focus and Daily Check-in; independent of AI. |
| Data | Clear reading history explains its scope before confirmation; saved items, conversations and relevance feedback are separate. |

## Install and use

[Download the official APK]({apk}). Updating from a 0.9.x release preserves your existing installation; do not uninstall or clear app data. Choose interests, connect a provider when ready, then open Feed. Test any configured alarm on your phone before overnight reliance. [Start here](START-HERE.md).

## Verification and release files

[Verification report]({verification}) · [Exact source snapshot]({source}) · [Release notes]({base}{official.get('releaseNotesFilename','RELEASE-NOTES.md')}) · [Checksums]({checksums}) · [Native gallery](docs/screenshots/README.md).

The current application source is commit `{official['sourceCommit']}` (tree `{official.get('sourceTree','not recorded')}`). The source archive preserves its original local build commit; see the verification report for the mapping.

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

[Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Privacy](docs/PRIVACY.md) · [Architecture](docs/ARCHITECTURE.md) · [Build guide](docs/BUILD.md) · [Release history](docs/PUBLIC-RELEASES.md).

## License

[Apache 2.0](LICENSE). Third-party material retains its own [notices](NOTICE) and [image credits](docs/IMAGE-CREDITS.md). MeBot is independent and is not endorsed by OpenAI, Anthropic, Google or any publisher. Documentation hosting is independent of the package identifier.
'''
(ROOT/'README.md').write_text(README)
candidate_start=(f'''\n## Prepared preview: MeBot {v}\n\nA story-first flow keeps the original source close and a suggested question grounded in the item. Review or edit the question before sending; local suggestions still work offline. This candidate is not publicly released or offered in-app yet. The current official download remains MeBot {official['version']}.\n\n[Explore the 0.9.5 story-flow preview](docs/preview-095.html) · [Read candidate notes](docs/RELEASE-{v}.md). The images are sample content, not live provider or physical-phone acceptance evidence.'''.rstrip() if candidate_preview else '')
start=f'''# Start here — MeBot {official['version']}

[Download Android APK]({apk}) · [Release files]({notes}) · [Full user guide](docs/USER-GUIDE.md)

## 1. Choose interests
Pick topics and, optionally, a city. The app does not require an imported context file.

![Choose interests](docs/screenshots/v090/onboarding-en-chooser.png)

## 2. Import only what you want
If you import context JSON, review the replacement first. After confirming, MeBot returns to your details with the saved notes visible. If you cancel or the file is invalid, your draft stays in place. Onboarding interests are already enough to begin.

![Imported notes in English](docs/screenshots/v094/imported-notes-en.png)

This example uses synthetic notes, not personal account data. See the [context guide](docs/guides/personal-context.html#after-import) for the review flow.

## 3. Read, ask and refine
{("Stories open in MeBot first, with the publisher visible. Choose an item-grounded question, review or edit its draft before you send, and open the original source whenever you want. Local questions remain available offline. Relevant / Not relevant still tunes that module; Undo changes your choice." if published and official["version"] == "0.9.5" else "Open an item for the introduction and an automatic explanation when AI is connected. Sources remain accessible. Relevant / Not relevant tunes that module; Undo changes your choice.")}

![A sourced item and its explanation](docs/screenshots/v082/detail-answer.png)

## 4. Choose a connection
Open You → AI connection. Choose an eligible sign-in, an API key or your personal endpoint. Set the request allowance here. Public information stays usable without AI.

![Provider choices](docs/screenshots/v092/connection-sw.png)

## 5. Set background behavior
Choose timing, preparation, connection/battery conditions and quiet hours. New results arrive as modules complete.

![Background controls](docs/screenshots/v082/background.png)

## 6. Keep MeBot current
Open You → Software Updates. Automatic checks and optional Wi-Fi downloads prepare eligible releases; Android requires installation approval. This package is `mebot.nguli.com`, separate from older development builds.

![Software Updates](docs/screenshots/v082/software-updates.png)

## 7. Use optional tools
Alarm, Focus and Daily Check-in are together during onboarding and remain available independently of AI. Run a short alarm test before relying on it overnight.

![Daily tools](docs/screenshots/v082/daily-tools.png)

The in-app guide is skippable and replayable from Help. Captures use dated public content or synthetic state; [source and image notes](docs/screenshots/README.md).
{candidate_start}
'''
(ROOT/'START-HERE.md').write_text(start.rstrip()+'\n')
print(f'Rendered {v}: five product pages, {len(screens)} gallery captures, README and Start Here.')

# Keep historical previews readable, but make the current official release and website footer consistent.
for document in DOCS.rglob('*.html'):
    content=document.read_text()
    content=re.sub(r'<aside class="wrap release-hold">.*?</aside>', '', content, flags=re.S)
    content=re.sub(r'<!-- preview-[0-9]+:start -->.*?<!-- preview-[0-9]+:end -->', '', content, flags=re.S)
    content=re.sub(r'Official 0\.\d+\.\d+(?:-[A-Za-z0-9.]+)?',f'Official {official["version"]}',content)
    if '<aside class="beta-banner">' in content:
        depth=len(document.relative_to(DOCS).parent.parts); prefix='../'*depth
        banner=f'<aside class="beta-banner"><!-- current-release-notice -->MeBot {official["version"]} is the current official Android release. <a href="{prefix}download.html">Download, update and verify →</a></aside>'
        content=re.sub(r'<aside class="beta-banner">.*?</aside>',banner,content,count=1,flags=re.S)
    content=re.sub(r'(<footer\b.*?</footer>)',lambda m:re.sub(r'MeBot 0\.[0-9.]+(?:-beta\.[0-9]+)? ·',f'MeBot {site_footer_version} ·',m.group(1),flags=re.S),content,flags=re.S)
    content=content.replace('Dated public content · native captures','Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.').replace('Dated public stories · labelled synthetic examples · native captures','Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied.')
    content=re.sub(r'<p class="image-note">.*?</p>', '<p class="image-note">Native Android host captures with dated public stories and labelled synthetic examples. No live personal accounts or physical-phone capture is implied. <a id="image-original">Open original image</a></p>',content,flags=re.S)
    if document.name=='preview-095.html':
        content=re.sub(r'<aside class="release-archive(?: wrap)?">.*?</aside>', '', content, flags=re.S)
    elif document.name.startswith('preview-'):
        content=re.sub(r'<aside class="release-archive(?: wrap)?">.*?</aside>', '', content, flags=re.S)
        content=content.replace('<main', f'<aside class="release-archive wrap"><p><strong>Historical preview.</strong> The candidate state below records the original verification stage. <a href="releases.html">Browse historical files</a> or <a href="download.html">get the current official MeBot {official["version"]}</a>.</p></aside><main',1)
    if not published:
        depth=len(document.relative_to(DOCS).parent.parts);prefix='../'*depth
        status=f'<aside class="wrap release-hold"><p><strong>MeBot {v} is a prepared candidate, not yet publicly released or available through in-app updates.</strong> The current official release is MeBot {official["version"]}; no candidate download is offered here. <a href="{prefix}download.html">Get the official release</a> · <a href="{prefix}preview-095.html">Preview the story flow</a> · <a href="{prefix}{candidate_notes}">Candidate notes</a>.</p></aside>'
        content=content.replace('<main',status+'<main',1)
    elif document.name=='preview-095.html':
        replacements={
            '<title>MeBot 0.9.5 story-flow preview · MeBot</title>':'<title>MeBot 0.9.5 story-flow guide · MeBot</title>',
            'A prepared, unpublished preview of MeBot\'s story-grounded questions and in-app reading flow.':'An illustrated guide to MeBot\'s story-grounded questions and in-app reading flow.',
            'Prepared preview · 0.9.5 is not publicly released':'MeBot 0.9.5 · Story-flow guide',
            'MeBot is preparing a more direct path from reading to a thoughtful next question.':'MeBot keeps a direct path from reading to a thoughtful next question.',
            'Not yet released · no 0.9.5 download':f'Official release · {official.get("publishedDate", "published")}',
            'The current official update is MeBot 0.9.4.':f'The current official release is MeBot {official["version"]}.',
            'MeBot 0.9.5 is not available through the website or in-app updates. Do not install draft candidate files. This page describes the prepared experience only.':'MeBot 0.9.5 is available through the official release page and in-app updates for eligible installations.',
            'Download the official 0.9.4 release':f'Download the official {official["version"]} release',
            'MeBot 0.9.5 is still being prepared.':'MeBot 0.9.5 is the current official release.',
            '0.9.5 · candidate preview':'0.9.5 · sample content',
            'The prepared candidate retains package':'The release retains package',
            'Wait for an official release before changing your installation.':'Install over an existing production 0.9.x app; do not uninstall or clear its data. Older 0.8.x builds use a different signing identity.',
        }
        for old,new in replacements.items(): content=content.replace(old,new)
        content=content.replace('MeBot 0.9.5 story-flow preview','MeBot 0.9.5 story-flow guide')
        content=content.replace('candidate release notes','experience-first release notes')
        content=re.sub(r'<p>The release retains package <code>mebot\.nguli\.com</code>.*?</p>', '<p>The release retains package <code>mebot.nguli.com</code> and the existing 0.9.x production signing identity. Install over a production 0.9.x app; do not uninstall or clear data. Older 0.8.x builds are not an in-place update path.</p>', content, flags=re.S)
        content=content.replace('Want to try MeBot now?','Get the story-first update')
        content=content.replace(f'MeBot 0.9.4 is the current official Android release; 0.9.5 is still being prepared.',f'MeBot {official["version"]} is the current official Android release.')
        content=content.replace('Download MeBot 0.9.4 ↓','Download MeBot APK ↓')
    document.write_text(content)

# Candidate documents remain useful to review, while all download links stay on published releases only.
if not published:
    notice=f'> **MeBot {v} is a release candidate, not yet published.** The official download remains MeBot {official["version"]}; do not install draft files.\n\n'
    for output in (ROOT/'README.md', ROOT/'START-HERE.md'):
        content=output.read_text()
        if 'is a release candidate, not yet published' not in content:
            output.write_text(notice+content)
