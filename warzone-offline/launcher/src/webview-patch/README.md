# webview-patch — Interceptação WebView sem root (metodologia)

> Para WZM 3.10.0 WebView bootstrap chain: `file:///android_asset/bootstrap/index.html` → `prod.cdni...`

## Opções sem precisar de `hosts` com root

### 1. Hosts (requer root / adb remount) — já VERIFIED em M1

```
127.0.0.1 prod.cdni.callofduty.com  # via warzone-offline/launcher/src/hosts-patch
```

Testado com `CaptureServer` / `BootstrapServer` em `127.0.0.1:8080`.

### 2. WebViewClient.shouldInterceptRequest (sem root, preferível)

Se você controla o wrapper da MainActivity (ex.: Frida hook em `loadWeb`), pode interceptar:

```java
webView.setWebViewClient(new WebViewClient() {
  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
    if (req.getUrl().getHost().equals("prod.cdni.callofduty.com")) {
      if (req.getUrl().getPath().equals("/manifest/build-selector-103.js")) {
        return new WebResourceResponse("application/javascript", "utf-8",
          getAssets().open("bootstrap/local-build-selector-103.js"));
      }
      if (req.getUrl().getPath().equals("/static/web/index.html")) {
        return new WebResourceResponse("text/html", "utf-8",
          getAssets().open("bootstrap/local-offline.html"));
      }
    }
    return super.shouldInterceptRequest(view, req);
  }
});
```

Isso evita alterar `/etc/hosts` e funciona sem root, apenas hookando `MainActivity.loadWeb`.

### 3. Frida hook de `loadUrl` (quando sem código)

```js
// frida -U -f com.activision.callofduty.warzone -l tools/frida/webview-intercept.js
Java.perform(() => {
  const WebView = Java.use("android.webkit.WebView");
  WebView.loadUrl.overload("java.lang.String").implementation = function(url) {
    if (url.includes("prod.cdni.callofduty.com")) {
      url = url.replace("https://prod.cdni.callofduty.com", "http://127.0.0.1:8080");
    }
    return this.loadUrl(url);
  };
});
```

> **Sem copiar HTML/JS proprietário:** todos os `local-*.js/html` são stubs criados em `server/src/bootstrap` (ver `buildSelectorLocalStub`, `offlinePageLocalStub`).

## Teste local sem device

BootstrapServer já prova lógica: `curl http://127.0.0.1:18081/manifest/build-selector-103.js -H "Host: prod.cdni.callofduty.com"` → stub local com `WZM_OFFLINE`.

Para device, combine com `hosts-patch` ou `shouldInterceptRequest`.

