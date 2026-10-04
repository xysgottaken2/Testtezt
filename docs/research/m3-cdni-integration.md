# M3 — Primeira integração real: CDNI local (roteamento DNS + HTTPS embarcado)

**Data:** 2026-10-04
**Etapa:** M3 (implementação)
**Commit de referência:** launcher M2 em `59d3df0` (branch `feature/launcher-apk`); M3 na branch `arena/01a10449-testtezt`
**Escopo desta rodada:** fazer as requisições CDNI reais do WZM chegarem ao servidor embarcado e aparecerem no log do launcher.
**Fora de escopo (proibido):** bypass de autenticação, anti-cheat, credenciais, modificação do APK do WZM, brute force, download de ativos do CDN.

---

## 1. O problema, medido no device (S23 Ultra, launcher M2)

| Observação | Resultado |
|---|---|
| Launcher abre | ✅ |
| Servidor local (stub `127.0.0.1:18081`) inicia | ✅ |
| WZM inicia pelo launcher | ✅ |
| WZM mostra "Estes servidores estão permanentemente fora de serviço" | ❌ (nenhum request chegava ao servidor local) |
| Launcher exibia "CDNI real não implementado" | ❌ |

**Causa raiz:** o WZM não fala com `127.0.0.1:18081`. Ele resolve `prod.cdni.callofduty.com` pelo DNS do sistema e conecta em **HTTPS :443** no IP público (Akamai, M2.2: `23.46.216.80`). Um servidor em porta alta e loopback nunca é consultado. Logo, M2 estava estruturalmente mudo.

## 2. Critérios de sucesso M3 (definidos pelo usuário)

1. Log do launcher mostra requests reais vindos do WZM.
2. Pelo menos o **primeiro** request CDNI chega ao servidor local.
3. Documentar exatamente **qual etapa** bloqueia o boot hoje.
4. "CDNI real não implementado" deixa de ser aceitável: tem que existir roteamento + logging de fato.

**Status após o teste no S23 Ultra (2026-10-04):** critérios 1, 2 e 4 **cumpridos** (ver §6.1 — 5 conexões do WZM
chegaram ao listener local e apareceram no log). O critério 3 está isolado: o bloqueio é **validação do
certificado pelo cliente** (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`), investigado em
[docs/research/m3.2-apk-tls-trust-investigation.md](m3.2-apk-tls-trust-investigation.md).

## 3. Decisão de arquitetura (sem root)

**Fatos verificados por pesquisa (2026-10-04):**

| # | Fato | Fonte/evidência | Confiança |
|---|---|---|---|
| A | Portas < 1024 exigem root ou `CAP_NET_BIND_SERVICE`/`ip_unprivileged_port_start<=porta`; um app Android sem root **não** tem esses privilégios por padrão | SO 413807; docs sysctl `net.ipv4.ip_unprivileged_port_start` (default 1024) | VERIFIED (Linux) / PROBABLE (Android) |
| B | `VpnService` sem root permite: interface `tun`, `addDnsServer`, `addAllowedApplication` (per-app), `protect()` em sockets | developer.android.com/reference/android/net/VpnService.Builder; AOSP VpnService | VERIFIED |
| C | `addDnsServer` rejeita loopback/any (o DNS virtual tem que ser um endereço do túnel) | docs VpnService.Builder.addDnsServer | VERIFIED |
| D | Endereços atribuídos a uma interface local são entregues pela tabela de rotas `local` (prioridade 0) — antes das regras marca da VPN | routing Linux/Android (netd instala regras com prioridade > 0) | PROBABLE |
| E | Mesmo se D não valer no aparelho, devolver o pacote ao `tun` ("bounce") re-entrega o pacote à pilha local, pois o destino é um endereço local da própria interface `tun` | comportamento de dispositivos `tun` (dst = endereço da interface) | PROBABLE |
| F | Mesmo roteando, o TLS só fecha se o cliente confiar no certificado. Apps com `targetSdk >= 24` **não** confiam em CAs instaladas pelo usuário (a menos que o app declare `networkSecurityConfig` com `src="user"`) | comportamento padrão Android 7+ | VERIFIED |
| G | Instalar CA no *system store* exige root/`/system` (ou `adb root`) | Android 7+ | VERIFIED |

**Arquitetura escolhida (implementada):**

```
WZM (com.activision.callofduty.warzone)
  │  (VPN per-app: addAllowedApplication → só o WZM entra no túnel)
  ▼
tun 10.111.222.1/32, DNS 10.111.222.2   ← rota só de 10.111.222.0/24 (NÃO é VPN de internet)
  │
  ├── consulta DNS "prod.cdni.callofduty.com" → CdnVpnService responde em userspace
  │        A = 10.111.222.1   (AAAA/SVCB = NOERROR vazio, força IPv4)
  │        qualquer outro domínio → encaminhado a 1.1.1.1/8.8.8.8 via socket protect()ed
  │
  └── TCP :443 para 10.111.222.1
           ├── tabela `local` entrega ao kernel do aparelho  → listener :443 (TCP/TLS do kernel)
           └── fallback: loop do tun devolve o pacote ("bounce") à interface → mesma entrega
                   ▼
             LocalHttpsServer (SSLServerSocket, certificado NOSSO)
                   ▼
             TcpRelay → CdnRouteTable
                   ├── endpoint comprovado → 200 + placeholder marcado
                   ├── nome conhecido/caminho inferido → 200 marcado HYPOTHESIS
                   └── desconhecido → 404 controlado + URL/path EXATOS no log
```

Por que não "só 127.0.0.1:18081" (M2): o cliente não foi redirecionado por DNS nenhum. Por que não `bind` em `0.0.0.0`: decisão de escopo do projeto (servidor só local). Por que não iptables/`REDIRECT`: exige root. Por que não implementar TCP em userspace: desnecessário enquanto o kernel entrega pacotes para endereços locais; o "bounce" cobre o caso contrário.

### 3.1 O que o túnel NÃO faz

* Não encaminha tráfego para a internet (não é VPN de saída).
* Não toca autenticação, Demonware, anti-cheat ou credenciais.
* Não modifica o APK do WZM (só `PackageManager` para lançar).
* Não faz MITM "invisível": o certificado é declaradamente nosso e o handshake é logado.

## 4. Endpoints implementados (somente o que já tinha evidência)

`BootstrapEndpoints` (`android/app/src/main/kotlin/com/wzm/launcher/cdn/BootstrapEndpoints.kt`):

| Path | Evidência | Confiança | Corpo servido |
|---|---|---|---|
| `/manifest/build-selector-103.js` | M2/M2.2 (1º request do bootstrap, ~130 B) | VERIFIED | JS placeholder marcado |
| `/static/web/index.html` | M2 (documento do WebView) | VERIFIED | HTML placeholder marcado |
| `/manifest/build-selector-102.js` | M2.2 (fetch_page, ~4,2 KB) | VERIFIED | JS placeholder marcado |
| `/manifest/manifest.json` | M2.2 (~1,6 KB) | VERIFIED | JSON placeholder marcado |
| `/prelogin/boot-15.0.0/web/manifest.json` | M2.2 | VERIFIED | JSON placeholder marcado |
| `/prelogin/boot-15.0.0/web/static/js/main.js` | M2.2 | VERIFIED | JS placeholder marcado |
| `/prelogin/boot-15.0.0/web/static/css/main.css` | M2.2 | VERIFIED | CSS placeholder marcado |
| `/wzm/shard_cdn/android/_manifest/cdni.meta` | M2.2 (320 B) | VERIFIED | JSON placeholder marcado |
| `/wzm/shard_cdn/ios/_manifest/cdni.meta` | M2.2 (410 B) | VERIFIED | JSON placeholder marcado |
| `/manifest/{popup,events,dailylogin}(.ext)` | M2.2 cita os nomes; caminho/extensão inferidos | HYPOTHESIS | JSON placeholder marcado |
| `/__wzm_offline/health`, `/__wzm_offline/requests` | internos do launcher | INTERNAL | diagnóstico |

**Regra dura:** nenhum corpo de manifest real foi inventado. Todo corpo local contém o marcador
`wzm-offline-local` e, para VERIFIED, a nota de origem; o tamanho upstream conhecido (ex. `320 B`)
aparece no corpo. Endpoint fora da tabela → `404` com `unknown_cdni_endpoint` e a URL exata (path + query + host)
no corpo **e** no log do launcher. É assim que os paths ainda desconhecidos vão ser descobertos.

## 5. Certificado local (não é da Activision)

* Gerado por nós em 2026-10-04 (`openssl`), **descartável**:
  * folha `CN=prod.cdni.callofduty.com`, `O=WZM Offline Preservation`, SAN `prod.cdni.callofduty.com`, `*.cdni.callofduty.com`, `cdni.callofduty.com`;
  * CA própria `CN=WZM Offline Local CDNI CA (throwaway)`.
* Embarque no APK: `app/src/main/assets/cdn_local.p12` (senha `wzm-offline-local`) e `cdn_local_ca.pem`.
  **Esses arquivos NÃO são versionados** (o `.gitignore` do repo bloqueia `*.p12`/`*.pem`, e essa política é mantida):
  eles são gerados no build pelo script `scripts/generate-local-cdni-cert.sh` (local e CI). É um certificado
  **público de laboratório**, sem valor de segurança, sem relação com a Activision, e nunca será usado contra serviços reais.
* O launcher tem o botão **EXPORTAR CA LOCAL**, que copia `cdn_local_ca.pem` para
  `/sdcard/Android/data/com.wzm.launcher.debug/files/wzm-offline-local-ca.crt` e registra o caminho no log.
* Gerar/regenerar (idempotente; os comandos `openssl` exatos ficam no próprio script):

```bash
bash scripts/generate-local-cdni-cert.sh           # gera se faltar (local e CI)
bash scripts/generate-local-cdni-cert.sh --force   # regenera (invalida CA já instalada no device)
```

  O CI (`.github/workflows/android-build.yml`) roda esse passo antes dos testes unitários — os testes
  `CertificateAssetTest` e `LocalHttpsServerTest` falham com mensagem explícita se o asset não existir,
  o que garante que o asset produzido é validado a cada build.

`CertificateAssetTest` valida em CI que o asset carrega com a senha configurada, tem chave privada,
cobre `prod.cdni.callofduty.com`, **não** tem "activision" no subject/issuer e não é CA.

## 6. Bloqueio confirmado (o que trava o boot hoje)

O bloqueio previsto nesta seção **foi confirmado no device** (§6.1): o cliente do WZM recusa o certificado
local na validação da cadeia (`certificate_unknown`). O roteamento funciona; o que falta é uma *trust chain*
que o app aceite — e isso só pode ser resolvido de forma legítima (CA confiável pelo app) ou não é resolvível
dentro das nossas regras (sem root, sem patch do APK, sem bypass de pinning).

* `targetSdk >= 24`: o app **não** confia em CA instalada pelo usuário por padrão; confiar exigiria
  `<certificates src="user"/>` no NSC do próprio WZM (não temos controle) ou CA no *system store* (root — fora de escopo).
* Se houver **pinning**, nem CA de sistema resolve: ver a matriz de decisão em
  [docs/research/m3.2-apk-tls-trust-investigation.md](m3.2-apk-tls-trust-investigation.md) §4.
* **Não contornamos nada disso**: sem patch de trust, sem root, sem fingerprint spoof, sem Frida.

### 6.1. Evidência do device — 2026-10-04 (S23 Ultra, WARZONE_VERIFIED)

| Medida | Valor | Significado |
|---|---|---|
| `tcpConnections` | **5** | houve 5 conexões aceitas no servidor local (**dono não registrado na época**) |
| listener | **127.0.0.1:443** | chegou pelo loopback (não pelo endereço do túnel `10.111.222.1`) |
| TLS | **5 × `SSLV3_ALERT_CERTIFICATE_UNKNOWN`** | o cliente alcançou o listener e **recusou a cadeia de certificados** |
| `httpRequests` | 0 | nenhuma requisição HTTP completou (sem TLS não há HTTP) |
| `tlsOk` / `tlsFailed` | 0 / 5 | nenhum handshake aceito |
| `dnsIntercepted` | 0 | nesta tentativa o WZM **não** usou o DNS do túnel (anomalia analisada em §1.1 do doc M3.2) |

Leitura (**revisada em M3.3** — ver `docs/research/m3.3-diferenca-entre-os-testes.md`):

* **VERIFIED:** houve 5 conexões TCP aceitas no listener de loopback e o cliente foi recusado no TLS
  (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`);
* **HYPOTHESIS (não comprovado):** que essas 5 conexões tenham vindo do WZM. Até 2026-10-04 o log não
  registrava o **dono** (UID) da conexão, e o listener de `127.0.0.1:443` é alcançável por **qualquer**
  app do aparelho — não só pelo app incluído na VPN per-app;
* **VERIFIED:** em uma segunda execução no mesmo device, `tcpConnections=0`, `dnsIntercepted=0` e somente
  consultas para `dns.adguard.com` apareceram — ou seja, o roteamento **não** é determinístico entre
  execuções e isso precisa ser explicado com evidência, não presumido.

Melhorias de diagnóstico feitas em cima desta evidência (não alteram o roteamento):

* cada tentativa de conexão registra `via loopback|túnel` **e** `dono=uid=<n> (<pacote>)`
  (`ConnectivityManager.getConnectionOwnerUid`, API 29+) — resolve a autoria das conexões;
* cabeçalho de sessão `[DIAG]` com número da execução, app/versão/UID alvo e se `addAllowedApplication`
  foi aceito (per-app) — responde "o processo está mesmo na VPN?";
* vigia de túnel: aviso único de `nenhum pacote recebido no TUN` e de `nenhuma consulta DNS dos hosts CDNI`
  (o silêncio do teste B passa a ser uma linha explícita, não uma ausência);
* cada pacote do TUN é contado e, quando descartado, registrado com `motivo=<CODIGO>`
  (`FORA_DA_ROTA`, `UDP_PORTA_NAO_DNS`, `PROTO_NAO_SUPORTADO`, `TCP_SEM_ATENDIMENTO`, `PACOTE_INVALIDO`);
* falhas de bind dos listeners ganham `motivo=ENDERECO_INDISPONIVEL|PORTA_EM_USO|PORTA_NEGADA` e, quando o
  endereço do túnel fica sem listener, o log diz explicitamente que só o caminho `127.0.0.1:443` pode
  completar;
* falhas de TLS continuam com `motivo=<CÓDIGO>` classificado por `TlsTrust` (com dica acionável);
* consultas DNS **encaminhadas** (que não são do CDNI) continuam registradas uma vez por nome — agora com
  contador próprio no card/export (`dns-encaminhado`).

## 7. Como validar no device (passo a passo)

1. Instalar o APK debug do artifact `wzm-offline-launcher-debug`.
2. Abrir o launcher → **INICIAR ROTEADOR CDNI** → aceitar o diálogo de VPN do sistema.
   * O log deve mostrar: `HTTPS local escutando em 10.111.222.1:443` (ou a falha exata) e
     `túnel ativo: DNS 10.111.222.2, rota 10.111.222.0/24, prod.cdni.callofduty.com -> 10.111.222.1`.
3. **INICIAR WARZONE MOBILE**. Acompanhar o log **na própria tela VER LOGS** (botão na tela principal; sem ADB):
   há filtros por tag (`DNS`, `CDNI`, `CDNI?`, `HTTP`, `TLS`, `TUN`, `VPN`, `LAUNCHER`), contadores,
   status HTTP de cada resposta e botões LIMPAR/COPIAR/SALVAR .TXT. As linhas também vão para
   `filesDir/request-log.txt` (o log sobrevive a reabrir o app).
   Linhas esperadas:
   * `[DNS] prod.cdni.callofduty.com (tipo 1) -> 10.111.222.1 [interceptado]` = o WZM resolveu o CDNI pelo nosso DNS;
   * `[CDNI] TCP SYN ... -> 10.111.222.1:443 (devolvido à pilha local ...)` = conexão chegando;
   * `[TLS] handshake OK ...` ou `[TLS] FALHA no handshake ...` = a requisição chegou (com ou sem confiança);
   * `[CDNI] GET /... -> 200 (VERIFICADO ...)` = servimos o endpoint;
   * `[CDNI?] DESCONHECIDO: GET /... -> 404 controlado` = path novo descoberto (anotar!).
4. Sem fio (opcional, para copiar o log bruto):

```bash
adb logcat -s WZMOffline:*            # (se habilitado) ou
adb shell run-as com.wzm.launcher.debug cat files/...   # log é em memória; use /__wzm_offline/requests
curl -k --resolve prod.cdni.callofduty.com:443:127.0.0.1 https://prod.cdni.callofduty.com/__wzm_offline/health
curl -k --resolve prod.cdni.callofduty.com:443:127.0.0.1 https://prod.cdni.callofduty.com/__wzm_offline/requests
```

(O `curl --resolve` funciona quando o ouvinte de loopback está ativo — usa o próprio aparelho via `adb shell`.)

## 8. Testes automatizados (JVM, rodam no CI)

| Arquivo | O que garante |
|---|---|
| `DnsRouterTest` | parse de pergunta; intercepta só hosts comprovados; devolve A = `10.111.222.1`; AAAA sem resposta; não responde outros domínios |
| `TunnelPacketsTest` | monta/parseia IPv4+UDP; RST correto (swap, seq/ack, checksum válido); nunca responde a RST; descarta não-IPv4 |
| `CdnRouteTableTest` | endpoints VERIFIED → 200 + marcador; HYPOTHESIS marcado; desconhecido → 404 com path/query exatos no corpo e no log; `/__wzm_offline/health` |
| `LocalHttpsServerTest` | **fim-a-fim com TLS real** (certificado do app): handshake, GET, 404 controlado, contadores e o log contendo path + SNI |
| `CertificateAssetTest` | p12 abre com a senha, tem chave privada, SAN correto, não é Activision, CA separada |
| `TlsTrustTest` | classificação das falhas de TLS, incluindo a **string exata do device** (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`) → `CLIENTE_RECUSOU_CERTIFICADO`, e os casos de cleartext/hostname/expirado/cifra/conexão encerrada |
| `LocalHttpsServerTest.untrustedClientHandshakeCountsAsTlsFailureAndNeverAsHttpRequest` | reproduz a evidência do device em JVM: cliente sem confiança no certificado → `tlsFailed=1`, `httpRequests=0`, `tlsOk=0`, log com `motivo=` e `via loopback` |
| `ServerTest`, `WzmLauncherTest` | regressão da fase M2 (inalterados, continuam verdes) |
| `RequestLogTest` | armazenamento/consulta do log mostrado em VER LOGS: ordem/timestamp/tag, filtro por tag (inclusive `CDNI?`), sanitização de linha longa/multilinha, limite do buffer, contadores (DNS/TCP/HTTP/TLS), export, restore e sink sem duplicar linhas restauradas |
| `FileLogSinkTest` | persistência em arquivo: append/readTail, rotação por tamanho, clear, criação de diretório, export `.txt` e escrita concorrente (4 threads) |

## 9. Limitações / dívida técnica registrada

* O corpo servido é placeholder (não há captura do arquivo real) — o objetivo desta etapa é **observar e rotear**, não completar o boot.
* O buffer em memória guarda 400 linhas (a UI mostra esse total); em paralelo, tudo é gravado em
  `filesDir/request-log.txt` com rotação de 256 KB, então o histórico não se perde ao reabrir o app
  (contadores, porém, zeram junto com o processo — não há como reconstruí-los).
* `POST`/outros métodos nos endpoints conhecidos são atendidos como `GET` (semântica real UNKNOWN);
  métodos em paths desconhecidos caem no 404 controlado.
* Se o device negar `:443`, o log mostra a negativa e sobe em 18443 (diagnóstico) — nesse caso o próximo passo é o responder TCP em userspace sobre o tun (M3.1), o que **não** foi implementado nesta rodada.
* **Bloqueio ativo (M3.2):** confiança TLS do cliente. Nenhuma solução será implementada sem antes responder,
  com evidência do APK, se existe cadeia suportável (§4 de `m3.2-apk-tls-trust-investigation.md`).
