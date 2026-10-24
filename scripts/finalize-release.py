#!/usr/bin/env python3
"""Finalize a tested, locally signed release in an authorized CI workspace.
Signing secrets are never received here. The original signed APK is separately
validated by the workflow. The source archive matches the final release tag;
archive digests live in SHA256SUMS.txt to avoid self-referential checksums.
"""
from pathlib import Path
import subprocess,json,hashlib,zipfile,xml.etree.ElementTree as ET,os,shutil
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ['RELEASE_DIRECTORY']); VERSION='0.8.0'; TAG='v'+VERSION
REPO='EmmanuelMmanda/MeBot'
def run(*args): return subprocess.check_output(args,cwd=ROOT,text=True).strip()
def digest(path):
 with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def test_totals(paths):
 result={k:0 for k in ('tests','failures','errors','skipped')}
 for p in paths:
  t=ET.parse(p).getroot()
  nodes=list(t) if t.tag=='testsuites' else [t]
  for n in nodes:
   for k in result:result[k]+=int(n.get(k,0))
 return result
core=test_totals((ROOT/'core/build/test-results').rglob('TEST-*.xml'))
android=test_totals((ROOT/'app/build/test-results').rglob('TEST-*.xml'))
service=test_totals([Path(os.environ['SERVICE_RESULTS'])])
for name,counts in [('core',core),('android',android),('service',service)]:
 assert counts['tests']>0 and counts['failures']==counts['errors']==counts['skipped']==0,(name,counts)
assert android['tests']>=396,('Full declared Android suite must run',android)
code_commit=run('git','rev-parse','HEAD')
r=json.loads((ROOT/'docs/assets/release.json').read_text())
r.update(version=VERSION,tag=TAG,status='published',channel='official',sourceCommit=code_commit,sourceRef=TAG,sourceIntegrityFile='SHA256SUMS.txt')
r.pop('sourceSha256',None);r.pop('sourceSizeBytes',None)
r['releases'][0]={k:v for k,v in r.items() if k!='releases'}
(ROOT/'docs/assets/release.json').write_text(json.dumps(r,indent=2)+'\n')
p=ROOT/'docs/releases/v0.8.0.json';i=json.loads(p.read_text());i.update(source_code_commit=code_commit,source_ref=TAG,source_integrity_file='SHA256SUMS.txt');p.write_text(json.dumps(i,indent=2)+'\n')
subprocess.run(['python3','scripts/build-showcase.py'],cwd=ROOT,check=True)
subprocess.run(['python3','scripts/check-docs.py'],cwd=ROOT,check=True)
subprocess.run(['python3','scripts/check-showcase.py'],cwd=ROOT,check=True)
run('git','add','README.md','docs')
if run('git','diff','--cached','--name-only'):
 run('git','commit','-m','release: finalize MeBot 0.8.0 download and source metadata')
final_commit=run('git','rev-parse','HEAD')
# Runtime code is exactly what was tested. Only publication metadata may differ.
changed=run('git','diff','--name-only',code_commit,final_commit).splitlines()
assert all(p=='README.md' or p.startswith('docs/') for p in changed),changed
source=OUT/f'mebot-{VERSION}-source.zip'
subprocess.run(['git','archive','--format=zip','--prefix=MeBot/','--output='+str(source),final_commit],cwd=ROOT,check=True)
with zipfile.ZipFile(source) as z:
 for name in z.namelist():
  assert not name.lower().endswith(('.p12','.jks','.keystore','.ttf','.otf','.woff','.woff2')),name
  assert not name.endswith('mebot-profile.json'),name
assert r['sha256']==digest(OUT/f'mebot-{VERSION}.apk')
issues=ET.parse(ROOT/'app/build/reports/lint-results-release.xml').getroot().findall('issue')
severities={s:sum(x.get('severity')==s for x in issues) for s in ('Error','Fatal','Warning','Information','Informational')}
assert severities['Error']==severities['Fatal']==0
browser=json.loads((ROOT/'website-evidence/browser-results.json').read_text())
assert browser['status']=='passed'
report=f'''MeBot {VERSION} — publication verification
Application label: MeBot
Package: app.mynguli
Version name: {VERSION}
Version code: 11
Minimum SDK: 26; target/compile SDK: 36
APK SHA-256: {r['sha256']}
APK bytes: {r['sizeBytes']}
Original signing certificate SHA-256:
b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9
Source-code test commit: {code_commit}
Release/tag commit: {final_commit}
Source ZIP is git archive of the release/tag commit. Runtime code is unchanged
between the tested source-code commit and release metadata commit.
Source ZIP SHA-256: {digest(source)}

FULL CI TEST RESULTS
Core: {core}
Android (including the original Android34 alarm suites): {android}
Service: {service}
Total tests: {core['tests']+android['tests']+service['tests']}
No test exclusions and no skipped tests in the full CI run.
Release lint: {severities}
Website browser checks: {len(browser['checks'])} scenarios passed.

BUILD / ARTIFACT CHECKS
Official APK compiled and signed locally with the original private key.
Release assemble and lint passed locally and on the public CI toolchain.
APK package/version/label, original certificate, v2 signature, archive integrity
and 16 KiB ZIP alignment verified. Public APK contains no private starter profile.
Signing secrets, credentials and font binaries are excluded from public source.
APK downloads do not replace MeBot Test; original official MeBot can update in place.

LIMITS
The tests use controlled providers and native host-rendered screens. Physical-phone
alarms, real account authorization/inference, provider-dependent media playback,
and the Android storage picker with each real storage provider were not exercised.
Test alarms on your phone before overnight reliance. Supported public file links
are bounded to 20 MiB; protected pages and missing source files are not bypassed.

PUBLICATION
Release identity is 0.8.0 as requested, not a renamed 0.7.1 binary.
The previous delivered polish changes are preserved; this publication does not
replace recommendation, account-transport or alarm implementations.
'''
(OUT/f'mebot-{VERSION}-verification.txt').write_text(report)
with zipfile.ZipFile(OUT/f'mebot-{VERSION}-resources.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in (ROOT/'docs/screenshots/v080').glob('*.png'): z.write(p,'screenshots/'+p.name)
 for label,path in [('core',ROOT/'core/build/test-results'),('android',ROOT/'app/build/test-results')]:
  for p in path.rglob('TEST-*.xml'): z.write(p,f'tests/{label}/'+p.name)
 z.write(Path(os.environ['SERVICE_RESULTS']),'tests/service.xml')
 z.write(ROOT/'app/build/reports/lint-results-release.xml','lint/lint-results-release.xml')
 for p in (ROOT/'website-evidence').glob('*'):
  if p.is_file():z.write(p,'website/'+p.name)
 z.write(OUT/f'mebot-{VERSION}-verification.txt','VERIFICATION.txt')
with zipfile.ZipFile(OUT/f'mebot-{VERSION}-documentation.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((ROOT/'docs').rglob('*')):
  if p.is_file() and p.suffix not in ('.ttf','.otf','.woff','.woff2'):z.write(p,p.relative_to(ROOT/'docs'))
for p in (ROOT/'docs/screenshots/v080').glob('*.png'):shutil.copy2(p,OUT/f'mebot-{VERSION}-{p.name}')
meta=dict(version=VERSION,tag=TAG,code_commit=code_commit,release_commit=final_commit,apk_sha256=r['sha256'],source_sha256=digest(source),source_bytes=source.stat().st_size,tests=dict(core=core,android=android,service=service),website_checks=len(browser['checks']))
(OUT/'release-metadata.json').write_text(json.dumps(meta,indent=2)+'\n')
files=[p for p in OUT.iterdir() if p.is_file() and p.name!='SHA256SUMS.txt']
(OUT/'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in sorted(files)))
body=(ROOT/'docs/RELEASE-0.8.0.md').read_text()
body=body[:body.index('## Verification')]+f'''## Verification
{core['tests']+android['tests']+service['tests']} automated tests passed (core {core['tests']}, Android {android['tests']}, service {service['tests']}). Full release lint: zero errors; advisory warnings are recorded in the report. {len(browser['checks'])} website browser scenarios passed.

The official signed APK is attached, with matching source, resources, illustrated documentation, screenshots, verification and SHA-256 checksums. Read the verification report for physical-device and live-provider limitations.

Source-code test commit: `{code_commit}`. Release metadata/tag commit: `{final_commit}`. Only documentation changed between them.
'''
(Path(os.environ['RUNNER_TEMP'])/'release-notes.md').write_text(body)
print(json.dumps(meta,indent=2))
