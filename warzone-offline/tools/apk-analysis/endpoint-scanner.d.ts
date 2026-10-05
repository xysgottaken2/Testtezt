export function scanPaths(roots: string[]): { scannedFiles: number; findings: Array<{ pattern: string; match: string; file: string; confidence: string }> };
