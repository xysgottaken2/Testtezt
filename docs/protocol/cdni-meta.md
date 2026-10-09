# Protocolo — CDN SHARD Meta: cdni.meta

> **Nunca inventar.** Preencher só com observação.

---

## Identificação

- **Name:** `CDNIShardMeta`
- **Direction:** WZM caller `UNKNOWN`; recurso CDN foi consultado manualmente via `fetch_page` (GET anônimo). Não há captura/owner UID que prove que o WZM pediu este path.
- **Transport:** `HTTPS`
- **Endpoint:** `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` (Android) e `…/ios/_manifest/cdni.meta` (iOS)
- **Encoding:** `JSON` (application/json, sem BOM, `\r\n` + 4-space indent) — **reconfirmado ao vivo 2026-10-08**: o arquivo usa CRLF; o launcher servia LF até M7 (mesmo conteúdo, 11 bytes a menos), agora serve CRLF byte a byte
- **Encryption:** `TLS` (Akamai edgesuite)
- **Size:** **397 B** medidos (android, CRLF + 4 espaços, sem quebra final; reobservado 2026-10-08) / ~410 B (ios, M2.2)
  - O `~320 B` registrado em M2.2/M2.2.1/M3 era uma ordem de grandeza do corpo devolvido pela ferramenta de fetch, **não** contagem de bytes: o mesmo conteúdo em LF dá 386 B e em CRLF 397 B. Correção lavrada em M7; os documentos históricos (M2.2, M2.2.1, M3) ficam como foram observados.
  - `ios`: **discrepância não liquidada** — o documento registra ~410 B e o literal em `CdniMetaBody.IOS` tem 509 B (LF) / 523 B (CRLF). Como não houve reobservação do arquivo iOS em 2026-10-08, o corpo iOS segue servido como estava e a fidelidade byte a byte dele é `UNKNOWN`. O caminho do experimento M7 é **android**.
- **Version:** `min_buildnum: 19854920`, `min_tu: 0` (ambas plataformas) — WZM 3.x até 4.x
- **Observed:** `2026-10-03T…Z` e **reconfirmado em 2026-10-04** via `fetch_page` (externo, sem auth, sem volume) — `200` para ambos; `Not a file` para diretórios, `Not found` para inexistente (prova Akamai diferencia)

## Campos

| # | Campo | Tipo | Obrigatório | Descrição | Exemplo |
|---|---|---|---|---|---|
| 1 | `min_tu` | `int` | sim | Minimum TU? (title update) — 0 | `0` |
| 2 | `min_buildnum` | `int` | sim | Build mínimo compatível | `19854920` |
| 3 | `app_store_url` | `string` | sim | URL da loja | `https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone` (android) / `https://apps.apple.com/app/id1638368439` (ios) |
| 4 | `future_app_id` | `string` | não (só ios) | ID futuro | `com.activision.callofduty.warzone` |
| 5 | `future_app_store_url` | `string` | não (só ios) | URL futura | `https://apps.apple.com/app/id1638368439` |
| 6 | `#x…` (flags hex) | `bool` | sim | Feature flags hasheadas (8–10 chaves) | `"#x3a74898c63cb5c55a": true` |
| — | — | — | — | — | — |

> Flags hex são opacas — não decodificadas; comportamento `UNKNOWN`.

## Exemplo (redigido — android, 2026-10-03)

```json
{
    "min_tu": 0,
    "min_buildnum": 19854920,
    "app_store_url": "https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone",
    "#x3a74898c63cb5c55a": true,
    "#x3e9cc40e792dcfdcc": true,
    "#x3e93a69101751bfa8": false,
    "#x3b20ca17f00dfb4c9": false,
    "#x3c839f93c3b076242": true,
    "#x3bc57a21a42173b49": true,
    "#x377addea98016dad6": true
}
```

iOS adiciona `"#x3febec63a7c2351ab": false` + `future_*`.

## Evidência

- **SOURCE:** `prod.cdni.callofduty.com` Akamai (`edgesuite.net` → `a224.dscw27.akamai.net`)
- **EVIDENCE (2026-10-08, M7):** `fetch_page https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` → corpo acima **com `\r\n` explícito entre as 12 linhas e sem quebra final** = 397 B; observado duas vezes no mesmo dia, resultado idêntico (10 membros, mesma ordem, mesmos 7 booleanos). `android`: `VERIFIED` byte a byte.
- **EVIDENCE (2026-10-03/04):** `fetch_page` → JSON acima (320 B); `…/ios/…` → 410 B; ambos `200`. Diretórios `…/android/` → ````\nNot a file\n```` (403-like). Ver `docs/research/m2.2-assets-cdni-investigation.md` §3.
- **CONFIDENCE:** `VERIFIED — observado live 200 via fetch_page 2026-10-03` (sem auth, sem token)

## Comportamento observado

- CDN retorna 200 sem auth — arquivo público, não assinado, sem `Authorization`.
- Cliente deve comparar `buildnum` local com `min_buildnum` para decidir update obrigatório — `HYPOTHESIS` (não observado em pcap).
- **2026-10-04:** o build instalado `3.10.0.19854920` é **igual** a `min_buildnum` (`19854920`) — a condição “build >= mínimo” já é satisfeita com o valor real do CDN (`VERIFIED` quanto aos valores; o uso pelo cliente segue `HYPOTHESIS`).
- **Não contém** URL/base URL/hash de manifesto. O mecanismo pelo qual o cliente obteria manifesto de conteúdo continua `UNKNOWN`; não inferir que outro endpoint seja fornecido pelo cliente até análise do APK/call-site — ver `docs/research/m4.0-verificando-atualizacoes.md` §6.
- A ferramenta `warzone-offline/tools/apk-analysis/update-check-scan.py` (M4.0) procura `cdni.meta`, `min_buildnum`, `min_tu` e `app_store_url` no APK para dizer onde essa comparação acontece.
- `Not a file` para diretório indica Akamai não permite listing — não brute-forceável.
- Não contém lista de shards — não é catálogo; é config de versão/flags. Catálogo real ainda `UNKNOWN`.

## Servido localmente pelo launcher (M3.5) — não é prova de pedido do WZM

- O launcher devolve o corpo documentado em `/wzm/shard_cdn/{android,ios}/_manifest/cdni.meta`, em
  `CdniMetaBody`; o cabeçalho HTTP local identifica a resposta (`X-WZM-Offline`). Isso descreve uma rota
  do **servidor do launcher**, não uma alteração/configuração do cliente WZM.
- O corpo real foi usado no teste sintético iniciado pelo launcher. Não há prova de que o WZM tenha pedido
  esse path, que seja o primeiro recurso de sua sessão ou que o certificado local seja aceito pelo WZM.
- A análise estática posterior M4.3 encontrou templates/call-sites de manifesto e `.shard` no `.so`; isso confirma presença no binário, não uma requisição do WZM nem o efeito local. Ver
  `docs/research/m3.5-loopback-e-teste-sintetico.md`, `docs/research/m4.0-verificando-atualizacoes.md`,
  [M4.3](../research/m4.3-libgame-static-analysis.md) e [M4.2 histórico](../research/m4.2-configuracao-endpoint-local.md).

## Experimento M4.4 (2026-10-05) — servir o `cdni.meta` localmente

Pergunta do experimento: **se o WZM receber este `cdni.meta` do servidor local, ele avança da tela
“Conectando a servidor de atualizações” ou faz outra requisição?** (`UNKNOWN` até observação.)

### O que já existia (não foi alterado)

| Item | Estado |
|---|---|
| `GET /wzm/shard_cdn/android/_manifest/cdni.meta` | `200`, `Content-Type: application/json; charset=utf-8`, corpo **real** (`CdniMetaBody.android()`), cabeçalhos `X-WZM-Offline: VERIFIED` + `X-WZM-Offline-Path` |
| Corpo | idêntico ao observado ao vivo (`min_tu=0`, `min_buildnum=19854920` = build instalado `3.10.0.19854920`, `app_store_url` da Play Store e as 7 chaves `#x…`) |
| Onde | `BootstrapEndpoints` → `CdnRouteTable.respond()` → `TcpRelay.serve()` → `LocalHttpsServer` |

Nenhum valor foi trocado por placeholder e nenhuma chave `#x…` foi decodificada: elas continuam
sendo registradas como **opacas** (`CdniMetaBody.flagKeys()`), com significado `UNKNOWN`.

### O que foi acrescentado (só observação)

1. **Origem por requisição** — toda linha `[CDNI]`/`[HTTP]` passa a sair com `origem=`:
   * `SINTETICO-LAUNCHER` = caiu dentro da janela do teste sintético aberto pelo launcher;
   * `FORA-DA-JANELA-SINTETICA` = **não** foi o nosso teste. Isso **não** é atribuição ao WZM:
     sem owner UID/tupla a autoria continua `UNKNOWN` (M4.1).
2. **Linha própria do próximo pedido** — tag nova `[CDNI-META]`:
   * `cdni.meta servido: status=… resposta=… B · cliente=… · origem=…`;
   * `PROXIMO-PEDIDO-APOS-CDNI.META: GET <path> · status=… · host=… · Δ=… ms depois do meta ·
     cliente=… · origem=…`. O **primeiro** pedido depois do meta ganha essa linha; os seguintes
     levam só `apos-cdni.meta=Δ…ms`. Um path desconhecido (404 controlado) entra igual — é assim
     que a próxima URL tentada pelo cliente aparece no log, sem inventar resposta.
3. **Contadores** (`cdniMetaServidos`, `cdniMetaSintetico`, `cdniMetaForaDaJanela`,
   `pedidosAposCdniMeta`) no cabeçalho exportado e nos cards.
4. Rastreio (`CdniMetaFlow`) — nada de TLS/pinning, TUN, DNS virtual ou roteamento foi alterado.

## Experimento M7 (2026-10-08) — byte a byte e contrato travado

Análise curta + mapeamento dos 12 itens do escopo: `docs/research/m7-cdni-meta-experimento-local.md`.

- **Correção de fidelidade:** o `cdni.meta` android do CDN usa **CRLF** + 4 espaços, sem quebra final
  (reobservado ao vivo 2026-10-08, duas vezes, idêntico) = **397 B**. Até aqui o launcher servia o mesmo
  conteúdo em **LF** (386 B). `CdniMetaBody.ANDROID` passou a ser unido por `\r\n`; nenhum campo, ordem ou
  valor mudou, e as chaves `#x…` seguem opacas (extraídas por regex, nunca interpretadas).
- **Contrato:** `CdniMetaResponseContractTest` trava a resposta inteira — corpo golden escrito por extenso
  no teste (independente de `CdniMetaBody`), os 7 cabeçalhos na ordem exata e sem duplicata,
  `Content-Length: 397`, ausência de BOM e de quebra final, caminho android nunca servindo o corpo iOS, e
  query string não alterando a resposta.
- **Fora do escopo M7 (deliberadamente não implementado):** `environment.config`, manifests de conteúdo,
  downloads/shards, TLS/pinning, TUN/VPN, DNS, autenticação. Path desconhecido continua em 404 controlado.
- O que o launcher devolve no total: `220 B` de cabeçalho + `397 B` de corpo = `617 B` por resposta.

### Como ler o resultado

- `cdni.meta` servido **fora da janela sintética** = um cliente que não é o nosso teste chegou ao
  listener e pediu o meta (TLS com o certificado local aceito). Ainda assim, **quem** é esse
  cliente só vira `VERIFIED` com owner UID do pacote-alvo.
- Nenhum pedido fora da janela = o experimento não observou o WZM nesse caminho; isso **não** prova
  que o jogo não tentou (pode não ter resolvido o host pelo nosso DNS, pode ter recusado o
  certificado local — o log `[TLS] FALHA … motivo=…` é o lugar onde isso apareceria).
- Próximo pedido registrado `404` = o path é real (veio do cliente) e desconhecido para nós:
  registrar, não inventar corpo.

## Notas

- Não confundir com `manifest.json` do bootstrap (`/manifest/manifest.json`) — `cdni.meta` é **shard CDN** (`wzm/shard_cdn`), `manifest.json` é **WebView prelogin** (`/manifest/`).
- Para shards, próximo passo M2.2.1: extrair `manifest.json` do APK (`split_asset_pack`) e testar `HEAD Range` por shard nomeado (sem brute force).
