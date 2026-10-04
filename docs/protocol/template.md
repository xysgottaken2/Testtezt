# Template — Documentação de Protocolo

> Copie este arquivo para `docs/protocol/<nome-da-mensagem>.md` a cada endpoint/mensagem descoberta. Nunca invente campos.

---

## Identificação

- **Name:** (ex.: `AuthRequest`, `MatchmakingCreate`, `PlayerMove`)
- **Direction:** `C→S` | `S→C` | `C↔S`
- **Transport:** `HTTPS` | `TCP:3074` | `UDP` | `WSS` | `UNKNOWN`
- **Endpoint:** (ex.: `POST https://auth.activision.com/v1/login` ou `TCP LSG 3074 op 0x12`)
- **Encoding:** `JSON` | `protobuf` | `custom binary` | `UNKNOWN`
- **Encryption:** `none` | `TLS` | `custom (8-byte key)` | `UNKNOWN`
- **Size:** bytes observados (ex.: `42–128`)
- **Version:** build em que foi observado (ex.: `WZM Global 2024-03-21, versionName 1.0.0`)
- **Observed:** data e método (ex.: `2026-10-05 via mitmproxy + Frida`)

## Campos

| # | Campo | Tipo | Tamanho | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|---|
| 1 | `sessionId` | `uuid` | 36 | sim | ID da sessão LSG | `550e8400-...` |
| 2 | `position.x` | `float32` | 4 | sim | Coordenada X | `1234.5` |
| — | — | — | — | — | — | — |

> Se campo não foi observado, deixar `UNKNOWN` e não preencher.

## Exemplo (hex / JSON / pcap)

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "map": "VERDANSK"
}
```

Ou hexdump:

```
00000000  12 34 56 78 9a bc de f0  ...
```

## Evidência

- **SOURCE:** (caminho do APK, hash, ou pcap)
- **EVIDENCE:** (trecho de log, screenshot Wireshark, decompilado)
- **CONFIDENCE:** `VERIFIED` | `PROBABLE` | `HYPOTHESIS` | `UNKNOWN`

## Comportamento observado

- O que o cliente faz se o servidor responder `200` vs `403`?
- Retries? Timeout?
- O que quebra se o campo faltar?

## Notas

Qualquer hipótese deve ser marcada `HYPOTHESIS` e vir com experimento proposto.
