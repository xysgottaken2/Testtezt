package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M3.5: prova, em JVM, a metade determinística do teste sintético do caminho CDNI
 * (`DNS → 10.111.222.1 → TCP 443 → listener local`) e o corpo real do `cdni.meta` que o listener serve.
 *
 * A metade de rede (TCP/TLS/GET reais no device) roda pelo botão "TESTE SINTÉTICO" e é registrada no log
 * com a tag `[SINTETICO]`; aqui garantimos que a consulta, a resposta virtual e a rota HTTP estão certas
 * antes de qualquer teste com o WZM.
 */
class SyntheticFlowPathTest {

    @Before
    fun setUp() {
        RequestLog.clear()
        RequestLog.clearWzmMarker()
    }

    private fun head(path: String): HttpRequestHead =
        checkNotNull(
            HttpHeadReader.parse("GET $path HTTP/1.1\r\nHost: ${CdnRouterConfig.EXACT_HOST}\r\n\r\n")
        ) { "head HTTP precisa ser parseável" }.head

    @Test
    fun dnsQueryBytesForCdnihostGetVirtualAnswer() {
        val step = SyntheticFlowTest.dnsVirtualStep()
        assertTrue("passo puro do DNS precisa passar: ${step.detail}", step.ok)
        assertTrue(step.verified)
        assertTrue(step.detail.contains(CdnRouterConfig.REDIRECT_TO))
    }

    @Test
    fun buildQueryRoundTripsThroughParser() {
        val query = DnsMessage.buildQuery(CdnRouterConfig.EXACT_HOST, DnsMessage.TYPE_A, id = 0x1234)
        val question = DnsMessage.parseQuery(query, query.size)
        assertNotNull("consulta montada precisa ser parseável (prévia: ${DnsMessage.hexPreview(query)})", question)
        assertEquals(CdnRouterConfig.EXACT_HOST, question!!.name)
        assertEquals(DnsMessage.TYPE_A, question.qType)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
    }

    /** Regressão do bug que só o CI pegou: header com 13 B desloca a pergunta e o parser devolve null. */
    @Test
    fun buildQueryWritesExactlyTheRfc1035HeaderSize() {
        val host = CdnRouterConfig.EXACT_HOST
        val query = DnsMessage.buildQuery(host, DnsMessage.TYPE_A)
        val labels = host.split('.').sumOf { it.length + 1 }
        assertEquals("header(12) + nome($labels) + tipo/classe(4)", 12 + labels + 4, query.size)
        // QDCOUNT = 1 exatamente nos bytes 4-5 do header e o nome começa no byte 12.
        assertEquals(0, query[4].toInt() and 0xFF)
        assertEquals(1, query[5].toInt() and 0xFF)
        assertEquals(host.substringBefore('.').length, query[12].toInt() and 0xFF)
    }

    @Test
    fun dnsResponderAnswersAWithTunnelAddressAndEmptyNoerrorForOthers() {
        val responder = DnsResponder()

        val aQuery = DnsMessage.buildQuery(CdnRouterConfig.EXACT_HOST, DnsMessage.TYPE_A)
        val aAnswer = checkNotNull(responder.answer(aQuery, aQuery.size)) { "host CDNI precisa ser interceptado" }
        assertEquals(listOf(CdnRouterConfig.REDIRECT_TO), DnsMessage.extractARecords(aAnswer, aAnswer.size))

        // AAAA continua NOERROR sem resposta (força IPv4) — o teste de 2026-10-04 viu AAAA na evidência.
        val aaaaQuery = DnsMessage.buildQuery(CdnRouterConfig.EXACT_HOST, DnsMessage.TYPE_AAAA)
        val aaaaAnswer = checkNotNull(responder.answer(aaaaQuery, aaaaQuery.size))
        assertTrue(DnsMessage.extractARecords(aaaaAnswer, aaaaAnswer.size).isEmpty())

        // Host que não é do CDNI não é respondido localmente (segue para o DNS real).
        val otherQuery = DnsMessage.buildQuery("dns.adguard.com", DnsMessage.TYPE_A)
        assertNull(responder.answer(otherQuery, otherQuery.size))
    }

    @Test
    fun dnsPacketForVirtualResolverIsPolicyAnswered() {
        val query = DnsMessage.buildQuery(CdnRouterConfig.EXACT_HOST, DnsMessage.TYPE_A)
        val packet = TunnelPackets.buildUdpPacket(
            srcAddress = CdnRouterConfig.VPN_ADDRESS,
            srcPort = 41234,
            dstAddress = CdnRouterConfig.VPN_DNS,
            dstPort = CdnRouterConfig.DNS_PORT,
            payload = query
        )
        val parsed = IpPacketParser.parse(packet, packet.size)
        assertTrue("pacote de DNS precisa ser válido", parsed is PacketParse.Ok)
        val header = (parsed as PacketParse.Ok).header
        assertTrue(header.isUdp)
        assertTrue(header.isDnsPort)
        assertEquals(CdnRouterConfig.VPN_DNS, header.dstAddress)
        assertEquals(TunAction.RESPOSTA_DNS, TunPolicy.decide(header).action)
        assertEquals(
            listOf(TunObservation.UDP_DNS_53, TunObservation.UDP_DNS_NO_DNS_VIRTUAL),
            TunPolicy.observations(header)
        )
    }

    @Test
    fun cdnMetaIsServedWithRealBodyAndLocalHeader() {
        val outcome = CdnRouteTable.respond(head(CdnRouterConfig.CDNI_META_PATH))
        assertEquals(200, outcome.status)
        assertEquals(Confidence.VERIFIED, outcome.confidence)
        val text = String(outcome.bytes, Charsets.ISO_8859_1)
        assertTrue("corpo real precisa manter min_buildnum", text.contains("\"min_buildnum\": 19854920"))
        assertTrue("app_store_url precisa estar no corpo real", text.contains("\"app_store_url\""))
        assertTrue("a marcação de servidor local fica no cabeçalho HTTP", text.contains("X-WZM-Offline: VERIFIED"))
        assertFalse("o corpo real não é placeholder", text.contains(PLACEHOLDER_MARKER))
    }

    @Test
    fun realBodiesMatchTheObservedShape() {
        assertTrue(CdniMetaBody.looksLikeRealBody(CdniMetaBody.ANDROID))
        assertTrue(CdniMetaBody.looksLikeRealBody(CdniMetaBody.IOS))
        assertTrue(CdniMetaBody.ANDROID.contains("play.google.com"))
        assertTrue(CdniMetaBody.IOS.contains("future_app_id"))
        assertEquals(CdniMetaBody.ANDROID.toByteArray(Charsets.UTF_8).size, CdniMetaBody.android().size)
        // O valor que importa para a checagem de versão é o build instalado (3.10.0.19854920).
        assertTrue(CdniMetaBody.ANDROID.contains("19854920"))
    }

    @Test
    fun everyEndpointThatIsNotRealStillCarriesThePlaceholderMarker() {
        for (endpoint in BootstrapEndpoints.all()) {
            val body = String(endpoint.body(), Charsets.UTF_8)
            if (endpoint.realUpstreamBody) {
                assertFalse("${endpoint.path} não pode ser placeholder", body.contains(PLACEHOLDER_MARKER))
            } else {
                assertTrue("${endpoint.path} precisa continuar marcado", body.contains(PLACEHOLDER_MARKER))
            }
        }
    }
}
