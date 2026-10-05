/**
 * bootstrap — servidor local para CDN bootstrap chain WZM 3.10.0
 * VERIFIED endpoints:
 *   GET https://prod.cdni.callofduty.com/manifest/build-selector-103.js
 *   GET https://prod.cdni.callofduty.com/static/web/index.html (offline page)
 *
 * Este módulo NÃO acessa Activision. Serve stubs locais que permitem
 * WebView sair do estado "Estes servidores estão permanentemente fora de serviço."
 *
 * Uso offline:
 *   hosts: 127.0.0.1 prod.cdni.callofduty.com  (via launcher/hosts-patch)
 *   server: BootstrapServer em 127.0.0.1:8080
 *   WebView faz GET /manifest/build-selector-103.js → recebe JS local que NÃO redireciona para offline,
 *   mas injeta window.WZM_OFFLINE = true ou similar.
 *
 * Tudo marcado como local stub — não propriedade Activision.
 */

import http from 'node:http';

export const BOOTSTRAP_HOST = 'prod.cdni.callofduty.com';
export const BUILD_SELECTOR_PATH = '/manifest/build-selector-103.js';
export const OFFLINE_PAGE_PATH = '/static/web/index.html';

// Stubs locais — conteúdo mínimo, não proprietário
export function buildSelectorLocalStub(): string {
  // Este JS é servido localmente no lugar do CDN real.
  // Ele evita o redirect para offline e sinaliza modo offline.
  // Não copia código Activision; é stub funcional para investigação.
  return `// WZM Offline Bootstrap — local stub for ${BUILD_SELECTOR_PATH}
// VERIFIED chain: file:///android_asset/bootstrap/index.html -> GET ${BUILD_SELECTOR_PATH}
// Original CDN now serves offline page. This stub keeps WebView alive locally.
(function(){
  console.log('[WZM-OFFLINE] build-selector local stub loaded');
  window.WZM_OFFLINE = true;
  window.WZM_OFFLINE_BUILD = '103';
  window.pre_login_GVS = { enabled: true, source: 'local', version: '103' };
  window.region_detection_option = { mode: 'local', region: 'offline' };
  // Não redirecionar para ${OFFLINE_PAGE_PATH} — manter no bootstrap
  if (window.WBootstrap && window.WBootstrap.onBuildSelectorLoaded) {
    window.WBootstrap.onBuildSelectorLoaded({ offline: true });
  }
})();`;
}

export function offlinePageLocalStub(): string {
  // Substitui a página de shutdown por uma página local que explica modo offline
  // Não copia HTML Activision; é stub para teste.
  return `<!doctype html>
<html lang="pt-BR">
<head><meta charset="utf-8"><title>WZM Offline — Local</title></head>
<body>
<h1>Modo Offline Local — WZM 3.10.0</h1>
<p>Este conteúdo é servido por <code>BootstrapServer</code> em localhost, não pelo CDN <code>${BOOTSTRAP_HOST}</code>.</p>
<p>Original CDN servia: "Estes servidores estão permanentemente fora de serviço."</p>
<p>Stub local mantém WebView viva para investigação de GVS/permissões sem acessar Activision.</p>
<script>window.WZM_OFFLINE_PAGE = 'local';</script>
</body>
</html>`;
}

export interface BootstrapServerOpts {
  host: string;
  port: number;
}

export class BootstrapServer {
  private server: http.Server | null = null;
  private hits: Array<{ ts: string; method: string; path: string; host: string }> = [];

  constructor(private opts: BootstrapServerOpts) {}

  listen(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.server = http.createServer((req, res) => {
        const url = req.url ?? '/';
        const host = (req.headers.host as string) ?? '';
        const path = url.split('?')[0];

        // Log hits for test verification
        this.hits.push({ ts: new Date().toISOString(), method: req.method ?? 'GET', path, host });

        if (path === '/health') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ status: 'ok', milestone: 'M2-bootstrap', host: BOOTSTRAP_HOST }));
          return;
        }
        if (path === BUILD_SELECTOR_PATH) {
          res.writeHead(200, { 'Content-Type': 'application/javascript', 'Cache-Control': 'no-store' });
          res.end(buildSelectorLocalStub());
          return;
        }
        if (path === OFFLINE_PAGE_PATH) {
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
          res.end(offlinePageLocalStub());
          return;
        }
        if (path === '/__hits') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ count: this.hits.length, hits: this.hits }, null, 2));
          return;
        }
        if (path === '/__reset') {
          this.hits = [];
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }

        // Fallback: 404 para paths não mapeados — prova que só os dois VERIFIED são servidos
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'not found', path, known: [BUILD_SELECTOR_PATH, OFFLINE_PAGE_PATH] }));
      });
      this.server.on('error', reject);
      this.server.listen(this.opts.port, this.opts.host, () => resolve());
    });
  }

  close(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (!this.server) return resolve();
      this.server.close((err) => (err ? reject(err) : resolve()));
    });
  }

  getHits() {
    return [...this.hits];
  }

  reset() {
    this.hits = [];
  }
}
