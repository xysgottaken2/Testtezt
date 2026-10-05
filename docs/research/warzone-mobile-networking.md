# Warzone Mobile — networking (M0 reclassificado para Stable)

> **Atualizado:** 2026-10-04 · **Escopo:** correção documental, somente leitura.
> **Estado:** o caminho de rede real do WZM continua `UNKNOWN`. Este documento não autoriza mudanças de rede, TLS, autenticação ou APK.
> **Limite do workspace:** não há APK/XAPK/APKM, `.shard` ou `.so` disponível; portanto `libgame.so` ainda não foi analisada. Nenhuma nova observação em dispositivo ocorreu nesta revisão.

Esta revisão substitui as hipóteses antigas deste documento que apresentavam backend, portas, anti-cheat, telemetria ou analogias de outros títulos como fatos do WZM. As menções históricas abaixo permanecem apenas como referências explicitamente limitadas.

---

## 1. Vocabulário de evidência

| Rótulo | Uso permitido |
|---|---|
| `WARZONE_VERIFIED` | Observação/configuração de uma versão WZM identificada, com alcance escrito. Para atribuir um fluxo ao UID do WZM é necessário correlacionar a tupla e obter o owner UID; saber que o jogo estava aberto não basta. |
| `DEVICE_OBSERVED` | Evento visto no aparelho, mas sem atribuição confiável ao processo/UID do WZM. |
| `DBD_REFERENCE` | Informação exclusivamente sobre Dead by Daylight. Nunca é evidência de Warzone Mobile. |
| `OTHER_TITLE_REFERENCE` | Informação de outro Call of Duty, Warzone PC ou código genérico. Não prova que WZM use o mesmo endpoint/protocolo. |
| `SECONDARY_SOURCE_CLAIM` | Afirmação de fonte secundária sobre WZM, ainda sem confirmação independente nesta build. Não equivale a `WARZONE_VERIFIED`. |
| `VERIFIED` | Fato limitado ao objeto indicado — por exemplo, uma API Android ou resposta HTTP consultada manualmente. Não significa que o WZM usou esse recurso. |
| `PROBABLE` / `HYPOTHESIS` / `UNKNOWN` | Indicam, respectivamente, inferência limitada, possibilidade não testada ou questão sem evidência suficiente. Ausência de evento não escolhe uma causa. |

`getConnectionOwnerUid()` retorna UID, não PID. Para os limites de TUN, per-app, UID/PID, Private DNS, `Network.bindSocket()` e `VpnService.protect()`, ver [M3.6](m3.6-caminho-real-de-rede.md) e [M4.1](m4.1-dono-das-conexoes-loopback.md).

---

## 2. Quadro atual — o que sabemos e o que não sabemos

| Pergunta | Estado conservador | Evidência/limite |
|---|---|---|
| Qual é o caminho de rede efetivo do WZM durante “Verificando atualizações”? | `UNKNOWN` | A janela histórica não registrou SYN TCP do UID-alvo no TUN nem owner UID atribuível. Isso não prova ausência de tentativa, outro transporte ou uma causa específica. Ver [M3.6](m3.6-caminho-real-de-rede.md). |
| A allow-list per-app garante que cada socket do WZM entre no TUN? | Não. | O código adiciona o pacote-alvo à allow-list; as rotas são um controle distinto. A configuração não prova o caminho de um socket real. |
| O WZM usa `Network.bindSocket()`, `bindProcessToNetwork()` ou `VpnService.protect()`? | `UNKNOWN` | Não foi observado nem analisado `libgame.so`; `protect()` usado pelo serviço para seus próprios sockets não é prova sobre sockets do jogo. |
| O WZM usa Demonware, AUTH/LSG, State Engine ou Matchmaking+? | `UNKNOWN` | Créditos/wikis comunitárias são `SECONDARY_SOURCE_CLAIM`; referências a outros títulos não são análise do binário nem observação atribuída do WZM. |
| O WZM usa TCP/UDP 3074, UDP 25656 ou outro endpoint citado em pesquisas de COD Online/PC? | `UNKNOWN` | Esses valores são `OTHER_TITLE_REFERENCE`; não usar como endpoint/configuração do WZM. |
| Existe um endpoint CDNI real conhecido? | Há um arquivo público específico confirmado; o uso dele pelo WZM não foi observado. | `prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` foi consultado manualmente; ver §5. Não extrapolar para outros caminhos/manifestos. |
| `bhvronline.com` ou a regra de telemetria do DbD serve para WZM? | Não. | É `DBD_REFERENCE` e não pode ser transferida para Warzone Mobile. |
| Ricochet está presente no WZM Mobile? | `UNKNOWN` | Referência sobre Ricochet em PC não prova biblioteca ou comportamento mobile. |
| `libgame.so` foi varrida? | Não. | Nenhum binário `.so` está no workspace. Não houve varredura nesta revisão. |

---

## 3. Evidência WZM existente — com limites

### 3.1 Sessões históricas no aparelho

O material arquivado de M3.4/M3.5/M3.6 descreve um Samsung Galaxy S23 Ultra, Android 13/14, sem root, com WZM `3.10.0.19854920`:

- A sessão registrou o pacote `com.activision.callofduty.warzone` na allow-list e UID `10692`. Isso verifica a configuração da sessão, não a captura de todo socket desse UID.
- Em uma janela M3.4 foram resumidos `tunTcpSyn=0`, nenhum destino CDNI observado e dois pacotes IPv6 no TUN (76 B/UDP e 48 B/ICMPv6). A origem desses pacotes e a causa da ausência de SYN continuam `UNKNOWN`.
- A mesma documentação registra Private DNS em modo hostname (`dns.adguard.com`) e consultas DNS sem owner UID. Não atribuí-las ao WZM.
- Outra janela registrou conexões em `127.0.0.1:443` sem owner UID resolvido. Loopback não demonstra conexão externa nem autoria do jogo.
- As janelas M3.4 e M3.5 são distintas; contagens de uma não devem ser misturadas com a outra.

A leitura completa, incluindo orientação da tupla e limites de `getConnectionOwnerUid()`, está em [M4.1](m4.1-dono-das-conexoes-loopback.md). Nenhuma nova rodada física ocorreu nesta atualização.

### 3.2 Código atual do launcher

- `VpnCapturePlan.kt` configura `10.111.222.0/24`; não configura rota default nem prefixo IPv6 explícito.
- `CdnVpnService.kt` adiciona à allow-list somente o pacote-alvo solicitado. A chamada é configuração per-app, não prova de que um fluxo do WZM chegou ao TUN.
- `OwnerProbe` é um controle TCP iniciado pelo próprio launcher em loopback. É útil para validar parte da instrumentação local, mas **não** é controle positivo de outro app, não é tráfego WZM e não prova caminho pelo TUN.
- O teste automatizado sintético continua separado de evidência de dispositivo/WZM.

### 3.3 Evidência de build não é evidência de rede

CI verde confirma compilação/testes do launcher e do artefato próprio do projeto. Não confirma tráfego WZM, owner UID em dispositivo, uso do CDNI ou comportamento de `libgame.so`.

---

## 4. Reclassificação das afirmações antigas de M0

| Afirmação antiga | Reclassificação Stable | Por que não é evidência WZM |
|---|---|---|
| “IW 9.0 MGL” como engine confirmada | `UNKNOWN` no workspace; `SECONDARY_SOURCE_CLAIM` de wikis que falam do WZM | Não há APK/build identificado nem confirmação no binário disponível. |
| “Demonware State Engine + Matchmaking+” como backend provável/confirmado | `UNKNOWN`; crédito comunitário alegado não foi validado em artefato/runtime | Referências de outros títulos não confirmam o backend efetivamente usado por esta build WZM. |
| AUTH/LSG em TCP 3074, gameplay UDP ou `DemonwarePortMapping` | `OTHER_TITLE_REFERENCE` | Derivado de outros títulos/relatos; não tratar como endpoint, porta ou formato de WZM. |
| Handshake, chave, serialização ou protocolo de COD Online | `OTHER_TITLE_REFERENCE` | Não transferir protocolo/código de um título para outro. Não implementar emulação a partir dessa analogia. |
| Ricochet mobile | `UNKNOWN` | Informação sobre PC não demonstra presença no app mobile. Anti-cheat está fora de escopo. |
| `bhvronline.com`, bloqueio de analytics e fluxo offline do DbD | `DBD_REFERENCE` | Não copiar domínio, regra de bloqueio, código ou conclusão para WZM. |
| `manifest.json`, `.shard`, tamanho ou estrutura de streaming relatados em fóruns sobre WZM | `SECONDARY_SOURCE_CLAIM` até confirmação na build exata | Não há artefato local verificado. Não baixar shards completos; inventário permitido continua sendo ZIP/apktool e HEAD/Range, conforme o escopo M2.2/M2.2.1. |
| `cdn.*`, `analytic.*`, `stun.*` e padrões de hostname como endpoints WZM | `UNKNOWN` | Padrões de busca não são endpoints descobertos. Não criar manifestos, allowlists ou respostas com base neles. |

As fontes comunitárias antigas podem orientar perguntas de pesquisa, mas não promovem uma hipótese a `WARZONE_VERIFIED`. Os procedimentos históricos de Frida/MITM/hosts override não são parte do roadmap Stable e não devem ser executados.

---

## 5. Endpoint conhecido: escopo exato de `cdni.meta`

A pesquisa M2.2/M4.0 documenta uma consulta manual bem-sucedida, em 2026-10-04, a:

```text
https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta
```

O arquivo público retornou HTTP 200 e aproximadamente 320 bytes; contém `min_buildnum=19854920`, URL da loja e flags opacas. O documento [M4.0](m4.0-verificando-atualizacoes.md) registra corpo, método e limites.

**Leitura permitida:** a existência/resposta desse recurso foi verificada. **Não** foi demonstrado que o WZM fez essa requisição durante a janela “Verificando atualizações”, que esse arquivo seja suficiente para concluir a etapa ou que outros endpoints/pathnames existam. Nenhum hostname, manifesto ou formato adicional deve ser inventado.

---

## 6. `libgame.so`: análise estática somente quando houver artefato

A busca local documentada em 2026-10-04 não encontrou `.apk`, `.apkm`, `.xapk`, `.shard` nem `.so` no workspace. Sem o binário exato, nenhuma análise ou resultado de strings pode ser declarado.

Quando o artefato estiver disponível por meio permitido, o próximo passo é **read-only** e deve registrar:

1. Origem autorizada, versão/build, ABI, caminho do membro no APK e SHA-256 do APK e da biblioteca. Manter binários fora do Git.
2. Inventário do APK/XAPK e extração apenas do `.so` necessário. Para `.shard`, manter o limite de inventário ZIP/apktool e HEAD/Range; não baixar os arquivos completos.
3. Cabeçalho/dependências/símbolos ELF (`readelf`, `nm`) e strings com arquivo/offset/contexto mínimo (`strings`). Se necessário, disassembly estático em ferramenta local, sem execução ou instrumentação do jogo.
4. Classificação de cada achado: literal isolado = no máximo `PROBABLE`; referência/call-site contextualizado ainda não prova execução; somente fluxo de runtime atribuível pode estabelecer uso real.
5. Revisão/redação de qualquer saída antes de compartilhá-la. Não commitar APK, `.so`, dumps proprietários, tokens, credenciais ou payloads.

Não usar Frida, root, hook, MITM, CA de interceptação, patch/repack, nem alterar trust/pinning. Esta lista é um plano para quando o binário existir, não relato de análise concluída.

---

## 7. Controle positivo independente — ainda pendente

O `OwnerProbe` atual é do launcher e usa loopback; não substitui um teste com outro pacote/UID. O controle positivo deve ser uma sessão separada e explicitamente rotulada `CONTROL_ONLY`: confirmar package/UID via `PackageManager`, permitir somente o app de controle nessa sessão, gerar uma conexão TCP local controlada e exigir correlação da mesma tupla no TUN e no owner lookup. Não usar endpoint externo, corpo HTTP, credencial ou tráfego sintético como evidência WZM. A proposta e os pré-requisitos pendentes estão em [M3.6 §8.1](m3.6-caminho-real-de-rede.md#81-controle-positivo-real-com-outro-app-planejado-nao-executado).

Um controle positivo validaria a instrumentação para aquele app/fluxo; não provaria que o WZM usa o mesmo caminho. O caminho WZM só pode avançar quando houver evidência atribuível própria.

---

## 8. Restrições ativas

- Não modificar o APK do WZM nem contornar autenticação, anti-cheat ou credenciais.
- Não alterar TLS, trust, certificado ou pinning antes de demonstrar e atribuir um fluxo TCP/SYN real do WZM; mesmo depois, qualquer mudança permanece fora do escopo atual.
- Não usar Frida, root, hooks, MITM, CA de interceptação, patch ou repack.
- Não registrar corpo HTTP, cookie, token, credencial ou payload. Não inferir DoH/DoT por porta isolada.
- Manter Private DNS habilitado; não adicionar rota default/IPv6 para forçar uma hipótese.
- Não inferir endpoint por wildcard/padrão, por outro título ou por ausência no TUN.
- Não tratar loopback, teste sintético, owner UID do launcher ou pacote TUN sem UID como tráfego WZM.

---

## 9. Próximas etapas Stable

1. Preparar uma execução controlada com app de teste de UID distinto; primeiro implementar/validar o modo `CONTROL_ONLY`, sem misturar resultados com a sessão WZM.
2. No aparelho, repetir a sessão WZM sem mudar rede/Private DNS e exigir tuple + owner UID antes de atribuir SYN/TUN ao UID-alvo. PID/processo auxiliar continuará separado do UID.
3. Fazer análise estática de `libgame.so` somente quando o binário exato estiver disponível; registrar versionamento, hash, método e confiança.
4. Reavaliar qualquer questão TLS apenas depois de fluxo TCP/SYN do WZM demonstrado e atribuível. Nenhuma mudança de pinning está autorizada nesta etapa.

**Conclusão atual:** backend, seleção de rede pelo WZM, uso de `Network.bindSocket()`, endpoints de runtime e conteúdo de `libgame.so` permanecem `UNKNOWN`. A evidência histórica é limitada; nenhuma causa foi inferida.

---

## 10. Fontes e alcance

| Fonte/data registrada | Afirmação histórica associada | Classificação permitida agora |
|---|---|---|
| COD Fandom “Warzone Mobile” e NamuWiki “IW engine”, consultados em 2026-10-04 | IW 9.0/MGL e crédito “Demonware (assisted)” | `SECONDARY_SOURCE_CLAIM`; não substitui análise da build/binário. |
| `zzVertigo/CodOnline-Disected` (2021-02-04) | AUTH/LSG, 3074 e detalhes de COD Online | `OTHER_TITLE_REFERENCE`; nenhum endpoint/protocolo é transferido a WZM. |
| `hosseinpourziyaie/demonware-companion` (repo comunitário, data/version não registrada nesta revisão) | ferramenta de terceiros para pesquisa de Demonware | referência técnica externa; não é evidência de WZM nem código para copiar. |
| ResHax thread sobre `.shard` (2024-04-21), relato r/WarzoneMobile (2024-04-26), MobileMatters (2024-05-28) e GINX (2024-07-19) | relatos/tamanhos sobre streaming | `SECONDARY_SOURCE_CLAIM`; não verificados em APK WZM desta revisão. |
| M2.2/M4.0, consultas públicas documentadas em 2026-10-03/04 | paths CDNI/WebView e resposta de `cdni.meta` | `VERIFIED` para recurso/path consultado; não prova chamada do WZM fora dos eventos WebView já documentados. |
| M3.4/M3.6/M4.1, S23 Ultra, WZM 3.10.0.19854920, Android 13/14 | janelas históricas TUN/loopback/UID e limites de atribuição | `WARZONE_VERIFIED` somente para os eventos explicitamente descritos; autoria de fluxos sem owner UID continua `UNKNOWN`. |
| Pesquisa e projetos de Dead by Daylight | hosts, modo offline e implementação DbD | `DBD_REFERENCE` apenas; nunca evidência WZM. |
