#!/usr/bin/env python3
"""Add 0.8.4 candidate guidance while preserving tested 0.8.3 history."""
from pathlib import Path
import re
import struct
ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'docs'
shell=(D/'preview-083.html').read_text()
head=shell[:shell.index('<main')]
head=re.sub(r'<!-- preview-08[34]:start -->.*?<!-- preview-08[34]:end -->','',head,flags=re.S)
head=head.replace('0.8.3','0.8.4').replace('preview-083.html','preview-084.html')
foot=shell[shell.index('<footer'):]
body='''<main id="main" tabindex="-1"><section class="section wrap preview-intro"><p class="eyebrow">Android candidate · 0.8.4</p><h1>MeBot 0.8.4</h1><p class="lead">A simpler star action, fullscreen media and a clearer first-run tour.</p><div class="preview-status"><strong>Preview, not a published APK</strong><p>This candidate passed 829 automated tests and both APK builds, building on the tested 0.8.3 update. Original-identity signing and physical-phone acceptance are still required before an official upgrade is offered. The official download remains 0.8.2.</p><div class="actions"><a class="button" href="download.html">Get official 0.8.2</a><a class="button secondary" href="preview-083.html">Explore the 0.8.3 foundation</a></div></div></section>
<section class="section wrap hairline" id="changes"><h2>What changes in 0.8.4</h2><div class="preview-guide"><article><span class="preview-number">01</span><div><h3>A star beside the bookmark</h3><p>Tap the star to follow a story. Tap the bookmark to save it. The star stays an icon: no extra label, follow-up panel or manage button on the feed card. Open <strong>Saved → Following</strong> when you want to manage cadence or pause a follow-up.</p><p>These remain separate actions. Follow-ups still use normal feed refresh and your existing request allowance; new coverage is not guaranteed.</p></div></article><article><span class="preview-number">02</span><div><h3>Give media the full screen</h3><p>Open supported image and media previews in a fullscreen viewer, with an explicit close action and a route back to the original source. Images, direct video and audio use the app’s fullscreen controls. For YouTube, use its own player fullscreen control; the same player stays active in the native shell.</p><p>Quality, captions, seeking and other controls depend on the source and the player. MeBot does not invent a quality selector when the source offers none. Some providers restrict embedding or playback; use the original source when needed. Availability still depends on connectivity and the provider.</p></div></article><article><span class="preview-number">03</span><div><h3>Finish the tour, then start reading</h3><p>Completing onboarding takes you straight to the feed, rather than immediately opening a second guide. You can replay the quick guide from <strong>Help</strong> whenever you need it.</p><p>The guide now explains save versus follow and source-dependent fullscreen controls in English and Kiswahili. The library’s empty-state guidance matches the selected Saved or Following tab, and its search label describes what can actually be searched.</p></div></article></div></section>
<section class="section wrap hairline" lang="sw"><p class="eyebrow">Kwa kifupi</p><h2>Rahisi kutumia, bila vitufe vya ziada</h2><p>Nyota iko karibu na alama ya kuhifadhi. Gusa nyota kufuatilia habari; nenda kwenye sehemu ya ulivyohifadhi unapotaka kusimamia ufuatiliaji. Fungua picha au video zinazokubalika kwenye skrini nzima. Ubora wa video na vidhibiti vingine hutegemea chanzo. Ukimaliza utangulizi, unaenda moja kwa moja kwenye habari; mwongozo mfupi unapatikana kwenye Usaidizi.</p><p>Hili bado ni toleo la majaribio. Upakuaji rasmi uliopo ni 0.8.2.</p></section>
<section class="section wrap hairline"><h2>Release history and verification</h2><p>The 0.8.3 foundation passed 817 automated tests and built both APK variants. It is preserved as a tested candidate, not a signed official release. The separate 0.8.4 gate passed 829 tests (181 core, 479 Android and 169 service), with zero failures, errors or skips. Both APK variants built. Lint reported zero errors, 215 debug warnings, 216 release warnings and one hint per variant.</p><p>Native preview images demonstrate layout with controlled test content. They do not establish live account authorization, real streaming, device installation, notification delivery or overnight alarms. Device speech input and installed-voice read-aloud remain distinct from full-duplex realtime voice.</p><div class="actions"><a class="button secondary" href="RELEASE-0.8.4.md">0.8.4 candidate notes</a><a class="text-link" href="RELEASE-0.8.3.md">0.8.3 tested candidate →</a><a class="text-link" href="guide.html">Official 0.8.2 guide →</a></div></section></main>'''
# Only include genuine reviewed 0.8.4 captures that have been added to this directory.
captures=[('feed','Simpler story actions','Native feed with the icon-only star next to its bookmark; controlled test content.'),('saved','Manage in your library','Follow-up cadence stays in Saved → Following, separate from the feed card.'),('media','Fullscreen media','Native fullscreen image viewer with pinch, pan and reset controls. Explicitly labelled local image fixture; not a video-playback capture.')]
figures=[]
for name,title,caption in captures:
    p=D/'screenshots/v084'/f'{name}.png'
    if not p.exists(): continue
    w,h=struct.unpack('>II',p.read_bytes()[16:24])
    figures.append(f'<figure class="shot"><a href="screenshots/v084/{name}.png" data-lightbox data-title="{title}" aria-label="Enlarge: {title}"><img src="screenshots/v084/{name}.png" width="{w}" height="{h}" loading="lazy" alt="{caption}"></a><figcaption><span class="shot-version">0.8.4 candidate · native render</span><strong>{title}</strong><span>{caption}</span></figcaption></figure>')
if figures:
    gallery='<section class="section wrap hairline"><h2>Native candidate preview</h2><div class="screen-grid">'+''.join(figures)+'</div><p class="micro"><a href="screenshots/v084/README.md">Capture provenance</a></p></section>'
    body=body.replace('<section class="section wrap hairline" id="changes">',gallery+'<section class="section wrap hairline" id="changes">')
(D/'preview-084.html').write_text(head+body+foot)
for p in [*D.glob('*.html'),*(D/'guides').glob('*.html')]:
    if p.name=='preview-084.html': continue
    t=re.sub(r'<!-- preview-08[34]:start -->.*?<!-- preview-08[34]:end -->','',p.read_text(),flags=re.S)
    rel='../' if p.parent.name=='guides' else ''
    banner=f'<!-- preview-084:start --><aside class="preview-banner"><strong>0.8.4 preview</strong> · Simpler stars, fullscreen media and a clearer tour. <a href="{rel}preview-084.html">See changes and release status →</a></aside><!-- preview-084:end -->'
    p.write_text(t.replace('</header>','</header>'+banner,1))
for p,link in [(ROOT/'README.md','docs/RELEASE-0.8.4.md'),(ROOT/'START-HERE.md','docs/RELEASE-0.8.4.md'),(D/'USER-GUIDE.md','RELEASE-0.8.4.md')]:
    t=re.sub(r'<!-- preview-084:start -->.*?<!-- preview-084:end -->\n*','',p.read_text(),flags=re.S)
    p.write_text(f'<!-- preview-084:start -->\n> **0.8.4 candidate:** Simpler story actions, fullscreen media and a clearer tour; 829 automated tests passed. [Changes and release limits]({link}). The official download remains **0.8.2**.\n<!-- preview-084:end -->\n\n'+t)
print('Updated 0.8.4 preview; preserved 0.8.3 history and official 0.8.2 download.')
