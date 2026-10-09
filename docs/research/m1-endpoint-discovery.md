# M1 — Descoberta de Endpoints e Mecanismo de Redirecionamento para localhost

> **Aviso vigente:** plano histórico supersedido por M3.2/M4.1/M4.2/M4.3. Menções a proxy TLS, Frida, `VerifyPeer`,
> CA, root, hosts override ou patch/repack abaixo são apenas contexto histórico; não executar nem alterar
> TLS/trust/pinning. M4.3 encontrou no binário um candidato `cdni_httpServer`, mas não comprovou acesso do usuário
> nem efeito local; ver [`m4.3-libgame-static-analysis.md`](m4.3-libgame-static-analysis.md). O caminho real do WZM
> deve ser atribuído primeiro.
>
> **Milestone:** M1 — Client communication<br>
> **Branch:** `research/m1-endpoint-discovery`<br>
> **Data:** 2026-10-04<br>
> **Regra deste milestone:** Não implementar nada baseado em hipótese não verificada. `DBD_REFERENCE` usado somente como metodologia.

---

## 1. Objetivo do M1

Responder com evidência **VERIFIED**, não hipótese:

1. **Como o cliente Warzone Mobile resolve seus endpoints?** (hosts, DNS, HTTPS/CDN, TCP, UDP, portas, TLS)
2. **Qual mecanismo é necessário para redirecioná-lo para `localhost`?** (hosts file, DNS local, proxy, Frida, VerifyPeer)
3. **Isso é possível hoje sem o APK?** Se não, documentar o bloqueio com `UNKNOWN`/`NEEDS_RESEARCH`.

> Definição de pronto M1: captura de pelo menos um request real do cliente em `localhost` OU documentação verificada de por que ainda não é possível, com experimento reproduzível.

---

## 2. Metodologia de referência — DbD Mobile Offline (`DBD_REFERENCE`)

Esta seção descreve **somente DbD** para extrair metodologia. Não é evidência para WZM.

### 2.1. Como DbD resolve endpoints (VERIFIED em DbD)

- **Build dev 3.0.0 (`ettfemnio/dbd-server`):** cliente lê 3 hosts hardcoded:
  ```
  latest.dev.dbd.bhvronline.com
  cdn.dev.dbd.bhvronline.com
  analytic.live.dbd.bhvronline.com
  ```
  Evidência: `127.0.0.1 latest.dev.dbd.bhvronline.com` no `hosts` do README do `dbd-server` + `[/Script/Engine.NetworkSettings] n.VerifyPeer=false` em `Engine.ini`.
- **PrivateServer (UE live):** não usa hosts custom — o “servidor” é o próprio UE listen server dentro da sandbox `DbDSandbox`. O launcher só orquestra.

### 2.2. Mecanismo de redirecionamento em DbD (VERIFIED)

| Mecanismo | Evidência DbD | Limitação |
|---|---|---|
| `hosts` override (`127.0.0.1 <domínio>`) | `dbd-server` README | Requer escrita em `C:\Windows\System32\drivers\etc\hosts` (Windows) ou `/etc/hosts` (Linux); no Android requer root ou `adb` via `hosts` remount |
| `VerifyPeer=false` em `Engine.ini` | `dbd-server` README | Desabilita verificação TLS para permitir cert self-signed no localhost |
| Sandbox guard (`DbDSandbox` ≠ install oficial, checa Steam Offline Mode) | `CarlosMonarrez/DbDPrivateServer` `SandboxManager.ps1` | Evita ban e escrita acidental no install oficial |

**Lição metodológica para WZM:** testar `hosts` + `VerifyPeer`/Frida como primeiro experimento é correto porque é o mecanismo mais simples, reversível e observado no DbD. Mas **não assumir** que WZM usa os mesmos hosts ou que `VerifyPeer` existe no IW 9.0.

### 2.3. O que DbD **não** ensina (e não deve ser copiado)

- Endpoints `bhvronline.com` não são de WZM.
- DbD não precisa emular Demonware TCP 3074/UDP — WZM provavelmente precisa (porém ainda `HYPOTHESIS` até APK).
- DbD PrivateServer não tem servidor HTTP autoritativo separado — WZM precisará.

---

## 3. Estado atual para Warzone Mobile — classificação rigorosa

| Afirmação | Classificação | Justificativa |
|---|---|---|
| WZM usa IW 9.0 MGL | `[VERIFIED]` | Fandom/Wikipedia/NamuWiki — engine listada oficialmente |
| WZM foi delistado 2025-05-18 e offline 2026-04-17 | `[VERIFIED]` | 4 fontes (Undrads/Notebookcheck/EGW/TalkEsport) |
| WZM tem `split_asset_pack/assets/shard/` + `manifest.json` + `IWffn100` | `[PROBABLE]` | ResHax 2024-04-21 + tamanhos XAPK — falta APK local para confirmar por build |
| WZM usa Demonware (State Engine + Matchmaking+) | `[PROBABLE]` → `[HYPOTHESIS]` para detalhes | Crédito Fandom “Demonware (assisted)” — sem strings de APK ainda |
| WZM AUTH/LSG em TCP 3074 + 8-byte key | `[HYPOTHESIS]` | Analogia COD Online (`formal-zone-(auth/lsg).codol.qq.com:3074`) — não observado em WZM |
| Lista exata de domínios WZM (cdn, auth, analytic, demonware.net) | `[UNKNOWN]` | Sem APK/logcat/pcap — bloqueado |
| Portas reais WZM | `[UNKNOWN]` | Sem APK/logcat/pcap |
| WZM aceita `hosts` override para `localhost` | `[UNKNOWN]` | Nunca testado sem APK |
| WZM faz cert pinning / exige Frida | `[UNKNOWN]` | Sem análise de `libgame.so` |
| Tick rate WZM | `[UNKNOWN]` | Sem pcap |

> **Conclusão metodológica:** Como a lista exata de endpoints é `[UNKNOWN]`, qualquer implementação que fixe `cdn.warzone.com` ou `auth.activision.com:3074` como se fosse VERIFIED violaria a regra. O correto é implementar **mecanismo genérico** e **ferramenta de descoberta**, e documentar o bloqueio.

---

## 4. Experimentos realizados neste milestone

### 4.1. Experimento A — Inventário de APK disponível no repo

**Goal:** Verificar se há APK/XAPK no workspace para extrair endpoints.

**Method:**
```bash
find /home/user/Testtezt -name "*.apk" -o -name "*.xapk" -o -name "*.apkm" 2>/dev/null
ls -R /tmp/wzm 2>/dev/null || echo "no /tmp/wzm"
strings $(find . -name "*.so" 2>/dev/null | head) | grep -i demonware 2>&1
```

**Observed:** Nenhum `.apk`/`.xapk` no repo (correto — `.gitignore` bloqueia). Nenhum `.so` nativo. Nenhum `/tmp/wzm` (sem dump local).

**Result:** `BLOCKED` — sem artefacto preservado, não é possível extrair endpoints.

**Confidence:** `[VERIFIED]` bloqueio reproduzível.

### 4.2. Experimento B — Validação do mecanismo genérico de redirecionamento

**Goal:** Provar que o mecanismo `hosts` + HTTP capture funciona genericamente, sem depender de endpoint WZM específico.

**Method:**
- Implementar `launcher/hosts-patch` como módulo puro (parse/format/add/remove) com testes unitários — metodologia DbD, não endpoint WZM.
- Implementar `server` HTTP genérico que loga **qualquer** `Host`/`URL` que o cliente tentar, respondendo `200` genérico, e expondo `/__capture` para inspeção.
- Teste de integração: cliente sintético resolve `fake.wzm.local` via `hosts` (in-memory) → request chega ao capture server.

**Observed:** Módulo `hosts-patch` com 12 testes passando; capture server registra todos os hosts recebidos sem assumir endpoint; ver `warzone-offline/launcher/src/hosts-patch/` e `warzone-offline/server/src/capture/`.

**Result:** `VERIFIED` — mecanismo genérico funciona. Falta **descobrir** qual host o cliente WZM realmente tentará (Experimento A bloqueado).

### 4.3. Experimento C — Scanner de endpoints sem APK

**Goal:** Provar que a ferramenta de descoberta está pronta para quando APK estiver disponível.

**Method:**
- Criar `tools/apk-analysis/endpoint-scanner.js` que recebe pasta `jadx-output` ou `lib/` e aplica regex para `https://`, `wss://`, `demonware`, `activision`, `cdn`, `analytic`, `3074`, etc., emitindo JSON com `SOURCE / EVIDENCE / CONFIDENCE`.
- Testar com fixture sintético (pasta fake com 3 arquivos contendo URLs conhecidas + 1 `.so` fake).

**Observed:** Scanner emite JSON estruturado, 5 testes passando, sem falso positivo quando pasta vazia.

**Result:** `VERIFIED` — tooling pronto, aguardando APK real.

---

## 5. Resposta às duas perguntas (estado honesto)

### Q1: Como o cliente WZM resolve seus endpoints?

**Resposta curta:** `[UNKNOWN]` — não verificável sem APK/logcat/pcap.

**O que sabemos com confiança:**
- `[VERIFIED]` IW 9.0 MGL.
- `[PROBABLE]` Streaming via `manifest.json` + shards (ResHax).
- `[PROBABLE]` Demonware como backend (crédito Fandom), porém sem lista de hosts/portas.
- Todo o resto (`cdn.*`, `auth.*:3074`, `analytic.*`, `demonware.net`) é `[HYPOTHESIS]` por analogia COD Online e **não implementado** neste milestone.

**O que precisa para tornar VERIFIED:**
1. Obter XAPK preservado legalmente (ex.: dump do próprio device via `adb pull`, ou download APKMirror de build pública, sem distribuir).
2. Rodar:
   ```bash
   apktool d warzone.xapk -o /tmp/wzm/apktool-output
   jadx -d /tmp/wzm/jadx-output warzone.xapk
   node tools/apk-analysis/endpoint-scanner.js /tmp/wzm/jadx-output /tmp/wzm/apktool-output/lib
   adb logcat | grep -i warzone  # boot sem login
   mitmproxy + Frida (se cert pinning)
   ```
3. Documentar cada host com `docs/protocol/<nome>.md` preenchendo `Name / Direction / Transport / Endpoint / Encoding / EVIDENCE / CONFIDENCE`.

Sem executar (1), qualquer lista seria invenção — violando `NUNCA INVENTE`.

### Q2: Qual mecanismo é necessário para redirecionar para localhost?

**Resposta metodológica (VERIFIED genericamente, UNKNOWN para WZM específico):**

Para **qualquer** domínio descoberto, a hierarquia de tentativas é:

```
1. hosts file override (mais simples, reversível, DbD VERIFIED)
   - Windows: C:\Windows\System32\drivers\etc\hosts -> 127.0.0.1 <domínio>
   - Linux: /etc/hosts
   - Android: requer root (remount) OU adb via `adb shell "echo 127.0.0.1 cdn.example >> /etc/hosts"` em device rootado
   - Ver `warzone-offline/launcher/src/hosts-patch/` — implementado e testado

2. Se cliente valida cert pinning:
   - DbD usou `VerifyPeer=false` (Engine.ini)
   - IW 9.0 não tem Engine.ini — provável necessidade de Frida hook para desabilitar pinning (ainda UNKNOWN, ver `tools/frida/` stub)
   - Testar: subir cert self-signed em localhost, ver se cliente rejeita, então aplicar Frida

3. Se cliente ignora hosts e usa DNS hardcoded / DoH:
   - Fallback: DNS local (dnsmasq) ou hotspot PC que responde como DNS para `A <domínio> -> 192.168.x.x`
   - Mais complexo, só após (1) falhar

4. Para UDP/TCP 3074 (se confirmado):
   - hosts funciona também para TCP/UDP (resolução DNS igual)
   - porta precisa ser descoberta e então servidor local em `127.0.0.1:<porta>` com protocol framing correto (ainda UNKNOWN; acesso LAN fora do escopo)
```

**Mecanismo implementado neste PR:** `(1)` + capture HTTP genérico. `(2)` documentado como próximo experimento com Frida, **não implementado** porque sem APK não há como saber se há pinning.

---

## 6. O que foi implementado neste milestone (sem hipótese)

| Componente | O que faz | Classificação | Teste |
|---|---|---|---|
| `launcher/hosts-patch` | Parse/format/add/remove/validate de `hosts` file, com sandbox guard (não escrever no hosts real sem `--apply`) | `VERIFIED` (metodologia DbD) | 12 testes |
| `server/capture` | HTTP server genérico que loga `method/host/url/headers` de **qualquer** request e responde `200 {note: M1 capture}` + endpoint `GET /__capture` e `POST /__reset` | `VERIFIED` (infra genérica) | 4 testes + integração |
| `tools/apk-analysis/endpoint-scanner.js` | Varre `jadx-output`/`lib` por regex de URLs e emite JSON com confiança | `VERIFIED` (tooling) | 5 testes |
| `docs/reverse-engineering/frida-bypass.md` | Documenta como testar cert pinning **quando** APK disponível, sem assumir que existe | `UNKNOWN` → procedimento | — |
| Esta doc | Registra bloqueio honesto | `VERIFIED` bloqueio | — |

**O que NÃO foi implementado (propositalmente):**
- Nenhum `POST /auth` com formato WZM fixo.
- Nenhum `manifest.json` com estrutura assumida.
- Nenhum `TCP 3074` server que finja ser Demonware.
- Nenhum `analytic.*` hardcoded como se fosse VERIFIED.

Tudo isso permanece `UNKNOWN` até experimento com APK.

---

## 7. Bloqueio documentado e issue aberta

**Bloqueio M1:** sem APK/XAPK preservado, não é possível preencher `Name/Endpoint/Encoding/Fields` de nenhum `docs/protocol/*.md` como `WARZONE_VERIFIED`.

**Issue aberta:** `#8 — [M1] APK/logcat necessário para tornar endpoints WARZONE_VERIFIED` — contém `Goal/Evidence/Unknowns/Experiment/Expected/Actual` e checklist de artefactos (`aapt dump`, `jadx`, `strings`, `logcat`, `pcap`).

**Critério para desbloquear M2:** anexar (sem commitar binário) saída redigida de `endpoint-scanner.js` + `logcat` de boot + `capture` de um request real em `localhost` na issue, e então promover um endpoint para `VERIFIED`.

---

## 8. Fontes deste documento

- `docs/research/dead-by-daylight-mobile.md` — DbD VERIFIED
- `docs/research/warzone-mobile-versions.md` — WZM versões VERIFIED
- `docs/research/warzone-mobile-networking.md` — WZM networking PROBABLE/HYPOTHESIS
- `warzone-offline/launcher/src/hosts-patch/__tests__/hostsPatch.test.ts` — 12 testes
- `warzone-offline/server/tests/capture.test.ts` — 4 testes
- `warzone-offline/tools/apk-analysis/__tests__/endpointScanner.test.ts` — 5 testes

---

## 9. Próximos passos

1. Quando APK disponível: rodar `endpoint-scanner.js` + `logcat` + `capture` e preencher `docs/protocol/*.md`.
2. Testar `hosts-patch --apply --dry-run` contra um domínio sintético, depois contra domínio real descoberto.
3. Se TLS falhar: criar `tools/frida/bypass.js` e validar com `mitmproxy` (issue #9).
4. Só então M2: mapear `config.GAME_VERSION/MAP/PORT` reais.
