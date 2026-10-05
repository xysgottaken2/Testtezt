# Protocolo — CDN SHARD Meta: cdni.meta

> **Nunca inventar.** Preencher só com observação.

---

## Identificação

- **Name:** `CDNIShardMeta`
- **Direction:** `C→S` (GET cliente ao iniciar, antes ou junto ao streaming)
- **Transport:** `HTTPS`
- **Endpoint:** `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` (Android) e `…/ios/_manifest/cdni.meta` (iOS)
- **Encoding:** `JSON` (application/json, sem BOM, `\r\n` + 4-space indent)
- **Encryption:** `TLS` (Akamai edgesuite)
- **Size:** ~320 B (android) / ~410 B (ios) — 2026-10-03 live
- **Version:** `min_buildnum: 19854920`, `min_tu: 0` (ambas plataformas) — WZM 3.x até 4.x
- **Observed:** `2026-10-03T…Z` via `fetch_page` (externo, sem auth, sem volume) — `200` para ambos; `Not a file` para diretórios, `Not found` para inexistente (prova Akamai diferencia)

## Campos

| # | Campo | Tipo | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|
| 1 | `min_tu` | `int` | sim | Minimum TU? (title update) — 0 | `0` |
| 2 | `min_buildnum` | `int` | sim | Build mínimo compatível | `19854920` |
| 3 | `app_store_url` | `string` | sim | URL da loja | `https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone` (android) / `https://apps.apple.com/app/id1638368439` (ios) |
| 4 | `future_app_id` | `string` | não (só ios) | ID futuro | `com.activision.callofduty.warzone` |
| 5 | `future_app_store_url` | `string` | não (só ios) | URL futura | `https://apps.apple.com/app/id1638368439` |
| 6 | `#x…` (flags hex) | `bool` | sim | Feature flags hasheadas (8–10 chaves) | `"#x3a74898c63cb5c55a": true` |
| — | — | — | — | — | — |

> Flags hex são opacas — não decodificadas; comportamento `UNKNOWN`.

## Exemplo (redigido — android, 2026-10-03)

```json
{
    "min_tu": 0,
    "min_buildnum": 19854920,
    "app_store_url": "https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone",
    "#x3a74898c63cb5c55a": true,
    "#x3e9cc40e792dcfdcc": true,
    "#x3e93a69101751bfa8": false,
    "#x3b20ca17f00dfb4c9": false,
    "#x3c839f93c3b076242": true,
    "#x3bc57a21a42173b49": true,
    "#x377addea98016dad6": true
}
```

iOS adiciona `"#x3febec63a7c2351ab": false` + `future_*`.

## Evidência

- **SOURCE:** `prod.cdni.callofduty.com` Akamai (`edgesuite.net` → `a224.dscw27.akamai.net`)
- **EVIDENCE:** `fetch_page https://…/android/_manifest/cdni.meta` → JSON acima (320 B); `…/ios/…` → 410 B; ambos `200`. Diretórios `…/android/` → ````\nNot a file\n```` (403-like). Ver `docs/research/m2.2-assets-cdni-investigation.md` §3.
- **CONFIDENCE:** `VERIFIED — observado live 200 via fetch_page 2026-10-03` (sem auth, sem token)

## Comportamento observado

- CDN retorna 200 sem auth — arquivo público, não assinado, sem `Authorization`.
- Cliente deve comparar `buildnum` local com `min_buildnum` para decidir update obrigatório — `HYPOTHESIS` (não observado em pcap).
- `Not a file` para diretório indica Akamai não permite listing — não brute-forceável.
- Não contém lista de shards — não é catálogo; é config de versão/flags. Catálogo real ainda `UNKNOWN`.

## Notas

- Não confundir com `manifest.json` do bootstrap (`/manifest/manifest.json`) — `cdni.meta` é **shard CDN** (`wzm/shard_cdn`), `manifest.json` é **WebView prelogin** (`/manifest/`).
- Para shards, próximo passo M2.2.1: extrair `manifest.json` do APK (`split_asset_pack`) e testar `HEAD Range` por shard nomeado (sem brute force).
