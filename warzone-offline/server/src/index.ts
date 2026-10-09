/**
 * Warzone Mobile Offline Server — M1
 * M1 honesto: servidor de CAPTURA genérico, não assume endpoints WZM.
 * Todo endpoint WZM é [UNKNOWN] até APK/logcat. Este servidor apenas
 * prova que `hosts` -> localhost funciona e loga o que o cliente tentar.
 *
 * Uso:
 *   npm run dev  # sobe em HOST:HTTP_PORT, health em /health, captura em /__capture
 *
 * Metodologia DBD_REFERENCE para hosts: ver launcher/src/hosts-patch/
 * Próximo passo: quando APK disponível, preencher docs/protocol/*.md e então
 * promover um endpoint de UNKNOWN -> VERIFIED e trocar este fallback por resposta específica.
 */

import { loadConfig } from './config/index.js';
import { CaptureServer } from './capture/index.js';

const config = loadConfig();

console.log('[warzone-offline] config:', config);
console.log('[warzone-offline] M1 status: capture server — endpoints WZM = UNKNOWN (sem APK)');
console.log('[warzone-offline] VERIFIED: IW 9.0 MGL | PROBABLE: Demonware | UNKNOWN: endpoints/portas/pinning');
console.log(`[warzone-offline] Metodologia hosts-patch VERIFIED (DbD REFERENCE) — ver docs/research/m1-endpoint-discovery.md`);
console.log(`[warzone-offline] Para testar: curl http://127.0.0.1:${config.httpPort}/health  e  curl http://127.0.0.1:${config.httpPort}/__capture`);

const capture = new CaptureServer({ host: config.host, port: config.httpPort });

await capture.listen();
console.log(`[capture] listening on http://${config.host}:${config.httpPort} — health at /health, capture at /__capture`);
console.log(`[capture] NEXT M1: descobrir domínio real via endpoint-scanner.js + logcat, então hosts-patch -> localhost:${config.httpPort}`);

process.on('SIGINT', async () => {
  console.log('[warzone-offline] SIGINT — shutting down');
  await capture.close();
  process.exit(0);
});
process.on('SIGTERM', async () => {
  await capture.close();
  process.exit(0);
});
