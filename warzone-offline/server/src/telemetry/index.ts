/**
 * Telemetry stub — DBD_REFERENCE pattern
 * Sempre 200 OK vazio para analytic.* — não quebrar cliente
 */
export function telemetryStub() {
  return { ok: true };
}
