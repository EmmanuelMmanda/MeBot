#!/usr/bin/env python3
"""Test only the locally served website; never interact with account sign-in."""
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
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
try:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for width, height in [(1440, 1000), (768, 1024), (390, 844), (320, 740)]:
            for theme in ['light', 'dark']:
                context = browser.new_context(viewport={'width': width, 'height': height}, color_scheme=theme, reduced_motion='reduce')
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda e: errors.append(str(e)))
                for name in ['index.html', 'screenshots.html', 'download.html', 'releases.html']:
                    page.goto(base + name, wait_until='networkidle')
                    assert page.locator('h1').count() == 1, name
                    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), f'Overflow {width} {theme} {name}'
                    assert not errors, errors
                    for link in page.locator('[data-release-apk]').all():
                        assert link.is_visible() and link.get_attribute('href') == apk
                    for im in page.locator('.shot img').all():
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
        page.goto(base + 'screenshots.html', wait_until='networkidle')
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
        assert page.locator('#screen-gallery .shot:visible').count() == 15
        page.get_by_role('button', name='Menu', exact=False).click()
        assert page.locator('#primary-nav').is_visible()
        page.keyboard.press('Escape')
        assert page.locator('[data-toggle]').get_attribute('aria-expanded') == 'false'
        checks += ['Gallery filters update accessible count', 'Keyboard opens viewer; Escape closes and restores focus', 'Mobile menu closes with Escape']
        context.close()
        context = browser.new_context(java_script_enabled=False, viewport={'width':390,'height':844})
        page = context.new_page()
        for name in ('index.html','download.html','releases.html'):
            page.goto(base+name)
            assert page.locator('[data-release-apk]').first.is_visible()
            assert page.locator('[data-release-apk]').first.get_attribute('href') == apk
        page.goto(base+'screenshots.html')
        assert page.locator('#screen-gallery .shot').count() == 15
        checks += ['APK links and all screenshots remain available with JavaScript disabled']
        context.close(); browser.close()
finally:
    server.shutdown()
(OUT / 'browser-results.json').write_text(json.dumps({'status':'passed','checks':checks}, indent=2)+'\n')
print(f'{len(checks)} browser scenarios passed.')
