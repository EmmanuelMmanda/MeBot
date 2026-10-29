/* MeBot documentation: no dependencies, telemetry, or third-party script requests. */
(() => {
  'use strict';
  const root = document.documentElement;
  const themeKey = 'mebot.docs.theme';
  const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
  let preference = 'system';
  try { const stored = localStorage.getItem(themeKey); if (['light', 'dark', 'system'].includes(stored)) preference = stored; } catch (_) { }
  function applyTheme() {
    root.dataset.theme = preference === 'system' ? (systemTheme.matches ? 'dark' : 'light') : preference;
    document.querySelectorAll('[data-theme-select]').forEach(select => { select.value = preference; });
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = root.dataset.theme === 'dark' ? '#111218' : '#f7f8fa';
  }
  applyTheme();
  if (systemTheme.addEventListener) systemTheme.addEventListener('change', () => { if (preference === 'system') applyTheme(); });
  document.querySelectorAll('[data-theme-select]').forEach(select => select.addEventListener('change', () => {
    preference = ['light', 'dark', 'system'].includes(select.value) ? select.value : 'system';
    try { localStorage.setItem(themeKey, preference); } catch (_) { }
    applyTheme();
  }));
  root.classList.add('js');

  const toggles = [];
  document.querySelectorAll('[data-toggle]').forEach(button => {
    const panel = document.getElementById(button.getAttribute('aria-controls'));
    if (!panel) return;
    const close = () => { button.setAttribute('aria-expanded', 'false'); panel.classList.remove('open'); };
    button.addEventListener('click', () => {
      const opening = button.getAttribute('aria-expanded') !== 'true';
      button.setAttribute('aria-expanded', String(opening)); panel.classList.toggle('open', opening);
    });
    panel.addEventListener('click', event => { if (event.target.closest('a')) close(); });
    toggles.push({button, panel, close});
  });
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    const open = toggles.find(item => item.button.getAttribute('aria-expanded') === 'true');
    if (open) { open.close(); open.button.focus(); }
  });

  const normalized = value => value.toLocaleLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').trim();
  document.querySelectorAll('[data-search-root]').forEach(container => {
    const input = container.querySelector('[data-guide-search]');
    const items = [...container.querySelectorAll('[data-search-item]')];
    const status = container.querySelector('[data-search-status]');
    const clear = container.querySelector('[data-search-clear]');
    const empty = container.querySelector('[data-search-empty]');
    if (!input || !items.length) return;
    const apply = () => {
      const words = normalized(input.value).split(/\s+/).filter(Boolean);
      let visible = 0;
      items.forEach(item => {
        const text = normalized(item.dataset.searchItem + ' ' + item.textContent);
        item.hidden = !words.every(word => text.includes(word));
        if (!item.hidden) visible++;
      });
      container.querySelectorAll('[data-search-group]').forEach(group => {
        group.hidden = ![...group.querySelectorAll('[data-search-item]')].some(item => !item.hidden);
      });
      if (status) status.textContent = words.length ? `${visible} ${visible === 1 ? 'guide' : 'guides'} found` : '';
      if (clear) clear.hidden = !words.length;
      if (empty) empty.hidden = visible > 0;
    };
    input.addEventListener('input', apply);
    if (clear) clear.addEventListener('click', () => { input.value = ''; apply(); input.focus(); });
    const query = new URLSearchParams(location.search).get('q');
    if (query) input.value = query.slice(0, 200);
    apply();
    if (location.hash === '#find-guide') input.focus();
  });

  const live = document.getElementById('copy-status');
  document.querySelectorAll('[data-copy-target]').forEach(button => button.addEventListener('click', async () => {
    const target = document.getElementById(button.dataset.copyTarget);
    if (!target) return;
    const original = button.dataset.copyLabel || button.textContent;
    let copied = false;
    try { await navigator.clipboard.writeText(target.textContent); copied = true; } catch (_) {
      const area = document.createElement('textarea');
      area.value = target.textContent; area.setAttribute('readonly', '');
      area.style.cssText = 'position:fixed;left:-9999px;top:0';
      document.body.appendChild(area); area.select();
      try { copied = document.execCommand('copy'); } catch (_) { }
      area.remove(); button.focus();
    }
    button.textContent = copied ? 'Copied' : 'Select text to copy';
    if (live) live.textContent = copied ? 'Copied to clipboard.' : 'Copy is unavailable. Select the text and copy it manually.';
    if (!copied) target.focus();
    clearTimeout(button._resetCopy); button._resetCopy = setTimeout(() => { button.textContent = original; }, 2600);
  }));

  const dialog = document.getElementById('image-dialog');
  if (dialog && typeof dialog.showModal === 'function') {
    const image = document.getElementById('image-full');
    const title = document.getElementById('image-title');
    const original = document.getElementById('image-original');
    let opener;
    document.querySelectorAll('[data-lightbox]').forEach(link => link.addEventListener('click', event => {
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault(); opener = link;
      image.src = link.href; image.alt = link.querySelector('img')?.alt || link.dataset.title || 'MeBot native Android screen';
      title.textContent = link.dataset.title || 'MeBot native Android screen'; original.href = link.href;
      dialog.showModal(); document.getElementById('image-close').focus();
    }));
    document.getElementById('image-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('click', event => {
      if (event.target !== dialog) return;
      const rect = dialog.getBoundingClientRect();
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
    });
    dialog.addEventListener('close', () => { image.removeAttribute('src'); if (opener?.isConnected) opener.focus(); });
  }

  if ('IntersectionObserver' in window) {
    const sections = [...document.querySelectorAll('.article-section[id]')];
    const links = [...document.querySelectorAll('.page-toc a[href^="#"]')];
    const observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (!visible.length) return;
      links.forEach(link => {
        if (link.hash === '#' + visible[0].target.id) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
    }, {rootMargin: '-100px 0px -60% 0px', threshold: 0});
    sections.forEach(section => observer.observe(section));
  }

  const releasePage = document.querySelector('[data-release-page]');
  if (releasePage) {
    const api = 'https://api.github.com/repos/EmmanuelMmanda/MeBot/releases/latest';
    const repo = 'https://github.com/EmmanuelMmanda/MeBot';
    const status = document.querySelector('[data-release-status]');
    const button = document.querySelector('[data-release-apk]');
    const detailsLink = document.querySelector('[data-release-details-link]');
    const name = document.querySelector('[data-release-name]');
    const date = document.querySelector('[data-release-date]');
    const notes = document.querySelector('[data-release-notes]');
    const size = document.querySelector('[data-release-size]');
    const digest = document.querySelector('[data-release-sha]');
    const version = document.querySelector('[data-release-version]');
    const resources = document.querySelector('[data-release-assets]');
    const info = document.querySelector('[data-release-info]');
    const error = document.querySelector('[data-release-error]');
    const showError = message => {
      if (status) status.textContent = 'Could not verify the latest APK.';
      if (error) { error.textContent = message; error.hidden = false; }
      if (button) {
        button.href = repo + '/releases/latest';
        button.textContent = 'Open latest GitHub release';
        button.setAttribute('aria-label', 'Open the latest GitHub release page');
      }
      if (detailsLink) detailsLink.href = repo + '/releases/latest';
      if (version) version.textContent = '';
      if (info) info.hidden = true;
    };
    const noVersion = value => String(value || '')
      .replace(/\bv?\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?\b/g, '')
      .replace(/[ \t]{2,}/g, ' ').trim();
    const assetUrl = (asset, tag) => {
      if (!asset || asset.state !== 'uploaded' || !Number.isSafeInteger(asset.size) || asset.size <= 0) return null;
      try {
        const expected = repo + '/releases/download/' + tag + '/' + asset.name;
        const parsed = new URL(asset.browser_download_url);
        return parsed.href === expected ? parsed.href : null;
      } catch (_) { return null; }
    };
    const releaseVersion = release => {
      if (!release || release.draft !== false || release.prerelease !== false) return null;
      const match = /^v(\d+\.\d+\.\d+)$/.exec(String(release.tag_name || ''));
      const published = Date.parse(release.published_at || '');
      if (!match || !Number.isFinite(published)) return null;
      return {tag: release.tag_name, version: match[1], published};
    };
    const loadRelease = async () => {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 10000);
      try {
        const response = await fetch(api, {
          cache: 'no-store',
          credentials: 'omit',
          headers: {Accept: 'application/vnd.github+json'},
          referrerPolicy: 'no-referrer',
          signal: controller.signal
        });
        if (!response.ok) {
          if (response.status === 403 || response.status === 429) throw new Error('rate');
          if (response.status === 404) throw new Error('missing');
          throw new Error('http');
        }
        const release = await response.json();
        const parsed = releaseVersion(release);
        if (!parsed) throw new Error('invalid');
        const assets = Array.isArray(release.assets) ? release.assets : [];
        const apkName = 'mebot-' + parsed.version + '.apk';
        const apk = assets.find(item => item && item.name === apkName);
        const apkUrl = assetUrl(apk, parsed.tag);
        const shaMatch = /^sha256:([a-f0-9]{64})$/i.exec(String(apk?.digest || ''));
        const releaseUrl = repo + '/releases/tag/' + parsed.tag;
        if (!apkUrl || !shaMatch || release.html_url !== releaseUrl) throw new Error('unverified');
        if (button) {
          button.href = apkUrl;
          button.textContent = 'Download latest version';
          button.removeAttribute('aria-label');
        }
        if (detailsLink) detailsLink.href = releaseUrl;
        if (name) name.textContent = noVersion(release.name) || 'Latest Android release';
        if (date) {
          date.dateTime = new Date(parsed.published).toISOString();
          date.textContent = new Date(parsed.published).toLocaleDateString(undefined, {year: 'numeric', month: 'long', day: 'numeric'});
        }
        if (notes) notes.textContent = noVersion(release.body) || 'No release notes were provided.';
        if (size) size.textContent = (apk.size / 1000000).toFixed(1) + ' MB';
        if (digest) digest.textContent = shaMatch[1].toLowerCase();
        if (version) version.textContent = ' ' + parsed.version;
        if (status) status.textContent = 'Latest published Android release';
        if (error) error.hidden = true;
        const allowed = [
          ['source', /(?:^|-)source\.zip$/i],
          ['verification report', /verification\.md$/i],
          ['release notes', /^RELEASE-NOTES\.md$/i],
          ['SHA-256 checksums', /^SHA256SUMS\.txt$/i],
          ['release identity metadata', /^release-metadata\.json$/i]
        ];
        if (resources) {
          resources.replaceChildren();
          for (const [label, pattern] of allowed) {
            const item = assets.find(entry => entry && pattern.test(entry.name) && assetUrl(entry, parsed.tag));
            const href = assetUrl(item, parsed.tag);
            if (!href) continue;
            const row = document.createElement('div');
            row.className = 'download-row';
            const text = document.createElement('div');
            const strong = document.createElement('strong');
            strong.textContent = label;
            text.appendChild(strong);
            const link = document.createElement('a');
            link.href = href;
            link.textContent = 'Open';
            link.rel = 'noopener';
            row.append(text, link);
            resources.appendChild(row);
          }
          if (!resources.children.length) {
            const paragraph = document.createElement('p');
            paragraph.textContent = 'No additional release files are available.';
            resources.appendChild(paragraph);
          }
        }
        if (info) info.hidden = false;
      } catch (cause) {
        if (cause?.name === 'AbortError') showError('GitHub did not respond in time. The latest APK could not be verified.');
        else if (cause?.message === 'rate') showError('GitHub is limiting release checks right now. Open Releases to see what is published.');
        else if (cause?.message === 'missing') showError('GitHub did not find a published Android release. Open Releases to check availability.');
        else if (cause?.message === 'unverified') showError('The latest release does not include a verifiable Android APK. Open Releases to review its files.');
        else if (cause?.message === 'invalid') showError('GitHub returned release information that could not be verified. Open Releases to review it.');
        else showError('The latest release could not be checked. Check your connection or open Releases.');
      } finally {
        clearTimeout(timeout);
      }
    };
    if (status) status.textContent = 'Checking GitHub for the latest published Android release…';
    loadRelease();
  }
})();
