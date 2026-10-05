#!/usr/bin/env bash
# make-local-cert.sh — gera a CA local e o certificado do servidor local (M5.0)
#
# IMPORTANTE: isto é o lado SERVIDOR. Não altera o cliente, o APK, o pinning
# nem a confiança do aparelho. Se/quando o handshake falhar por falta de
# confiança, isso é registrado como o blocker do estágio 3 — decisão separada.
#
# Escopo dos SANs: somente hosts de CONTEÚDO do CDNI. Nenhum host de
# autenticação/login/Demonware entra aqui (fora do milestone e das regras).
#
# Uso:
#   bash make-local-cert.sh                       # gera em ./ (ca.pem, server.pem, server.key)
#   bash make-local-cert.sh --days 30 --force
#   bash make-local-cert.sh --san host.extra.com  # repetível
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DAYS=825
FORCE=0
EXTRA_SANS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --san) EXTRA_SANS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

command -v openssl >/dev/null || { echo "openssl não encontrado" >&2; exit 1; }

CA="$DIR/ca.pem"; CA_KEY="$DIR/ca.key"
CRT="$DIR/server.pem"; KEY="$DIR/server.key"

if { [[ -f "$CRT" || -f "$CA" ]] && [[ $FORCE -eq 0 ]]; }; then
  echo "já existem certificados em $DIR — use --force para substituir" >&2
  exit 1
fi

# Hosts de CONTEÚDO (escopo do milestone: pré-engine/CDNI). Nada de auth/Demonware.
SANS=(prod.cdni.callofduty.com "*.cdni.callofduty.com" cod-assets.cdn.callofduty.com)
SANS+=("${EXTRA_SANS[@]}")

SAN_STRING="$(IFS=,; echo "${SANS[*]/#/DNS:}")"
echo "SANs: $SAN_STRING"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days "$DAYS" \
  -keyout "$CA_KEY" -out "$CA" -subj "/CN=wzm-local-ca/O=wzm-local-server"

openssl req -newkey rsa:2048 -nodes \
  -keyout "$KEY" -out "$TMP/server.csr" -subj "/CN=prod.cdni.callofduty.com"

cat > "$TMP/ext.cnf" <<CNF
subjectAltName=$SAN_STRING
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
CNF

openssl x509 -req -in "$TMP/server.csr" -CA "$CA" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$CRT" -days "$DAYS" -sha256 -extfile "$TMP/ext.cnf"

chmod 600 "$KEY" "$CA_KEY"

echo
echo "gerado:"
echo "  CA     : $CA        (para instalar no aparelho SOMENTE se você decidir)"
echo "  server : $CRT"
echo "  key    : $KEY  (chmod 600)"
echo
echo "conferir: openssl x509 -in \"$CRT\" -noout -text | grep -A1 'Subject Alternative Name'"
echo "Nenhum arquivo .pem/.key é versionado (.gitignore)."
