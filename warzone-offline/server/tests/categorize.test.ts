import { describe, it, expect } from 'vitest';
import { categorize } from '../../tools/apk-analysis/categorize-endpoints.js';

describe('categorize-endpoints (M1 unlock)', () => {
  it('routes auth findings', () => {
    const f = [{ pattern: 'activision', match: 'https://auth.activision.com/v1/login', file: 'a.java', confidence: 'PROBABLE' }];
    const cat = categorize(f as any);
    expect(cat.auth.length).toBe(1);
  });

  it('routes demonware & telemetry', () => {
    const f = [
      { pattern: 'demonware', match: 'demonware.net', file: 'a.java', confidence: 'PROBABLE' },
      { pattern: 'port_3074', match: ':3074', file: 'b.java', confidence: 'HYPOTHESIS' },
      { pattern: 'analytic_keyword', match: 'analytic.example.com', file: 'c.java', confidence: 'HYPOTHESIS' },
      { pattern: 'https_url', match: 'https://unknown.random.com/foo', file: 'd.java', confidence: 'PROBABLE' },
    ];
    const cat = categorize(f as any);
    expect(cat.demonware.length).toBe(2);
    expect(cat.telemetry.length).toBe(1);
    expect(cat.other.length).toBe(1);
  });

  it('routes manifest/cdn', () => {
    const f = [
      { pattern: 'manifest', match: 'manifest.json', file: 'a.java', confidence: 'PROBABLE' },
      { pattern: 'https_url', match: 'https://cdn.example.com/manifest.json', file: 'a.java', confidence: 'PROBABLE' },
      { pattern: 'shard', match: '.shard', file: 'b.java', confidence: 'PROBABLE' },
    ];
    const cat = categorize(f as any);
    expect(cat.manifest_cdn.length).toBe(3);
  });

  it('routes matchmaking', () => {
    const f = [{ pattern: 'https_url', match: 'https://mm.example.com/lobby', file: 'a.java', confidence: 'PROBABLE' }];
    const cat = categorize(f as any);
    expect(cat.matchmaking.length).toBe(1);
  });

  it('empty input yields empty buckets', () => {
    const cat = categorize([]);
    expect(cat.other.length).toBe(0);
    expect(Object.values(cat).every((v) => (v as any[]).length === 0)).toBe(true);
  });

  it('routes cdni bootstrap to manifest_cdn and wbootstrap to bootstrap', () => {
    const f = [
      { pattern: 'cdni_callofduty', match: 'https://prod.cdni.callofduty.com/manifest/build-selector-103.js', file: 'a.java', confidence: 'VERIFIED' },
      { pattern: 'wbootstrap', match: 'WBootstrap', file: 'b.java', confidence: 'VERIFIED' },
    ];
    const cat = categorize(f as any);
    expect(cat.manifest_cdn.length).toBe(1);
    expect(cat.bootstrap.length).toBe(1);
  });
});
