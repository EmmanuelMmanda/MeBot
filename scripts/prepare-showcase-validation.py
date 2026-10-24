#!/usr/bin/env python3
"""One-time compatibility update for the existing release-catalog checker.
Retains its full link and integrity validation while accepting prerelease tags
and an explicitly selected per-release integrity record. Idempotent.
"""
from pathlib import Path
p = Path(__file__).resolve().with_name('check-docs.py')
s = p.read_text()
s = s.replace("integrity = json.loads((DOCS / 'release-integrity.json').read_text())", "integrity_path = (DOCS / catalog.get('integrityFile', 'release-integrity.json')).resolve()\n        if not integrity_path.is_relative_to(DOCS.resolve()):\n            raise ValueError('Integrity file must stay inside docs')\n        integrity = json.loads(integrity_path.read_text())")
s = s.replace("r'\\d+\\.\\d+\\.\\d+', version", "r'\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?', version")
p.write_text(s)
js = p.parents[1] / 'docs/site.js'
s = js.read_text().replace('`Version ${release.version} · Public release`', "`Version ${release.version} · ${release.channel === 'beta' ? 'Testing release' : 'Public release'}`")
s = s.replace('(release.sizeBytes / 1048576).toFixed(1)', '(release.sizeBytes / 1000000).toFixed(1)')
js.write_text(s)
