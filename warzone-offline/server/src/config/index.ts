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
    host: env.HOST ?? '0.0.0.0',
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
