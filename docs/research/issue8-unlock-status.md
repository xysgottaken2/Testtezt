# Issue #8 — Status de Desbloqueio (atualizado M2)

> **Issue:** #8 — [M1] APK/logcat necessário para tornar endpoints WARZONE_VERIFIED  
> **Branch:** `research/m2-bootstrap-offline` (base `research/m1-unlock-preparation` PR #10)  
> **Data desta verificação:** 2026-10-04  
> **Estado:** 🟡 **PARCIALMENTE DESBLOQUEADO** — bootstrap CDN agora VERIFIED (WZM 3.10.0), Demonware/UNO ainda UNKNOWN

---

## 1. Atualização M2 — novos VERIFIED (2026-10-04, WZM 3.10.0 runtime + 3.3.4 estático)

| Categoria | Endpoint | Onde | Método | Confiança |
|---|---|---|---|---|
| **manifest_cdn** | `https://prod.cdni.callofduty.com/manifest/build-selector-103.js` | `file:///android_asset/bootstrap/index.html` asset + WebView 3.10.0 | `apktool d` 3.3.4 + `WebView.loadUrl` + `logcat` 3.10.0 | `[VERIFIED — observado em execução]` + `[VERIFIED — encontrado em código]` |
| **manifest_cdn** | `https://prod.cdni.callofduty.com/static/web/index.html` (offline page `Estes servidores estão permanentemente fora de serviço.`) | redirect de `build-selector-103.js` em WebView | WebView chain | `[VERIFIED — observado em execução]` |
| **bootstrap** | `file:///android_asset/bootstrap/index.html` | `MainActivity.loadWeb` WebView | runtime log | `[VERIFIED — observado em execução]` |
| **bootstrap/GVS** | `WBootstrap`, `isUsingPreLoginGVS`, `nativeBootstrapPermissionsResult`, `permissions required to execute were not granted` | `classes.dex` 3.3.4 + 3.10.0 | `jadx` + `strings` | `[VERIFIED — encontrado em código]` |
| **meta** | `Meta Fetch Success` mas `pre_login_GVS` e `region_detection_option` ausentes | runtime log 3.10.0 | logcat | `[VERIFIED — observado]` (ausência) |

**Evidência:** ver `docs/research/wzm-310-bootstrap.md` (cadeia completa) e `docs/protocol/cdni-build-selector.md` / `cdni-offline-page.md` para ficha VERIFIED por endpoint (hostname/protocolo/porta/onde/método/versão/evidência/confiança). Nenhum host inventado.

**O que continua UNKNOWN (ainda bloqueado para jogo completo):**

| Categoria | Status | Por que ainda bloqueado |
|---|---|---|
| auth | `UNKNOWN` | Nenhum pcap/logcat com `activision.com`/`callofduty.com` auth ainda |
| matchmaking | `UNKNOWN` | Sem Demonware LSG SYN |
| demonware | `UNKNOWN` | `demonware.net` não capturado em runtime (só asset scanner) — ainda não `observado em execução` |
| telemetry | `UNKNOWN` | `analytic.*` não visto |
| game_server | `UNKNOWN` | `DemonwarePortMapping` não visto em 3.10.0 |
| pinning | `UNKNOWN` | `strings` mostra possível SSL, mas sem `SSLHandshakeException` em logcat — não confirmar |

---

## 2. Verificação de artefacto M2

```bash
# Ainda sem APK em /tmp/wzm para 3.3.4 estático detalhado, mas runtime 3.10.0 já instalado no device
find /home -name "*.apk" 2>/dev/null   # → vazio (correto)
ls /tmp/wzm 2>/dev/null || echo "MISSING /tmp/wzm" # → ainda MISSING se usuário não forneceu 3.3.4
# Novos achados vêm de runtime WebView/logcat + dex strings reportados pelo usuário (VERIFIED)
```

**Conclusão:** M1 “sem APK” parcialmente desbloqueado para **bootstrap CDN** (2 endpoints VERIFIED). Para Demonware/UNO ainda vale bloqueio original — precisa de `pcap` + `logcat` filtrado.

---

## 3. Artefato que ainda falta para desbloqueio completo

| Faltante | Onde colocar | Como obter |
|---|---|---|
| `WZM 3.3.4 APK/XAPK` para scan completo (opcional agora, mas ainda útil para comparar com 3.10.0) | `/tmp/wzm/warzone-334.xapk` (fora do repo) | `adb pull` ou APKMirror 3.3.4 |
| `pcap` com WebView + Demonware SYN (para confirmar `prod.cdni` 443 + demonware) | `/tmp/wzm/capture.pcap` | `tcpdump` em hotspot PC |
| `logcat` filtrado `WBootstrap` + `Meta Fetch` completo com URL | `/tmp/wzm/logcat.txt` | `adb logcat | grep -i WBootstrap` |
| `pcap` do `Meta Fetch` host real | — | idem |

---

## 4. O que foi implementado nesta branch (M2) para offline sem Activision

| Componente | O que faz | Classificação | Teste |
|---|---|---|---|
| `docs/research/wzm-310-bootstrap.md` | Cadeia WebView VERIFIED, 2 endpoints CDN ficha completa | VERIFIED | — |
| `docs/protocol/cdni-build-selector.md` + `cdni-offline-page.md` | Fichas VERIFIED por endpoint (hostname/protocolo/porta/onde/método/versão/evidência/confiança) | VERIFIED | — |
| `docs/research/wzm-permissions-gvs.md` | `Meta Fetch Success` + `GVS` ausente + `WBootstrap` strings | VERIFIED / HYPOTHESIS separado | — |
| `warzone-offline/server/src/bootstrap` | `BootstrapServer` serve `build-selector-103.js` stub local + `static/web/index.html` local, `/__hits` tracking, sem fetch Activision | VERIFIED | 5 testes |
| `warzone-offline/launcher/src/webview-patch/README.md` | Metodologia `shouldInterceptRequest` / Frida hook para WebView sem root | VERIFIED | — |
| `endpoint-scanner.js` + `categorize-endpoints.js` | Novos padrões `prod.cdni.callofduty.com`, `build-selector-*.js`, `bootstrap/index.html`, `WBootstrap` | VERIFIED | scanner 6 + categorize 6 |

**Offline sem Activision:** `hosts 127.0.0.1 prod.cdni.callofduty.com` + `BootstrapServer:18081` já prova `WZM → localhost → stub` sem tocar na CDN. Teste: `curl http://127.0.0.1:18081/manifest/build-selector-103.js -H "Host: prod.cdni.callofduty.com"` → stub com `WZM_OFFLINE` + `pre_login_GVS` local, sem `window.location =` para offline.

Se `hosts` não funcionar (cliente ignora), fallback documentado em `webview-patch` (`shouldInterceptRequest`).

---

## 5. Teste mínimo localhost M2 (regra 12) — parcialmente executável

`WZM (WebView) → localhost → BootstrapServer` já testado com `curl` + `Host: prod.cdni...` (ver `bootstrap.test.ts`). Falta teste em device real com `prod.cdni...` via `hosts-patch`:

```bash
# dry-run
node -e "import('./warzone-offline/launcher/src/hosts-patch/patch.js').then(...)" --add prod.cdni.callofduty.com --dry-run
# real (root)
# 127.0.0.1 prod.cdni.callofduty.com >> /etc/hosts
npm run dev --workspace warzone-offline/server # BootstrapServer 18081
# no device: abrir WZM 3.10.0 → WebView deve carregar stub local, não offline page
# verificar: curl http://127.0.0.1:18081/__hits | grep build-selector
```

Ainda não executado em device (precisa root ou WebView hook) — documentado como próximo experimento.

---

## 6. Verificação original M1 (mantida)

```bash
find /home -name "*.apk" -o -name "*.xapk" 2>/dev/null
# → (vazio)
ls -R /tmp/wzm 2>/dev/null || echo "MISSING /tmp/wzm"
# → MISSING /tmp/wzm (se sem 3.3.4)
which apktool jadx strings readelf nm adb 2>&1
# → strings/readelf OK, apktool/jadx/adb missing em CI
```

---

## 7. Próximo desbloqueio

1. **M2.1 (offline bootstrap completo):** testar `hosts → BootstrapServer` em device 3.10.0 (ou `shouldInterceptRequest`) e capturar `/__hits` com `host === prod.cdni.callofduty.com` como `VERIFIED — observado em execução` final.
2. **M3 (Demonware/UNO):** `pcap` do `Meta Fetch` host real + strings `demonware.net` em `strings libgame.so` 3.3.4 (aguarda APK) → fichas `VERIFIED` para auth/matchmaking/game_server.
