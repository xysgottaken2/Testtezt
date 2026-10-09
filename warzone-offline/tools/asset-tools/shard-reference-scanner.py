#!/usr/bin/env python3
"""
shard-reference-scanner.py — M2.2.1
Inventaria *referências* a shards dentro de um APK/XAPK extraído, sem brute force.

Uso:
  python3 shard-reference-scanner.py /tmp/wzm/apktool-output --out /tmp/wzm/shard-refs.json
  python3 shard-reference-scanner.py --fixtures  # testa com fixture sintético
  unzip -l /tmp/wzm/warzone.xapk | python3 shard-reference-scanner.py --stdin-zip-list

O que faz (sem baixar shards):
  1) Lista física: **/*.shard, **/*.xpak, **/manifest.json (via shard-inventory)
  2) Lista lógica: grep em todos os arquivos por padrões de referência:
     - shard_cdn
     - \.shard\b
     - \.xpak\b
     - manifest\.json
     - cdni\.meta
     - _manifest/
     - IWff (header)
  3) Emite JSON com SOURCE/EVIDENCE/CONFIDENCE, nunca o binário.

Regras M2.2.1: sem brute force, sem auth, sem baixar shards. Só inspeção ZIP/apktool local.
"""
import sys, re, json, hashlib
from pathlib import Path

PATTERNS = [
    (r"shard_cdn", "shard_cdn URL", "VERIFIED — encontrado em código"),
    (r"cdni\.meta", "cdni.meta", "VERIFIED — encontrado em código"),
    (r"_manifest/", "_manifest dir", "VERIFIED — encontrado em código"),
    (r"\.shard\b", ".shard filename", "VERIFIED — encontrado em código"),
    (r"\.xpak\b", ".xpak filename", "VERIFIED — encontrado em código"),
    (r"manifest\.json", "manifest.json", "VERIFIED — encontrado em código"),
    (r"split_asset_pack", "split asset pack", "VERIFIED — encontrado em código"),
    (r"IWff", "IWff header", "VERIFIED — encontrado em binário"),
    (r"KAPIs", "KAPIs xpak", "VERIFIED — encontrado em binário"),
    (r"prod\.cdni\.callofduty\.com", "CDNI host", "VERIFIED — WZM 3.10.0"),
    (r"build-selector-[0-9]+\.js", "build-selector", "VERIFIED — WZM bootstrap"),
]

# Regex para extrair nomes de shard plausíveis quando encontrados em texto
SHARD_NAME_RE = re.compile(r"[a-zA-Z0-9_\-./]*\.shard\b")
XPAK_NAME_RE = re.compile(r"[a-zA-Z0-9_\-./]*\.xpak\b")

def scan_text(content: str, rel_path: str):
    findings = []
    for pat, label, conf in PATTERNS:
        for m in re.finditer(pat, content, flags=re.IGNORECASE):
            start = max(0, m.start()-60)
            end = min(len(content), m.end()+60)
            snippet = content[start:end].replace("\n"," ").strip()[:200]
            findings.append({
                "pattern": label,
                "match": m.group(0)[:200],
                "snippet": snippet,
                "file": rel_path,
                "confidence": conf
            })
    # extrair nomes completos
    for m in SHARD_NAME_RE.finditer(content):
        findings.append({
            "pattern": "shard_filename_extracted",
            "match": m.group(0)[-200:],
            "snippet": content[max(0,m.start()-30):m.end()+30].replace("\n"," ")[:200],
            "file": rel_path,
            "confidence": "VERIFIED — nome .shard extraído de referência"
        })
    for m in XPAK_NAME_RE.finditer(content):
        findings.append({
            "pattern": "xpak_filename_extracted",
            "match": m.group(0)[-200:],
            "snippet": content[max(0,m.start()-30):m.end()+30].replace("\n"," ")[:200],
            "file": rel_path,
            "confidence": "VERIFIED — nome .xpak extraído"
        })
    return findings

def inventory(root: Path):
    root = root.resolve()
    if not root.exists():
        return {"error": f"path not found: {root}", "verified": False}
    files = []
    # list all files recursively, sample up to 5000 to avoid explosion
    for p in root.rglob("*"):
        if p.is_file():
            # only text-ish or small binary; skip huge .shard/xpak content (we only header)
            if p.stat().st_size > 20*1024*1024 and p.suffix in (".shard",".xpak"):
                continue
            if p.suffix in (".java",".kt",".xml",".json",".txt",".js",".ts",".smali",".cfg",".ini",".meta",".so",".dex") or p.name in ("AndroidManifest.xml","manifest.json","cdni.meta"):
                files.append(p)
            elif p.suffix == "" or p.suffix in (".bin",):
                files.append(p)
            if len(files) > 5000:
                break
    findings = []
    physical_shards = []
    for pattern in ["**/*.shard", "**/*.xpak", "**/manifest.json"]:
        for p in root.glob(pattern):
            rel = str(p.relative_to(root))
            stat = p.stat()
            # header first 16 bytes
            try:
                with open(p,"rb") as f:
                    h = f.read(16)
                header_hex = h[:12].hex() + (" | " + h[:8].decode(errors="replace") if h else "")
            except: header_hex = "error"
            physical_shards.append({
                "path": rel,
                "size": stat.st_size,
                "header": header_hex if p.suffix in (".shard",".xpak") else None
            })
    for f in files:
        try:
            content = f.read_text(encoding="utf-8", errors="replace")
        except:
            try:
                content = f.read_text(encoding="latin-1")
            except: continue
        # skip huge files content > 1MB for grep (truncate)
        if len(content) > 1024*1024:
            content = content[:1024*1024]
        findings.extend(scan_text(content, str(f.relative_to(root))))
    # dedup
    seen=set()
    dedup=[]
    for e in findings:
        key=(e["pattern"], e["match"], e["file"])
        if key not in seen:
            seen.add(key)
            dedup.append(e)
    # summarize shard names proven present
    proven_shard_names = sorted(set(
        e["match"] for e in dedup if e["pattern"]=="shard_filename_extracted"
    ))
    proven_xpak_names = sorted(set(
        e["match"] for e in dedup if e["pattern"]=="xpak_filename_extracted"
    ))
    return {
        "root": str(root),
        "scannedFiles": len(files),
        "physicalShards": {"count": len(physical_shards), "shards": sorted(physical_shards, key=lambda x: x["size"], reverse=True)[:50]},
        "findings": dedup[:500],
        "provenShardNames": proven_shard_names[:100],
        "provenXpakNames": proven_xpak_names[:100],
        "summary": {
            "hasPhysicalShard": len(physical_shards)>0,
            "hasShardReference": any(e["pattern"]==".shard filename" for e in dedup),
            "hasShardCdn": any(e["pattern"]=="shard_cdn URL" for e in dedup),
            "hasCdniMetaRef": any(e["pattern"]=="cdni.meta" for e in dedup),
        },
        "confidence": "VERIFIED — inspeção ZIP/apktool local, sem brute force"
    }

if __name__ == "__main__":
    import argparse, tempfile, os
    ap = argparse.ArgumentParser(description="M2.2.1 shard reference scanner")
    ap.add_argument("root", nargs="?", default=".", help="apktool-output dir")
    ap.add_argument("--out", help="output json path")
    ap.add_argument("--stdin-zip-list", action="store_true", help="read unzip -l output from stdin")
    ap.add_argument("--fixtures", action="store_true", help="run synthetic fixture")
    args = ap.parse_args()

    if args.fixtures:
        # create synthetic fixture to prove tool works without real APK
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            (td/"assets/bootstrap").mkdir(parents=True)
            (td/"assets/bootstrap/index.html").write_text('<script src="https://prod.cdni.callofduty.com/manifest/build-selector-103.js"></script>')
            (td/"lib/arm64-v8a").mkdir(parents=True)
            (td/"lib/arm64-v8a/libgame.so").write_text("shard_cdn android _manifest cdni.meta IWffn100 KAPIs")
            (td/"assets/shard").mkdir(parents=True)
            (td/"assets/shard/manifest.json").write_text('{"shards":["base.shard","textures_00.shard"]}')
            (td/"assets/shard/base.shard").write_bytes(b"IWffn100 fake shard content")
            (td/"assets/shard/textures_00.shard").write_bytes(b"IWffn100 more")
            (td/"res/xml").mkdir(parents=True)
            (td/"res/xml/network_security_config.xml").write_text("<network-security-config/>")
            # also add a smali with shard ref
            (td/"smali/com/activision").mkdir(parents=True)
            (td/"smali/com/activision/Bootstrap.smali").write_text("const-string v0, \"prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta\"")
            result = inventory(td)
            out = json.dumps(result, indent=2, ensure_ascii=False)
            print(out)
            if args.out:
                Path(args.out).write_text(out, encoding="utf-8")
        sys.exit(0)

    if args.stdin_zip_list:
        content = sys.stdin.read()
        findings = scan_text(content, "unzip -l output")
        proven = sorted(set(e["match"] for e in findings if e["pattern"]=="shard_filename_extracted"))
        print(json.dumps({"source":"unzip -l stdin","findings":findings[:200],"provenShardNames":proven[:100]}, indent=2, ensure_ascii=False))
        sys.exit(0)

    root = Path(args.root)
    result = inventory(root)
    out = json.dumps(result, indent=2, ensure_ascii=False)
    print(out)
    if args.out:
        Path(args.out).write_text(out, encoding="utf-8")
        print(f"\nWrote {args.out}", file=sys.stderr)
