#!/usr/bin/env bash
# Legacy BootstrapServer wrapper — all local listeners stay on 127.0.0.1.
# Usage:
#   bash run-bootstrap.sh --port 18081
#   bash run-bootstrap.sh --port 18081 --test   # starts locally, curls, then stops
set -euo pipefail
HOST="127.0.0.1"
PORT="18081"
TEST_ONLY="false"
while (($#)); do
  case "$1" in
    --host)
      [[ $# -ge 2 ]] || { echo "--host requires 127.0.0.1" >&2; exit 2; }
      [[ "$2" == "127.0.0.1" ]] || { echo "blocked: local server may bind only to 127.0.0.1" >&2; exit 2; }
      HOST="$2"; shift 2 ;;
    --port)
      [[ $# -ge 2 ]] || { echo "--port requires a value" >&2; exit 2; }
      PORT="$2"; shift 2 ;;
    --test) TEST_ONLY="true"; shift ;;
    --help) echo "Usage: bash run-bootstrap.sh [--host 127.0.0.1] [--port 18081] [--test]"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/../../server"

if [[ "$TEST_ONLY" == "true" ]]; then
  echo "[run-bootstrap] --test: localhost-only server, curl, then stop"
  node --loader ts-node/esm src/bootstrap/cli.ts --host "$HOST" --port "$PORT" &
  PID=$!
  trap 'kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true' EXIT
  for _ in {1..30}; do
    if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null; then break; fi
    sleep 0.1
  done
  echo "[curl] GET /manifest/build-selector-103.js with a local Host header"
  curl -s -H "Host: prod.cdni.callofduty.com" "http://127.0.0.1:$PORT/manifest/build-selector-103.js" | head -n 20 | cat -v
  echo ""
  echo "[hits]"; curl -s "http://127.0.0.1:$PORT/__hits" | head -n 50
  exit 0
fi

echo "[run-bootstrap] BootstrapServer em $HOST:$PORT (somente loopback; sem tráfego externo)"
echo "Acesse: curl -H 'Host: prod.cdni.callofduty.com' http://127.0.0.1:$PORT/manifest/build-selector-103.js"
echo "Ver hits: curl http://127.0.0.1:$PORT/__hits"
exec node --loader ts-node/esm src/bootstrap/cli.ts --host "$HOST" --port "$PORT"
