import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { BootstrapServer, BUILD_SELECTOR_PATH, OFFLINE_PAGE_PATH, BOOTSTRAP_HOST, buildSelectorLocalStub, offlinePageLocalStub } from '../src/bootstrap/index.js';

describe('BootstrapServer — WZM 3.10.0 offline stubs (VERIFIED chain)', () => {
  let server: BootstrapServer;
  const host = '127.0.0.1';
  const port = 18081;

  beforeAll(async () => {
    server = new BootstrapServer({ host, port });
    await server.listen();
  });

  afterAll(async () => {
    await server.close();
  });

  it('serves build-selector local stub (not redirecting to offline)', async () => {
    const res = await fetch(`http://${host}:${port}${BUILD_SELECTOR_PATH}`, {
      headers: { Host: `${BOOTSTRAP_HOST}:443` },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('javascript');
    const body = await res.text();
    expect(body).toContain('WZM_OFFLINE');
    expect(body).toContain('pre_login_GVS');
    expect(body).not.toContain('Estes servidores estão permanentemente fora');
    // original CDN redirecionava para offline — stub não deve fazer redirect real
    expect(body).not.toContain('window.location =');
    expect(body).toContain('Não redirecionar');
  });

  it('serves offline page local replacement', async () => {
    const res = await fetch(`http://${host}:${port}${OFFLINE_PAGE_PATH}`, {
      headers: { Host: BOOTSTRAP_HOST },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('text/html');
    const body = await res.text();
    expect(body).toContain('Modo Offline Local');
    expect(body).toContain(BOOTSTRAP_HOST);
  });

  it('health and hits tracking', async () => {
    await fetch(`http://${host}:${port}${BUILD_SELECTOR_PATH}`);
    await fetch(`http://${host}:${port}${OFFLINE_PAGE_PATH}`);
    const hitsRes = await fetch(`http://${host}:${port}/__hits`);
    const hits = await hitsRes.json() as any;
    expect(hits.count).toBeGreaterThanOrEqual(2);
    expect(hits.hits.some((h: any) => h.path === BUILD_SELECTOR_PATH)).toBe(true);
  });

  it('404 for unknown paths (proves only VERIFIED endpoints)', async () => {
    const res = await fetch(`http://${host}:${port}/manifest/unknown.js`);
    expect(res.status).toBe(404);
    const json = await res.json() as any;
    expect(json.known).toContain(BUILD_SELECTOR_PATH);
  });

  it('stubs do not copy Activision proprietary code', () => {
    const js = buildSelectorLocalStub();
    const html = offlinePageLocalStub();
    // must not contain the shutdown message as failure — it must be clearly local
    expect(html).toContain('não pelo CDN');
    // JS must not fetch Activision
    expect(js).not.toContain('fetch(');
    expect(js).not.toContain('prod.cdni.callofduty.com');
  });
});
