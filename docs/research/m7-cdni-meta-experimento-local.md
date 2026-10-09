# M7 — experimento mínimo do `cdni.meta` local (2026-10-08)

> **Objetivo único:** descobrir se, ao receber o `cdni.meta` servido pelo launcher, o WZM sai da tela
> **“Conectando a servidor de atualizações”** e **qual é a primeira requisição seguinte**. Nada fora disso
> foi implementado. A investigação da `JsBridge` (M6.9–M6.13) está formalmente encerrada (`m6.13`).

MARCADORES: `M7_ESOPEITO_MINIMO` · `CDNI_META_LF_VS_CRLF_CORRIGIDO` · `DEVICE_OBSERVATION_PENDENTE`

---

## 1. Análise curta, feita antes de alterar código

O experimento **já existia** (montado em M3.5 e refinado em M4.4). O que faltava não era endpoint, era
fidelidade de bytes e um teste que trave a resposta inteira.

| # | Item do escopo M7 | Onde está hoje (verificado por leitura) | Estado antes deste incremento |
|---|---|---|---|
| 1 | responder `GET /wzm/shard_cdn/android/_manifest/cdni.meta` | `cdn/CdnRouterConfig.kt:37` (constante) → `cdn/BootstrapEndpoints.kt:135` (rota) → `cdn/CdnRouteTable.kt:30` (`respond`) | **já atendido** |
| 2 | corpo = exatamente o JSON real (10 campos, 7 chaves `#x…`) | `cdn/CdniMetaBody.kt:56` (`ANDROID`) | conteúdo igual, **renderização diferente** (LF vs CRLF do CDN) → corrigido |
| 3 | HTTP `200` | `cdn/CdnRouteTable.kt:52` | **já atendido** (travado em `CdnRouteTableTest`, `CdniMetaFlowTest`) |
| 4 | `Content-Type: application/json` | `cdn/BootstrapEndpoints.kt:137` → `HttpResponses.build` escreve `application/json; charset=utf-8` | **já atendido**; nenhum teste travava a linha do cabeçalho → coberto |
| 5 | não interpretar nem alterar `#x…` | `cdn/CdniMetaBody.kt:43` (`flagKeys`, só regex de extração) | **já atendido** |
| 6 | logs que distingam request sintética × request real | `cdn/CdniMetaFlow.kt:61` (`observe`) + `cdn/TcpRelay.kt:41` + `cdn/LocalHttpsServer.kt:259`; `RequestLog.isDuringSyntheticTest` | **já atendido** (com a ressalva de correlação temporal do M4.1) |
| 7 | registrar a sequência posterior, em especial o 1º pedido | `cdn/CdniMetaFlow.kt:88` (`PROXIMO-PEDIDO-APOS-CDNI.META`) + `apos-cdni.meta=Δ…ms` em todos os seguintes + contadores `RequestLog.kt:118,124` | **já atendido** |
| 8 | **não** implementar `environment.config`, manifests, downloads | nada implementado; path desconhecido cai em `CdnRouteTable.unknown()` → 404 controlado (`:65`) | **já em conformidade** |
| 9 | não alterar TLS/pinning, TUN/VPN, autenticação, outros endpoints | sem alteração de código; só o corpo de um endpoint já existente | **já em conformidade** |
| 10 | não remover/quebrar testes existentes | 31 arquivos / 262 testes no run 37657231158 | preservados (1 ajuste de literal, §3) |
| 11 | testes travando a resposta exata | `CdniMetaFlowTest` (corpo), `CdnRouteTableTest` (status/confidence), `LocalHttpsServerTest` (assinatura frouxa `looksLikeRealBody`) | **lacuna real** → `CdniMetaResponseContractTest` |
| 12 | regenerar o APK debug no CI | `.github/workflows/android-build.yml` (`assembleDebug`) + `build.yml:81` (`wzm-offline-launcher-debug`) | sem alteração a fazer; só disparar/verificar |

**Por que isso basta:** o único recurso que o launcher pode servir com conteúdo verdadeiro é o `cdni.meta`
(o conteúdo dos `manifest.json` segue `UNKNOWN` — item 8 proíbe inventar). Servir esse arquivo, rotular a
origem de cada pedido e registrar o primeiro pedido seguinte cobre exatamente o que a pergunta exige; o
resto da resposta não está no código, está na tela do device (§4).

## 2. Descoberta que justificou a única mudança de runtime

Reobservação ao vivo (2026-10-08, duas chamadas idênticas, corpo anônimo público):

```
GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta
{<CR><LF>    "min_tu": 0,<CR><LF> … <CR><LF>}     → 397 B, sem quebra final
```

- O arquivo real do CDN usa **CRLF** com indentação de 4 espaços e termina em `}` **sem** linha final.
- O launcher servia o mesmo conteúdo com **LF** = 386 B: campos e valores idênticos, **11 bytes a menos**.
- Consequência para o experimento: se o cliente comparar tamanho/hash do arquivo (não sabemos — `HYPOTHESIS`),
  um corpo LF seria uma diferença *real* em relação ao que ele espera do CDN. Esse risco era gratuito de
  eliminar, então foi eliminado.
- O `~320 B` registrado em M2.2/M2.2.1/M3 era ordem de grandeza da resposta da ferramenta de fetch, não
  contagem de bytes — **corrigido** em `docs/protocol/cdni-meta.md`; os documentos históricos não são reescritos.
- `ios`: **não reobservado** hoje (a chamada de verificação falhou no proxy de fetch, erro de infra). O
  tamanho documentado (~410 B) não bate com o literal (509 B LF / 523 B CRLF). O corpo iOS ficou **como
  estava**, sem reivindicação de byte-a-byte, e a divergência está registrada como `UNKNOWN` no protocolo.

## 3. Alterações deste incremento (mínimas, e só essas)

1. `cdn/CdniMetaBody.kt` — `ANDROID` passa a ser `trimIndent().replace("\n","\r\n")` (397 B). Nenhum campo
   acrescentado/removido/reordenado; `IOS` intacto; `looksLikeRealBody` e `flagKeys` inalterados.
2. `test/…/CdniMetaResponseContractTest.kt` — **novo**, 7 testes: golden independente do corpo (linhas +
   ordem + valores), cabeçalhos exatos e sem duplicata, `Content-Length: 397`, sem BOM, sem quebra final,
   corpo = bytes após `\r\n\r\n`, as 7 chaves `#x…` transmitidas sem alteração, caminho android nunca serve
   o corpo iOS, query string não muda a resposta.
3. `test/…/CdniMetaFlowTest.kt` — o literal `upstreamJson` (que era LF) recebeu o mesmo `replace`, porque o
   teste dele afirma igualdade byte a byte com o corpo. **Nenhuma asserção foi removida ou enfraquecida.**
4. Notas/documentação: `BootstrapEndpoints` (`397 B CRLF`), `docs/protocol/cdni-meta.md` (Size/Encoding/
   EVIDENCE + discrepância iOS), `docs/launcher.md` (frase corrente).

Não-alterações deliberadas: servidor, roteamento, TLS/CA/pinning, TUN/VPN, DNS virtual, autenticação,
contadores, UI, novos endpoints, APK (a mudança vive em `src/test`, exceto os bytes do corpo).

## 4. Como ler o resultado no device (o passo que falta, e só ele)

Pré-requisitos e procedimento estão em `docs/research/m3.5-loopback-e-teste-sintetico.md` (botão
**TESTE SINTÉTICO DNS → 10.111.222.1:443 → cdni.meta**) e `docs/research/m5.0-servidor-local-dns.md`.

| O que aparece no log (`VER LOGS`) | Leitura |
|---|---|
| `[CDNI-META] cdni.meta servido: status=200 resposta=617 B · … origem=SINTETICO-LAUNCHER` | o caminho funciona de ponta a ponta (teste nosso); **não** diz nada sobre o WZM |
| `… origem=FORA-DA-JANELA-SINTETICA` | chegou um pedido que não foi o nosso teste — autoria ainda `UNKNOWN` sem owner UID/tupla (M4.1) |
| `[CDNI-META] PROXIMO-PEDIDO-APOS-CDNI.META: GET <path> · status=… · Δ=… ms` | **a resposta que o M7 quer**: a primeira requisição seguinte do cliente. `status=404` é bom sinal: o path é real e nós não o implementamos (não inventamos corpo) |
| `[TLS] FALHA no handshake … motivo=…` | o cliente chegou até o listener e **recusou** o certificado local → o experimento fica bloqueado por M5.3/M6.1/M6.2, não por este endpoint |
| nada além da linha sintética | o host não resolveu pelo nosso DNS virtual ou o cliente não tentou este caminho — `UNKNOWN`, não negativo |
| tela do jogo | “saiu de *Conectando a servidor de atualizações*?” é observado **na tela/na sequência de pedidos**, não no log do launcher |

## 5. Veredito

- **`VERIFIED`** — o launcher responde `200` + `application/json; charset=utf-8` + o corpo real **byte a
  byte** (397 B, CRLF), registra origem e o primeiro pedido seguinte; nada das chaves `#x…` é interpretado.
- **`VERIFIED`** — a resposta do CDN em 2026-10-08 é exatamente esse corpo (duas observações idênticas).
- **`HYPOTHESIS`** — que o cliente use `min_buildnum` para liberar a tela de update (nunca observado em
  pcap/dump; ver `docs/research/m4.0-verificando-atualizacoes.md`, `CANNOT_SKIP_DIRECTLY`).
- **`UNKNOWN`** — se o WZM sai da tela “Conectando a servidor de atualizações” com este `cdni.meta`, e qual
  é a primeira requisição que ele faz de verdade: exige rodar no device (sem adb/dispositivo neste ambiente,
  nenhuma medição foi feita nem simulada aqui).
- **`UNKNOWN`** — bytes exatos do `cdni.meta` iOS (discrepância de tamanho acima) e a existência de
  verificação de hash/tamanho pelo cliente.

**Próximo passo, e apenas ele:** rodar o APK debug deste build no device com o servidor local + DNS virtual,
observar a tela e o log `[CDNI-META]`, e anotar aqui o resultado (com owner UID quando a API resolver).

## 6. Verificação (medida no CI, não estimada)

| Verificação | Resultado |
|---|---|
| `python3 scripts/check-kotlin-preflight.py --dir android` | OK (81 arquivos) |
| `python3 scripts/check-kotlin-literals.py android` | OK (9 473 linhas) |
| `bash scripts/check-docs.sh` | docs obrigatórios presentes, marcadores ok |
| `android-build` run `37850384872` (commit `9935fcd`) | **sucesso** — `arquivos=32 testes=269 falhas=0 erros=0 pulados=0` |
| regressão da mudança de corpo | nenhuma: os 262 testes anteriores seguem passando (32º arquivo = +7 testes do contrato) |
| artefato | `wzm-offline-launcher-debug` — 15 731 908 B (zip), job `verify-artifact` OK (layout + sha256 conferidos no artefato publicado) |

O contrato byte-a-byte foi, portanto, validado por compilação + asserções reais no CI (397 B servidos com
`Content-Length: 397`), não só pela simulação local do `trimIndent()` feita durante a edição.

Resta **uma** coisa fora do alcance do CI: a observação no device (§4).

## 7. M8 — o que o log de 2026-10-08 mudou no launcher (diagnóstico, nada de TLS)

O export (157 linhas, `analysis` fora do repo) foi lido contra o código e produziu três lacunas de
instrumentação, fechadas no incremento M8 sem tocar em TLS/pinning, TUN/VPN, DNS ou endpoints:

| Lacuna | Correção | Consequência para a leitura |
|---|---|---|
| `sni` só era lido **no sucesso**, e por `getHandshakeSession()` — null depois de `startHandshake()` completar (por isso o handshake **válido** saiu `SNI=?`) | `LocalHttpsServer.resolveSni`/`sniOf`: sessão em construção → sessão estabelecida; `sni=` agora também nas duas linhas de **falha**, com `sni=INDISPONIVEL` declarado quando a pilha não expõe | prod × dev deixa de ser correlação temporal e passa a ser medida por conexão. Nenhuma das duas APIs garante acesso após handshake falho em todo fornecedor — por isso o `INDISPONIVEL` é um resultado, não um `?` |
| 4 de 12 falhas (`BAD_PACKET_LENGTH` + `DECRYPTION_FAILED_OR_BAD_RECORD_MAC`) caíam em `DESCONHECIDO`, a um passo de serem lidas como mais uma recusa de cadeia | código `FALHA_REGISTRO_TLS` em `TlsTrust`, posicionado **depois** do ramo de certificado (alerta de certificado vence quando ambos aparecem), com hint dizendo que as causas candidatas não são distinguíveis aqui | a assinatura de camada de registro fica separada da de confiança: **nem** recusa, **nem** aceitação |
| a linha `[DNS]` afirmava `RESPOSTA A=10.111.222.1` para **todo** tipo interceptado, inclusive AAAA (que o `DnsResponder` responde com NOERROR vazio) | texto por qtype (`RESPOSTA A=…` só para tipo A; `NOERROR sem respostas (…)` para 28/65) | os `(42 B)`/`(41 B)` do log param de parecer contradição; o comportamento do respondedor não mudou |

**Correção a uma leitura que o log autoriza e o quadro de evidências sugere:** `tcp_para_o_alvo_cdni_443:
UNKNOWN — SYN observado=0` **não** é um dado sobre o WZM. Como o destino é o próprio endereço do túnel
(`CdnRouterConfig.REDIRECT_TO = VPN_ADDRESS`), a tabela `local` do kernel entrega o SYN ao listener e o pacote
nunca aparece no fd do TUN — `tunTcpSyn=0`, `tunBounces=0`, `tunTcpPackets=0` junto de 13 conexões aceitas é o
**esperado**. Pelo mesmo motivo `tunFluxosDestinoResolvido=0`: o join nome↔destino em `CdnVpnService` é
`!header.isCdnTarget` por construção. Quem mede o cliente é a linha de aceite do listener (owner UID) e a
linha `[TLS]`.

**Testes do M8:** 6 novos (`LocalHttpsServerTest` +3 = T1; `TlsTrustTest` +3 = T2), suíte 32 arquivos /
**275** `@Test`, nenhum removido nem enfraquecido. T1 inclui a guarda de privacidade (a linha de falha não
pode conter `GET /`, `HTTP/1.1`, `Host:`, `Cookie`, `Authorization` nem o corpo) e T2 fixa que a assinatura de
registro **não** é reportada como recusa de certificado.

**Dado que o CI mediu (2026-10-08, run `37998930152`):** no JVM o alerta do cliente chega como
`SSLProtocolException`, não `SSLHandshakeException`, e cai no `catch` genérico — que não escreve `motivo=`
(escreve `sni=` desde o M8). No device, as 12 falhas do log vieram como `SSLHandshakeException`, ramo que
classifica. Ou seja: `motivo=` é informação **do fornecedor**, não promessa; o que o código garante em ambos os
ramos é `sni=`. Um teste que exigia `motivo=` no JVM falhou por especificar demais e foi corrigido para
condicional — sem remover nem enfraquecer a exigência sobre o `sni=`.

**Como reler o device depois disto:** a primeira coisa a olhar deixa de ser "quantas falhas" e passa a ser
`sni=` e `motivo=` **por tentativa** — `sni=prod.cdni.callofduty.com` × `sni=dev.cdni.callofduty.com`, e
`motivo=FALHA_REGISTRO_TLS` separado de `motivo=CLIENTE_RECUSOU_CERTIFICADO`. Se aparecer
`sni=INDISPONIVEL` em todas as falhas, a conclusão é sobre o Conscrypt (a sessão não é acessível após a
falha), não sobre o WZM: nesse caso o caminho seguinte é o D3 de `m6.1` (controle com três políticas de
confiança no próprio launcher), não um hook. A pergunta do M7 (sai da tela?) segue `UNKNOWN` até o teste da
CA de usuário (M5.4) ser executado no aparelho por você.
