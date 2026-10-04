#!/usr/bin/env python3
"""
shard-inventory.py — inventaria shards de um XAPK/APK extraído
Uso: python3 shard-inventory.py /tmp/wzm/apktool-output
Saída: JSON com lista de shards, tamanhos e headers
Nunca incluir shards no repo — saída apenas local.
"""
import os
import sys
import json
import hashlib
from pathlib import Path

def header(path: Path, n=16) -> str:
    try:
        with open(path, "rb") as f:
            data = f.read(n)
        return data[:12].hex() + (" | " + data[:8].decode(errors="replace") if data else "")
    except Exception as e:
        return f"error: {e}"

def inventory(root: Path):
    shards = []
    for pattern in ["**/*.shard", "**/*.xpak", "**/manifest.json"]:
        for p in root.glob(pattern):
            stat = p.stat()
            sha = hashlib.sha256(p.read_bytes()[:1024*1024]).hexdigest()[:16] if p.stat().st_size > 0 else "empty"
            shards.append({
                "path": str(p.relative_to(root)),
                "size": stat.st_size,
                "sha256_prefix": sha,
                "header": header(p) if p.suffix in (".shard", ".xpak") else None,
            })
    shards.sort(key=lambda x: x["size"], reverse=True)
    return shards

if __name__ == "__main__":
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    if not root.exists():
        print(f"path not found: {root}", file=sys.stderr)
        sys.exit(1)
    inv = inventory(root)
    print(json.dumps({"root": str(root), "count": len(inv), "shards": inv}, indent=2, ensure_ascii=False))
    # também tenta detectar IWffn100
    iw = [s for s in inv if s.get("header") and "495766" in s["header"]]  # 'IWf' hex
    if iw:
        print(f"\n# IWff shards detected: {len(iw)}", file=sys.stderr)
