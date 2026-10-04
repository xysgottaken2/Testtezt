package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3: o diagnóstico precisa classificar cada pacote do TUN e cada falha de bind em códigos
 * estáveis — é isso que permite comparar dois testes do device (um com 5 conexões, outro com 0)
 * sem adivinhar. Kotlin puro, roda em JVM.
 */
class TunDiagnosticsTest {

    private fun tcpSegment(srcPort: Int, dstPort: Int, flags: Int): ByteArray {
        val segment = ByteArray(20)
        TunnelPackets.writeU16(segment, 0, srcPort)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU32(segment, 4, 1000)
        TunnelPackets.writeU32(segment, 8, 0)
        segment[12] = (5 shl 4).toByte()
        segment[13] = flags.toByte()
        TunnelPackets.writeU16(segment, 14, 65535)
        return segment
    }

    private fun tcpPacket(
        src: String = CdnRouterConfig.VPN_ADDRESS,
        dst: String = CdnRouterConfig.VPN_ADDRESS,
        srcPort: Int = 41234,
        dstPort: Int = 443,
        flags: Int = TunnelPackets.FLAG_SYN
    ): ByteArray {
        val segment = tcpSegment(srcPort, dstPort, flags)
        val source = TunnelPackets.ipv4Bytes(src)!!
        val destination = TunnelPackets.ipv4Bytes(dst)!!
        TunnelPackets.writeU16(segment, 16, TunnelPackets.transportChecksum(source, destination, 6, segment))
        return TunnelPackets.wrapIpv4(source, destination, TunnelPackets.PROTO_TCP, segment, 64)
    }

    @Test
    fun tcpSynToTunnelAddressIsBounceableNotDiscarded() {
        val packet = tcpPacket()
        val view = TunDiagnostics.view(packet, packet.size)!!
        assertTrue(view.isRedirectDest)
        assertTrue(view.isLocalDest)
        assertTrue(view.isSyn)
        assertFalse(view.isSynAck)
        assertEquals("TCP", view.protocolName)
        assertEquals(443, view.dstPort)
        assertTrue("SYN para o endereço do túnel precisa seguir para o bounce", TunDiagnostics.discardReason(view) == null)
        assertTrue(view.brief().contains("flags=SYN"))
    }

    @Test
    fun tcpToForeignAddressIsDiscardedWithReason() {
        val packet = tcpPacket(dst = "23.46.216.80")
        val view = TunDiagnostics.view(packet, packet.size)!!
        assertFalse(view.isLocalDest)
        assertEquals(TunDiscardReason.TCP_SEM_ATENDIMENTO, TunDiagnostics.discardReason(view))
        assertTrue(TunDiscardReason.TCP_SEM_ATENDIMENTO.line(view).contains("motivo=TCP_SEM_ATENDIMENTO"))
    }

    @Test
    fun udpToDnsPortIsHandledAndOtherUdpIsDiscarded() {
        val dns = TunnelPackets.buildUdpPacket("10.111.222.2", 41234, CdnRouterConfig.VPN_DNS, 53, byteArrayOf(1, 2, 3))
        val dnsView = TunDiagnostics.view(dns, dns.size)!!
        assertTrue(dnsView.isDnsPort)
        assertNull("DNS segue o fluxo normal", TunDiagnostics.discardReason(dnsView))

        val other = TunnelPackets.buildUdpPacket("10.111.222.2", 41234, "23.46.216.80", 9999, byteArrayOf(1, 2, 3))
        val otherView = TunDiagnostics.view(other, other.size)!!
        assertEquals(TunDiscardReason.UDP_PORTA_NAO_DNS, TunDiagnostics.discardReason(otherView))
    }

    @Test
    fun icmpIsReportedAsUnsupportedProtocol() {
        val segment = ByteArray(8)
        val source = TunnelPackets.ipv4Bytes("10.111.222.1")!!
        val destination = TunnelPackets.ipv4Bytes("10.111.222.1")!!
        val packet = TunnelPackets.wrapIpv4(source, destination, TunnelPackets.PROTO_ICMP, segment, 64)
        val view = TunDiagnostics.view(packet, packet.size)!!
        assertEquals("ICMP", view.protocolName)
        assertEquals(TunDiscardReason.PROTO_NAO_SUPORTADO, TunDiagnostics.discardReason(view))
    }

    @Test
    fun invalidPacketHasNoView() {
        assertNull(TunDiagnostics.view(ByteArray(4), 4))
        assertTrue(TunDiscardReason.PACOTE_INVALIDO.line(null).contains("motivo=PACOTE_INVALIDO"))
    }

    @Test
    fun bindFailuresAreClassifiedByErrno() {
        assertEquals(
            ListenerFailure.ENDERECO_INDISPONIVEL,
            ListenerFailures.classify("BindException", "Cannot assign requested address")
        )
        assertEquals(
            ListenerFailure.ENDERECO_INDISPONIVEL,
            ListenerFailures.classify("BindException", "bind failed: EADDRNOTAVAIL")
        )
        assertEquals(
            ListenerFailure.PORTA_EM_USO,
            ListenerFailures.classify("BindException", "Address already in use")
        )
        assertEquals(
            ListenerFailure.PORTA_NEGADA,
            ListenerFailures.classify("BindException", "Permission denied")
        )
        assertEquals(
            ListenerFailure.DESCONHECIDO,
            ListenerFailures.classify("IOException", "algo inesperado")
        )
        assertEquals(ListenerFailures.VIA_LOOPBACK, ListenerFailures.via(CdnRouterConfig.LOOPBACK_ADDRESS))
        assertEquals(ListenerFailures.VIA_TUNEL, ListenerFailures.via(CdnRouterConfig.VPN_ADDRESS))
    }

    @Test
    fun summaryLineCarriesEveryTunnelCounter() {
        val counters = RequestCounters(
            dnsQueries = 3,
            dnsIntercepted = 1,
            dnsForwarded = 2,
            tcpConnections = 5,
            tlsFailed = 5,
            tunPackets = 12,
            tunToRedirect = 5,
            tunBounces = 5,
            tunDiscards = 7
        )
        val line = TunDiagnostics.summaryLine(counters)
        assertTrue(line.contains("pacotes=12"))
        assertTrue(line.contains("para-10.111.222.1=5"))
        assertTrue(line.contains("devolvidos-bounce=5"))
        assertTrue(line.contains("descartados=7"))
        assertTrue(line.contains("dns-total=3"))
        assertTrue(line.contains("dns-cdni-interceptado=1"))
        assertTrue(line.contains("dns-encaminhado=2"))
        assertTrue(line.contains("tcp-conexoes=5"))
    }
}
