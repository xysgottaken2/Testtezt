#!/usr/bin/env node
/**
 * Bootstrap CLI — M2.1 legacy local test harness.
 * The listener is restricted to loopback; remote/hotspot binds are not supported.
 * Usage: node --loader ts-node/esm src/bootstrap/cli.ts --host 127.0.0.1 --port 18081
 */
import { requireLoopbackHost } from '../config/index.js';
import { BootstrapServer } from './index.js';

const args = process.argv.slice(2);
let host = '127.0.0.1';
let port = 18081;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--host') {
    const candidate = args[++i];
    if (!candidate) throw new Error('--host requires a loopback IP address');
    host = requireLoopbackHost(candidate);
  } else if (args[i] === '--port') {
    const value = args[++i];
    if (!value) throw new Error('--port requires a numeric value');
    port = parseInt(value, 10);
  }
}

const server = new BootstrapServer({ host, port });
await server.listen();
console.log(`[bootstrap] listening on http://${host}:${port}`);
console.log(`[bootstrap] VERIFIED endpoints: GET /manifest/build-selector-103.js + GET /static/web/index.html (Host: prod.cdni.callofduty.com)`);
console.log(`[bootstrap] __hits at http://${host}:${port}/__hits`);
console.log(`[bootstrap] sem Activision, sem auth/Demonware`);
process.on('SIGINT', async () => { await server.close(); process.exit(0); });
process.on('SIGTERM', async () => { await server.close(); process.exit(0); });
