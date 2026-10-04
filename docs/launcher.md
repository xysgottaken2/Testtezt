# WZM Offline Launcher — Android

> **Status:** M3/M3.2 — **roteamento CDNI local COMPROVADO no device** (S23 Ultra, 2026-10-04): 5 conexões do WZM
> chegaram ao listener `127.0.0.1:443` e apareceram no log; todas foram recusadas pelo cliente no TLS
> (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`) → **o bloqueio restante é exclusivamente confiança de certificado**.
> O stub de 18081 (M2) continua existindo, mas o caminho real do CDNI é o `cdn/` (ver §5.1 e [docs/research/m3-cdni-integration.md](research/m3-cdni-integration.md) §6.1).
> Investigação do trust/pinning do APK 3.10.0: [docs/research/m3.2-apk-tls-trust-investigation.md](research/m3.2-apk-tls-trust-investigation.md).

## 0. Build verificado (CI)

| Campo | Valor |
|---|---|
| Workflow | `.github/workflows/android-build.yml` (job `build`) — roda sozinho em todo push **e** é chamado pelo workflow `build` (job `apk`), de modo que o artifact do APK aparece **nos dois runs** |
| Resultado | **success** — `testDebugUnitTest` + `assembleDebug` + SHA256 + upload |
| Runs verdes | M2: `37177649488` (`18ae95c`), `37177973604` (`d6feff9`) · M3: `37179735648` (`622a97b`) · M3 + VER LOGS: `37205801261`/`37206351532` · **M3.2 (classificador TLS + evidência do device): `37209458668` (commit `d69a731`)** · **artifact do APK verificado pelo CI: `37210054051` (commit `b761b07`, jobs `build` + `verify-artifact`)** · **`37210339073` (commit `2d49ab0`, só documentação): também gerou e verificou o artifact — prova de que o workflow roda em todo push, sem filtro de `paths`** |
| Artifact | **`wzm-offline-launcher-debug`** (30 dias) — `app-debug.apk` + `app-debug.apk.sha256` |
| SHA-256 observados | M2: `ca21f32d…eded`, `68caf1f7…aa04` · M3: `75474ede…6eb558` · M3 + VER LOGS: `c358af7b…60ec5b`, `e9beba63…48847` · **M3.2 (run `37209458668`): `1aa52f6d226f7726a7209bb1bde1348d73198609b996c325ef5898cc47d1a008`** · **verificado pelo job `verify-artifact` (run `37211354617`, commit `1280324`): `05696536a9ab0a1365ac30a70d3b21eb2646108eee6660956d21699dcf455ffd`** · **`3c03870cd2f0e59ae6a96bd332f099a86aad0c27b4f88ae1cc81aef9a4b1f779` (run `37210339073`, 15.678.268 B)** |
| Tamanho | M2 ~15.573.600 B · M3 15.640.840 B · M3 + VER LOGS 15.675.176 B / 15.675.060 B · **M3.2: 15.678.140 B (~14,9 MiB)** · **verificado: 15.678.356 B (~14,9 MiB)** |
| Data | 2026-10-04 |

> **O hash muda a cada execução** (APK *debug* embute timestamps); a fonte de verdade é sempre o arquivo `app-debug.apk.sha256` que acompanha o artifact — e o resumo do run traz a annotation `sha256=… size=…`.

> `[WARZONE_VERIFIED — device]` **M3 comprovado no S23 Ultra (2026-10-04):** `tcpConnections=5` em `127.0.0.1:443`,
> `tlsFailed=5` (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`), `httpRequests=0`, `dnsIntercepted=0`. O tráfego do jogo
> chega ao servidor local e aparece em VER LOGS; o bloqueio é confiança de certificado.
> `[VERIFIED]` em CI (M3.2): compilação Kotlin/Compose, **70 testes JVM** (10 `ServerTest` + 3 `WzmLauncherTest` + 9 `CdnRouteTableTest` + 6 `TunnelPacketsTest` + 5 `DnsRouterTest` + 5 `LocalHttpsServerTest` fim-a-fim com TLS real + 5 `CertificateAssetTest` + 10 `RequestLogTest` + 7 `FileLogSinkTest` + **9 `TlsTrustTest`**), incluindo servidor real em socket com `HTTP 200` + `"OK"` em `/health`, roteamento DNS, RST/checksums, 404 controlado com path exato e log, armazenamento/consulta/exportação do RequestLog, empacotamento do APK, SHA-256, preflight de sintaxe Kotlin e checagem anti-commit de assets proprietários. (`HealthEndpointTest` é instrumented, roda só em device.)
> `[VERIFIED no device, launcher M2]` instalação no S23 Ultra, abertura sem crash, servidor local e `startActivity` do WZM (teste do usuário 2026-10-04).
> `[PENDING DEVICE]` **M3 no S23 Ultra:** consentimento de VPN, `bind` em `:443`, DNS interceptado, primeiro request CDNI chegando ao servidor e o bloqueio de confiança TLS — é o teste que o usuário precisa rodar (passo a passo em [docs/research/m3-cdni-integration.md](research/m3-cdni-integration.md) §7).

### Correções de build descobertas (para não repetir)

| Sintoma no CI | Causa | Correção |
|---|---|---|
| `Plugin [id: 'org.jetbrains.kotlin.plugin.compose', version: '1.9.22'] was not found` | esse plugin só existe a partir do **Kotlin 2.0.0** | com Kotlin 1.9.22 usar `composeOptions.kotlinCompilerExtensionVersion` |
| `The 'android.useAndroidX' property is not enabled` | deps AndroidX (Compose/Lifecycle) exigem a flag | `android/gradle.properties` com `android.useAndroidX=true` |
| `Platform declaration clash: getStatus()` | propriedade `status` + `fun getStatus()` geram a mesma assinatura JVM | campo privado `currentStatus` + `fun getStatus()` |
| Logs do CI inacessíveis deste ambiente (blob bloqueado) | — | pipeline emite `::error::`/`::notice::` (annotations) e artifact `android-test-logs` |
| `e: …:130:42 Identifier expected` + `e: …:157:1 Unclosed comment` (linha errada, longe da causa) | **Kotlin aninha comentários de bloco**: um `/__wzm_offline/*` dentro de um KDoc abriu comentário aninhado e o `*/` seguinte fechou só o interno → o resto do arquivo virou comentário | nunca escrever `/*` ou `*/` dentro de comentário/KDoc (usar `/__wzm_offline/…`); preflight no CI pega isso |
| `Identifier expected` em string raw `""""campo":…` | aspas **coladas** ao delimitador `"""`, que o lexer do Kotlin rejeita | montar JSON com concatenação/`jsonEscape` (nunca `"""` colado em `"`) |
| Preflight local | — | `python3 scripts/check-kotlin-preflight.py --dir android` (roda no CI antes do build) |
| Teste falhou e não dá para ler artifact/log (blob bloqueado) | — | passo `Report unit test failures as annotations` publica mensagem + stack das falhas como annotations: `gh api repos/<owner>/<repo>/check-runs/<job>/annotations` |

Compatibilidade VERIFIED: Kotlin `1.9.22` ↔ Compose Compiler `1.5.8` ↔ AGP `8.5.2` ↔ Gradle `8.7` ↔ JDK `17` ↔ compileSdk `34`.

---

## 1. Arquitetura

```
UI (Compose/MainActivity)
  ↓
LauncherViewModel (StateFlow, viewModelScope, logs)
  ↓
ServerController (Mutex, ServerStatus: PARADO→INICIANDO→ONLINE→PARANDO→PARADO/ERRO)
  ↓
EmbeddedLocalServer (ServerSocket em 127.0.0.1:18081, thread daemon, CopyOnWrite hits)
  ↓
OfflineContentServer (interface stub para futuro CDNI: manifest/cdni.meta/shard)

WzmLauncher ──→ PackageManager.getLaunchIntentForPackage("com.activision.callofduty.warzone")

--- M3: roteador CDNI local (app sem root) ---
CdnRouterController (estado p/ a UI)
  ├─→ LocalHttpsServer (SSLServerSocket em endereço do túnel :443 e loopback :443; nunca 0.0.0.0)
  │      ↓ TcpRelay → CdnRouteTable → BootstrapEndpoints (VERIFIED/HYPOTHESIS) | 404 controlado
  └─→ CdnVpnService (VpnService, foreground, per-app = só o WZM)
         ├─ DNS 10.111.222.2 interceptado → hosts CDNI respondem A=10.111.222.1; demais vão p/ 1.1.1.1/8.8.8.8
         ├─ TCP p/ 10.111.222.1:443 → entregue à pilha local (kernel) ou devolvido ao tun ("bounce")
         └─ resto: RST / descarte — nada é inventado
RequestLog (buffer único: launcher + DNS + TLS + HTTP) → UI Compose
```

- **UI nunca bloqueia:** `ServerController.start()/stop()` são `suspend` com `Mutex`, chamados via `viewModelScope.launch` em `Dispatchers.Main`. `EmbeddedLocalServer.accept()` roda em `thread(isDaemon=true)` e cada conexão em nova thread.
- **Rotação segura:** `LauncherViewModel` é `AndroidViewModel`, sobrevive a `Activity` recreation. `ServerController` é `single` por ViewModel; `EmbeddedLocalServer.running` é `AtomicBoolean`, `start()` é `@Synchronized` → segunda instância não é criada.
- **Config central:** `LauncherConfig` (`HOST=127.0.0.1`, `PORT=18081`, `WZM_PACKAGE`). Nenhum valor espalhado.

---

## 2. Como compilar

> Antes de compilar (e antes dos testes JVM), gere o certificado local descartável do roteador CDNI:
> `bash scripts/generate-local-cdni-cert.sh` (o CI já faz isso automaticamente). Os arquivos
> `android/app/src/main/assets/cdn_local*.{p12,pem}` **não** são versionados (o `.gitignore` bloqueia `*.p12`/`*.pem`).

### Local (com Android SDK)
```bash
cd android
./gradlew :app:assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Requer:
- JDK 17 (Temurin)
- Android SDK com `platforms;android-34` e `build-tools;34.0.0`
- Gradle 8.7 (wrapper em `android/gradle/wrapper/` — se jar for placeholder, `gradle wrapper --gradle-version 8.7` regenera)

### GitHub Actions (recomendado — VERIFIED)

O workflow gera o certificado local (`scripts/generate-local-cdni-cert.sh`), roda os testes JVM e monta o APK
com `./gradlew assembleDebug`.

**Onde baixar o APK (passo a passo):** Actions → abra um run recente do workflow **`build`** (o run principal do
CI, que também publica o artifact técnico) **ou** do workflow **`android-build`** → seção **Artifacts** →
**`wzm-offline-launcher-debug`** (fica listado ao lado do artifact técnico `warzone-offline-M1`). Dentro do artifact:

| Caminho no artifact | Arquivo |
|---|---|
| `app-debug.apk` (raiz) | o APK debug instalável |
| `app-debug.apk.sha256` (raiz) | hash SHA-256 do APK |

> Os workflows `build` e `android-build` rodam em **todo push** (nenhum dos dois tem filtro de `paths`)
> justamente para o artifact nunca faltar; o `build` chama o `android-build.yml` como workflow reutilizável
> (`workflow_call`), então o APK está no mesmo run do artifact técnico **e** no run próprio do `android-build`.
> O artifact técnico `warzone-offline-M1` (workflow `build`) contém **somente código-fonte e docs — nunca APK**,
> e as guardas do CI (pacote `com.wzm.launcher*` via aapt2 + varredura de extensões proprietárias + guarda
> anti-`.apk` fora do build) impedem que qualquer APK que não seja o nosso entre nos artifacts.
> Nenhum APK do Warzone Mobile original é publicado.

O CI **verifica o artifact depois de publicar** (job `verify-artifact`: baixa de volta, exige exatamente os 2
arquivos na raiz e confere sha256/tamanho contra o build) e publica o resultado no resumo do run e na annotation
`artifact-verificado`. Última verificação no run do workflow **`build`** (run `37211354617`, commit `1280324`):

```
artifact=wzm-offline-launcher-debug caminho-no-artifact=app-debug.apk (+app-debug.apk.sha256)
arquivos=2 size=15.678.356 sha256=05696536a9ab0a1365ac30a70d3b21eb2646108eee6660956d21699dcf455ffd
```

O APK é publicado em **todo push** (nenhum filtro de `paths`, inclusive commits só de documentação) por dois
caminhos que usam o **mesmo** `.github/workflows/android-build.yml`: o próprio workflow `android-build` e o job
`apk` do workflow `build` (via `uses: ./.github/workflows/android-build.yml`) — por isso o artifact
`wzm-offline-launcher-debug` aparece na seção Artifacts do run do `build`, ao lado de `warzone-offline-M1`.

Workflow `.github/workflows/android-build.yml`:

1. `checkout`
2. `setup-java@v4` (Temurin 17)
3. SDK Android via `sdkmanager` preinstalado (`platforms;android-34`, `build-tools;34.0.0`) + `android/local.properties`
4. `gradle/actions/setup-gradle@v3` (Gradle 8.7)
5. Wrapper estrito (`gradle wrapper --gradle-version 8.7` se `gradle-wrapper.jar` estiver vazio; falha se não gerar)
6. `./gradlew :app:testDebugUnitTest` (log completo em `gradle-test.log`; falha vira annotations + artifact `android-test-logs`)
7. `./gradlew :app:assembleDebug`
8. `sha256sum app-debug.apk` + **annotation com o hash** + `upload-artifact@v4` nome `wzm-offline-launcher-debug`
9. Guarda anti-commit: falha se houver `*.apk/*.xapk/*.shard` fora de `android/app/build/`

> O **mesmo arquivo** é reutilizado pelo workflow `build` (job `apk`, `uses: ./.github/workflows/android-build.yml`),
> de modo que cada push publica o APK em dois runs — `build` (ao lado do artifact técnico) e `android-build` —
> sempre com o nome `wzm-offline-launcher-debug` e a mesma estrutura de 2 arquivos na raiz.

Artefato disponível em **Actions → `build` ou `android-build` → Artifacts → `wzm-offline-launcher-debug`**.

---

## 3. Como instalar

```bash
# S23 Ultra via adb
adb install android/app/build/outputs/apk/debug/app-debug.apk
# ou baixa o artifact do GitHub Actions e instala
adb install wzm-offline-launcher-debug/app-debug.apk
```

Ou instala manualmente tocando no `app-debug.apk` no gerenciador de arquivos (permitir fontes desconhecidas).

**Debug package:** `com.wzm.launcher.debug` (evita conflito com futuro release `com.wzm.launcher`).

---

## 4. Como usar

1. Abra **WZM Offline Launcher** — status inicial:
   - Servidor: **PARADO**
   - WZM: **NÃO DETECTADO** ou **INSTALADO • 3.10.0** (se WZM estiver instalado)
   - Log: `[HH:MM:SS] Launcher iniciado`

2. **INICIAR SERVIDOR** → `INICIANDO → ONLINE`, log `Servidor ONLINE em 127.0.0.1:18081`
   - Teste: `adb shell curl -v http://127.0.0.1:18081/health` → `200 OK`
   - Ou no PC: `curl http://127.0.0.1:18081/health` apenas se `adb forward tcp:18081 tcp:18081` (servidor é 127.0.0.1 apenas, não 0.0.0.0)

3. **INICIAR ROTEADOR CDNI** → aceite o diálogo de VPN do sistema:
   - log esperado: `HTTPS local ativo em 10.111.222.1:443, 127.0.0.1:443` e
     `túnel ativo: DNS 10.111.222.2, rota 10.111.222.0/24, prod.cdni.callofduty.com -> 10.111.222.1`
   - se `:443` for negado pelo sistema, o log mostra o erro exato (e sobe 18443 só p/ diagnóstico)
   - **EXPORTAR CA LOCAL** copia a CA (não-Activision) para o armazenamento do app (instalação manual, ver §5.1)

4. **INICIAR WARZONE MOBILE** → verifica `PackageManager.getPackageInfo("com.activision.callofduty.warzone")`
   - Se instalado: `getLaunchIntentForPackage()` → `startActivity` → log `Warzone Mobile iniciado`
   - Se não: log `Erro: Warzone Mobile não está instalado`
   - Com o roteador ativo, o log passa a mostrar `[DNS] … [interceptado]`, `[CDNI] TCP SYN …`, `[TLS] …` e `[CDNI] GET /… -> 200 (VERIFICADO)`

5. **PARAR ROTEADOR** → derruba túnel + listener HTTPS; **PARAR SERVIDOR** → `PARANDO → PARADO`, log `Servidor PARADO`.

Todos os estados refletem instantaneamente na UI via `StateFlow`.

---

## 5. Endpoints do stub (apenas teste, não são CDNI real)

| Método | Path | Resposta | Uso |
|---|---|---|---|
| `GET` | `/health` | `200 text/plain "OK"` | Health check MVP, usado em teste `HealthEndpointTest` |
| `GET` | `/` | `200 text/html` com link para `/health`/`/__hits` | Página simples “Servidor offline funcionando” |
| `GET` | `/__hits` | `200 application/json` `[{"id":1,"method":"GET","path":"/health","ts":...},...]` | Lista de requests (max 1000, thread-safe) |
| `POST` | `/__reset` | `200 {"reset":true,"count":0}` | Zera `hits` (também aceita `GET /__reset` por conveniência) |
| outro | `*` | `404 Not Found` | — |

**Não inventamos CDNI:** `/manifest/build-selector-*.js`, `/wzm/shard_cdn/...`, etc. **não** estão neste stub. Este stub existe só para provar que o servidor sobe; o CDNI real está em §5.1.

---

## 5.1 Roteador CDNI local (M3) — DNS + HTTPS de verdade

O WZM não fala com `127.0.0.1:18081`: ele resolve `prod.cdni.callofduty.com` e conecta em **HTTPS :443**.
Sem root não há `bind` em `:443` nem `iptables`; a solução implementada é:

1. **VpnService per-app** (`addAllowedApplication` = só `com.activision.callofduty.warzone`) com rota **apenas** de `10.111.222.0/24`
   → não é VPN de internet (o resto do tráfego do WZM continua normal).
2. **DNS virtual** `10.111.222.2`: consultas dos hosts CDNI comprovados são respondidas **em userspace**
   (`A = 10.111.222.1`, `AAAA`/`SVCB` = NOERROR vazio); qualquer outro domínio é encaminhado a `1.1.1.1`/`8.8.8.8`
   por socket `protect()`ed.
3. **`10.111.222.1` é endereço local** (da própria interface tun): o kernel entrega o pacote à sua pilha TCP,
   onde o **nosso listener HTTPS :443** atende; se algum aparelho mandar o pacote pelo túnel, o loop do tun
   devolve ("bounce") o pacote para a interface — mesmo resultado.
4. **TLS** termina no kernel com um **certificado nosso** (SAN `prod.cdni.callofduty.com`, `*.cdni.callofduty.com`);
   cada handshake (sucesso **ou falha**) é logado — é a prova de que a requisição do WZM chegou.
   Falhas saem classificadas: `[TLS] FALHA no handshake TLS em …: motivo=CLIENTE_RECUSOU_CERTIFICADO (…) — …`
   (`TlsTrust`), o que distingue recusa de certificado, HTTP em claro, cifra sem comum, etc.
5. **HTTP** → `CdnRouteTable`:
   * endpoint com evidência (M2/M2.2) → `200` + corpo placeholder **marcado** (`wzm-offline-local`);
   * nome conhecido com caminho inferido → `200` marcado `HYPOTHESIS`;
   * qualquer outro → `404` controlado com URL/path **exatos** no corpo e no log (é assim que os paths
     ainda desconhecidos serão descobertos — nada de inventar manifest).

Endpoints servidos (detalhes e evidência por path em [docs/research/m3-cdni-integration.md](research/m3-cdni-integration.md) §4):

| Path | Chamado por | Resposta local |
|---|---|---|
| `/manifest/build-selector-103.js` | bootstrap (M2.2, 1º request) | 200 JS placeholder marcado |
| `/manifest/build-selector-102.js` | bootstrap (M2.2) | 200 JS placeholder marcado |
| `/static/web/index.html` | WebView (M2) | 200 HTML placeholder marcado |
| `/manifest/manifest.json`, `/prelogin/boot-15.0.0/web/**` | boot web (M2.2) | 200 placeholder marcado |
| `/wzm/shard_cdn/{android,ios}/_manifest/cdni.meta` | shard_cdn (M2.2) | 200 placeholder marcado |
| `/__wzm_offline/health`, `/__wzm_offline/requests` | diagnóstico do launcher | JSON com contadores e o log |

Exemplo de leitura no próprio device:

```bash
adb shell 'curl -k --resolve prod.cdni.callofduty.com:443:127.0.0.1 https://prod.cdni.callofduty.com/__wzm_offline/requests'
```

**Diagnóstico de sessão (M3.3):** como dois testes no mesmo device deram resultados diferentes
(5 conexões em um, 0 no outro), o launcher passou a registrar dono da conexão, rota/interfaces aplicadas,
inatividade do túnel e motivo de cada descarte — sem alterar o roteamento. Investigação completa, hipóteses
e protocolo de reprodução: [docs/research/m3.3-diferenca-entre-os-testes.md](research/m3.3-diferenca-entre-os-testes.md).

**Bloqueio conhecido (§6 do doc M3):** apps com `targetSdk ≥ 24` não confiam em CA instalada pelo usuário;
o WZM provavelmente recusará o certificado no handshake (logado como `[TLS] FALHA … cliente RECUSOU …`).
Isso **não** é contornado: nada de root, patch de trust ou alteração do APK do jogo.

---

## 5.2 VER LOGS — tela de logs dentro do APK (sem ADB/Logcat)

O botão **VER LOGS** (tela principal) abre um painel com o `RequestLog` completo do roteador CDNI,
atualizado **em tempo real** (o log é um `StateFlow` consumido pelo Compose; o WZM pode estar aberto
em primeiro plano).

| Recurso | Detalhe |
|---|---|
| Tags visíveis | `[DNS]`, `[CDNI]`, `[CDNI?]` (desconhecidos), `[HTTP]`, `[TLS]`, `[TUN]`, `[VPN]`, `[DIAG]`, `[LAUNCHER]` |
| Conexões TCP | cada tentativa traz o listener, o caminho (`via loopback`/`via túnel`) e o **dono** da conexão: `dono=uid=<n> (<pacote>)` — sem isso não se sabe se quem conectou foi o WZM ou outro app (M3.3) |
| Sessão (M3.3) | `[DIAG]` abre a sessão com número da execução, pacote/versão/UID alvo, se o per-app foi aceito, interfaces/rotas/dns aplicados e quais listeners subiram |
| Falha de TLS | linha com `motivo=<CÓDIGO>` + dica (ex.: `CLIENTE_RECUSOU_CERTIFICADO` = o cliente recusou a CA local — a requisição **chegou**) |
| DNS | consultas interceptadas (`[DNS CDNI recebido e interceptado]`), as vistas no túnel (as 12 primeiras) e as **encaminhadas** (uma vez por nome) — útil para diagnosticar `dnsIntercepted=0` |
| Silêncio do túnel (M3.3) | aviso único de `[DIAG] nenhum pacote recebido no TUN…` e de `[DIAG] nenhuma consulta DNS dos hosts CDNI…` — o "não aconteceu nada" passa a ser explícito no log |
| Descarte de pacote | `[TUN] pacote descartado: motivo=<CODIGO> …` (`FORA_DA_ROTA`, `UDP_PORTA_NAO_DNS`, `PROTO_NAO_SUPORTADO`, `TCP_SEM_ATENDIMENTO`, `PACOTE_INVALIDO`) |
| Bind dos listeners | `[CDNI] listener NÃO subiu em …: motivo=ENDERECO_INDISPONIVEL|PORTA_EM_USO|PORTA_NEGADA` e aviso explícito quando o endereço do túnel fica sem listener |
| Resumo do túnel | `[DIAG] resumo do túnel: pacotes=… para-10.111.222.1=… devolvidos-bounce=… descartados=… dns-total=… dns-cdni-interceptado=… dns-encaminhado=… tcp-conexoes=… tls-ok=… tls-falha=…` (a cada 20 s e no fim) |
| Status/código HTTP | linha `[HTTP]` dedicada: `GET /path -> 200 OK (resposta N B, cliente=…)`; desconhecidos aparecem como `404 Not Found` |
| Timestamps | `[HH:mm:ss.SSS]` em cada linha (fuso local do aparelho) |
| Contadores | `DNS: consultas/interceptadas • TCP: conexões • HTTP: requests/desconhecidos • TLS: ok/falhas • TUN: pacotes/p-10.111.222.1/bounce/descartes • DNS encaminhado` |
| Filtros | chips por tag + contagem de linhas visíveis |
| Leitura | fonte monoespaçada, cor por tag, toggle **auto-rolar** (desligue para ler enquanto chegam linhas novas) |
| LIMPAR LOGS | com confirmação; apaga buffer, contadores e o arquivo persistido |
| COPIAR LOGS | copia o texto completo (cabeçalho de contadores + linhas) para a área de transferência |
| SALVAR/EXPORTAR .TXT | grava em diretório privado do app e abre o compartilhamento (FileProvider) |
| Persistência | cada linha também vai para `filesDir/request-log.txt` (rotação em 256 KB); ao reabrir o launcher, o log anterior é restaurado e uma linha avisa quantas linhas vieram |
| Navegação | tela principal ↔ logs sem perder nada (o log vive fora da UI); botão físico de voltar retorna |

Exemplo do cabeçalho exportado (também é o mesmo texto do COPIAR):

```
# WZM Offline Launcher — RequestLog do roteador CDNI local
# exportado em: 2026-10-04T14:22:31.512-03:00
# contadores: dnsQueries=3 dnsIntercepted=1 tcpConnections=2 httpRequests=1 unknownRequests=1 tlsOk=1 tlsFailed=0 tunPackets=12 tunToRedirect=5 tunBounces=5 tunDiscards=7 dnsForwarded=2
# linhas: 12 (buffer máximo: 400)
# privacidade: não são registrados corpos de requisição nem cabeçalhos (sem cookies/tokens)
```

**Privacidade:** registramos apenas tag, horário, método, host/path, status e contadores. Não são
logados corpos de requisição, cabeçalhos, cookies, tokens nem credenciais — nem no arquivo persistido.

---
## 6. O que já funciona (MVP)

- [x] Projeto Android compilável **e compilado em CI** (Kotlin 1.9.22, AGP 8.5.2, Gradle 8.7, Compose Compiler 1.5.8, minSdk 24, target 34)
- [x] APK debug gerado e publicado como artifact (`wzm-offline-launcher-debug`, ~14,9 MiB; hash por execução, ver `app-debug.apk.sha256` do artifact)
- [x] `EmbeddedLocalServer` em `127.0.0.1:18081`, background thread, shutdown limpo
- [x] `ServerController` com estados `PARADO/INICIANDO/ONLINE/PARANDO/ERRO`, `isRunning()`, `getPort()`, mutex
- [x] `WzmLauncher` detecta instalação, `getInstalledVersion()`, `launch()` via `PackageManager` dinâmico (sem hardcode Activity)
- [x] `LauncherConfig` centralizado
- [x] `OfflineContentServer` interface stub
- [x] UI Compose com status, 3 botões, logs roláveis
- [x] Testes unitários JVM: `ServerTest` (10 casos: estado inicial, start, double start, stop, double stop, `/health` 200 OK via socket, `/` HTML, `/__hits` + `/__reset`, config de porta, bind só em loopback), `WzmLauncherConfigTest` (3 casos); `HealthEndpointTest` instrumented para device
- [x] GitHub Actions `android-build.yml` verde: `testDebugUnitTest` + `assembleDebug` + artifact `wzm-offline-launcher-debug` + SHA256 + checagem de assets proprietários
- [x] `docs/launcher.md` + README
- [x] **M3:** `CdnVpnService` (VpnService per-app + DNS em userspace + bounce/RST), `LocalHttpsServer` (:443 no endereço do túnel e loopback), `CdnRouteTable`/`BootstrapEndpoints` (só endpoints com evidência), `RequestLog` unificado, certificado local + botão EXPORTAR CA
- [x] **M3:** testes JVM novos (`DnsRouterTest`, `TunnelPacketsTest`, `CdnRouteTableTest`, `LocalHttpsServerTest` fim-a-fim com TLS real, `CertificateAssetTest`)
- [x] **M3 + VER LOGS:** tela de logs no APK (filtros por tag, contadores, status HTTP, timestamps, auto-rolar), botões LIMPAR/COPIAR/SALVAR .TXT, persistência do log em arquivo (sobrevive a reinício do processo) e testes `RequestLogTest` + `FileLogSinkTest`

---

## 7. O que ainda NÃO funciona / próximo

- **Corpo real dos arquivos do CDNI (UNKNOWN):** servimos placeholder marcado; nenhum manifest é inventado
- **Confiança TLS do lado do WZM (bloqueio CONFIRMADO no device):** 5/5 conexões recusadas com `SSLV3_ALERT_CERTIFICATE_UNKNOWN`; `targetSdk ≥ 24` não confia em CA de usuário e CA no *system store* exige root → investigação de pinning/trust no APK em `docs/research/m3.2-apk-tls-trust-investigation.md` (sem bypass, sem patch)
- **Paths exatos da cadeia do boot (ex.: `popup/events/dailylogin`):** hoje marcados `HYPOTHESIS`; o 404 controlado revela o path real quando o cliente pedir
- Persistência de logs (o buffer é em memória, 400 linhas), splash, onboarding

---

## 8. Segurança e escopo

Launcher é ferramenta **local/offline de preservação**. Não faz: roubo de credenciais, bypass de auth/anti-cheat, interceptação de contas, toque em servidores Activision, distribuição de APK/shard.

M3 (VPN/DNS/HTTPS locais):
- O `CdnVpnService` **não encaminha tráfego para a internet** e não é VPN de saída: a rota do túnel cobre apenas `10.111.222.0/24`.
- O túnel é **per-app** (`addAllowedApplication` = WZM) e existe apenas enquanto o usuário deixa o roteador ligado; o sistema pede consentimento explícito (`VpnService.prepare`).
- Os listeners HTTPS escutam **somente** endereços específicos do dispositivo (`10.111.222.1`, `127.0.0.1`) — nunca `0.0.0.0`.
- O certificado é **nosso** (CA + folha descartáveis, geradas por `openssl`, sem valor de segurança, sem relação com a Activision) e é declarado no handshake e no log — não há tentativa de se passar pelo emissor oficial.
- Nada no launcher modifica o APK do WZM, toca credenciais, autenticação ou anti-cheat.

---

## 9. Troubleshooting

- **Build falha `SDK not found`:** instale `Android SDK 34` via `sdkmanager "platforms;android-34" "build-tools;34.0.0"` ou use GitHub Actions (que já faz isso).
- **Wrapper jar missing/vazio:** `gradle wrapper --gradle-version 8.7` em `android/` (CI faz automaticamente e falha se não gerar).
- **`Plugin org.jetbrains.kotlin.plugin.compose not found`:** não aplique esse plugin com Kotlin 1.9.x (só existe em 2.0+); use `composeOptions.kotlinCompilerExtensionVersion`.
- **`android.useAndroidX is not enabled`:** mantenha `android/gradle.properties` com `android.useAndroidX=true`.
- **`Platform declaration clash`:** não declare `val status` junto de `fun getStatus()` na mesma classe (mesma assinatura JVM).
- **`/health` não responde:** verifique `adb logcat | grep WZM` e se servidor está `ONLINE`; teste `curl http://127.0.0.1:18081/health` **dentro** do device (`adb shell`).
- **WZM não abre:** verifique `adb shell pm list packages | grep warzone` e `LauncherConfig.WZM_PACKAGE`.
- **Roteador não sobe / `:443` negado:** o log mostra `não foi possível escutar em 10.111.222.1:443: BindException …`; o ouvinte cai para `18443` (diagnóstico). Sem `:443` o WZM não chega — registre o erro exato e abra issue (caminho alternativo: responder TCP em userspace sobre o tun).
- **Log mostra DNS interceptado mas nenhum `TCP SYN`:** o WZM pode estar usando DNS próprio/DoH ou IP fixo; verifique também se outra VPN estava ativa (só uma VPN por vez).
- **`[TLS] FALHA … cliente RECUSOU o certificado local`:** esperado com `targetSdk ≥ 24` (CA de usuário não é confiada) — é evidência de que a requisição chegou; ver §5.1/§7.
- **WZM não usa o túnel:** confirme `addAllowedApplication` no log (`per-app: somente com.activision.callofduty.warzone`).
- **Não sei onde o log foi salvo:** a tela VER LOGS mostra o caminho (`arquivo: /data/user/0/<pkg>/files/request-log.txt`); o `.txt` exportado vai para `/sdcard/Android/data/<pkg>/files/logs/` (sem permissão de armazenamento).
- **Log vazio mesmo com o WZM aberto:** confirme que o **ROTEADOR CDNI** está ativo e que apareceu `[DNS] prod.cdni.callofduty.com … [interceptado]`; sem DNS interceptado, nada chega ao servidor local.
- **Como ver o log do CI sem baixar artifact:** os passos `Report Gradle failure as annotations` / `Announce APK SHA256` publicam trechos legíveis em **check-runs/annotations** (útil quando o blob de logs está inacessível).

