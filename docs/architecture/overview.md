# Arquitetura — Warzone Mobile Offline Server

> **Status:** M0 Research — proposta modular, sujeita a mudanças após pesquisa de protocolo

---

## 1. Visão de alto nível

```
                    ┌─────────────────────┐
                    │   Warzone Mobile    │
                    │   Client (Android)  │
                    │   IW 9.0 MGL        │
                    └─────────┬───────────┘
                              │ HTTPS / TCP 3074 / UDP / WSS ?
                ┌─────────────┼─────────────┐
                │             │             │
         ┌──────▼──────┐ ┌───▼────┐ ┌─────▼─────┐
         │  Launcher   │ │  CDN   │ │ Telemetry │
         │ (hosts/     │ │ (shard │ │ (stub)    │
         │  Frida)     │ │ /manifest)│          │
         └──────┬──────┘ └───┬────┘ └─────┬─────┘
                │            │            │
                └────────────┼────────────┘
                             │
                    ┌────────▼────────┐
                    │  Servidor Local │
                    │  (PC → LAN →    │
                    │   Android)      │
                    │  Node/TS ou Go  │
                    └──────┬──────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
         ┌────▼────┐  ┌───▼────┐  ┌───▼────┐
         │ Profile │  │ Match  │  │Session │
         │  Local  │  │ making │  │Gameplay│
         └─────────┘  └────────┘  └────────┘
```

**Fluxo M1 (mínimo):**

```
Cliente → localhost (hosts override) → Servidor → Resposta válida (200 / handshake)
```

**Fluxo M4-M10:**

```
Cliente → AUTH mock (TCP 3074) → LSG mock → Session → Spawn → Movimento → Bot
```

---

## 2. Estrutura de pastas (proposta)

```
warzone-offline/
├── server/                 # Servidor autoritativo
│   ├── auth/               # Mock Activision token + Demonware AUTH
│   ├── profile/            # Perfil local (JSON → DB)
│   ├── matchmaking/        # Fila local → cria sessionId, adiciona bots
│   ├── lobby/              # Lobby local
│   ├── session/            # Session management (id, players, estado)
│   ├── gameplay/           # Tick, zona, loot, eventos
│   ├── player/             # Posição, rotação, movimento, saúde
│   ├── bots/               # Spawn, percepção, navegação, tiro
│   ├── weapons/            # Armas, munição
│   ├── vehicles/           # Veículos (ATV, LTV, Heli, etc.)
│   ├── map/                # Verdansk / Rebirth — spawns, colisão, LOD
│   ├── streaming/          # Emulação de CDN (manifest.json + .shard)
│   ├── inventory/          # Inventário local
│   ├── telemetry/          # Stub (200 OK vazio)
│   ├── database/           # SQLite local (perfis, sessões)
│   ├── protocol/           # Serialização / deserialização Demonware
│   ├── config.ts           # HOST, PORT, GAME_VERSION, MAP, BOT_COUNT
│   └── index.ts            # Bootstrap
│
├── launcher/               # Orquestrador
│   ├── hosts-patch/        # Escreve 127.0.0.1 para domínios WZM
│   ├── frida/              # Bypass cert pinning se necessário
│   ├── adb/                # Instala/ lança APK via adb
│   └── cli/                # Menu (Configure / Sync / Launch)
│
├── tools/
│   ├── apk-analysis/       # apktool, jadx, strings, shard-inventory
│   ├── log-parser/         # Parser de logcat
│   ├── packet-tools/       # Wireshark helpers, buffer_deserializer
│   └── asset-tools/        # Manifest/shard inspect
│
├── docs/
│   ├── architecture/       # este arquivo
│   ├── protocol/           # um .md por mensagem
│   ├── research/           # DbD, versões, networking, streaming
│   ├── versions/           # por build
│   ├── maps/               # Verdansk, Rebirth
│   └── reverse-engineering/
│
├── tests/                  # Vitest/Jest — ver seção 8
├── scripts/                # dev, build, release helpers
└── .github/workflows/      # CI
```

> A estrutura pode mudar se a pesquisa mostrar arquitetura melhor (ex.: Go ao invés de Node, ou separar `cdn/` como serviço HTTP independente).

---

## 3. Módulos do servidor — responsabilidades

| Módulo | Responsável por | Dependências | Milestone |
|---|---|---|---|
| `auth` | Validar token (mock), handshake 8-byte, responder com entitlements vazios | `protocol`, `database` | M3 |
| `profile` | CRUD perfil local `{id, name, level}` | `database` | M3 |
| `matchmaking` | `createSession`, `sessionId`, `addPlayer`, `addBots`, `startMatch` | `session`, `bots` | M4 |
| `lobby` | Estado de lobby, ready, host | `session` | M5 |
| `session` | Ciclo de vida da partida, ID, mapa, modo | `map`, `gameplay` | M6 |
| `gameplay` | Tick rate (alvo 21 Hz / 48 ms), zona, loot, kill/death | `player`, `bots` | M6-M14 |
| `player` | Posição (x,y,z), rotação (yaw/pitch), movimento, saúde (100), inventário | `protocol` | M7-M9 |
| `bots` | Spawn, percepção, pathfinding, mirar, atirar, morrer | `player`, `map` | M10 |
| `map` | Metadados Verdansk/Rebirth, spawns, colisão, chunks | `streaming` | M11 |
| `streaming` | Servir `manifest.json` + `.shard` de localhost, cache, hashes | — | M12 |
| `weapons` | Definições de arma, dano, munição | `player` | M9 |
| `vehicles` | Spawn e state de veículos | `map`, `player` | M13 |
| `telemetry` | Stub `200 OK` para `analytic.*` | — | M1 |
| `protocol` | (De)serialização binária Demonware, framing, criptografia | — | M1-M2 |
| `database` | SQLite — perfis, sessões, inventário | — | M2-M3 |

---

## 4. Configuração do servidor

```env
# warzone-offline/server/.env.example — [HYPOTHESIS] valores a descobrir
HOST=127.0.0.1
PORT=3074              # AUTH/LSG TCP — confirmar via APK
HTTP_PORT=8080         # CDN mock (manifest/shard)
UDP_PORT=27000         # gameplay — confirmar
GAME_VERSION=1.0.0     # [UNKNOWN] extrair de APK
MAP=VERDANSK           # VERDANSK | REBIRTH_ISLAND | SHIPMENT | SHOOT_HOUSE | SCRAPYARD
GAME_MODE=BR_SOLO      # [HYPOTHESIS] BR_SOLO, RESURGENCE_48, TDM, etc.
BOT_COUNT=20
DATABASE=sqlite:./data/warzone.db
LOG_LEVEL=debug
TICK_RATE=21
```

Cada variável deve ser documentada com `SOURCE / EVIDENCE` quando descoberta.

---

## 5. Fluxo de dados — exemplo M4 (matchmaking local)

```
1. Cliente → POST /matchmaking/create { map: VERDANSK, mode: BR_SOLO }
   → server/matchmaking cria sessionId=uuid, persiste em database
2. Server → adiciona local-player-001 em session
3. Server → spawna BOT_COUNT bots (server/bots) com posições de spawn de warzone-offline/server/map
4. Server → responde { sessionId, serverAddr: HOST, port: UDP_PORT, players: [...] }
5. Cliente → conecta UDP em HOST:UDP_PORT
6. Server → tick loop 21 Hz → broadcast state (posição, saúde) via protocol
```

Protocolo exato (campos, encoding, endpoint) é `[UNKNOWN]` — usar `docs/protocol/template.md` para documentar quando descoberto.

---

## 6. Launcher — design

Inspirado no DbD PrivateServer sandbox-first:

```
launcher/
├── cli.ts              # menu: Configure paths / Sync shards / Import local .shard / Launch
├── config.json         # { gamePath, sandboxPath, serverHost, serverPort }
├── hosts-patch/
│   ├── patch.ts        # escreve/remova entradas 127.0.0.1 para domínios WZM
│   └── domains.json    # lista de domínios a redirecionar (a descobrir)
├── apk-analysis/       # ferramentas estáticas read-only; sem Frida/bypass
└── adb/
    └── launch.ts       # adb install / am start -n com.activision... / logcat
```

**Guards:**

- Nunca escrever no APK/XAPK original — trabalhar em cópia/sandbox.
- Validar que `sandboxPath` ≠ `gamePath` e não está dentro dele (como DbD SandboxManager).
- Exigir que usuário forneça XAPK legalmente (não distribuir).

---

## 7. PC Host → Android (M0-M15) depois Android host (M16)

```
Fase PC Host (adiada):
  PC (127.0.0.1:3074/8080 — loopback somente)
  Sem acesso por Wi-Fi/LAN enquanto vigorar a regra de bind local-only.

Fase Android Host (M16):
  Android (Termux: node server) → localhost → mesmo device cliente
  Investigar: background restrictions, sockets, RAM, thermal throttling
```

**Por que PC primeiro:** reduz complexidade (mais RAM, sem restrições Android, Wireshark fácil).

---

## 8. Testes

```
tests/
├── unit/
│   ├── auth.test.ts
│   ├── profile.test.ts
│   ├── matchmaking.test.ts
│   ├── session.test.ts
│   ├── player.test.ts
│   ├── bots.test.ts
│   └── protocol/
│       ├── serialize.test.ts
│       └── deserialize.test.ts
├── integration/
│   ├── server-startup.test.ts      # servidor sobe em porta X
│   ├── client-connection.test.ts   # mock cliente conecta em localhost
│   └── lobby-flow.test.ts
└── e2e/
    └── full-match.test.ts          # perfil → matchmaking → lobby → spawn
```

Cada PR deve rodar `npm test` no CI.

---

## 9. Roadmap detalhado

| Milestone | Entregável | Critério de pronto |
|---|---|---|
| **M0** | Research (este PR) | Docs DbD, versões, networking, streaming, arquitetura, CI skeleton, issues |
| **M1** | Client communication | Servidor HTTP mock responde 200 em `localhost:8080`; teste `client-connection` passa; `hosts` PoC documentado |
| **M2** | Local configuration | `config.ts` lê `.env`; testes de config; documentação de `HOST/PORT/GAME_VERSION` reais |
| **M3** | Local auth/profile | `POST /auth` mock retorna perfil local; `adb logcat` mostra cliente aceitando perfil |
| **M4** | Local matchmaking | `POST /matchmaking/create` retorna `sessionId`; adiciona player+bots |
| **M5** | Lobby | Estado de lobby, ready, start |
| **M6** | Game session | Tick loop, session lifecycle |
| **M7** | Player spawn | Cliente spawna em coordenada (x,y,z) |
| **M8** | Player movement | Movimento validado autoritativo (server reconcilia) |
| **M9** | Weapons | Tiro, dano, munição |
| **M10** | Bots | Bot spawna, anda, mira, atira, morre |
| **M11** | Map | Verdansk vanilla carregado; spawns documentados |
| **M12** | Asset streaming | `manifest.json` + shards servidos de localhost; cliente não precisa CDN |
| **M13** | Vehicles | Veículos spawnam e são dirigíveis |
| **M14** | BR systems | Zona, loot, gulag, resurgence |
| **M15** | LAN multiplayer | 2 celulares na mesma LAN via PC host |
| **M16** | Android server | Servidor roda em Termux no próprio device |

---

## 10. Decisões de tecnologia (proposta, aberta a mudança)

| Camada | Proposta | Alternativa | Motivo |
|---|---|---|---|
| Servidor | **TypeScript / Node.js** | Go, Rust | Demonware companion já tem tooling JS; fácil para prototipar HTTP+TCP+UDP; equipe JS |
| DB | **SQLite** (`better-sqlite3` / `drizzle`) | Postgres | Local, zero-ops, suficiente para perfil/sessão |
| Testes | **Vitest** | Jest | Nativo TS, rápido |
| Lint | **ESLint + Prettier** | Biome | Padrão |
| Launcher CLI | **Node + commander** | Go binary | Reaproveita deps do server |
| Frida | **frida-tools (Python) + TS bindings** | — | Necessário para TLS bypass em APKs futuros |
| CI | **GitHub Actions (ubuntu-latest)** | — | Gratuito, valida em cada PR |

Se a pesquisa mostrar que Go tem melhor performance para tick 21 Hz com 120 bots, migrar é aceitável — documentar ADR em `docs/architecture/adr/`.

---

## 11. Segurança / Legal

- Servidor só aceita conexões de `localhost` / LAN privada; não exposto à internet.
- Nenhum bypass de Ricochet para servidores oficiais — só mock local.
- `telemetry/` é stub vazio, não coleta dados reais.
- Secrets nunca commitados (`.gitignore` cobre `*.key`, `.env`, `cookies.txt`).
