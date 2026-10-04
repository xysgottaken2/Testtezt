# Runbook — Análise de APK/XAPK Warzone Mobile (quando disponível)

> **Quando usar:** somente quando você tiver uma cópia **legal** do APK/XAPK (dump do próprio device via `adb pull` ou download de build pública, sem distribuir).  
> **Onde colocar:** **NUNCA** dentro de `/home/user/Testtezt` (o repo bloqueia). Use `/tmp/wzm` (fora do Git).
> **O que commitar:** apenas artefatos pequenos redigidos: `endpoints.json`, `apk-metadata.json`, hashes, listas — NUNCA o `.apk`.

---

## 1. Artefato necessário — o que falta exatamente (2026-10-04, ainda bloqueado)

| Artefato | Exemplo de caminho legal | Como obter legalmente | Status atual |
|---|---|---|---|
| **WZM APK ou XAPK** — qualquer build pública (ex.: Global 2024-03-21 ou LR) | `/tmp/wzm/warzone.xapk` | 1) Device próprio: `adb shell pm path com.activision.callofduty.warzone` → `adb pull /data/app/.../base.apk /tmp/wzm/base.apk`<br>2) APKMirror / repositório de preservação de build pública (não distribuir)<br>3) Backup local antigo | **MISSING** — verificado em 2026-10-04: `find /home -name *.apk` vazio, `/tmp/wzm` inexistente |
| **OBB / shards** (se XAPK) | `/tmp/wzm/split_asset_pack/` | Vem dentro do XAPK; `unzip warzone.xapk` | MISSING |
| **libgame.so + outras .so** (dentro do APK, `lib/arm64-v8a/`) | `/tmp/wzm/apktool-output/lib/arm64-v8a/libgame.so` | Extraído via `apktool d` (ver passo 2) | MISSING — sem APK não extraível |
| **logcat** (opcional, precisa device) | `/tmp/wzm/logcat.txt` | `adb logcat` durante boot | MISSING |
| **pcap** (opcional, precisa device + hotspot) | `/tmp/wzm/capture.pcap` | `tcpdump` ou `Wireshark` | MISSING |

> **Não simular:** se a linha `Status` ainda for MISSING, o desbloqueio de #8 permanece bloqueado. Este runbook prepara as ferramentas, não os dados.

---

## 2. Preparação (sem APK — já feito nesta branch)

```bash
# ferramentas já instaladas no CI / local
which apktool jadx strings readelf nm adb  # verificar

# scripts deste repo (não precisam de APK para testes)
node warzone-offline/tools/apk-analysis/endpoint-scanner.js --help
bash warzone-offline/tools/apk-analysis/apk-inspect.sh --help
bash warzone-offline/tools/apk-analysis/lib-inspect.sh --help
```

Testes já verdes: `warzone-offline/server` 14 + `warzone-offline/launcher` 12.

---

## 3. Passo a passo quando o arquivo chegar

### 3.1. Isolar fora do repo

```bash
mkdir -p /tmp/wzm
cp /caminho/legal/warzone.xapk /tmp/wzm/warzone.xapk  # NUNCA cp para ~/Testtezt
cd /tmp/wzm
sha256sum warzone.xapk | tee warzone.xapk.sha256  # hash para doc, não conteúdo
unzip -l warzone.xapk | head -n 100 | tee xapk-contents.txt  # apenas lista
```

### 3.2. Metadata (não proprietário)

```bash
# se for XAPK, ele é um ZIP — extrair APK interno primeiro
unzip warzone.xapk -d xapk-extracted
find xapk-extracted -name "*.apk" -exec aapt dump badging {} \; | tee apk-metadata.txt
# ou
bash /home/user/Testtezt/warzone-offline/tools/apk-analysis/apk-inspect.sh /tmp/wzm/warzone.xapk --out /tmp/wzm/apk-metadata.json
# saída redigida: package, versionCode, versionName, sdkVersion, native libs, hashes — SEM binário
```

**Exemplo de saída redigida (sintético, não WZM):**
```json
{
  "package": "com.activision.callofduty.warzone",
  "versionCode": 12345,
  "versionName": "1.0.0",
  "sha256": "abc123... (primeiros 16 chars)",
  "nativeLibs": ["arm64-v8a/libgame.so (12 MB)"],
  "confidence": "VERIFIED — extraído de APK local em 2026-10-04"
}
```

### 3.3. Decode (apktool + jadx — fora do repo)

```bash
apktool d /tmp/wzm/warzone.xapk -o /tmp/wzm/apktool-output
jadx -d /tmp/wzm/jadx-output /tmp/wzm/warzone.xapk  # pode demorar
ls -lh /tmp/wzm/apktool-output/assets/shard/ 2>&1 | head
cat /tmp/wzm/apktool-output/AndroidManifest.xml | grep -E "package|uses-permission|application"
```

### 3.4. Scanner de endpoints (dentro do repo, saída só em /tmp)

```bash
node /home/user/Testtezt/warzone-offline/tools/apk-analysis/endpoint-scanner.js \
  /tmp/wzm/jadx-output /tmp/wzm/apktool-output/lib \
  > /tmp/wzm/endpoints.json

# categorizar por tipo: auth, matchmaking, demonware, cdn, telemetry, game-server
node /home/user/Testtezt/warzone-offline/tools/apk-analysis/categorize-endpoints.js \
  /tmp/wzm/endpoints.json > /tmp/wzm/endpoints-by-category.json

# verificar lib nativa
bash /home/user/Testtezt/warzone-offline/tools/apk-analysis/lib-inspect.sh \
  /tmp/wzm/apktool-output/lib/arm64-v8a/libgame.so > /tmp/wzm/lib-inspect.json
```

### 3.5. Logcat + pcap (precisa device físico)

```bash
adb logcat -c
adb shell am start -n com.activision.callofduty.warzone/com.unity3d.player.UnityPlayerActivity
adb logcat | grep -i -E "warzone|demonware|activision|cdn|analytic|manifest|shard|demonwareportmapping" | tee /tmp/wzm/logcat.txt
# pcap em hotspot (PC como AP)
sudo tcpdump -i wlan0 -w /tmp/wzm/capture.pcap port 3074 or port 443 or udp &
```

### 3.6. Gerar artefatos redigidos para commit

```bash
# NUNCA cp *.apk para o repo. Apenas estes 3 arquivos pequenos:
cp /tmp/wzm/endpoints.json /tmp/wzm/endpoints-redacted.json  # redigir tokens se houver
# editar manualmente para marcar confidence por entrada (ver seção 4)
cp /tmp/wzm/apk-metadata.json /home/user/Testtezt/docs/research/_tmp-apk-metadata.json # revisar antes
# na prática, copiar só hashes/listas, não dumps
```

**O que pode ser commitado (após revisão):**
- `docs/research/endpoints-verified.json` — apenas hostnames (sem tokens, sem dumps)
- `docs/protocol/<nome>.md` — um por endpoint, preenchendo `hostname / protocolo / porta / onde encontrada / método / versão / evidência / confiança`
- `docs/research/lib-inspect-summary.md` — presença/ausência de `libgame.so`, tamanho, strings relevantes (apenas lista)

**O que NUNCA commitar:**
- `*.apk`, `*.xapk`, `*.obb`, `*.shard`, `*.so`, `*.dex`, `*.pcap`, `logcat` com tokens

---

## 4. Classificação de confiança (não promover por código morto)

| Nível | Critério | Exemplo |
|---|---|---|
| `VERIFIED — observado em execução` | Host apareceu em `logcat` OU `pcap` com SYN/HTTP real do cliente | `logcat: "Connecting to cdn.warzone...:443"` |
| `PROBABLE — encontrado em código` | String apareceu em `jadx-output` ou `strings libgame.so`, mas não observado em runtime | `strings libgame.so: "https://cdn..."` sem logcat |
| `HYPOTHESIS` | Mesmo padrão visto em outro COD (ex.: `demonware.net:3074`), sem evidência WZM | `port_3074` em código genérico |
| `UNKNOWN` | Não encontrado em lugar nenhum | — |
| `DBD_REFERENCE` | Só existe em DbD, nunca em WZM | `bhvronline.com` |

> **Regra 9:** só `observado em execução` vira `WARZONE_VERIFIED` para habilitar `hosts` → `localhost`.

---

## 5. Certificate pinning — como detectar (sem remover)

```bash
# 1. Tentar hosts → localhost sem Frida
# 2. Se logcat mostrar "SSLHandshakeException / Pinning failure / Trust anchor",
#    então pinning existe → documentar em docs/reverse-engineering/pinning-report.md
# 3. Pesquisar metodologia (Frida, network_security_config.xml repack em sandbox)
#    — documentar, não tratar remoção como objetivo em si (regra 11)
```

---

## 6. Teste mínimo de localhost (regra 12)

Quando um host for `VERIFIED — observado em execução`, então:

```bash
# 1. Launcher adiciona 127.0.0.1 <host-verified> (hosts-patch, dry-run primeiro)
node warzone-offline/launcher/src/hosts-patch/cli.js --add <host-verified> --dry-run
# 2. CaptureServer em localhost:8080 já responde 200 genérico
npm run dev --workspace warzone-offline/server
# 3. Client em device (mesma LAN ou hotspot PC) deve gerar 1 entry em curl http://127.0.0.1:8080/__capture
#    com host === <host-verified>
# 4. Se aparecer, documentar como VERIFIED — não implementar protocolo ainda
```

Se `hosts` não surtir efeito (cliente usa DoH/dns hardcoded), documentar onde falhou e próximo experimento (DNS local).

---

## 7. Checklist para desbloquear #8

- [ ] `apk-metadata.json` com `versionCode/versionName/sha256` (sem binário)
- [ ] `endpoints.json` com `scannedFiles` + `findings` (sem tokens)
- [ ] `endpoints-by-category.json` (auth/matchmaking/demonware/cdn/telemetry/game)
- [ ] `lib-inspect.json` (libgame.so presente? tamanho? strings demonware?)
- [ ] `logcat.txt` redigido (se device)
- [ ] 1 entry em `/__capture` com host VERIFIED (se localhost testado)
- [ ] 1 `docs/protocol/<endpoint>.md` preenchido como `VERIFIED — observado em execução`
