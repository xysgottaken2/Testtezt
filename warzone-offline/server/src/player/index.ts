/**
 * Player — M7/M8
 * Estado mínimo: posição, rotação, movimento, saúde
 */
export interface PlayerState {
  id: string;
  position: { x: number; y: number; z: number };
  rotation: { yaw: number; pitch: number };
  health: number; // 0-100
  velocity?: { x: number; y: number; z: number };
}

export function createPlayerState(id: string, spawn: { x: number; y: number; z: number }): PlayerState {
  return {
    id,
    position: spawn,
    rotation: { yaw: 0, pitch: 0 },
    health: 100,
    velocity: { x: 0, y: 0, z: 0 },
  };
}
