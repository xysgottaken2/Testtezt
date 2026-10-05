# M3 — Primeira integração real: CDNI local (roteamento DNS + HTTPS embarcado)

**Data:** 2026-10-04
**Etapa:** M3 (implementação)
**Commit de referência:** launcher M2 em `59d3df0` (branch `feature/launcher-apk`); M3 na branch `arena/01a10449-testtezt`
**Escopo desta rodada:** implementar o roteador CDNI local e sua observabilidade. A implementação e o tráfego do próprio launcher não provam que o WZM chegou ao servidor; atribuição de fluxo permanece em M4.1.
**Fora de escopo (proibido):** bypass de autenticação, anti-cheat, credenciais, modificação do APK do WZM, brute force, download de ativos do CDN.

---

## 1. O problema, medido no device (S23 Ultra, launcher M2)

| Observação | Resultado |
|---|---|
| Launcher abre | ✅ |
| Servidor local (stub `127.0.0.1:18081`) inicia | ✅ |
| WZM inicia pelo launcher | ✅ |
| WZM mostra "Estes servidores estão permanentemente fora de serviço" | ❌ (M2 não observou request no stub; causa e origem desconhecidas) |
| Launcher exibia "CDNI real não implementado" | ❌ |

**Leitura corrigida:** no registro M2 não foi observada requisição atribuída ao WZM chegando ao stub `127.0.0.1:18081`. O hostname CDNI e IPs públicos registrados em M2.2 são evidência do inventário, não observação de uma conexão do jogo nessa sessão. A causa do estado do jogo permanece `UNKNOWN`.

## 2. Critérios de sucesso M3 (definidos pelo usuário)

1. Log do launcher mostra requests reais vindos do WZM.
2. Pelo menos o **primeiro** request CDNI chega ao servidor local.
3. Documentar exatamente **qual etapa** bloqueia o boot hoje.
4. "CDNI real não implementado" deixa de ser aceitável: tem que existir roteamento + logging de fato.

**Status da implementação/observação (revisado em 2026-10-04):** o roteador, listener e log estão implementados (critério 4). O Teste A registrou cinco conexões loopback e alertas TLS no peer, mas a origem não foi atribuída ao UID WZM; portanto os critérios 1/2 (“vindos do WZM”) não estão demonstrados. O bloqueio do jogo e a causa dos alertas permanecem `UNKNOWN`; TLS/trust/pinning ficam intocados até entender o caminho real (ver M4.1 e M3.2).

## 3. Decisão de arquitetura (sem root)

**Fatos verificados por pesquisa (2026-10-04):**

| # | Fato | Fonte/evidência | Confiança |
|---|---|---|---|
| A | Portas < 1024 exigem root ou `CAP_NET_BIND_SERVICE`/`ip_unprivileged_port_start<=porta`; um app Android sem root **não** tem esses privilégios por padrão | SO 413807; docs sysctl `net.ipv4.ip_unprivileged_port_start` (default 1024) | VERIFIED (Linux) / PROBABLE (Android) |
| B | `VpnService` sem root permite: interface `tun`, `addDnsServer`, `addAllowedApplication` (per-app), `protect()` em sockets | developer.android.com/reference/android/net/VpnService.Builder; AOSP VpnService | VERIFIED |
| C | `addDnsServer` rejeita loopback/any (o DNS virtual tem que ser um endereço do túnel) | docs VpnService.Builder.addDnsServer | VERIFIED |
| D | AOSP/Linux têm regras locais e política de roteamento VPN; a ordem/efeito exato no One UI e para uma tupla específica exige observar a tabela e o fluxo no device | Android/AOSP netd (detalhes e escopo em M4.1) | VERIFIED como implementação de referência; efeito no S23 `UNKNOWN` |
| E | O código escreve de volta ao TUN pacotes locais para os quais usa bounce; isso pode levar o socket local ao listener, mas entrega/rota final não foi validada em cada device | código do launcher; comportamento do kernel/device ainda não observado | hipótese de caminho, não prova WZM |
| F | Mesmo roteando, o TLS só fecha se o cliente confiar no certificado. Apps com `targetSdk >= 24` **não** confiam em CAs instaladas pelo usuário (a menos que o app declare `networkSecurityConfig` com `src="user"`) | comportamento padrão Android 7+ | VERIFIED |
| G | Instalar CA no *system store* exige root/`/system` (ou `adb root`) | Android 7+ | VERIFIED |

**Plano de arquitetura implementado (não é observação do fluxo WZM):**

```
WZM (com.activision.callofduty.warzone)
  │  (addAllowedApplication configura o UID-alvo; não prova captura de todo socket)
  ▼
tun 10.111.222.1/32, DNS 10.111.222.2   ← rota só de 10.111.222.0/24 (NÃO é VPN de internet)
  │
  ├── consulta DNS "prod.cdni.callofduty.com" → CdnVpnService responde em userspace
  │        A = 10.111.222.1   (AAAA/SVCB = NOERROR vazio, força IPv4)
  │        qualquer outro domínio → encaminhado a 1.1.1.1/8.8.8.8 via socket protect()ed
  │
  └── Se um socket do UID-alvo usar a VPN e o destino casar com o prefixo explícito:
           ├── entrega local pelo Android → listener :443 (a confirmar no device por fluxo atribuído)
           └── ou pacote observado no TUN → bounce implementado (entrega local final não presumida)
                   ▼
             LocalHttpsServer (SSLServerSocket, certificado NOSSO)
                   ▼
             TcpRelay → CdnRouteTable
                   ├── endpoint comprovado → 200 + placeholder marcado
                   ├── nome conhecido/caminho inferido → 200 marcado HYPOTHESIS
                   └── desconhecido → 404 controlado + URL/path EXATOS no log
```

Por que não "só 127.0.0.1:18081" (M2): o plano antigo não registrou tráfego atribuído ao WZM nesse stub. Por que não `bind` em `0.0.0.0`: decisão de escopo do projeto (servidor só local). Por que não iptables/`REDIRECT`: exige root. A entrega de sockets para endereços locais e o resultado do bounce dependem do roteamento efetivo; não são presumidos para o WZM.

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

## 6. Estado do caminho real e do TLS (causa não confirmada)

O log de cinco conexões loopback com alertas `certificate_unknown` (§6.1) não identifica o peer como WZM.
Portanto, não há base para declarar que confiança TLS é o bloqueio do jogo nem que o roteamento real do WZM
funcionou. O caminho/UID real permanece `UNKNOWN` até M4.1.

* Android docs descreve defaults de Network Security Configuration por `targetSdk`; isso é referência para
  análise read-only, não diagnóstico do peer histórico.
* Uma inspeção estática de APK pode documentar stack/NSC/pinning, sem alterar o APK ou TLS.
* **Regra vigente:** não instalar CA, mudar trust/pinning/certificados ou testar bypass até o fluxo real
  estar entendido e atribuído; sem root, patch do APK, fingerprint spoof ou Frida.

### 6.1. Eventos do device — 2026-10-04 (S23 Ultra, peer não atribuído)

| Medida | Valor | Significado |
|---|---|---|
| `tcpConnections` | **5** | cinco conexões foram aceitas no listener local; owner UID não resolvido nos logs antigos |
| listener | **127.0.0.1:443** | accept em loopback; isso não identifica peer nem prova tráfego do túnel |
| TLS | **5 × `SSLV3_ALERT_CERTIFICATE_UNKNOWN`** | alerta TLS registrado para os sockets locais; peer/processo sem atribuição |
| `httpRequests` | 0 | nenhuma requisição HTTP completou nesse listener/intervalo |
| `tlsOk` / `tlsFailed` | 0 / 5 | nenhum handshake completado no listener naquela sessão |
| `dnsIntercepted` | 0 | nenhum host CDNI interceptado nessa janela; não prova qual resolver o WZM usou |

Leitura (**revisada em M3.3/M3.4** — ver `docs/research/m3.3-diferenca-entre-os-testes.md` e, para o caminho TCP/IP e o ciclo de vida do listener, `docs/research/m3.4-caminho-wzm-tun.md`):

* **DEVICE_OBSERVED:** cinco accept(s) em loopback e cinco falhas TLS foram contados; peer/UID não atribuído;
* **UNKNOWN:** se essas conexões vieram do WZM. Loopback não é restrito ao pacote da VPN e o IP não codifica processo;
* **DEVICE_OBSERVED:** uma execução separada registrou `tcpConnections=0`, `dnsIntercepted=0` e consulta(s) a `dns.adguard.com`; isso mostra logs diferentes, não a causa nem que o roteamento do WZM mudou.

Melhorias de diagnóstico feitas em cima desta evidência (não alteram o roteamento):

* cada tentativa registra o endpoint e o resultado estruturado de `getConnectionOwnerUid` na direção peer → listener; UID não é PID e `INVALID_UID` é ambíguo — só UID resolvido igual ao alvo pode atribuir aquele socket;
* cabeçalho `[DIAG]` registra pacote/versão/UID configurado e se `addAllowedApplication` foi aceito; isso verifica configuração, não se cada processo/socket está na VPN;
* vigia de túnel: aviso único de `nenhum pacote recebido no TUN` e de `nenhuma consulta DNS dos hosts CDNI`
  (o silêncio do teste B passa a ser uma linha explícita, não uma ausência);
* cada pacote do TUN é contado e, quando descartado, registrado com `motivo=<CODIGO>`
  (`IPV6_SEM_ATENDIMENTO`, `UDP_PORTA_NAO_DNS`, `PROTO_NAO_SUPORTADO`, `TCP_SEM_ATENDIMENTO`) e, para pacote
  realmente inválido, o motivo exato do parser (`CURTO_DEMAIS`, `VERSAO_DESCONHECIDA`,
  `IPV4_CABECALHO_INCONSISTENTE`, `TAMANHO_DECLARADO_MENOR_QUE_CABECALHO`, `TAMANHO_DECLARADO_MAIOR_QUE_LIDO`,
  `TRANSPORTE_CABECALHO_CURTO`, `IPV6_CABECALHO_CURTO`) — ver M3.4;
* falhas de bind ganham `motivo=ENDERECO_INDISPONIVEL|PORTA_EM_USO|PORTA_NEGADA`; sem listener de túnel, o loopback segue um endpoint diagnóstico separado e não é tratado como substituto nem evidência WZM;
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
   * `[DNS] prod.cdni.callofduty.com ...` = consulta observada; só atribuir ao WZM se houver owner UID na tupla;
   * `[CDNI] TCP SYN ... -> 10.111.222.1:443` = cabeçalho observado; só owner UID-alvo promove evidência do fluxo;
   * `[TLS] ...` = handshake do socket aceito; sem UID-alvo confirmado, não atribuir ao WZM;
   * `[CDNI] GET /... -> 200` = o servidor local serviu esse path; associar à sessão/UID só quando a conexão tiver atribuição confiável;
   * `[CDNI?] DESCONHECIDO: GET /... -> 404 controlado` = path novo descoberto (anotar!).
4. Sem fio (opcional, para copiar o log bruto):

A exportação pela tela VER LOGS é a fonte de sessão. Qualquer chamada manual ao endpoint local (inclusive loopback) é somente health-check do launcher, não tráfego WZM; não usar `curl -k` como evidência de caminho ou de confiança do jogo.

## 8. Testes automatizados (JVM, rodam no CI)

| Arquivo | O que garante |
|---|---|
| `DnsRouterTest` | parse de pergunta; intercepta só hosts comprovados; devolve A = `10.111.222.1`; AAAA sem resposta; não responde outros domínios |
| `TunnelPacketsTest` | monta/parseia IPv4+UDP; RST correto (swap, seq/ack, checksum válido); nunca responde a RST; descarta não-IPv4 |
| `CdnRouteTableTest` | endpoints VERIFIED → 200 + marcador; HYPOTHESIS marcado; desconhecido → 404 com path/query exatos no corpo e no log; `/__wzm_offline/health` |
| `LocalHttpsServerTest` | **fim-a-fim com TLS real** (certificado do app): handshake, GET, 404 controlado, contadores e o log contendo path + SNI |
| `CertificateAssetTest` | p12 abre com a senha, tem chave privada, SAN correto, não é Activision, CA separada |
| `TlsTrustTest` | classificação das falhas de TLS, incluindo a **string exata do device** (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`) → `CLIENTE_RECUSOU_CERTIFICADO`, e os casos de cleartext/hostname/expirado/cifra/conexão encerrada |
| `LocalHttpsServerTest.untrustedClientHandshakeCountsAsTlsFailureAndNeverAsHttpRequest` | reproduz comportamento TLS de cliente de teste em JVM; não reproduz nem atribui as conexões antigas do device ao WZM |
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
* **Caminho WZM ainda não atribuído (M4.1):** TLS/trust/pinning permanecem intocados. Inspeção estática pode ser read-only; qualquer alteração ou instalação de CA fica bloqueada até a rota real ser entendida e autorização específica existir.
