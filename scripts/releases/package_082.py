#!/usr/bin/env python3
"""Package a verified 0.8.2 candidate. Never receives or uses private signing keys."""
from pathlib import Path
import hashlib,json,os,subprocess,zipfile,xml.etree.ElementTree as E,shutil
R=Path(__file__).resolve().parents[2];CI=Path(os.environ['MEBOT_CI_DIR']);O=Path(os.environ['MEBOT_RELEASE_DIR']);O.mkdir(parents=True,exist_ok=True)
TESTED='36cc6ad8a59f4a1e8516f42129b0ff2577de1f70'
APK_SHA='fe7b2742b2c236b3a8cabee72c5d0d575dec11eeae5c48c1ecdddadb55e61fda'
CERT='b1b69dcefe26c815dbaec76223b7b80be76af06ba30da774637a806a7f75e3e9'
V='0.8.2';TAG='v'+V
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def git(*a):return subprocess.check_output(['git',*a],cwd=R,text=True).strip()
assert not git('diff','--name-only',TESTED,'HEAD','--','app','core','service','gradle','gradlew','gradlew.bat','gradle.properties','settings.gradle.kts','build.gradle.kts'), 'Untested runtime changes'
commit=git('rev-parse','HEAD');apk=O/f'mebot-{V}.apk';assert sha(apk)==APK_SHA
counts={}
for label,path in [('core','core/build/test-results/test'),('android','app/build/test-results/testDebugUnitTest'),('service','service')]:
 t=dict(tests=0,failures=0,errors=0,skipped=0)
 for p in (CI/path).glob('*.xml'):
  root=E.parse(p).getroot()
  for suite in ([root] if root.tag=='testsuite' else root.findall('testsuite')):
   for k in t:t[k]+=int(suite.get(k,0))
 counts[label]=t
for label,n in [('core',156),('android',440),('service',145)]:assert counts[label]==dict(tests=n,failures=0,errors=0,skipped=0),counts
issues=E.parse(CI/'app/build/reports/lint-results-release.xml').getroot().findall('issue')
lint={k:sum(i.get('severity')==k for i in issues) for k in ['Error','Fatal','Warning','Hint']};assert lint==dict(Error=0,Fatal=0,Warning=194,Hint=1)
browser=json.loads((R/'website-evidence/browser-results.json').read_text());assert browser['status']=='passed' and len(browser['checks'])==45
sig=(O/'signature-verification.txt').read_text();assert CERT in sig and 'v2 scheme (APK Signature Scheme v2): true' in sig and 'v3 scheme (APK Signature Scheme v3): true' in sig
with zipfile.ZipFile(CI/'review-input/mebot-0.8.2-unsigned.apk') as a,zipfile.ZipFile(apk) as b:
 assert a.namelist()==b.namelist() and b.testzip() is None
 for name in a.namelist():assert a.read(name)==b.read(name),name
 assert not any(n.endswith(('mebot-profile.json','.p12','.jks','.keystore')) for n in b.namelist())
source=O/f'mebot-{V}-source.zip';subprocess.run(['git','archive','--format=zip','--prefix=MeBot/','-o',str(source),'HEAD'],cwd=R,check=True)
forbidden=('.p12','.pfx','.jks','.keystore','.ttf','.otf','.woff','.woff2','.apk','.aab')
with zipfile.ZipFile(source) as z:
 assert z.testzip() is None
 for n in z.namelist():
  assert not n.lower().endswith(forbidden),n
  assert not any(s in n.lower() for s in ['signing-details.json','mebot-personal-context.json','mebot-profile.json','private-signing']),n
report=f'''MeBot 0.8.2 — official Android release

Application: MeBot
Application ID / namespace: mebot.nguli.com
Version code: 13
Minimum Android: 8.0 / API26
Tested runtime source: {TESTED}
Final tagged source: {commit}
Independent CI: https://github.com/EmmanuelMmanda/MeBot/actions/runs/40170141233
Environment: JDK17, Gradle8.13, SDK36, BuildTools35.0.0.

741 passed tests: 156 core + 440 Android-host + 145 service.
Failures:0. Errors:0. Skipped:0. No test exclusions.
Release compilation passed.
Release lint: 0 errors, 194 warnings, 1 hint.
Website:45 browser scenarios passed with real local HTTP serving in GitHub CI.
Local layout fixtures were also checked in-memory; localhost navigation was
restricted in the local browser, so deployed-site behavior is not inferred from it.
Static documentation, relative links, gallery images and download wiring checked.

APK SHA-256: {APK_SHA}
APK bytes: {apk.stat().st_size}
Signer certificate SHA-256: {CERT}
Android apksigner v2/v3 signature checks, 16 KiB alignment, package/name/code,
minimum SDK, non-debuggable flag and ZIP integrity verified.
Every application ZIP entry matches the independently tested unsigned APK.
Runtime tree matches the tested source; publication changes are documentation,
release tooling and evidence only. Source ZIP is the exact release-tag commit.
No private key, provider token or private starter profile is distributed.

This is a NEW Android installation identity, not an in-place update from
app.mynguli. Reconnect accounts and review preferences and alarm registrations.
Future mebot.nguli.com updates preserve package/certificate and advance code.

Provider/OS limits:
Live paid inference, browser account authorization, real-device media playback,
Android save/installation approval and overnight alarms were not exercised here.
Claude uses API access, not Claude.ai subscription credentials. OpenRouter uses
its own credits. Gemini uses supplied public sources, not native Google Search
grounding. Compatible endpoints must declare capabilities. The optional Python
service remains OpenAI-backed. No silent provider/billing fallback exists.
Screenshots use dated public sources and controlled request state. They are
native host renders, not physical-phone or live-account recordings.
Resource downloads remain bounded to20 MiB; software APKs to100 MiB.
Public file availability can change. Authentication and paywalls are not bypassed.
Long-term recommendation quality needs real-user evaluation beyond unit tests.
'''
(O/f'mebot-{V}-verification.txt').write_text(report)
for p in (R/'docs/screenshots/v082').glob('*.png'):shutil.copy2(p,O/f'mebot-{V}-{p.name}')
with zipfile.ZipFile(O/f'mebot-{V}-documentation.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted((R/'docs').rglob('*')):
  if p.is_file() and not p.name.lower().endswith(forbidden):z.write(p,'MeBot-docs/'+str(p.relative_to(R/'docs')))
 z.write(R/'START-HERE.md','START-HERE.md')
with zipfile.ZipFile(O/f'mebot-{V}-resources.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in (R/'docs/screenshots/v082').glob('*'):
  if p.is_file():z.write(p,'screenshots/'+p.name)
 for pattern in ['core/build/test-results/test/*.xml','app/build/test-results/testDebugUnitTest/*.xml','app/build/reports/lint-results-release.xml','service/test-results.xml']:
  for p in CI.glob(pattern):z.write(p,'evidence/'+str(p.relative_to(CI)))
 for p in (R/'website-evidence').rglob('*'):
  if p.is_file():z.write(p,'website/'+str(p.relative_to(R/'website-evidence')))
 for name in ['signature-verification.txt','alignment-verification.txt','apk-badging.txt',f'mebot-{V}-verification.txt']:z.write(O/name,'evidence/'+name)
 for name in ['USER-GUIDE.md','RELEASE-TRACKER-0.8.2.md','IMAGE-CREDITS.md','RELEASE-0.8.2.md']:z.write(R/'docs'/name,name)
 z.write(R/'docs/captures/v082/sources.json','capture-provenance.json')
assets=[apk,source,O/f'mebot-{V}-resources.zip',O/f'mebot-{V}-documentation.zip',O/f'mebot-{V}-verification.txt',*sorted(O.glob(f'mebot-{V}-*.png'))]
metadata=dict(schemaVersion=1,version=V,versionCode=13,applicationId='mebot.nguli.com',minimumSdk=26,signerCertificateSha256=CERT,sourceCommit=commit,applicationSourceCommit=TESTED,ciRun=40170141233,tests=counts,lint=lint,browserScenarios=45,assets=[dict(name=p.name,bytes=p.stat().st_size,sha256=sha(p)) for p in assets])
meta=O/'release-metadata.json';meta.write_text(json.dumps(metadata,indent=2)+'\n');assets.append(meta)
checks=O/'SHA256SUMS.txt';checks.write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(assets)));assets.append(checks)
for p in assets:
 if p.suffix=='.zip':
  with zipfile.ZipFile(p) as z:assert z.testzip() is None
(R/'website-evidence/public-assets.txt').write_text('\n'.join(map(str,assets))+'\n')
print(f'Packaged {len(assets)} verified public files for {commit}.')
