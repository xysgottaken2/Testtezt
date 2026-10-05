#!/usr/bin/env bash
# run-bootstrap.sh — inicia BootstrapServer local para teste M2.1 sem Activision
# Uso:
#   bash run-bootstrap.sh --port 18081 --host 0.0.0.0          # inicia
#   bash run-bootstrap.sh --port 18081 --test                 # inicia, testa com curl Host header, encerra
set -euo pipefail
HOST="0.0.0.0"
PORT="18081"
TEST_ONLY="false"
for arg in "$@"; do case $arg in --host) HOST="$2"; shift 2;; --port) PORT="$2"; shift 2;; --test) TEST_ONLY="true";; --help) echo "Usage: bash run-bootstrap.sh [--host 0.0.0.0] [--port 18081] [--test]"; exit 0;; esac; done

cd "$(dirname "$0")/../../server"

if [[ "$TEST_ONLY" == "true" ]]; then
  echo "[run-bootstrap] --test: inicia em background, curl, encerra"
  # inicia em background
  node --loader ts-node/esm src/bootstrap/cli.ts --host "$HOST" --port "$PORT" &
  PID=$!
  sleep 3
  echo "[curl] GET /manifest/build-selector-103.js com Host prod.cdni..."
  curl -s -H "Host: prod.cdni.callofduty.com" "http://127.0.0.1:$PORT/manifest/build-selector-103.js" | head -n 20 | cat -v
  echo ""
  echo "[hits]"; curl -s "http://127.0.0.1:$PORT/__hits" | head -n 50
  kill $PID || true
  wait $PID 2>/dev/null || true
  echo "[done]"
  exit 0
fi

echo "[run-bootstrap] BootstrapServer em $HOST:$PORT (prod.cdni.callofduty.com mock local, sem Activision)"
echo "Acesse: curl -H 'Host: prod.cdni.callofduty.com' http://127.0.0.1:$PORT/manifest/build-selector-103.js"
echo "Ver hits: curl http://127.0.0.1:$PORT/__hits"
exec node --loader ts-node/esm src/bootstrap/cli.ts --host "$HOST" --port "$PORT"
