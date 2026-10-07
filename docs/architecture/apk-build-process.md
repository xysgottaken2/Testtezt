# Processo completo de build do APK (launcher `com.wzm.launcher`)

> **Data:** 2026-10-07 · **Escopo:** como o **nosso** APK debug é construído, assinado, verificado e publicado. Não há processo de build do APK do Warzone Mobile neste repo (e não pode haver: `*.apk`/`*.obb`/`*.shard` são bloqueados).
> **STATUS:** `BUILD_APK_DOCUMENTADO` · **Rótulos:** `VERIFIED` (lido nos arquivos deste repo nesta rodada, com número de linha) · `PROBABLE` (comportamento documentado do AGP/Actions, não medido aqui).

---

## 0. O fluxo em uma linha

```
push (main | arena/** | research/** | feature/**) ou PR → main
        │
        ├── workflow `build`  ──► npm ci/tsc (server+launcher Node) + check-docs  ──► artifact TÉCNICO `warzone-offline-M1`  (SEM APK)
        │        └── (usa `workflow_call`) ─────────────────────────────────┐
        │                                                                   ▼
        └── workflow `android-build`  ◄──────────────────── mesmo job, chamado de duas formas
                 JDK17 → SDK34 → Gradle8.7 → wrapper → preflights → CERTIFICADO LOCAL
                 → :app:testDebugUnitTest (262 testes) → assembleDebug → staging+sha256
                 → guard de pacote/assets → artifact `wzm-offline-launcher-debug`
                 → job `verify-artifact` re-baixa o artifact e confere layout/hash/tamanho
```

O APK **só** nasce em `android-build.yml`. Os dois gatilhos (push/PR e `workflow_call` do `build`) publicam o mesmo artifact `wzm-offline-launcher-debug`, para você achar o APK no run que estiver olhando (`android-build.yml:3-12`). `VERIFIED`.

---

## 1. Insumos exatos (tudo que entra no APK)

| Insumo | Arquivo / fonte | Valor |
|---|---|---|
| Módulos | `android/settings.gradle.kts:15-16` | `rootProject.name = "wzm-offline-launcher"`, `include(":app")` — **um** módulo |
| Plugins | `android/build.gradle.kts:3-5` | AGP `8.5.2`, Kotlin Android `1.9.22` (nada além) |
| Wrapper | `android/gradle/wrapper/gradle-wrapper.properties` | Gradle **8.7** (`-bin.zip`) |
| Propriedades | `android/gradle.properties:3-10` | `useAndroidX=true`, `enableJetifier=false`, `nonTransitiveRClass=true`, `jvmargs -Xmx3072m`, `parallel=true`, **`caching=true`** |
| Config do app | `android/app/build.gradle.kts:8-65` | `namespace`/`applicationId` = `com.wzm.launcher`; `compileSdk 34`; `minSdk 24`; `targetSdk 34`; `versionCode 1`; `versionName "0.1.0-mvp"`; Java/Kotlin target **17**; `compose=true` + `kotlinCompilerExtensionVersion "1.5.8"` |
| Variantes | `android/app/build.gradle.kts:25-37` | `debug { isDebuggable=true, applicationIdSuffix=".debug" }` · `release { isMinifyEnabled=false, proguardFiles(...) }` — **nenhum `signingConfig` no arquivo (0 ocorrências)** |
| Dependências | `android/app/build.gradle.kts:67-102` | Compose BOM `2024.06.00`, material3, activity-compose 1.9.0, lifecycle 2.8.4, core-ktx 1.12.0, coroutines 1.8.1; testes: junit 4.13.2, mockito 5.11.0, **Robolectric 4.12.2**, compose-ui-test, espresso 3.6.1 |
| Código-fonte | `android/app/src/**` | **48** `.kt` em `main` (`cdn/`, `server/`, `ui/`, `wzm/` + `LauncherConfig`), **31** em `test`, **1** em `androidTest` |
| Recursos | `android/app/src/main/res` | 14 arquivos: ícones em 5 densidades (`mipmap-*dpi`), `values/{colors,strings,themes}.xml`, `xml/file_paths.xml` |
| Manifesto | `android/app/src/main/AndroidManifest.xml` | permissões `INTERNET`, `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`; `<queries>` para `com.activision.callofduty.warzone`; `.ui.MainActivity`; `FileProvider` `${applicationId}.fileprovider`; serviço `.cdn.CdnVpnService` (`BIND_VPN_SERVICE`, `foregroundServiceType="specialUse"`) |
| **Gerado no build** | `scripts/generate-local-cdni-cert.sh` | `android/app/src/main/assets/cdn_local.p12` + `cdn_local_ca.pem` — **não existem no tree** (`VERIFIED`: `ls android/app/src/main/assets` não existe); são criados pelo passo 7 do CI |
| ProGuard | `android/app/proguard-rules.pro` | presente, mas `isMinifyEnabled=false` no release ⇒ só afeta `assembleRelease` |
| Toolchain no runner | `android-build.yml:32-59` | JDK **17 Temurin**, `sdkmanager` → `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`; `gradle/actions/setup-gradle@v3` com `gradle-version: 8.7` |

**Ausências deliberadas:** `android/local.properties` (gerado no CI em `android-build.yml:51`), `assets/` (gerado), keystore (default do AGP), e qualquer `.apk`/`.obb`/`.shard` no Git (`.gitignore:24-33`).

---

## 2. Passo a passo do CI (`android-build.yml`, job `build`) — o que cada passo faz e por que existe

| # | Passo (linha) | O que roda | Por que está aí |
|---|---|---|---|
| 1 | `actions/checkout@v4` (:30) | clone | — |
| 2 | Set up JDK 17 (:32-36) | Temurin 17 | `compileOptions`/`kotlinOptions` exigem 17 |
| 3 | Set up Android SDK (:38-54) | `sdkmanager --licenses`, `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`; **escreve `android/local.properties` com `sdk.dir`** | o runner tem a SDK pré-instalada, mas o Gradle só a acha via `local.properties`/`ANDROID_HOME`; o `echo ... > local.properties` é o que torna o build reproduzível sem commitar caminho de máquina |
| 4 | Setup Gradle 8.7 (:56-59) | `gradle/actions/setup-gradle@v3` | cache de dependências/build do Gradle |
| 5 | **Generate Gradle Wrapper** (:61-71) | se `gradle/wrapper/gradle-wrapper.jar` estiver vazio/ausente → `gradle wrapper --gradle-version 8.7`; depois `test -s` | **o jar versionado tem 0 bytes** (`100644 blob e69de29b… 0` — blob vazio do Git). Sem este passo, `./gradlew` morre com `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` |
| 6 | Preflights Kotlin (:73-77) | `scripts/check-kotlin-preflight.py --dir android` (aspas raw + comentário aninhado) e `scripts/check-kotlin-literals.py android` | armadilhas que já quebraram builds aqui; falham em segundos, antes de qualquer compilação |
| 7 | **Generate local CDNI certificate** (:79-85) | `bash scripts/generate-local-cdni-cert.sh` + `test -s` dos dois arquivos | o `LocalHttpsServer`/`TlsContextFactory` leem `assets/cdn_local.p12` (senha pública `wzm-offline-local`, SANs `prod.cdni.callofduty.com`, `*.cdni.callofduty.com`); o `CertificateAssetTest` depende deles |
| 8 | Run unit tests (:87-98) | `./gradlew :app:testDebugUnitTest --stacktrace 2>&1 \| tee ../gradle-test.log`; guarda `PIPESTATUS[0]` em `TEST_EXIT` via `$GITHUB_ENV`; `exit 0` | roda **antes** do APK; o `tee` + `PIPESTATUS` é para capturar o código real sem perder o log nem morrer no `pipefail` do shell do Actions |
| 9 | Anotações de falha (:100-118) | `grep` do log → `::error title=gradle-error-N::` | o log bruto do Actions nem sempre é legível de todo ambiente; a anotação aparece na API de checks |
| 10 | Falhas de teste como check-run (:120-124) | `scripts/ci-report-test-failures.py --dir android/app/build/test-results/testDebugUnitTest` | idem, via XML |
| 11 | Contagem de testes (:128-143) | soma os `tests/failures/errors/skipped` dos XMLs → `::notice title=testes-jvm::` | evidência numérica de que a suíte rodou neste build (último estado conhecido: **262 testes, 0 falhas**) |
| 12 | Upload de logs em falha (:145-155) | `gradle-test.log`, `reports/`, `test-results/` (7 dias) | diagnóstico sem reconstruir |
| 13 | **Fail if unit tests failed** (:157-161) | `exit 1` se `TEST_EXIT != 0` | nenhum APK nasce de uma suíte vermelha |
| 14 | **Build debug APK** (:163-166) | `./gradlew assembleDebug --stacktrace` | única tarefa que produz APK |
| 15 | Staging + metadados (:168-185) | copia `app/build/outputs/apk/debug/app-debug.apk` para `artifact-staging/`, gera `app-debug.apk.sha256`, publica outputs `apk_sha`/`apk_size`/`apk_file`; `test -f` antes | garante que o que é publicado é **exatamente** o que foi hashado; o último build conhecido: 15 732 644 B, `sha256 269d961cd177…bd2f` |
| 16 | **Guard — só o NOSSO APK** (:187-209) | `aapt2 dump badging` ⇒ o pacote tem de começar com `com.wzm.launcher`; `unzip -l` ⇒ **falha** se houver `.obb/.pak/.ucas/.utoc/.shard/.xapk` dentro do APK | impede publicar APK errado e empacotar asset proprietário |
| 17 | Upload artifact (:211-219) | `wzm-offline-launcher-debug` com `app-debug.apk` + `.sha256` na **raiz**, `if-no-files-found: error`, retenção 30 dias | contrato de layout do artifact |
| 18 | Guard do repositório (:221-230) | `find . -name *.apk/*.xapk/*.shard` fora de build/staging ⇒ erro | a mesma proibição, no lado do repo |
| 19 | Job `verify-artifact` (:232-282) | **re-baixa** o artifact publicado; exige exatamente 2 arquivos; recomputa `sha256sum` e compara com o `.sha256` **e** com o output do build; compara tamanho; escreve a tabela no *step summary* | nada disso é decorativo: o artifact publicado é o entregável; o build só é considerado bom quando o que foi baixado de volta bate byte por byte |

Job `build` do workflow `build` (:1-82) roda `npm ci || npm install`, `npx tsc --noEmit` nos projetos Node (`warzone-offline/server`, `warzone-offline/launcher`), `bash scripts/check-docs.sh`, publica o artifact **técnico** `warzone-offline-M1` (só `src/`, um scanner e `docs/`) e verifica que ele não contém APK; no fim **chama `android-build.yml` por `workflow_call`** (`build.yml:81-82`) — por isso o APK aparece no mesmo run. `test.yml` roda os testes Node (14+12), os self-tests de `tls-trust-scan.py`, `update-check-scan.py`, `dex-scan.py` e do par servidor-local/DNS, e `check-docs.sh`. `release.yml` **não** constrói o APK do launcher: empacota o `server` Node em `warzone-offline-M0`.

---

## 3. O que sai, e as duas consequências práticas

| Item | Valor |
|---|---|
| Caminho no runner | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Caminho no artifact | `app-debug.apk` (raiz) + `app-debug.apk.sha256` (raiz) |
| `applicationId` efetivo | `com.wzm.launcher.debug` (sufixo `debug`) |
| Assinatura | **keystore debug default do AGP** (`$HOME/.android/debug.keystore`, alias `androiddebugkey`, senha `android`), criado automaticamente se faltar — `PROBABLE` (documentação do AGP: [app-signing#debug-mode](https://developer.android.com/studio/publish/app-signing)); **0** `signingConfig` no `build.gradle.kts` |
| Conteúdo extra | os dois assets de certificado gerados no passo 7 |
| Tamanho real observado | artifact `wzm-offline-launcher-debug` = **15 731 906 B** no run `37657231158` (commit `9fe86ff`, docs-only) contra **15 732 644 B** no run anterior — **o `.apk` não é bit-a-bit reproduzível entre runs**: muda a keystore de debug, muda o timestamp das assinaturas em `META-INF`, muda o par CA gerado no passo 7 |

**Consequência 1 — reinstalação.** Num runner efêmero o `debug.keystore` nasce de novo a cada run, então **cada APK publicado tem assinatura diferente** e o `adb install -r` sobre uma instalação anterior falha (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). É preciso **desinstalar** o app do launcher antes de instalar um build novo (ou versionar uma keystore de debug em secret, o que este repo não faz). `PROBABLE`, e é o comportamento clássico de CI sem keystore fixa.

**Consequência 2 — a CA do APK é descartável.** A cada run o passo 7 gera um par CA/folha novo (o `.gitignore` bloqueia `*.pem`/`*.p12`). A **única** CA que combina com um APK é a que está **dentro daquele** APK: `unzip -p app-debug.apk assets/cdn_local_ca.pem` e comparar por *fingerprint* SHA-256. Já registrei isto em M6.2 §4.1; a medição D1 depende disso. `VERIFIED` pelo script.

Release: `assembleRelease` produziria `app-release-unsigned.apk` (sem `signingConfig`, sem `minify`) — **não é** parte do pipeline; o workflow de `release` é do server Node. `VERIFIED`.

---

## 4. Pegar o APK publicado (do CI)

```bash
# 1) descobrir o run e o artifact (evita o download cego — a quota de storage já estourou aqui)
gh run list --workflow android-build -L 5
gh api repos/$OWNER/$REPO/actions/runs/<RUN_ID>/artifacts        # nome, tamanho, id
gh api repos/$OWNER/$REPO/actions/artifacts/<ARTIFACT_ID>/zip > /tmp/apk.zip   # ou pela URL do browser

# 2) abrir, conferir layout e hash
unzip -o /tmp/apk.zip -d /tmp/apk
cd /tmp/apk && sha256sum -c app-debug.apk.sha256
unzip -l app-debug.apk | grep -E 'assets/cdn_local'      # os dois assets de laboratório

# 3) instalar (lembre: assinatura muda a cada build)
adb uninstall com.wzm.launcher.debug || true
adb install -r app-debug.apk
```

O job `verify-artifact` já fez (2) no servidor; refazer localmente é para o caso de você baixar por URL/CLI com redirecionamento.

---

## 5. Build local, completo (6 passos)

Pré-requisitos: **JDK 17**, **Android SDK** com `platforms;android-34` + `build-tools;34.0.0`, `openssl` no PATH, e um Gradle 8.7 **fora do wrapper** (por causa do jar de 0 bytes — `PROBABLE`/`VERIFIED` pela árvore).

```bash
cd <repo>/android
# 1) dizer ao AGP onde está a SDK (não versionado)
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2) certificado local (idêntico ao passo 7 do CI; não versiona nada)
bash ../scripts/generate-local-cdni-cert.sh

# 3) wrapper: o jar versionado é vazio ⇒ regenere com um gradle do sistema
gradle wrapper --gradle-version 8.7        # exige: gradle 8.7 instalado
# alternativa: copiar gradle-8.7/lib/plugins/gradle-wrapper-*.jar? NÃO — use o `gradle wrapper`,
#            ou aponte para uma distribuição: https://services.gradle.org/distributions/gradle-8.7-bin.zip

# 4) testes JVM (262 no estado atual) - o CI exige verde antes de gerar APK
./gradlew :app:testDebugUnitTest --stacktrace

# 5) APK
./gradlew assembleDebug --stacktrace

# 6) hash idêntico ao do CI (mas NUNCA o mesmo valor: assinatura/keystore/time mudam)
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

O que **não** é igual ao CI: a chave de assinatura (keystore da sua máquina), o timestamp do `META-INF`, e portanto o SHA-256 do arquivo. Comparar hash local × hash do artifact é **inútil** — comparar *layout e conteúdo* (`unzip -l`) é o que faz sentido. `PROBABLE` para o caso local; a **variação entre runs do CI já é `VERIFIED`** (15 731 906 × 15 732 644 bytes com a mesma árvore-fonte).

---

## 6. Intermediários úteis quando algo quebra

`android/app/build/` → `intermediates/` (packaged manifest em `intermediates/merged_manifest*/`, `packaged_res/`, `compiled_local_resources`), `test-results/testDebugUnitTest/*.xml` (o que as anotações dos passos 10-11 leem), `reports/tests/testDebugUnitTest/index.html`, e `outputs/apk/debug/`. Como `org.gradle.caching=true`, um passo `assembleDebug` pode **não recompilar** nada: para depuração honesta, `./gradlew clean :app:testDebugUnitTest assembleDebug`.

Ordem de falha mais frequente, da mais barata para a mais cara: `preflight` (passo 6) → wrapper (5) → `sdk.dir` ausente → asset de certificado ausente (7) → teste vermelho (8/13) → APK ausente (15) → pacote inesperado/asset proprietário (16) → layout de artifact (19).

---

## 7. Conformidade

Nada aqui altera APK do WZM, `libgame.so`, TLS/pinning, TUN, DNS ou confiança do jogo: o documento descreve **o build do nosso launcher**. Nenhum `.apk`, `.p12`, `.pem`, `.obb` ou `.shard` foi commitado (guardas dos passos 16 e 18 continuam válidas); `analysis/wzm310/libgame.so` permanece fora do Git. Logging do pipeline continua sem corpos HTTP/tokens (os passos publicam apenas nome, tamanho e hash). Nenhum código Kotlin/Gradle foi modificado nesta rodada — o único arquivo de script tocado foi `scripts/check-docs.sh`, que é o portão de documentação (mesma convenção dos milestones M4.6→M5.0).
