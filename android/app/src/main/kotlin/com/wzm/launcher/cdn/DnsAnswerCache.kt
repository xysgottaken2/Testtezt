package com.wzm.launcher.cdn

/**
 * Cache observacional de respostas DNS (M3.6) — responde à pergunta "de onde veio esse destino?"
 * quando o destino **não** é o alvo CDNI.
 *
 * Ideia: guardamos apenas o par **nome → endereços** que passou pelo túnel (seja a resposta virtual
 * do CDNI, seja uma resposta encaminhada do DNS real). Depois, quando um fluxo sai para um endereço
 * externo, comparamos o destino com o cache. Isso distingue, com observação:
 *
 *  * `destino casou com resposta DNS observada` → a resolução passou pelo túnel (mesmo que a consulta
 *    tenha sido respondida antes da janela observada);
 *  * `destino NÃO casou com nenhuma resposta observada` → o app resolveu por fora (DoH/DoT/cache do
 *    sistema) ou o destino veio embutido — **PROBABLE**, nunca afirmado como causa.
 *
 * Só endereços e nomes: nenhum payload, cookie ou credencial. Tudo é limitado e expira.
 * Acesso concorrente (thread do TUN escreve, vigia lê) é protegido por `synchronized`.
 */
class DnsAnswerCache(
    private val maxEntries: Int = 64,
    private val ttlMs: Long = 300_000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Entry(val host: String, val address: String, val atMs: Long, val source: String) {
        fun ageSeconds(nowMs: Long): Double = (nowMs - atMs) / 1000.0
    }

    private val entries = ArrayDeque<Entry>()

    /** Registra os endereços A de [host] como vistos agora. Ignora endereços vazios/duplicados. */
    @Synchronized
    fun record(host: String, addresses: List<String>, source: String) {
        val now = clock()
        val normalizedHost = host.trimEnd('.').lowercase()
        for (address in addresses.map { it.trim() }.filter { it.isNotEmpty() }) {
            entries.removeAll { it.host == normalizedHost && it.address == address }
            entries.addLast(Entry(normalizedHost, address, now, source))
        }
        val overflow = entries.size - maxEntries
        repeat(overflow.coerceAtLeast(0)) { entries.removeFirst() }
    }

    /** Endereço registrado mais recente para [address] (ou null quando nunca visto / expirado). */
    @Synchronized
    fun match(address: String): Entry? {
        val now = clock()
        expire(now)
        return entries.lastOrNull { it.address == address }
    }

    /** Entradas ativas (mais recentes por último), no máximo [limit]. */
    @Synchronized
    fun active(limit: Int = 8): List<Entry> {
        val now = clock()
        expire(now)
        return entries.toList().takeLast(limit)
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun expire(now: Long) {
        entries.removeAll { now - it.atMs > ttlMs }
    }
}
