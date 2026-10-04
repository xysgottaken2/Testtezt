/**
 * Profile — M3
 * Perfil local sem Activision account
 * [HYPOTHESIS] estrutura mínima — descobrir campos reais via APK
 */
export interface LocalProfile {
  id: string;
  name: string;
  level: number;
  activisionId?: string;
  entitlements?: string[];
}

export function createDefaultProfile(): LocalProfile {
  return {
    id: 'local-player-001',
    name: 'Player',
    level: 1,
    activisionId: 'local:player001',
    entitlements: [],
  };
}
