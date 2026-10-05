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
  "docs/research/m3.5-loopback-e-teste-sintetico.md"
  "warzone-offline/tools/apk-analysis/update-check-scan.py"
  "docs/research/m4.1-dono-das-conexoes-loopback.md"
  "docs/research/m4.2-configuracao-endpoint-local.md"
  "docs/research/m4.3-libgame-static-analysis.md"
  "docs/research/m4.5-cadeia-estatica-ate-connect.md"
  "docs/research/m4.6-manifest-processos-e-cruzamento.md"
  "docs/research/m4.7-meta-fetch-pre-engine-dns.md"
  "docs/research/m4.8-preengine-sequencia-e-preservacao.md"
  "docs/research/m5.0-servidor-local-dns.md"
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
grep -q "SINTETICO" docs/research/m3.5-loopback-e-teste-sintetico.md || { echo "M3.5 synthetic marker missing"; exit 1; }
grep -q "INVALID_UID_NAO_PROVA" docs/research/m4.1-dono-das-conexoes-loopback.md || { echo "M4.1 ownership marker missing"; exit 1; }
grep -q "SEM_CONFIG_DIRETA_WZM" docs/research/m4.2-configuracao-endpoint-local.md || { echo "M4.2 historical direct-config marker missing"; exit 1; }
grep -q "CANDIDATO_OVERRIDE_CDNI_NATIVO" docs/research/m4.3-libgame-static-analysis.md || { echo "M4.3 candidate marker missing"; exit 1; }
grep -q "CADEIA_ESTATICA_M4_5" docs/research/m4.5-cadeia-estatica-ate-connect.md || { echo "M4.5 static chain marker missing"; exit 1; }
grep -q "CDNI_META_SO_PELO_JNI_PORTOS_ANTES_DO_SOCKET" docs/research/m4.6-manifest-processos-e-cruzamento.md || { echo "M4.6 process marker missing"; exit 1; }
grep -q "META_FETCH_JAVA_DNS_ANTES_DO_SOCKET" docs/research/m4.7-meta-fetch-pre-engine-dns.md || { echo "M4.7 meta fetch marker missing"; exit 1; }
grep -q "PREENGINE_META_JAVA_CADEIA_PARCIALMENTE_REPRODUZIVEL" docs/research/m4.8-preengine-sequencia-e-preservacao.md || { echo "M4.8 pre-engine sequence marker missing"; exit 1; }
grep -q "M5_SERVIDOR_LOCAL_DNS_CDNI_META_PRIMEIRO" docs/research/m5.0-servidor-local-dns.md || { echo "M5.0 local server marker missing"; exit 1; }
echo "markers ok"
