import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
// @ts-ignore — JS tool without strict types, see endpoint-scanner.d.ts
import { scanPaths } from '../../tools/apk-analysis/endpoint-scanner.js';

describe('endpoint-scanner (M1 discovery tool)', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wzm-scan-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('finds urls and keywords in synthetic jadx output', () => {
    fs.writeFileSync(path.join(tmpDir, 'Foo.java'), `
      public class Foo {
        String a = "https://cdn.warzone.example/manifest.json";
        String b = "wss://match.example.com/lobby";
        String c = "demonware.net";
      }
    `);
    fs.writeFileSync(path.join(tmpDir, 'Bar.kt'), `val x = "https://activision.com/auth"`);
    const result = scanPaths([tmpDir]);
    expect(result.scannedFiles).toBe(2);
    const patterns = result.findings.map((f) => f.pattern);
    expect(patterns).toContain('https_url');
    expect(patterns).toContain('demonware');
    expect(patterns).toContain('activision');
  });

  it('deduplicates findings', () => {
    fs.writeFileSync(path.join(tmpDir, 'A.java'), `String s = "https://cdn.example.com/a";`);
    fs.writeFileSync(path.join(tmpDir, 'B.java'), `String s = "https://cdn.example.com/a";`);
    const result = scanPaths([tmpDir]);
    // same URL in two files => one finding per file (deduplicated per-file), plus cdn_keyword etc.
    // At least 2 findings (one per file for https_url), but not duplicated within same file
    const httpsFindings = result.findings.filter((f) => f.pattern === 'https_url');
    expect(httpsFindings.length).toBe(2);
    // No duplicate of same match in same file
    const keys = new Set(result.findings.map((f) => `${f.pattern}:${f.match}:${f.file}`));
    expect(keys.size).toBe(result.findings.length);
  });

  it('handles empty directory', () => {
    const result = scanPaths([tmpDir]);
    expect(result.scannedFiles).toBe(0);
    expect(result.findings.length).toBe(0);
  });

  it('handles nonexistent path gracefully', () => {
    const result = scanPaths(['/nonexistent/path/xyz']);
    expect(result.scannedFiles).toBe(0);
    expect(result.findings.length).toBe(0);
  });

  it('detects .so-like file', () => {
    const soPath = path.join(tmpDir, 'libgame.so');
    fs.writeFileSync(soPath, 'https://cdn.warzone.example/shard/abc.shard demonware.net :3074');
    const result = scanPaths([tmpDir]);
    // .so is included via walk (no ext check for .so in tools? our scanner checks .so)
    // Our scanner walk includes .so, but we wrote file with .so ext -> should be scanned
    expect(result.findings.length).toBeGreaterThan(0);
    const matches = result.findings.map((f) => f.match);
    expect(matches.join(' ')).toContain('cdn.warzone.example');
  });
});
