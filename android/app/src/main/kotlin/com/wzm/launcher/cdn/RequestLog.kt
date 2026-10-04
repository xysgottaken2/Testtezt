package com.wzm.launcher.cdn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contadores exibidos no card do roteador e na tela de logs.
 *
 * Os contadores do TUN (M3.4) são separados por versão/protocolo/porta para que o log responda
 * "o que exatamente chegou no túnel" — não existe mais um número único e genérico.
 */
data class RequestCounters(
    val dnsQueries: Int = 0,
    val dnsIntercepted: Int = 0,
    val tcpConnections: Int = 0,
    val httpRequests: Int = 0,
    val unknownRequests: Int = 0,
    val tlsOk: Int = 0,
    val tlsFailed: Int = 0,
    // ---- TUN: volume e classificação por versão/protocolo (M3.4) ----
    val tunPacketsTotal: Int = 0,
    val tunIpv4Packets: Int = 0,
    val tunIpv6Packets: Int = 0,
    val tunTcpPackets: Int = 0,
    val tunUdpPackets: Int = 0,
    val tunIcmpPackets: Int = 0,
    val tunInvalidPackets: Int = 0,
    val tunIpv4ToCdnTarget: Int = 0,
    val tunIpv6ToCdnTarget: Int = 0,
    val tunToRedirect: Int = 0,
    val tunBounces: Int = 0,
    val tunDiscards: Int = 0,
    // ---- TUN: observações de caminho (TCP/DNS/DoT/QUIC) ----
    val tunTcpSyn: Int = 0,
    val tunTcpSynToRedirect: Int = 0,
    val tunTcpSynOther: Int = 0,
    val tunUdpDns53: Int = 0,
    val tunUdpDnsNoVirtualDns: Int = 0,
    val tunDotFlows: Int = 0,
    val tunDohCandidates: Int = 0,
    val tunTcp443Externo: Int = 0,
    val tunUidVerifiedFlows: Int = 0,
    /** Consultas DNS que NÃO eram do CDNI e foram encaminhadas ao DNS real. */
    val dnsForwarded: Int = 0
) {
    /** Linha única com todos os contadores (UI e cabeçalho de exportação). */
    fun summary(): String =
        "DNS: $dnsQueries consultas / $dnsIntercepted interceptadas / $dnsForwarded encaminhadas • " +
            "TCP: $tcpConnections conexões • HTTP: $httpRequests requests / $unknownRequests desconhecidos • " +
            "TLS: $tlsOk ok / $tlsFailed falhas • " +
            "TUN: $tunPacketsTotal pacotes (IPv4 $tunIpv4Packets / IPv6 $tunIpv6Packets / inválidos $tunInvalidPackets; " +
            "TCP $tunTcpPackets / UDP $tunUdpPackets / ICMP $tunIcmpPackets) • " +
            "alvo-CDNI: $tunToRedirect bounce $tunBounces / $tunDiscards descartes"

    /** Versão curta para os cards. */
    fun compact(): String =
        "DNS $dnsQueries/$dnsIntercepted/$dnsForwarded • TCP $tcpConnections • HTTP $httpRequests/$unknownRequests • " +
            "TLS $tlsOk/$tlsFailed • TUN $tunPacketsTotal(v4 $tunIpv4Packets/v6 $tunIpv6Packets/inv $tunInvalidPackets) " +
            "bounce $tunBounces desc $tunDiscards"

    /** Linha `chave=valor` com os nomes exatos usados no log e no relatório (export .txt). */
    fun exportLine(): String =
        "dnsQueries=$dnsQueries dnsIntercepted=$dnsIntercepted dnsForwarded=$dnsForwarded " +
            "tcpConnections=$tcpConnections httpRequests=$httpRequests unknownRequests=$unknownRequests " +
            "tlsOk=$tlsOk tlsFailed=$tlsFailed " +
            "tunPacketsTotal=$tunPacketsTotal tunIpv4Packets=$tunIpv4Packets tunIpv6Packets=$tunIpv6Packets " +
            "tunTcpPackets=$tunTcpPackets tunUdpPackets=$tunUdpPackets tunIcmpPackets=$tunIcmpPackets " +
            "tunInvalidPackets=$tunInvalidPackets " +
            "tunIpv4ToCdnTarget=$tunIpv4ToCdnTarget tunIpv6ToCdnTarget=$tunIpv6ToCdnTarget " +
            "tunToRedirect=$tunToRedirect tunBounces=$tunBounces tunDiscards=$tunDiscards " +
            "tunTcpSyn=$tunTcpSyn tunTcpSynToRedirect=$tunTcpSynToRedirect tunTcpSynOther=$tunTcpSynOther " +
            "tunUdpDns53=$tunUdpDns53 tunUdpDnsNoVirtualDns=$tunUdpDnsNoVirtualDns " +
            "tunDotFlows=$tunDotFlows tunDohCandidates=$tunDohCandidates tunTcp443Externo=$tunTcp443Externo " +
            "tunUidVerifiedFlows=$tunUidVerifiedFlows"
}

/**
 * Destino opcional de persistência: cada linha adicionada é entregue ao sink.
 * Ver [FileLogSink] (JVM-testável) e [LogPersistence] (ligação com o app).
 */
interface LogSink {
    fun append(line: String)
}

/**
 * Buffer único de log do launcher: eventos do próprio app + DNS/TLS/HTTP interceptados do WZM.
 *
 * Este é o `RequestLog` mostrado na tela "VER LOGS". Requisitos de privacidade (M3/M3.4):
 * registramos apenas tag, horário, método, host/path, status e **metadados de cabeçalho IP**
 * (versão, protocolo, endereço, porta, flags). Nunca corpos de requisição, cabeçalhos HTTP,
 * cookies, tokens, credenciais ou payload — nem mesmo dos pacotes do TUN.
 */
object RequestLog {

    /** Buffer em memória (linhas visíveis na UI). */
    const val MAX_ENTRIES = 400

    /** Limite por linha: evita uma linha gigante travar a UI/exportação. */
    const val MAX_LINE_CHARS = 800

    const val TAG_LAUNCHER = "LAUNCHER"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val _counters = MutableStateFlow(RequestCounters())
    val counters: StateFlow<RequestCounters> = _counters.asStateFlow()

    private val dnsQueries = AtomicInteger(0)
    private val dnsIntercepted = AtomicInteger(0)
    private val dnsForwarded = AtomicInteger(0)
    private val tcpConnections = AtomicInteger(0)
    private val httpRequests = AtomicInteger(0)
    private val unknownRequests = AtomicInteger(0)
    private val tlsOk = AtomicInteger(0)
    private val tlsFailed = AtomicInteger(0)
    private val tunPacketsTotal = AtomicInteger(0)
    private val tunIpv4Packets = AtomicInteger(0)
    private val tunIpv6Packets = AtomicInteger(0)
    private val tunTcpPackets = AtomicInteger(0)
    private val tunUdpPackets = AtomicInteger(0)
    private val tunIcmpPackets = AtomicInteger(0)
    private val tunInvalidPackets = AtomicInteger(0)
    private val tunIpv4ToCdnTarget = AtomicInteger(0)
    private val tunIpv6ToCdnTarget = AtomicInteger(0)
    private val tunToRedirect = AtomicInteger(0)
    private val tunBounces = AtomicInteger(0)
    private val tunDiscards = AtomicInteger(0)
    private val tunTcpSyn = AtomicInteger(0)
    private val tunTcpSynToRedirect = AtomicInteger(0)
    private val tunTcpSynOther = AtomicInteger(0)
    private val tunUdpDns53 = AtomicInteger(0)
    private val tunUdpDnsNoVirtualDns = AtomicInteger(0)
    private val tunDotFlows = AtomicInteger(0)
    private val tunDohCandidates = AtomicInteger(0)
    private val tunTcp443Externo = AtomicInteger(0)
    private val tunUidVerifiedFlows = AtomicInteger(0)

    @Volatile
    private var sink: LogSink? = null

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "[${timeFormat.format(Date())}] [$tag] ${sanitize(message)}"
        buffer.addLast(line)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
        sink?.let { current -> runCatching { current.append(line) } }
    }

    /** Uma linha por evento (quebras viram espaço) e com tamanho limitado. */
    private fun sanitize(message: String): String {
        val singleLine = message.replace('\n', ' ').replace('\r', ' ')
        return if (singleLine.length > MAX_LINE_CHARS) singleLine.take(MAX_LINE_CHARS) + "…" else singleLine
    }

    fun incDnsQuery() { dnsQueries.incrementAndGet(); publishCounters() }
    fun incDnsIntercepted() { dnsIntercepted.incrementAndGet(); publishCounters() }
    fun incDnsForwarded() { dnsForwarded.incrementAndGet(); publishCounters() }
    fun incTcpConnection() { tcpConnections.incrementAndGet(); publishCounters() }
    fun incHttpRequest() { httpRequests.incrementAndGet(); publishCounters() }
    fun incUnknownRequest() { unknownRequests.incrementAndGet(); publishCounters() }
    fun incTlsOk() { tlsOk.incrementAndGet(); publishCounters() }
    fun incTlsFailed() { tlsFailed.incrementAndGet(); publishCounters() }

    fun incTunPacketsTotal() { tunPacketsTotal.incrementAndGet(); publishCounters() }
    fun incTunIpv4Packet() { tunIpv4Packets.incrementAndGet(); publishCounters() }
    fun incTunIpv6Packet() { tunIpv6Packets.incrementAndGet(); publishCounters() }
    fun incTunTcpPacket() { tunTcpPackets.incrementAndGet(); publishCounters() }
    fun incTunUdpPacket() { tunUdpPackets.incrementAndGet(); publishCounters() }
    fun incTunIcmpPacket() { tunIcmpPackets.incrementAndGet(); publishCounters() }
    fun incTunInvalidPacket() { tunInvalidPackets.incrementAndGet(); publishCounters() }
    fun incTunIpv4ToCdnTarget() { tunIpv4ToCdnTarget.incrementAndGet(); publishCounters() }
    fun incTunIpv6ToCdnTarget() { tunIpv6ToCdnTarget.incrementAndGet(); publishCounters() }
    fun incTunToRedirect() { tunToRedirect.incrementAndGet(); publishCounters() }
    fun incTunBounce() { tunBounces.incrementAndGet(); publishCounters() }
    fun incTunDiscard() { tunDiscards.incrementAndGet(); publishCounters() }
    fun incTunUidVerifiedFlow() { tunUidVerifiedFlows.incrementAndGet(); publishCounters() }

    /** Registra uma observação de caminho ([TunObservation]) no contador correspondente. */
    fun incTunObservation(observation: TunObservation) {
        when (observation) {
            TunObservation.TCP_SYN -> tunTcpSyn.incrementAndGet()
            TunObservation.TCP_SYN_PARA_ALVO_443 -> tunTcpSynToRedirect.incrementAndGet()
            TunObservation.TCP_SYN_OUTRO_DESTINO -> tunTcpSynOther.incrementAndGet()
            TunObservation.UDP_DNS_53 -> tunUdpDns53.incrementAndGet()
            TunObservation.UDP_DNS_NO_DNS_VIRTUAL -> tunUdpDnsNoVirtualDns.incrementAndGet()
            TunObservation.FLUXO_DOT -> tunDotFlows.incrementAndGet()
            TunObservation.TCP_443_EXTERNO -> tunTcp443Externo.incrementAndGet()
            TunObservation.UDP_443_QUIC_DOH -> tunDohCandidates.incrementAndGet()
        }
        publishCounters()
    }

    private fun publishCounters() {
        _counters.value = RequestCounters(
            dnsQueries = dnsQueries.get(),
            dnsIntercepted = dnsIntercepted.get(),
            dnsForwarded = dnsForwarded.get(),
            tcpConnections = tcpConnections.get(),
            httpRequests = httpRequests.get(),
            unknownRequests = unknownRequests.get(),
            tlsOk = tlsOk.get(),
            tlsFailed = tlsFailed.get(),
            tunPacketsTotal = tunPacketsTotal.get(),
            tunIpv4Packets = tunIpv4Packets.get(),
            tunIpv6Packets = tunIpv6Packets.get(),
            tunTcpPackets = tunTcpPackets.get(),
            tunUdpPackets = tunUdpPackets.get(),
            tunIcmpPackets = tunIcmpPackets.get(),
            tunInvalidPackets = tunInvalidPackets.get(),
            tunIpv4ToCdnTarget = tunIpv4ToCdnTarget.get(),
            tunIpv6ToCdnTarget = tunIpv6ToCdnTarget.get(),
            tunToRedirect = tunToRedirect.get(),
            tunBounces = tunBounces.get(),
            tunDiscards = tunDiscards.get(),
            tunTcpSyn = tunTcpSyn.get(),
            tunTcpSynToRedirect = tunTcpSynToRedirect.get(),
            tunTcpSynOther = tunTcpSynOther.get(),
            tunUdpDns53 = tunUdpDns53.get(),
            tunUdpDnsNoVirtualDns = tunUdpDnsNoVirtualDns.get(),
            tunDotFlows = tunDotFlows.get(),
            tunDohCandidates = tunDohCandidates.get(),
            tunTcp443Externo = tunTcp443Externo.get(),
            tunUidVerifiedFlows = tunUidVerifiedFlows.get()
        )
    }

    fun resetCounters() {
        dnsQueries.set(0); dnsIntercepted.set(0); dnsForwarded.set(0)
        tcpConnections.set(0); httpRequests.set(0); unknownRequests.set(0)
        tlsOk.set(0); tlsFailed.set(0)
        tunPacketsTotal.set(0); tunIpv4Packets.set(0); tunIpv6Packets.set(0)
        tunTcpPackets.set(0); tunUdpPackets.set(0); tunIcmpPackets.set(0); tunInvalidPackets.set(0)
        tunIpv4ToCdnTarget.set(0); tunIpv6ToCdnTarget.set(0)
        tunToRedirect.set(0); tunBounces.set(0); tunDiscards.set(0)
        tunTcpSyn.set(0); tunTcpSynToRedirect.set(0); tunTcpSynOther.set(0)
        tunUdpDns53.set(0); tunUdpDnsNoVirtualDns.set(0); tunDotFlows.set(0)
        tunDohCandidates.set(0); tunTcp443Externo.set(0); tunUidVerifiedFlows.set(0)
        publishCounters()
    }

    /** Limpa buffer **e** contadores (o arquivo persistido é limpo por [LogPersistence.clearFile]). */
    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        resetCounters()
    }

    /** Linhas do buffer, mais recentes por último. */
    @Synchronized
    fun lines(): List<String> = buffer.toList()

    /** Consulta por tag exata (ex.: "DNS", "CDNI", "TUN", "DIAG", "TLS", "HTTP", "VPN", "LAUNCHER"). */
    fun tagOf(line: String): String? {
        val separator = line.indexOf("] [")
        if (separator < 0) return null
        val end = line.indexOf(']', separator + 3)
        if (end < 0) return null
        return line.substring(separator + 3, end)
    }

    /** Filtra as linhas por tag; tag null devolve tudo (usado pela tela de logs e pelos testes). */
    fun filterTags(lines: List<String>, tag: String?): List<String> =
        if (tag == null) lines else lines.filter { tagOf(it) == tag }

    /** Todas as tags presentes nas linhas (para os chips da UI). */
    fun tagsIn(lines: List<String>): List<String> = lines.mapNotNull { tagOf(it) }.distinct().sorted()

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")

    /** Texto completo para COPIAR/EXPORTAR (cabeçalho com contadores + todas as linhas). */
    @Synchronized
    fun exportText(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(now)
        val current = counters.value
        return buildString {
            append("# WZM Offline Launcher — RequestLog do roteador CDNI local\n")
            append("# exportado em: $stamp\n")
            append("# contadores: ${current.exportLine()}\n")
            append("# resumo: ${TunDiagnostics.summaryLine(current)}\n")
            append("# linhas: ${buffer.size} (buffer máximo: $MAX_ENTRIES)\n")
            append(
                "# privacidade: registramos apenas metadados (tag, horário, destino, porta, protocolo, status).\n" +
                    "#              NÃO são registrados corpos de requisição, cabeçalhos HTTP, cookies, tokens nem payloads.\n"
            )
            append("\n")
            buffer.forEach { append(it).append("\n") }
        }
    }

    /**
     * Repõe o buffer com linhas de uma sessão anterior (arquivo persistido).
     * Contadores **não** são restaurados (não há como reconstruí-los com fidelidade).
     */
    @Synchronized
    fun restore(lines: List<String>) {
        buffer.clear()
        lines.takeLast(MAX_ENTRIES).forEach { buffer.addLast(it) }
        _entries.value = buffer.toList()
    }

    /** Liga/desliga o destino de persistência. `null` desliga (usado nos testes). */
    fun attachSink(newSink: LogSink?) {
        sink = newSink
    }

    fun currentSink(): LogSink? = sink
}
