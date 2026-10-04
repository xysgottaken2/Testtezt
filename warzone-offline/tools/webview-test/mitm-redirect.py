"""
mitm-redirect.py — fallback B para S23 sem root (Wi-Fi proxy manual)
Uso: mitmproxy --mode regular --listen-port 8080 --scripts mitm-redirect.py
S23: Wi-Fi WZM-Offline-Test → Proxy Manual 10.42.0.1:8080 + instalar mitmproxy CA
Este script NÃO acessa Activision: se host == prod.cdni.callofduty.com, retorna stub local;
senão, passa direto (flow.request -> server, sem tocar).
"""
from mitmproxy import http

# Stub local — mesmo conteúdo de BootstrapServer.buildSelectorLocalStub() (não proprietário)
LOCAL_JS = b"""// WZM Offline via mitmproxy — local stub
window.WZM_OFFLINE=true;window.pre_login_GVS={enabled:true,source:"local-mitm"};window.region_detection_option={mode:"local"};console.log("[WZM-OFFLINE] mitm stub");"""

LOCAL_HTML = b"""<!doctype html><title>WZM Offline — mitm</title><h1>Modo Offline Local — mitm</h1><p>via mitmproxy, host prod.cdni.callofduty.com interceptado localmente</p>"""

TARGET_HOST = "prod.cdni.callofduty.com"

def request(flow: http.HTTPFlow) -> None:
    if flow.request.pretty_host == TARGET_HOST:
        path = flow.request.path.split("?")[0]
        if path == "/manifest/build-selector-103.js":
            flow.response = http.Response.make(200, LOCAL_JS, {"Content-Type": "application/javascript", "Cache-Control": "no-store"})
        elif path == "/static/web/index.html":
            flow.response = http.Response.make(200, LOCAL_HTML, {"Content-Type": "text/html; charset=utf-8"})
        else:
            # 404 para paths desconhecidos — prova que só VERIFIED são servidos
            flow.response = http.Response.make(404, b'{"error":"not found","known":["/manifest/build-selector-103.js","/static/web/index.html"]}', {"Content-Type": "application/json"})
    # else: passa direto — não toca Activision além do necessário para teste
