#!/usr/bin/env python3
"""Assemble an already signed APK without giving CI a private signing key.
The signature was produced locally with Android apksigner and alignment preserved.
Input/output hashes and all application entry bytes must match. This cannot sign
another APK. Always run apksigner and certificate/package checks afterwards.
"""
from pathlib import Path
import argparse,base64,hashlib,io,json,struct,zlib,zipfile
p=argparse.ArgumentParser();p.add_argument('manifest',type=Path);p.add_argument('unsigned',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
m=json.loads(args.manifest.read_text());u=args.unsigned.read_bytes()
assert m['schemaVersion']==2
assert hashlib.sha256(u).hexdigest()==m['unsignedSha256'], 'Unsigned APK mismatch'
e=u.rfind(b'PK\x05\x06');assert e>=0 and e+22+struct.unpack_from('<H',u,e+20)[0]==len(u)
c=struct.unpack_from('<I',u,e+16)[0];assert c==m['centralDirectoryOffset'] and 0<c<e
assert 0<m['blockBytes']<65536
block=zlib.decompress(base64.b64decode(m['publicSignatureBlockZlibBase64'],validate=True))
assert len(block)==m['blockBytes'] and block.endswith(b'APK Sig Block 42')
result=bytearray(u[:c]+block+u[c:]);struct.pack_into('<I',result,e+len(block)+16,c+len(block))
assert len(result)==m['signedSizeBytes'] and hashlib.sha256(result).hexdigest()==m['signedSha256'], 'Signed APK mismatch'
with zipfile.ZipFile(io.BytesIO(u)) as a,zipfile.ZipFile(io.BytesIO(result)) as b:
 assert a.namelist()==b.namelist() and b.testzip() is None
 for name in a.namelist():assert a.read(name)==b.read(name),name
 assert not any(n.endswith(('mebot-profile.json','signing-details.json','.p12','.jks','.keystore')) for n in b.namelist())
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_bytes(result)
print(f"Official signed file verified: {m['signedSha256']} ({len(result)} bytes). Application entries unchanged.")
