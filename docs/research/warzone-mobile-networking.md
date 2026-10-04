# Warzone Mobile — Networking & Backend

> **Data:** 2026-10-04 | **Status:** M0 Research
> **Classificação padrão:** `HYPOTHESIS` salvo quando marcado `WARZONE_VERIFIED` ou `DBD_REFERENCE`

---

## 1. Resumo executivo

| Camada | Hipótese atual | Confiança | Próximo experimento |
|---|---|---|---|
| Engine | IW 9.0 MGL, compartilhado com MWII/WZ2 | VERIFIED | Extrair strings `MGL`, `IW9` de `libgame.so` |
| Backend autoritativo | **Demonware** (State Engine + Matchmaking+) | PROBABLE | Confirmar hosts `*.demonware.net` / `*.codol.qq.com`-like em strings do APK |
| Transporte gameplay | UDP (State Engine) + TCP 3074 (AUTH/LSG) + HTTPS | HYPOTHESIS (por analogia COD Online) | Wireshark em device com APK preservado + Frida hook |
| Auth | Activision account + token via HTTPS → TCP AUTH | HYPOTHESIS | Interceptar tráfego de login |
| Matchmaking | Demonware Matchmaking+ → lobby → session | HYPOTHESIS | Documentar endpoints/hosts |
| CDN / Assets | `split_asset_pack/assets/shard/` + manifest.json (KAPIs/xpak) | PROBABLE | Parsear manifest.json real |
| Anti-cheat | Ricochet (kernel/driver no PC; mobile equivalente) | VERIFIED (PC) / HYPOTHESIS (mobile) | Verificar `libRicochet.so` / strings |
| Telemetria | Domínio `analytic.*` bloqueável | DBD_REFERENCE → HYPOTHESIS para WZM | Procurar `analytic` em strings |

---

## 2. Demonware — o que é e por que importa

**VERIFIED (Demonware como empresa/produto):**

- Subsidiária da Activision desde 2007 (Dublin + Vancouver/LA/Shanghai), 177–500 funcionários.
- Produtos: **State Engine** (C++ framework de sincronização de estado, elimina reescrever netcode) e **Matchmaking+** (matchmaking, perfil, estatísticas). Implementado em Erlang + Python, escala para >5M CCU, milhares de matches/segundo.
- Usado em todos os CoD desde 2005 (primeiro `COD2: Big Red One`). CodOnline-Disected (2021) confirma Demonware via hostnames `formal-zone-(auth/lsg).codol.qq.com` + stun servers.
- Ferramenta comunitária `hosseinpourziyaie/demonware-companion` oferece `buffer_deserializer` e `data_interceptor` (DLL que hooka funções DW para dumpar payloads de lobby) — prova de que o protocolo DW é hookável via DLL/Frida.

**HYPOTHESIS para WZM:**

WZM sendo IW 9.0 MGL e listado com `Demonware (assisted)` nos créditos (Fandom), é **altamente provável** que todo o multiplayer passe por Demonware, mas **não verificado ainda** sem APK. Não assumir endpoints idênticos ao COD Online ou ao WZ PC.

**Implicação para servidor offline:**

- O mínimo de servidor não é só HTTP — haverá pelo menos um **handshake TCP/UDP com Demonware** que precisa ser emulado ou bypassado.
- Diferente do DbD (que só precisou de 2 hosts HTTP + block de analytics), WZM provavelmente exige **emulação parcial do State Engine / LSG** para o cliente criar sessão.

---

## 3. Arquitetura de rede hipotética (a validar)

```
[Client APK] —HTTPS→ [Activision Auth] —token→ [Demonware AUTH :3074 TCP]
     │                          │
     │—HTTPS→ [CDN / manifest.json / .shard]   (asset streaming)
     │—TCP 3074→ [Demonware LSG]  (lobby / session)
     │—UDP (DemonwarePortMapping, ex: 25656) → [Dedicated / listen server]
     │—HTTPS→ [Telemetry analytic.*] (bloqueável)
```

**Portas observadas em outros títulos:**

- `3074/TCP` — AUTH/LSG (COD Online)
- `3074/UDP`, `27000+` — gameplay
- `DemonwarePortMapping` UPnP em roteadores (ex.: `UDP:25656:192.168.10.96:25656`) — prova de que clientes abrem porta UDP via UPnP para Demonware.

---

## 4. Endpoints / Domínios — o que procurar no APK

**Lista de busca (strings, Frida, logcat, Wireshark):**

```
demonware.net
bhvronline.com          # (DbD pattern, pode não existir no WZM)
activision.com
callofduty.com
codol.qq.com            # pattern AUTH/LSG
cdn.* / manifest
analytic.*
stun.*
auth / lsg / matchmaking / lobby / session / profile / inventory / store
```

**Técnica:**

```bash
# após extrair APK (ver docs/reverse-engineering/methodology.md)
apktool d warzone.apk -o apktool-output
jadx -d jadx-output warzone.apk
grep -r "demonware\|activision\|cdn\|analytic\|stun\|wss://\|https://" jadx-output --include="*.java" | head
strings lib/arm64-v8a/libgame.so | grep -i "demonware\|https\|wss\|3074"
adb logcat | grep -i "warzone\|demonware\|http\|websocket"
```

Cada domínio encontrado deve ser documentado em `docs/protocol/` com template.

---

## 5. Protocolos e encoding (UNKNOWN → NEEDS_RESEARCH)

| Aspecto | Estado | Notas |
|---|---|---|
| HTTP vs HTTPS | Esperado HTTPS (TLS) para auth/CDN | Verificar cert pinning — pode exigir Frida bypass |
| WebSocket | [UNKNOWN] — alguns CoD usam WSS para lobby | Procurar `wss://` em strings |
| UDP vs TCP gameplay | State Engine tipicamente UDP; AUTH/LSG TCP 3074 | Confirmar via Wireshark |
| Serialização | [UNKNOWN] — provavelmente binário custom + protobuf-like (ver demonware-companion deserializer) | Usar `buffer_deserializer` como referência |
| Tick rate | WZ PC ~21 Hz (48 ms) observado via Wireshark (r/CODWarzone) | Hipótese WZM similar; medir |
| Criptografia | LSG/AUTH packets com 8 bytes mutáveis + key embutida (CodOnline) | Aplicável a WZM? |
| Compressão | [UNKNOWN] | Verificar headers |

---

## 6. Autenticação — fluxo hipotético

```
1. Cliente inicia → lê cache local / keystore
2. Se tem Activision token, tenta refresh via HTTPS
3. Senão, login (Activision / Guest) → recebe token
4. Conecta em AUTH (TCP 3074) com token + key embutida (8 bytes handshake)
5. AUTH responde com perfil / inventário / entitlements
6. Cliente pede matchmaking → LSG cria lobby
```

**Perfil local (M3):** para offline, mockar passos 2–5 com JSON local:

```json
{
  "id": "local-player-001",
  "name": "Player",
  "level": 1,
  "activisionId": "local:player001",
  "entitlements": []
}
```

Endpoint de auth precisará responder com estrutura compatível — descobrir via APK.

---

## 7. Matchmaking / Lobby / Session — fluxo hipotético

```
matchmaking.create → { game_mode, map, region }
  → lsg.createLobby → { lobbyId, host }
  → lsg.joinLobby → { players: [local] + bots }
  → lsg.startSession → { sessionId, serverAddr, port }
  → client connects UDP → spawn
```

Cada transição precisa de um teste em `tests/protocol/` (ver docs/architecture/overview.md).

---

## 8. CDN / Asset streaming vs Server-dependent

Ver `warzone-mobile-streaming.md` — separação:

- **Client-local:** o que já está no OBB/shard após instalação completa (mapa base, armas base)
- **Downloaded/cache:** shards baixados sob demanda (texturas HD, POIs S5)
- **CDN-dependent:** manifest.json que lista shards/hashes
- **Server-dependent vs emulável:** manifest pode ser servido de localhost se o cliente aceitar `hosts` override

---

## 9. Telemetria e bloqueios

Em DbD, `0.0.0.0 analytic.live.dbd.bhvronline.com` silencia telemetria sem quebrar o jogo. Para WZM, procurar domínio `analytic` e testar se bloquear quebra inicialização. Se não quebrar, pode ser mockado com `200 OK` vazio.

---

## 10. Ferramentas de interceptação

| Ferramenta | Uso |
|---|---|
| **Wireshark / tcpdump** | Captura UDP/TCP na LAN (PC host como hotspot) |
| **mitmproxy / Charles** | Intercepta HTTPS (com cert pinning bypass) |
| **Frida** | Hooka funções Demonware / TLS pinning / `buffer_deserializer` |
| **adb logcat** | Logs de inicialização, endpoints, erros de conexão |
| **apktool / JADX / Ghidra** | Extração de strings, endpoints, libs nativas |

> Ver `docs/reverse-engineering/methodology.md` para passo-a-passo.

---

## 11. Evidências e confiança

**VERIFIED:**

- Demonware existe e é backend de CoD desde 2005; State Engine/Matchmaking+ em Erlang/Python (Wikipedia, demonware.net).
- CodOnline usa AUTH/LSG em `formal-zone-(auth/lsg).codol.qq.com:3074` + HTTPS handshake (zzVertigo/CodOnline-Disected, 2021-02-04).
- `DemonwarePortMapping` UPnP observado em roteadores para Destiny2/CoD (r/DestinyTechSupport).
- Ricochet existe e foi vazado em 2021 (VG247).

**PROBABLE:**

- WZM usa Demonware (crédito Fandom “Demonware (assisted)” + IW 9.0).

**HYPOTHESIS:**

- Portas 3074, fluxo AUTH→LSG→UDP, payloads binários com key embutida se aplicam ao WZM.

**UNKNOWN:**

- Lista exata de domínios WZM, formato de mensagens, serialização, tick rate, cert pinning.

---

## 12. Próximos experimentos (NEEDS_RESEARCH)

1. **APK strings sweep** — extrair lista completa de domínios/URLs/ports de um XAPK preservado.
2. **Logcat boot** — instalar APK em emulador Waydroid/AVD com `adb logcat` e capturar hosts tentados ao abrir o jogo (sem login).
3. **Frida TLS bypass** — testar se `VerifyPeer=false` ou Frida script desabilita pinning.
4. **Hosts override PoC** — redirecionar um domínio CDN para `127.0.0.1` com servidor HTTP mock que loga requests.
5. **UDP capture** — hotspot PC → celular, Wireshark na interface do hotspot.

Cada experimento deve virar Issue com `Goal / Evidence / Unknowns / Experiment / Expected / Actual`.

---

## 13. Fontes

**Demonware State Engine / Matchmaking+, Erlang/Python, >5M CCU:** Wikipedia Demonware, Grokipedia Demonware
**CodOnline AUTH/LSG :3074 + 8-byte key:** zzVertigo/CodOnline-Disected (2021-01-30/02-04)
**demonware-companion buffer_deserializer/data_interceptor:** hosseinpourziyaie/demonware-companion
**DemonwarePortMapping UPnP:** r/DestinyTechSupport (2024-06-01)
**WZM IW 9.0 + Demonware assisted:** COD Fandom Warzone Mobile, NamuWiki IW engine
**WZ PC tick ~21 Hz / 48 ms:** r/CODWarzone Wireshark post (2020-04-23)
**Ricochet leak:** VG247 (2021-10-15)
