package com.wzm.launcher.cdn

/** Resultado completo (bytes + metadados de log) do atendimento de uma requisição. */
data class HttpOutcome(
    val status: Int,
    val confidence: Confidence,
    val bytes: ByteArray,
    val logTag: String,
    val logMessage: String
) {
    val isKnown: Boolean get() = status == 200

    override fun equals(other: Any?): Boolean =
        other is HttpOutcome && status == other.status && logMessage == other.logMessage

    override fun hashCode(): Int = status * 31 + logMessage.hashCode()
}

/**
 * Decide a resposta para cada requisição recebida no servidor local.
 *
 * - Endpoint comprovado (M2/M2.2) → 200 com placeholder marcado ([Confidence.VERIFIED]).
 * - Nome conhecido, caminho inferido → 200 marcado como [Confidence.HYPOTHESIS].
 * - Desconhecido → 404 com erro controlado + URL/path exatos (nunca inventamos manifest).
 * - Endpoints internos `/__wzm_offline/*` → diagnóstico.
 */
object CdnRouteTable {

    const val HEALTH_PATH = "/__wzm_offline/health"
    const val REQUESTS_PATH = "/__wzm_offline/requests"

    fun respond(head: HttpRequestHead): HttpOutcome {
        val path = head.path
        when (path) {
            HEALTH_PATH -> return internal(path, healthBody())
            REQUESTS_PATH -> return internal(path, requestsBody())
        }
        if (path == "/favicon.ico") {
            return HttpOutcome(
                status = 200,
                confidence = Confidence.INTERNAL,
                bytes = HttpResponses.build(200, "image/x-icon", ByteArray(0)),
                logTag = "CDNI",
                logMessage = "favicon.ico -> 200 vazio (interno)"
            )
        }
        val endpoint = BootstrapEndpoints.lookup(path)
        if (endpoint != null) {
            val body = runCatching { endpoint.body() }.getOrElse { ByteArray(0) }
            val marker = if (endpoint.confidence == Confidence.VERIFIED) "VERIFICADO" else "HIPÓTESE"
            return HttpOutcome(
                status = 200,
                confidence = endpoint.confidence,
                bytes = HttpResponses.build(
                    status = 200,
                    contentType = endpoint.contentType,
                    body = body,
                    extraHeaders = listOf(
                        "X-WZM-Offline" to endpoint.confidence.name,
                        "X-WZM-Offline-Path" to endpoint.path
                    )
                ),
                logTag = "CDNI",
                logMessage = "${head.rawLine} -> 200 ($marker, ${body.size} B) ${endpoint.note}"
            )
        }
        return unknown(head)
    }

    private fun unknown(head: HttpRequestHead): HttpOutcome {
        val path = head.path
        val raw = head.target
        val query = head.query
        val body = (
            """{"error":"unknown_cdni_endpoint","marker":"$PLACEHOLDER_MARKER",""" +
                """"method":"${HttpResponses.jsonEscape(head.method)}",""" +
                """"path":"${HttpResponses.jsonEscape(path)}",""" +
                """"target":"${HttpResponses.jsonEscape(raw)}",""" +
                (if (query != null) """"query":"${HttpResponses.jsonEscape(query)}",""" else "") +
                """"host":"${HttpResponses.jsonEscape(head.host ?: "?")}",""" +
                """"hint":"endpoint não implementado — path registrado no launcher; nenhuma resposta foi inventada"}"""
            ).toByteArray(Charsets.UTF_8)
        return HttpOutcome(
            status = 404,
            confidence = Confidence.HYPOTHESIS,
            bytes = HttpResponses.build(
                status = 404,
                contentType = "application/json; charset=utf-8",
                body = body,
                extraHeaders = listOf("X-WZM-Offline" to "UNKNOWN")
            ),
            logTag = "CDNI?",
            logMessage = "DESCONHECIDO: ${head.rawLine} (host=${head.host ?: "?"}) -> 404 controlado"
        )
    }

    private fun internal(path: String, body: ByteArray): HttpOutcome = HttpOutcome(
        status = 200,
        confidence = Confidence.INTERNAL,
        bytes = HttpResponses.build(200, "application/json; charset=utf-8", body),
        logTag = "CDNI",
        logMessage = "GET $path -> 200 (interno)"
    )

    private fun healthBody(): ByteArray {
        val counters = RequestLog.counters.value
        val endpoints = BootstrapEndpoints.all().joinToString(",") { endpoint ->
            """{"path":"${endpoint.path}","confidence":"${endpoint.confidence.name}",""" +
                """"note":"${HttpResponses.jsonEscape(endpoint.note)}"}"""
        }
        return (
            """{"marker":"$PLACEHOLDER_MARKER","service":"local-cdn-router",""" +
                """"counters":{"dnsQueries":${counters.dnsQueries},"dnsIntercepted":${counters.dnsIntercepted},""" +
                """"httpRequests":${counters.httpRequests},"unknownRequests":${counters.unknownRequests},""" +
                """"tlsOk":${counters.tlsOk},"tlsFailed":${counters.tlsFailed}},""" +
                """"endpoints":[$endpoints]}"""
            ).toByteArray(Charsets.UTF_8)
    }

    private fun requestsBody(): ByteArray {
        val counters = RequestLog.counters.value
        val header = "# contadores: dnsQueries=${counters.dnsQueries} dnsIntercepted=${counters.dnsIntercepted} " +
            "httpRequests=${counters.httpRequests} unknownRequests=${counters.unknownRequests} " +
            "tlsOk=${counters.tlsOk} tlsFailed=${counters.tlsFailed}\n"
        return (header + RequestLog.snapshot() + "\n").toByteArray(Charsets.UTF_8)
    }
}
