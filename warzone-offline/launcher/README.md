# Launcher — M0 skeleton

> Launcher orquestra: `hosts` override → Frida bypass → `adb am start` → logcat

## Estrutura futura

```
launcher/
├── cli.ts           # Configure paths / Sync shards / Import local .shard / Launch
├── config.json      # { gamePath, sandboxPath, serverHost }
├── hosts-patch/     # 127.0.0.1 para domínios WZM (a descobrir)
├── frida/           # bypass cert pinning
└── adb/             # launch via adb
```

## Estado atual (M0)

Skeleton — sem implementação. Ver `docs/research/dead-by-daylight-mobile.md` para referência DbD

e `docs/reverse-engineering/methodology.md` para técnica de hosts override:

```
127.0.0.1   cdn.warzone-mobile.example   # [UNKNOWN] domínio real a descobrir
127.0.0.1   auth.warzone-mobile.example
0.0.0.0     analytic.warzone-mobile.example
```

Requer `adb root` ou `hosts` via `adb shell` + Frida para TLS.

**Próximo passo (M1):** PoC que redireciona um domínio de teste para `localhost:8080` e prova que o cliente aceita `VerifyPeer=false`.
