# Warzone Mobile Offline Server — Projeto Experimental de Preservação / Interoperabilidade

> **STATUS:** `Launcher Android MVP` | Branch: `feature/launcher-apk` → APK `wzm-offline-launcher-debug` (127.0.0.1:18081, Compose)  
> **Anteriores:** `M2.2.1 → research/m2.2.1-shard-inventory` (PR #14), `M2.2 → PR #13`, `M2.1 → PR #12`, `M2 Bootstrap → PR #11`, `M1 → PR #10`  
> **Objetivo de longo prazo:** fazer o cliente de **Call of Duty: Warzone Mobile** entrar em uma partida local contra bots, sem depender da infraestrutura online oficial, via servidor local/privado.

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

## ⚠️ Prioridade absoluta

**FAZER O CLIENTE ENTRAR EM UMA PARTIDA LOCAL.**

Não é necessário recriar inicialmente: loja, microtransações, Battle Pass, contas Activision reais, matchmaking global, serviços sociais, eventos online, telemetria real, servidores oficiais.

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
| [M1 — Endpoint Discovery](docs/research/m1-endpoint-discovery.md) | M1: como o cliente resolve endpoints e mecanismo para localhost |
| [M2 — Bootstrap 3.10.0](docs/research/wzm-310-bootstrap.md) | **NOVO M2:** WebView `bootstrap/index.html → build-selector-103.js → offline pt-BR` VERIFIED |
| [Permissões & GVS](docs/research/wzm-permissions-gvs.md) | **NOVO M2:** `WBootstrap / pre_login_GVS / Meta Fetch` VERIFIED vs UNKNOWN |
| [Frida / Cert Pinning](docs/reverse-engineering/frida-bypass.md) | Procedimento quando APK disponível |
| [Runbook APK](docs/reverse-engineering/apk-analysis-runbook.md) | Runbook para APK em /tmp/wzm |
| [CDN Build-Selector](docs/protocol/cdni-build-selector.md) | **NOVO M2:** `prod.cdni.callofduty.com/manifest/build-selector-103.js` VERIFIED |
| [CDN Offline Page](docs/protocol/cdni-offline-page.md) | **NOVO M2:** `static/web/index.html` `fora de serviço` VERIFIED |
| [Android Launcher](docs/launcher.md) | **NOVO MVP:** Launcher Android `127.0.0.1:18081` + UI Compose + `com.activision.callofduty.warzone` |
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

## 🏗️ Arquitetura modular (proposta)

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

## 🔬 Primeiro protótipo — meta incremental

```
Cliente → localhost → Servidor → Resposta válida
```

Depois, nesta ordem:

1. cliente conecta
2. passa pela inicialização
3. perfil local
4. matchmaking local
5. lobby
6. sessão
7. spawn
8. movimento
9. arma
10. bot

Cada etapa com teste automatizado.

---

## ⚙️ Servidor local — configuração futura

```env
HOST=0.0.0.0
PORT=XXXXX          # [UNKNOWN] descobrir porta real
GAME_VERSION=XXXXX  # [UNKNOWN] descobrir build alvo
MAP=VERDANSK        # Verdansk | Rebirth Island
GAME_MODE=BR_SOLO   # [HYPOTHESIS] BR_SOLO, RESURGENCE, etc.
BOT_COUNT=20
DATABASE=sqlite
LOG_LEVEL=debug
```

> Valores não assumidos — descobrir via análise de APK/logs/tráfego.

---

## 🧪 Como testar localmente

```bash
# Server — bootstrap local CDN (M2, VERIFIED chain)
cd warzone-offline/server && npm ci && npx tsc --noEmit && npx vitest run  # 30 testes
npm run dev   # captura em 0.0.0.0:8080 — /health, /__capture

# Launcher Node — hosts-patch + webview-patch (metodologia DbD REFERENCE)
cd ../launcher && npm ci && npx tsc --noEmit && npx vitest run  # 12 testes

# Android Launcher MVP — servidor 127.0.0.1:18081 + UI Compose
cd ../../android && ./gradlew :app:testDebugUnitTest  # 70 testes JVM (servidor, launcher, roteador CDNI, RequestLog e TLS/trust)
./gradlew :app:assembleDebug  # APK em android/app/build/outputs/apk/debug/app-debug.apk
# instalar no S23 Ultra:
adb install android/app/build/outputs/apk/debug/app-debug.apk
# testar health do stub:
adb shell 'curl -v http://127.0.0.1:18081/health'  # deve dar 200 OK
# M3: subir o roteador no app (INICIAR ROTEADOR CDNI) e conferir o log de requests do WZM,
# ou no device: adb shell 'curl -k --resolve prod.cdni.callofduty.com:443:127.0.0.1 https://prod.cdni.callofduty.com/__wzm_offline/health'

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

**M3 comprovado no device (S23 Ultra, 2026-10-04):** 5 conexões do WZM chegaram ao servidor local
(`127.0.0.1:443`) e foram registradas em VER LOGS; todas recusadas pelo cliente no TLS
(`SSLV3_ALERT_CERTIFICATE_UNKNOWN`, `httpRequests=0`) → **o bloqueio restante é exclusivamente confiança de
certificado**. A investigação de TLS/trust/pinning do APK 3.10.0 (read-only, sem modificar/distribuir o APK e
sem bypass) está em [docs/research/m3.2-apk-tls-trust-investigation.md](docs/research/m3.2-apk-tls-trust-investigation.md),
com scanner pronto: `python3 warzone-offline/tools/apk-analysis/tls-trust-scan.py --apk <externo>` (self-test no CI).

**M3 — roteamento CDNI local (sem root, sem tocar no APK do jogo):** o launcher agora intercepta o DNS de
`prod.cdni.callofduty.com` (VpnService *per-app*, rota só de `10.111.222.0/24`) e entrega o HTTPS `:443` a um
servidor embarcado com **certificado nosso** (SAN `prod.cdni.callofduty.com`). Endpoints já comprovados em M2/M2.2
respondem `200` com placeholder marcado; **endpoint desconhecido → `404` controlado com a URL/path exatos no log**
(nada de manifest inventado). Tudo aparece no log do launcher: `[DNS]`, `[CDNI] TCP SYN`, `[TLS]`, `[CDNI] GET …`.
Detalhes, decisão técnica e o bloqueio conhecido (confiança TLS de `targetSdk ≥ 24`):
[docs/research/m3-cdni-integration.md](docs/research/m3-cdni-integration.md).

**APK instalável (CI VERIFIED, 2026-10-04):**

| Item | Valor |
|---|---|
| Artifact | **`wzm-offline-launcher-debug`** — Actions → run do workflow **`build`** (o APK fica listado ao lado do artifact técnico) ou **`android-build`** → seção Artifacts (roda em todo push) |
| Arquivo | `app-debug.apk` (raiz do artifact) + `app-debug.apk.sha256` |
| SHA-256 | **muda a cada execução** — fonte de verdade é o `app-debug.apk.sha256` do artifact. Última verificação automática do CI (job `verify-artifact`, run `37211354617`, commit `1280324`): `05696536a9ab0a1365ac30a70d3b21eb2646108eee6660956d21699dcf455ffd` |
| Tamanho | 15.678.356 bytes (~14,9 MiB) |
| Package | `com.wzm.launcher.debug` (debug) |
| Instalar | `adb install app-debug.apk` (ou tocar no arquivo no device) |
| Verificação | o CI baixa o artifact de volta e confere os 2 arquivos na raiz, sha256 e tamanho (`verify-artifact`) |
| ⚠️ Não confundir | o artifact técnico `warzone-offline-M1` (workflow `build`) tem **só código/docs — sem APK**; o APK é sempre o `wzm-offline-launcher-debug` |

Servidor stub escuta **somente** `127.0.0.1:18081` (`GET /health` → `200 OK`, `GET /` → página, `GET /__hits`, `POST /__reset`).
O caminho real do CDNI é o roteador M3: listeners HTTPS **somente** em endereços específicos (`10.111.222.1:443`, `127.0.0.1:443`), nunca `0.0.0.0`.
Botão **INICIAR WARZONE MOBILE** usa `PackageManager` para `com.activision.callofduty.warzone` (sem Activity hardcoded, sem modificar o APK do jogo).

---

## 🧭 Roadmap

| Milestone | Nome |
|---|---|
| M0 | Research — concluído (PR #1) |
| M1 | Client communication — concluído (PR #9 + #10) |
| M2 | Bootstrap Offline (WebView 3.10.0, GVS/permissões) — **concluído no launcher MVP** |
| M3 | Integração real do CDNI local (DNS + HTTPS embarcado + log de requests do WZM) — **implementado; bloqueio conhecido = confiança TLS do cliente** |
| M3.1 | Tela VER LOGS no APK (RequestLog em tempo real, filtros, contadores, LIMPAR/COPIAR/SALVAR .TXT) — **implementado** |
| M3.2 | Investigação TLS/trust/pinning do APK 3.10.0 (read-only) — **roteamento provado no device; scanner + matriz de decisão prontos; análise do APK pendente do artefato externo** |
| M2 | Local configuration |
| M3 | Local auth/profile |
| M4 | Local matchmaking |
| M5 | Lobby |
| M6 | Game session |
| M7 | Player spawn |
| M8 | Player movement |
| M9 | Weapons |
| M10 | Bots |
| M11 | Map |
| M12 | Asset streaming |
| M13 | Vehicles |
| M14 | Battle Royale systems (zona, loot, gulag) |
| M15 | LAN multiplayer |
| M16 | Android server (Termux) |

Ver [docs/architecture/overview.md#roadmap](docs/architecture/overview.md) e Issues.

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
