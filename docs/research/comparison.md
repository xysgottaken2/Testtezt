# Comparação — Dead by Daylight Mobile vs Warzone Mobile

> **Aviso:** `DBD_REFERENCE` ≠ `WARZONE_VERIFIED`. Esta tabela compara para decidir **o que reaproveitar** e **o que não assumir**.

---

## 1. Tabela comparativa

| Dimensão | Dead by Daylight Mobile (DBD_REFERENCE) | Warzone Mobile (WARZONE_*) | Implicação para servidor offline |
|---|---|---|---|
| **Engine** | Unreal Engine (UE) | **IW 9.0 MGL** (PC engine portado) | WZM exigirá reverse mais baixo nível (.so nativo, não Blueprints) |
| **Backend** | Behaviour NetEase / `bhvronline.com` (HTTP simples) | **Demonware** (State Engine + Matchmaking+, Erlang/Python, TCP 3074 + UDP) | WZM mínimo de servidor é maior e binário; não basta stub HTTP |
| **Transporte** | HTTPS (latest/cdn) + block analytics | HTTPS + **TCP 3074 AUTH/LSG** + **UDP gameplay** + HTTPS CDN | WZM exige emulação de protocolo stateful, não só HTTP mock |
| **Autenticação** | Steam/Epic via Offline Mode guard | Activision account + token + key embutida (8 bytes mutáveis) | WZM precisará mockar token + handshake TCP |
| **Matchmaking** | Inexistente offline — lobby local via mod (host cria sala) | Demonware Matchmaking+ (milhares matches/seg) | WZM precisará recriar fila/matchmaking ou atalho “criar sessão direto” |
| **Sessão / Host** | UE listen server dentro da sandbox (peer) | Demonware dedicated / State Engine autoritativo | WZM não pode só “lançar listen” — precisará de server autoritativo mínimo (posição, saúde, etc.) |
| **Mapa** | Procedural / random, leve | **Verdansk (120p), Rebirth (48p)** + POIs pesados, ~5 GB shards | WZM streaming/CDN é crítico; DbD não tem equivalente |
| **Streaming** | Não relevante | `split_asset_pack/shard/*.shard` + `manifest.json` + KAPIs/xpak + CDN | WZM M12 dedicado a emular CDN |
| **Assets** | `.pak/.ucas/.utoc/.sig` (UnrealPak) | `.shard` (`IWffn100`) / `.xpak` / KAPIs | Ferramentas diferentes (UnrealPak vs IW shard) |
| **Bots** | Roles nativas reaproveitadas (sem IA externa) | Sem bots nativos; IW bots teriam que ser implementados (navegação, tiro, zona) | WZM M10 é IA real, não só unlock |
| **Launcher** | `.bat` + `.ps1` + `PrivateServer.json` + UnrealPak; guard Steam Offline | Precisará: hosts override + TLS bypass (Frida) + launch via `adb`/`am start` ou deep link | WZM launcher é mais invasivo (mobile cert pinning) |
| **Versionamento** | 1 release offline = 1 versão DBD (ex: v2.0.0 → DBD 5.6.2); `copy.sig` por versão | 1 XAPK = 1 temporada (ex: S5 2024-08-21 com POIs exclusivos); shards/manifest por versão | Mesma estratégia: amarrar release a build específica; começar por LR AU ou Global vanilla |
| **Distribuição** | GitHub Releases Assets 3 (zip launcher) | Mesmo padrão, mas **nunca incluir XAPK/shard** | Seguir sandbox-first: usuário extrai shard localmente |
| **Dificuldade** | **Média** — UE modding bem documentado, listen server já existe | **Alta** — IW 9.0 nativo, Demonware fechado, sem listen fallback, streaming pesado, Ricochet | WZM exige pesquisa de protocolo + server autoritativo |
| **PC Host** | Não aplicável (PC já é host) | **PC → Wi-Fi/LAN → Android** (M0-M15) depois **Android → Android** (M16 Termux) | WZM terá 2 fases de host |
| **Anti-cheat** | Não relevante offline | Ricochet (PC kernel; mobile [HYPOTHESIS] lib) | Não burlar para servidores oficiais; offline pode exigir bypass local (Frida) apenas para localhost |

---

## 2. O que pode ser reaproveitado (PROBABLE)

1. **Sandbox-first workflow** (CarlosMonarrez): nunca escrever no install oficial; criar cópia `WzSandbox/` e validar paths. Reduz risco de ban/contaminação.
2. **Launcher como orquestrador** (ModByDaylight): menu `Configure paths / Sync / Import shards / Launch` — mesmo UX para WZM.
3. **Hosts override + `VerifyPeer=false` pattern** (ettfemnio): mínimo viável para redirecionar CDN/latest para localhost. Testar no WZM antes de tentar proxy complexo.
4. **Amarrar release a versão** + `copy.sig`-like (hash por shard) para invalidar cache quando atualizar.
5. **Documentação separada** (`ModByDaylight/Documentation`) → nosso `docs/` com templates de protocolo.

---

## 3. O que NÃO pode ser assumido

- ❌ “DbD só precisou de 2 hosts HTTP, logo WZM também” — WZM tem **TCP 3074 + UDP** que DbD não tem.
- ❌ “Bots são só unlock de role” — WZM precisará de **IA de navegação/tiro** real.
- ❌ “UnrealPak funciona para WZM” — WZM usa **IW shard/xpak**, ferramenta diferente.
- ❌ “Offline Mode guard do Steam serve para Activision” — WZM usa **Activision account**, guard será diferente (ex: bloquear `activision.com` ou mockar token).
- ❌ “Listen server basta” — IW 9.0 WZM provavelmente **não tem listen server** exposto; precisará de **dedicated autoritativo**.

---

## 4. Estratégia recomendada para WZM (derivada, mas a validar)

```
Fase 1 (M1-M2): Reaproveitar pattern hosts → HTTP mock (DbD) para CDN
  └─ validar que cliente aceita localhost para manifest/shard

Fase 2 (M3-M4): Divergir — emular Demonware AUTH/LSG TCP 3074
  └─ usar demonware-companion (buffer_deserializer) como referência

Fase 3 (M6-M10): Construir State Engine mínimo (posição, rotação, saúde)
  └─ sem equivalente no DbD — pesquisa nova

Fase 4 (M12): CDN streaming (shard) — reaproveitar ideia de “import local shard” do DbD

Fase 5 (M15-M16): LAN (PC host → Android, depois Android host via Termux)
  └─ DbD não precisou de LAN dedicada (PC já host); WZM precisará testar hotspot/Wi-Fi + DemonwarePortMapping UPnP
```

---

## 5. Matriz de confiança

| Afirmação | Confiança |
|---|---|
| DbD arquitetura descrita aqui | VERIFIED (repos inspecionados) |
| WZM usa Demonware | PROBABLE (crédito + IW 9.0, sem APK ainda) |
| WZM precisa de TCP 3074 + UDP | HYPOTHESIS (analogia COD Online) |
| WZM streaming usa shard/manifest | PROBABLE (ResHax) |
| WZM pode ser offline só com HTTP mock | UNKNOWN (precisa testar) |
| WZM tem Ricochet mobile | HYPOTHESIS |

---

## 6. Próximo passo

Validar a coluna WZM com **APK strings sweep** (Issue #3) e **logcat boot** (Issue #6). Até lá, manter separação `DBD_REFERENCE` vs `WARZONE_*` em toda a doc.
