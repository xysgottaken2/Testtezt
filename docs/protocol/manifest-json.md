# Protocolo — CDN Manifest: manifest.json (WebView prelogin chain)

> **Nunca inventar.** Preencher só com observação.

---

## Identificação

- **Name:** `CDNPreloginManifest`
- **Direction:** `C→S` (WebView `build-selector-102.js` → fetch)
- **Transport:** `HTTPS`
- **Endpoint:** `GET https://prod.cdni.callofduty.com/manifest/manifest.json` (+ variantes `manifest-popup.json`, `manifest-events.json`, `manifest-dailylogin.json`)
- **Encoding:** `JSON` (application/json)
- **Encryption:** `TLS` (Akamai)
- **Size:** 1.1–1.6 KB (por variante, 2026-10-03 live)
- **Version:** mapeia `semver` do cliente para `root` do boot web (ex: `>=3.10.0 <4.0.0` → `boot-15.0.0`)
- **Observed:** `2026-10-03` via `fetch_page` sem auth — `200` para todas as 4 variantes

## Campos

| # | Campo | Tipo | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|
| 1 | `builds` | `array<object>` | sim | Lista de mapeamentos semver→root | `[{"semver":"1.x.x","root":"https://…/prelogin/boot-21.0.0/"}, …]` |
| 2 | `builds[].semver` | `string` (semver range) | sim | Range compatível | `">=3.10.0 <4.0.0"` |
| 3 | `builds[].root` | `string` (URL) | sim | Prefixo onde está o boot web | `https://prod.cdni.callofduty.com/prelogin/boot-15.0.0/` |
| — | — | — | — | — | — |

Variantes:

- `manifest-popup.json`: `root` → `https://…/popup/popup-*`
- `manifest-events.json`: `root` → `https://…/mgl-ui/events-*`
- `manifest-dailylogin.json`: `root` → `https://…/daily-login/daily-login-*`

## Exemplo (redigido — manifest.json, 2026-10-03)

```json
{"builds": [
  {"semver":"1.x.x","root":"https://prod.cdni.callofduty.com/prelogin/boot-21.0.0/"},
  {"semver":"2.8.x","root":"https://prod.cdni.callofduty.com/prelogin/boot-2.8.2/"},
  {"semver":">=3.10.0 <4.0.0","root":"https://prod.cdni.callofduty.com/prelogin/boot-15.0.0/"},
  {"semver":">=4.5.2","root":"https://prod.cdni.callofduty.com/prelogin/boot-21.1.0/"}
]}
```

Boot web sob `root` contém `web/manifest.json`, `web/static/js/main.js`, `web/static/css/main.css` etc. (todos VERIFIED 200 para `boot-15.0.0`).

## Evidência

- **SOURCE:** `https://prod.cdni.callofduty.com/manifest/build-selector-102.js` (JS legado ainda vivo) → `fetch(manifestUrl)` onde `manifestUrl` é construído por `feature` (`prelogin/popup/events/dailylogin`)
- **EVIDENCE:** `fetch_page https://…/manifest/manifest.json` → JSON acima 200; `manifest-popup/events/dailylogin` também 200; `fetch_page https://…/prelogin/boot-15.0.0/web/manifest.json` 200; `…/main.js` e `main.css` 200 (primeiro chunk). Ver `docs/research/m2.2-assets-cdni-investigation.md` §3.
- **CONFIDENCE:** `VERIFIED — observado live 200 via fetch_page 2026-10-03, sem auth, sem assinatura`

## Comportamento observado

- `build-selector-102.js` filtra `manifestData.builds.filter(semver.satisfies(window.buildVersion))` e escolhe `sortedVersions[sortedVersions.length-1].root` — lógica versionada clara, não brute force.
- `build-selector-103.js` atual é stub offline que só redireciona — prova que 103 foi “desligado” mas 102 preserva lógica.
- `manifest.json` é público, sem token, mesma infraestrutura Akamai de `cdni.meta`.
- Cliente WebView 3.10.0 usaria `>=3.10.0 <4.0.0` → `boot-15.0.0` — VERIFIED mapping.

## Notas

- Diferenciar de `cdni.meta` (`wzm/shard_cdn/...`) — `manifest.json` é **prelogin WebView**, `cdni.meta` é **shard CDN config**.
- Para offline: `server/src/bootstrap` já replica essa lógica localmente; este DOC permite espelhar `manifest.json` e `boot-*` localmente sem tocar Activision.
- Tamanho total da cadeia prelogin < 2 MB, preservável.
