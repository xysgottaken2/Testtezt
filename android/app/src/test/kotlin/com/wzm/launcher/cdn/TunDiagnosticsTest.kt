package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * M3.4: descarte com motivo exato, contadores por versão/protocolo e resumo do caminho.
 * O parser em si é coberto por [IpPacketParserTest]; aqui ficam a política e a formatação do log.
 */
class TunDiagnosticsTest {

    private fun tcp(dst: String, dstPort: Int = 443, flags: Int = TunnelPackets.FLAG_SYN): ByteArray {
        val segment = ByteArray(20)
        TunnelPackets.writeU16(segment, 0, 41234)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU32(segment, 4, 1000)
        segment[12] = (5 shl 4).toByte()
        segment[13] = flags.toByte()
        TunnelPackets.writeU16(segment, 14, 65535)
        return TunnelPackets.wrapIpv4(
            checkNotNull(TunnelPackets.ipv4Bytes(CdnRouterConfig.VPN_ADDRESS)),
            checkNotNull(TunnelPackets.ipv4Bytes(dst)),
            TunnelPackets.PROTO_TCP,
            segment,
            64
        )
    }

    private fun udp(dst: String, dstPort: Int, payload: ByteArray = dnsQuery()): ByteArray {
        val segment = ByteArray(8 + payload.size)
        TunnelPackets.writeU16(segment, 0, 52000)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU16(segment, 4, segment.size)
        payload.copyInto(segment, 8)
        return TunnelPackets.wrapIpv4(
            checkNotNull(TunnelPackets.ipv4Bytes(CdnRouterConfig.VPN_ADDRESS)),
            checkNotNull(TunnelPackets.ipv4Bytes(dst)),
            TunnelPackets.PROTO_UDP,
            segment,
            64
        )
    }

    private fun dnsQuery(name: String = "prod.cdni.callofduty.com"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun header(packet: ByteArray): PacketHeader =
        ((IpPacketParser.parse(packet, packet.size)) as PacketParse.Ok).header

    @Test
    fun tcpSynToTunnelAddressBouncesToLocalListener() {
        val packet = tcp(CdnRouterConfig.VPN_ADDRESS)
        val view = checkNotNull(TunDiagnostics.view(packet, packet.size)) {
            "visão de compatibilidade precisa reconhecer IPv4"
        }
        assertEquals(TunAction.BOUNCE, TunPolicy.decide(header(packet)).action)
        assertTrue(view.isRedirectDest)
        assertTrue(view.brief().contains("10.111.222.1:41234 -> 10.111.222.1:443"))
        assertEquals("SYN", view.flagNames())
    }

    @Test
    fun tcpToForeignAddressDiscardsWithExactReason() {
        val packet = tcp("23.46.216.80")
        val decision = TunPolicy.decide(header(packet))
        assertEquals(TunDiscardReason.TCP_SEM_ATENDIMENTO, decision.reason)
        val line = TunDiscardReason.TCP_SEM_ATENDIMENTO.line(header(packet).brief(), decision.note)
        assertTrue(line.contains("motivo=TCP_SEM_ATENDIMENTO"))
        assertTrue(line.contains("IP-CDNI-CONHECIDO(M2.2)"))
    }

    @Test
    fun udpDnsIsHandledAndOtherUdpDiscarded() {
        assertEquals(TunAction.RESPOSTA_DNS, TunPolicy.decide(header(udp(CdnRouterConfig.VPN_DNS, 53))).action)
        val other = TunPolicy.decide(header(udp("1.1.1.1", 784, ByteArray(0))))
        assertEquals(TunDiscardReason.UDP_PORTA_NAO_DNS, other.reason)
    }

    @Test
    fun icmpBecomesPolicyDiscardNotInvalidPacket() {
        val segment = ByteArray(8)
        val packet = TunnelPackets.wrapIpv4(
            checkNotNull(TunnelPackets.ipv4Bytes(CdnRouterConfig.VPN_ADDRESS)),
            checkNotNull(TunnelPackets.ipv4Bytes(CdnRouterConfig.VPN_ADDRESS)),
            TunnelPackets.PROTO_ICMP,
            segment,
            64
        )
        assertEquals(TunAction.DESCARTE, TunPolicy.decide(header(packet)).action)
        assertEquals(TunDiscardReason.PROTO_NAO_SUPORTADO, TunPolicy.decide(header(packet)).reason)
    }

    @Test
    fun compatibilityViewIgnoresIpv6AndInvalidPackets() {
        val ipv6 = ByteArray(48).also { it[0] = 0x60 }
        assertNull("IPv6 não é IPv4: a visão antiga deve dizer null (o parser diz o resto)", TunDiagnostics.view(ipv6, ipv6.size))
        assertNull(TunDiagnostics.view(ByteArray(4), 4))
    }

    @Test
    fun summaryLineCarriesEveryRequestedCounter() {
        val counters = RequestCounters(
            dnsQueries = 3,
            dnsIntercepted = 1,
            dnsForwarded = 2,
            tcpConnections = 5,
            tlsFailed = 5,
            tunPacketsTotal = 12,
            tunIpv4Packets = 10,
            tunIpv6Packets = 2,
            tunTcpPackets = 6,
            tunUdpPackets = 4,
            tunIcmpPackets = 0,
            tunInvalidPackets = 1,
            tunIpv4ToCdnTarget = 5,
            tunIpv6ToCdnTarget = 2,
            tunToRedirect = 5,
            tunBounces = 5,
            tunDiscards = 6,
            tunTcpSyn = 6,
            tunTcpSynToRedirect = 5,
            tunTcpSynOther = 1,
            tunUdpDns53 = 2,
            tunUdpDnsNoVirtualDns = 2,
            tunDotFlows = 0,
            tunDohCandidates = 1
        )
        val line = TunDiagnostics.summaryLine(counters)
        for (expected in listOf(
            "pacotes=12", "ipv4=10", "ipv6=2", "tcp=6", "udp=4", "icmp=0", "invalidos=1",
            "para-alvo-ipv4=5", "para-alvo-ipv6=2", "toRedirect=5", "bounces=5", "descartes=6",
            "syn=6/alvo443=5/outros=1", "dns53=2/virtual=2", "dot=0", "quic/doh=1",
            "dns-total=3", "dns-cdni-interceptado=1", "dns-encaminhado=2", "tcp-conexoes=5", "tls-falha=5",
            // M3.6: a seção que separa descoberta local de tráfego IPv6 de verdade e o rastreio de destino
            "ipv6-descartado=2", "descoberta-local=0", "unicast=0", "destino-resolvido=0", "respostas-dns=0"
        )) {
            assertTrue("resumo deve conter $expected", line.contains(expected))
        }
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
    fun discardLineNeverUsesGenericInvalidMotivoForPolicyDiscards() {
        val line = TunDiscardReason.IPV6_SEM_ATENDIMENTO.line("IPv6 UDP [fe80::1]:5353 -> [::ffff:10.111.222.1]:53")
        assertTrue(line.contains("motivo=IPV6_SEM_ATENDIMENTO"))
        assertFalse(line.contains("PACOTE_INVALIDO"))
    }
}
