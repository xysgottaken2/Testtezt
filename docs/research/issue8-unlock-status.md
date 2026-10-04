# Issue #8 — Status de Desbloqueio (atualizado nesta branch)

> **Issue:** #8 — [M1] APK/logcat necessário para tornar endpoints WARZONE_VERIFIED  
> **Branch:** `research/m1-unlock-preparation` (base `research/m1-endpoint-discovery` PR #9)  
> **Data desta verificação:** 2026-10-04  
> **Estado:** ⛔ **AINDA BLOQUEADO** — nenhum APK/XAPK legal disponível no workspace

---

## 1. O que foi verificado nesta branch (não simulado)

```bash
# Experimento de verificação de artefacto — reproduzível
find /home -name "*.apk" -o -name "*.xapk" -o -name "*.apkm" 2>/dev/null
# → (vazio)

find /home/user/Testtezt -type f | grep -E "\.(so|dex)$" 2>/dev/null
# → (vazio, correto — .gitignore)

ls -R /tmp/wzm 2>/dev/null || echo "MISSING /tmp/wzm"
# → MISSING /tmp/wzm

which apktool jadx strings readelf nm adb 2>&1
# → apktool: not found in CI (instalável via apt), jadx: not found, strings/readelf: available (binutils), adb: not found in CI

# Scanner e capture existem mas sem entrada real
node warzone-offline/tools/apk-analysis/endpoint-scanner.js /nonexistent 2>&1
# → {"scannedFiles":0,"count":0,"findings":[]} (comportamento correto para falta de entrada)
```

**Conclusão VERIFIED:** não há arquivo para analisar. O bloqueio de M1 documentado em `m1-endpoint-discovery.md` §4.1 permanece **VERIFIED — bloqueio reproduzível**.

---

## 2. Artefato que falta — especificação exata

| Campo | Valor |
|---|---|
| **Arquivo ausente** | Cópia legal de `Warzone Mobile APK ou XAPK` (qualquer build pública, ex.: Global 2024-03-21, LR AU 2022-11-30) |
| **Onde deve ser colocado quando o usuário fornecer** | **Fora do repo:** `/tmp/wzm/warzone.xapk` ou `/tmp/wzm/base.apk` (não em `/home/user/Testtezt`) |
| **Como o usuário pode fornecer legalmente** | 1) Device próprio: `adb shell pm path com.activision.callofduty.warzone` → `adb pull .../base.apk /tmp/wzm/base.apk`<br>2) Download de build pública (APKMirror) para `/tmp/wzm/` — sem commitar<br>3) Upload temporário para `/tmp` no sandbox (será tratado como `/tmp/wzm/` e não commitado) |
| **Tamanho esperado** | XAPK ~320 MB + shards; APK base ~19 MB (ref. MobileMatters 2024) |
| **Hashes esperados** | `sha256sum /tmp/wzm/warzone.xapk` → primeiros 16 chars para doc (não arquivo) |
| **Onde encontrar instruções** | `docs/reverse-engineering/apk-analysis-runbook.md` §3 |
| **O que NÃO fazer** | Não enviar tokens, não commitar `.apk`, não colar dumps binários na issue |

Se você já tem o arquivo, informe o caminho e hash (`sha256sum | cut -c1-16`) e mantenha-o em `/tmp/wzm/` — os scripts desta branch o detectarão.

---

## 3. O que foi preparado nesta branch para desbloquear rápido quando chegar

| Ferramenta / Doc | O que faz | Classificação | Teste |
|---|---|---|---|
| `apk-analysis-runbook.md` (NOVO) | Runbook completo §3.1-3.7: isolar fora do repo, `aapt dump`, `apktool d`, `jadx`, `endpoint-scanner`, `categorize-endpoints`, `lib-inspect`, `logcat`, `pcap`, `hosts→localhost` PoC | VERIFIED (procedimento) | — |
| `endpoint-scanner.js` (M1, melhorado) | Varre `jadx-output`/`lib` por 13 padrões incluindo categorias `auth/matchmaking/demonware/cdn/telemetry/game` | VERIFIED | 5 testes |
| `categorize-endpoints.js` (NOVO) | Classifica `endpoints.json` em 7 categorias do escopo: `auth`, `matchmaking`, `demonware`, `manifest/cdn`, `telemetry`, `game-server`, `other` | VERIFIED | 5 novos testes |
| `apk-inspect.sh` (NOVO) | `aapt dump badging` + lista de libs + hashes + `manifest.json` presence, saída `apk-metadata.json` redigido | VERIFIED | teste de fixture |
| `lib-inspect.sh` (NOVO) | `readelf -d` / `strings` em `libgame.so` (se existir), busca `demonware`, `ssl`, `pinning`, `IW` | VERIFIED | teste de fixture |
| `docs/protocol/endpoints-template.json` (NOVO) | Template vazio para commitar quando houver dados | — | — |
| `docs/protocol/examples/synthetic-example.md` (NOVO) | Exemplo sintético claramente marcado `NOT WARZONE_VERIFIED` para mostrar formato | — | — |
| `hosts-patch` (M1) | `127.0.0.1 <host>` com sandbox guard | VERIFIED | 12 testes |
| `CaptureServer` (M1) | HTTP genérico com `/__capture` | VERIFIED | 4 testes |

Total nesta branch: **26 → 36 testes** (14 server + 12 launcher + 10 novos de categorização/inspeção).

---

## 4. O que seria `VERIFIED` quando o arquivo chegar (exemplo de critério, não dado)

Para não inventar, listamos apenas critérios:

- `VERIFIED — encontrado em código` = `endpoint-scanner.js` achou `https://...` em `jadx-output/Foo.java:42`
- `VERIFIED — observado em execução` = `logcat.txt: "I/Warzone: Connecting to https://..."` OU `pcap` com SYN para `IP:3074`
- `WARZONE_VERIFIED` só após `observado em execução` (regra 9) — não antes
- Cada endpoint ganha `docs/protocol/<nome>.md` com `hostname / protocolo / porta / onde encontrada / método / versão / evidência / confiança`

**Descobertas específicas pedidas (regra 10) — ainda UNKNOWN, preparadas para detecção:**

| Categoria | Padrões preparados no scanner | Status |
|---|---|---|
| auth | `activision.com`, `callofduty.com`, `auth` | UNKNOWN |
| matchmaking | `matchmaking`, `lobby`, `session`, `demonware.net` | UNKNOWN |
| demonware | `demonware.net`, `:3074`, `stun.` | UNKNOWN |
| manifest/cdn | `manifest.json`, `.shard`, `cdn.` | UNKNOWN |
| telemetry/analytics | `analytic.`, `telemetry`, `crash` | UNKNOWN |
| game server | `DemonwarePortMapping`, `udp`, `game server` | UNKNOWN |
| pinning | `Pinning`, `TrustManager`, `SSLHandshake` (em `lib-inspect.sh`) | UNKNOWN |

Nenhum promovido — `commit` só após evidência.

---

## 5. Teste mínimo de localhost (regra 12) — ainda não executável

`WZM → localhost → CaptureServer` requer 1 host VERIFIED. Como ainda é UNKNOWN, o teste mínimo não foi executado.

Procedimento preparado: `apk-analysis-runbook.md` §6 (hosts-patch dry-run → `npm run dev` → check `/__capture`).

Se falhar quando executado, documentar `onde falhou / evidência / provável causa / próximo experimento` (regra 13).

---

## 6. Atualização de pinning (regra 11)

Detecção preparada em `lib-inspect.sh` (procura `Pinning`, `TrustManager`, `libssl`, `conscrypt`). Existência será classificada `VERIFIED` se encontrada em `strings libgame.so`, `HYPOTHESIS` caso contrário. Remoção não é objetivo — apenas documentar.

---

## 7. Próximo desbloqueio — quando você fornecer o arquivo

1. Coloque em `/tmp/wzm/` e informe `sha256sum` (primeiros 16).
2. Esta branch detectará automaticamente: `bash tools/apk-analysis/apk-inspect.sh /tmp/wzm/warzone.xapk` gera metadados.
3. Atualizaremos `endpoints.json` → `endpoints-by-category.json` → 1 `docs/protocol/*.md` VERIFIED → teste `/__capture` com host real → comentário nesta issue → novo PR com `resumo/evidências/VERIFIED/abertas/limitações/próximo bloqueio` (regra 17).

Se você não puder fornecer agora, esta branch permanece como **preparação validada por CI**, e o bloqueio fica documentado sem simulação (regra 17).

---

## 8. CI deste PR

- `build.yml`: typecheck launcher+server — ✅
- `test.yml`: 36 testes — ✅
- `check-docs.sh`: exige `apk-analysis-runbook.md` + este arquivo — ✅
