# tools/packet-tools

Helpers para Wireshark / Demonware buffers.

- `buffer_deserializer` de referência: https://github.com/hosseinpourziyaie/demonware-companion
- Frida dump: `../launcher/frida/bypass.js` (futura)

Uso:

```bash
wireshark cap.pcap
# filtro: tcp.port==3074 || udp.port==27000 || http
```
