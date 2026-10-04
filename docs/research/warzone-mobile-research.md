# Warzone Mobile Offline Server — Síntese da Pesquisa (M0)

> **Data:** 2026-10-04 | **Branch:** `arena/01a10449-testtezt` | **PR:** #1 `research: documentação inicial + CI skeleton`
> **Método:** web search (GitHub, wikis, fóruns, artigos técnicos) + análise de arquitetura Demonware/IW

---

## 1. Perguntas que a pesquisa responde

| Pergunta do escopo | Resposta sucinta | Confiança |
|---|---|---|
| Engine? | IW 9.0 MGL (mesmo de MWII/WZ2/BO6) | VERIFIED |
| Arquitetura de rede? | Demonware State Engine + Matchmaking+ (Erlang/Python) — PROBABLE para WZM | PROBABLE |
| Existe servidor privado de WZM? | **Não encontrado** offline/local; só COD2 master emulator, COD Online Dissected, DbD PrivateServer | VERIFIED (busca GitHub) |
| Warzone Mobile offline já existe? | Não; DbD Mobile offline existe e serve de referência | VERIFIED |
| Como DbD offline funciona? | Launcher .bat + sandbox + hosts override + UE listen server + .pak mods | VERIFIED |
| Versões WZM? | Project Aurora 2022-03-10 → LR AU 2022-11-30 → Global 2024-03-21 → Delist 2025-05-18 → Offline 2026-04-17 | VERIFIED |
| Mapas? | Verdansk 120p, Rebirth 48p (36-48), Shipment/Shoot House/Scrapyard | VERIFIED |
| Streaming? | `split_asset_pack/assets/shard/*.shard` + `manifest.json` (IWffn100, KAPIs/xpak), 320 MB XAPK + 3.5–5.5 GB shards | PROBABLE |
| Protocolo? | AUTH/LSG TCP 3074 + HTTPS + UDP gameplay (por analogia COD Online); WZM exato UNKNOWN | HYPOTHESIS |
| Mínimo de servidor (DbD)? | 2 hosts HTTP para localhost + telemetry block (DbD 3.0.0-dev) | DBD_REFERENCE VERIFIED |
| Mínimo de servidor (WZM)? | HTTP mock + TCP 3074 AUTH/LSG + UDP stub — maior que DbD | HYPOTHESIS |

---

## 2. Evidências por categoria (formato obrigatório)

### 2.1. Engine IW 9.0

**SOURCE:** COD Fandom IW engine table, Wikipedia IW, NamuWiki IW
**VERSION:** IW 9.0 MGL (MWII 2022, WZ2 2022, MWIII 2023, WZM 2024, BO6 2024)
**DATE:** 2026-10-04
**EVIDENCE:** “IW 9.0 · Warzone Mobile · 2024 · Mobile port of MWII build, dubbed MGL” (Fandom); “IW 9.0 … first time engine shared across all studios … mobile” (NamuWiki)
**CONFIDENCE:** VERIFIED

### 2.2. Demonware

**SOURCE:** Wikipedia Demonware, demonware.net, Grokipedia, zzVertigo/CodOnline-Disected (2021-02-04), hosseinpourziyaie/demonware-companion
**VERSION:** State Engine (C++) + Matchmaking+ (Erlang/Python), COD Online `formal-zone-(auth/lsg).codol.qq.com:3074`
**DATE:** 2026-10-04
**EVIDENCE:** `AUTH server listening on port 3074`, `8 bytes mutáveis + key embutida`, `DemonwarePortMapping UPnP UDP:25656`, `buffer_deserializer` tool
**CONFIDENCE:** VERIFIED (para Demonware em geral) / PROBABLE (para WZM especificamente, via crédito Fandom “Demonware (assisted)”)

### 2.3. DbD Mobile Offline

**SOURCE:** ModByDaylight/PrivateServer, CarlosMonarrez/DbDPrivateServer, ettfemnio/dbd-server, GGLinnk/DBD-Server
**VERSION:** PrivateServer v2.2.2 (DBD 5.6.2), SandboxManager (2025), dbd-server 3.0.0-dev
**DATE:** 2026-10-04
**EVIDENCE:** `PrivateSeverLauncher.bat`, `PrivateServer.json`, `UnrealPak/`, `DbDSandbox` guard, `127.0.0.1 latest.dev.dbd.bhvronline.com`, `n.VerifyPeer=false`
**CONFIDENCE:** VERIFIED (DBD_REFERENCE)

### 2.4. Versões WZM

**SOURCE:** COD Fandom Warzone Mobile, VentureBeat, WindowsCentral, Wiki Verdansk, Notebookcheck/EGW/TalkEsport/Undrads
**VERSION:** LR AU 2022-11-30, Global 2024-03-21, S5 2024-08-21, BO6 S3 2025-04-03, Delist 2025-05-18, Offline 2026-04-17
**DATE:** 2026-10-04
**EVIDENCE:** tabelas Fandom dev/announcement, “50M pre-reg” (VentureBeat 2024-02-28), “Servers offline 2026-04-17” (Undrads/Notebookcheck)
**CONFIDENCE:** VERIFIED

### 2.5. Streaming

**SOURCE:** ResHax .shard thread (2024-04-21), r/WarzoneMobile streaming post (2024-04-26), MobileMatters/GINX XAPK sizes
**VERSION:** `split_asset_pack/assets/shard/` + `manifest.json` + `IWffn100` + XAPK 320 MB + 3.5–5.5 GB
**DATE:** 2026-10-04
**EVIDENCE:** “found .shard files and manifest.json … IWffn100 … KAPIs (xpak)”, “streaming the graphics from another server”
**CONFIDENCE:** PROBABLE

---

## 3. Desconhecidos críticos (UNKNOWN → Issues)

| # | Desconhecido | Issue |
|---|---|---|
| 1 | Lista exata de domínios/hosts WZM (demonware, cdn, analytic) | #1 |
| 2 | Portas reais (3074? 27000? UDP?) | #1, #2 |
| 3 | versionCode/versionName por build | #3 |
| 4 | Formato de manifest.json / shards (criptografado?) | #4 |
| 5 | Serialização Demonware no WZM (JSON vs binary) | #2 |
| 6 | Cert pinning / VerifyPeer | #6 |
| 7 | Tick rate real WZM | #7 |
| 8 | O que cliente já possui vs precisa do servidor (mapa local?) | #3, #11 |
| 9 | Ricochet mobile lib | #8 |

---

## 4. Hipóteses a testar (primeiro)

1. **H1:** WZM usa `*.demonware.net` + `*.activision.com` para auth/matchmaking — testar com `strings` no XAPK.
2. **H2:** Handshake AUTH igual ao COD Online (8 bytes mutáveis + key) — testar com Frida + mitmproxy.
3. **H3:** Cliente aceita `hosts` override para CDN se `VerifyPeer=false` ou Frida bypass — testar PoC HTTP mock.
4. **H4:** Verdansk vanilla já está no OBB base e não precisa CDN para spawn — testar boot offline sem rede.
5. **H5:** Tick ~21 Hz (48 ms) similar ao WZ PC — medir via Wireshark.

---

## 5. Próximo passo (ordem estrita do escopo §31)

1. Criar Issues #1–#8 (este PR já cria templates)
2. Ferramentas `tools/apk-analysis/` + `tools/asset-tools/shard-inventory.py` (skeleton neste PR)
3. Em `research/network` branch: executar APK strings sweep + logcat boot quando APK preservado disponível
4. Documentar cada achado com template `docs/protocol/`
5. Só então M1: servidor mock HTTP que responde 200 em localhost

**Não pular para implementação completa de gameplay.**

---

## 6. Riscos

- APKs WZM já delistados — preservação depende de dumps locais; sem APK, networking permanece HYPOTHESIS.
- IW 9.0 + Demonware é significativamente mais complexo que UE listen server do DbD — schedule M1-M10 pode subestimar protocolo.
- Streaming de 5 GB exige emulação parcial ou cliente precisa de todos os shards — pode bloquear M12 se manifest for assinado.

---

## 7. Conclusão

**M0 atingiu:** pesquisa profunda, referência DbD documentada, versões mapeadas, arquitetura proposta, streaming investigado, metodologia de reversão definida, CI skeleton e issues criados.

**Próximo milestone M1:** validar que conseguimos fazer o cliente falar com `localhost` (hosts PoC + HTTP mock) e documentar o primeiro endpoint real.
