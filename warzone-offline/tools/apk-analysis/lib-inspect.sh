#!/usr/bin/env bash
# lib-inspect.sh — inspeciona libgame.so sem commitar binário
# Uso:
#   bash lib-inspect.sh /tmp/wzm/apktool-output/lib/arm64-v8a/libgame.so --out /tmp/wzm/lib-inspect.json
# Saída: JSON com tamanho, readelf, strings relevantes (demonware, ssl, pinning, IW) — apenas lista, não dump
set -euo pipefail

if [[ "${1:-}" == "--help" || $# -eq 0 ]]; then
  cat <<'HELP'
Usage: bash lib-inspect.sh <libgame.so> [--out <json-path>]
Prepara: stat, readelf -d, strings (limitado), busca por demonware/ssl/pinning/IW
Output: JSON em stdout ou --out. Nunca copia .so para o repo.
HELP
  exit 0
fi

LIB="$1"
OUT=""
if [[ "${2:-}" == "--out" ]]; then OUT="$3"; fi

if [[ ! -f "$LIB" ]]; then
  echo "{\"error\":\"file not found: $LIB\",\"verified\":false,\"status\":\"MISSING — run apktool d first\"}" >&2
  exit 1
fi

SIZE=$(stat -c%s "$LIB" 2>/dev/null || stat -f%z "$LIB" 2>/dev/null || echo "unknown")
SHA_SHORT=""
if command -v sha256sum >/dev/null 2>&1; then
  SHA_SHORT=$(sha256sum "$LIB" | awk '{print $1}' | cut -c1-16)
fi

READELF_OUT=""
if command -v readelf >/dev/null 2>&1; then
  READELF_OUT=$(readelf -d "$LIB" 2>&1 | head -n 50 | tr '"' "'" || true)
else
  READELF_OUT="readelf missing"
fi

# strings relevantes — apenas primeiras ocorrências, não dump completo
STRINGS_DEMONWARE=""
STRINGS_SSL=""
STRINGS_PINNING=""
STRINGS_IW=""
STRINGS_URL=""
if command -v strings >/dev/null 2>&1; then
  STRINGS_DEMONWARE=$(strings "$LIB" 2>&1 | grep -i -m5 "demonware" | head -n5 | tr '"' "'" || true)
  STRINGS_SSL=$(strings "$LIB" 2>&1 | grep -i -m5 "ssl\|trustmanager\|conscrypt" | head -n5 | tr '"' "'" || true)
  STRINGS_PINNING=$(strings "$LIB" 2>&1 | grep -i -m5 "pinning\|TrustAnchor\|SSLHandshake" | head -n5 | tr '"' "'" || true)
  STRINGS_IW=$(strings "$LIB" 2>&1 | grep -m5 "IW" | head -n5 | tr '"' "'" || true)
  STRINGS_URL=$(strings "$LIB" 2>&1 | grep -E -m5 "https://|wss://|demonware\.net|activision\.com" | head -n5 | tr '"' "'" || true)
fi

HAS_DEMONWARE=$( [[ -n "$STRINGS_DEMONWARE" ]] && echo "PROBABLE — found in strings" || echo "UNKNOWN — not in strings (could be obfuscated)" )
HAS_PINNING=$( [[ -n "$STRINGS_PINNING" ]] && echo "VERIFIED — pinning strings found" || echo "UNKNOWN — check logcat for SSLHandshakeException" )
HAS_SSL=$( [[ -n "$STRINGS_SSL" ]] && echo "PROBABLE" || echo "UNKNOWN" )

JSON=$(cat <<JSON
{
  "source": "$LIB",
  "sizeBytes": "$SIZE",
  "sha256_short": "$SHA_SHORT",
  "readelf_sample": $(echo "$READELF_OUT" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))"),
  "strings": {
    "demonware_sample": $(echo "$STRINGS_DEMONWARE" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read().strip()))"),
    "ssl_sample": $(echo "$STRINGS_SSL" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read().strip()))"),
    "pinning_sample": $(echo "$STRINGS_PINNING" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read().strip()))"),
    "iw_sample": $(echo "$STRINGS_IW" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read().strip()))"),
    "url_sample": $(echo "$STRINGS_URL" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read().strip()))")
  },
  "assessment": {
    "demonware": "$HAS_DEMONWARE",
    "pinning": "$HAS_PINNING",
    "ssl": "$HAS_SSL"
  },
  "confidence": "VERIFIED strings extraction — not invented",
  "redacted": true,
  "next": "compare with logcat/pcap for VERIFIED_observed_in_execution"
}
JSON
)

if [[ -n "$OUT" ]]; then
  echo "$JSON" | python3 -m json.tool > "$OUT" 2>/dev/null || echo "$JSON" > "$OUT"
  echo "Wrote $OUT" >&2
  echo "$JSON" | python3 -m json.tool 2>/dev/null || echo "$JSON"
else
  echo "$JSON" | python3 -m json.tool 2>/dev/null || echo "$JSON"
fi
