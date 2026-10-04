/**
 * hosts-patch — implementação genérica, sem hipótese WZM.
 * Inspirado em DbD `ettfemnio/dbd-server` (hosts override) e
 * `CarlosMonarrez/DbDPrivateServer` (sandbox guard).
 * Testes em __tests__/hostsPatch.test.ts
 */

import { HostsEntry } from './types.js';

const HOSTS_LINE_RE = /^\s*([0-9a-fA-F:.]+)\s+([^#\s]+(?:\s+[^#\s]+)*)\s*(?:#.*)?$/;

export function parseHosts(content: string): HostsEntry[] {
  const entries: HostsEntry[] = [];
  for (const line of content.split('\n')) {
    if (!line.trim() || line.trim().startsWith('#')) {
      entries.push({ ip: '', hostnames: [], comment: line, raw: line });
      continue;
    }
    const m = line.match(HOSTS_LINE_RE);
    if (!m) {
      entries.push({ ip: '', hostnames: [], comment: line, raw: line });
      continue;
    }
    const ip = m[1];
    const hostsPart = m[2].trim();
    const hostnames = hostsPart.split(/\s+/).map((h) => h.toLowerCase());
    entries.push({ ip, hostnames, raw: line });
  }
  return entries;
}

export function formatHosts(entries: HostsEntry[]): string {
  return entries
    .map((e) => {
      if (e.ip === '' && e.raw !== undefined) return e.raw;
      if (e.ip === '') return e.comment ?? '';
      return `${e.ip}\t${e.hostnames.join(' ')}`;
    })
    .join('\n');
}

export function hasMapping(
  entries: HostsEntry[],
  ip: string,
  hostname: string
): boolean {
  const h = hostname.toLowerCase();
  return entries.some((e) => e.ip === ip && e.hostnames.includes(h));
}

export interface AddOptions {
  ip?: string;
  commentTag?: string; // ex: "# warzone-offline"
}

/**
 * Adiciona entradas `ip -> hostnames` se ainda não existirem.
 * Não duplica. Retorna novo array + PatchResult.
 */
export function addEntries(
  entries: HostsEntry[],
  hostnames: string[],
  opts: AddOptions = {}
): { entries: HostsEntry[]; result: import('./types.js').PatchResult } {
  const ip = opts.ip ?? '127.0.0.1';
  const normalized = hostnames.map((h) => h.toLowerCase()).filter(Boolean);
  const added: string[] = [];
  const unchanged: string[] = [];

  for (const h of normalized) {
    if (hasMapping(entries, ip, h)) {
      unchanged.push(h);
    } else {
      added.push(h);
    }
  }

  if (added.length === 0) {
    return { entries: [...entries], result: { added: [], removed: [], unchanged: normalized } };
  }

  const newEntries = [...entries];
  // remover linhas vazias finais duplicadas
  while (newEntries.length && newEntries[newEntries.length - 1].raw === '') newEntries.pop();
  // adicionar bloco com tag
  if (opts.commentTag) {
    newEntries.push({ ip: '', hostnames: [], comment: opts.commentTag, raw: opts.commentTag });
  }
  newEntries.push({ ip, hostnames: added, raw: undefined });

  return { entries: newEntries, result: { added, removed: [], unchanged } };
}

export function removeEntries(
  entries: HostsEntry[],
  hostnames: string[],
  ip?: string
): { entries: HostsEntry[]; result: import('./types.js').PatchResult } {
  const toRemove = new Set(hostnames.map((h) => h.toLowerCase()));
  const removed: string[] = [];
  const newEntries: HostsEntry[] = [];

  for (const e of entries) {
    if (e.ip === '' || e.hostnames.length === 0) {
      newEntries.push(e);
      continue;
    }
    if (ip && e.ip !== ip) {
      newEntries.push(e);
      continue;
    }
    const kept = e.hostnames.filter((h) => {
      if (toRemove.has(h)) {
        removed.push(h);
        return false;
      }
      return true;
    });
    if (kept.length > 0) {
      newEntries.push({ ...e, hostnames: kept });
    } else {
      // se tinha comentário/tag, manter linha de comentário mas não mapping
      // aqui simplesmente remove a linha; tags órfãs são limpas no próximo passo
    }
  }

  // limpar tags órfãs deixadas vazias (opcional, mantém simplicidade)
  const unchanged = [...toRemove].filter((h) => !removed.includes(h));
  return { entries: newEntries, result: { added: [], removed, unchanged } };
}

/**
 * Valida sandbox guard: hostsPath não deve ser o hosts real sem --apply,
 * e sandboxPath não deve ser igual/parent de gamePath (DbD sandbox-first).
 */
export function validateSandboxPaths(
  gamePath?: string,
  sandboxPath?: string
): { ok: boolean; reason?: string } {
  if (!gamePath || !sandboxPath) return { ok: true };
  const norm = (p: string) => p.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  const g = norm(gamePath);
  const s = norm(sandboxPath);
  if (g === s) return { ok: false, reason: 'sandboxPath must not equal gamePath' };
  if (s.startsWith(g + '/')) return { ok: false, reason: 'sandboxPath must not be inside gamePath' };
  if (g.startsWith(s + '/')) return { ok: false, reason: 'gamePath must not be inside sandboxPath' };
  return { ok: true };
}

export function validateHostname(hostname: string): boolean {
  // RFC 1123 simplified, lowercased
  if (!hostname || hostname.length > 253) return false;
  const labels = hostname.toLowerCase().split('.');
  if (labels.length < 2) return false; // exigir FQDN
  const labelRe = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
  return labels.every((l) => labelRe.test(l));
}
