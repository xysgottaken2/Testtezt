# APK Patch Diff — O que seria alterado (M2.1-D, protótipo)

> **Status:** PROTÓTIPO DOCUMENTADO, NÃO APLICADO.  
> **Base:** WZM 3.10.0 APK (fora do repo, `/tmp/wzm/warzone-310.apk`).  
> **Ferramenta:** `apktool d` → editar → `apktool b` → `apksigner`.

---

## 1. Arquivos tocados (3 arquivos + 1 novo)

### 1.1. `assets/bootstrap/index.html` — 1 linha

**Original (3.10.0):**
```html
<script src="https://prod.cdni.callofduty.com/manifest/build-selector-103.js"></script>
```

**Patch D1 (localhost via HTTP, sem TLS):**
```html
<script src="http://10.42.0.1:18081/manifest/build-selector-103.js"></script>
<!-- localhost BootstrapServer, sem Activision, sem pinning -->
```

**Por que:** evita `hosts` / DNS / TLS / pinning. WebView carrega direto do stub local. `10.42.0.1` é IP do hotspot PC (configurável em `local.properties`).

**Alternativa D1-file (sem rede):**
```html
<script src="file:///android_asset/bootstrap/local-build-selector-103.js"></script>
```
Colocar stub em `assets/bootstrap/local-build-selector-103.js` (copiar `BootstrapServer.buildSelectorLocalStub()` para arquivo).

**Risco:** Mínimo — só asset.

---

### 1.2. `AndroidManifest.xml` — 2 linhas

**Original tem:**
```xml
<application ... >
```

**Patch:**
```xml
<application android:usesCleartextTraffic="true" android:networkSecurityConfig="@xml/network_security_config" ... >
```

E permissão já existe (`INTERNET`), não precisa adicionar.

**Risco:** Médio — permite `http://` cleartext, mas só para `10.42.0.1` no config abaixo. Não abre para todo http se config filtrar.

---

### 1.3. `res/xml/network_security_config.xml` — arquivo novo, 12 linhas

**Novo arquivo:**
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.42.0.1</domain>
    <domain includeSubdomains="false">192.168.12.1</domain>
    <domain includeSubdomains="false">localhost</domain>
  </domain-config>
  <!-- base-config mantém padrão (cleartext false) para todo o resto -->
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="system" />
      <certificates src="user" />
    </trust-anchors>
  </base-config>
</network-security-config>
```

**Risco:** Médio — só libera cleartext para IPs locais, mantém pinning para resto. Ainda pode precisar `<certificates src="user">` se testar mitmproxy (B).

---

### 1.4. `smali/com/activision/callofduty/warzone/MainActivity.smali` — ~15 linhas + nova classe (opcional, se asset patch não bastar)

**Objetivo:** injetar `WebViewClient` que intercepta `prod.cdni...` sem depender de asset.

**Diff (smali pseudo):**
```smali
# em loadWeb() antes de loadUrl
new-instance v0, Lcom/activision/LocalInterceptor;
invoke-direct {v0}, Lcom/activision/LocalInterceptor;-><init>()V
invoke-virtual {p0, v0}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V

# nova classe LocalInterceptor.smali:
.class public Lcom/activision/LocalInterceptor;
.super Landroid/webkit/WebViewClient;
.method public shouldInterceptRequest(...)Landroid/webkit/WebResourceResponse;
  # if (url.contains("prod.cdni.callofduty.com/manifest/build-selector-103.js"))
  #   return new WebResourceResponse("application/javascript","utf-8", getAssets().open("bootstrap/local-build-selector-103.js"))
  # idem para static/web/index.html
.end method
```

**Quando usar:** se `assets/bootstrap/index.html` for ofuscado ou carregado via `loadData` não editável, hook é mais confiável.

**Risco:** Médio-Alto — smali frágil entre builds (3.10.0 vs 3.3.4). Testar com `apktool` em branch separada.

---

## 2. Build e assinatura (não distribuir)

```bash
# 1. Decodificar (fora do repo)
apktool d /tmp/wzm/warzone-310.apk -o /tmp/wzm/warzone-310-patched

# 2. Aplicar diffs acima (editar com vim)

# 3. Recodificar
apktool b /tmp/wzm/warzone-310-patched -o /tmp/wzm/warzone-310-patched.apk

# 4. Keystore debug (gerar uma vez, não commitar)
keytool -genkey -v -keystore /tmp/wzm/debug.keystore -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass debug123 -dname "CN=WZM Offline"

# 5. Zipalign + apksigner
zipalign 4 /tmp/wzm/warzone-310-patched.apk /tmp/wzm/warzone-310-patched-aligned.apk
apksigner sign --ks /tmp/wzm/debug.keystore --ks-pass pass:debug123 --out /tmp/wzm/warzone-310-patched-signed.apk /tmp/wzm/warzone-310-patched-aligned.apk

# 6. Instalar em S23 (perde updates Play, sandbox)
adb install -r /tmp/wzm/warzone-310-patched-signed.apk
# Verificar: adb logcat | grep -i "WZM-OFFLINE\|WBootstrap" | tee /tmp/wzm/patched-logcat.txt
```

**Reversão:** `adb uninstall com.activision.callofduty.warzone` e reinstalar original (sem dados locais preservados).

---

## 3. Checklist antes de instalar (usuário deve aprovar cada item)

- [ ] Li `wzm-310-bootstrap.md` §6 e entendo que `build-selector-103.js` hoje só serve offline page
- [ ] Confirmo que A/B/C falharam com evidência (`logcat` com `ERR_CONNECTION_REFUSED` ou `SSLHandshake`)
- [ ] Tenho backup do save/data (`adb backup` se aplicável) e entendo que patch perde assinatura Play
- [ ] Entendo que patch é só para `bootstrap` (sem auth/Demonware), não libera itens/battle pass
- [ ] Entendo que rede ainda é só localhost, sem Activision

---

## 4. Artefatos que seriam commitados (se patch fosse aplicado e validado)

- `docs/protocol/cdni-build-selector-local.md` — ficha do endpoint local `http://10.42.0.1:18081/...` com `VERIFIED — via patch`
- `warzone-offline/patches/apk-webview-patch/local-build-selector-103.js` — stub (mesmo de `BootstrapServer`)
- `patches/apk-webview-patch/build.log` — `apktool b` log redigido, sem binário

Nunca commitado: `*.apk`, `*.keystore`, `*.aligned.apk`.

