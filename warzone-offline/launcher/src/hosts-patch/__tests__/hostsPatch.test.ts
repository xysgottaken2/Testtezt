import { describe, it, expect } from 'vitest';
import { parseHosts, formatHosts, addEntries, removeEntries, validateSandboxPaths, validateHostname, hasMapping } from '../patch.js';

describe('hosts-patch', () => {
  it('parses comments and entries', () => {
    const content = `# comment\n127.0.0.1 localhost\n127.0.0.1 latest.dev.dbd.bhvronline.com cdn.dev.dbd.bhvronline.com # tag\n`;
    const entries = parseHosts(content);
    // trailing newline produces an extra empty entry
    expect(entries.length).toBe(4);
    expect(entries[1].ip).toBe('127.0.0.1');
    expect(entries[2].hostnames).toEqual(['latest.dev.dbd.bhvronline.com', 'cdn.dev.dbd.bhvronline.com']);
  });

  it('format round-trips', () => {
    const content = `127.0.0.1 localhost\n0.0.0.0 analytic.live.dbd.bhvronline.com`;
    const entries = parseHosts(content);
    const out = formatHosts(entries);
    expect(out).toContain('127.0.0.1\tlocalhost');
    expect(out).toContain('0.0.0.0\tanalytic.live.dbd.bhvronline.com');
  });

  it('addEntries does not duplicate', () => {
    const entries = parseHosts(`127.0.0.1 foo.example.com\n`);
    const { entries: e2, result } = addEntries(entries, ['foo.example.com', 'bar.example.com']);
    expect(result.added).toEqual(['bar.example.com']);
    expect(result.unchanged).toEqual(['foo.example.com']);
    expect(hasMapping(e2, '127.0.0.1', 'bar.example.com')).toBe(true);
  });

  it('addEntries with commentTag', () => {
    const entries = parseHosts(`# header\n`);
    const { entries: e2 } = addEntries(entries, ['a.example.com'], { commentTag: '# warzone-offline' });
    const out = formatHosts(e2);
    expect(out).toContain('# warzone-offline');
    expect(out).toContain('a.example.com');
  });

  it('removeEntries removes only matching ip/host', () => {
    const entries = parseHosts(`127.0.0.1 a.example.com b.example.com\n0.0.0.0 analytic.example.com\n`);
    const { entries: e2, result } = removeEntries(entries, ['a.example.com'], '127.0.0.1');
    expect(result.removed).toEqual(['a.example.com']);
    expect(hasMapping(e2, '127.0.0.1', 'b.example.com')).toBe(true);
    expect(hasMapping(e2, '127.0.0.1', 'a.example.com')).toBe(false);
    expect(hasMapping(e2, '0.0.0.0', 'analytic.example.com')).toBe(true);
  });

  it('sandbox guard rejects equal paths', () => {
    expect(validateSandboxPaths('/games/WZM', '/games/WZM').ok).toBe(false);
  });

  it('sandbox guard rejects nested paths', () => {
    expect(validateSandboxPaths('/games/WZM', '/games/WZM/sandbox').ok).toBe(false);
    expect(validateSandboxPaths('/games/WZM/sandbox', '/games/WZM').ok).toBe(false);
  });

  it('sandbox guard allows distinct paths', () => {
    expect(validateSandboxPaths('/games/WZM', '/tmp/wzm-sandbox').ok).toBe(true);
  });

  it('validateHostname rejects invalid', () => {
    expect(validateHostname('not-a-fqdn')).toBe(false);
    expect(validateHostname('')).toBe(false);
    expect(validateHostname('a..b.com')).toBe(false);
  });

  it('validateHostname accepts valid', () => {
    expect(validateHostname('cdn.example.com')).toBe(true);
    expect(validateHostname('latest.dev.dbd.bhvronline.com')).toBe(true);
  });

  it('hasMapping is case-insensitive', () => {
    const entries = parseHosts(`127.0.0.1 CDN.Example.COM\n`);
    expect(hasMapping(entries, '127.0.0.1', 'cdn.example.com')).toBe(true);
  });

  it('parse handles empty and whitespace', () => {
    const entries = parseHosts(`\n  \n# only comment\n`);
    // ["", "  ", "# only comment", ""]
    expect(entries.length).toBe(4);
  });
});
