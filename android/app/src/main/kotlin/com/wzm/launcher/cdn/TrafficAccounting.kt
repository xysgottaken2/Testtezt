package com.wzm.launcher.cdn

import android.net.TrafficStats

/**
 * Contabilidade de tráfego **por UID** (M3.6) — a peça que faltava para responder à pergunta
 * "o WZM está gerando tráfego de rede?" **sem** depender do TUN.
 *
 * Motivo: no registro de 2026-10-04 não foram observados pacotes no TUN após o marcador de lançamento.
 * Essa ausência não determina se o WZM tentou rede nem qual caminho usou. [android.net.TrafficStats]
 * responde por UID (contadores cumulativos do kernel, desde o boot): crescimento medido enquanto o TUN
 * está vazio pode apoiar uma hipótese PROBABLE de tráfego daquele UID fora do TUN, mas inclui helpers e
 * não identifica processo/rota. Sem crescimento ou sem contadores utilizáveis, a causa permanece UNKNOWN.
 *
 * Limites, declarados em vez de escondidos:
 *  * os contadores contam **o UID**, não um processo: processos auxiliares do mesmo pacote entram juntos;
 *  * são cumulativos desde o boot → só a **diferença** interessa;
 *  * a API pode responder `UNSUPPORTED` (-1) ou lançar (`SecurityException`) — nesse caso o resultado
 *    diz `INDISPONIVEL`, nunca zero;
 *  * nada aqui altera roteamento, e nenhum payload/conteúdo é lido.
 */
object TrafficAccounting {

    /** Valor devolvido pela API quando o device não suporta o contador (`TrafficStats.UNSUPPORTED`). */
    const val UNSUPPORTED = -1L

    /** Amostra dos contadores de um UID. `null` = a API não respondeu para aquele contador. */
    data class UidStats(
        val uid: Int,
        val txBytes: Long?,
        val rxBytes: Long?,
        val txPackets: Long?,
        val rxPackets: Long?,
        val note: String
    ) {
        val available: Boolean get() = txBytes != null || rxBytes != null || txPackets != null || rxPackets != null
        val totalBytes: Long? get() = if (txBytes != null && rxBytes != null) txBytes + rxBytes else null
    }

    /** Leitura real via [TrafficStats] (API pública, sem permissão). Nunca lança. */
    fun snapshot(uid: Int): UidStats {
        if (uid == android.os.Process.INVALID_UID) {
            return UidStats(uid, null, null, null, null, "UID inválido — sem contabilidade")
        }
        var failure: String? = null
        fun read(label: String, block: () -> Long): Long? = try {
            block().takeIf { it != UNSUPPORTED }
        } catch (e: Exception) {
            if (failure == null) failure = "$label: ${e.javaClass.simpleName}"
            null
        }
        val txBytes = read("txBytes") { TrafficStats.getUidTxBytes(uid) }
        val rxBytes = read("rxBytes") { TrafficStats.getUidRxBytes(uid) }
        val txPackets = read("txPackets") { TrafficStats.getUidTxPackets(uid) }
        val rxPackets = read("rxPackets") { TrafficStats.getUidRxPackets(uid) }
        val note = when {
            failure != null -> "INDISPONIVEL ($failure)"
            txBytes == null && rxBytes == null && txPackets == null && rxPackets == null ->
                "INDISPONIVEL (TrafficStats devolveu UNSUPPORTED para este UID)"
            else -> "ok"
        }
        return UidStats(uid, txBytes, rxBytes, txPackets, rxPackets, note)
    }

    /** `true` = houve tráfego; `false` = nenhum byte/pacote novo; `null` = não há como afirmar. */
    fun grew(before: UidStats, after: UidStats): Boolean? {
        val b = before.totalBytes
        val a = after.totalBytes
        if (b != null && a != null) return a > b
        val bp = packetTotal(before)
        val ap = packetTotal(after)
        if (bp != null && ap != null) return ap > bp
        return null
    }

    private fun packetTotal(stats: UidStats): Long? =
        if (stats.txPackets != null && stats.rxPackets != null) stats.txPackets + stats.rxPackets else null

    /**
     * Diferença legível entre duas amostras (bytes e pacotes). Quando um contador não está
     * disponível, ele é omitido em vez de sair como zero.
     */
    fun delta(before: UidStats, after: UidStats): String {
        if (!before.available && !after.available) {
            return "sem contabilidade para o uid ${after.uid} (${after.note})"
        }
        val parts = mutableListOf<String>()
        bytesDelta(before.totalBytes, after.totalBytes)?.let { parts += "bytes=$it" }
        packetsDelta(before.txPackets, after.txPackets)?.let { parts += "tx-pacotes=$it" }
        packetsDelta(before.rxPackets, after.rxPackets)?.let { parts += "rx-pacotes=$it" }
        if (parts.isEmpty()) return "sem variação legível para o uid ${after.uid}"
        return parts.joinToString(" ")
    }

    private fun bytesDelta(before: Long?, after: Long?): String? {
        if (before == null || after == null) return null
        val diff = after - before
        val sign = if (diff >= 0) "+" else "-"
        val value = kotlin.math.abs(diff)
        return "$sign$value B (${formatBytes(value)})"
    }

    private fun packetsDelta(before: Long?, after: Long?): String? {
        if (before == null || after == null) return null
        val diff = after - before
        return (if (diff >= 0) "+" else "-") + kotlin.math.abs(diff)
    }

    /** Formatação humana curta (KiB/MiB) — só para o log. */
    fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KiB", value / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
    }

    /** Linha de cabeçalho da contabilidade (usada no início da sessão e a cada ciclo do vigia). */
    fun line(label: String, stats: UidStats, delta: String? = null): String = buildString {
        append("contabilidade do uid ${stats.uid} ($label): ")
        if (!stats.available) {
            append(stats.note)
        } else {
            append("tx=${stats.txBytes ?: "?"} B rx=${stats.rxBytes ?: "?"} B ")
            append("pacotes tx=${stats.txPackets ?: "?"} rx=${stats.rxPackets ?: "?"}")
            if (delta != null) append(" • desde o início: $delta")
        }
    }
}
