# Issue #8 — Status de Desbloqueio (relatório M2, atualizado em M4.3)

> **Issue:** #8 — [M1] APK/logcat necessário para tornar endpoints WARZONE_VERIFIED  
> **Branch histórica do relatório:** `research/m2-bootstrap-offline` (esta sessão permanece em `arena/01a10449-testtezt`)
> **Data do relatório:** 2026-10-04
> **Estado atual:** bootstrap WebView documentado; CDNI nativo e caminho runtime continuam sem observação atribuída. M4.3 encontrou no `libgame.so` um candidato de override (`cdni_httpServer`), mas acesso do usuário/endpoint local permanecem `UNKNOWN`. Ver [M4.3](m4.3-libgame-static-analysis.md).
>
> **Correção M4.2/M4.3:** respostas remotas obtidas por `fetch_page` e `curl` contra servidor local não comprovam que o WZM usou o endpoint. A alegação antiga `WZM → localhost → stub` foi incorreta: nenhum teste WZM/hosts foi executado, `hosts-patch` não aplica mapping ao Android e o candidato estático ainda não foi validado em runtime. Não executar root, hosts override, WebView hook, MITM ou mudança TLS/pinning.

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

**Conclusão M2 (escopo histórico):** a observação WebView desbloqueou parcialmente dois paths públicos do bootstrap para as versões anotadas. Isso não valida configuração de endpoint local nem atribui `Meta Fetch`. M4.3 confirma strings/call-sites estáticos do CDNI nativo e um candidato de override, mas Demonware/UNO e o caminho real do jogo continuam `UNKNOWN`; consultar [M4.3](m4.3-libgame-static-analysis.md), [M4.2 histórico](m4.2-configuracao-endpoint-local.md) e [M3.6](m3.6-caminho-real-de-rede.md).

---

## 3. Artefato que ainda falta para desbloqueio completo

| Faltante | Onde colocar | Como obter |
|---|---|---|
| `WZM 3.3.4 APK/XAPK` para scan completo (opcional agora, mas ainda útil para comparar com 3.10.0) | `/tmp/wzm/warzone-334.xapk` (fora do repo) | `adb pull` ou APKMirror 3.3.4 |
| `pcap` com WebView + Demonware SYN (para confirmar `prod.cdni` 443 + demonware) | `/tmp/wzm/capture.pcap` | `tcpdump` em hotspot PC |
| `logcat` filtrado `WBootstrap` + `Meta Fetch` completo com URL | `/tmp/wzm/logcat.txt` | `adb logcat | grep -i WBootstrap` |
| `pcap` do `Meta Fetch` host real | — | idem |

---

## 4. Componentes e evidência local (não é desbloqueio offline)

| Componente | O que faz | Classificação | Limite |
|---|---|---|---|
| `docs/research/wzm-310-bootstrap.md` | Registra cadeia WebView histórica e respostas públicas `103`/página offline, separando versões. | `WARZONE_VERIFIED` somente para o request WebView no alcance anotado; `VERIFIED` para fetch público. | Não identifica o CDNI nativo, nem configura host local. |
| `docs/protocol/cdni-build-selector.md` + `cdni-offline-page.md` | Documentam paths e conteúdo remoto observado. | `VERIFIED` no alcance do fetch/registro indicado. | O uso de resposta local pelo WZM não foi testado. |
| `docs/research/wzm-permissions-gvs.md` | `Meta Fetch Success`, GVS ausente e strings `WBootstrap`. | Evento/string com classificação própria; endpoint Meta segue `UNKNOWN`. | Não inventar host/path nem mock. |
| `warzone-offline/server/src/bootstrap` | Serve stubs locais e possui tracking `/__hits`. | `VERIFIED` como servidor local; testes locais. | `curl`/fixture prova somente o servidor, não `WZM → localhost`. |
| `warzone-offline/launcher/src/hosts-patch/patch.ts` | Transforma linhas de hosts em memória. | `VERIFIED` por inspeção/testes da função. | Não escreve arquivo do Android nem aplica redirecionamento. |
| `warzone-offline/launcher/src/webview-patch/README.md` | Nota de área retirada/deprecated. | Documentação histórica. | Não há hook/override ativo; não executar. |
| `endpoint-scanner.js` + `categorize-endpoints.js` | Ferramentas genéricas de busca/categorização de strings. | `VERIFIED` como tooling. | Sem APK/`.so`, não geram evidência nova sobre o WZM. |

**Correção essencial:** `curl http://127.0.0.1:18081/manifest/build-selector-103.js -H "Host: prod.cdni.callofduty.com"` verifica uma resposta do servidor local. Não usa o DNS do aparelho, não inicia WZM, não comprova chamada WZM e não verifica TLS do jogo. A cadeia WebView não é configuração direta de endpoint.

---

## 5. Estado do antigo teste localhost

Nenhum teste em dispositivo com WZM/hosts foi executado. O rascunho de comando com escrita em `/etc/hosts`, root ou WebView hook está **retirado** e não deve ser seguido. O `hosts-patch` disponível não aplica a mudança no Android. Não usar MITM, CA, pinning bypass, hook ou alteração do APK.



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

## 7. Próximo passo Stable

1. M4.3 encontrou `cdni_httpServer` como candidato, sem comprovar que seja user-writable ou funcione para local; TUN não está provado como necessidade absoluta. Ver [M4.3](m4.3-libgame-static-analysis.md).
2. Retomar primeiro `CONTROL_ONLY` com app de UID distinto, em sessão per-app isolada. O `OwnerProbe` atual é loopback do launcher e não valida captura de outro UID pelo TUN; plano em [M3.6 §8.1](m3.6-caminho-real-de-rede.md#81-controle-positivo-real-com-outro-app-planejado-nao-executado).
3. Só depois, observar WZM separadamente com tuple/owner UID e correlacionar sessão/processos. `Meta Fetch` continua sem host/path atribuído.
4. A análise estática do `.so` de `origin/main` está documentada em M4.3, incluindo o fluxo candidato de registro/leitura até dispatch; o próximo passo é confirmar a versão via APK/build e rastrear a ligação do hash a uma fonte suportada de configuração, mantendo o limite de não baixar shards completos.
