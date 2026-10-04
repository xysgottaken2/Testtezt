#!/usr/bin/env bash
# Gera o certificado TLS LOCAL e DESCARTÁVEL usado pelo roteador CDNI do launcher (M3).
#
# Por que existe: o WZM conecta em HTTPS :443 em prod.cdni.callofduty.com. Para o servidor
# embarcado responder TLS precisamos de um certificado cujo nome bata com o host — e esse
# certificado é NOSSO (nunca da Activision). Ele NÃO é uma credencial de terceiros, não dá
# acesso a nada real e por isso é gerado localmente/CI em vez de ser versionado
# (o .gitignore do repo bloqueia *.p12/*.pem, e essa política é mantida).
#
# Uso:
#   bash scripts/generate-local-cdni-cert.sh            # gera se faltar
#   bash scripts/generate-local-cdni-cert.sh --force    # regenera (invalida CA já instalada)
#
# Saída (não versionada):
#   android/app/src/main/assets/cdn_local.p12   (folha + chave, senha abaixo)
#   android/app/src/main/assets/cdn_local_ca.pem (CA pública, para instalação manual no device)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/android/app/src/main/assets"
WORK_DIR="${TMPDIR:-/tmp}/wzm-local-cdni-cert"
PASSWORD="wzm-offline-local"   # senha pública: é um certificado de laboratório, sem valor de segurança
DAYS=3650
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "argumento desconhecido: $arg" >&2; exit 2 ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERRO: openssl não encontrado no PATH (necessário para gerar o certificado local)" >&2
  exit 1
fi

mkdir -p "$ASSETS_DIR" "$WORK_DIR"

if [ -s "$ASSETS_DIR/cdn_local.p12" ] && [ -s "$ASSETS_DIR/cdn_local_ca.pem" ] && [ "$FORCE" -eq 0 ]; then
  echo "certificado local já existe em $ASSETS_DIR — nada a fazer (use --force para regenerar)"
  sha256sum "$ASSETS_DIR/cdn_local.p12" "$ASSETS_DIR/cdn_local_ca.pem"
  exit 0
fi

cd "$WORK_DIR"

echo "[1/5] CA local (descartável)"
openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
  -keyout ca.key -out ca.crt \
  -subj "/O=WZM Offline Preservation/CN=WZM Offline Local CDNI CA (throwaway)" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" >/dev/null 2>&1

echo "[2/5] chave + CSR da folha"
openssl req -newkey rsa:2048 -sha256 -nodes -keyout leaf.key -out leaf.csr \
  -subj "/O=WZM Offline Preservation/CN=prod.cdni.callofduty.com" >/dev/null 2>&1

echo "[3/5] assinatura (SANs dos hosts CDNI comprovados em M2/M2.2)"
printf "subjectAltName=DNS:prod.cdni.callofduty.com,DNS:*.cdni.callofduty.com,DNS:cdni.callofduty.com\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n" > leaf.ext
openssl x509 -req -in leaf.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out leaf.crt -days "$DAYS" -sha256 -extfile leaf.ext >/dev/null 2>&1

echo "[4/5] PKCS12 para o APK"
openssl pkcs12 -export -out "$ASSETS_DIR/cdn_local.p12" -inkey leaf.key -in leaf.crt \
  -certfile ca.crt -passout "pass:$PASSWORD" -name cdnlocal

echo "[5/5] CA pública para o asset"
cp ca.crt "$ASSETS_DIR/cdn_local_ca.pem"

echo "OK — certificado local gerado:"
openssl x509 -in leaf.crt -noout -subject -issuer -ext subjectAltName | sed 's/^/    /'
sha256sum "$ASSETS_DIR/cdn_local.p12" "$ASSETS_DIR/cdn_local_ca.pem"
echo "    (senha do p12: $PASSWORD — pública, certificado de laboratório)"
