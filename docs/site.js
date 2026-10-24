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

  const releaseNodes = document.querySelectorAll('[data-release-status]');
  const archiveNodes = document.querySelectorAll('[data-release-entry]');
  if (releaseNodes.length || archiveNodes.length) {
    const source = document.body.dataset.releaseManifest || 'assets/release.json';
    fetch(source, {cache: 'no-cache'}).then(response => {
      if (!response.ok) throw new Error('Unavailable release metadata');
      return response.json();
    }).then(release => {
      const repository = 'https://github.com/EmmanuelMmanda/MeBot';
      const valid = item => item && /^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?$/.test(item.version) && item.tag === `v${item.version}`;
      const validApk = item => item.filename === `mebot-${item.version}.apk`
        && /^[a-f0-9]{64}$/i.test(item.sha256 || '')
        && Number.isSafeInteger(item.sizeBytes) && item.sizeBytes > 0;
      const validSource = item => item.sourceFilename === `mebot-${item.version}-source.zip`
        && ((/^[a-f0-9]{64}$/i.test(item.sourceSha256 || '') && Number.isSafeInteger(item.sourceSizeBytes) && item.sourceSizeBytes > 0) || item.sourceIntegrityFile === 'SHA256SUMS.txt')
        && /^[a-f0-9]{40}$/i.test(item.sourceCommit || '');
      const unavailable = () => document.querySelectorAll('[data-release-load-error]').forEach(item => { item.hidden = false; });
      if (!valid(release)) throw new Error('Invalid release version');
      if (Array.isArray(release.releases)) archiveNodes.forEach(node => {
        const entry = release.releases.find(item => item && item.version === node.dataset.releaseEntry);
        if (!valid(entry) || entry.status !== 'published') return;
        if (!validSource(entry) || (entry.apk !== false && (entry.apk !== true || !validApk(entry)))) {
          unavailable(); return;
        }
        const sourceLink = node.querySelector('[data-archive-source]');
        if (sourceLink) { sourceLink.href = `${repository}/releases/download/${entry.tag}/mebot-${entry.version}-source.zip`; sourceLink.hidden = false; }
        const notesLink = node.querySelector('[data-archive-notes]');
        if (notesLink) { notesLink.href = `${repository}/releases/tag/${entry.tag}`; notesLink.hidden = false; }
        const apkLink = node.querySelector('[data-archive-apk]');
        if (apkLink && entry.apk === true) { apkLink.href = `${repository}/releases/download/${entry.tag}/mebot-${entry.version}.apk`; apkLink.hidden = false; }
        const status = node.querySelector('[data-archive-status]');
        if (status) status.textContent = entry.apk === true ? 'Public APK and source' : 'Source only · no APK';
        node.querySelectorAll('[data-archive-pending]').forEach(item => { item.hidden = true; });
        if (/^[a-f0-9]{64}$/i.test(entry.sha256 || '')) {
          const checksum = node.querySelector('[data-archive-sha]');
          if (checksum) { checksum.textContent = entry.sha256; checksum.parentElement.hidden = false; }
        }
      });
      if (release.status !== 'published') return;
      if (!validApk(release) || !validSource(release)) throw new Error('Incomplete public release metadata');
      const file = `mebot-${release.version}.apk`;
      const apk = `${repository}/releases/download/v${release.version}/${file}`;
      document.querySelectorAll('[data-release-apk]').forEach(link => { link.href = apk; link.hidden = false; });
      document.querySelectorAll('[data-release-pending]').forEach(item => { item.hidden = true; });
      document.querySelectorAll('[data-release-source]').forEach(link => {
        link.href = `${repository}/releases/download/v${release.version}/mebot-${release.version}-source.zip`; link.hidden = false;
      });
      releaseNodes.forEach(item => { item.textContent = `Version ${release.version} · ${release.channel === 'beta' ? 'Testing release' : 'Official release'}`; });
      document.querySelectorAll('[data-release-filename]').forEach(item => { item.textContent = file; });
      document.querySelectorAll('[data-release-version]').forEach(item => { item.textContent = release.version; });
      if (/^[a-f0-9]{64}$/i.test(release.sha256 || '')) {
        document.querySelectorAll('[data-release-sha]').forEach(item => { item.textContent = release.sha256; });
        document.querySelectorAll('[data-release-integrity]').forEach(item => { item.hidden = false; });
      }
      if (Number.isSafeInteger(release.sizeBytes) && release.sizeBytes > 0)
        document.querySelectorAll('[data-release-size]').forEach(item => { item.textContent = `${(release.sizeBytes / 1000000).toFixed(1)} MB`; });
    }).catch(() => {
      document.querySelectorAll('[data-release-load-error]').forEach(item => { item.hidden = false; });
    });
  }
})();
