#!/usr/bin/env python3
import re, sys, json
from pathlib import Path
PATTERNS = [r"demonware", r"activision", r"callofduty", r"cdn\.", r"analytic", r"manifest", r"shard", r"xpak", r"https?://", r"wss://", r":3074", r"DemonwarePortMapping", r"bhvronline"]
rx = re.compile("|".join(PATTERNS), re.I)
path = Path(sys.argv[1]) if len(sys.argv)>1 else None
lines = path.read_text(errors="ignore").splitlines() if path else sys.stdin.read().splitlines()
hits = [l for l in lines if rx.search(l)]
print(json.dumps({"total": len(lines), "hits": len(hits), "matches": hits[:500]}, indent=2, ensure_ascii=False))
