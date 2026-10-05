import { isIP } from 'node:net';

/** Local services in this repository may bind only to loopback. */
export function requireLoopbackHost(candidate: string | undefined): string {
  const host = candidate?.trim() || '127.0.0.1';
  const isIpv4Loopback = isIP(host) === 4 && host.split('.')[0] === '127';
  if (host !== '::1' && !isIpv4Loopback) {
    throw new Error(`Local server host must be loopback (127.0.0.0/8 or ::1), got: ${host}`);
  }
  return host;
}

/**
 * Config — M0 skeleton
 * [UNKNOWN] valores reais a descobrir via APK/logcat
 * Cada campo deve ser documentado com SOURCE/EVIDENCE quando verificado.
 */
export interface ServerConfig {
  host: string;
  port: number; // TCP AUTH/LSG — [UNKNOWN] default 3074 por analogia COD Online
  httpPort: number; // CDN mock
  udpPort: number; // gameplay — [UNKNOWN]
  gameVersion: string; // [UNKNOWN]
  map: string; // VERDANSK | REBIRTH_ISLAND | ...
  gameMode: string; // [HYPOTHESIS] BR_SOLO, RESURGENCE, etc.
  botCount: number;
  database: string;
  logLevel: string;
  tickRate: number; // Hz — [HYPOTHESIS] 21 (PC WZ 48ms)
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServerConfig {
  return {
    host: requireLoopbackHost(env.HOST),
    port: parseInt(env.PORT ?? '3074', 10),
    httpPort: parseInt(env.HTTP_PORT ?? '8080', 10),
    udpPort: parseInt(env.UDP_PORT ?? '27000', 10),
    gameVersion: env.GAME_VERSION ?? 'UNKNOWN',
    map: env.MAP ?? 'VERDANSK',
    gameMode: env.GAME_MODE ?? 'BR_SOLO',
    botCount: parseInt(env.BOT_COUNT ?? '20', 10),
    database: env.DATABASE ?? 'sqlite:./data/warzone.db',
    logLevel: env.LOG_LEVEL ?? 'debug',
    tickRate: parseInt(env.TICK_RATE ?? '21', 10),
  };
}
