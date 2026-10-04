#!/usr/bin/env node
/**
 * categorize-endpoints.js — classifica endpoints.json em categorias do escopo.
 * Categorias (rule 10): auth, matchmaking, demonware, manifest/cdn, telemetry, game-server, other
 * Nunca inventa: apenas classifica o que já foi encontrado.
 * Uso:
 *   node categorize-endpoints.js /tmp/wzm/endpoints.json > /tmp/wzm/endpoints-by-category.json
 *   node categorize-endpoints.js --help
 */

import fs from 'node:fs';

const CATEGORY_RULES = [
  { category: 'auth', test: (f) => /activision|callofduty|auth|login|token/i.test(f.match) || f.pattern === 'activision' || f.pattern === 'callofduty' },
  { category: 'matchmaking', test: (f) => /matchmaking|lobby|session|match/i.test(f.match) },
  { category: 'demonware', test: (f) => f.pattern === 'demonware' || /demonware|stun\./i.test(f.match) || f.pattern === 'port_3074' || f.pattern === 'stun_keyword' },
  { category: 'manifest_cdn', test: (f) => f.pattern === 'manifest' || f.pattern === 'shard' || /manifest|shard|cdn\./i.test(f.match) || f.pattern === 'cdn_keyword' },
  { category: 'telemetry', test: (f) => f.pattern === 'analytic_keyword' || /analytic|telemetry|crash|tracking/i.test(f.match) },
  { category: 'game_server', test: (f) => /DemonwarePortMapping|game.*server|udp.*port|27000|3074/i.test(f.match) },
];

export function categorize(findings) {
  const categorized = { auth: [], matchmaking: [], demonware: [], manifest_cdn: [], telemetry: [], game_server: [], other: [] };
  for (const f of findings) {
    let placed = false;
    for (const rule of CATEGORY_RULES) {
      if (rule.test(f)) {
        categorized[rule.category].push(f);
        placed = true;
        break;
      }
    }
    if (!placed) categorized.other.push(f);
  }
  return categorized;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  if (process.argv.includes('--help')) {
    console.log(`Usage: node categorize-endpoints.js <endpoints.json> [--pretty]
Input: JSON from endpoint-scanner.js {findings: [...]}
Output: {auth, matchmaking, demonware, manifest_cdn, telemetry, game_server, other}`);
    process.exit(0);
  }
  const inputPath = process.argv[2];
  if (!inputPath) {
    console.error('Missing input: endpoints.json');
    process.exit(1);
  }
  const data = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
  const findings = data.findings ?? data;
  const cat = categorize(findings);
  console.log(JSON.stringify({
    source: inputPath,
    scannedFiles: data.scannedFiles,
    counts: Object.fromEntries(Object.entries(cat).map(([k, v]) => [k, v.length])),
    ...cat
  }, null, 2));
}
