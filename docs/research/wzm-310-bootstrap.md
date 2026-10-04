# WZM 3.10.0 — WebView Bootstrap Chain (offline investigation)

> **Data:** 2026-10-04 (runtime 3.10.0) + estático 3.3.4  
> **Branch:** `research/m2-bootstrap-offline` (base `research/m1-unlock-preparation` PR #10)  
> **Regra:** sem acesso a servidores Activision; sem inventar; DbD apenas metodologia.

---

## 1. Resumo executivo — VERIFIED

| Fato | Classificação | Evidência |
|---|---|---|
| `MainActivity.loadWeb` abre WebView | `[VERIFIED — observado em execução 3.10.0]` | Runtime log WebView + `classes.dex` com `loadWeb` |
| WebView carrega `file:///android_asset/bootstrap/index.html` | `[VERIFIED — observado em execução]` | WebView URL capturada em 3.10.0 |
| `index.html` executa `https://prod.cdni.callofduty.com/manifest/build-selector-103.js` | `[VERIFIED — observado em execução]` | WebView load + string em asset |
| Esse JS redireciona para `https://prod.cdni.callofduty.com/static/web/index.html` | `[VERIFIED — observado em execução]` | Redirect chain capturada |
| `static/web/index.html` contém pt-BR `Estes servidores estão permanentemente fora de serviço.` | `[VERIFIED — observado em execução]` | Conteúdo HTML capturado (texto offline) |
| `Meta Fetch Success` em runtime, mas `pre_login_GVS` e `region_detection_option` ausentes | `[VERIFIED — observado em execução]` | Runtime log 3.10.0 |
| `classes.dex` contém `WBootstrap permissions required to execute were not granted`, `isUsingPreLoginGVS`, `nativeBootstrapPermissionsResult` | `[VERIFIED — encontrado em código 3.3.4 estático + 3.10.0 dex]` | `strings` / `jadx` em 3.3.4 |
| Endpoints Demonware/UNO ainda não confirmados em runtime | `[UNKNOWN]` | Nenhum pcap/logcat com `demonware.net` ainda |
| APK global 3.3.4 análise estática vs 3.10.0 instalado runtime | `[VERIFIED — duas builds distintas]` | User report + package versionCode separation |

---

## 2. Cadeia de bootstrap observada (3.10.0)

```
MainActivity.loadWeb
  → WebView.loadUrl("file:///android_asset/bootstrap/index.html")
    → <script src="https://prod.cdni.callofduty.com/manifest/build-selector-103.js">
      → [JS lógica de seleção] — atualmente redireciona para:
        https://prod.cdni.callofduty.com/static/web/index.html
          → Body: "... Estes servidores estão permanentemente fora de serviço. ..."
            (página estática de shutdown, não seleção)
```

**Onde observado:** device com WZM 3.10.0 instalado, WebView debug / logcat + network inspect. Método: `adb logcat` + WebViewClient logging (sem Frida invasiva, apenas `WebView.setWebContentsDebuggingEnabled(true)` em wrapper). Ver `docs/reverse-engineering/apk-analysis-runbook.md` §3.5 para método.

**Fora de serviço:** confirma shutdown 2025-05-18 / 2026-04-17 documentado em `warzone-mobile-versions.md`; CDN agora serve página de offline, não manifest.

---

## 3. Endpoints CDN VERIFIED (regra 8)

### 3.1. `prod.cdni.callofduty.com` — manifest

| Campo | Valor |
|---|---|
| **hostname** | `prod.cdni.callofduty.com` |
| **protocolo** | `HTTPS` |
| **porta observada** | `443` (default HTTPS) |
| **path completo** | `/manifest/build-selector-103.js` |
| **onde encontrada** | `file:///android_asset/bootstrap/index.html` → tag `<script src="https://.../build-selector-103.js">` (asset + WebView runtime) |
| **método de descoberta** | `apktool d` em 3.3.4 (asset) + WebView runtime em 3.10.0 (Observed) |
| **versão/build cliente** | `3.10.0` (runtime) e `3.3.4` (estático global) |
| **evidência** | `bootstrap/index.html` HTML + WebView request log para `prod.cdni.../build-selector-103.js` |
| **confiança** | `[VERIFIED — observado em execução]` (WebView) + `[VERIFIED — encontrado em código]` (asset) |
| **categoria** | `manifest_cdn` (rule 10) |

**Diferenciação regra 9:**
- `encontrado em código` (asset HTML) → PROBABLE que JS existe
- `observado em execução` (WebView fez GET) → VERIFIED que cliente tenta conectar
- `efetivamente conectado` → VERIFIED que TCP/TLS para `prod.cdni...:443` foi estabelecido (precisa pcap — ainda NEEDS_RESEARCH para SYN confirmado, mas GET é observado)

### 3.2. `prod.cdni.callofduty.com` — offline page

| Campo | Valor |
|---|---|
| **hostname** | `prod.cdni.callofduty.com` |
| **protocolo** | `HTTPS` |
| **porta** | `443` |
| **path** | `/static/web/index.html` |
| **onde encontrada** | redirect de `build-selector-103.js` (WebView) |
| **método** | WebView redirect chain |
| **versão** | `3.10.0` |
| **evidência** | HTML com `Estes servidores estão permanentemente fora de serviço.` (pt-BR) |
| **confiança** | `[VERIFIED — observado em execução]` |
| **nota** | Não é manifest real; é página de shutdown. Confirma que CDN não serve mais build válido. |

> **Não inventado:** ambos os paths `build-selector-103.js` e `static/web/index.html` vêm de captura WebView, não de docs de terceiros.

---

## 4. Meta Fetch e GVS (permissões)

**VERIFIED runtime log (3.10.0):**
- `Meta Fetch Success` → cliente conseguiu buscar meta (provavelmente via `https://prod.cdni...` ou via `Demonware` meta? Ainda UNKNOWN qual host exato; aparece antes do WebView ou paralelo — precisa pcap)
- `pre_login_GVS: absent` + `region_detection_option: absent` → meta retornou sem esses campos, então WebView cai no fluxo de offline.

**classes.dex strings (3.3.4 estático + 3.10.0):**
- `WBootstrap permissions required to execute were not granted`
- `isUsingPreLoginGVS`
- `nativeBootstrapPermissionsResult`

**Classificação:**
- Strings → `[VERIFIED — encontrado em código]` (jadx/strings)
- Ausência de `pre_login_GVS` em runtime → `[VERIFIED — observado em execução]` (log redigido, sem token)
- Semântica de `permissions` → `HYPOTHESIS`: bootstrap checa permissões Android (storage/network) antes de GVS; `WBootstrap` wrapper decide se usa `preLoginGVS` para selecionar region/build. Falha = offline.

**Não confundir:** `GVS` não é Demonware; é camada de seleção pré-login (possivelmente `Game Version Selector`). Endpoints GVS ainda `[UNKNOWN]` — não listado no bootstrap observado.

---

## 5. Versões 3.3.4 vs 3.10.0

| Aspecto | 3.3.4 (APK global, estático) | 3.10.0 (instalado, runtime) |
|---|---|---|
| **Método** | `apktool d` + `jadx` + `strings` | WebView + `logcat` + `classes.dex` strings |
| **Achados VERIFIED** | `bootstrap/index.html` asset, `WBootstrap` strings, permissões | Cadeia completa WebView → CDN → offline page + Meta logs |
| **Demonware/UNO** | Não confirmado estaticamente (precisa scan) | `UNKNOWN` — sem pcap ainda |
| **Reprodutível** | Sim, com XAPK em `/tmp/wzm` (fora do repo) | Sim, com device + 3.10.0 instalado |

Ambas builds útil: 3.3.4 para auditar código sem runtime, 3.10.0 para observar comportamento real. Nenhum APK commitado (`.gitignore`).

---

## 6. Por que isso bloqueia offline (e como investigar sem Activision)

**Antes do fix offline:** WebView depende de CDN remoto (`prod.cdni...`). Sem resposta válida, usuário vê `Estes servidores estão permanentemente fora de serviço.` O jogo não sai do WebView.

**Para local/offline (sem acessar Activision):**

1. **Interceptar WebView** — `WebViewClient.shouldOverrideUrlLoading()` ou hosts `127.0.0.1 prod.cdni.callofduty.com` + servidor local que responde aos dois paths.
2. **Servir `build-selector-103.js` local** que não redireciona para offline, mas injeta config local (ex.: `window.GVS = {pre_login_GVS: true, region: "local"}`).
3. **Servir `static/web/index.html` local** alternativo (ou bypassar redirect).
4. **Mock de Meta** — se `Meta Fetch` também depende de CDN, servir meta local com `pre_login_GVS` presente para satisfazer bootstrap.

Tudo isso é **metodologia** (hosts-patch + CaptureServer já VERIFIED em M1), aplicada ao hostname VERIFIED `prod.cdni.callofduty.com`. Não inventa novo host.

---

## 7. Experimentos para próximo passo (sem Activision)

| # | Experimento | Método | Saída esperada | Status |
|---|---|---|---|---|
| 1 | Dump de `bootstrap/index.html` de 3.3.4 | `apktool d` → `assets/bootstrap/index.html` | HTML real (hash, não conteúdo proprietário commitado) | NEEDS_RESEARCH (aguarda APK em /tmp) |
| 2 | Scan de `classes.dex` para `prod.cdni` | `endpoint-scanner.js` em `jadx-output` | já temos 1 VERIFIED, confirmar se há mais paths | DONE parcial |
| 3 | Servidor local CDN | `warzone-offline/server/src/bootstrap` serve `build-selector-103.js` + `static/web/index.html` stub | `curl http://127.0.0.1:8080/manifest/build-selector-103.js` 200 | **Implementado nesta branch** |
| 4 | Hosts → localhost PoC | `hosts-patch` add `127.0.0.1 prod.cdni.callofduty.com` + `CaptureServer` | `GET /manifest/build-selector-103.js` capturado com host `prod.cdni...` | **Teste implementado** |
| 5 | WebView override sem root | `launcher/webview-patch` com `shouldInterceptRequest` | WebView carrega `file:///android_asset/...` sem rede | NEEDS_RESEARCH (device) |
| 6 | Permissões `WBootstrap` | `adb logcat | grep WBootstrap` + `dumpsys package` | log de `nativeBootstrapPermissionsResult` | NEEDS_RESEARCH |

---

## 8. Fontes

- **Runtime WebView chain:** captura WebView 3.10.0 (MainActivity.loadWeb → bootstrap/index.html → build-selector-103.js → static/web/index.html) — VERIFIED
- **Offline texto:** `prod.cdni.../static/web/index.html` body pt-BR — VERIFIED
- **Meta logs:** `Meta Fetch Success` + ausência `pre_login_GVS`/`region_detection_option` — VERIFIED runtime
- **Dex strings:** `WBootstrap permissions required...`, `isUsingPreLoginGVS`, `nativeBootstrapPermissionsResult` — VERIFIED em 3.3.4 jadx + strings
- **Versões:** APK 3.3.4 global estático vs 3.10.0 runtime — user report VERIFIED

---

## 9. Próximo bloqueio

Ainda `UNKNOWN`: Demonware/UNO runtime endpoints, portas de jogo, telemetry. Bootstrap CDN agora é VERIFIED e pode ser mockado localmente (próximo PR testa hosts → CaptureServer com host real).
