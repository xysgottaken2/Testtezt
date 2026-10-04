/**
 * Protocol — M1/M2
 * [UNKNOWN] Demonware framing real — placeholder para (de)serialização
 * Referência: hosseinpourziyaie/demonware-companion buffer_deserializer
 */
export interface Packet {
  op: number;
  payload: Uint8Array;
}

export function serialize(packet: Packet): Uint8Array {
  // [HYPOTHESIS] placeholder — descobrir framing real via Frida + pcap
  const header = new Uint8Array(4);
  const view = new DataView(header.buffer);
  view.setUint16(0, packet.op, true);
  view.setUint16(2, packet.payload.length, true);
  const out = new Uint8Array(header.length + packet.payload.length);
  out.set(header, 0);
  out.set(packet.payload, header.length);
  return out;
}

export function deserialize(buf: Uint8Array): Packet {
  const view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const op = view.getUint16(0, true);
  const len = view.getUint16(2, true);
  const payload = buf.slice(4, 4 + len);
  return { op, payload };
}
