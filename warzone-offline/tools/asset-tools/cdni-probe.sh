#!/usr/bin/env bash
set -euo pipefail
# cdni-probe.sh — M2.2 investigação leve de endpoints CDNI/WZM
# Uso:
#   bash warzone-offline/tools/asset-tools/cdni-probe.sh --live     # tenta HEAD nos endpoints VERIFIED (usa curl; no sandbox TLS é bloqueado, ver M2.2 doc)
#   bash warzone-offline/tools/asset-tools/cdni-probe.sh --list     # só lista endpoints sem rede
#   bash warzone-offline/tools/asset-tools/cdni-probe.sh --help
#
# Regras M2.2: sem brute force, sem auth, sem baixar volumes grandes. Só HEAD/Range-0-1023, só URLs
# estaticamente encontradas (bootstrap) ou derivadas logicamente (manifest.json → boot-*/web/*, cdni.meta).

MODE="list"
for arg in "$@"; do
  case "$arg" in
    --live) MODE="live" ;;
    --list) MODE="list" ;;
    --help|-h) echo "Usage: $0 [--list|--live|--help]"; echo "  --list  só lista endpoints (sem rede)"; echo "  --live  tenta HEAD com curl (requer egress TLS; no sandbox falha com SSL_ERROR_SYSCALL - usar fetch_page manualmente)"; exit 0 ;;
  esac
done

# Endpoints VERIFIED M2.2 (não adivinhar shards)
ENDPOINTS=(
  "https://prod.cdni.callofduty.com/manifest/build-selector-103.js"
  "https://prod.cdni.callofduty.com/manifest/build-selector-102.js"
  "https://prod.cdni.callofduty.com/manifest/manifest.json"
  "https://prod.cdni.callofduty.com/manifest/manifest-popup.json"
  "https://prod.cdni.callofduty.com/manifest/manifest-events.json"
  "https://prod.cdni.callofduty.com/manifest/manifest-dailylogin.json"
  "https://prod.cdni.callofduty.com/static/web/index.html"
  "https://prod.cdni.callofduty.com/prelogin/boot-15.0.0/web/manifest.json"
  "https://prod.cdni.callofduty.com/prelogin/boot-15.0.0/web/static/js/main.js"
  "https://prod.cdni.callofduty.com/prelogin/boot-15.0.0/web/static/css/main.css"
  "https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta"
  "https://prod.cdni.callofduty.com/wzm/shard_cdn/ios/_manifest/cdni.meta"
)

if [ "$MODE" = "list" ]; then
  echo "== M2.2 cdni-probe --list (sem rede) =="
  printf "%s\n" "${ENDPOINTS[@]}"
  echo ""
  echo "Total: ${#ENDPOINTS[@]} endpoints VERIFIED M2.2 (ver docs/research/m2.2-assets-cdni-investigation.md)"
  echo "Para teste live: $0 --live  (curl -I --max-time 10 -s; se TLS bloqueado, ver fetch_page no doc)"
  exit 0
fi

echo "== M2.2 cdni-probe --live (HEAD leve, sem volume) =="
echo "Regra: sem auth, sem brute force, Range 0-1023 para evitar GB"
echo "Data: $(date -u +%Y-%m-%dT%H:%M:%SZ)  Host: $(hostname)"
echo ""

# Nota sobre sandbox: curl direto falha com SSL_ERROR_SYSCALL no sandbox Arena (egress filtrado).
# Em device/PC fora do sandbox, esses HEADs devem retornar 200/404.
# O serviço fetch_page externo foi usado para verificação M2.2 (ver doc §2.2).

for url in "${ENDPOINTS[@]}"; do
  echo "---"
  echo "URL: $url"
  # HEAD leve; para main.js/css usamos Range 0-1023 para não baixar volume grande
  if [[ "$url" == *"/main.js" ]] || [[ "$url" == *"/main.css" ]]; then
    curl -s -D - --max-time 15 -o /dev/null -H "Range: bytes=0-1023" -H "User-Agent: WZM-M2.2-probe" "$url" 2>&1 | head -n 20 || echo "curl failed (sandbox TLS bloqueado? ver doc §2.2 — fetch_page provou 200 fora do sandbox)"
  else
    curl -s -I --max-time 15 -H "User-Agent: WZM-M2.2-probe" "$url" 2>&1 | head -n 30 || echo "curl failed (sandbox TLS bloqueado? ver doc §2.2 — fetch_page provou 200 fora do sandbox)"
  fi
  # Também tenta get com Range pequeno para inferir tamanho sem volume (opcional)
  # Evita baixar shard: não lista nenhum *.shard aqui
done

echo ""
echo "== done =="
echo "Classificação: todos acima VERIFIED via fetch_page em 2026-10-03 (ver docs/research/m2.2-assets-cdni-investigation.md §3)."
echo "Shards (*.shard): UNKNOWN — requer lista exata do APK (shard-inventory.py) + HEAD por nome (sem brute force)."
