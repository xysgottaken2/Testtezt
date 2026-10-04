/**
 * hosts-patch — tipos
 * Metodologia VERIFIED: DbD dbd-server usa `127.0.0.1 <domínio>` em hosts.
 * Este módulo é genérico (não assume domínios WZM).
 */

export interface HostsEntry {
  ip: string;
  hostnames: string[]; // lowercased
  comment?: string;
  raw?: string; // linha original se comentário/branco
}

export interface PatchResult {
  added: string[];
  removed: string[];
  unchanged: string[];
}

export const LOCALHOST_IP = '127.0.0.1';
export const TELEMETRY_IP = '0.0.0.0'; // DbD pattern para bloquear analytics
