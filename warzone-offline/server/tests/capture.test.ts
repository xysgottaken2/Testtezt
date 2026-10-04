import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { CaptureServer } from '../src/capture/index.js';

describe('CaptureServer (M1 generic)', () => {
  let server: CaptureServer;
  const host = '127.0.0.1';
  const port = 18080; // deterministic for test

  beforeAll(async () => {
    server = new CaptureServer({ host, port });
    await server.listen();
  });

  afterAll(async () => {
    await server.close();
  });

  it('health returns ok', async () => {
    const res = await fetch(`http://${host}:${port}/health`);
    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.status).toBe('ok');
    expect(json.milestone).toContain('M1');
  });

  it('captures generic request without assuming endpoint', async () => {
    const res = await fetch(`http://${host}:${port}/some/unknown/path?foo=bar`, {
      headers: { Host: `fake.wzm.local:${port}` },
    });
    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.ok).toBe(true);
    expect(json.note).toContain('M1 capture');

    const capRes = await fetch(`http://${host}:${port}/__capture`);
    const cap = await capRes.json() as any;
    expect(cap.count).toBeGreaterThanOrEqual(1);
    const last = cap.captured[cap.captured.length - 1];
    expect(last.url).toContain('/some/unknown/path');
  });

  it('__reset clears capture', async () => {
    await fetch(`http://${host}:${port}/test-reset`, { method: 'GET' });
    let cap = await (await fetch(`http://${host}:${port}/__capture`)).json() as any;
    expect(cap.count).toBeGreaterThanOrEqual(1);
    await fetch(`http://${host}:${port}/__reset`, { method: 'POST' });
    cap = await (await fetch(`http://${host}:${port}/__capture`)).json() as any;
    expect(cap.count).toBe(0);
  });

  it('logs Host header verbatim', async () => {
    // Use raw http.request to set Host explicitly (fetch blocks Host override)
    await new Promise<void>(async (resolve, reject) => {
      const http = await import('node:http');
      const req = http.request(
        { host, port, path: '/host-test', method: 'GET', headers: { Host: 'cdn.warzone.example:8080' } },
        (res) => {
          res.on('data', () => {});
          res.on('end', resolve);
        }
      );
      req.on('error', reject);
      req.end();
    });
    const cap = await (await fetch(`http://${host}:${port}/__capture`)).json() as any;
    const found = cap.captured.find((c: any) => c.url === '/host-test');
    expect(found).toBeDefined();
    expect(found.host).toContain('cdn.warzone.example');
  });
});
