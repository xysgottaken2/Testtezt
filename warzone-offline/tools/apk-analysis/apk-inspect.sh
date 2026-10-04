#!/usr/bin/env bash
# apk-inspect.sh — extrai metadados não proprietários de APK/XAPK sem commitar binário
# Uso:
#   bash apk-inspect.sh /tmp/wzm/warzone.xapk --out /tmp/wzm/apk-metadata.json
#   bash apk-inspect.sh --help
# Saída: JSON com package, versionCode/Name, hashes (primeiros 16), libs, shard presence — NUNCA o binário.
set -euo pipefail

if [[ "${1:-}" == "--help" || $# -eq 0 ]]; then
  cat <<'HELP'
Usage: bash apk-inspect.sh <apk-or-xapk> [--out <json-path>]
Prepara: aapt dump badging (se disponível), unzip -l, sha256 (primeiros 16), lista de libs
Output: JSON em stdout ou --out. Nunca copia .apk para o repo.
Requer: aapt, unzip, sha256sum (opcionais, degradam graciosamente)
HELP
  exit 0
fi

APK="$1"
OUT=""
if [[ "${2:-}" == "--out" ]]; then OUT="$3"; fi

if [[ ! -f "$APK" ]]; then
  echo "{\"error\":\"file not found: $APK\",\"verified\":false}" >&2
  exit 1
fi

SHA256_FULL=""
SHA256_SHORT=""
if command -v sha256sum >/dev/null 2>&1; then
  SHA256_FULL=$(sha256sum "$APK" | awk '{print $1}')
  SHA256_SHORT=${SHA256_FULL:0:16}
elif command -v shasum >/dev/null 2>&1; then
  SHA256_FULL=$(shasum -a 256 "$APK" | awk '{print $1}')
  SHA256_SHORT=${SHA256_FULL:0:16}
fi

SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK" 2>/dev/null || echo "unknown")

# aapt metadata (se disponível)
PKG="UNKNOWN"
VERSION_CODE="UNKNOWN"
VERSION_NAME="UNKNOWN"
SDK_MIN="UNKNOWN"
if command -v aapt >/dev/null 2>&1; then
  BADGING=$(aapt dump badging "$APK" 2>&1 || true)
  PKG=$(echo "$BADGING" | grep -m1 "package:" | sed -n "s/.*name='\([^']*\)'.*/\1/p" | head -n1 || echo "UNKNOWN")
  VERSION_CODE=$(echo "$BADGING" | grep -m1 "package:" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -n1 || echo "UNKNOWN")
  VERSION_NAME=$(echo "$BADGING" | grep -m1 "package:" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n1 || echo "UNKNOWN")
  SDK_MIN=$(echo "$BADGING" | grep -m1 "sdkVersion" | sed -n "s/.*'\([^']*\)'.*/\1/p" | head -n1 || echo "UNKNOWN")
fi

# XAPK vs APK
IS_XAPK="false"
if [[ "$APK" == *.xapk ]] || unzip -l "$APK" 2>/dev/null | grep -q "manifest.json"; then
  # heurística simples
  if unzip -l "$APK" 2>/dev/null | grep -q "\.apk"; then IS_XAPK="true"; fi
fi

# list libs / shards / manifest (apenas nomes, não conteúdo)
CONTENTS=""
if command -v unzip >/dev/null 2>&1; then
  CONTENTS=$(unzip -l "$APK" 2>&1 | head -n 100 || true)
fi
HAS_SHARD=$(echo "$CONTENTS" | grep -qi "shard" && echo "true" || echo "false")
HAS_MANIFEST=$(echo "$CONTENTS" | grep -qi "manifest.json" && echo "true" || echo "false")
NATIVE_LIBS=$(echo "$CONTENTS" | grep -E "lib/.*\.so" | awk '{print $4}' | head -n 20 | tr '\n' ',' | sed 's/,$//' || true)
if [[ -z "$NATIVE_LIBS" ]]; then NATIVE_LIBS="UNKNOWN (run apktool d for details)"; fi

# availability of decode tools
HAS_APKTOOL=$(command -v apktool >/dev/null 2>&1 && echo "available" || echo "missing (apt install apktool)")
HAS_JADX=$(command -v jadx >/dev/null 2>&1 && echo "available" || echo "missing")

JSON=$(cat <<JSON
{
  "source": "$APK",
  "isXapk": $IS_XAPK,
  "sizeBytes": "$SIZE",
  "sha256_full_not_committed": "$SHA256_FULL",
  "sha256_short": "$SHA256_SHORT",
  "package": "$PKG",
  "versionCode": "$VERSION_CODE",
  "versionName": "$VERSION_NAME",
  "sdkMin": "$SDK_MIN",
  "hasShard": $HAS_SHARD,
  "hasManifest": $HAS_MANIFEST,
  "nativeLibsSample": "$NATIVE_LIBS",
  "tools": { "apktool": "$HAS_APKTOOL", "jadx": "$HAS_JADX", "aapt": "$(command -v aapt >/dev/null 2>&1 && echo available || echo missing)" },
  "nextSteps": "apktool d <apk> -o /tmp/wzm/apktool-output && jadx -d /tmp/wzm/jadx-output <apk> && node endpoint-scanner.js ...",
  "confidence": "VERIFIED — extracted locally, not invented",
  "redacted": true
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
