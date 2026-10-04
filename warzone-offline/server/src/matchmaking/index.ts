/**
 * Matchmaking — M4
 * [HYPOTHESIS] implementação local — descobrir protocolo real antes de congelar
 */
import { randomUUID } from 'node:crypto';

export interface Session {
  sessionId: string;
  map: string;
  gameMode: string;
  players: string[];
  bots: string[];
  status: 'waiting' | 'starting' | 'in_progress';
}

export function createSession(map: string, gameMode: string, playerId: string, botCount: number): Session {
  const sessionId = randomUUID();
  const bots = Array.from({ length: botCount }, (_, i) => `bot-${String(i + 1).padStart(3, '0')}`);
  return {
    sessionId,
    map,
    gameMode,
    players: [playerId],
    bots,
    status: 'waiting',
  };
}
