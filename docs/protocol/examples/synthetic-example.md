# Exemplo Sintético — Formato de Endpoint (NÃO é dado WZM)

> **ATENÇÃO:** Este arquivo contém dados **sintéticos** apenas para mostrar o formato exigido pela regra 8.  
> **Nenhum hostname aqui é WARZONE_VERIFIED.** Não usar como prova.

---

## Exemplo 1: CDN (sintético)

- **hostname:** `cdn-synthetic.example.com`
- **protocolo:** `HTTPS`
- **porta:** `443` (observada em `strings` + confirmada em `logcat: GET https://cdn-synthetic.example.com/manifest.json`)
- **onde encontrada:** `jadx-output/com/synthetic/Foo.java:42` + `strings libgame.so` (offset sintético)
- **método de descoberta:** `endpoint-scanner.js` + `strings`
- **versão/build:** `synthetic-test-fixture v0`
- **evidência:** `https://cdn-synthetic.example.com/manifest.json` (sem token, domínio sintético)
- **nível de confiança:** `PROBABLE — encontrado em código (não observado em execução neste exemplo)`

Para virar `VERIFIED — observado em execução`:
```
logcat: 2026-10-04 12:00:00 I/Warzone: Fetching https://cdn-synthetic.example.com/manifest.json
pcap: SYN 192.168.1.10 -> 93.184.216.34:443 Host: cdn-synthetic.example.com
```

---

## Exemplo 2: Auth (sintético, code-only — não promover)

- **hostname:** `auth-synthetic.example.com`
- **protocolo:** `HTTPS`
- **porta:** `443`
- **onde encontrada:** `jadx-output/com/synthetic/Auth.java:100` (`"https://auth-synthetic.example.com/v1/login"`)
- **método:** `endpoint-scanner.js`
- **versão:** `synthetic`
- **evidência:** string literal em `Auth.java`
- **confiança:** `PROBABLE — encontrado em código` → **NÃO é WARZONE_VERIFIED** até `logcat` mostrar request real.

---

## Como preencher quando tiver dado real

1. Copiar `docs/protocol/template.md` para `docs/protocol/<nome>.md`
2. Preencher todos os campos com dados de `/tmp/wzm/endpoints.json` + `/tmp/wzm/logcat.txt`
3. Marcar `confidence` conforme `apk-analysis-runbook.md` §4
4. Só se `observado em execução`, adicionar entrada em `docs/research/endpoints-verified.json`
