package com.wzm.launcher.cdn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Contadores exibidos no card do roteador e na tela de logs. */
data class RequestCounters(
    val dnsQueries: Int = 0,
    val dnsIntercepted: Int = 0,
    val tcpConnections: Int = 0,
    val httpRequests: Int = 0,
    val unknownRequests: Int = 0,
    val tlsOk: Int = 0,
    val tlsFailed: Int = 0
) {
    /** Linha única com todos os contadores (UI e cabeçalho de exportação). */
    fun summary(): String =
        "DNS: $dnsQueries consultas / $dnsIntercepted interceptadas • " +
            "TCP: $tcpConnections conexões • HTTP: $httpRequests requests / $unknownRequests desconhecidos • " +
            "TLS: $tlsOk ok / $tlsFailed falhas"

    /** Versão curta para os cards. */
    fun compact(): String =
        "DNS ${dnsQueries}/${dnsIntercepted} • TCP $tcpConnections • HTTP $httpRequests/$unknownRequests • " +
            "TLS $tlsOk/$tlsFailed"
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
 * Este é o `RequestLog` mostrado na tela "VER LOGS". Requisitos de privacidade (M3):
 * registramos apenas tag, horário, método, host/path e status — **nunca** corpos de requisição
 * nem cabeçalhos (sem cookies/tokens/credenciais). Ver `docs/launcher.md` §5.2.
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
    private val tcpConnections = AtomicInteger(0)
    private val httpRequests = AtomicInteger(0)
    private val unknownRequests = AtomicInteger(0)
    private val tlsOk = AtomicInteger(0)
    private val tlsFailed = AtomicInteger(0)

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
    fun incTcpConnection() { tcpConnections.incrementAndGet(); publishCounters() }
    fun incHttpRequest() { httpRequests.incrementAndGet(); publishCounters() }
    fun incUnknownRequest() { unknownRequests.incrementAndGet(); publishCounters() }
    fun incTlsOk() { tlsOk.incrementAndGet(); publishCounters() }
    fun incTlsFailed() { tlsFailed.incrementAndGet(); publishCounters() }

    private fun publishCounters() {
        _counters.value = RequestCounters(
            dnsQueries = dnsQueries.get(),
            dnsIntercepted = dnsIntercepted.get(),
            tcpConnections = tcpConnections.get(),
            httpRequests = httpRequests.get(),
            unknownRequests = unknownRequests.get(),
            tlsOk = tlsOk.get(),
            tlsFailed = tlsFailed.get()
        )
    }

    fun resetCounters() {
        dnsQueries.set(0); dnsIntercepted.set(0); tcpConnections.set(0); httpRequests.set(0)
        unknownRequests.set(0); tlsOk.set(0); tlsFailed.set(0)
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

    /** Consulta por tag exata (ex.: "DNS", "CDNI", "CDNI?", "TLS", "HTTP", "VPN", "LAUNCHER"). */
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
            append(
                "# contadores: dnsQueries=${current.dnsQueries} dnsIntercepted=${current.dnsIntercepted} " +
                    "tcpConnections=${current.tcpConnections} httpRequests=${current.httpRequests} " +
                    "unknownRequests=${current.unknownRequests} tlsOk=${current.tlsOk} tlsFailed=${current.tlsFailed}\n"
            )
            append("# linhas: ${buffer.size} (buffer máximo: $MAX_ENTRIES)\n")
            append("# privacidade: não são registrados corpos de requisição nem cabeçalhos (sem cookies/tokens)\n")
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
