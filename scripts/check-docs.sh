#!/usr/bin/env bash
set -euo pipefail
echo "== check-docs =="
# verifica que docs obrigatórios existem
required=(
  "docs/research/dead-by-daylight-mobile.md"
  "docs/research/comparison.md"
  "docs/research/warzone-mobile-versions.md"
  "docs/research/warzone-mobile-networking.md"
  "docs/research/warzone-mobile-streaming.md"
  "docs/research/warzone-mobile-research.md"
  "docs/research/m1-endpoint-discovery.md"
  "docs/research/issue8-unlock-status.md"
  "docs/research/wzm-310-bootstrap.md"
  "docs/research/wzm-permissions-gvs.md"
  "docs/architecture/overview.md"
  "docs/reverse-engineering/methodology.md"
  "docs/reverse-engineering/frida-bypass.md"
  "docs/reverse-engineering/apk-analysis-runbook.md"
  "docs/protocol/template.md"
  "docs/protocol/cdni-build-selector.md"
  "docs/protocol/cdni-offline-page.md"
  "docs/protocol/endpoints-template.json"
  "docs/protocol/examples/synthetic-example.md"
)
missing=0
for f in "${required[@]}"; do
  if [ ! -f "$f" ]; then echo "MISSING: $f"; missing=1; fi
done
if [ $missing -ne 0 ]; then exit 1; fi
echo "all required docs present"
# verifica marcações obrigatórias
grep -q "DBD_REFERENCE" docs/research/dead-by-daylight-mobile.md || { echo "DBD_REFERENCE missing"; exit 1; }
grep -q "WARZONE" docs/research/warzone-mobile-networking.md || { echo "WARZONE marker missing"; exit 1; }
echo "markers ok"
