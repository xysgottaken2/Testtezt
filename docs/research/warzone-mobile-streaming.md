# Warzone Mobile — streaming/assets (estado conservador Stable)

> **Atualizado:** 2026-10-04 · **Escopo:** inventário e pesquisa read-only.
> **Estado:** não há APK/XAPK real nem `.shard` no workspace; nenhum shard real foi identificado, pedido ou baixado. Instalação completa/streaming de assets permanece `UNKNOWN`.
> Esta página reclassifica hipóteses M0 antigas. Não usar nomes de fixture, relatos de fóruns ou padrões de URL como dados da build WZM.

---

## 1. O que está confirmado — e em qual escopo

| Fato | Classificação e limite |
|---|---|
| A cadeia WebView/CDNI contém paths específicos para builds 3.3.4/3.10.0 | `VERIFIED` no escopo descrito em [WZM 3.10 bootstrap](wzm-310-bootstrap.md) e [M2.2](m2.2-assets-cdni-investigation.md). A cadeia é de bootstrap/UI; não prova a lista de assets do jogo. |
| O endpoint `https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` retornou um arquivo pequeno com `min_buildnum=19854920` | `VERIFIED` como recurso consultado manualmente, conforme M2.2/M4.0. O arquivo não lista nomes de shards e não prova que o WZM o pediu durante a sessão observada. |
| O nome `manifest.json` ocorre na cadeia WebView de seleção de UI | `VERIFIED` para essa cadeia. Não confundir esse manifesto de `builds/root` com manifesto de conteúdo/shards. |
| `split_asset_pack/assets/shard/`, `.shard`, `IWffn100` e `KAPIs` foram relatados em fórum/comunidade sobre WZM | `SECONDARY_SOURCE_CLAIM` não reproduzido no APK local; não é inventário confirmado de uma versão WZM. |
| O workspace contém zero APK/XAPK e zero shard real | `VERIFIED` por [M2.2.1](m2.2.1-shard-inventory.md); ferramentas e exemplos sintéticos não mudam esse resultado. |
| A existência, tamanho, assinatura, criptografia, dependências ou disponibilidade CDN de shards reais | `UNKNOWN` até aparecer um artefato exato e uma lista de nomes comprovada. |

---

## 2. Não misturar dois manifestos diferentes

1. **Manifesto WebView/UI:** `prod.cdni.callofduty.com/manifest/manifest.json` é referenciado pelo seletor legado da WebView e contém seleção de `builds`/`root` para UI de pré-login. Sua existência e conteúdo consultado estão descritos em M2.2. Isso não o torna catálogo de arquivos `.shard`.
2. **Manifesto local/de conteúdo:** a existência e o caminho de um catálogo de shards dentro do pacote (por exemplo `assets/shard/manifest.json`) não foram confirmados em um APK WZM disponível neste workspace. `m2.2.1-shard-inventory.md` usa nomes semelhantes apenas em fixtures sintéticas.
3. **`cdni.meta`:** o arquivo remoto contém metadados de versão/flags; não contém lista de shards. Não inferir dele URL, hash, nome ou conteúdo de manifesto adicional.

Nenhum endpoint nativo de download de conteúdo ou URL de shard deve ser inventado a partir dos itens acima.

---

## 3. Resultado do inventário M2.2.1

- Busca no workspace: nenhum APK/XAPK/APKM, `.shard` ou `.so` real encontrado.
- `physicalShards.count=0` no workspace.
- `base.shard`, `textures_00.shard`, o `manifest.json` e `libgame.so` usados pelos testes são **fixtures sintéticas**; não são dados WZM.
- Os testes HEAD/Range/404 sobre nomes das fixtures validam a ferramenta e sua contenção de volume, não disponibilidade de shard real.
- Nenhuma consulta CDN com nome de shard WZM comprovado foi realizada; qualquer resultado para esses nomes permanece `UNKNOWN`.

A metodologia permitida está em [M2.2.1](m2.2.1-shard-inventory.md): só nomes encontrados em ZIP/apktool ou referências literais do artefato exato; para CDN, somente `HEAD`/`Range: bytes=0-1023`. Sem brute force e sem downloads completos.

---

## 4. Alegações M0 reclassificadas

| Alegação antiga | Estado atual |
|---|---|
| XAPK de determinado tamanho ou vários GB de shards | `OTHER_TITLE_REFERENCE`/relato não verificado nesta build; não usar como requisito/tamanho confirmado. |
| `manifest.json` lista hashes/dependências de conteúdo | `UNKNOWN` para o manifesto de shards; o manifesto de UI tem escopo diferente. |
| Shards começam por `IWffn100` ou usam KAPIs/xpak | `UNKNOWN` para artefato WZM real; o cabeçalho semelhante nos testes é sintético. |
| Shards são criptografados, assinados, obrigatórios/opcionais ou mapeiam Verdansk/Rebirth | `UNKNOWN`; não há amostra real analisada. |
| Domínios `cdn.*`, `*.demonware.net` ou outros hosts servem assets | `UNKNOWN` até endpoint/path exato encontrado no artefato ou observado em fluxo atribuível. |
| Redirecionar CDN para localhost ou servir um manifesto local pode iniciar o jogo | Hipótese antiga, não demonstrada e não parte do plano atual. Não implementar a partir de hostname inventado. |

---

## 5. Próxima pesquisa estática (quando o artefato estiver disponível)

1. Usar APK/XAPK de origem permitida e registrar versão/build e SHA-256; manter o binário fora do Git.
2. Inspecionar ZIP (`unzip -l`) antes de extrair. Extrair apenas o necessário com apktool para localizar arquivos/listas; não baixar shards grandes.
3. Procurar nomes reais em arquivos e referências do artefato exato. Preservar caminho e contexto mínimo; nomes de fixtures/docs não contam.
4. Para cada nome comprovado, consultar CDN somente por `HEAD` ou `Range: bytes=0-1023` e registrar status, tamanho e Content-Type. `404` de fixture não diz nada sobre shards WZM.
5. Se disponível, analisar `libgame.so` estaticamente, com build/hash/offset; string isolada não demonstra chamada nem uso em runtime. Ver [networking Stable](warzone-mobile-networking.md), §6.

Não executar o app sob hook; não usar Frida, root, MITM/CA, bypass de pinning, patch/repack ou alteração de TLS. Não registrar corpo HTTP, cookies, tokens, credenciais ou payloads.

---

## 6. Próximos passos e limites

- **Bloqueio atual:** falta o binário exato. Nenhuma descoberta de shards reais é possível sem nomes obtidos do artefato.
- **Sem download de volume:** a regra de M2.2.1 continua sendo inventário local + HEAD/Range por nome comprovado; não fazer GET completo de `.shard`.
- **Sem inferência de rede:** mesmo que o manifesto/arquivo exista, só uma sessão com fluxo atribuído por tupla/owner UID pode demonstrar que o WZM o requisitou. Ver [M3.6](m3.6-caminho-real-de-rede.md) e [M4.1](m4.1-dono-das-conexoes-loopback.md).
- **Sem modificação de TLS:** qualquer pergunta de trust/pinning permanece read-only e bloqueada até o caminho TCP/SYN do WZM estar demonstrado e atribuível.

**Conclusão:** bootstrap WebView e `cdni.meta` têm evidências próprias documentadas; catálogo, tamanho, conteúdo, disponibilidade e download de shards do WZM continuam `UNKNOWN`. Não há base para declarar assets completos ou uma arquitetura de streaming.

---

## 7. Fontes e alcance

| Fonte/data registrada | Alegação original | Uso atual |
|---|---|---|
| M2.2, consulta via `fetch_page` em 2026-10-03/04 | resposta de paths WebView/CDNI, manifests de UI e `cdni.meta` | `VERIFIED` para cada recurso/path e corpo consultado, no escopo anotado por M2.2. Não confundir o manifesto de UI com catálogo de shards. |
| M2.2.1, inventário do workspace em 2026-10-04 | ausência de APK/XAPK e shards reais; fixtures para scanner e HEAD/Range | `VERIFIED` para o bloqueio e funcionamento das fixtures; não é evidência sobre nomes/disponibilidade de shards WZM. |
| ResHax, thread de `.shard` (2024-04-21) | relatos sobre nomes/estrutura de arquivos | `SECONDARY_SOURCE_CLAIM`; não reproduzido no APK desta build. |
| r/WarzoneMobile, post sobre streaming (2024-04-26) | relato de jogador sobre assets em streaming | relato, não evidência de endpoint, tamanho ou requisito offline. |
| MobileMatters (2024-05-28) e GINX (2024-07-19) | estimativas de tamanhos de pacotes/atualizações | `SECONDARY_SOURCE_CLAIM`; não usar como tamanho verificado desta build. |
| `warzone-mobile-networking.md` + M3.6/M4.1 | ausência de artefato e limites de fluxo atribuído | fonte vigente para próximos passos e restrições Stable. |
