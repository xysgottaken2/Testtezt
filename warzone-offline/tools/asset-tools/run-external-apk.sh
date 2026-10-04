#!/usr/bin/env bash
set -euo pipefail
# run-external-apk.sh — M2.2.1 adaptado para APK/XAPK externo (S23) sem commit
# Uso no SEU S23 (Termux) ou no PC com arquivo transferido temporariamente:
#
#   # S23 via Termux (sem copiar para o Git):
#   bash warzone-offline/tools/asset-tools/run-external-apk.sh \
#     --apk /storage/emulated/0/Download/warzone-3.10.0.19854920.xapk \
#     --out-dir /storage/emulated/0/Download/wzm-out
#
#   # PC após adb pull temporário (fora do repo):
#   adb pull /data/app/com.activision.callofduty.warzone-*/base.apk /tmp/wzm/warzone.apk
#   bash warzone-offline/tools/asset-tools/run-external-apk.sh --apk /tmp/wzm/warzone.apk --out-dir /tmp/wzm/out
#
#   # Só ZIP list sem extrair (mais leve, sem apktool):
#   bash warzone-offline/tools/asset-tools/run-external-apk.sh --apk /storage/.../warzone.xapk --zip-only
#
# Regras M2.2.1: NUNCA copiar APK/XAPK/*.shard para dentro de /home/user/Testtezt
# (.gitignore bloqueia, mas este script recusa --apk dentro do repo para evitar erro humano).
# Saídas são apenas *.json com hashes/metadados (sem binário), em --out-dir FORA do repo.
# Não armazena nem publica APK no repositório.

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
APK=""
OUT_DIR=""
ZIP_ONLY="false"
CHECK_CDN="false"

# parse
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --zip-only) ZIP_ONLY="true"; shift ;;
    --check-cdn) CHECK_CDN="true"; shift ;;
    --help|-h) cat <<'HELP'
Uso: bash run-external-apk.sh --apk <caminho-externo> [--out-dir <fora-do-repo>] [--zip-only] [--check-cdn]

  --apk <path>     Caminho ABSOLUTO do APK/XAPK fora do repo.
                   Ex: /storage/emulated/0/Download/warzone.xapk  (S23 Termux)
                   ou  /tmp/wzm/warzone.apk                        (PC)
  --out-dir <dir>  Pasta FORA do repo para JSONs (default: /tmp/wzm/out ou /storage/.../wzm-out)
  --zip-only       Só faz unzip -l + scanner de zip list (sem extrair, sem apktool)
  --check-cdn      Após inventário, faz HEAD/Range para cada shard provado (sem volume)
  --help           esta ajuda

Regras: --apk NÃO pode estar dentro do repo (recusa). --out-dir NÃO pode estar dentro do repo.
Saídas: apk-metadata.json, zip-list.json, physical-shards.json, shard-refs.json, (shard-cdn.log se --check-cdn)
HELP
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$APK" ]]; then
  echo "ERRO: --apk obrigatório. Ex: --apk /storage/emulated/0/Download/warzone.xapk" >&2
  exit 1
fi

# valida APK existe
if [[ ! -f "$APK" ]]; then
  echo "ERRO: arquivo não encontrado: $APK" >&2
  echo "Dica S23: ls /storage/emulated/0/Download/*.xapk" >&2
  echo "Dica PC: adb pull /data/app/com.activision.callofduty.warzone-*/base.apk /tmp/wzm/warzone.apk" >&2
  exit 1
fi

# recusa APK dentro do repo
if [[ "$APK" == "$REPO_ROOT"* ]]; then
  echo "ERRO: --apk não pode estar dentro do repo: $APK" >&2
  echo "Isso evitaria commit acidental (.gitignore bloqueia, mas recusamos aqui)." >&2
  echo "Mova para fora: cp \"$APK\" /tmp/wzm/  e use --apk /tmp/wzm/$(basename "$APK")" >&2
  echo "Ou use original externo: /storage/emulated/0/Download/... ou /tmp/wzm/..." >&2
  exit 2
fi

# default out-dir fora do repo
if [[ -z "$OUT_DIR" ]]; then
  if [[ -d "/storage/emulated/0" ]]; then
    OUT_DIR="/storage/emulated/0/Download/wzm-out"
  else
    OUT_DIR="/tmp/wzm/out"
  fi
fi

# recusa out-dir dentro do repo
if [[ "$OUT_DIR" == "$REPO_ROOT"* ]]; then
  echo "ERRO: --out-dir não pode estar dentro do repo: $OUT_DIR" >&2
  echo "Use: /tmp/wzm/out ou /storage/emulated/0/Download/wzm-out" >&2
  exit 2
fi

mkdir -p "$OUT_DIR"
echo "== M2.2.1 run-external-apk (sem copiar para Git) =="
echo "APK externo: $APK"
echo "Out (fora do repo): $OUT_DIR"
echo "Repo: $REPO_ROOT (não será tocado)"
echo "Data: $(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date)"
echo ""

# 1. ZIP list leve (sem extrair)
echo "[1/5] unzip -l (ZIP inspection, sem volume)"
UNZIP_JSON="$OUT_DIR/zip-list.json"
if command -v unzip >/dev/null 2>&1; then
  # captura nomes, não conteúdo
  if unzip -l "$APK" > "$OUT_DIR/unzip-l.txt" 2>&1; then
    wc -l "$OUT_DIR/unzip-l.txt" | awk '{print "unzip -l lines:", $1}'
    grep -i -E "\.shard|\.xpak|manifest\.json|shard_cdn|_manifest" "$OUT_DIR/unzip-l.txt" | head -n 50 > "$OUT_DIR/unzip-filtered.txt" || true
    echo "  -> $OUT_DIR/unzip-l.txt + unzip-filtered.txt"
    # também gera json via scanner stdin
    if [[ -f "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-reference-scanner.py" ]]; then
      cat "$OUT_DIR/unzip-l.txt" | python3 "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-reference-scanner.py" --stdin-zip-list > "$UNZIP_JSON" 2>&1 || true
      echo "  -> $UNZIP_JSON (zip-refs)"
    fi
  else
    echo "  unzip -l falhou (APK pode ser APKM bundle? tente apktool)" >&2
  fi
else
  echo "  unzip não encontrado (Termux: pkg install unzip)" >&2
fi
echo ""

# 2. Metadados apk-inspect (aapt/sha/hasShard) — nunca copia binário
echo "[2/5] apk-inspect (hashes/metadados, sem binário)"
APK_META="$OUT_DIR/apk-metadata.json"
if [[ -f "$REPO_ROOT/warzone-offline/tools/apk-analysis/apk-inspect.sh" ]]; then
  bash "$REPO_ROOT/warzone-offline/tools/apk-analysis/apk-inspect.sh" "$APK" --out "$APK_META" 2>&1 | head -n 100 || true
  echo "  -> $APK_META"
  # mostra hasShard/hasManifest sem vazar sha completo no log (apenas short)
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import json; d=json.load(open('$APK_META')); print('hasShard:',d.get('hasShard'),'hasManifest:',d.get('hasManifest'),'pkg:',d.get('package'),'ver:',d.get('versionName'))" 2>&1 | head
  fi
else
  echo "  apk-inspect.sh não encontrado" >&2
fi
echo ""

if [[ "$ZIP_ONLY" == "true" ]]; then
  echo "== modo --zip-only: parando aqui (sem extrair) =="
  echo "Saídas em: $OUT_DIR"
  ls -lh "$OUT_DIR" 2>&1 | head -n 30
  echo ""
  echo "Próximo: compartilhe APENAS $UNZIP_JSON e $APK_META (sem APK) para análise."
  exit 0
fi

# 3. Extrair para OUT_DIR (não no repo) — prefer unzip, fallback apktool
EXTRACT_DIR="$OUT_DIR/apktool-output"
mkdir -p "$EXTRACT_DIR"
echo "[3/5] extração para $EXTRACT_DIR (fora do repo)"
# Se XAPK (ZIP contendo .apk + OBB), unzip -q já basta; se APK puro, também
if command -v unzip >/dev/null 2>&1; then
  # limpa antes se já existia
  rm -rf "$EXTRACT_DIR"/* 2>/dev/null || true
  if unzip -q "$APK" -d "$EXTRACT_DIR" 2>&1 | head; then
    echo "  unzip -q ok"
    # XAPK contém inner apks: extrair inner apks também se houver
    INNER_APKS=$(find "$EXTRACT_DIR" -maxdepth 2 -name "*.apk" 2>/dev/null | head)
    if [[ -n "$INNER_APKS" ]]; then
      echo "  XAPK detectado, extraindo inner APKs..."
      for inner in $INNER_APKS; do
        echo "    inner: $inner"
        # extrai apenas manifest/shard/so do inner sem sobre-escrever muito
        TMP_INNER="$OUT_DIR/inner-$(basename "$inner" .apk)"
        mkdir -p "$TMP_INNER"
        unzip -q "$inner" -d "$TMP_INNER" 2>&1 | head || true
        # merge: copia shard/manifest/so para EXTRACT_DIR para scanner achar
        rsync -a "$TMP_INNER"/ "$EXTRACT_DIR"/ 2>/dev/null || cp -r "$TMP_INNER"/* "$EXTRACT_DIR"/ 2>/dev/null || true
      done
    fi
    ls "$EXTRACT_DIR" 2>&1 | head -n 20
  else
    echo "  unzip falhou, tentando apktool se disponível..." >&2
    if command -v apktool >/dev/null 2>&1; then
      apktool d "$APK" -o "$EXTRACT_DIR" -f 2>&1 | head
    else
      echo "  nem unzip nem apktool funcionaram" >&2
    fi
  fi
else
  echo "  unzip não disponível" >&2
fi
echo ""

# 4. Inventário físico + referências lógicas (só JSONs, sem binário)
echo "[4/5] shard-inventory + shard-reference-scanner (só JSONs, sem volume)"
PHYS_JSON="$OUT_DIR/physical-shards.json"
REFS_JSON="$OUT_DIR/shard-refs.json"
if [[ -f "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-inventory.py" ]]; then
  python3 "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-inventory.py" "$EXTRACT_DIR" > "$PHYS_JSON" 2>"$PHYS_JSON.log" || true
  echo "  -> $PHYS_JSON"
  cat "$PHYS_JSON.log" 2>/dev/null | head || true
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import json; d=json.load(open('$PHYS_JSON')); print('physicalShards:',d.get('count'),'top:',[(s['path'],s['size']) for s in d.get('shards',[])[:3]])" 2>&1 | head
  fi
fi
if [[ -f "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-reference-scanner.py" ]]; then
  python3 "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-reference-scanner.py" "$EXTRACT_DIR" --out "$REFS_JSON" 2>"$REFS_JSON.log" || true
  echo "  -> $REFS_JSON"
  cat "$REFS_JSON.log" 2>/dev/null | head || true
  if command -v python3 >/dev/null 2>&1; then
    python3 -c "import json; d=json.load(open('$REFS_JSON')); print('provenShardNames:',len(d.get('provenShardNames',[])), d.get('provenShardNames',[])[:5]); print('summary:',d.get('summary'))" 2>&1 | head -n 20
  fi
fi
echo ""

# 5. (opcional) verificação CDN leve para cada shard provado
if [[ "$CHECK_CDN" == "true" ]]; then
  echo "[5/5] shard-cdn-check --list-proven (HEAD/Range, sem volume)"
  CDN_LOG="$OUT_DIR/shard-cdn.log"
  if [[ -f "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-cdn-check.sh" ]]; then
    bash "$REPO_ROOT/warzone-offline/tools/asset-tools/shard-cdn-check.sh" --list-proven "$REFS_JSON" > "$CDN_LOG" 2>&1 || true
    echo "  -> $CDN_LOG"
    head -n 80 "$CDN_LOG" 2>&1 | head -n 80
  fi
else
  echo "[5/5] (pulado) use --check-cdn para HEAD/Range de cada shard provado (sem volume)"
  echo "  ex: bash run-external-apk.sh --apk $APK --out-dir $OUT_DIR --check-cdn"
fi
echo ""

echo "== CONCLUÍDO (sem copiar APK para o repo) =="
echo "Saídas (compartilhe APENAS estes JSONs, NUNCA o APK):"
ls -lh "$OUT_DIR"/*.json "$OUT_DIR"/*.txt 2>&1 | head -n 30
echo ""
echo "O que compartilhar para M2.2.1:"
echo "  - $APK_META  (package/version/sha short/hasShard)"
echo "  - $PHYS_JSON (lista física path/size/sha16/header, sem binário)"
echo "  - $REFS_JSON (provenShardNames + findings)"
echo "  - (se --check-cdn) $OUT_DIR/shard-cdn.log  (HEAD/Range 404/200)"
echo ""
echo "O que NÃO compartilhar / NÃO está no repo:"
echo "  - APK/XAPK original ($APK) — permanece só no seu S23 / $OUT_DIR não é Git"
echo "  - *.shard/*.xpak binários — nunca copiados para $REPO_ROOT"
echo ""
echo "Próximo: anexe os 3 JSONs redigidos a uma issue ou envie para análise M2.2.1."
echo "Ver docs/research/m2.2.1-shard-inventory.md §2.3 para tabela VERIFIED/UNKNOWN."

# sanity: garante que nada foi criado dentro do repo (exceto logs)
REPO_SHARDS=$(find "$REPO_ROOT" -type f -name "*.shard" 2>/dev/null | head)
if [[ -n "$REPO_SHARDS" ]]; then
  echo "AVISO: encontrado *.shard dentro do repo (não deveria): $REPO_SHARDS" >&2
fi
