# Metodologia de Engenharia Reversa — Warzone Mobile

> **Status vigente:** guia histórico. Trechos antigos sobre mitmproxy/CA, Frida, root, hooks de Demonware,
> pinning, patch ou repack estão supersedidos e não devem ser executados. M3.2/M4.1 autorizam apenas
> pesquisa read-only até atribuição segura do caminho WZM; o APK do jogo e TLS/trust/pinning ficam intocados.
>
> **Princípio:** interoperabilidade e preservação. Apenas em APKs obtidos legalmente (próprio device, APKMirror com consentimento). Não distribuir APK/OBB.

---

## 1. Objetivo

Descobrir, sem inventar: engine, libs, endpoints, portas, protocolos, auth, CDN, streaming, cache, logs, manifests.

---

## 2. Ferramentas

| Ferramenta | Camada | Instalação |
|---|---|---|
| **apktool** | Decode resources, `AndroidManifest.xml`, `split_asset_pack` | `brew install apktool` / `apt install apktool` |
| **JADX** | Dex → Java/Kotlin, busca de URLs/Retrofit | `brew install jadx` |
| **Ghidra** | Native `.so` (ARM64) — `libgame.so`, `libUnity` se existir | ghidra-sre.org |
| **strings** | Extração rápida de domínios/URLs de `.so` | `strings`, `r2` |
| **readelf / nm / objdump** | Inspeção de ELF ARM | `llvm-readelf` |
| **adb + logcat** | Logs de boot, hosts tentados, erros TLS | `adb logcat \| grep -i warzone` |
| **Wireshark / tcpdump** | Captura UDP/TCP na LAN/hotspot | `wireshark`, `adb shell tcpdump` |
| **mitmproxy / Charles** | Intercepta HTTPS (com Frida bypass) | `mitmproxy` |
| **Frida** | Hook Demonware / TLS pinning / dump buffers | `pip install frida-tools`, `frida-server` no device |
| **demonware-companion** | `buffer_deserializer` + `data_interceptor` (DLL hook) | `github.com/hosseinpourziyaie/demonware-companion` |

---

## 3. Fluxo recomendado (M0-M2)

### 3.1. Preparação

```bash
# 1. Obter XAPK legalmente (ex.: APKMirror Warzone Mobile S5, ou extrair do próprio device)
adb shell pm path com.activision.callofduty.warzone
adb pull /data/app/.../base.apk ./warzone.apk
# ou
cp ~/Downloads/Warzone_Mobile_S5.xapk ./warzone.xapk && unzip warzone.xapk

# 2. Criar pasta de trabalho (não commitada)
mkdir -p /tmp/wzm && cp warzone.apk /tmp/wzm/
```

### 3.2. Fingerprint rápido (30s)

```bash
aapt dump badging warzone.apk | grep -E "package|version|sdkVersion"
unzip -l warzone.apk | grep -E "lib/|split_asset|assets/shard|manifest"
file lib/arm64-v8a/*.so
```

### 3.3. Decode completo

```bash
apktool d warzone.apk -o /tmp/wzm/apktool-output
jadx -d /tmp/wzm/jadx-output warzone.apk
# opcional: dex2jar + vineflower para comparar
```

### 3.4. Busca de endpoints

```bash
# Java/Kotlin
grep -R "https://\|wss://\|demonware\|activision\|cdn\.\|analytic\|stun\|3074" /tmp/wzm/jadx-output --include="*.java" | tee /tmp/wzm/endpoints.java.txt

# Nativo
strings /tmp/wzm/apktool-output/lib/arm64-v8a/libgame.so | grep -i "https\|demonware\|cdn\|activision\|analytic\|stun\|3074" | tee /tmp/wzm/endpoints.so.txt

# Manifest
cat /tmp/wzm/apktool-output/AndroidManifest.xml | grep -E "INTERNET|USES|activity|service"
```

Cada domínio/URL encontrado → `docs/protocol/<nome>.md` com template.

### 3.5. Native deep dive (Ghidra)

1. Importar `libgame.so` (ARM64) no Ghidra
2. Buscar `strings` → XREFs → funções que montam URL / fazem `connect` / `SSL_CTX`
3. Procurar `DemonwarePortMapping`, `StateEngine`, `Matchmaking`, `Ricochet`
4. Se ofuscado (R8), usar dicas do `android-reverse-engineering-skill` (Kotlin metadata)

### 3.6. Runtime — logcat

```bash
adb logcat -c
adb shell am start -n com.activision.callofduty.warzone/com.unity3d.player.UnityPlayerActivity
adb logcat | grep -i -E "warzone|demonware|activision|cdn|http|tls|error|auth" | tee /tmp/wzm/logcat.txt
# ou filtrar por PID
adb shell pidof com.activision.callofduty.warzone
adb logcat --pid=<pid> | tee /tmp/wzm/logcat.pid.txt
```

### 3.7. Runtime — tráfego

```bash
# Opção A: hotspot no PC, celular conecta, Wireshark na interface do hotspot
# Opção B: tcpdump no device (root)
adb shell "tcpdump -i wlan0 -w /sdcard/cap.pcap port 3074 or port 443 or udp" &
# depois: adb pull /sdcard/cap.pcap && wireshark cap.pcap

# HTTPS intercept (requer Frida bypass de pinning)
mitmproxy --mode transparent --listen-port 8080
# + Frida: frida -U -f com.activision.callofduty.warzone -l tools/frida/bypass.js
```

### 3.8. Frida — dump de buffers Demonware

Baseado em `demonware-companion`:

```js
// tools/frida/demonware-dump.js
Interceptor.attach(Module.findExportByName("libgame.so", "DW_Send"), {
  onEnter(args) {
    console.log("DW_Send buffer:", hexdump(args[1], { length: args[2].toInt32() }));
  }
});
```

---

## 4. O que documentar por achado

Para cada descoberta, preencher:

```
SOURCE:   (APK path, build, hash ou log)
VERSION:  (versionName/versionCode)
DATE:     (data da observação)
EVIDENCE: (trecho de log, screenshot, pcap, decompilado)
CONFIDENCE: VERIFIED / PROBABLE / HYPOTHESIS / UNKNOWN
```

E classificar `DBD_REFERENCE` vs `WARZONE_VERIFIED`.

---

## 5. Shard / manifest forense

```bash
ls -lh /tmp/wzm/apktool-output/assets/shard/ 2>/dev/null || find /tmp/wzm -name "*.shard" | head
cat /tmp/wzm/apktool-output/assets/shard/manifest.json 2>/dev/null | jq . | head -n 100
hexdump -C /tmp/wzm/apktool-output/assets/shard/*.shard | head
# procurar IWffn100
grep -a "IWff" /tmp/wzm/apktool-output/assets/shard/*.shard | head
```

Documentar em `docs/research/warzone-mobile-streaming.md`.

---

## 6. Regras legais e de segurança

- Nunca commitar `warzone.apk`, `*.shard`, `*.pak`, `logcat` com tokens, `pcap` com tráfego pessoal.
- Ao compartilhar `logcat`/`pcap` em Issue, **redigir** tokens, IPs privados, IDs.
- Não atacar servidores oficiais; tráfego só contra `localhost` ou device próprio.
- Se encontrar chaves privadas / certs no APK, **não publicar** — reportar como `[REDACTED]`.

---

## 7. Próximos experimentos concretos

| # | Experimento | Ferramenta | Saída |
|---|---|---|---|
| 1 | APK strings sweep | `strings` + `grep` | `endpoints.so.txt` |
| 2 | JADX endpoint sweep | `grep` Java | `endpoints.java.txt` |
| 3 | Shard inventory | `tools/asset-tools/shard-inventory.py` | `shards.json` |
| 4 | Logcat boot (avião + hosts) | `adb logcat` | `logcat.txt` |
| 5 | Hosts override PoC | `mitmproxy` + hosts | prova de que cliente aceita localhost |
| 6 | UDP capture | Wireshark hotspot | `cap.pcap` |
