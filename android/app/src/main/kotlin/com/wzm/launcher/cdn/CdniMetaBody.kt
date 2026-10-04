package com.wzm.launcher.cdn

/**
 * Corpo **real e documentado** do `cdni.meta` (M3.5).
 *
 * Origem dos valores (público, sem auth, sem token):
 *  * `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/cdni.meta` — 200, ~320 B
 *  * `GET https://prod.cdni.callofduty.com/wzm/shard_cdn/ios/_manifest/cdni.meta` — 200, ~410 B
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

    /** Idêntico ao corpo observado para Android. */
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
""".trimIndent()

    /** Idêntico ao corpo observado para iOS (inclui `future_*` e uma flag extra). */
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
