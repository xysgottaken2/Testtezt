# tools/apk-analysis — Kit de análise sem hipótese

> **Nunca commitar APK/XAPK/OBB/shards/so`.** Todas as saídas ficam em `/tmp/wzm`, só artefatos redigidos vão para `docs/`.

## Ferramentas nesta pasta

| Script | O que faz | Saída |
|---|---|---|
| `endpoint-scanner.js` | Varre `jadx-output`/`lib` por 13 padrões (https/wss/demonware/activision/cdn/3074/manifest/shard) | `/tmp/wzm/endpoints.json` |
| `categorize-endpoints.js` | Classifica `endpoints.json` em 7 categorias do escopo: auth/matchmaking/demonware/manifest_cdn/telemetry/game_server/other | `/tmp/wzm/endpoints-by-category.json` |
| `apk-inspect.sh` | `aapt dump` + `unzip -l` + `sha256` + libs, sem copiar binário | `/tmp/wzm/apk-metadata.json` |
| `lib-inspect.sh` | `readelf`/`strings` em `libgame.so`, busca demonware/ssl/pinning/IW | `/tmp/wzm/lib-inspect.json` |

## Uso rápido (quando APK chegar)

```bash
mkdir -p /tmp/wzm
cp /caminho/legal/warzone.xapk /tmp/wzm/warzone.xapk  # fora do repo!
bash warzone-offline/tools/apk-analysis/apk-inspect.sh /tmp/wzm/warzone.xapk --out /tmp/wzm/apk-metadata.json
apktool d /tmp/wzm/warzone.xapk -o /tmp/wzm/apktool-output
jadx -d /tmp/wzm/jadx-output /tmp/wzm/warzone.xapk
node warzone-offline/tools/apk-analysis/endpoint-scanner.js /tmp/wzm/jadx-output /tmp/wzm/apktool-output/lib > /tmp/wzm/endpoints.json
node warzone-offline/tools/apk-analysis/categorize-endpoints.js /tmp/wzm/endpoints.json > /tmp/wzm/endpoints-by-category.json
bash warzone-offline/tools/apk-analysis/lib-inspect.sh /tmp/wzm/apktool-output/lib/arm64-v8a/libgame.so --out /tmp/wzm/lib-inspect.json
# só então revisar e copiar hashes/listas redigidas para docs/
```

Veja `docs/reverse-engineering/apk-analysis-runbook.md` para runbook completo.

## Testes

```bash
cd warzone-offline/server && npx vitest run --grep "endpoint-scanner|categorize|apk-inspect"
cd warzone-offline/launcher && npx vitest run
```

Tudo roda com fixtures sintéticos, sem APK real.
