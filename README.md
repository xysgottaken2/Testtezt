# Warzone Mobile Offline Server — Projeto Experimental de Preservação / Interoperabilidade

> **STATUS:** Stable — launcher Android MVP e pesquisa incremental; artifact próprio `wzm-offline-launcher-debug` (127.0.0.1:18081, Compose).
> **M4.3 (2026-10-05):** análise estática read-only de `libgame.so` encontrou registro/leitura de uma entrada string candidata que flui ao setter cujo diagnóstico cita `cdni_httpServer`, com fallback Prod e montagem de URL até dispatch; um host bare loopback forma hipoteticamente `http://127.0.0.1:443/...`. A associação nome↔hash é provável, e definição suportada pelo usuário, execução runtime e envio de rede continuam `UNKNOWN`; a identidade WZM 3.10.0 não foi confirmada pelo ELF. Sem execução ou mudança TLS/pinning. Ver [M4.3](docs/research/m4.3-libgame-static-analysis.md) e o contexto histórico [M4.2](docs/research/m4.2-configuracao-endpoint-local.md). O TUN segue como a rota de redirecionamento implementada, não como necessidade absoluta demonstrada.
> **M4.5 (2026-10-05):** investigação **estática e sem mudança de código** de `libgame.so` (SHA-256 `28ba7995…`): `libcurl/8.4.0` + `OpenSSL 1.1.1n` estão **linkados estaticamente** no binário (HTTP/TLS no próprio processo que carrega a lib), a camada é `bdHTTP` → `bdHTTPWorker(CURL)`/`(Android)` → `getaddrinfo`; a URL do `cdni.meta` **cruza para Java** por `NewStringUTF` num nativo registrado no `JNI_OnLoad`, e também existe no caminho nativo; o `pre-login.json` é escrito uma vez e nunca lido; há um fence de pre-login que compara uma string de ambiente com `"disabled"`, uma máquina de estados do CDNI com 31 estados e portões de Wi-Fi/celular com estado vindo de Java. Nada de TLS/pinning, TUN, DNS, endpoints, dvars ou binário do jogo foi alterado. Ver [M4.5](docs/research/m4.5-cadeia-estatica-ate-connect.md).
> **M4.6 (2026-10-05, revisão sobre o binário correto):** investigação **estática e sem mudança de código**, agora feita **exclusivamente** sobre `analysis/wzm310/libgame.so` (91.196.224 B, SHA-256 `9796040c…8680`), cujo `buildinf` crava `changelist=19854920`, `p4branch=mgl-release` — **o próprio build 3.10.0.19854920 instalado**, o que resolve a ressalva de versão da v1. Conclusões: (1) a URL do `cdni.meta` (`0x4e45b8`) tem **um único consumidor** no binário — o getter JNI de 20 B em `0x25dc900`, que a devolve a Java via `NewStringUTF`; **nenhum** código nativo do CDNI a referencia; (2) o pedido nativo é montado no bloco `0x15a4e34` do controlador de 34 estados (`0x15a1534`, 47.184 B) com **URL vinda de chamada virtual** (objeto global `0x6c142d0`) e **guarda de URL vazia** em `0x15a4e68`, seguindo para `bdHTTP` (`0x369xxxx`) → libcurl 8.4.0 estático; (3) portões antes do socket: estado 0 do controlador, fence de pré-login (`0x11cdea4`, literal `"disabled"`), política de celular (`0x1dc5200`), `cdni_httpServer` lido em `0x25f6d80`, estado de Wi‑Fi/strings empurrados de Java (o `.so` não tem `ConnectivityManager`/`android.net`/`OkHttp`); (4) diferenças verificadas contra o 3.3.4 descartado: sem literal de base `https://prod.cdni.callofduty.com/wzm`, sem `cdni_check_update_available`, sem `bdHTTPWorker(Android)`, e `pre-login.json` agora usado. **Processo auxiliar permanece `UNKNOWN`** (não há `AndroidManifest.xml` do 3.10.0; a cópia disponível é do APK 3.3.4 e foi rebaixada a contexto histórico). Próximo passo mínimo: `logcat` passivo filtrando `CDNIStateString`/`CDNIErrorCode`/`CacheValidation` + `pm dump` do pacote. Nada de TLS/pinning, TUN, DNS, endpoints, dvars ou binário do jogo foi alterado. Ver [M4.6](docs/research/m4.6-manifest-processos-e-cruzamento.md).
> **Limite de evidência:** loopback, teste sintético e listener do launcher não provam tráfego WZM; atribuição exige tupla/owner UID e correlação. TLS/pinning permanecem intocados. Próxima etapa: `CONTROL_ONLY` separado e investigação TUN.
> **Objetivo de longo prazo (não é milestone atualmente autorizado):** pesquisar interoperabilidade/offline somente dentro dos limites de segurança e evidência documentados.

```
Warzone Mobile Client
       ↓
  Servidor local (PC, depois Android)
       ↓
  Partida local
       ↓
  Jogador + bots (LAN)
```

**Este é um projeto de pesquisa incremental.** Não tente criar tudo de uma vez. Ordem obrigatória:

```
PESQUISAR → OBSERVAR → DOCUMENTAR → HIPÓTESE → TESTAR → IMPLEMENTAR → AUTOMATIZAR BUILD → TESTAR → PR → REVISAR → MERGE
```

---

## ⚠️ Escopo Stable vigente

O objetivo de partida local é uma **meta histórica de longo prazo**, não autorização para contornar autenticação/anti-cheat, modificar o APK ou inventar endpoints. O foco imediato é pesquisa read-only do caminho real de rede, atribuição de tráfego e `CONTROL_ONLY`; nenhuma implementação de backend/offline deve partir de analogias ou hipóteses.

---

## 🗺️ Visão

- [ ] funcionar sem servidores oficiais
- [ ] jogar sozinho contra bots
- [ ] carregar mapas já disponíveis no cliente (Verdansk, Rebirth Island, Shipment/Shoot House/Scrapyard)
- [ ] perfil local
- [ ] matchmaking / lobby / sessões locais
- [ ] gameplay local
- [ ] eventualmente LAN multiplayer
- [ ] eventualmente PC hospeda servidor, celular roda cliente
- [ ] futuramente investigar se o próprio Android hospeda o servidor

---

## 📚 Documentação

| Documento | Descrição |
|---|---|
| [Arquitetura](docs/architecture/overview.md) | Arquitetura modular proposta |
| [DbD Mobile Offline — Referência](docs/research/dead-by-daylight-mobile.md) | Como o launcher/servidor offline de Dead by Daylight funciona |
| [Comparação DbD vs WZM](docs/research/comparison.md) | O que pode e **não pode** ser reaproveitado |
| [Versões do Warzone Mobile](docs/research/warzone-mobile-versions.md) | Tabela Version/Build/Date/Platform/Maps/Backend |
| [Networking & Backend](docs/research/warzone-mobile-networking.md) | Demonware, endpoints, protocolos, TLS |
| [Asset Streaming](docs/research/warzone-mobile-streaming.md) | CDN, manifests, .shard, cache |
| [Metodologia de Reversão](docs/reverse-engineering/methodology.md) | Ferramentas, fluxos, evidências |
| [M1 — Endpoint Discovery](docs/research/m1-endpoint-discovery.md) | Relatório histórico; propostas de redirect supersedidas por M4.2/M4.3 |
| [M2 — Bootstrap 3.10.0](docs/research/wzm-310-bootstrap.md) | WebView histórica `bootstrap/index.html → build-selector-103.js → shutdown`; não prova endpoint nativo nem configuração local |
| [Permissões & GVS](docs/research/wzm-permissions-gvs.md) | **NOVO M2:** `WBootstrap / pre_login_GVS / Meta Fetch` VERIFIED vs UNKNOWN |
| [Frida / Cert Pinning](docs/reverse-engineering/frida-bypass.md) | Procedimento retirado/deprecated; não executar nem alterar TLS/pinning |
| [Runbook APK](docs/reverse-engineering/apk-analysis-runbook.md) | Runbook para APK em /tmp/wzm |
| [CDN Build-Selector](docs/protocol/cdni-build-selector.md) | **NOVO M2:** `prod.cdni.callofduty.com/manifest/build-selector-103.js` VERIFIED |
| [CDN Offline Page](docs/protocol/cdni-offline-page.md) | **NOVO M2:** `static/web/index.html` `fora de serviço` VERIFIED |
| [Android Launcher](docs/launcher.md) | **NOVO MVP:** Launcher Android `127.0.0.1:18081` + UI Compose + `com.activision.callofduty.warzone` |
| [M4.0 — “Verificando atualizações”](docs/research/m4.0-verificando-atualizacoes.md) | **NOVO M4.0:** dá para pular a verificação de atualização? `CANNOT_SKIP_DIRECTLY` + cadeia concreta + scanner do APK |
| [M4.2 — Configuração direta de endpoint local](docs/research/m4.2-configuracao-endpoint-local.md) | **Histórico M4.2:** sem o binário no snapshot `eb16937`; resultado posteriormente limitado pelo M4.3 |
| [M4.3 — Análise estática de `libgame.so`](docs/research/m4.3-libgame-static-analysis.md) | **NOVO M4.3:** registro/leitura candidatos de `cdni_httpServer` ligados por dataflow a setter, host Prod/local e dispatch HTTP; definição user-writable e envio real ainda `UNKNOWN`; binário fora do Git |
| [M4.5 — Cadeia estática até o primeiro `connect()`/DNS](docs/research/m4.5-cadeia-estatica-ate-connect.md) | **NOVO M4.5 (somente leitura):** curl/OpenSSL estáticos no processo; `bdHTTP` → worker CURL/Android → `getaddrinfo`; `cdni.meta` entregue a Java por JNI **e** usado nativamente; `pre-login.json` latente; fence de pre-login com `"disabled"`; 31 estados do CDNI e portões Wi-Fi/celular; **sem nenhuma mudança de código** |
| [M4.1 — Dono das conexões (loopback)](docs/research/m4.1-dono-das-conexoes-loopback.md) | **NOVO M4.1:** `INVALID_UID` é ambíguo por desenho (AOSP `appliesToUid`) — autoria só com uid resolvido; controle atual é loopback do launcher, não `CONTROL_ONLY` |
| [M3.6 — Por que o tráfego do WZM não aparece no TUN](docs/research/m3.6-caminho-real-de-rede.md) | **NOVO M3.6:** contabilidade por UID, processos do alvo, IPv6 classificado (descoberta local × unicast), casamento de destino DNS — para separar "o app não fez rede" de "a rede não passou pelo túnel" |
| [M3.5 — Loopback separado + teste sintético](docs/research/m3.5-loopback-e-teste-sintetico.md) | **NOVO M3.5:** `127.0.0.1:443` só diagnóstico (antes/depois do WZM), listener do túnel com peer/UID, botão TESTE SINTÉTICO e `cdni.meta` real |
| [Síntese da Pesquisa](docs/research/warzone-mobile-research.md) | Estado atual VERIFIED / HYPOTHESIS / UNKNOWN |
| [Template de Protocolo](docs/protocol/template.md) | Como documentar cada mensagem |

Sistema de evidências em toda a doc:

- `[VERIFIED]` observado em build específica
- `[PROBABLE]` forte indício, falta confirmação
- `[HYPOTHESIS]` hipótese a testar
- `[UNKNOWN]` ainda não sabemos
- `[NEEDS_RESEARCH]` experimento necessário

E marcação obrigatória:

- `DBD_REFERENCE` — comportamento do projeto DbD Mobile
- `WARZONE_VERIFIED` — comportamento verificado no Warzone Mobile

> **Regra:** nunca tratar `DBD_REFERENCE` como evidência de `WARZONE_VERIFIED`.

---

## 🏗️ Arquitetura modular — proposta M0 histórica

> A árvore abaixo não descreve endpoints, backend ou rota testados no WZM. No código Stable, `hosts-patch` é transformação de texto e `webview-patch` está retirada; não seguir o antigo roadmap sem evidência e autorização.

```
warzone-offline/
├── server/          # TypeScript/Node — ver docs/architecture/overview.md
│   ├── auth/ profile/ matchmaking/ lobby/ session/ gameplay/
│   ├── player/ bots/ weapons/ vehicles/ map/ streaming/
│   └── inventory/ telemetry/ database/ protocol/
├── launcher/        # Node — hosts-patch / webview-patch (metodologia DbD)
├── tools/           # apk-analysis, log-parser, packet-tools, asset-tools
├── docs/            # arquitetura, protocolo, pesquisa, versões, mapas
├── tests/           # testes de regressão
├── scripts/         # automação
└── .github/workflows

android/             # NOVO — Launcher Android (Kotlin + Compose, 127.0.0.1:18081)
├── app/src/main/kotlin/com/wzm/launcher/
│   ├── LauncherConfig, server/EmbeddedLocalServer+ServerController, wzm/WzmLauncher, ui/MainActivity+LauncherViewModel
│   └── ui/theme, server/OfflineContentServer (stub CDNI)
└── app/build/outputs/apk/debug/app-debug.apk  # artifact wzm-offline-launcher-debug
```

Detalhe completo em [docs/architecture/overview.md](docs/architecture/overview.md).

---

## 🔬 Protótipo M0 — hipótese histórica, não validada

```text
Cliente → localhost → Servidor → Resposta válida
```

Esse diagrama não representa um fluxo WZM observado e não deve orientar implementação. A etapa Stable atual é
pesquisa read-only: M4.3 encontrou um candidato de override CDNI no binário, mas sua disponibilidade e uso local
não foram comprovados. O próximo teste permitido continua sendo `CONTROL_ONLY` de TUN com UID distinto, não
login, backend, matchmaking ou partida.

---

## ⚙️ Servidor local — configuração futura

```env
HOST=127.0.0.1      # servers locais devem ficar em loopback; não usar 0.0.0.0
PORT=XXXXX          # [UNKNOWN] porta do eventual serviço local
GAME_VERSION=XXXXX  # [UNKNOWN] build alvo
MAP=VERDANSK        # hipótese histórica; não há backend/partida implementados
GAME_MODE=BR_SOLO   # hipótese, não protocolo observado
BOT_COUNT=20        # placeholder
DATABASE=sqlite     # proposta M0
LOG_LEVEL=debug
```

> Esta configuração é um rascunho histórico, não configuração atual do WZM. Não inventar endpoints nem usar `0.0.0.0`.

---

## 🧪 Como testar localmente

```bash
# Server — bootstrap local CDN (M2, VERIFIED chain)
cd warzone-offline/server && npm ci && npx tsc --noEmit && npx vitest run  # 30 testes
npm run dev   # server enforces loopback (127.0.0.1); /health and /__capture test only the local process

# Launcher Node — funções de texto hosts; testes não aplicam mapping ao Android nem integram WZM
cd ../launcher && npm ci && npx tsc --noEmit && npx vitest run  # unit tests das funções locais

# Android Launcher MVP — servidor 127.0.0.1:18081 + UI Compose
cd ../../android && ./gradlew :app:testDebugUnitTest  # testes JVM: servidor, launcher, roteador CDNI, RequestLog, TLS/trust, parser IPv4/IPv6 e diagnóstico do caminho WZM->TUN
./gradlew :app:assembleDebug  # APK em android/app/build/outputs/apk/debug/app-debug.apk
# instalar no S23 Ultra:
adb install android/app/build/outputs/apk/debug/app-debug.apk
# testar health do stub:
adb shell 'curl -v http://127.0.0.1:18081/health'  # deve dar 200 OK
# diagnóstico loopback apenas: esta chamada é gerada pelo curl, não pelo WZM.
# Não tratar logs do router como tráfego WZM sem tupla/owner UID e correlação temporal.
adb shell 'curl -k --resolve prod.cdni.callofduty.com:443:127.0.0.1 https://prod.cdni.callofduty.com/__wzm_offline/health'

# Scanner (quando APK disponível, fora do repo)
bash warzone-offline/tools/asset-tools/run-external-apk.sh --apk /storage/emulated/0/Download/warzone.xapk --out-dir /storage/emulated/0/Download/wzm-out
```

Workflow CI roda em todo PR: checkout → deps → build → testes → artifacts (incl. `wzm-offline-launcher-debug`).

### Android Launcher

Ver [docs/launcher.md](docs/launcher.md) — arquitetura Compose, como compilar, o **roteador CDNI local (M3)** e o que ainda não funciona.

**VER LOGS (dentro do APK, sem ADB/Logcat):** botão na tela principal abre o `RequestLog` do roteador em
tempo real — `[DNS]`, `[CDNI]`, `[CDNI?]` (paths desconhecidos), `[HTTP]` (status/código de cada resposta),
`[TLS]`, `[TUN]`, `[VPN]` — com timestamps, filtros por tag, contadores (DNS/TCP/HTTP/TLS), auto-rolar e
botões **LIMPAR LOGS**, **COPIAR LOGS** e **SALVAR/EXPORTAR .TXT**. O log também é persistido em
`filesDir/request-log.txt` (sobrevive a reabrir o app) e nada sensível é registrado (sem corpos/cabeçalhos).
Detalhes em [docs/launcher.md](docs/launcher.md) §5.2.

**Registro histórico de loopback (S23 Ultra, 2026-10-04; não atribuível ao WZM):** cinco peers foram contados em
`127.0.0.1:443`; um ocorreu antes do launch e os outros quatro retornaram `INVALID_UID`. Os erros TLS desses
peers não provam handshake do WZM nem rejeição de certificado pelo jogo. Confiança/pinning permanecem `UNKNOWN`
e intocados. A interpretação atual está em [M3.5](docs/research/m3.5-loopback-e-teste-sintetico.md) e [M4.1](docs/research/m4.1-dono-das-conexoes-loopback.md).

**M4.1 — de quem é a conexão que aparece no listener? (`INVALID_UID` não prova nada):** a API pública
`ConnectivityManager.getConnectionOwnerUid` devolve `-1` em **dois** casos distintos — conexão fora da tabela
**ou** dono fora do per-app da VPN que chamou (AOSP: `appliesToUid`). Logo `dono=NAO_RESOLVIDO (INVALID_UID)` do
log antigo **não** autorizava dizer "não é do jogo". O launcher agora classifica o retorno em seis estados
(`ConnectionOwnership`), dá um veredito de origem **com nível de evidência** a cada conexão aceita
(`origem-da-conexao=… VERIFIED|PROBABLE|UNKNOWN`), calibra a janela de portas do próprio processo e roda um
**teste de controle** com sockets reais (loopback próprio, endereço do túnel, tupla inexistente, escopo da VPN)
com o resultado no log e no card da tela principal. Regra mantida: conexão de loopback **nunca** conta como
tráfego do WZM — só uid resolvido pela API conta. Detalhes e leitura em
[docs/research/m4.1-dono-das-conexoes-loopback.md](docs/research/m4.1-dono-das-conexoes-loopback.md).

**M4.6 — qual processo pede, o que vem antes do pedido, e existe processo auxiliar? (`CDNI_META_SO_PELO_JNI_PORTOS_ANTES_DO_SOCKET`):** leitura estática **exclusiva** de `analysis/wzm310/libgame.so` (91.196.224 B, SHA-256 `9796040c…8680`), com índices reconstruídos do zero (185.959 funções por `.eh_frame_hdr`, 432.675 pares `ADRP+ADD`, 845.031 arestas `BL`) — o `buildinf` crava `changelist=19854920` / `p4branch=mgl-release`, **o próprio build instalado**. **Identidade resolvida:** a ressalva de versão da v1 (feita sobre o `.so` 3.3.4.17654269, agora descartado por decisão do usuário) não se aplica mais. **`cdni.meta`:** a URL existe uma vez, em `0x4e45b8`, e tem **um único consumidor** — o getter JNI de 20 B em `0x25dc900` (`NewStringUTF`, slot `0x538`), registrado em `0x25e0fe8` dentro de `JNI_OnLoad` (`0x25dd758`, 86.980 B); varredura exaustiva (xrefs + ponteiros crus) mostra que **nenhum** código nativo a usa. **Caminho nativo:** controlador de 34 estados em `0x15a1534` (47.184 B, tabela em `0x5729a0`); o pedido é montado em `0x15a4e34` com **URL obtida por chamada virtual** (`0x6c142d0`, slot `+0x60`), **guarda de URL vazia** em `0x15a4e68` e timeout 1000, seguindo para `bdHTTP` (`0x3699758`/`0x369a02c`) → `bdHTTPWorker(CURL)` → libcurl 8.4.0 estático → `/system/etc/security/cacerts/` (só registro). Os templates nativos são outros (`environment.config`, `cdni_manifest_*`, `.shard`, `qos`). **Portões antes do socket:** estado 0 do controlador, fence de pré-login (`0x11cdea4`, 12.780 B, literal `"disabled"` = passa, publica `isPreloginFencePassed`/`isCDNIFinished`), política de celular (`0x1dc5200`), `cdni_httpServer` lido em `0x25f6d80`, e estado de rede empurrado de Java (0 ocorrências de `ConnectivityManager`/`android.net`/`OkHttp`; `isWifiConnected` registrado em `0x1bc154c`; cinco stubs recebem strings por `GetStringUTFChars`). **Processo auxiliar: `UNKNOWN`** — não há `AndroidManifest.xml` do 3.10.0 (a cópia é do APK 3.3.4, rebaixada a contexto histórico) e o `.so` não traz `chromium`/`play.core`/`AssetPack`. **Diferenças verificadas contra o 3.3.4:** sem literal de base `https://prod.cdni.callofduty.com/wzm`, sem `cdni_check_update_available`, sem `bdHTTPWorker(Android)`, e `pre-login.json` com 4 xrefs (antes latente). `cdni_build_info`/`manifest-*.json` entram só como contexto: bases `prod…/wzm` + `dev…/wzm` sem scheme e recipientes `ENCRYPT_COMPRESS` ilegíveis — **nenhum campo interpretado**. Próximo passo mínimo: `logcat` passivo (`CDNIStateString`, `CDNIErrorCode`, `CacheValidation`, `bdHTTPWorker(CURL)`) + `pm dump` do pacote, sem tocar em TLS/TUN/DNS/endpoint. Detalhes em [docs/research/m4.6-manifest-processos-e-cruzamento.md](docs/research/m4.6-manifest-processos-e-cruzamento.md).

**M4.0 — dá para pular “Verificando atualizações”? (`CANNOT_SKIP_DIRECTLY`):** a evidência estática registra
estados de manifesto (`MANIFEST_DOWNLOAD_ERROR`, `manifest_ver`, `num_files_manifest`, `CDNI_MANDATORY_NOT_INSTALLED`),
mas não demonstra um bypass direto. O valor público `min_buildnum=19854920` coincide com o build anotado; a
comparação/efeito no cliente não foi observada. `build-selector-102.js` é selector legado da UI WebView; `103`
redireciona para a página atual de shutdown. Nada disso prova endpoint nativo ou configuração local. Cadeia e
limites em [docs/research/m4.0-verificando-atualizacoes.md](docs/research/m4.0-verificando-atualizacoes.md);
scanner read-only (quando APK exato disponível): `python3 warzone-offline/tools/apk-analysis/update-check-scan.py --apk <externo> --out /tmp/wzm/update-check.json`.

**M3.5 — loopback não é evidência + teste sintético antes do WZM:** `127.0.0.1:443` passou a ser **diagnóstico
secundário** (contadores próprios, papel no log, e a relação `antes/depois do WZM iniciado` em cada conexão —
a 1ª conexão do teste anterior foi 5 s **antes** de o jogo abrir), o listener `10.111.222.1:443` registra
**conexão aceita + peer + UID/pacote**, e o botão **TESTE SINTÉTICO DNS → 10.111.222.1:443 → cdni.meta** prova o
caminho sem o WZM. O `cdni.meta` deixou de ser placeholder e é servido com o **corpo real** (~320 B,
`min_buildnum=19854920`). Procedimento e critério em
[docs/research/m3.5-loopback-e-teste-sintetico.md](docs/research/m3.5-loopback-e-teste-sintetico.md).

**M3 — roteador CDNI local implementado no launcher (não comprova uso pelo WZM):** o serviço pode responder
consultas CDNI que cheguem ao DNS do `VpnService` per-app (rota `10.111.222.0/24`) e direcionar o endereço
virtual ao listener HTTPS local com certificado próprio. Isso é mecanismo do launcher; não prova que o WZM fez
uma consulta, usou esse hostname ou aceitou TLS. Endpoints documentados têm respostas locais; path desconhecido
recebe `404` controlado com URL/path exatos. Não inventar manifestos. Confiança TLS do WZM continua `UNKNOWN`.
Detalhes do código e limites de evidência em [docs/research/m3-cdni-integration.md](docs/research/m3-cdni-integration.md),
[M4.2](docs/research/m4.2-configuracao-endpoint-local.md) e [M4.3](docs/research/m4.3-libgame-static-analysis.md).

**APK instalável (CI VERIFIED, 2026-10-04):**

| Item | Valor |
|---|---|
| Artifact | **`wzm-offline-launcher-debug`** — Actions → run do workflow **`build`** (o APK fica listado ao lado do artifact técnico) ou **`android-build`** → seção Artifacts (roda em todo push) |
| Arquivo | `app-debug.apk` (raiz do artifact) + `app-debug.apk.sha256` |
| SHA-256 | **muda a cada execução** — fonte de verdade é o `app-debug.apk.sha256` do artifact. Última verificação automática do CI (job `verify-artifact`, run `37215523106`, commit `7a4ff2a`): `2dab7e3cb209f1e8044dcab7ec046dab743516d40896aa25427aebe79371470f` |
| Tamanho | 15.740.968 bytes (~14,9 MiB) |
| Package | `com.wzm.launcher.debug` (debug) |
| Instalar | `adb install app-debug.apk` (ou tocar no arquivo no device) |
| Verificação | o CI baixa o artifact de volta e confere os 2 arquivos na raiz, sha256 e tamanho (`verify-artifact`) |
| ⚠️ Não confundir | o artifact técnico `warzone-offline-M1` (workflow `build`) tem **só código/docs — sem APK**; o APK é sempre o `wzm-offline-launcher-debug` |

Servidor stub escuta **somente** `127.0.0.1:18081` (`GET /health` → `200 OK`, `GET /` → página, `GET /__hits`, `POST /__reset`).
O roteador local M3 usa listeners HTTPS em endereços específicos (`10.111.222.1:443`, `127.0.0.1:443`), nunca `0.0.0.0`; isso descreve o launcher e não comprova uso pelo WZM.
Botão **INICIAR WARZONE MOBILE** usa `PackageManager` para `com.activision.callofduty.warzone` (sem Activity hardcoded, sem modificar o APK do jogo).

---

## 🧭 Estado Stable

| Área | Estado atual |
|---|---|
| WebView/bootstrap | Requests históricos de uma build e respostas públicas documentados; não provam endpoint nativo nem caminho local. |
| Roteador CDNI | Implementado no launcher por DNS via `VpnService`/TUN; uso pelo WZM não está atribuído nesta evidência. |
| TLS/trust/pinning | `UNKNOWN`; nenhuma alteração autorizada ou executada. |
| Caminho de rede/owner UID do WZM | `UNKNOWN`; allow-list não prova captura integral. Ver [M3.6](docs/research/m3.6-caminho-real-de-rede.md) e [M4.1](docs/research/m4.1-dono-das-conexoes-loopback.md). |
| Endpoint local direto | Binário contém cadeia estática de registro/leitura candidata de `cdni_httpServer` até montagem e dispatch HTTP, com fallback Prod; associação nome↔hash e entrada bare loopback são `PROBABLE`, enquanto configuração Android suportada e tráfego real são `UNKNOWN`. TUN segue implementado, não provado como única alternativa. Ver [M4.3](docs/research/m4.3-libgame-static-analysis.md). |
| Próxima etapa | `CONTROL_ONLY` em sessão separada com UID distinto; só depois observação do WZM atribuída por tupla/owner UID. |

Os milestones de auth, matchmaking, sessão e gameplay abaixo do antigo roadmap M0 não são trabalho Stable autorizado nem inferências sobre arquitetura WZM.

---

## 🔐 Legal / Distribuição / Preservação

Projeto voltado a **preservação, pesquisa, interoperabilidade, aprendizado**.

**NUNCA commitar no GitHub:**

- APK / OBB / .shard / assets proprietários
- tokens, cookies, credenciais, chaves privadas, dados pessoais
- bypass de anti-cheat para vantagem em servidores oficiais
- ataques contra infraestrutura da Activision
- fraude, roubo de contas

Quando arquivo proprietário for necessário, documentar **como o usuário obtém legalmente** (ex.: extrair do próprio APK instalado).

Anti-cheat (Ricochet) não será burlado para uso em servidores oficiais — pesquisa apenas em ambiente local/offline.

---

## 🤝 Contribuindo

1. Não trabalhar direto em `main` — criar `research/*` ou `feature/*`
2. Commits semânticos: `research:`, `feat:`, `fix:`, `docs:`, `test:`, `build:`
3. Cada PR = problema + pesquisa + hipótese + implementação + testes + limitações + próximo passo
4. CI deve passar (build + testes + lint)

---

## 📜 Licença

Código do servidor/launcher/tools: **a definir (MIT/Apache-2.0 sugerido para pesquisa)**.
Assets do jogo **não incluídos** — pertencem à Activision. Este repositório contém apenas código de interoperabilidade e documentação.

---

## 🙏 Agradecimentos

- Comunidade ModByDaylight / PrivateServer (referência de arquitetura offline)
- Projeto DarkflameServer, cod4-docker, demonware-companion (referências de emulator)
- Fandom/Wiki do Call of Duty, documentação de Demonware/State Engine

---

> **Regra mais importante: NUNCA INVENTE.** Se não souber → PESQUISE. Se não encontrar → `UNKNOWN`. Se tiver hipótese → `HYPOTHESIS`. Se funcionar → `VERIFIED`. Documente falhas também.
