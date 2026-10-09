package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.6: classificação do tráfego do TUN só com metadados de cabeçalho.
 *
 * Distingue ICMPv6 multicast de tipos NDP/MLD selecionados, outro multicast e unicast.
 * As categorias não identificam o emissor nem presumem tráfego do sistema ou do jogo.
 */
class TrafficClassifierTest {

    private fun ipv6Header(
        src: String,
        dst: String,
        protocolCode: Int,
        icmpType: Int? = null
    ) = PacketHeader(
        version = IpVersion.IPV6,
        rawLength = 48,
        declaredTotalLength = 48,
        headerLength = 40,
        payloadLength = 8,
        transportPayloadLength = null,
        protocolCode = protocolCode,
        protocol = TransportKind.of(protocolCode),
        srcAddress = src,
        dstAddress = dst,
        srcPort = null,
        dstPort = null,
        tcpFlags = null,
        hopLimit = 255,
        icmpType = icmpType
    )

    @Test
    fun scopesAreClassifiedFromTheFormattedAddress() {
        assertEquals(TrafficClassifier.Scope.LINK_LOCAL, TrafficClassifier.scopeOf("fe80::1"))
        assertEquals(TrafficClassifier.Scope.LINK_LOCAL, TrafficClassifier.scopeOf("febf:ffff::2"))
        assertEquals(TrafficClassifier.Scope.MULTICAST, TrafficClassifier.scopeOf("ff02::1"))
        assertEquals(TrafficClassifier.Scope.MULTICAST, TrafficClassifier.scopeOf("ff02::2"))
        assertEquals(TrafficClassifier.Scope.LOOPBACK, TrafficClassifier.scopeOf("::1"))
        assertEquals(TrafficClassifier.Scope.MAPEADO_IPV4, TrafficClassifier.scopeOf("::ffff:10.111.222.1"))
        assertEquals(TrafficClassifier.Scope.ULA, TrafficClassifier.scopeOf("fd00::1"))
        assertEquals(TrafficClassifier.Scope.GLOBAL, TrafficClassifier.scopeOf("2a00:1450:4001::1"))
    }

    @Test
    fun neighborSolicitationIsLocalDiscovery() {
        val header = ipv6Header("fe80::a", "ff02::1:ff00:2", 58, icmpType = 135)
        val profile = TrafficClassifier.ipv6Profile(header)
        assertTrue(profile.label(), profile.localDiscovery)
        assertTrue(profile.label(), profile.label().contains("neighbor-solicitation"))
        assertEquals(TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL, TrafficClassifier.ipv6Category(header))
    }

    @Test
    fun routerAdvertisementAndMldAreAlsoLocalDiscovery() {
        assertEquals(
            TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL,
            TrafficClassifier.ipv6Category(ipv6Header("fe80::1", "ff02::1", 58, icmpType = 134))
        )
        assertEquals(
            TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL,
            TrafficClassifier.ipv6Category(ipv6Header("fe80::1", "ff02::16", 58, icmpType = 143))
        )
    }

    @Test
    fun unicastTrafficIsNotLocalDiscovery() {
        val header = ipv6Header("2a00:1450::1", "2a00:1450::2", 6)
        val profile = TrafficClassifier.ipv6Profile(header)
        assertFalse("unicast global não é descoberta local", profile.localDiscovery)
        assertEquals(TrafficClassifier.Ipv6Category.UNICAST, TrafficClassifier.ipv6Category(header))
    }

    @Test
    fun multicastIcmpEchoIsNotClassifiedAsNeighborDiscovery() {
        val header = ipv6Header("fe80::a", "ff02::1", 58, icmpType = 128)
        val profile = TrafficClassifier.ipv6Profile(header)
        assertTrue(profile.multicast)
        assertFalse(profile.label(), profile.localDiscovery)
        assertEquals(TrafficClassifier.Ipv6Category.MULTICAST_OUTRO, TrafficClassifier.ipv6Category(header))
    }

    @Test
    fun nonIcmpMulticastIsMulticastButNotDiscovery() {
        val header = ipv6Header("fe80::a", "ff02::1:2", 17)
        val profile = TrafficClassifier.ipv6Profile(header)
        assertTrue(profile.multicast)
        assertFalse("só ICMPv6 de vizinhança/MLD é considerado descoberta local", profile.localDiscovery)
        assertEquals(TrafficClassifier.Ipv6Category.MULTICAST_OUTRO, TrafficClassifier.ipv6Category(header))
    }

    @Test
    fun icmpTypeNamesCoverTheObservedPalette() {
        assertEquals("neighbor-solicitation", TrafficClassifier.icmpv6TypeName(135))
        assertEquals("neighbor-advertisement", TrafficClassifier.icmpv6TypeName(136))
        assertEquals("echo-request", TrafficClassifier.icmpv6TypeName(128))
        assertEquals("router-advertisement", TrafficClassifier.icmpv6TypeName(134))
        assertEquals("mld-multicast-listener", TrafficClassifier.icmpv6TypeName(143))
        assertEquals("echo-request", TrafficClassifier.icmpv4TypeName(8))
        assertTrue(TrafficClassifier.icmpv6TypeName(200).startsWith("outro("))
        assertEquals("tipo-desconhecido", TrafficClassifier.icmpv6TypeName(null))
    }

    @Test
    fun destinationClassSeparatesDnsDotQuicAndHttps() {
        fun header(port: Int, protocol: TransportKind) = PacketHeader(
            version = IpVersion.IPV4,
            rawLength = 40,
            declaredTotalLength = 40,
            headerLength = 20,
            payloadLength = 20,
            transportPayloadLength = null,
            protocolCode = protocol.code,
            protocol = protocol,
            srcAddress = "10.111.222.1",
            dstAddress = "23.46.216.80",
            srcPort = 40000,
            dstPort = port,
            tcpFlags = 0x02,
            hopLimit = 64
        )
        assertEquals("DNS:53", TrafficClassifier.destinationClass(header(53, TransportKind.UDP)))
        assertEquals("DoT:853", TrafficClassifier.destinationClass(header(853, TransportKind.TCP)))
        assertEquals("DoT:853", TrafficClassifier.destinationClass(header(853, TransportKind.UDP)))
        assertEquals("QUIC/DoH:443-udp", TrafficClassifier.destinationClass(header(443, TransportKind.UDP)))
        assertEquals("HTTPS:443-tcp", TrafficClassifier.destinationClass(header(443, TransportKind.TCP)))
        assertEquals("HTTP:80", TrafficClassifier.destinationClass(header(80, TransportKind.TCP)))
        assertEquals("outra:1234", TrafficClassifier.destinationClass(header(1234, TransportKind.UDP)))
    }
}
