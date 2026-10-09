package com.wzm.launcher.cdn

/** Nível de evidência de um endpoint servido localmente (disciplina do projeto). */
enum class Confidence {
    /** Comprovado em M2/M2.2 (request real observado ou artefato capturado). */
    VERIFIED,

    /** Nome conhecido, caminho/extensão inferidos — pode estar errado (será logado como hipótese). */
    HYPOTHESIS,

    /** Endpoint do próprio launcher (diagnóstico). */
    INTERNAL
}

/**
 * Endpoint CDNI servido pelo servidor local.
 *
 * [body] é o corpo efetivamente devolvido. Dois casos:
 *  * [realUpstreamBody] = `true` → o corpo é o **conteúdo real público** já observado (hoje só o
 *    `cdni.meta`, M3.5) e é servido sem alterações (a marcação de servidor local vai no cabeçalho HTTP);
 *  * `false` → o corpo upstream nunca foi obtido (manifest de conteúdo, JS/CSS) e servimos um
 *    **placeholder marcado** — nunca um manifest inventado.
 *
 * Ver `docs/research/m3-cdni-integration.md` e `docs/research/m3.5-loopback-e-teste-sintetico.md`.
 */
data class CdnEndpoint(
    val path: String,
    val method: String,
    val contentType: String,
    val body: () -> ByteArray,
    val confidence: Confidence,
    val note: String,
    /** Corpo real observado servido localmente (não é placeholder). */
    val realUpstreamBody: Boolean = false
)

/** Marcador presente em todo corpo servido localmente (facilita grep/log). */
const val PLACEHOLDER_MARKER = "wzm-offline-local"

/**
 * Tabela de endpoints de bootstrap/CDNI já comprovados (M2 / M2.2) + aliases de hipótese.
 * Não há aqui nenhuma resposta "de manifest real" inventada.
 */
object BootstrapEndpoints {

    private fun text(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)

    private fun jsPlaceholder(path: String, observedSize: String): ByteArray = text(
        """
        /* $PLACEHOLDER_MARKER (M3) — endpoint servido pelo servidor local
         * path: $path
         * content-length upstream observado: $observedSize (M2.2)
         * Corpo upstream real NÃO capturado -> placeholder (UNKNOWN, ver docs/research/m3-cdni-integration.md)
         * Nenhum dado de manifest/auth foi inventado.
         */
        window.__wzmOffline = {path: "$path", servedBy: "$PLACEHOLDER_MARKER", upstreamBodyCaptured: false};
        """.trimIndent()
    )

    private fun jsonPlaceholder(path: String, observedSize: String): ByteArray = text(
        """{"_marker":"$PLACEHOLDER_MARKER","_endpoint":"$path","_note":"corpo upstream real não capturado (UNKNOWN)","_observedUpstreamBytes":"$observedSize"}"""
    )

    private val ENDPOINTS: List<CdnEndpoint> = listOf(
        CdnEndpoint(
            path = "/manifest/build-selector-103.js",
            method = "GET",
            contentType = "application/javascript; charset=utf-8",
            body = { jsPlaceholder("/manifest/build-selector-103.js", "~130 B") },
            confidence = Confidence.VERIFIED,
            note = "M2.2: 1º request do bootstrap; upstream 130 B (redirect). M2 servia redirecionamento p/ offline."
        ),
        CdnEndpoint(
            path = "/static/web/index.html",
            method = "GET",
            contentType = "text/html; charset=utf-8",
            body = {
                text(
                    """<!doctype html>
                    |<html lang="en"><head><meta charset="utf-8"><title>WZM Offline</title></head>
                    |<body>
                    |<h1>$PLACEHOLDER_MARKER</h1>
                    |<p>path: /static/web/index.html (servido pelo servidor local, M3)</p>
                    |<p>O HTML real do WebView não foi capturado (UNKNOWN) — este é um placeholder.</p>
                    |</body></html>
                    """.trimMargin()
                )
            },
            confidence = Confidence.VERIFIED,
            note = "M2: documento carregado no WebView após o build-selector."
        ),
        CdnEndpoint(
            path = "/manifest/build-selector-102.js",
            method = "GET",
            contentType = "application/javascript; charset=utf-8",
            body = { jsPlaceholder("/manifest/build-selector-102.js", "~4.2 KB") },
            confidence = Confidence.VERIFIED,
            note = "M2.2: segunda etapa do build-selector (fetch_page)."
        ),
        CdnEndpoint(
            path = "/manifest/manifest.json",
            method = "GET",
            contentType = "application/json; charset=utf-8",
            body = { jsonPlaceholder("/manifest/manifest.json", "~1.6 KB") },
            confidence = Confidence.VERIFIED,
            note = "M2.2: manifesto de manifesto (cadeia popup/events/dailylogin)."
        ),
        CdnEndpoint(
            path = "/prelogin/boot-15.0.0/web/manifest.json",
            method = "GET",
            contentType = "application/json; charset=utf-8",
            body = { jsonPlaceholder("/prelogin/boot-15.0.0/web/manifest.json", "UNKNOWN") },
            confidence = Confidence.VERIFIED,
            note = "M2.2: manifesto do boot web (versão 15.0.0)."
        ),
        CdnEndpoint(
            path = "/prelogin/boot-15.0.0/web/static/js/main.js",
            method = "GET",
            contentType = "application/javascript; charset=utf-8",
            body = { jsPlaceholder("/prelogin/boot-15.0.0/web/static/js/main.js", "UNKNOWN") },
            confidence = Confidence.VERIFIED,
            note = "M2.2: bundle referenciado pelo boot web."
        ),
        CdnEndpoint(
            path = "/prelogin/boot-15.0.0/web/static/css/main.css",
            method = "GET",
            contentType = "text/css; charset=utf-8",
            body = {
                text("/* $PLACEHOLDER_MARKER (M3) — /prelogin/boot-15.0.0/web/static/css/main.css (placeholder) */\n")
            },
            confidence = Confidence.VERIFIED,
            note = "M2.2: css referenciado pelo boot web."
        ),
        CdnEndpoint(
            path = CdnRouterConfig.CDNI_META_PATH,
            method = "GET",
            contentType = "application/json; charset=utf-8",
            body = { CdniMetaBody.android() },
            confidence = Confidence.VERIFIED,
            note = "M2.2/M4.0/M7: corpo REAL observado (android, 397 B CRLF + 4 espaços; " +
                "min_buildnum=19854920) — servido byte a byte, sem alterações, para o teste do " +
                "caminho CDNI (M3.5; reobservado ao vivo 2026-10-08).",
            realUpstreamBody = true
        ),
        CdnEndpoint(
            path = "/wzm/shard_cdn/ios/_manifest/cdni.meta",
            method = "GET",
            contentType = "application/json; charset=utf-8",
            body = { CdniMetaBody.ios() },
            confidence = Confidence.VERIFIED,
            note = "M2.2/M4.0: corpo REAL observado (ios, ~410 B; inclui future_*) — servido sem " +
                "alterações; tamanho/EOL do arquivo iOS segue não reconciliado (ver docs/protocol/cdni-meta.md).",
            realUpstreamBody = true
        )
    )

    /** Nomes da cadeia do manifesto (popup/events/dailylogin) — extensão/caminho exatos = UNKNOWN. */
    private val HYPOTHESIS_BASENAMES = setOf("popup", "events", "dailylogin")

    private val byPath: Map<String, CdnEndpoint> =
        ENDPOINTS.associateBy { normalize(it.path) }

    fun all(): List<CdnEndpoint> = ENDPOINTS

    fun lookup(path: String): CdnEndpoint? {
        val normalized = normalize(path)
        byPath[normalized]?.let { return it }
        if (normalized.startsWith("/manifest/")) {
            val file = normalized.substringAfterLast('/')
            val base = file.substringBeforeLast('.').lowercase()
            if (base in HYPOTHESIS_BASENAMES) {
                return CdnEndpoint(
                    path = normalized,
                    method = "GET",
                    contentType = "application/json; charset=utf-8",
                    body = { jsonPlaceholder(normalized, "UNKNOWN") },
                    confidence = Confidence.HYPOTHESIS,
                    note = "nome da cadeia de manifesto (M2.2), caminho/extensão inferidos — hipótese."
                )
            }
        }
        return null
    }

    /** Normaliza para comparação: sem query, com barra inicial, sem duplicar barras. */
    fun normalize(path: String): String {
        var value = path.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val afterScheme = value.substringAfter("://")
            value = "/" + afterScheme.substringAfter('/', "")
        }
        value = value.substringBefore('?').substringBefore('#')
        if (!value.startsWith("/")) value = "/$value"
        while (value.contains("//")) value = value.replace("//", "/")
        return value.lowercase()
    }
}
