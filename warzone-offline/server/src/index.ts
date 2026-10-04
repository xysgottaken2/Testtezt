/**
 * Warzone Mobile Offline Server — M0 skeleton
 * Objetivo M1: Cliente → localhost → resposta válida
 * Hoje: apenas bootstrap + HTTP mock que responde 200 + stubs de AUTH/LSG
 */
import http from 'node:http';
import { loadConfig } from './config/index.js';

const config = loadConfig();
console.log('[warzone-offline] config:', config);
console.log('[warzone-offline] status: M0 Research — servidor skeleton, não autoritativo ainda');
console.log('[warzone-offline] VERIFIED: IW 9.0 MGL | PROBABLE: Demonware | UNKNOWN: endpoints reais');

// --- HTTP mock (CDN / telemetry / health) ---
const server = http.createServer((req, res) => {
  const url = req.url ?? '/';
  console.log(`[http] ${req.method} ${url} from ${req.socket.remoteAddress}`);

  // Health check
  if (url === '/health' || url === '/') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', milestone: 'M0', config: { map: config.map, gameMode: config.gameMode } }));
    return;
  }

  // Telemetry stub — sempre 200 vazio (DBD_REFERENCE pattern)
  if (url.includes('analytic') || url.includes('telemetry')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({}));
    return;
  }

  // CDN manifest mock — [HYPOTHESIS] endpoint a descobrir
  if (url.includes('manifest')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ version: config.gameVersion, shards: [], note: 'M0 mock — manifest real a descobrir via APK' }));
    return;
  }

  // Auth mock — [HYPOTHESIS]
  if (url.includes('auth') || url.includes('profile')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ id: 'local-player-001', name: 'Player', level: 1, note: 'M0 mock — descobrir formato real' }));
    return;
  }

  // Fallback: 200 para que cliente não trave em M1
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: true, path: url, milestone: 'M0-mock' }));
});

server.listen(config.httpPort, config.host, () => {
  console.log(`[http] listening on http://${config.host}:${config.httpPort} — health at /health`);
  console.log(`[warzone-offline] NEXT: M1 — testar cliente contra localhost:${config.httpPort} via hosts override`);
});

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('[warzone-offline] SIGINT — shutting down');
  server.close(() => process.exit(0));
});
