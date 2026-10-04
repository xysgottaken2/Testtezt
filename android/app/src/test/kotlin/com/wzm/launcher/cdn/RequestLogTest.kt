package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Testes de armazenamento e consulta do [RequestLog] — o mesmo buffer mostrado na tela
 * "VER LOGS" e exportado pelos botões COPIAR/SALVAR.
 */
class RequestLogTest {

    private class RecordingSink : LogSink {
        val lines = mutableListOf<String>()
        override fun append(line: String) { lines.add(line) }
    }

    @After
    fun tearDown() {
        RequestLog.attachSink(null)
        RequestLog.clear()
    }

    @Test
    fun storesLinesInOrderWithTagAndTimestamp() {
        RequestLog.add("DNS", "prod.cdni.callofduty.com (tipo 1) -> 10.111.222.1 [interceptado]")
        RequestLog.add("CDNI", "GET /manifest/build-selector-103.js HTTP/1.1 -> 200 (VERIFICADO, 63 B)")
        RequestLog.add("TLS", "handshake OK (SNI=prod.cdni.callofduty.com, TLSv1.3)")

        val lines = RequestLog.lines()
        assertEquals(3, lines.size)
        assertEquals(listOf("DNS", "CDNI", "TLS"), lines.map { RequestLog.tagOf(it) })
        // timestamp no formato [HH:mm:ss.SSS]
        assertTrue(Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[").containsMatchIn(lines[0]))
        assertTrue(lines[1].contains("/manifest/build-selector-103.js"))
        assertTrue(lines[1].contains("200"))
    }

    @Test
    fun queriesByTagIncludingUnknown() {
        RequestLog.add("DNS", "consulta")
        RequestLog.add("CDNI", "conexão TCP")
        RequestLog.add("CDNI?", "DESCONHECIDO: GET /path/novo -> 404 controlado")
        RequestLog.add("HTTP", "GET /path/novo -> 404 Not Found")

        val lines = RequestLog.lines()
        assertEquals(2, RequestLog.filterTags(lines, null).size)
        assertEquals(1, RequestLog.filterTags(lines, "DNS").size)
        assertEquals(1, RequestLog.filterTags(lines, "CDNI").size)
        assertEquals(1, RequestLog.filterTags(lines, "CDNI?").size)
        assertTrue(RequestLog.filterTags(lines, "CDNI?").first().contains("/path/novo"))
        assertTrue(RequestLog.filterTags(lines, "HTTP").first().contains("404 Not Found"))
        assertTrue(RequestLog.filterTags(lines, "TLS").isEmpty())
        assertEquals(listOf("CDNI", "CDNI?", "DNS", "HTTP"), RequestLog.tagsIn(lines))
    }

    @Test
    fun tagOfHandlesMessagesWithoutTag() {
        assertNull(RequestLog.tagOf("linha sem tag"))
        assertNull(RequestLog.tagOf("[12:00:00.000] sem tag"))
        assertEquals("DNS", RequestLog.tagOf("[12:00:00.000] [DNS] consulta"))
    }

    @Test
    fun countersTrackDnsTcpHttpAndTls() {
        RequestLog.incDnsQuery(); RequestLog.incDnsQuery()
        RequestLog.incDnsIntercepted()
        RequestLog.incTcpConnection()
        RequestLog.incHttpRequest(); RequestLog.incUnknownRequest()
        RequestLog.incTlsOk(); RequestLog.incTlsFailed()

        val counters = RequestLog.counters.value
        assertEquals(2, counters.dnsQueries)
        assertEquals(1, counters.dnsIntercepted)
        assertEquals(1, counters.tcpConnections)
        assertEquals(1, counters.httpRequests)
        assertEquals(1, counters.unknownRequests)
        assertEquals(1, counters.tlsOk)
        assertEquals(1, counters.tlsFailed)
        assertTrue(counters.summary().contains("TCP: 1 conexões"))
        assertTrue(counters.compact().contains("TLS 1/1"))
    }

    @Test
    fun clearResetsLinesAndCounters() {
        RequestLog.add("DNS", "antes")
        RequestLog.incDnsQuery()
        RequestLog.clear()
        assertTrue(RequestLog.lines().isEmpty())
        assertEquals(RequestCounters(), RequestLog.counters.value)
    }

    @Test
    fun bufferKeepsOnlyLastEntries() {
        repeat(RequestLog.MAX_ENTRIES + 20) { index -> RequestLog.add("DNS", "linha $index") }
        val lines = RequestLog.lines()
        assertEquals(RequestLog.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().endsWith("linha 20"))
        assertTrue(lines.last().endsWith("linha ${RequestLog.MAX_ENTRIES + 19}"))
    }

    @Test
    fun longOrMultilineMessagesAreSanitized() {
        RequestLog.add("CDNI", "a".repeat(RequestLog.MAX_LINE_CHARS + 100))
        RequestLog.add("CDNI", "duas\nlinhas\r\naqui")
        val lines = RequestLog.lines()
        assertEquals(1, lines[0].split("[CDNI]").size - 1) // tag uma vez só
        assertTrue(lines[0].endsWith("…"))
        assertTrue(lines[0].length <= RequestLog.MAX_LINE_CHARS + 40)
        assertFalse(lines[1].contains("\n"))
        assertEquals("CDNI", RequestLog.tagOf(lines[1]))
    }

    @Test
    fun exportTextHasCountersPrivacyNoteAndEveryLine() {
        RequestLog.add("CDNI", "GET /manifest/manifest.json -> 200 (VERIFICADO, 150 B)")
        RequestLog.incTcpConnection()
        RequestLog.incHttpRequest()

        val export = RequestLog.exportText(Date(0))
        assertTrue(export.startsWith("# WZM Offline Launcher — RequestLog do roteador CDNI local"))
        assertTrue(export.contains("# exportado em: "))
        assertTrue(export.contains("dnsQueries=0"))
        assertTrue(export.contains("tcpConnections=1"))
        assertTrue(export.contains("httpRequests=1"))
        assertTrue(export.contains("linhas: 1"))
        assertTrue(export.contains("não são registrados corpos de requisição"))
        assertTrue(export.contains("GET /manifest/manifest.json -> 200"))
    }

    @Test
    fun sinkReceivesNewLinesButNotRestoredOnes() {
        val sink = RecordingSink()
        RequestLog.restore(listOf("[10:00:00.000] [LAUNCHER] log restaurado da sessão anterior (1 linhas; contadores zerados)"))
        RequestLog.attachSink(sink)

        RequestLog.add("DNS", "nova consulta")
        assertEquals(1, sink.lines.size)
        assertTrue(sink.lines.single().contains("nova consulta"))
        assertEquals(2, RequestLog.lines().size)

        // restaurar de novo (simulando reinício) não pode duplicar linhas no arquivo
        RequestLog.restore(listOf("[09:00:00.000] [LAUNCHER] antigo"))
        assertEquals(1, sink.lines.size)
    }

    @Test
    fun restoreKeepsOnlyTheTail() {
        val restored = (1..(RequestLog.MAX_ENTRIES + 50)).map { "[10:00:00.000] [DNS] linha $it" }
        RequestLog.restore(restored)
        val lines = RequestLog.lines()
        assertEquals(RequestLog.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().endsWith("linha 51"))
        assertTrue(lines.last().endsWith("linha ${RequestLog.MAX_ENTRIES + 50}"))
    }
}
