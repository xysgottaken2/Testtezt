package com.wzm.launcher.cdn

/**
 * Corpo **real e documentado** do `cdni.meta` (M3.5).
 *
 * Origem dos valores (público, sem auth, sem token):
 *  * `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` — 200, **397 B**
 *    (CRLF + 4 espaços; reobservado ao vivo 2026-10-08 — o "~320 B" anotado em M2.2 era a ordem de
 *    grandeza do `fetch_page`, não uma contagem de bytes: 386 B é o mesmo conteúdo em LF)
 *  * `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/ios/_manifest/cdni.meta` — 200, ~410 B (M2.2;
 *    tamanho não reconciliado com o literal, que tem 509 B em LF — ver docs/protocol/cdni-meta.md)
 *  * observado em M2.2 (2026-10-03) e reconfirmado na íntegra em 2026-10-04 (M4.0);
 *    campos descritos em `docs/protocol/cdni-meta.md`.
 *
 * Por que o corpo real entra no servidor local nesta fase: a evidência do M3.4/M4.0 não mostrava nenhuma
 * requisição do WZM chegando ao roteador; o primeiro recurso que faz sentido servir é justamente o único
 * cujo conteúdo **existe** (o `manifest.json` de conteúdo segue `UNKNOWN` — não é inventado aqui).
 *
 * O valor que importa para a checagem de versão é `min_buildnum = 19854920`, **igual** ao build instalado
 * `3.10.0.19854920` — ou seja, o cliente recebe a mesma resposta que receberia do CDN real
 * (`HYPOTHESIS` que ele use esse campo; o dado é `VERIFIED`).
 *
 * Nenhum campo é acrescentado/removido: alterar o corpo poderia invalidar a comparação do cliente.
 * A marcação de "servido localmente" fica no cabeçalho HTTP (`X-WZM-Offline`), não no JSON.
 */
object CdniMetaBody {

    /**
     * Build mínimo exigido pelo CDN (`VERIFIED` no recurso público, 2026-10-04/M4.0).
     * É **igual** ao build instalado `3.10.0.19854920`, portanto a condição "build >= mínimo" já
     * é satisfeita com o corpo real — sem alterar um único byte da resposta.
     */
    const val MIN_BUILDNUM = 19854920

    /** Build instalado observado no device (M4.0); só para leitura/documentação, nunca servido. */
    const val INSTALLED_BUILD_LABEL = "3.10.0.19854920"

    /**
     * Chaves `#x…` do corpo: **opacas por decisão**. São registradas exatamente como vieram do CDN
     * (chave + booleano), nunca decodificadas, renomeadas ou interpretadas — o significado de cada
     * flag é `UNKNOWN` e inferi-lo seria inventar comportamento (regra do projeto).
     */
    fun flagKeys(body: String = ANDROID): List<String> =
        Regex("\"(#x[0-9a-fA-F]+)\"\\s*:").findAll(body).map { it.groupValues[1] }.toList()

    /**
     * Idêntico ao corpo observado para Android — **inclusive a quebra de linha**.
     *
     * Reobservado ao vivo em 2026-10-08 (`GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta`):
     * o arquivo real usa **CRLF** (`\r\n`) com indentação de 4 espaços e **termina em `}` sem quebra final**
     * = 397 B. Antes deste incremento o launcher servia o mesmo conteúdo com LF (386 B): os campos e
     * valores batiam, a separação de linhas não. Como o corpo é a única coisa que o cliente lê aqui,
     * "exatamente o JSON real" passou a significar byte a byte (M7): as linhas abaixo são unidas por CRLF
     * exatamente como chegam do CDN. Nenhum campo foi acrescentado/removido/reordenado.
     */
    val ANDROID: String = """
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
""".trimIndent().replace("\n", "\r\n")

    /**
     * Corpo observado para iOS (inclui `future_*` e uma flag extra) — **não reobservado em 2026-10-08**,
     * então continua na renderização original com LF e **não** é afirmado byte a byte: o documento de
     * protocolo registra `~410 B` para o arquivo iOS, enquanto este literal tem 509 B (LF) / 523 B (CRLF).
     * A divergência de tamanho está registrada como `UNKNOWN` em `docs/protocol/cdni-meta.md`; enquanto
     * ela não for liquidada por uma reobservação, o caminho iOS serve o conteúdo verificado (campos e
     * valores) sem reivindicação de bytes idênticos. O experimento M7 é do caminho **android**.
     */
    val IOS: String = """
{
    "min_tu": 0,
    "min_buildnum": 19854920,
    "app_store_url": "https://apps.apple.com/app/id1638368439",
    "#x3a74898c63cb5c55a": true,
    "#x3e9cc40e792dcfdcc": true,
    "#x3e93a69101751bfa8": false,
    "#x3b20ca17f00dfb4c9": false,
    "#x3c839f93c3b076242": true,
    "#x3bc57a21a42173b49": true,
    "#x3febec63a7c2351ab": false,
    "future_app_id": "com.activision.callofduty.warzone",
    "future_app_store_url": "https://apps.apple.com/app/id1638368439",
    "#x377addea98016dad6": true
}
""".trimIndent()

    /** Bytes prontos para servir (UTF-8, sem BOM — igual ao observado). */
    fun android(): ByteArray = ANDROID.toByteArray(Charsets.UTF_8)

    fun ios(): ByteArray = IOS.toByteArray(Charsets.UTF_8)

    /** Assinatura mínima que o corpo precisa manter para continuar sendo "o cdni.meta real". */
    fun looksLikeRealBody(text: String): Boolean =
        text.contains("\"min_buildnum\"") &&
            text.contains("19854920") &&
            text.contains("\"app_store_url\"")
}
