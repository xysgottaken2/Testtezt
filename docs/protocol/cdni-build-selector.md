# Protocolo — CDN Manifest: build-selector

> **Nunca inventar.** Preencher só com observação.

---

## Identificação

- **Name:** `CDNIBuildSelector`
- **Direction:** `C→S` (WebView GET)
- **Transport:** `HTTPS`
- **Endpoint:** `GET https://prod.cdni.callofduty.com/manifest/build-selector-103.js`
- **Encoding:** `javascript` (application/javascript)
- **Encryption:** `TLS` (HTTPS)
- **Size:** `UNKNOWN` (não capturado tamanho; precisa pcap/Content-Length)
- **Version:** `WZM 3.10.0` (runtime) + `3.3.4` (asset estático)
- **Observed:** `2026-10-04` via WebView `file:///android_asset/bootstrap/index.html` → `adb logcat` + WebView request

## Campos

| # | Campo | Tipo | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|
| 1 | `URL` | `string` | sim | Caminho completo com versão | `https://prod.cdni.callofduty.com/manifest/build-selector-103.js` |
| 2 | `Host header` | `string` | sim | Host no TLS SNI + HTTP Host | `prod.cdni.callofduty.com` |
| 3 | `Referer` | `string` | PROBABLE | `file:///android_asset/bootstrap/index.html` | — |

Resposta **observada** (atual offline): JS que imediatamente seta `window.location = "https://prod.cdni.callofduty.com/static/web/index.html"` (ou equivalente). O conteúdo exato é proprietário — não colar aqui; apenas descrever comportamento.

## Exemplo (redigido)

```
> GET /manifest/build-selector-103.js HTTP/1.1
> Host: prod.cdni.callofduty.com

< HTTP/1.1 200
< Content-Type: application/javascript
// [redacted] — build selector que redireciona para /static/web/index.html
```

## Evidência

- **SOURCE:** `file:///android_asset/bootstrap/index.html` (asset) + WebView runtime 3.10.0
- **EVIDENCE:** `WebView loadUrl(file:///android_asset/bootstrap/index.html)` → request capturado para `https://prod.cdni.callofduty.com/manifest/build-selector-103.js`
- **CONFIDENCE:** `VERIFIED — observado em execução` (WebView) + `VERIFIED — encontrado em código` (asset HTML contém `<script src="https://.../build-selector-103.js">`)

## Comportamento observado

- A evidência WebView arquivada registra o request ao CDN e o redirect atual para a página de shutdown.
- A ideia de `hosts 127.0.0.1 prod.cdni.callofduty.com` + servidor local era uma **HYPOTHESIS**, não uma execução com WZM. O M4.3 encontrou um candidato nativo `cdni_httpServer`, mas não provou acesso do usuário ou eficácia local. A transformação `hosts-patch` não instala mapping no Android; resolução local não altera URL/SNI nem demonstra aceitação do certificado. Não implementar/testar esta rota no escopo atual; ver [M4.3](../research/m4.3-libgame-static-analysis.md) e [M4.2 histórico](../research/m4.2-configuracao-endpoint-local.md).

## Notas

- Não confundir com Demonware/UNO — este é **manifest CDN** (cdnI). Demonware ainda `UNKNOWN`.
- `103` em `build-selector-103.js` provavelmente versão do selector; pode haver `build-selector-102.js` em builds antigas — verificar via `endpoint-scanner.js` em 3.3.4 dex.

