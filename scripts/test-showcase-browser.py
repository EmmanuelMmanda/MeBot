#!/usr/bin/env python3
"""Test only the locally served website; never interact with account sign-in."""
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import os
import shutil
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'website-evidence'
OUT.mkdir(exist_ok=True)
class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass
server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Quiet, directory=str(ROOT / 'docs')))
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://127.0.0.1:{server.server_port}/'
r = json.loads((ROOT / 'docs/assets/release.json').read_text())
published = r["status"] == "published"
official = r if published else next(x for x in r['releases'] if x.get('status')=='published' and x.get('apk') is True)
apk = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{official['tag']}/{official['filename']}"
guide_pages = ['guide.html','guides/install.html','guides/personal-context.html','guides/feed-and-ai.html','guides/troubleshooting.html']
checks = []
def load(page,name,**kwargs):
    if not os.environ.get('MEBOT_OFFLINE_BROWSER'):
        return page.goto(base+name,**kwargs)
    # Purely in-memory fixture: no network or browser security settings are changed.
    import base64,re
    path=ROOT/'docs'/name
    text=path.read_text()
    def uri(rel):
        item=(path.parent/rel).resolve()
        assert item.is_relative_to((ROOT/'docs').resolve())
        kind='image/svg+xml' if item.suffix=='.svg' else 'image/png'
        return 'data:'+kind+';base64,'+base64.b64encode(item.read_bytes()).decode()
    text=re.sub(r'<link rel="stylesheet" href="([^"]+)"[^>]*>',lambda m:'<style>'+(path.parent/m.group(1)).read_text()+'</style>',text)
    text=re.sub(r'<script src="([^"]+)"[^>]*></script>',lambda m:'<script>'+ (path.parent/m.group(1)).read_text()+'</script>',text)
    text=re.sub(r'(src|href)="([^"\s]+\.(?:png|svg))"',lambda m:m.group(1)+'="'+uri(m.group(2))+'"',text)
    # Defer external script equivalents until the DOM exists.
    scripts=re.findall(r'<script>(.*?)</script>',text,re.S)
    text=re.sub(r'<script>.*?</script>','',text,flags=re.S)
    page.set_content(text+'<script>'+"\n".join(scripts)+'</script>',wait_until='load')

try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('MEBOT_CHROMIUM') or shutil.which('chromium'))
        for width, height in [(1440, 1000), (768, 1024), (390, 844), (320, 740)]:
            for theme in ['light', 'dark']:
                context = browser.new_context(viewport={'width': width, 'height': height}, color_scheme=theme, reduced_motion='reduce')
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda e: errors.append(str(e)))
                for name in ['index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html', 'preview-083.html', 'preview-084.html', 'preview-085.html', 'preview-090.html']:
                    load(page,name,wait_until='networkidle')
                    assert page.locator('h1').count() == 1, name
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Overflow {width} {theme} {name}'
                    assert not errors, errors
                    if name == 'preview-083.html':
                        assert 'Preview, not a published APK' in page.locator('main').inner_text()
                        assert page.locator('a[href*="/releases/download/v0.8.3/"]').count() == 0
                        assert page.locator('#kiswahili').get_attribute('lang') == 'sw'
                        assert page.locator('.preview-jumps a').count() == 6
                    if name == 'preview-084.html':
                        assert 'Preview, not a published APK' in page.locator('main').inner_text()
                        assert page.locator('a[href*="/releases/download/v0.8.4/"]').count() == 0
                        assert page.locator('main [lang="sw"]').count() == 1
                        assert page.locator('a[href="preview-083.html"]').count() >= 1
                    if name == 'preview-085.html':
                        assert 'Preview, not a published APK' in page.locator('main').inner_text()
                        assert '150 words and 1,000 characters' in page.locator('main').inner_text()
                        assert page.locator('a[href*="/releases/download/v0.8.5/"]').count() == 0
                        assert page.locator('main [lang="sw"]').count() == 1
                    if name == 'preview-090.html':
                        assert 'not a published official APK' in page.locator('main').inner_text()
                        assert page.locator('a[href*="/releases/download/v0.9.0/"]').count() == 0
                        assert page.locator('.shot img[src$="onboarding-en-chooser.png"]').count() == 1
                        assert page.locator('.shot img[src$="onboarding-sw-chooser.png"]').count() == 1
                        assert page.locator('.shot img[src*="small-sw-large"]').count() == 0
                    if name == 'screenshots.html':
                        assert page.locator('#screen-gallery .shot img[src*="imported-notes-en.png"]').count() == 1
                        assert page.locator('#screen-gallery .shot img[src*="imported-notes-sw.png"]').count() == 1
                        assert page.locator('#screen-gallery .shot img[src*="320dp-130percent"]').count() == 0
                        assert 'Accessibility stress evidence' in page.locator('#image-notes').inner_text()
                    if name == 'download.html':
                        for field in [r['filename'],r['sourceFilename'],r['verificationFilename'],r['sourceCommit'],r['sourceTree'],r['certificateSha256']]:
                            assert field in page.locator('.release-metadata').inner_text()
                    for link in page.locator('[data-release-apk]').all():
                        assert link.is_visible() and link.get_attribute('href') == apk
                    for im in page.locator('.shot:visible img').all():
                        im.scroll_into_view_if_needed()
                        im.evaluate('img => img.decode()')
                        assert im.evaluate('img => img.naturalWidth > 0')
                    if name == 'screenshots.html':
                        evidence = page.locator('#image-notes .evidence-only img')
                        evidence.scroll_into_view_if_needed(); evidence.evaluate('img => img.decode()')
                        assert evidence.evaluate('img => img.naturalWidth == 640')
                    page.evaluate('scrollTo(0,0)')
                    if width in (1440,390) and theme == 'light':
                        page.screenshot(path=str(OUT / f'{name[:-5]}-{width}.png'), full_page=name == 'index.html')
                    checks.append(f'{width}px {theme}: {name} layout, images and downloads')
                if width in (1440,390):
                    for name in guide_pages:
                        load(page,name,wait_until='networkidle')
                        assert page.locator('h1').count() == 1, name
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Guide overflow {width} {theme} {name}'
                        assert not errors, errors
                        if name.endswith('personal-context.html'):
                            assert page.locator('#after-import').count() == 1
                            assert page.locator('#after-import img[src*="imported-notes-en.png"]').count() == 1
                        if name.endswith('feed-and-ai.html'):
                            assert 'up to eight public stories per module' in page.locator('main').inner_text()
                        if name.endswith('troubleshooting.html'):
                            assert 'After import, I do not see my notes.' in page.locator('main').inner_text()
                        for im in page.locator('.guide-figure img:visible').all():
                            im.scroll_into_view_if_needed(); im.evaluate('img => img.decode()')
                            assert im.evaluate('img => img.naturalWidth > 0')
                        checks.append(f'{width}px {theme}: {name} guide layout and key release content')
                context.close()
        context = browser.new_context(viewport={'width': 390, 'height': 844})
        page = context.new_page()
        load(page,'screenshots.html',wait_until='networkidle')
        page.get_by_role('button', name='Getting started', exact=True).click()
        start_count = sum(s['group'] == 'start' for s in json.loads((ROOT/'docs/assets/screens.json').read_text()))
        assert page.locator('#screen-gallery .shot:visible').count() == start_count
        assert page.locator('#screen-count').inner_text() == f'{start_count} screens'
        opener = page.locator('#screen-gallery .shot:visible a').first
        opener.focus(); page.keyboard.press('Enter')
        assert page.locator('#image-dialog').is_visible()
        page.keyboard.press('Escape')
        assert not page.locator('#image-dialog').is_visible()
        assert opener.evaluate('el => document.activeElement === el')
        page.get_by_role('button', name='All screens', exact=True).click()
        assert page.locator('#screen-gallery .shot:visible').count() == len(json.loads((ROOT/'docs/assets/screens.json').read_text()))
        page.get_by_role('button', name='Menu', exact=False).click()
        assert page.locator('#primary-nav').is_visible()
        page.keyboard.press('Escape')
        assert page.locator('[data-toggle]').get_attribute('aria-expanded') == 'false'
        checks += ['Gallery filters update accessible count', 'Keyboard opens viewer; Escape closes and restores focus', 'Mobile menu closes with Escape']
        context.close()
        context = browser.new_context(java_script_enabled=False, viewport={'width':390,'height':844})
        page = context.new_page()
        for name in ('index.html','download.html','releases.html'):
            load(page,name)
            assert page.locator('[data-release-apk]').first.is_visible()
            assert page.locator('[data-release-apk]').first.get_attribute('href') == apk
            if not published:
                assert page.locator('.release-hold').is_visible()
                assert page.locator(f'a[href*="/releases/download/{r["tag"]}/"]').count() == 0
        load(page,'screenshots.html')
        assert page.locator('#screen-gallery .shot').count() == len(json.loads((ROOT/'docs/assets/screens.json').read_text()))
        checks += ['APK links and all screenshots remain available with JavaScript disabled']
        context.close()
        context = browser.new_context(viewport={'width':390,'height':844}, reduced_motion='reduce')
        page = context.new_page(); load(page,'tour.html',wait_until='networkidle')
        assert page.locator('.tour-panel:visible').count()==1
        steps = page.locator('[data-tour-step]').count()
        assert steps >= 6
        for i in range(steps):
            page.locator('[data-tour-step]').nth(i).click()
            assert page.locator('#tour-status').inner_text()==f'Step {i+1} of {steps}'
            assert page.locator(f'#tour-panel-{i}').is_visible()
        page.locator('[data-tour-step]').nth(steps-1).press('Home')
        assert page.locator('#tour-panel-0').is_visible()
        page.locator('[data-tour-next]').click(); assert page.locator('#tour-panel-1').is_visible()
        page.locator('[data-tour-prev]').click(); assert page.locator('#tour-panel-0').is_visible()
        page.screenshot(path=str(OUT/'tour-390.png'),full_page=True)
        checks += [f'Illustrated guide: all {steps} steps, next/back, keyboard Home and reduced motion']
        load(page,'preview-085.html',wait_until='networkidle')
        page.keyboard.press('Tab')
        assert page.locator('.skip-link').evaluate('el => el === document.activeElement')
        page.keyboard.press('Enter')
        assert page.locator('#main').evaluate('el => el === document.activeElement')
        checks += ['0.8.5 preview: keyboard skip link reaches main content']
        context.close(); browser.close()
finally:
    server.shutdown()
(OUT / 'browser-results.json').write_text(json.dumps({'status':'passed','checks':checks}, indent=2)+'\n')
print(f'{len(checks)} browser scenarios passed.')
