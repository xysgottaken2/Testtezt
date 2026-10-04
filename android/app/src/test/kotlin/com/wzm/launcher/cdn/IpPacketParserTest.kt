package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * M3.4 — parser do TUN: IPv4 **e** IPv6, com motivo exato de descarte.
 *
 * Regressão central desta suíte: na evidência de 2026-10-04 dois pacotes (76 B e 48 B) saíram
 * como `PACOTE_INVALIDO` genérico. Os mesmos tamanhos batem com IPv6 (40 B de cabeçalho +
 * ICMPv6 de 8 B = 48 B; 40 + UDP 8 + consulta = 76 B). Aqui os dois lados são testados **sem**
 * presumir o protocolo: os mesmos tamanhos existem como IPv6 válido **e** como lixo inválido.
 */
class IpPacketParserTest {

    // ---------- helpers ----------

    private fun v4(value: String): ByteArray = checkNotNull(TunnelPackets.ipv4Bytes(value))

    private fun ipv4(
        src: String,
        dst: String,
        protocol: Int,
        segment: ByteArray,
        declaredTotalOverride: Int? = null,
        ihlOverride: Int? = null,
        payloadLengthOverride: Int? = null
    ): ByteArray {
        val packet = TunnelPackets.wrapIpv4(v4(src), v4(dst), protocol, segment, 64)
        val headerLength = packet.size - segment.size
        if (ihlOverride != null) {
            packet[0] = (0x40 or (ihlOverride / 4)).toByte()
        }
        val declared = declaredTotalOverride
            ?: (payloadLengthOverride?.let { headerLength + it } ?: packet.size)
        packet[2] = ((declared shr 8) and 0xFF).toByte()
        packet[3] = (declared and 0xFF).toByte()
        return packet
    }

    private fun tcpSegment(srcPort: Int, dstPort: Int, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val segment = ByteArray(20 + payload.size)
        TunnelPackets.writeU16(segment, 0, srcPort)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU32(segment, 4, 1000)
        TunnelPackets.writeU32(segment, 8, 0)
        segment[12] = (5 shl 4).toByte()
        segment[13] = flags.toByte()
        TunnelPackets.writeU16(segment, 14, 65535)
        payload.copyInto(segment, 20)
        return segment
    }

    private fun udpSegment(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val segment = ByteArray(8 + payload.size)
        TunnelPackets.writeU16(segment, 0, srcPort)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU16(segment, 4, segment.size)
        payload.copyInto(segment, 8)
        return segment
    }

    private fun v6(vararg groups: Int): ByteArray {
        val out = ByteArray(16)
        groups.forEachIndexed { index, group ->
            out[index * 2] = ((group shr 8) and 0xFF).toByte()
            out[index * 2 + 1] = (group and 0xFF).toByte()
        }
        return out
    }

    private val loopbackV6 = v6(0xfe80, 0, 0, 0, 0, 0, 0, 1)
    private val mappedTargetV6 = v6(0, 0, 0, 0, 0, 0xffff, 0x0a6f, 0xde01)

    private fun ipv6(
        nextHeader: Int,
        payload: ByteArray,
        src: ByteArray = loopbackV6,
        dst: ByteArray = mappedTargetV6,
        payloadLengthOverride: Int? = null
    ): ByteArray {
        val packet = ByteArray(40 + payload.size)
        packet[0] = 0x60
        val declared = payloadLengthOverride ?: payload.size
        packet[4] = ((declared shr 8) and 0xFF).toByte()
        packet[5] = (declared and 0xFF).toByte()
        packet[6] = nextHeader.toByte()
        packet[7] = 64
        src.copyInto(packet, 8)
        dst.copyInto(packet, 24)
        payload.copyInto(packet, 40)
        return packet
    }

    private fun dnsQueryBytes(name: String, type: Int = DnsMessage.TYPE_A): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), (type and 0xFF).toByte(), 0x00, 0x01))
        return out.toByteArray()
    }

    private fun ok(packet: ByteArray): PacketHeader {
        val parsed = IpPacketParser.parse(packet, packet.size)
        assertTrue("esperava pacote válido, veio $parsed", parsed is PacketParse.Ok)
        return (parsed as PacketParse.Ok).header
    }

    private fun fault(packet: ByteArray): PacketParse.Fault {
        val parsed = IpPacketParser.parse(packet, packet.size)
        assertTrue("esperava falha, veio $parsed", parsed is PacketParse.Fault)
        return parsed as PacketParse.Fault
    }

    // ---------- 1. IPv4 válido ----------

    @Test
    fun parsesIpv4TcpToCdnTarget() {
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP,
            tcpSegment(41234, 443, TunnelPackets.FLAG_SYN)
        )
        val header = ok(packet)
        assertEquals(IpVersion.IPV4, header.version)
        assertEquals(TransportKind.TCP, header.protocol)
        assertEquals("10.111.222.1", header.srcAddress)
        assertEquals("10.111.222.1", header.dstAddress)
        assertEquals(41234, header.srcPort)
        assertEquals(443, header.dstPort)
        assertEquals(20, header.headerLength)
        assertEquals(0, header.transportPayloadLength)
        assertTrue(header.isSyn)
        assertFalse(header.isSynAck)
        assertTrue(header.isCdnTargetV4)
        assertTrue(header.isCdnTarget)
        assertEquals("ALVO-CDNI-LOCAL", header.destinationClass)
        assertEquals("SYN", header.flagNames())
        assertTrue(header.brief().startsWith("IPv4 TCP 10.111.222.1:41234 -> 10.111.222.1:443"))
    }

    @Test
    fun parsesIpv4UdpToAnyPort() {
        val packet = ipv4(
            "10.111.222.1", "10.111.222.2", TunnelPackets.PROTO_UDP,
            udpSegment(53000, 53, dnsQueryBytes("prod.cdni.callofduty.com"))
        )
        val header = ok(packet)
        assertEquals(TransportKind.UDP, header.protocol)
        assertEquals(53, header.dstPort)
        assertTrue(header.isDnsPort)
        assertEquals("DNS-VIRTUAL-DO-TUNEL", header.destinationClass)
        assertTrue("payload do DNS deve existir", (header.transportPayloadLength ?: 0) > 0)
        assertEquals(TunAction.RESPOSTA_DNS, TunPolicy.decide(header).action)
    }

    @Test
    fun parsesIpv4Icmp() {
        val packet = ipv4("10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_ICMP, ByteArray(8))
        val header = ok(packet)
        assertEquals(TransportKind.ICMP, header.protocol)
        assertTrue(header.isIcmp)
        assertEquals(TunDiscardReason.PROTO_NAO_SUPORTADO, TunPolicy.decide(header).reason)
    }

    // ---------- 2. IPv6 válido (nunca "inválido") ----------

    @Test
    fun parsesIpv6UdpAsIpv6NotInvalid_76BytesRegression() {
        // 40 B (IPv6) + 8 B (UDP) + 28 B = 76 B — o tamanho exato observado no device.
        val payload = udpSegment(5353, 53, dnsQueryBytes("prod.cdni.callofduty.com").copyOf(28))
        val packet = ipv6(TunnelPackets.PROTO_UDP, payload)
        assertEquals("regressão: precisa continuar com 76 B", 76, packet.size)
        val header = ok(packet)
        assertEquals(IpVersion.IPV6, header.version)
        assertEquals(TransportKind.UDP, header.protocol)
        assertEquals("fe80::1", header.srcAddress)
        assertEquals(CdnRouterConfig.CDN_TARGET_V6, header.dstAddress)
        assertEquals(53, header.dstPort)
        assertEquals(64, header.hopLimit)
        assertTrue("destino IPv4-mapeado do alvo conta como alvo IPv6", header.isCdnTargetV6)
        assertTrue(header.brief().startsWith("IPv6 UDP [fe80::1]"))
        val decision = TunPolicy.decide(header)
        assertEquals(TunAction.DESCARTE, decision.action)
        assertEquals(TunDiscardReason.IPV6_SEM_ATENDIMENTO, decision.reason)
        assertTrue(decision.note.contains("IPv6 válido"))
    }

    @Test
    fun parsesIpv6Icmpv6AsIpv6NotInvalid_48BytesRegression() {
        // 40 B (IPv6) + 8 B (ICMPv6 echo) = 48 B — o outro tamanho observado no device.
        val icmpv6 = ByteArray(8).also { it[0] = 128.toByte() }
        val packet = ipv6(TransportKind.ICMPV6.code, icmpv6)
        assertEquals("regressão: precisa continuar com 48 B", 48, packet.size)
        val header = ok(packet)
        assertEquals(IpVersion.IPV6, header.version)
        assertEquals(TransportKind.ICMPV6, header.protocol)
        assertTrue(header.isIcmp)
        assertNull(header.srcPort)
        assertEquals(TunDiscardReason.IPV6_SEM_ATENDIMENTO, TunPolicy.decide(header).reason)
    }

    @Test
    fun ipv6ExtensionHeaderIsWalkedAndDoTIsFlagged() {
        val tcp = tcpSegment(51000, CdnRouterConfig.DOT_PORT, TunnelPackets.FLAG_SYN)
        val hopByHop = ByteArray(8).also { it[0] = TunnelPackets.PROTO_TCP.toByte(); it[1] = 0 }
        val packet = ipv6(0, hopByHop + tcp)
        val header = ok(packet)
        assertEquals(TransportKind.TCP, header.protocol)
        assertEquals(CdnRouterConfig.DOT_PORT, header.dstPort)
        assertEquals(listOf(0), header.extensionHeaders)
        assertTrue(TunPolicy.decide(header).note.contains("DoT candidato"))
    }

    @Test
    fun ipv6AddressFormattingCompressesZerosAndMapsIpv4() {
        val packet = ipv6(TransportKind.UDP.code, udpSegment(1, 2, ByteArray(0)))
        val header = ok(packet)
        assertEquals("::ffff:10.111.222.1", header.dstAddress)
        assertEquals("fe80::1", header.srcAddress)
    }

    // ---------- 3. Pacotes realmente inválidos: motivo exato + hex limitado ----------

    @Test
    fun truncatedPacketGetsCurtoDemaisWithHexPreview() {
        val packet = ByteArray(12) { 0x45 }
        val failure = fault(packet)
        assertEquals(PacketFault.CURTO_DEMAIS, failure.fault)
        assertEquals(12, failure.rawLength)
        assertEquals(4, failure.firstNibble)
        assertTrue(TunDiagnostics.faultLine(failure).contains("motivo=CURTO_DEMAIS"))
        assertTrue(tunLineHasPreviewAndNoPayloadDump(failure))
    }

    @Test
    fun unknownVersionIsReportedAsVersionNotAsGenericInvalid() {
        val packet = ByteArray(40).also { it[0] = 0x0F }
        val failure = fault(packet)
        assertEquals(PacketFault.VERSAO_DESCONHECIDA, failure.fault)
        assertEquals(0, failure.firstNibble)
        val line = TunDiagnostics.faultLine(failure)
        assertTrue(line.contains("motivo=VERSAO_DESCONHECIDA"))
        assertFalse("não pode existir motivo genérico", line.contains("PACOTE_INVALIDO"))
        assertTrue(line.contains("hex[<=32]"))
    }

    @Test
    fun inconsistentIpv4HeaderIsRejected() {
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP,
            tcpSegment(1, 2, 0), ihlOverride = 0
        )
        assertEquals(PacketFault.IPV4_CABECALHO_INCONSISTENTE, fault(packet).fault)
    }

    @Test
    fun headerLongerThanReadLengthIsRejected() {
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP,
            tcpSegment(1, 2, 0), ihlOverride = 60
        )
        assertEquals(PacketFault.IPV4_CABECALHO_INCONSISTENTE, fault(packet).fault)
    }

    @Test
    fun payloadSmallerThanHeaderIsRejected() {
        // total length declarado (20) é menor que o cabeçalho real (20 + payload de 20)
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP,
            tcpSegment(1, 2, 0), declaredTotalOverride = 12
        )
        assertEquals(PacketFault.TAMANHO_DECLARADO_MENOR_QUE_CABECALHO, fault(packet).fault)
    }

    @Test
    fun declaredLengthGreaterThanReadIsRejected() {
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP,
            tcpSegment(1, 2, 0), declaredTotalOverride = 900
        )
        assertEquals(PacketFault.TAMANHO_DECLARADO_MAIOR_QUE_LIDO, fault(packet).fault)
    }

    @Test
    fun shortTransportHeaderIsRejected() {
        // cabeçalho IP diz TCP, mas só há 4 bytes de "transporte" depois dele
        val packet = ipv4(
            "10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP, ByteArray(4)
        )
        assertEquals(PacketFault.TRANSPORTE_CABECALHO_CURTO, fault(packet).fault)
    }

    @Test
    fun ipv6WithoutFullHeaderIsRejectedAsIpv6ShortHeader() {
        val packet = ByteArray(30).also { it[0] = 0x60 }
        assertEquals(PacketFault.IPV6_CABECALHO_CURTO, fault(packet).fault)
    }

    /**
     * O usuário pediu explicitamente o teste de regressão 76 B / 48 B **sem assumir** o protocolo:
     * aqui os MESMOS tamanhos aparecem como lixo (sem versão válida) e devem virar motivo exato
     * com prévia hexadecimal curta — nada de "PACOTE_INVALIDO".
     */
    @Test
    fun garbageOfTheObservedSizesIsInvalidWithBoundedHex() {
        for (size in intArrayOf(76, 48)) {
            val garbage = ByteArray(size) { 0x11 }
            val failure = fault(garbage)
            assertEquals(size, failure.rawLength)
            assertEquals(PacketFault.VERSAO_DESCONHECIDA, failure.fault)
            assertTrue(tunLineHasPreviewAndNoPayloadDump(failure))
        }
    }

    private fun tunLineHasPreviewAndNoPayloadDump(failure: PacketParse.Fault): Boolean {
        val line = TunDiagnostics.faultLine(failure)
        val hex = Regex("hex\\[<=32\\]=\\{([^}]*)\\}").find(line)?.groupValues?.get(1) ?: return false
        val bytes = hex.split(' ').filter { it.isNotBlank() }
        return bytes.size <= IpPacketParser.HEX_PREVIEW_BYTES &&
            bytes.all { it.length == 2 && it.all { char -> char in "0123456789abcdef" } } &&
            IpPacketParser.hexPreview(ByteArray(100), 100).split(' ').size == IpPacketParser.HEX_PREVIEW_BYTES
    }

    // ---------- 4. Política de descarte e observações ----------

    @Test
    fun tcpToForeignHttpsIsDiscardedWithMetadataOnly() {
        val packet = ipv4(
            "10.111.222.1", CdnRouterConfig.KNOWN_CDNI_IPS.first(), TunnelPackets.PROTO_TCP,
            tcpSegment(41234, 443, TunnelPackets.FLAG_SYN)
        )
        val header = ok(packet)
        val decision = TunPolicy.decide(header)
        assertEquals(TunAction.DESCARTE, decision.action)
        assertEquals(TunDiscardReason.TCP_SEM_ATENDIMENTO, decision.reason)
        assertEquals("IP-CDNI-CONHECIDO(M2.2)", header.destinationClass)
        assertTrue("nota deve dizer que nada de payload é registrado", decision.note.contains("sem payload"))
        assertEquals(
            listOf(TunObservation.TCP_SYN, TunObservation.TCP_SYN_OUTRO_DESTINO, TunObservation.TCP_443_EXTERNO),
            TunPolicy.observations(header)
        )
    }

    @Test
    fun observationsCoverSynToTargetDnsVirtualAndQuic() {
        val syn = ok(
            ipv4("10.111.222.1", "10.111.222.1", TunnelPackets.PROTO_TCP, tcpSegment(1, 443, TunnelPackets.FLAG_SYN))
        )
        assertEquals(
            listOf(TunObservation.TCP_SYN, TunObservation.TCP_SYN_PARA_ALVO_443),
            TunPolicy.observations(syn)
        )
        assertEquals(TunAction.BOUNCE, TunPolicy.decide(syn).action)

        val dns = ok(
            ipv4(
                "10.111.222.1", CdnRouterConfig.VPN_DNS, TunnelPackets.PROTO_UDP,
                udpSegment(5000, 53, dnsQueryBytes("dns.adguard.com", DnsMessage.TYPE_AAAA))
            )
        )
        assertEquals(listOf(TunObservation.UDP_DNS_53, TunObservation.UDP_DNS_NO_DNS_VIRTUAL), TunPolicy.observations(dns))

        val quic = ok(
            ipv4("10.111.222.1", "1.1.1.1", TunnelPackets.PROTO_UDP, udpSegment(5000, 443, ByteArray(4)))
        )
        assertEquals(listOf(TunObservation.UDP_443_QUIC_DOH), TunPolicy.observations(quic))
        assertTrue(TunPolicy.decide(quic).note.contains("QUIC/DoH candidato"))
    }

    @Test
    fun udpToNonDnsPortIsDiscardedWithReason() {
        val header = ok(ipv4("10.111.222.1", "1.1.1.1", TunnelPackets.PROTO_UDP, udpSegment(5000, 784, ByteArray(0))))
        assertEquals(TunDiscardReason.UDP_PORTA_NAO_DNS, TunPolicy.decide(header).reason)
    }

    // ---------- 5. DNS CDNI vs. não-CDNI no caminho completo do parser ----------

    @Test
    fun cdnDnsQueryParsedFromTunIsInterceptedAndOtherNameIsNot() {
        val responder = DnsResponder()
        val cdnQuery = dnsQueryBytes("prod.cdni.callofduty.com")
        val cdnPacket = ipv4(
            "10.111.222.1", CdnRouterConfig.VPN_DNS, TunnelPackets.PROTO_UDP,
            udpSegment(5000, 53, cdnQuery)
        )
        val cdnHeader = ok(cdnPacket)
        assertEquals(TunAction.RESPOSTA_DNS, TunPolicy.decide(cdnHeader).action)
        val payload = cdnPacket.copyOfRange(TunnelPackets.udpPayloadOffset(cdnPacket), cdnPacket.size)
        val answer = checkNotNull(responder.answer(payload, payload.size)) { "DNS CDNI deveria ser interceptado" }
        assertEquals("10.111.222.1", answer.takeLast(4).joinToString(".") { (it.toInt() and 0xFF).toString() })

        val otherQuery = dnsQueryBytes("dns.adguard.com", DnsMessage.TYPE_AAAA)
        val otherHeader = ok(
            ipv4(
                "10.111.222.1", CdnRouterConfig.VPN_DNS, TunnelPackets.PROTO_UDP,
                udpSegment(5000, 53, otherQuery)
            )
        )
        assertEquals(TunAction.RESPOSTA_DNS, TunPolicy.decide(otherHeader).action)
        val otherPayload = otherQuery
        assertNull("nome não-CDNI não é interceptado", responder.answer(otherPayload, otherPayload.size))
    }
}
