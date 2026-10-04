# Dead by Daylight Mobile Offline — Pesquisa de Referência

> **Classificação:** `DBD_REFERENCE` — NÃO usar como evidência de `WARZONE_VERIFIED`
> **Data da pesquisa:** 2026-10-04
> **Fontes ao final com `SOURCE / VERSION / DATE / EVIDENCE / CONFIDENCE`**

---

## 1. Objetivo deste documento

Responder: *qual é o mínimo de servidor necessário? O que pode ser mockado? Como separar launcher/cliente/servidor? Como iniciar automaticamente? Como lidar com versões?*

O projeto de referência é o **Dead by Daylight Private Server / Offline Launcher** da comunidade ModByDaylight (e forks). Ele é útil porque mostra um padrão completo de **preservação offline** de um jogo live-service baseado em Unreal Engine.

---

## 2. Repositórios identificados

| Repositório | Descrição | Último release relevante |
|---|---|---|
| `ModByDaylight/PrivateServer` | Private Server canônico — Batch launcher + UnrealPak + mods | v2.2.2 (set 2022) |
| `GGLinnk/DBD-Server` | Fork com 104 commits do anterior | fork ativo até 2025 |
| `CarlosMonarrez/DbDPrivateServer` / `DBD Sandbox Manager` | Sandbox-first: instala Steam oficial como read-only, cria cópia `DbDSandbox` separada, exige Steam Offline Mode | 2025+ |
| `ettfemnio/dbd-server` (arquivado 2026-04-21) | Servidor para **build dev 3.0.0** — redireciona `*.bhvronline.com` via hosts | 3.0.0-dev |
| `SmirkzyyDBD/DBD-Private-Server-Paker` | Empacotador de `.pak` para 6.5.2 | v1.2 |

> Nenhum repositório oficial da Behaviour Interactive. Todos são comunitários e voltados a modding offline.

---

## 3. Arquitetura observada — DBD Private Server (ModByDaylight)

### 3.1. Componentes

```
PrivateServer/
├── PrivateSeverLauncher.bat   # menu interativo (setup / launch / mods)
├── PrivateServer.json         # config (paths, versão)
├── DefaultMods.json           # mods padrão carregados
├── UnrealPak/                 # ferramentas para empacotar .pak
└── (sandbox) DbDSandbox/      # cópia do jogo — NUNCA escreve no install oficial
```

**Princípios:**

1. **Sandbox obrigatório** (evolução do CarlosMonarrez fork): o install oficial do Steam é tratado como read-only. O launcher rejeita paths iguais ou dentro do install oficial. Tudo (`.pak`, `.sig`, `.ucas`, `.utoc`) importado vai só para a sandbox.
2. **Steam Offline Mode guard:** o launcher verifica e exige Steam em Offline Mode antes de lançar a sandbox — reduz risco de ban/contaminação de conta live.
3. **No auto-download proprietário:** o setup NÃO baixa binários privados automaticamente (removido). Usuário importa apenas arquivos locais.
4. **Modding via UnrealPak:** pastas de mod → `.pak` assinado. DefaultMods controla o que carrega por padrão.

### 3.2. Fluxo de lançamento

```
Usuário roda .bat
  → menu: Configure paths / Sync official install to sandbox / Import local .pak / Launch sandbox
  → se Launch:
      verifica Steam Offline Mode
      resolve DbDSandbox path
      injeta/verifica .pak(s) em sandbox
      lança executável da sandbox (não o oficial)
      exibe controles de lobby (v2.2.1+)
```

**O que fica no launcher:**

- resolução de paths e validação
- sync (cópia) do install oficial para sandbox
- importação e empacotamento de mods
- guard de Offline Mode
- orquestração de lançamento

**O que fica no “servidor”:**

No PrivateServer **não há servidor dedicado separado em Erlang/Python** — por ser UE mirror do PC, o “servidor” é:

- o próprio processo do jogo em modo listen/private (peer ou listen server) **dentro da sandbox**, com mods que desbloqueiam lobby controls;
- na variante **ettfemnio/dbd-server (3.0.0-dev)**, há um **servidor fake HTTP/HTTPS + TCP** minimal que responde aos domínios oficiais redirecionados via hosts:

```
127.0.0.1   latest.dev.dbd.bhvronline.com
127.0.0.1   cdn.dev.dbd.bhvronline.com
0.0.0.0     analytic.live.dbd.bhvronline.com
```

e exige:

```ini
[/Script/Engine.NetworkSettings]
n.VerifyPeer=false
```

Isso é o **mínimo de servidor necessário** no caso DbD dev build: responder aos hosts CDN/latest e stub de analytics para o cliente achar que está online.

### 3.3. Serviços online substituídos

| Serviço oficial | Como é substituído no offline |
|---|---|
| Autenticação (Steam/Epic) | Ignorada via Offline Mode guard + sandbox launch |
| Matchmaking | Lobby local com controles de host (PrivateServer v2.2.1+: lobby controls mod) — sem matchmaking global |
| CDN / patches | Sync local do install oficial + import de .pak locais — nada é baixado de CDN remoto |
| Telemetria | Bloqueada via `0.0.0.0 analytic.live.dbd.bhvronline.com` |
| Loja / BP | Não recriada — mods desbloqueiam cosméticos localmente se desejado |

### 3.4. Matchmaking / sessão / bots

- **Matchmaking simulado:** não há fila global. O host cria sala privada e outros jogadores na mesma sandbox build podem entrar (se houver rede) — caso contrário solo vs bots via mods.
- **Bots:** no DbD não são bots de IA completa; são behaviours de killer/survivor já presentes no jogo acessados via mod (lobby controls). Não é IA externa.
- **Sessão:** a sessão é o listen server do próprio UE — o launcher só garante que o cliente aponte para localhost/sandbox.

### 3.5. Versionamento

- Cada release do PrivateServer é amarrada a uma versão do jogo base: `v2.0.0 → DBD 5.6.2`, `v1.1.11.2 → 5.5.0`, Paker `1.2 → 6.5.2`
- Paker tem `copy.sig` para assinar `.pak` por versão — cada versão quebra compatibilidade de mods
- Não há compatibilidade retroativa automática; usuário precisa ter o install oficial da mesma versão para sync.

### 3.6. Builds / distribuição

- Releases no GitHub com `Assets 3` (zip do launcher + UnrealPak + json)
- Setup extrai folder e o `.bat` faz o resto — sem compilação pelo usuário
- Paker distribui binário de empacotamento separado

---

## 4. Segunda variante: ettfemnio/dbd-server (dev 3.0.0)

- Servidor dedicado mínimo para a **build de desenvolvedor 3.0.0** (vazada/histórica).
- Intercepta exatamente 3 domínios via hosts; escuta HTTPS e TCP 3074 (AUTH/LSG pattern similar ao COD Online — ver demonware-companion).
- Útil como referência de **“mínimo viável para cliente achar que está online”**: 2 domínios para conteúdo + 1 para silenciar telemetria, + `VerifyPeer=false` para aceitar cert self-signed do localhost.

---

## 5. Organização de código observada

- Não há `server/` em Node/Go separado — a lógica está em `.bat` + `.ps1` + `UnrealPak` + `.json` de mods.
- Fork `DeadByDaylightProject` (C++ / UE Project) mostra que mods avançados são feitos como projeto UE que gera `.pak`.
- Documentação fica em `ModByDaylight/Documentation` (repo separado, Markdown).

**Lição para WZM:** separar launcher (orquestração + hosts/patch) de servidor (HTTP/TCP/UDP que responde endpoints reais) será necessário porque WZM usa Demonware/IW, não UE listen server puro.

---

## 6. Lições diretas para Warzone Mobile (ainda DBD_REFERENCE)

| Pergunta | Resposta no DbD |
|---|---|
| Mínimo de servidor? | Redirecionar 2-3 domínios para localhost + stub HTTP que responde 200 + telemetry block. Pode ser < 100 linhas se o cliente só precisa de handshake. |
| O que pode ser mockado? | Tudo que não é gameplay autoritativo: auth, CDN version check, analytics. O gameplay pode ser local se o engine permitir listen. |
| O que precisa ser implementado? | No DbD, o gameplay já é local (UE listen). Em WZM provavelmente precisará de server autoritativo (IW/Demonware) — diferença crítica. |
| Como separar componentes? | Launcher = path guard + hosts + launch. Servidor = HTTP(s) stub. Mods/pak = conteúdo. |
| Como lidar com versões? | Amarrar 1 release do offline = 1 versão do jogo base; `copy.sig` por versão. |
| Como iniciar automaticamente? | `.bat` menu → auto-check Offline Mode → auto-sync se necessário → launch. |
| Como organizar projeto? | `launcher/` + `UnrealPak/` + `*.json` + `Documentation/` separado. Releases com zips. |
| Como testar contra localhost? | `hosts` override + `VerifyPeer=false` (ou cert pinning bypass via Frida em mobile). |

---

## 7. Limitações / Riscos observados

- Depende de o cliente aceitar `localhost` via hosts e `VerifyPeer=false` — se WZM faz cert pinning forte ou usa Demonware TCP criptografado, precisará de interceptação mais avançada (Frida, proxy TLS).
- Toda atualização quebra mods/pak — exige manutenção por versão.
- Risco de ban se a sandbox escrever no install oficial — por isso o guard de Steam Offline existe.
- Não há bots “reais” — são apenas roles do jogo base reaproveitadas.

---

## 8. Fontes

**SOURCE:** ModByDaylight/PrivateServer README + releases
**VERSION:** v2.2.2 (set 2022), v2.0.0 (DBD 5.6.2), v1.1.11.2 (5.5.0)
**DATE:** verificado 2026-10-04
**EVIDENCE:** `PrivateSeverLauncher.bat`, `PrivateServer.json`, `UnrealPak/`, Assets 3 por release, README “The Private Server is a modified version … 100% offline”
**CONFIDENCE:** VERIFIED (repositório público inspecionado)
**URL:** https://github.com/ModByDaylight/PrivateServer

**SOURCE:** CarlosMonarrez/DbDPrivateServer — SandboxManager.ps1 + README sandbox-first
**VERSION:** pós-2025
**DATE:** 2026-10-04
**EVIDENCE:** README descreve `DbDSandbox`, `no writes to official Steam install`, `no automatic private-binary downloads`, `Steam Offline Mode check`
**CONFIDENCE:** VERIFIED
**URL:** https://github.com/CarlosMonarrez/DbDPrivateServer

**SOURCE:** ettfemnio/dbd-server (Preston159) — hosts + NetworkSettings
**VERSION:** 3.0.0-dev
**DATE:** arquivado 2026-04-21
**EVIDENCE:** `127.0.0.1 latest.dev.dbd.bhvronline.com`, `n.VerifyPeer=false`
**CONFIDENCE:** VERIFIED
**URL:** https://github.com/Preston159/dbd-server

**SOURCE:** GGLinnk/DBD-Server fork (104 commits)
**DATE:** 2026-10-04
**EVIDENCE:** estrutura idêntica ao PrivateServer, confirma padrão
**CONFIDENCE:** PROBABLE

**SOURCE:** SmirkzyyDBD/DBD-Private-Server-Paker
**VERSION:** 1.2 (DBD 6.5.2)
**DATE:** 2026-10-04
**EVIDENCE:** `copy.sig` por versão
**CONFIDENCE:** VERIFIED
