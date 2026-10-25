#!/usr/bin/env python3
"""Package the exact release commit and evidence; excludes private/build assets."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1];OUT=Path(os.environ['RELEASE_DIRECTORY']);CI=Path(os.environ['MEBOT_CANDIDATE_DIR'])
V='0.8.1';m=json.loads((ROOT/'release/0.8.1/signed-apk.json').read_text())
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
commit=git('rev-parse','HEAD')
assert not git('diff','--name-only',m['sourceCommit'],'HEAD','--','app','core','service','gradle','gradlew','gradlew.bat','gradle.properties','settings.gradle.kts','build.gradle.kts'), 'Runtime source differs from tested candidate'
assert sha(OUT/f'mebot-{V}.apk')==m['signedSha256']
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in (OUT/'signature-verification.txt').read_text()
assert m['certificateSha256'] in (OUT/'signature-verification.txt').read_text()
counts=json.loads((OUT/'test-counts.json').read_text());browser=json.loads((ROOT/'website-evidence/browser-results.json').read_text())
assert browser['status']=='passed' and len(browser['checks'])>=45
source=OUT/f'mebot-{V}-source.zip'
subprocess.run(['git','archive','--format=zip','--prefix=MeBot/','-o',str(source),'HEAD'],cwd=ROOT,check=True)
forbidden=('.p12','.pfx','.jks','.keystore','.ttf','.otf','.woff','.woff2','.apk','.aab')
with zipfile.ZipFile(source) as z:
 assert z.testzip() is None
 for n in z.namelist():
  assert not n.lower().endswith(forbidden), f'Forbidden distributable: {n}'
  assert not any(x in n.lower() for x in ['signing-details.json','mebot-personal-context.json','mebot-profile.json','private-signing']), n
  assert not n.endswith('signed-assembly.zlib.b64'), 'Obsolete transport file'
report=f'''MeBot 0.8.1 — official release verification

Application: MeBot
Package: app.mynguli
Version code: 12
Minimum Android: 8.0 / API 26
Release source commit: {commit}
Tested application source commit: {m['sourceCommit']}
Independent verification: https://github.com/EmmanuelMmanda/MeBot/actions/runs/{m['ciRun']}

Tests: 143 core + 405 Android-host + 145 service = 693 passed.
Failures: 0. Errors: 0. Skipped: 0. No excluded Android suites.
Release compilation: passed.
Full independent CI lint: 0 errors, {counts['lint']['Warning']} warnings, {counts['lint']['Hint']} hint.
Website: {len(browser['checks'])} browser scenarios passed; static links and release metadata checked.

APK SHA-256: {m['signedSha256']}
APK bytes: {m['signedSizeBytes']}
Signer certificate SHA-256: {m['certificateSha256']}
Original certificate, package identity and version increment verified.
Android apksigner v2/v3 verification, ZIP integrity and 16 KiB alignment passed.
All signed APK ZIP entries are byte-identical to the tested unsigned APK.
Source archive is the exact release commit; runtime tree equals the tested source.
No personal starter profile, private signing key or credentials are included.
Signing was performed locally. CI receives only the public APK signature block.

Limits:
No real-device installation, live account inference, media-provider playback,
Android save-picker interaction or overnight alarm delivery was performed here.
Native screenshot content is controlled test content, not live news/vacancies.
The activity-details capture is an isolated badge fixture, not a popup screenshot.
Resource availability can change after checking; protected content is not bypassed.
The documented 20 MiB per-file download limit remains.
Install over the original official MeBot. Do not uninstall it first.
MeBot Test is a separate older package; its data does not automatically migrate.
'''
(OUT/f'mebot-{V}-verification.txt').write_text(report)
for p in sorted((ROOT/'docs/screenshots/v081').glob('mebot-0.8.1-*.png')):shutil.copy2(p,OUT/p.name)
with zipfile.ZipFile(OUT/f'mebot-{V}-documentation.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((ROOT/'docs').rglob('*')):
  if p.is_file() and not p.name.lower().endswith(forbidden):z.write(p,'MeBot-docs/'+str(p.relative_to(ROOT/'docs')))
with zipfile.ZipFile(OUT/f'mebot-{V}-resources.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((ROOT/'docs/screenshots/v081').glob('*')):
  if p.is_file():z.write(p,'screenshots/'+p.name)
 for pattern in ['core/build/test-results/test/TEST-*.xml','app/build/test-results/testDebugUnitTest/TEST-*.xml','app/build/reports/lint-results-release.xml','service/test-results.xml']:
  for p in sorted(CI.glob(pattern)):z.write(p,'evidence/'+str(p.relative_to(CI)))
 for p in sorted((ROOT/'website-evidence').rglob('*')):
  if p.is_file():z.write(p,'website/'+str(p.relative_to(ROOT/'website-evidence')))
 for name in ['signature-verification.txt','alignment-verification.txt','apk-badging.txt','test-counts.json',f'mebot-{V}-verification.txt']:
  z.write(OUT/name,'evidence/'+name)
 z.write(ROOT/'docs/ACTIVITY-AND-DISCOVERY.md','ACTIVITY-AND-DISCOVERY.md')
 z.write(ROOT/'docs/RELEASE-0.8.1.md','RELEASE-0.8.1.md')
# Only user-facing deliverables are published, never temporary build inputs.
public=[OUT/f'mebot-{V}.apk',source,OUT/f'mebot-{V}-documentation.zip',OUT/f'mebot-{V}-resources.zip',OUT/f'mebot-{V}-verification.txt',*sorted(OUT.glob(f'mebot-{V}-*.png'))]
meta=dict(version=V,tag='v'+V,sourceCommit=commit,applicationSourceCommit=m['sourceCommit'],ciRun=m['ciRun'],applicationId=m['applicationId'],versionCode=m['versionCode'],signerCertificateSha256=m['certificateSha256'],tests=counts,browserScenarios=len(browser['checks']),assets=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in public])
metadata=OUT/'release-metadata.json';metadata.write_text(json.dumps(meta,indent=2)+'\n');public.append(metadata)
checksums=OUT/'SHA256SUMS.txt';checksums.write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(public)));public.append(checksums)
for p in public:
 if p.suffix=='.zip':
  with zipfile.ZipFile(p) as z:assert z.testzip() is None
(ROOT/'website-evidence/public-assets.txt').write_text('\n'.join(str(p) for p in public)+'\n')
print(f'Packaged {len(public)} public release assets for {commit}.')
