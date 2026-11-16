#!/usr/bin/env python3
"""Fail-closed checks for the fixed, already signed 0.10.1 release; no keys."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

SOURCE = 'd0b054ec8fad5b97b34664edd63fbaacf21b40aa'
CERT = 'c665c578ecf64f33541670d94f099ee435db1e55790395ff0339f2bdf231d007'
SHA = '38c011bb6c84110af4539341a89ba4a623914a70a91b5db3a2a66fd0c5c72861'
TMP = Path(os.environ['RUNNER_TEMP'])
OUT = TMP / 'mebot-release'
META = json.loads(Path('release/0.10.1/release-metadata.json').read_text())
assert META['sourceCommit'] == SOURCE
assert META['applicationId'] == 'mebot.nguli.com'
assert META['version'] == '0.10.1' and META['versionCode'] == 28
assert META['signerCertificateSha256'] == CERT
assert META['assets'] == [{'name': 'mebot-0.10.1.apk', 'sha256': SHA, 'bytes': 20864712}]
mode = sys.argv[1]
if mode == 'provenance':
    run = json.loads((TMP / 'run.json').read_text())
    artifact = json.loads((TMP / 'artifact.json').read_text())
    assert run['id'] == 43956622167 and run['status'] == 'completed' and run['conclusion'] == 'success'
    assert run['head_sha'] == SOURCE
    assert run['repository']['full_name'] == 'EmmanuelMmanda/MeBot'
    assert artifact['id'] == 14273057049 and not artifact['expired']
    assert artifact['workflow_run']['id'] == run['id']
    assert artifact['workflow_run']['head_sha'] == SOURCE
    assert artifact['digest'] == 'sha256:cf658b67f2ccdfe9aa4510d9a968170ec7bf0b37755b8e42aa2982c405c996a0'
elif mode == 'package':
    signature = (OUT / 'signature-verification.txt').read_text()
    assert 'Signer #1 certificate SHA-256 digest: ' + CERT in signature
    assert 'Number of signers: 1' in signature
    for scheme in ('v2', 'v3'):
        assert f'Verified using {scheme} scheme (APK Signature Scheme {scheme}): true' in signature
    badging = (OUT / 'apk-badging.txt').read_text()
    assert "package: name='mebot.nguli.com' versionCode='28' versionName='0.10.1'" in badging
    assert "application-label:'MeBot'" in badging and "sdkVersion:'26'" in badging
    assert 'application-debuggable' not in badging
    apk = (OUT / 'mebot-0.10.1.apk').read_bytes()
    assert len(apk) == 20864712 and hashlib.sha256(apk).hexdigest() == SHA
    shutil.copyfile('release/0.10.1/release-metadata.json', OUT / 'release-metadata.json')
    (OUT / 'SHA256SUMS.txt').write_text(''.join(
        f'{hashlib.sha256((OUT / name).read_bytes()).hexdigest()}  {name}\n'
        for name in ('mebot-0.10.1.apk', 'release-metadata.json')))
elif mode == 'assets':
    check = TMP / 'release-check'
    release = json.loads((check / 'release.json').read_text())
    assert release['tag_name'] == 'v0.10.1' and release['draft'] and not release['prerelease']
    assets = {a['name']: a for a in release['assets']}
    assert set(assets) == {'mebot-0.10.1.apk', 'release-metadata.json', 'SHA256SUMS.txt'}
    for name, asset in assets.items():
        expected = (OUT / name).read_bytes()
        assert (check / name).read_bytes() == expected, name
        assert asset['state'] == 'uploaded' and asset['size'] == len(expected), name
        assert asset['digest'] == 'sha256:' + hashlib.sha256(expected).hexdigest(), name
else:
    raise SystemExit('Expected provenance, package or assets')
print(f'0.10.1 {mode} verification passed')
