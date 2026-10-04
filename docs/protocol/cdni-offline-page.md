# Protocolo — CDN Offline Page

> **Endpoint de shutdown, não manifest.** Importante para offline: é o que o usuário vê hoje.

---

## Identificação

- **Name:** `CDNIOfflinePage`
- **Direction:** `S→C` (WebView redirect)
- **Transport:** `HTTPS`
- **Endpoint:** `GET https://prod.cdni.callofduty.com/static/web/index.html`
- **Encoding:** `html` (text/html)
- **Encryption:** `TLS`
- **Size:** `UNKNOWN`
- **Version:** `WZM 3.10.0` (observado) e `3.3.4` (provável mesmo CDN)
- **Observed:** `2026-10-04` via redirect de `build-selector-103.js`

## Campos

| # | Campo | Tipo | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|
| 1 | `body` | `html` | sim | Página estática com mensagem offline | Contém `Estes servidores estão permanentemente fora de serviço.` (pt-BR) |

## Exemplo (redigido)

```
> GET /static/web/index.html HTTP/1.1
> Host: prod.cdni.callofduty.com

< HTTP/1.1 200 text/html
<html>... Estes servidores estão permanentemente fora de serviço. ...</html>
```
Texto exato proprietário — não colar HTML completo; apenas citar frase como evidência.

## Evidência

- **SOURCE:** WebView redirect chain 3.10.0
- **EVIDENCE:** `bootstrap/index.html` → `build-selector-103.js` → `static/web/index.html` com captura de conteúdo pt-BR
- **CONFIDENCE:** `VERIFIED — observado em execução`

## Comportamento observado

- CDN real serve página de shutdown (serviço offline permanente) — confirma `warzone-mobile-versions.md` delist 2025-05-18 / offline 2026-04-17.
- Para modo local, este `GET` deve ser **interceptado** (hosts ou WebViewClient) para não exibir shutdown, mas sim UI local ou bypass.

## Notas

- Esta página é **não-proprietária na essência** (mensagem de shutdown), mas HTML completo é proprietário — não commitar.
- Próximo passo: servir localmente alternativa em `warzone-offline/server/src/bootstrap` e testar via `hosts` (ver `wzm-310-bootstrap.md` §6).
