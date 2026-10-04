# WZM Offline Launcher — Android

> **Status:** MVP 0.1.0 — **build CI VERIFIED (APK gerado)** — base funcional instalável no S23 Ultra. CDNI real ainda não implementado (stub).

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
```

- **UI nunca bloqueia:** `ServerController.start()/stop()` são `suspend` com `Mutex`, chamados via `viewModelScope.launch` em `Dispatchers.Main`. `EmbeddedLocalServer.accept()` roda em `thread(isDaemon=true)` e cada conexão em nova thread.
- **Rotação segura:** `LauncherViewModel` é `AndroidViewModel`, sobrevive a `Activity` recreation. `ServerController` é `single` por ViewModel; `EmbeddedLocalServer.running` é `AtomicBoolean`, `start()` é `@Synchronized` → segunda instância não é criada.
- **Config central:** `LauncherConfig` (`HOST=127.0.0.1`, `PORT=18081`, `WZM_PACKAGE`). Nenhum valor espalhado.

---

## 2. Como compilar

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

3. **INICIAR WARZONE MOBILE** → verifica `PackageManager.getPackageInfo("com.activision.callofduty.warzone")`
   - Se instalado: `getLaunchIntentForPackage()` → `startActivity` → log `Warzone Mobile iniciado`
   - Se não: log `Erro: Warzone Mobile não está instalado`

4. **PARAR SERVIDOR** → `PARANDO → PARADO`, log `Servidor PARADO`, `ServerSocket` fechado limpo.

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

**Não inventamos CDNI:** `/manifest/build-selector-*.js`, `/wzm/shard_cdn/...`, etc. **não** estão neste stub. Futuro `OfflineContentServer` terá rota plugável — hoje só `200 OK` para testes.

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

---

## 7. O que ainda NÃO funciona / próximo

- CDNI real (`/manifest/manifest.json`, `cdni.meta`, shards) — stub → futuro `OfflineContentServer` com rotas verificadas (M2.2/M2.2.1)
- Redirecionamento WebView/DNS para `127.0.0.1` — hoje servidor é só loopback para teste; integração hotspot DNS/mitmproxy virá depois (M2.1)
- Persistência de logs, splash, onboarding
- Permissões adicionais, foreground service (se servidor precisar sobreviver com app em background)

---

## 8. Segurança e escopo

Launcher é ferramenta **local/offline de preservação**. Não faz: roubo de credenciais, bypass de auth/anti-cheat, interceptação de contas, toque em servidores Activision, distribuição de APK/shard. Só inicia servidor `127.0.0.1` e inicia pacote instalado via `Intent` público.

---

## 9. Troubleshooting

- **Build falha `SDK not found`:** instale `Android SDK 34` via `sdkmanager "platforms;android-34" "build-tools;34.0.0"` ou use GitHub Actions (que já faz isso).
- **Wrapper jar missing/vazio:** `gradle wrapper --gradle-version 8.7` em `android/` (CI faz automaticamente e falha se não gerar).
- **`Plugin org.jetbrains.kotlin.plugin.compose not found`:** não aplique esse plugin com Kotlin 1.9.x (só existe em 2.0+); use `composeOptions.kotlinCompilerExtensionVersion`.
- **`android.useAndroidX is not enabled`:** mantenha `android/gradle.properties` com `android.useAndroidX=true`.
- **`Platform declaration clash`:** não declare `val status` junto de `fun getStatus()` na mesma classe (mesma assinatura JVM).
- **`/health` não responde:** verifique `adb logcat | grep WZM` e se servidor está `ONLINE`; teste `curl http://127.0.0.1:18081/health` **dentro** do device (`adb shell`).
- **WZM não abre:** verifique `adb shell pm list packages | grep warzone` e `LauncherConfig.WZM_PACKAGE`.
- **Como ver o log do CI sem baixar artifact:** os passos `Report Gradle failure as annotations` / `Announce APK SHA256` publicam trechos legíveis em **check-runs/annotations** (útil quando o blob de logs está inacessível).

