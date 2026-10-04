# WZM Offline Launcher — Android

> **Status:** MVP 0.1.0 — base funcional instalável no S23 Ultra, pronta para evoluir. CDNI real ainda não implementado (stub).

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

### GitHub Actions (recomendado)
Workflow `.github/workflows/android-build.yml` já faz:

1. `checkout`
2. `setup-java@v4` (Temurin 17, cache gradle)
3. `setup-android@v3`
4. `setup-gradle@v3` (8.7)
5. `chmod +x gradlew` + `gradle wrapper` se jar ausente
6. `./gradlew :app:testDebugUnitTest`
7. `./gradlew :app:assembleDebug`
8. `sha256sum app-debug.apk` + `upload-artifact@v4` com nome `wzm-offline-launcher-debug`

Artefato disponível em **Actions → android-build → Artifacts**.

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

- [x] Projeto Android compilável (Kotlin, AGP 8.5.2, Gradle 8.7, minSdk 24, target 34, Compose)
- [x] `EmbeddedLocalServer` em `127.0.0.1:18081`, background thread, shutdown limpo
- [x] `ServerController` com estados `PARADO/INICIANDO/ONLINE/PARANDO/ERRO`, `isRunning()`, `getPort()`, mutex
- [x] `WzmLauncher` detecta instalação, `getInstalledVersion()`, `launch()` via `PackageManager` dinâmico (sem hardcode Activity)
- [x] `LauncherConfig` centralizado
- [x] `OfflineContentServer` interface stub
- [x] UI Compose com status, 3 botões, logs roláveis
- [x] Testes unitários: `ServerTest` (7 casos), `WzmLauncherConfigTest` (3), `HealthEndpointTest` instrumented
- [x] GitHub Actions `android-build.yml` com `assembleDebug` + artifact + SHA256 + checagem de assets proprietários
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

- **Build falha `SDK not found`:** instale `Android SDK 34` via `sdkmanager "platforms;android-34" "build-tools;34.0.0"` ou use GitHub Actions.
- **Wrapper jar missing:** `gradle wrapper --gradle-version 8.7` em `android/` (CI faz automaticamente).
- **`/health` não responde:** verifique `adb logcat | grep WZM` e se servidor está `ONLINE`; teste `curl http://127.0.0.1:18081/health` **dentro** do device (`adb shell`).
- **WZM não abre:** verifique `adb shell pm list packages | grep warzone` e `LauncherConfig.WZM_PACKAGE`.

