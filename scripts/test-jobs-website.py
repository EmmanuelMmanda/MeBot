#!/usr/bin/env python3
"""Rendered Jobs website regression gate; serves local docs, no paid provider calls."""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os
import shutil
import threading
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'website-evidence' / 'jobs'
OUT.mkdir(parents=True, exist_ok=True)
server = ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=str(ROOT / 'docs')))
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f'http://127.0.0.1:{server.server_port}'
results = []
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('MEBOT_CHROMIUM') or shutil.which('chromium'))
        for width in (360, 768, 1024, 1440):
            for theme in ('light', 'dark'):
                page = browser.new_page(viewport={'width': width, 'height': 1000}, color_scheme=theme, reduced_motion='reduce')
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(base + '/jobs.html')
                page.get_by_role('heading', name='Find a role. Keep your next step.').wait_for()
                metrics = page.evaluate('''async () => {
                    await document.fonts.load('400 16px Figtree');
                    await document.fonts.load('600 24px Figtree');
                    await document.fonts.ready;
                    return {loaded:[...document.fonts].some(f=>f.family==='Figtree'&&f.status==='loaded'),
                      families:['body','h1','h2','.button','.jobs-map-note'].map(s=>getComputedStyle(document.querySelector(s)).fontFamily),
                      weights:['body','h1','h2','.button'].map(s=>getComputedStyle(document.querySelector(s)).fontWeight),
                      width:innerWidth, content:document.documentElement.scrollWidth};
                }''')
                assert metrics['loaded'] and all('Figtree' in f for f in metrics['families']), metrics
                assert metrics['weights'] == ['400', '600', '600', '600'], metrics
                assert metrics['content'] <= width, metrics
                # Scroll each lazy native capture into view and verify decoded source dimensions.
                captures = page.locator('.jobs-native-shot img')
                assert captures.count() == 6
                for capture in captures.all():
                    capture.scroll_into_view_if_needed()
                    capture.evaluate('(img) => img.decode()')
                    assert capture.evaluate('(img) => img.naturalWidth === Number(img.getAttribute("width")) && img.naturalHeight === Number(img.getAttribute("height"))')
                assert page.evaluate('document.documentElement.scrollWidth') <= width
                page.evaluate('window.scrollTo(0, 0)')
                page.screenshot(path=str(OUT / f'jobs-{width}-{theme}.png'), full_page=True)
                # Real keyboard action toggles native disclosure, not a synthetic DOM event.
                summary = page.get_by_text('Can I use Jobs without AI?', exact=True)
                summary.focus()
                summary.press('Enter')
                assert page.locator('details').first.get_attribute('open') is not None
                assert page.locator('details').first.locator('p').is_visible()
                summary.press('Enter')
                assert page.locator('details').first.get_attribute('open') is None
                page.get_by_role('link', name='Get started', exact=True).click()
                assert page.url.endswith('#how-it-works')
                # Main menu exposes Jobs without changing the site's existing navigation.
                menu = page.get_by_role('button', name='Menu')
                if menu.is_visible():
                    menu.click()
                    assert menu.get_attribute('aria-expanded') == 'true'
                    assert page.locator('#primary-nav').get_by_role('link', name='Jobs', exact=True).is_visible()
                    menu.click()
                    assert menu.get_attribute('aria-expanded') == 'false'
                assert not errors, errors
                results.append({'width': width, 'theme': theme, 'font': metrics, 'consoleErrors': errors})
                page.close()
        page = browser.new_page(viewport={'width': 390, 'height': 844})
        page.goto(base + '/guide.html')
        search = page.locator('input[type="search"]')
        assert search.count() == 1
        search.fill('jobs')
        page.get_by_role('link', name='Jobs & applications').click()
        assert page.url == base + '/jobs.html'
        page.get_by_role('link', name='Get MeBot for Android').click()
        assert page.url == base + '/download.html'
        # Release fetching is covered by the existing release suite, not stubbed here.
        assert page.locator('[data-release-apk]').count() == 1
        browser.close()
finally:
    server.shutdown()
(OUT / 'results.json').write_text(json.dumps(results, indent=2))
print(f'Jobs website: {len(results)} responsive/theme cases plus guide → Jobs → download passed.')
