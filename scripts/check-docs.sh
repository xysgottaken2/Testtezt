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
  "docs/research/m2.1-webview-test-plan.md"
  "docs/architecture/overview.md"
  "docs/reverse-engineering/methodology.md"
  "docs/reverse-engineering/frida-bypass.md"
  "docs/reverse-engineering/apk-analysis-runbook.md"
  "docs/protocol/template.md"
  "docs/protocol/cdni-build-selector.md"
  "docs/protocol/cdni-offline-page.md"
  "docs/protocol/cdni-meta.md"
  "docs/protocol/manifest-json.md"
  "docs/protocol/endpoints-template.json"
  "docs/protocol/examples/synthetic-example.md"
  "docs/research/m2.2-assets-cdni-investigation.md"
  "docs/research/m2.2.1-shard-inventory.md"
  "warzone-offline/tools/asset-tools/cdni-probe.sh"
  "warzone-offline/tools/asset-tools/shard-reference-scanner.py"
  "warzone-offline/tools/asset-tools/shard-cdn-check.sh"
  "warzone-offline/tools/asset-tools/run-external-apk.sh"
  "warzone-offline/tools/apk-analysis/tls-trust-scan.py"
  "docs/research/m3.2-apk-tls-trust-investigation.md"
  "docs/research/m3-cdni-integration.md"
  "docs/research/m3.3-diferenca-entre-os-testes.md"
  "docs/research/m3.4-caminho-wzm-tun.md"
  "docs/research/m4.0-verificando-atualizacoes.md"
  "warzone-offline/tools/apk-analysis/update-check-scan.py"
  "warzone-offline/patches/apk-webview-patch/README.md"
  "warzone-offline/patches/apk-webview-patch/APK_PATCH_DIFF.md"
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
grep -q "CANNOT_SKIP_DIRECTLY" docs/research/m4.0-verificando-atualizacoes.md || { echo "M4.0 verdict marker missing"; exit 1; }
echo "markers ok"
