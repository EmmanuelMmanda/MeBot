#!/usr/bin/env python3
"""Test current static website rendering, accessibility, download links and font loading."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from playwright.sync_api import sync_playwright
import hashlib
import json
import os
import re
import shutil
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
OUT = ROOT / 'website-evidence'
OUT.mkdir(exist_ok=True)
r = json.loads((DOCS / 'assets/release.json').read_text())
public_releases = [x for x in r.get('releases', []) if x.get('status') == 'published' and x.get('apk') is True]
official = r if r.get('status') == 'published' and r.get('apk') is True else public_releases[-1]
latest_release = 'https://github.com/EmmanuelMmanda/MeBot/releases/latest'
apk_download = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{official['tag']}/{official['filename']}"
screens = json.loads((DOCS / 'assets/screens.json').read_text())
question_screen = next(s for s in screens if s['group'] == 'ask')
questions = question_screen.get('suggestedQuestions', [])
current_guides = ['guide.html', 'user-manual.html'] + [f'guides/{p.name}' for p in sorted((DOCS / 'guides').glob('*.html')) if p.name != 'release-082.html']

def required_font(page, guide=False):
    metrics = page.evaluate("""async guide => {
      const regular = await document.fonts.load('400 16px Figtree');
      const semibold = await document.fonts.load('600 20px Figtree');
      await document.fonts.ready;
      const face = [...document.fonts].find(f => f.family.replaceAll('"', '') === 'Figtree');
      const selectors = ['body','h1','h2','h3','p','button','.button','.header-download'];
      if (guide) selectors.push('.article-section p','.article-section li','.guide-figure figcaption');
      else selectors.push('.shot figcaption','.shot figcaption strong');
      const family = selectors.filter(s => document.querySelector(s)).map(s => [s,getComputedStyle(document.querySelector(s)).fontFamily]);
      const line = selector => document.querySelector(selector) ? getComputedStyle(document.querySelector(selector)).lineHeight : null;
      const bodyLine = line('body');
      // Measure the same semantic body copy for line height and weight. A selector
      // list such as '.hero-lead,.lead,main p' matches in document order, so the
      // earlier eyebrow can win even when a lead paragraph is present.
      const paragraph = guide
        ? (document.querySelector('.article-section p') || document.querySelector('main .lead') || document.querySelector('main p:not(.eyebrow)'))
        : (document.querySelector('.hero-lead') || document.querySelector('main .lead') || document.querySelector('main p:not(.eyebrow)'));
      const paragraphStyle = paragraph ? getComputedStyle(paragraph) : null;
      const paragraphLine = paragraphStyle?.lineHeight ?? null;
      const captionLine = line(guide?'.guide-figure figcaption':'.shot figcaption');
      return {status:face?.status ?? 'missing', family,
        regularFaces:regular.length, semiboldFaces:semibold.length,
        h1:getComputedStyle(document.querySelector('h1')).fontWeight,
        h2:document.querySelector('h2') ? getComputedStyle(document.querySelector('h2')).fontWeight : null,
        h3:document.querySelector('h3') ? getComputedStyle(document.querySelector('h3')).fontWeight : null,
        button:document.querySelector('.button') ? getComputedStyle(document.querySelector('.button')).fontWeight : getComputedStyle(document.querySelector('.header-download')).fontWeight,
        headerButtonWeight:document.querySelector('.header-download') ? getComputedStyle(document.querySelector('.header-download')).fontWeight : null,
        paragraphWeight:paragraphStyle?.fontWeight ?? null,
        paragraphClasses:paragraph ? [...paragraph.classList] : [],
        captionWeight:document.querySelector(guide?'.guide-figure figcaption':'.shot figcaption') ? getComputedStyle(document.querySelector(guide?'.guide-figure figcaption':'.shot figcaption')).fontWeight : null,
        captionTitle:document.querySelector('.shot figcaption strong') ? getComputedStyle(document.querySelector('.shot figcaption strong')).fontWeight : null,
        bodyWeight:getComputedStyle(document.body).fontWeight,
        body:bodyLine, p:paragraphLine, caption:captionLine};
    }""", guide)
    assert metrics['status'] == 'loaded', metrics
    assert metrics['regularFaces'] > 0 and metrics['semiboldFaces'] > 0, metrics
    assert all('Figtree' in value for _,value in metrics['family']), metrics
    assert metrics['h1'] == '600', metrics
    assert metrics['h2'] in (None,'600'), metrics
    assert metrics['h3'] in (None,'600'), metrics
    assert metrics['button'] == '600', metrics
    assert metrics['headerButtonWeight'] in (None,'600'), metrics
    assert metrics['bodyWeight'] == '400', metrics
    assert metrics['paragraphWeight'] in (None,'400'), metrics
    assert 'eyebrow' not in metrics['paragraphClasses'], metrics
    assert metrics['captionWeight'] in (None,'400'), metrics
    if metrics['captionTitle'] is not None:
        assert metrics['captionTitle'] == '600', metrics
    assert metrics['body'] == '24px', metrics
    # A 17px mobile hero lead at 1.5 line-height resolves to 25.5px.
    assert metrics['p'] in ('24px', '25.5px', '27px', '28.5px'), metrics
    if guide and metrics['caption']:
        assert metrics['caption'] in ('18px','19.8px'), metrics
    elif not guide and metrics['caption']:
        assert metrics['caption'] in ('21px','normal'), metrics

def github_release_payload():
    receipt = json.loads((DOCS / official['integrityFile']).read_text())
    return {
        'tag_name': official['tag'],
        'name': f"MeBot {official['version']} release",
        'draft': False,
        'prerelease': False,
        'published_at': official['publishedAt'],
        'html_url': f"https://github.com/EmmanuelMmanda/MeBot/releases/tag/{official['tag']}",
        'body': f"Release {official['version']} notes. Published Android app and signed verification.",
        'assets': [
            {'name': item['name'], 'state': 'uploaded', 'size': item['size'],
             'digest': item['digest'], 'browser_download_url': item['browser_download_url']}
            for item in receipt['assets']
        ],
    }


def fetch_live_latest_release():
    url = 'https://api.github.com/repos/EmmanuelMmanda/MeBot/releases/latest'
    request = Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'MeBot-website-release-check',
    })
    try:
        with urlopen(request, timeout=8) as response:
            if response.status != 200:
                return response.status, None
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, None
    except Exception:
        return None, None

def route_live_release(context, payload):
    context.route('https://api.github.com/repos/EmmanuelMmanda/MeBot/releases/latest',
                  lambda route: route.fulfill(status=200, content_type='application/json',
                                              headers={'access-control-allow-origin': '*'},
                                              body=json.dumps(payload)))

def route_release(context, scenario='valid'):
    def handler(route):
        if scenario == 'offline':
            route.abort()
            return
        if scenario == 'rate':
            route.fulfill(status=403, content_type='application/json',
                          headers={'access-control-allow-origin': '*'},
                          body=json.dumps({'message':'API rate limit exceeded'}))
            return
        if scenario == 'missing':
            payload=github_release_payload()
            payload['assets']=[x for x in payload['assets'] if not x['name'].endswith('.apk')]
        elif scenario == 'missing_digest':
            payload=github_release_payload()
            apk=next(item for item in payload['assets'] if item['name']==official['filename'])
            apk.pop('digest', None)
        elif scenario == 'invalid':
            payload=github_release_payload()
            apk=next(item for item in payload['assets'] if item['name']==official['filename'])
            apk['digest']='sha1:invalid'
        elif scenario == 'prerelease':
            payload=github_release_payload()
            payload['prerelease']=True
        else:
            payload=github_release_payload()
        route.fulfill(status=200, content_type='application/json',
                      headers={'access-control-allow-origin': '*'},
                      body=json.dumps(payload))

    context.route('https://api.github.com/repos/EmmanuelMmanda/MeBot/releases/latest', handler)

def primary_downloads(page):
    header = page.locator('.header-download')
    assert header.count() == 1 and header.is_visible()
    assert header.inner_text().strip() == 'Download latest version'
    assert header.get_attribute('href').endswith('download.html')
    if page.locator('[data-release-page]').count():
        link=page.locator('[data-release-apk]')
        assert link.count()==1 and link.is_visible()
        assert link.inner_text().strip()=='Download latest version'
        assert link.get_attribute('href')==apk_download
    else:
        assert page.locator('[data-release-apk]').count()==0

server = ThreadingHTTPServer(('127.0.0.1',0),partial(SimpleHTTPRequestHandler,directory=str(DOCS)))
threading.Thread(target=server.serve_forever,daemon=True).start()
base = f'http://127.0.0.1:{server.server_port}/'
checks=[]

def load(page,name,wait_until='networkidle'):
    if not os.environ.get('MEBOT_OFFLINE_BROWSER'):
        return page.goto(base+name,wait_until=wait_until)
    import base64
    path=(DOCS/name).resolve()
    assert path.is_relative_to(DOCS.resolve())
    text=path.read_text()
    def uri(item):
        item=item.resolve(); assert item.is_relative_to(DOCS.resolve())
        mime={'.svg':'image/svg+xml','.ttf':'font/ttf','.png':'image/png'}.get(item.suffix,'application/octet-stream')
        return 'data:'+mime+';base64,'+base64.b64encode(item.read_bytes()).decode()
    def inline_css(m):
        css_path=(path.parent/m.group(1)).resolve()
        assert css_path.is_relative_to(DOCS.resolve())
        css=css_path.read_text()
        def font_url(u): return uri((css_path.parent/u.group(1)).resolve())
        return '<style>'+re.sub(r'url\(["\']?([^)"\']+)["\']?\)',font_url,css)+'</style>'
    text=re.sub(r'<link rel="stylesheet" href="([^"]+)"[^>]*>',inline_css,text)
    def inline_script(m):
        script=(path.parent/m.group(1)).resolve(); assert script.is_relative_to(DOCS.resolve())
        return '<script>'+script.read_text()+'</script>'
    text=re.sub(r'<script src="([^"]+)"[^>]*></script>',inline_script,text)
    scripts=re.findall(r'<script>(.*?)</script>',text,re.S)
    text=re.sub(r'<script>.*?</script>','',text,flags=re.S)
    text=re.sub(r'(src|href)="([^"\s]+\.(?:png|svg))"',lambda m:m.group(1)+'="'+uri((path.parent/m.group(2)).resolve())+'"',text)
    text=text.replace('</body>', '<script>'+"\n".join(scripts)+'</script></body>')
    page.set_content(text,wait_until='load')

try:
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=os.environ.get('MEBOT_CHROMIUM') or shutil.which('chromium'))
        asset_context=browser.new_context()
        font_response=asset_context.request.get(base+'assets/figtree.ttf')
        assert font_response.status==200, f'Figtree asset HTTP status: {font_response.status}'
        assert 'font' in font_response.headers.get('content-type','').lower(), font_response.headers.get('content-type')
        font_bytes=font_response.body()
        assert len(font_bytes)>50_000
        assert hashlib.sha256(font_bytes).digest()==hashlib.sha256((ROOT/'app/src/main/res/font/figtree.ttf').read_bytes()).digest()
        checks.append('Figtree asset responds 200 with byte-identical app font')
        asset_context.close()
        # Landing and download pages at desktop, tablet, phone, and narrow phone sizes in both themes.
        for width,height in [(1440,1000),(768,1024),(390,844),(320,740)]:
            for theme in ('light','dark'):
                context=browser.new_context(viewport={'width':width,'height':height},color_scheme=theme,reduced_motion='reduce')
                route_release(context)
                page=context.new_page(); errors=[]
                page.on('pageerror',lambda e:errors.append(str(e)))
                for name in ('index.html','screenshots.html','download.html','releases.html','tour.html','guide.html','user-manual.html'):
                    load(page,name)
                    assert page.locator('h1').count()==1,name
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'),f'Overflow {width} {theme} {name}'
                    assert not errors,errors
                    primary_downloads(page)
                    required_font(page,guide=False)
                    body=page.locator('main').inner_text().lower()
                    assert '0.9.9 is available' not in body
                    assert 'read what matters' not in body and 'read with purpose' not in body
                    if name=='index.html':
                        for required in ('about you','modules','saved','ai connection','settings','notifications','notes','software updates','five','kiswahili','opportunities','original read','theme'):
                            assert required in body,(required,body)
                        assert 'created' in body
                        for question in questions: assert question in page.locator('main').inner_text(),question
                        assert question_screen['sampleItem'] in page.locator('main').inner_text()
                        assert 'screen images are examples' in body and 'sample content' in body and 'not a live account result' in body
                        assert page.locator('a[href="user-manual.html"]').count() >= 1
                    if name=='screenshots.html':
                        assert page.locator('#screen-gallery .shot').count()==len(screens)
                        assert page.locator('#screen-gallery .shot-version').count()==0
                        assert 'screen images are examples' in body and 'sample content' in body and 'not a live account result' in body
                        assert page.locator('.archived-shot').count()==0
                        assert 'additional app screens' not in body and 'earlier native captures' not in body
                        for capture in screens:
                            figure=page.locator(f'#screen-gallery .shot:has(img[src="{capture["file"]}"])')
                            assert figure.count()==1
                            caption=figure.locator('figcaption span').first.inner_text()
                            assert caption.startswith(('Example','Sample','Mfano')),caption
                    if name=='download.html':
                        assert page.locator('[data-release-status]').inner_text()=='Latest published Android release'
                        assert page.locator('[data-release-name]').inner_text()=='MeBot release'
                        assert page.locator('[data-release-date]').get_attribute('datetime')
                        assert page.locator('[data-release-sha]').inner_text()==official['sha256']
                        assert official['sizeBytes'] / 1000000 > 0
                        assert page.locator('[data-release-assets] a').count() >= 4
                        assert not re.search(r'MeBot\s+0\.\d+',page.locator('main').inner_text())
                        assert f"MeBot {official['version']}" in page.locator('footer').inner_text()
                    for img in page.locator('.shot:visible img').all():
                        img.scroll_into_view_if_needed(); img.evaluate('img => img.decode()')
                        assert img.evaluate('img => img.naturalWidth > 0')
                    page.evaluate('scrollTo(0,0)')
                    if width in (1440,390) and theme=='light':
                        page.screenshot(path=str(OUT/f'{name[:-5]}-{width}.png'),full_page=name=='index.html')
                    checks.append(f'{width}px {theme}: {name}, font, layout, CTA, images')
                # Current guide body typography, true memory boundary and exact unversioned primary link.
                if width in (1440,390):
                    for name in current_guides:
                        load(page,name)
                        assert page.locator('h1').count()==1,name
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'),f'Guide overflow {width} {theme} {name}'
                        primary_downloads(page); required_font(page,guide=True)
                        main=page.locator('main').inner_text()
                        assert not re.search(r'MeBot\s+0\.9\.\d',main),name
                        if name.endswith('install.html'):
                            for step in ('three stages','Continue with ChatGPT','Continue with public feeds',
                                         'Add personal context','Choose feed topics','Start my feed'):
                                assert step in main,(step,main)
                        if name.endswith('personal-context.html'):
                            assert 'does not automatically read or sync ChatGPT memory or conversation history after sign-in' in main
                            assert 'manual, reviewed import' in main
                            assert 'Choose feed topics' in main and 'Find your interests' in main
                            assert 'Selections save on this device and do not start an AI request' in main
                            assert page.locator('#after-import').count()==1
                        if name.endswith('feed-and-ai.html'):
                            assert 'up to eight public stories per module' in main
                            assert 'five distinct questions' in main and 'More angles' in main and 'appended rather than replacing' in main
                        if name.endswith('troubleshooting.html'):
                            assert 'After import, I do not see my notes.' in main
                        checks.append(f'{width}px {theme}: {name} Figtree and guide copy')
                context.close()

        # Search filters, image dialog keyboard flow, theme and mobile navigation.
        context=browser.new_context(viewport={'width':390,'height':844},reduced_motion='reduce')
        page=context.new_page(); load(page,'screenshots.html')
        group=screens[0]['group']; page.locator(f'[data-screen-filter="{group}"]').click()
        assert page.locator(f'[data-screen-filter="{group}"]').get_attribute('aria-pressed')=='true'
        assert page.locator('[data-screen-filter][aria-pressed="true"]').count()==1
        count=sum(s['group']==group for s in screens)
        assert page.locator('#screen-gallery .shot:visible').count()==count
        assert page.locator('#screen-count').inner_text()==f'{count} '+('screen' if count==1 else 'screens')
        opener=page.locator('#screen-gallery .shot:visible a').first; opener.focus(); page.keyboard.press('Enter')
        assert page.locator('#image-dialog').is_visible(); page.keyboard.press('Escape')
        assert not page.locator('#image-dialog').is_visible() and opener.evaluate('el => document.activeElement === el')
        page.get_by_role('button',name='All screens',exact=True).click()
        theme=page.locator('[data-theme-select]').first; theme.select_option('dark')
        assert page.locator('html').get_attribute('data-theme')=='dark'
        theme.select_option('light'); assert page.locator('html').get_attribute('data-theme')=='light'
        page.get_by_role('button',name='Menu',exact=False).click(); assert page.locator('#primary-nav').is_visible()
        page.keyboard.press('Escape'); assert page.locator('[data-toggle]').get_attribute('aria-expanded')=='false'
        checks += ['Gallery filters and count','Keyboard image viewer restores focus','Theme selector and mobile menu work']
        context.close()

        # Without JavaScript, all download paths remain safe and lead to the release page.
        context=browser.new_context(java_script_enabled=False,viewport={'width':390,'height':844})
        page=context.new_page()
        for name in ('index.html','download.html','guide.html','guides/personal-context.html'):
            load(page,name,wait_until='load')
            header=page.locator('.header-download')
            assert header.inner_text().strip()=='Download latest version' and header.get_attribute('href').endswith('download.html')
            if name=='download.html':
                link=page.locator('[data-release-apk]')
                assert link.inner_text().strip()=='Open latest GitHub release'
                assert link.get_attribute('href')==latest_release
                assert page.locator('noscript').inner_text()
        checks.append('No-JavaScript fallback points to GitHub releases without a stale APK')
        context.close()

        # Release API error paths fail closed and never reuse the manifest APK.
        live_status, live_payload = fetch_live_latest_release()
        if live_payload is not None:
            context = browser.new_context(viewport={'width':390,'height':844})
            route_live_release(context, live_payload)
            page = context.new_page(); load(page,'download.html')
            tag = str(live_payload.get('tag_name') or '')
            match = re.fullmatch(r'v(\d+\.\d+\.\d+)', tag)
            live_apk = next((item for item in live_payload.get('assets', []) if item.get('name') == f"mebot-{match.group(1)}.apk"), None) if match else None
            expected_url = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{tag}/{live_apk.get('name')}" if live_apk else None
            digest_value = live_apk.get('digest') if live_apk else None
            digest_present = digest_value is not None and str(digest_value).strip() != ''
            digest_match = re.fullmatch(r'sha256:([a-fA-F0-9]{64})', str(digest_value or ''))
            official_release = f'https://github.com/EmmanuelMmanda/MeBot/releases/tag/{tag}'
            api_asset_ok = bool(match and live_payload.get('draft') is False and live_payload.get('prerelease') is False and live_payload.get('published_at') and live_payload.get('html_url') == official_release and live_apk and live_apk.get('state') == 'uploaded' and isinstance(live_apk.get('size'), int) and live_apk['size'] > 0 and live_apk.get('browser_download_url') == expected_url and (not digest_present or digest_match))
            live_button = page.locator('[data-release-apk]')
            if api_asset_ok:
                assert live_button.inner_text().strip() == 'Download latest version'
                assert live_button.get_attribute('href') == expected_url
                assert page.locator('[data-release-info]').is_visible()
                assert page.locator('[data-release-sha]').inner_text() == (digest_match.group(1).lower() if digest_match else 'Not provided by GitHub')
            else:
                assert live_button.inner_text().strip() == 'Open latest GitHub release'
                assert live_button.get_attribute('href') == latest_release
                assert page.locator('[data-release-info]').is_hidden()
            context.close()
            checks.append('Actual latest GitHub release API response rendered through the download page')
        else:
            checks.append(f'Live GitHub latest release probe skipped (HTTP {live_status or "network unavailable"}); API fixtures still run')

        # GitHub's digest field is optional. Keep the official direct asset link and state plainly when no digest was supplied.
        context=browser.new_context(viewport={'width':390,'height':844})
        route_release(context,'missing_digest')
        page=context.new_page(); load(page,'download.html')
        assert page.locator('[data-release-error]').is_hidden()
        assert page.locator('[data-release-info]').is_visible()
        assert page.locator('[data-release-apk]').inner_text().strip()=='Download latest version'
        assert page.locator('[data-release-apk]').get_attribute('href')==apk_download
        assert page.locator('[data-release-sha]').inner_text()=='Not provided by GitHub'
        context.close()
        checks.append('Missing optional GitHub SHA-256 digest keeps the exact official APK link and is disclosed')

        for scenario,expected in (
            ('missing','does not include a verifiable Android APK'),
            ('invalid','does not include a verifiable Android APK'),
            ('rate','limiting release checks'),
            ('offline','could not be checked'),
            ('prerelease','could not be verified'),
        ):
            context=browser.new_context(viewport={'width':390,'height':844})
            route_release(context,scenario)
            page=context.new_page()
            load(page,'download.html')
            assert page.locator('[data-release-error]').is_visible(),scenario
            assert expected.lower() in page.locator('[data-release-error]').inner_text().lower(),scenario
            link=page.locator('[data-release-apk]')
            assert link.inner_text().strip()=='Open latest GitHub release',scenario
            assert link.get_attribute('href')==latest_release,scenario
            assert page.locator('[data-release-info]').is_hidden(),scenario
            assert not re.search(r'MeBot\s+0\.\d+',page.locator('main').inner_text()),scenario
            context.close()
        checks.append('Missing APK, invalid digest, rate limit, network failure and invalid release are handled safely')

        # Historical previews remain accessible as dated records and never link their candidate APKs.
        context=browser.new_context(viewport={'width':390,'height':844})
        page=context.new_page()
        for name, marker in [('preview-083.html','Preview, not a published APK'),('preview-084.html','Preview, not a published APK'),('preview-085.html','Preview, not a published APK'),('preview-090.html','not a published official APK')]:
            load(page,name)
            assert marker in page.locator('main').inner_text()
        checks.append('Historical preview records remain separate from the latest download')
        context.close(); browser.close()
finally:
    server.shutdown()
(OUT/'browser-results.json').write_text(json.dumps({'status':'passed','checks':checks},indent=2)+'\n')
print(f'{len(checks)} browser scenarios passed.')
