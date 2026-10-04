# WZM Offline Launcher — Android

> **Status:** M3 — **roteamento CDNI local implementado** (DNS interceptado + servidor HTTPS :443 + log de requests reais do WZM).
> O stub de 18081 (M2) continua existindo, mas o caminho real do CDNI é o `cdn/` (ver §5.1 e [docs/research/m3-cdni-integration.md](research/m3-cdni-integration.md)).
> Bloqueio conhecido e documentado: confiança no certificado local (targetSdk ≥ 24) — ver §5.1/§7.

## 0. Build verificado (CI)

| Campo | Valor |
|---|---|
| Workflow | `.github/workflows/android-build.yml` (job `build`) |
| Resultado | **success** — `testDebugUnitTest` + `assembleDebug` + SHA256 + upload |
| Runs verdes | `37177649488` (commit `18ae95c`), `37177973604` (commit `d6feff9`) |
| Artifact | **`wzm-offline-launcher-debug`** (30 dias) — `app-debug.apk` + `app-debug.apk.sha256` |
| SHA-256 observados | run `37177649488`: `ca21f32ddca825b704d9be5ad9ce963af3a1ba914a5a89fbc0e3a845e976eded` · run `37177973604`: `68caf1f7f2e7547b09ed19a2b11e82b7d363f755e1c6a756dd2550b6497caa04` |
| Tamanho | ~15.573.600 bytes (~14,9 MiB) |
| Data | 2026-10-04 |

> **O hash muda a cada execução** (APK *debug* embute timestamps); a fonte de verdade é sempre o arquivo `app-debug.apk.sha256` que acompanha o artifact — e o resumo do run traz a annotation `sha256=… size=…`.

> `[VERIFIED]` em CI: compilação Kotlin/Compose, **13 testes JVM** (10 em `ServerTest` + 3 em `WzmLauncherConfigTest`), incluindo servidor real em socket com `HTTP 200` + `"OK"` em `/health`, empacotamento do APK, SHA-256 e checagem anti-commit de assets proprietários. (`HealthEndpointTest` é instrumented, roda só em device.)
> `[PENDING DEVICE]` ainda não verificado em hardware: instalação no S23 Ultra, abertura sem crash, botões na UI e `startActivity` do WZM — depende do device do usuário.

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

O workflow gera o certificado local (`scripts/generate-local-cdni-cert.sh`), roda os testes JVM e monta o APK.

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

Artefato disponível em **Actions → android-build → Artifacts → wzm-offline-launcher-debug**.

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

**Bloqueio conhecido (§6 do doc M3):** apps com `targetSdk ≥ 24` não confiam em CA instalada pelo usuário;
o WZM provavelmente recusará o certificado no handshake (logado como `[TLS] FALHA … cliente RECUSOU …`).
Isso **não** é contornado: nada de root, patch de trust ou alteração do APK do jogo.

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

---

## 7. O que ainda NÃO funciona / próximo

- **Corpo real dos arquivos do CDNI (UNKNOWN):** servimos placeholder marcado; nenhum manifest é inventado
- **Confiança TLS do lado do WZM (bloqueio atual):** `targetSdk ≥ 24` não confia em CA de usuário; CA no *system store* exige root → o handshake pode falhar no cliente (o log registra isso com a requisição exata)
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
- **Como ver o log do CI sem baixar artifact:** os passos `Report Gradle failure as annotations` / `Announce APK SHA256` publicam trechos legíveis em **check-runs/annotations** (útil quando o blob de logs está inacessível).

