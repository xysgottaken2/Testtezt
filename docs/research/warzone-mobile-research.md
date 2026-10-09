# Warzone Mobile — índice da pesquisa M0 (reclassificado para Stable)

> **Atualizado:** 2026-10-05 · **Base:** pesquisa histórica e documentos M2–M4, incluindo a análise estática M4.3.
> **Este arquivo não comprova backend, protocolo, downloads de shards ou caminho runtime de rede do WZM.** As afirmações antigas abaixo foram substituídas por um quadro de evidências conservador.
> **Estado de trabalho:** caminho real WZM `UNKNOWN`; `libgame.so` do commit `67d8a52` foi analisada read-only, mas identidade WZM 3.10.0 não confirmada pelo ELF e nenhuma observação nova em device ocorreu.

---

## 1. Estado resumido

| Tema | Estado atual | Evidência / limite |
|---|---|---|
| Engine e versão interna do jogo | `UNKNOWN` nesta investigação | `libgame.so` é ELF64/AArch64; Build ID/changelist não confirmam independentemente engine ou WZM 3.10.0. Ver [M4.3](m4.3-libgame-static-analysis.md). |
| Backend, auth e matchmaking | `UNKNOWN` | Referências de outros títulos não identificam backend da build WZM. Auth e bypass permanecem fora de escopo. |
| Portas e protocolo gameplay | `UNKNOWN` | TCP 3074, UDP e outros exemplos de COD Online/PC são `OTHER_TITLE_REFERENCE`, não endpoints do WZM. |
| Bootstrap WebView/CDNI | Há paths específicos confirmados em docs M2 para builds identificadas | Ver [WZM 3.10 bootstrap](wzm-310-bootstrap.md) e [M2.2](m2.2-assets-cdni-investigation.md). Isso não é prova do caminho TCP/TUN do jogo. |
| `cdni.meta` | Endpoint público e arquivo consultado manualmente confirmados | `min_buildnum=19854920`; não contém lista de shards nem demonstra requisição feita pelo WZM. Ver [M4.0](m4.0-verificando-atualizacoes.md). |
| Shards/manifesto de conteúdo | Templates de endpoint confirmados no binário; inventário de assets/shards e uso runtime `UNKNOWN` | M4.3 encontrou paths `environment.config`, manifest, `.shard` e agregados; M2.2.1 continua registrando zero shards físicos/fixtures. Não inventar nomes nem baixar arquivos completos. |
| Dead by Daylight offline | `DBD_REFERENCE` | Serve apenas como corpus separado; nenhum host, patch, endpoint ou implementação é evidência WZM. |
| Caminho real da VPN/TUN/UID | `UNKNOWN` | Ver [M3.6](m3.6-caminho-real-de-rede.md) e [M4.1](m4.1-dono-das-conexoes-loopback.md). A allow-list e rotas configuradas não provam captura de socket WZM. |
| Configuração direta para endpoint local | `CANDIDATO_OVERRIDE_CDNI_NATIVO`; uso local/user-writable `UNKNOWN` | M4.3 encontrou registro/leitura de entrada hashed candidata fluindo ao setter nomeado no diagnóstico, com fallback Prod e URL até dispatch. A associação nome↔hash é provável; setter suportado pelo usuário, execução e envio não comprovados. TUN não está provado como necessário em toda configuração. |
| TLS/pinning | Sem mudança autorizada | Não tocar até haver fluxo TCP/SYN real atribuído ao UID do WZM; nenhuma hipótese de pinning é conclusão. |

---

## 2. Evidência interna relevante

### 2.1. Builds e bootstrap

A cadeia WebView descrita por M2.1/M2.2 separa build e fonte de evidência. `wzm-310-bootstrap.md` documenta material estático/runtime para 3.3.4 e 3.10.0; `m2.2-assets-cdni-investigation.md` documenta consultas anônimas de recursos CDNI específicos. Não extrapolar esses resultados para endpoints nativos de shards, auth ou gameplay.

### 2.2. Inventário de assets

`m2.2.1-shard-inventory.md` registra o resultado do workspace: nenhum APK/XAPK nem shard real disponível. O scanner e as checagens HEAD/Range foram exercitados com fixtures sintéticas; `base.shard`, `textures_00.shard` e a `.so` das fixtures **não** são arquivos de WZM. Shards reais permanecem `UNKNOWN`.

### 2.3. Rede do WZM

A evidência histórica do S23 está documentada em M3.4/M3.6/M4.1. Em uma janela específica não houve SYN TCP do UID-alvo no TUN; origem dos pacotes IPv6, processo das conexões loopback e causa da ausência permanecem `UNKNOWN`. O `OwnerProbe` de M4.1 é launcher-owned loopback, não controle positivo de outro app.

---

## 3. Reclassificação de alegações M0

| Alegação antiga | Estado Stable |
|---|---|
| “WZM usa Demonware/State Engine/Matchmaking+” | `UNKNOWN`; referências gerais/comunitárias não demonstram uso nesta build. |
| “AUTH/LSG TCP 3074”, handshake ou chave de COD Online | `OTHER_TITLE_REFERENCE`; não implementar nem usar como endpoint WZM. |
| “Ricochet mobile” | `UNKNOWN`; referências de PC não demonstram biblioteca/funcionalidade mobile. |
| “`bhvronline.com`/telemetria bloqueável” | `DBD_REFERENCE`; não transferir para WZM. |
| “shards de tamanho X, manifesto chamado `manifest.json` ou nomes `base.shard`/`shard_0`” | `UNKNOWN` para WZM; nomes encontrados em fixtures ou fóruns não são inventário da build. |
| “WZM está offline por causa de auth/CDN/telemetria” | `UNKNOWN`; nenhuma causa deve ser inferida por ausência de resposta ou pacote. |
| “é preciso mockar auth/gameplay para jogar offline” | hipótese antiga não validada e fora do escopo atual. |

As páginas históricas de M0 não autorizam Frida, root, MITM/CA, hook, bypass de pinning, hosts override, bloqueio de telemetria ou alteração de autenticação. Consultar M3.2/M3.6/M4.1 para as restrições vigentes.

---

## 4. Como avançar sem inventar

1. **Controle positivo:** preparar um app com UID distinto em sessão `CONTROL_ONLY`, testar apenas a instrumentação e manter o resultado separado do WZM. O plano ainda não foi executado; ver [M3.6 §8.1](m3.6-caminho-real-de-rede.md#81-controle-positivo-real-com-outro-app-planejado-nao-executado).
2. **Observação WZM:** repetir no dispositivo sem alterar rotas/Private DNS e atribuir cada fluxo pela mesma tupla + owner UID. UID não identifica PID/helper; ausência de evento não é causa.
3. **`libgame.so`:** a leitura estática do arquivo de `origin/main` está documentada em [M4.3](m4.3-libgame-static-analysis.md). Próximo passo é obter o APK/build exato para confirmar a correspondência de versão e rastrear estaticamente a associação nome↔hash e a ligação de `dvar_overrides`/`android_dvars`/`.cfg` ao handle, sempre sem execução e com arquivos fora do Git.
4. **TLS/pinning:** manter read-only e sem alterações até uma conexão TCP/SYN WZM demonstrada e atribuível; nenhuma modificação é feita nesta etapa.

**Próximo bloqueio:** confirmar a versão do `.so` por metadados do APK/build exato, identificar se há um meio suportado de definir o valor candidato `cdni_httpServer` e obter uma futura observação de rede atribuível. Registro/leitura e dispatch estão documentados, mas não substituem prova de controle pelo usuário ou runtime.

---

## 5. Separação de fontes

- `DBD_REFERENCE`: apenas Dead by Daylight, conforme [comparação](comparison.md) e [pesquisa DbD](dead-by-daylight-mobile.md).
- `OTHER_TITLE_REFERENCE`: outros jogos COD/Warzone PC ou relatos genéricos.
- `WARZONE_VERIFIED`: somente o alcance estrito descrito no documento de evidência da build/dispositivo; não promover um fluxo sem owner UID.
- Fontes Android, limites de UID/PID e CI atual: [M4.1](m4.1-dono-das-conexoes-loopback.md).
