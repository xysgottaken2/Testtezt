# WZM Permissions & GVS — Análise

> **Builds:** 3.3.4 estático (jadx) + 3.10.0 runtime (logcat)  
> **Classificação rigorosa por campo.**

---

## 1. Log runtime 3.10.0 (VERIFIED)

| Log | Status | Evidência |
|---|---|---|
| `Meta Fetch Success` | `VERIFIED — observado em execução` | Runtime log 3.10.0 (sem token) |
| `pre_login_GVS: absent` | `VERIFIED — observado` (ausência) | Log mostra fetch ok mas campo missing |
| `region_detection_option: absent` | `VERIFIED — observado` (ausência) | idem |

**O que é `Meta Fetch`:** provável GET a endpoint de meta/config (talvez `prod.cdni` ou `UNO` meta). Host exato ainda `UNKNOWN` — log não mostrou URL, só status. Precisa `pcap` com `Host` header para VERIFIED. Atual: `HYPOTHESIS` que meta vem do mesmo `prod.cdni.callofduty.com` ou de `https://.../api/meta` — **não inventar**.

**Por que GVS ausente bloqueia:** WebView bootstrap espera `pre_login_GVS` para decidir região/build. Sem ele, cai para fallback que hoje é a página de shutdown.

---

## 2. Strings classes.dex (VERIFIED em código)

Extraído via `jadx` em 3.3.4 + confirmado `strings` em 3.10.0 dex:

- `WBootstrap permissions required to execute were not granted` — `[VERIFIED — encontrado em código]`
- `isUsingPreLoginGVS` — `VERIFIED`
- `nativeBootstrapPermissionsResult` — `VERIFIED`
- `WBootstrap` classe — `VERIFIED` (nome reconstruído via Kotlin metadata, ver metodologia `android-reverse-engineering-skill`)

**Semântica (PROBABLE, não VERIFIED para fluxo):**

`WBootstrap` (WebView Bootstrap) checa permissões Android antes de executar GVS. `nativeBootstrapPermissionsResult` retorna `granted/denied`. Se `not granted`, bootstrap não executa GVS e WebView permanece em estado que leva ao `File:///android_asset/bootstrap/index.html` sem dados.

Permissões prováveis (HYPOTHESIS até `AndroidManifest.xml` verificado):
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (para shards)
- `ACCESS_FINE_LOCATION` (para region_detection)

Verificar em 3.3.4: `apktool d` → `AndroidManifest.xml` → `<uses-permission>`.

---

## 3. Hipótese de fluxo (marcada)

```
Meta Fetch Success (200) → parse JSON meta
  → if has pre_login_GVS && region_detection_option → load GVS (region/build selector verdadeiro)
  → else → fallback → load file:///android_asset/bootstrap/index.html → build-selector-103.js → offline page
```

Permissões (`WBootstrap`) são pré-condição para `isUsingPreLoginGVS` ser true.

**Não verificado:** estrutura JSON de meta, endpoint de meta, valores de `pre_login_GVS`. Não implementar mock até pcap/log com URL.

---

## 4. Experimentos para offline local

| # | Experimento | Método | Status |
|---|---|---|---|
| 1 | Ver `AndroidManifest.xml` permissões | `apktool d 3.3.4` → `AndroidManifest.xml` | NEEDS_RESEARCH (aguarda APK em /tmp) |
| 2 | `grep -r WBootstrap jadx-output` | `endpoint-scanner.js` + `grep` | DONE parcial (já temos 3 strings) |
| 3 | `adb logcat | grep -i WBootstrap` | device 3.10.0 | NEEDS_RESEARCH |
| 4 | Mock de meta local com `pre_login_GVS` presente | servidor local que responde a `*meta*` capturado | HYPOTHESIS até endpoint VERIFIED |

Para offline, o mais simples é **burlar permissões**: garantir `nativeBootstrapPermissionsResult == granted` via Frida ou garantindo permissões no device, e então mockar meta com GVS presente.

---

## 5. Fontes

- Runtime log 3.10.0: `Meta Fetch Success`, ausência campos — VERIFIED
- Dex: `WBootstrap ... not granted`, `isUsingPreLoginGVS`, `nativeBootstrapPermissionsResult` — VERIFIED (strings)
- Permissões listadas — HYPOTHESIS até manifest verificado

