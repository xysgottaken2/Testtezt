/**
 * capture — servidor HTTP genérico de captura para M1
 * NÃO assume endpoints WZM. Loga qualquer request (Host, URL, headers)
 * para descobrir o que o cliente realmente tenta resolver.
 *
 * Metodologia: genérico + /__capture para inspeção (sem hipótese).
 */

import http from 'node:http';

export interface CapturedRequest {
  id: number;
  ts: string;
  method: string;
  url: string;
  host: string; // header Host
  remoteAddr: string;
  headers: Record<string, string>;
}

export class CaptureServer {
  private server: http.Server | null = null;
  private captured: CapturedRequest[] = [];
  private nextId = 1;

  constructor(private opts: { host: string; port: number }) {}

  listen(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.server = http.createServer((req, res) => {
        const url = req.url ?? '/';
        const host = (req.headers.host as string) ?? '';

        // Rotas de inspeção (não são endpoints WZM)
        if (url === '/__capture' && req.method === 'GET') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ count: this.captured.length, captured: this.captured }, null, 2));
          return;
        }
        if (url === '/__reset' && req.method === 'POST') {
          this.captured = [];
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true }));
          return;
        }
        if (url === '/health' && req.method === 'GET') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ status: 'ok', milestone: 'M1-capture', captured: this.captured.length }));
          return;
        }

        // Captura genérica: loga tudo sem assumir
        const entry: CapturedRequest = {
          id: this.nextId++,
          ts: new Date().toISOString(),
          method: req.method ?? 'GET',
          url,
          host,
          remoteAddr: req.socket.remoteAddress ?? '',
          headers: Object.fromEntries(
            Object.entries(req.headers).map(([k, v]) => [k, Array.isArray(v) ? v.join(', ') : String(v ?? '')])
          ),
        };
        this.captured.push(entry);

        // Drain body if any
        req.on('data', () => {});
        req.on('end', () => {
          // Resposta genérica 200 — não finge ser WZM, apenas prova que localhost foi atingido
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, note: 'M1 capture — generic 200, not WARZONE_VERIFIED', capturedId: entry.id }));
        });
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

  getCaptured(): CapturedRequest[] {
    return [...this.captured];
  }

  reset() {
    this.captured = [];
  }

  get address(): { host: string; port: number } {
    return { host: this.opts.host, port: this.opts.port };
  }
}
