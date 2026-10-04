package com.wzm.launcher.cdn

/**
 * Decide como responder as consultas DNS do WZM que chegam pelo túnel.
 *
 * - Hosts CDNI comprovados (`prod.cdni.callofduty.com` e 1 nível abaixo) → resposta A = 127.0.0.1
 *   (o cliente então conecta no servidor HTTPS local). AAAA/HTTPS → NOERROR sem respostas
 *   (força IPv4; evita que o cliente tente IPv6).
 * - Qualquer outro domínio → [answer] devolve null e o chamador encaminha para DNS real.
 */
class DnsResponder(
    private val redirectIp: String = CdnRouterConfig.REDIRECT_TO,
    private val exactHost: String = CdnRouterConfig.EXACT_HOST,
    private val wildcardSuffix: String = CdnRouterConfig.WILDCARD_SUFFIX
) {

    /** `prod.cdni.callofduty.com` ou exatamente 1 rótulo sob `cdni.callofduty.com`. */
    fun isIntercepted(name: String): Boolean {
        val normalized = name.trimEnd('.').lowercase()
        if (normalized == exactHost) return true
        val suffix = ".$wildcardSuffix"
        if (!normalized.endsWith(suffix)) return false
        val prefix = normalized.removeSuffix(suffix)
        return prefix.isNotEmpty() && !prefix.contains('.')
    }

    /** Resposta pronta, ou null quando a consulta não é nossa (deve ser encaminhada). */
    fun answer(query: ByteArray, length: Int): ByteArray? {
        val question = DnsMessage.parseQuery(query, length) ?: return null
        if (!isIntercepted(question.name)) return null
        val answers = when (question.qType) {
            DnsMessage.TYPE_A -> listOf(AddressRecord(question.name, redirectIp))
            // Sem AAAA (nada de IPv6) e sem SVCB/HTTPS: NOERROR vazio é resposta válida.
            else -> emptyList()
        }
        return DnsMessage.buildResponse(query, length, question, answers)
    }
}
