# Warzone Mobile — Versões, Builds e Ciclo de Vida

> **Data da pesquisa:** 2026-10-04
> **Status:** M0 Research — tabela inicial, sujeita a correções por análise de APK

---

## 1. Tabela principal

| Version (marketing) | Build / Codename | Date | Platform | Maps disponíveis | Backend / Notas |
|---|---|---|---|---|---|
| **Project Aurora** (anúncio) | — | 2022-03-10 | — | — | Anúncio oficial: Digital Legends + Beenox + Shanghai + Solid State + Demonware; Demonware State Engine + Matchmaking+ |
| **Closed Alpha** | Aurora Alpha | 2022-05 | Android (convite) | Verdansk (early) | Alpha fechada |
| **Limited Release — AU** | — | **2022-11-30** | Android/iOS (AU) | Verdansk | Primeiro LR público |
| **Store leak / 120 players** | — | 2022-09 | — | Verdansk (120p leak) | Vazamento Play Store confirmou Verdansk 120p + progressão compartilhada MWII |
| **Limited Release — SE/NO/CL** | — | **2023-03-23** | Android/iOS | Verdansk | Expansão LR |
| **Limited Release — DE** | — | **2023-12-13** | Android/iOS | Verdansk | |
| **Limited Release — MY** | — | **2024-01-10** | Android/iOS | Verdansk | |
| **Global Launch** | MWIII S1 base (IW 9.0 MGL) | **2024-03-21** | Android + iOS | **Verdansk (120p BR), Rebirth Island (48p Resurgence), Shipment / Shoot House / Scrapyard (MP)** | Cross-progression PC/console; 50M+ pré-registros |
| **S1 (MWIII) — neve no norte** | MWIII S1 | 2023-12~2024-03 (dentro do LR → launch) | Android/iOS | Verdansk c/ neve ao norte do Stadium/Hospital | Update de mapa |
| **S5 (MWIII) — novos POIs** | MWIII S5 / S5 Reloaded 2024-08-21 | 2024-08 | Android/iOS | Government Building (Boneyard-Train), Cliffside Base (Gora Tunnel), Zoo (ao lado Park), Construction Site (Downtown top 2 floors), Train Wreck (Hospital), Outposts (Dam, Airport Maintenance, Scrapyard) | POIs exclusivos WZM |
| **BO6 S3 — unificação** | BO6 S3 (2025-04-03 espelho WZ2) | 2025-04~ | Android/iOS | Verdansk atualizado para espelhar WZ2 (remove POIs exclusivos WZM) | Alinhamento com Warzone 2.0 |
| **Delist** | — | **2025-05-18** (compras até 2025-05-19) | — | — | Removido da Play Store / App Store; sem novo conteúdo; COD Points ainda gastáveis se já instalado |
| **EOL / Servers offline** | — | **2026-04-17** | — | — | Servidores desligados permanentemente; breve re-listagem fantasma na Play Store em 2026-02 foi bug de backend, sem relaunch |

> **Build numbers específicos (ex.: 1.x.x, 3.x.x) — [UNKNOWN].** Nenhuma fonte pública lista versionCode/versionName completos por temporada. Necessário extrair de APKs preservados (APKMirror, dumps locais). Issue #3.

---

## 2. Engine e plataforma (VERIFIED)

| Atributo | Valor | Evidência |
|---|---|---|
| Engine | **IW 9.0** (variante MGL) — mesmo branch de MWII (2022) / WZ2.0 / MWIII / BO6 | Wiki COD, NamuWiki, fandom IW engine table |
| Base | Rebuild do IW 8.0 (2019) colaborativo Polônia + Califórnia; 5 anos de desenvolvimento | Wikipedia IW engine |
| Plataformas | Android 7.0+ (min), iOS | APKCombo: `com.netease.ma100asia` para DbD; WZM `com.activision.callofduty.warzone` (a confirmar via APK) |
| Tamanho | XAPK ~320 MB + OBB/streaming ~3.5–5.5 GB (S2 ~ 3.5 GB, S5 ~ 5.5 GB adicional) | MobileMatters GINX links |
| Anti-cheat | Ricochet (kernel-level, mesmo do PC) | VG247 leak, IGN entrevista Plummer |
| Networking | Demonware (State Engine + Matchmaking+) | Demonware wiki, CodOnline-Disected, demonware-companion |
| Cross-progression | Sim (Activision account) até 2025-05 | VentureBeat / WindowsCentral launch coverage |

---

## 3. Mapas detalhados (VERIFIED)

| Mapa | Modo | Jogadores | Notas |
|---|---|---|---|
| **Verdansk** | BR | 120 (WZM) vs 150 (PC original) | Layout idêntico ao Verdansk original; Gulag original incluso |
| **Rebirth Island** | Resurgence | 48 no launch (depois 36 dependendo playlist) | Remake Alcatraz; central prison + periferia; menor, ação contínua sem Gulag se squad vivo |
| **Shipment** | MP | 6v6 | |
| **Shoot House** | MP | 6v6 | |
| **Scrapyard** | MP | 6v6 | |

Veículos (Verdansk): ATV (1+2), Cargo Truck (1+1+flatbed), LTV (1+3), Polaris RZR Pro R 4 (1+3), Heli (1+4). Rebirth: Chopper (1+4).

---

## 4. Histórico de pré-registro e métricas

- 2022-09: vazamento indicou >25M pré-registros reportados
- 2024-02-28: VentureBeat reportou **>50M** pré-registros antes do launch global
- 15M Google Play early (Undrads) — mede interesse, não retenção
- Consumer spending $1.4B em 4 dias pós-launch (GamersArena — [HYPOTHESIS], número parece confundir com franchise inteiro; marcar NEEDS_RESEARCH)

---

## 5. Por que isso importa para o servidor offline

1. **Alvo de pesquisa prioritário:** builds do **Limited Release AU (2022-11-30)** e **Global Launch (2024-03-21)** — são as mais preservadas e têm Verdansk vanilla (antes dos POIs exclusivos S5 e da unificação BO6 S3). Menos streaming, menos dependências.
2. **Streaming:** a partir de S5 o mapa divergiu (POIs exclusivos) e exigiu mais shards — servidor precisaria emular mais manifests. Evitar inicialmente.
3. **Remoção das lojas:** APKs já não estão na Play Store oficialmente; **preservação** depende de dumps locais e APKMirror/XAPK. Não incluir APK no repo; documentar como extrair (Issue #3).
4. **Shutdown 2026-04-17:** a partir desta data o jogo não inicia sem servidor — valida a necessidade do projeto; também significa que comportamento “online” não pode mais ser observado ao vivo, só via logs/APK preservados.

---

## 6. Próximos passos — versionamento

- [ ] Extrair `versionCode`, `versionName`, `split` de um XAPK S1/global (apktool + `aapt dump badging`) — `tools/apk-analysis/`
- [ ] Catalogar `split_asset_pack` shards por versão (tamanho, hash, contagem)
- [ ] Identificar `GAME_VERSION` string esperada pelo backend (provavelmente enviada no handshake Demonware)
- [ ] Mapear `build` → `MaxPlayers` (120 vs 48) para validar `MAP`/`GAME_MODE` enums
- [ ] Documentar `engine MGL` commit/branch se encontrável em strings do `.so`

---

## 7. Fontes

**SOURCE:** Call of Duty Fandom — Warzone Mobile dev/announcement, maps, vehicles, limited release dates
**VERSION:** página 2024-07-29
**DATE:** 2026-10-04 verificado
**EVIDENCE:** “Developers: Digital Legends … Demonware (assisted)”, LR dates table, vehicle table
**CONFIDENCE:** VERIFIED
**URL:** https://callofduty.fandom.com/wiki/Call_of_Duty:_Warzone_Mobile

**SOURCE:** VentureBeat — Warzone Mobile debuts March 21 + WindowsCentral
**DATE:** 2024-02-28
**EVIDENCE:** “Verdansk 120p, Rebirth 48p, Shipment/Shoot House/Scrapyard at launch, 50M pre-reg, cross-progression”
**CONFIDENCE:** VERIFIED
**URL:** https://venturebeat.com/games/warzone-mobile-debuts-march-21-with-verdansk-rebirth-island-and-multiplayer-maps/

**SOURCE:** COD Wiki Verdansk map + Fandom IW engine table
**DATE:** 2026-07-29
**EVIDENCE:** IW 9.0 MGL, S5 POIs (Gov Building, Cliffside, Zoo, Train Wreck), BO6 S3 unification 2025-04-03
**CONFIDENCE:** VERIFIED

**SOURCE:** Undrads / Notebookcheck / EGW / TalkEsport — shutdown timeline
**DATE:** 2026-02-16 a 2026-09-07
**EVIDENCE:** “Removed 2025-05-18, purchases disabled 2025-05-19, servers offline 2026-04-17”
**CONFIDENCE:** VERIFIED

**SOURCE:** MobileMatters / GINX — XAPK sizes
**DATE:** 2024-05-28 a 2024-07-19
**EVIDENCE:** XAPK 320 MB, OBB 3.5–5.5 GB
**CONFIDENCE:** PROBABLE (tamanho varia por versão)

**SOURCE:** ResHax — .shard manifest + NamuWiki engine
**DATE:** 2024-04-21
**EVIDENCE:** `split_asset_pack/assets/shard/` + IW 9.0
**CONFIDENCE:** PROBABLE
