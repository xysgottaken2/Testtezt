#!/usr/bin/env node
/**
 * endpoint-scanner.js — M1 tooling sem hipótese
 * Varre pastas jadx-output / lib por padrões de URL/host.
 * Não assume lista WZM; emite JSON com SOURCE/EVIDENCE/CONFIDENCE.
 *
 * Uso:
 *   node endpoint-scanner.js /tmp/wzm/jadx-output /tmp/wzm/apktool-output/lib
 *   node endpoint-scanner.js --test-fixture  # self-test
 */

import fs from 'node:fs';
import path from 'node:path';

const PATTERNS = [
  { name: 'https_url', re: /https:\/\/[a-z0-9.-]+\.[a-z]{2,}[^\s"'`<\]]*/gi, confidence: 'PROBABLE' },
  { name: 'wss_url', re: /wss:\/\/[a-z0-9.-]+\.[a-z]{2,}[^\s"'`<\]]*/gi, confidence: 'PROBABLE' },
  { name: 'http_url', re: /http:\/\/[a-z0-9.-]+\.[a-z]{2,}[^\s"'`<\]]*/gi, confidence: 'PROBABLE' },
  { name: 'demonware', re: /demonware\.net/gi, confidence: 'PROBABLE' },
  { name: 'activision', re: /activision\.com/gi, confidence: 'PROBABLE' },
  { name: 'callofduty', re: /callofduty\.com/gi, confidence: 'PROBABLE' },
  { name: 'bhvronline', re: /bhvronline\.com/gi, confidence: 'DBD_REFERENCE' },
  { name: 'cdn_keyword', re: /\bcdn\.[a-z0-9.-]+\b/gi, confidence: 'HYPOTHESIS' },
  { name: 'analytic_keyword', re: /analytic\.[a-z0-9.-]+\b/gi, confidence: 'HYPOTHESIS' },
  { name: 'stun_keyword', re: /\bstun\.[a-z0-9.-]+\b/gi, confidence: 'HYPOTHESIS' },
  { name: 'port_3074', re: /:3074\b/g, confidence: 'HYPOTHESIS' },
  { name: 'manifest', re: /manifest\.json/gi, confidence: 'PROBABLE' },
  { name: 'shard', re: /\.shard\b/gi, confidence: 'PROBABLE' },
];

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  const st = fs.statSync(dir);
  if (st.isFile()) {
    // se for arquivo único, filtrar por extensão relevante
    if (/\.(java|kt|xml|json|txt|so|js|ts)$/i.test(dir) || !path.extname(dir)) out.push(dir);
    return out;
  }
  for (const entry of fs.readdirSync(dir)) {
    const full = path.join(dir, entry);
    const s = fs.statSync(full);
    if (s.isDirectory()) walk(full, out);
    else if (/\.(java|kt|xml|json|txt|so|js|ts|smali)$/i.test(entry) || entry === 'AndroidManifest.xml') out.push(full);
  }
  return out;
}

export function scanPaths(roots) {
  const files = roots.flatMap((r) => walk(r));
  const findings = [];
  for (const file of files) {
    let content;
    try {
      content = fs.readFileSync(file, 'utf8');
    } catch {
      try {
        content = fs.readFileSync(file, 'latin1');
      } catch { continue; }
    }
    for (const pat of PATTERNS) {
      const re = new RegExp(pat.re.source, pat.re.flags);
      let m;
      while ((m = re.exec(content)) !== null) {
        // evitar matches gigantes
        const match = m[0].slice(0, 200);
        findings.push({
          pattern: pat.name,
          match,
          file: path.relative(process.cwd(), file),
          confidence: pat.confidence,
        });
        if (findings.length > 5000) break;
      }
      if (findings.length > 5000) break;
    }
    if (findings.length > 5000) break;
  }
  // deduplicate
  const seen = new Set();
  const deduped = [];
  for (const f of findings) {
    const key = `${f.pattern}:${f.match}:${f.file}`;
    if (!seen.has(key)) { seen.add(key); deduped.push(f); }
  }
  return { scannedFiles: files.length, findings: deduped };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const roots = process.argv.slice(2).filter((a) => !a.startsWith('--'));
  if (process.argv.includes('--help') || (roots.length === 0 && !process.argv.includes('--test-fixture'))) {
    console.log(`Usage: node endpoint-scanner.js <jadx-output> [lib-dir] [...]
Options:
  --json   output JSON (default)
  --help   this help`);
    process.exit(0);
  }
  if (roots.length === 0) {
    console.error('No roots given and not --test-fixture. Provide jadx-output or lib dir.');
    process.exit(1);
  }
  const result = scanPaths(roots);
  console.log(JSON.stringify({ roots, scannedFiles: result.scannedFiles, count: result.findings.length, findings: result.findings }, null, 2));
}
