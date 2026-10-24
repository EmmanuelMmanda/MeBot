#!/usr/bin/env python3
"""Fetch a pinned public build resource; no font binaries are shipped in source archives."""
from pathlib import Path
import hashlib
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
URL = "https://raw.githubusercontent.com/google/fonts/7888febb355d9e2c2c4aa357d20734e383f2198f/ofl/figtree/Figtree%5Bwght%5D.ttf"
SHA256 = "26ad3db9b31ff7dde67a91ff515d022d2f495cd506590699cf264f0bfe6fb714"
DEST = ROOT / "app/src/main/res/font/figtree.ttf"

def main() -> None:
    if DEST.is_file() and hashlib.sha256(DEST.read_bytes()).hexdigest() == SHA256:
        print("Build resource verified.")
        return
    with urllib.request.urlopen(URL, timeout=30) as response:
        data = response.read(2_000_001)
    if len(data) > 2_000_000 or hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit("Build resource checksum mismatch; refusing to install.")
    DEST.parent.mkdir(parents=True, exist_ok=True)
    temporary = DEST.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.replace(DEST)
    print("Pinned build resource downloaded and verified. See docs/FONT-LICENSE.txt.")

if __name__ == "__main__":
    main()
