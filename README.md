# Warzone Mobile Offline Server — Projeto Experimental de Preservação / Interoperabilidade

> **STATUS:** `M0 — Research` | Branch: `arena/01a10449-testtezt` → PR #1 `research: documentação inicial + CI skeleton`
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
├── launcher/        # inicia cliente, redireciona para localhost
├── tools/           # apk-analysis, log-parser, packet-tools, asset-tools
├── docs/            # arquitetura, protocolo, pesquisa, versões, mapas
├── tests/           # testes de regressão
├── scripts/         # automação
└── .github/workflows
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

## 🧪 Como testar localmente (skeleton)

```bash
npm ci
npm run build
npm test
npm run dev   # sobe servidor mock em 0.0.0.0:8080
```

Workflow CI roda em todo PR: checkout → deps → build → testes → artifacts.

---

## 🧭 Roadmap

| Milestone | Nome |
|---|---|
| M0 | Research (atual) |
| M1 | Client communication (localhost) |
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
