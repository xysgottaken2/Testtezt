package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Testes de armazenamento e consulta do [RequestLog] — o mesmo buffer mostrado na tela
 * "VER LOGS" e exportado pelos botões COPIAR/SALVAR.
 *
 * Nota de isolamento: `RequestLog` é um singleton compartilhado por toda a suíte e outros testes
 * (ex.: servidor HTTPS) deixam threads daemon que podem registrar uma linha atrasada. Por isso cada
 * teste (a) limpa o buffer em `@Before`/`@After` e (b) verifica apenas as linhas com um marcador
 * único, em vez de assumir que o buffer contém *somente* o que este teste escreveu.
 */
class RequestLogTest {

    private val marker = "caso-requestlog"

    private class RecordingSink : LogSink {
        val lines = mutableListOf<String>()
        override fun append(line: String) { lines.add(line) }
    }

    @Before
    fun setUp() {
        RequestLog.attachSink(null)
        RequestLog.clear()
    }

    @After
    fun tearDown() {
        RequestLog.attachSink(null)
        RequestLog.clear()
    }

    private fun mine(): List<String> = RequestLog.lines().filter { it.contains(marker) }

    @Test
    fun storesLinesInOrderWithTagAndTimestamp() {
        RequestLog.add("DNS", "$marker consulta de DNS")
        RequestLog.add("CDNI", "$marker GET /manifest/build-selector-103.js -> 200 (VERIFICADO, 63 B)")
        RequestLog.add("TLS", "$marker handshake OK")

        val lines = mine()
        assertEquals(3, lines.size)
        assertEquals(listOf("DNS", "CDNI", "TLS"), lines.map { RequestLog.tagOf(it) })
        // timestamp no formato [HH:mm:ss.SSS]
        assertTrue(
            "linha sem timestamp: ${lines[0]}",
            Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[").containsMatchIn(lines[0])
        )
        assertTrue(lines[1].contains("/manifest/build-selector-103.js"))
        assertTrue(lines[1].contains("200"))
    }

    @Test
    fun queriesByTagIncludingUnknown() {
        RequestLog.add("DNS", "$marker consulta")
        RequestLog.add("CDNI", "$marker conexão TCP")
        RequestLog.add("CDNI?", "$marker DESCONHECIDO: GET /path/novo -> 404 controlado")
        RequestLog.add("HTTP", "$marker GET /path/novo -> 404 Not Found")

        val lines = mine()
        assertEquals(4, lines.size)
        assertEquals("sem filtro devolve tudo", 4, RequestLog.filterTags(lines, null).size)
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

    /**
     * M3.4: cada contador do TUN tem nome próprio (versão, protocolo, porta, caminho) e aparece
     * no card, no resumo e no `.txt` exportado com o **nome exato** usado no relatório.
     */
    @Test
    fun countersTrackTunnelActivityByVersionProtocolAndPath() {
        RequestLog.clear()
        RequestLog.incTunPacketsTotal()
        RequestLog.incTunIpv4Packet()
        RequestLog.incTunTcpPacket()
        RequestLog.incTunObservation(TunObservation.TCP_SYN)
        RequestLog.incTunObservation(TunObservation.TCP_SYN_PARA_ALVO_443)
        RequestLog.incTunIpv4ToCdnTarget()
        RequestLog.incTunToRedirect()
        RequestLog.incTunBounce()
        RequestLog.incTunUidVerifiedFlow()
        RequestLog.incTunPacketsTotal()
        RequestLog.incTunIpv6Packet()
        RequestLog.incTunUdpPacket()
        RequestLog.incTunObservation(TunObservation.UDP_DNS_53)
        RequestLog.incTunObservation(TunObservation.UDP_DNS_NO_DNS_VIRTUAL)
        RequestLog.incTunObservation(TunObservation.UDP_443_QUIC_DOH)
        RequestLog.incTunDiscard()
        RequestLog.incTunInvalidPacket()
        RequestLog.incDnsForwarded()

        val counters = RequestLog.counters.value
        assertEquals(2, counters.tunPacketsTotal)
        assertEquals(1, counters.tunIpv4Packets)
        assertEquals(1, counters.tunIpv6Packets)
        assertEquals(1, counters.tunTcpPackets)
        assertEquals(1, counters.tunUdpPackets)
        assertEquals(0, counters.tunIcmpPackets)
        assertEquals(1, counters.tunInvalidPackets)
        assertEquals(1, counters.tunIpv4ToCdnTarget)
        assertEquals(0, counters.tunIpv6ToCdnTarget)
        assertEquals(1, counters.tunToRedirect)
        assertEquals(1, counters.tunBounces)
        assertEquals(1, counters.tunDiscards)
        assertEquals(1, counters.tunTcpSyn)
        assertEquals(1, counters.tunTcpSynToRedirect)
        assertEquals(0, counters.tunTcpSynOther)
        assertEquals(1, counters.tunUdpDns53)
        assertEquals(1, counters.tunUdpDnsNoVirtualDns)
        assertEquals(1, counters.tunDohCandidates)
        assertEquals(1, counters.tunUidVerifiedFlows)
        assertEquals(1, counters.dnsForwarded)

        val summary = counters.summary()
        assertTrue(summary.contains("TUN: 2 pacotes"))
        assertTrue(summary.contains("IPv4 1"))
        assertTrue(summary.contains("IPv6 1"))
        assertTrue(summary.contains("inválidos 1"))
        assertTrue(summary.contains("alvo-CDNI: 1 bounce 1 / 1 descartes"))
        assertTrue(counters.compact().contains("TUN 2(v4 1/v6 1/inv 1)"))

        val export = RequestLog.exportText()
        for (name in listOf(
            "tunPacketsTotal=2", "tunIpv4Packets=1", "tunIpv6Packets=1", "tunTcpPackets=1",
            "tunUdpPackets=1", "tunIcmpPackets=0", "tunInvalidPackets=1", "tunIpv4ToCdnTarget=1",
            "tunIpv6ToCdnTarget=0", "tunToRedirect=1", "tunBounces=1", "tunDiscards=1",
            "tunTcpSyn=1", "tunTcpSynToRedirect=1", "tunTcpSynOther=0", "tunUdpDns53=1",
            "tunUdpDnsNoVirtualDns=1", "tunDotFlows=0", "tunDohCandidates=1",
            "tunTcp443Externo=0", "tunUidVerifiedFlows=1"
        )) {
            assertTrue("export deve conter $name", export.contains(name))
        }
        assertTrue("export não pode conter payload", export.contains("nada de payload"))
        assertTrue(export.contains("não são registrados corpos de requisição"))
    }

    @Test
    fun clearResetsLinesAndCounters() {
        RequestLog.add("DNS", "$marker antes")
        RequestLog.incDnsQuery()
        RequestLog.clear()
        assertTrue(RequestLog.lines().isEmpty())
        assertEquals(RequestCounters(), RequestLog.counters.value)
    }

    @Test
    fun bufferKeepsOnlyLastEntries() {
        repeat(RequestLog.MAX_ENTRIES + 20) { index -> RequestLog.add("DNS", "$marker linha $index") }
        val lines = mine()
        val firstIndex = Regex("linha (\\d+)$").find(lines.first())?.groupValues?.get(1)?.toInt()

        assertTrue("esperado ~${RequestLog.MAX_ENTRIES} linhas, veio ${lines.size}", lines.size >= RequestLog.MAX_ENTRIES - 2)
        assertTrue("linha antiga (índice $firstIndex) não deveria estar no buffer", (firstIndex ?: 0) >= 20)
        assertTrue("última linha perdida: ${lines.last()}", lines.last().endsWith("linha ${RequestLog.MAX_ENTRIES + 19}"))
        assertFalse(lines.any { it.endsWith("linha 0") })
    }

    @Test
    fun longOrMultilineMessagesAreSanitized() {
        RequestLog.add("CDNI", "Z".repeat(RequestLog.MAX_LINE_CHARS + 100))
        RequestLog.add("CDNI", "duas\nlinhas\r\naqui")

        val truncated = RequestLog.lines().first { it.contains("ZZZ") }
        val multiline = RequestLog.lines().first { it.contains("duas") }

        // Regex explícita (o comportamento de split(String) varia conforme a sobrecarga da stdlib)
        assertEquals("a tag [CDNI] deve aparecer uma vez na linha", 1, Regex("\\[CDNI]").findAll(truncated).count())
        assertTrue("linha não truncada (fim=${truncated.takeLast(10)})", truncated.endsWith("…"))
        assertTrue("linha longa demais: ${truncated.length}", truncated.length <= RequestLog.MAX_LINE_CHARS + 40)
        assertFalse("quebra de linha não sanitizada: $multiline", multiline.contains("\n"))
        assertEquals("CDNI", RequestLog.tagOf(multiline))
        assertTrue("conteúdo perdido na sanitização: $multiline", multiline.contains("duas linhas  aqui"))
    }

    @Test
    fun exportTextHasCountersPrivacyNoteAndEveryLine() {
        RequestLog.add("CDNI", "$marker GET /manifest/manifest.json -> 200 (VERIFICADO, 150 B)")
        RequestLog.incTcpConnection()
        RequestLog.incHttpRequest()

        val export = RequestLog.exportText(Date(0))
        assertTrue(export.startsWith("# WZM Offline Launcher — RequestLog do roteador CDNI local"))
        assertTrue(export.contains("# exportado em: "))
        assertTrue(export.contains("# contadores: dnsQueries="))
        assertTrue(export.contains("tcpConnections=1"))
        assertTrue(export.contains("# linhas: "))
        assertTrue(export.contains("não são registrados corpos de requisição"))
        assertTrue("a linha do teste precisa estar na exportação", export.contains("$marker GET /manifest/manifest.json -> 200"))
    }

    @Test
    fun sinkReceivesNewLinesButNotRestoredOnes() {
        val sink = RecordingSink()
        RequestLog.restore(listOf("[10:00:00.000] [LAUNCHER] log restaurado da sessão anterior (1 linhas; contadores zerados)"))
        RequestLog.attachSink(sink)

        RequestLog.add("DNS", "$marker nova consulta")
        assertEquals(1, sink.lines.count { it.contains(marker) })
        assertEquals(
            "buffer deve conter a linha restaurada + a nova",
            2,
            RequestLog.lines().count { it.contains(marker) || it.contains("log restaurado") }
        )
        assertTrue("linhas restauradas não podem ser regravadas", sink.lines.none { it.contains("log restaurado") })

        // restaurar de novo (simulando reinício) não pode duplicar linhas no arquivo
        RequestLog.restore(listOf("[09:00:00.000] [LAUNCHER] antigo"))
        assertEquals(1, sink.lines.count { it.contains(marker) })
    }

    @Test
    fun restoreKeepsOnlyTheTail() {
        val restored = (1..(RequestLog.MAX_ENTRIES + 50)).map { "[10:00:00.000] [DNS] $marker linha $it" }
        RequestLog.restore(restored)

        val indices = mine().mapNotNull { Regex("linha (\\d+)$").find(it)?.groupValues?.get(1)?.toInt() }
        assertTrue("buffer deveria ter ~${RequestLog.MAX_ENTRIES} linhas, veio ${indices.size}", indices.size >= RequestLog.MAX_ENTRIES - 2)
        assertTrue("linha mais antiga mantida: ${indices.minOrNull()}", (indices.minOrNull() ?: 0) >= 45)
        assertEquals(RequestLog.MAX_ENTRIES + 50, indices.maxOrNull())
        assertFalse("linha 1 não deveria sobreviver", indices.contains(1))
    }
}
