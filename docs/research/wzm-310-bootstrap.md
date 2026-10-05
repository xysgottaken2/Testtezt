# WZM 3.10.0 — WebView Bootstrap Chain (offline investigation)

> **Data:** 2026-10-04 (runtime 3.10.0) + estático 3.3.4  
> **Branch:** `research/m2-bootstrap-offline` (base `research/m1-unlock-preparation` PR #10)  
> **Regra:** sem acesso a servidores Activision; sem inventar; DbD apenas metodologia.
> **Atualização M4.3 (2026-10-05):** o `libgame.so` de `origin/main` commit `67d8a52` foi analisado estaticamente; o arquivo é identificado pelo usuário como WZM 3.10.0, mas a versão não foi confirmada pelo ELF. Foi encontrada uma entrada hashed de tipo string candidata com fluxo de leitura ao setter cujo diagnóstico cita `cdni_httpServer`, fallback Prod e montagem até dispatch; a associação nome↔hash é provável, enquanto acesso do usuário e uso local permanecem `UNKNOWN`. Isso não altera nem revalida a cadeia WebView histórica deste documento. Não executar nem alterar WZM/TLS/pinning; ver [M4.3](m4.3-libgame-static-analysis.md) e o snapshot [M4.2](m4.2-configuracao-endpoint-local.md).

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

## 6. Limites das propostas de redirecionamento local (não validadas)

**Observação runtime:** a WebView histórica 3.10.0 carregou o selector remoto e chegou à página de shutdown. Isso comprova aquela cadeia WebView, não o endpoint do CDNI nativo nem a autoria de todo socket do jogo.

As seguintes ideias foram registradas em M0/M2, mas **não** são configuração direta comprovada do WZM:

| Ideia antiga | Estado M4.2 |
|---|---|
| `WebViewClient.shouldInterceptRequest()` no launcher | Não foi encontrada API para o launcher substituir o `WebViewClient` de outro processo. Fazer isso dentro do WZM exigiria modificação/injeção do cliente, fora de escopo. |
| Mapear `prod.cdni.callofduty.com` para localhost | Não é uma opção de configuração do WZM. O helper `hosts-patch` só transforma linhas em memória; não aplica mapping ao Android. Mesmo com resolução local, URL/SNI permaneceriam no hostname original e aceitação do certificado pelo WZM é `UNKNOWN`. |
| Servir localmente um selector que injeta `pre_login_GVS`/região | O JS no `server/src/bootstrap` é um stub próprio do launcher; não há evidência de que o WZM aceite esses campos ou que eles representem configuração de endpoint. Não servir/injetar isso no WZM. |
| Mockar `Meta Fetch` com host/path presumido | Host e path do `Meta Fetch` continuam `UNKNOWN`; não criar mock até existir endpoint/call-site observado. |

A conclusão e o inventário das superfícies estão em [M4.2 — busca de configuração direta](m4.2-configuracao-endpoint-local.md). Não alterar DNS/TLS/trust/pinning, não usar MITM/CA, root, hook ou patch do WZM.

---

## 7. Estado das ferramentas locais (não equivale a teste WZM)

Os testes do servidor validam respostas HTTP locais e fixtures sintéticas. Não simulam DNS do aparelho, não alteram a resolução no WZM e não provam que o WZM aceitaria a resposta/certificado.

| # | Ferramenta/ideia | O que existe | Limite atual |
|---|---|---|---|
| 1 | Inspeção de `bootstrap/index.html` 3.3.4 | Evidência histórica via apktool está descrita neste documento. | O APK não está no workspace; não há nova inspeção nesta revisão. |
| 2 | Busca de `prod.cdni` em `classes.dex` | Há evidência histórica parcial de WZM 3.3.4/3.10.0, separada por build. | Não identifica endpoint de `Meta Fetch` nem CDNI nativo; sem binário local não há nova varredura. |
| 3 | Servidor bootstrap local | `warzone-offline/server/src/bootstrap` serve stubs e escuta em loopback; testes checam esses paths localmente. | Não há integração do servidor com o WZM nem mecanismo direto para mudar a URL cliente. |
| 4 | `hosts-patch` | Funções de transformação de texto e testes unitários sintéticos. | Não escreve no sistema/Android, não aplica mapping e não foi testado com WZM. A hipótese de resolução local permanece fora do escopo atual. |
| 5 | `webview-patch` | Diretório contém apenas nota `DEPRECATED / NÃO EXECUTAR`. | Não há override ativo do WebView WZM. |
| 6 | `WBootstrap` / `Meta Fetch` | Logs/strings históricos descritos acima. | Host/path Meta `UNKNOWN`; não mockar nem alterar sem endpoint/call-site real. |

---

## 8. Fontes

- **Runtime WebView chain:** captura WebView 3.10.0 (MainActivity.loadWeb → bootstrap/index.html → build-selector-103.js → static/web/index.html) — VERIFIED
- **Offline texto:** `prod.cdni.../static/web/index.html` body pt-BR — VERIFIED
- **Meta logs:** `Meta Fetch Success` + ausência `pre_login_GVS`/`region_detection_option` — VERIFIED runtime
- **Dex strings:** `WBootstrap permissions required...`, `isUsingPreLoginGVS`, `nativeBootstrapPermissionsResult` — VERIFIED em 3.3.4 jadx + strings
- **Versões:** APK 3.3.4 global estático vs 3.10.0 runtime — user report VERIFIED

---

## 9. Próximo bloqueio

O host/path de `Meta Fetch`, resolver efetivamente usado, endpoint requisitado em runtime e aceitação de endpoint local permanecem `UNKNOWN`. M4.3 encontrou no `.so` uma entrada hashed candidata cujo valor lido flui ao setter nomeado no diagnóstico `cdni_httpServer`, mais parser com `server_url` e seleção Dev/Prod; a associação nome↔hash é provável, mas escrita suportada pelo usuário, efeito local e envio real não foram demonstrados. A necessidade absoluta de TUN segue `UNKNOWN`. Próximos passos: confirmar versão via APK/build e rastrear estaticamente a origem gravável do valor; o controle positivo `CONTROL_ONLY` permanece um trabalho separado. Nenhum mock de endpoint Meta/asset ou host override deve ser tratado como caminho verificado.
