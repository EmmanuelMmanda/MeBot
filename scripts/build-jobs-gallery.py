#!/usr/bin/env python3
"""Render independently proven native Jobs captures; never synthesize app screens."""
from pathlib import Path
from html import escape
import hashlib
import json
import re
import struct

ROOT = Path(__file__).resolve().parents[1]
START = '<!-- JOBS NATIVE GALLERY START -->'
END = '<!-- JOBS NATIVE GALLERY END -->'

def validate(data, docs):
    assert data['schemaVersion'] == 1
    assert data['syntheticData'] is True and data['liveAiRequest'] is False
    assert data['version'].endswith('-debug')
    for key, size in [('sourceHead',40),('testedMerge',40),('debugApkSha256',64),('testApkSha256',64)]:
        assert re.fullmatch('[0-9a-f]{'+str(size)+'}',data[key]), key
    assert len(data['screens']) >= 6
    ids, hashes = set(), set()
    for s in data['screens']:
        assert re.fullmatch('[a-z][a-z0-9-]*',s['id']) and s['id'] not in ids
        ids.add(s['id'])
        path = (docs/s['path']).resolve()
        assert path.is_relative_to((docs/'screenshots/v0101-jobs').resolve())
        assert path.suffix == '.png'
        b=path.read_bytes()
        assert b[:8] == b'\x89PNG\r\n\x1a\n'
        assert hashlib.sha256(b).hexdigest() == s['sha256'] and s['sha256'] not in hashes
        hashes.add(s['sha256'])
        assert struct.unpack('>II',b[16:24]) == (s['width'],s['height'])
        assert s['visuallyReviewed'] is True and s['theme'] in ('light','dark')
        assert s['captureMethod'].startswith('native ') and s['locale'] == 'en'
        assert s['title'] and s['caption'] and s['evidencePath']
    return data

def render(data):
    cards=[]
    for n,s in enumerate(data['screens'],1):
        p=escape(s['path'],quote=True)
        cards.append(f'''<figure class="jobs-native-shot"><a href="{p}" aria-label="Open full-size capture: {escape(s['title'],quote=True)}"><img src="{p}" width="{s['width']}" height="{s['height']}" alt="{escape(s['title']+': '+s['caption'],quote=True)}" loading="lazy" decoding="async"></a><figcaption><span class="eyebrow">{n:02d} / Native Android</span><h3>{escape(s['title'])}</h3><p>{escape(s['caption'])}</p></figcaption></figure>''')
    return START+'''
<section class="wrap jobs-section" id="native-gallery" aria-labelledby="jobs-gallery-title"><div class="jobs-section-heading"><p class="eyebrow">Inside Jobs</p><h2 id="jobs-gallery-title">From a direction to a next step.</h2><p>Six real native screens from the tested 0.10.1 debug build. Sample jobs, notes and drafts are synthetic, not live vacancies. No AI request was sent. Open any capture at full size.</p></div><div class="jobs-native-grid">'''+''.join(cards)+'''</div><p class="micro">Phone light, dark with larger text, and tablet views. <a href="screenshots/v0101-jobs/README.md">Capture record</a> · <a href="assets/jobs-screens.json">Exact build and image provenance</a>. See the <a href="download.html">download page</a> for the latest published release.</p></section>
'''+END

def main():
    manifest=ROOT/'docs/assets/jobs-screens.json'
    if not manifest.exists():
        return
    data=validate(json.loads(manifest.read_text()),ROOT/'docs')
    page=ROOT/'docs/jobs.html'
    text=page.read_text()
    assert text.count(START)==text.count(END)==1, 'Gallery markers must exist exactly once'
    before,rest=text.split(START)
    _,after=rest.split(END)
    page.write_text(before+render(data)+after)
    print(f"Rendered {len(data['screens'])} verified native Jobs captures")

if __name__ == '__main__':
    main()
