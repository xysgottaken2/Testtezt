# Frida / Cert Pinning — Procedimento quando APK disponível

> **Status:** `[UNKNOWN]` para WZM — procedimento documentado sem assumir que pinning existe.
> **Quando executar:** após `endpoint-scanner.js` confirmar hosts HTTPS e após `capture` mostrar que `hosts` sozinho não basta (ex.: `ERR_CERT_AUTHORITY_INVALID` em logcat).

---

## 1. Quando é necessário

- Se o cliente WZM rejeitar cert self-signed do `localhost` (logcat mostra `Pinning failure`, `SSLHandshakeException`, `Trust anchor not found`), `hosts` sozinho não basta.
- Em DbD, a solução foi `VerifyPeer=false` em `Engine.ini` (UE). IW 9.0 não tem esse arquivo — por isso Frida é o próximo passo metodológico.

## 2. Pré-requisitos

- Device Android rootado ou com `frida-server` via `adb root` (ou emulador Waydroid).
- `pip install frida-tools`
- APK preservado instalado no device (não incluído no repo).

## 3. Procedimento reproduzível

```bash
# 1. Iniciar frida-server no device
adb root
adb push frida-server /data/local/tmp/frida-server
adb shell "chmod 755 /data/local/tmp/frida-server && /data/local/tmp/frida-server &"

# 2. Listar processos
frida-ps -U | grep -i warzone

# 3. Hook genérico de pinning (testar com mitmproxy primeiro)
#    Script universal: https://github.com/apkunpacker/FridaScripts (ex.: Universal Android SSL Pinning Bypass)
frida -U -f com.activision.callofduty.warzone -l tools/frida/universal-pinning-bypass.js --no-pause

# 4. Subir mitmproxy e repetir boot
mitmproxy --mode transparent --listen-port 8080
adb logcat | grep -i warzone  # ver se GET /manifest.json agora chega ao proxy
```

## 4. O que documentar se funcionar

- `Name: TLS Pinning Bypass`
- `Transport: HTTPS`
- `Evidence: logcat antes/depois + pcap com 200 do proxy`
- `Confidence: VERIFIED` somente após captura real.
- Nunca commitar `universal-pinning-bypass.js` proprietário — linkar fonte.

## 5. Se Frida não for possível

- Alternativa: `apktool d → network_security_config.xml → adicionar <trust-anchors><certificates src="user"/></trust-anchors>` + repack com `apktool b` + assinar com keystore local (sandbox). Documentar como `HYPOTHESIS` até testado.
- Último fallback: hotspot PC como DNS que resolve `A cdn.example -> 192.168.x.x` e aceitar que cliente use IP direto.

## 6. Guard

- Não bypassar Ricochet para servidores oficiais — uso apenas contra `localhost`.
- Não distribuir APK modificado.
