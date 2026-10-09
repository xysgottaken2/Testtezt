import { describe, it, expect } from 'vitest';
import { loadConfig } from '../src/config/index.js';
import { createDefaultProfile } from '../src/profile/index.js';
import { createSession } from '../src/matchmaking/index.js';
import { createPlayerState } from '../src/player/index.js';
import { serialize, deserialize } from '../src/protocol/index.js';

describe('config', () => {
  it('loads defaults on loopback', () => {
    const cfg = loadConfig({});
    expect(cfg.host).toBe('127.0.0.1');
    expect(cfg.map).toBe('VERDANSK');
    expect(cfg.botCount).toBe(20);
    expect(cfg.tickRate).toBe(21);
  });

  it('rejects non-loopback server hosts', () => {
    expect(() => loadConfig({ HOST: '0.0.0.0' })).toThrow(/must be loopback/i);
    expect(() => loadConfig({ HOST: '192.168.1.2' })).toThrow(/must be loopback/i);
    expect(loadConfig({ HOST: '127.0.0.1' }).host).toBe('127.0.0.1');
    expect(loadConfig({ HOST: '::1' }).host).toBe('::1');
  });
});

describe('profile', () => {
  it('creates local profile', () => {
    const p = createDefaultProfile();
    expect(p.id).toBe('local-player-001');
    expect(p.level).toBe(1);
  });
});

describe('matchmaking', () => {
  it('creates session with bots', () => {
    const s = createSession('VERDANSK', 'BR_SOLO', 'local-player-001', 5);
    expect(s.players).toContain('local-player-001');
    expect(s.bots).toHaveLength(5);
    expect(s.status).toBe('waiting');
  });
});

describe('player', () => {
  it('spawns with 100 health', () => {
    const ps = createPlayerState('p1', { x: 0, y: 0, z: 0 });
    expect(ps.health).toBe(100);
  });
});

describe('protocol', () => {
  it('round-trips packet', () => {
    const payload = new TextEncoder().encode('hello');
    const pkt = { op: 0x12, payload };
    const buf = serialize(pkt);
    const out = deserialize(buf);
    expect(out.op).toBe(0x12);
    expect(new TextDecoder().decode(out.payload)).toBe('hello');
  });
});
