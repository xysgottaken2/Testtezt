#!/usr/bin/env node
/**
 * bootstrap CLI — M2.1
 * Inicia BootstrapServer para teste S23 sem root.
 * Uso: node --loader ts-node/esm src/bootstrap/cli.ts --host 0.0.0.0 --port 18081
 */
import { BootstrapServer } from './index.js';

const args = process.argv.slice(2);
let host = '0.0.0.0';
let port = 18081;
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--host') host = args[++i];
  if (args[i] === '--port') port = parseInt(args[++i], 10);
}

const server = new BootstrapServer({ host, port });
await server.listen();
console.log(`[bootstrap] listening on http://${host}:${port}`);
console.log(`[bootstrap] VERIFIED endpoints: GET /manifest/build-selector-103.js + GET /static/web/index.html (Host: prod.cdni.callofduty.com)`);
console.log(`[bootstrap] __hits at http://${host}:${port}/__hits`);
console.log(`[bootstrap] sem Activision, sem auth/Demonware`);
process.on('SIGINT', async () => { await server.close(); process.exit(0); });
process.on('SIGTERM', async () => { await server.close(); process.exit(0); });
