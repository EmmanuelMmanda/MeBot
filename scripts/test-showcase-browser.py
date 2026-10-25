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
apk = f"https://github.com/EmmanuelMmanda/MeBot/releases/download/{r['tag']}/{r['filename']}"
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
                for name in ['index.html', 'screenshots.html', 'download.html', 'releases.html', 'tour.html', 'preview-083.html']:
                    load(page,name,wait_until='networkidle')
                    assert page.locator('h1').count() == 1, name
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Overflow {width} {theme} {name}'
                    assert not errors, errors
                    if name == 'preview-083.html':
                        assert 'Preview, not a published APK' in page.locator('main').inner_text()
                        assert page.locator('a[href*="/releases/download/v0.8.3/"]').count() == 0
                        assert page.locator('#kiswahili').get_attribute('lang') == 'sw'
                        assert page.locator('.preview-jumps a').count() == 6
                    for link in page.locator('[data-release-apk]').all():
                        assert link.is_visible() and link.get_attribute('href') == apk
                    for im in page.locator('.shot:visible img').all():
                        im.scroll_into_view_if_needed()
                        im.evaluate('img => img.decode()')
                        assert im.evaluate('img => img.naturalWidth > 0')
                    page.evaluate('scrollTo(0,0)')
                    if width in (1440,390) and theme == 'light':
                        page.screenshot(path=str(OUT / f'{name[:-5]}-{width}.png'), full_page=name == 'index.html')
                    checks.append(f'{width}px {theme}: {name} layout, images and downloads')
                context.close()
        context = browser.new_context(viewport={'width': 390, 'height': 844})
        page = context.new_page()
        load(page,'screenshots.html',wait_until='networkidle')
        page.get_by_role('button', name='Getting started', exact=True).click()
        assert page.locator('#screen-gallery .shot:visible').count() == 3
        assert page.locator('#screen-count').inner_text() == '3 screens'
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
        load(page,'screenshots.html')
        assert page.locator('#screen-gallery .shot').count() == len(json.loads((ROOT/'docs/assets/screens.json').read_text()))
        checks += ['APK links and all screenshots remain available with JavaScript disabled']
        context.close()
        context = browser.new_context(viewport={'width':390,'height':844}, reduced_motion='reduce')
        page = context.new_page(); load(page,'tour.html',wait_until='networkidle')
        assert page.locator('.tour-panel:visible').count()==1
        for i in range(6):
            page.locator('[data-tour-step]').nth(i).click()
            assert page.locator('#tour-status').inner_text()==f'Step {i+1} of 6'
            assert page.locator(f'#tour-panel-{i}').is_visible()
        page.locator('[data-tour-step]').nth(5).press('Home')
        assert page.locator('#tour-panel-0').is_visible()
        page.locator('[data-tour-next]').click(); assert page.locator('#tour-panel-1').is_visible()
        page.locator('[data-tour-prev]').click(); assert page.locator('#tour-panel-0').is_visible()
        page.screenshot(path=str(OUT/'tour-390.png'),full_page=True)
        checks += ['Illustrated guide: all six steps, next/back, keyboard Home and reduced motion']
        context.close(); browser.close()
finally:
    server.shutdown()
(OUT / 'browser-results.json').write_text(json.dumps({'status':'passed','checks':checks}, indent=2)+'\n')
print(f'{len(checks)} browser scenarios passed.')
