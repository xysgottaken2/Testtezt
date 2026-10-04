package com.wzm.launcher.cdn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelPacketsTest {

    /** Pacote IPv4 + TCP mínimo e válido (SYN). */
    private fun tcpPacket(
        src: String = "10.111.222.1",
        dst: String = "10.111.222.1",
        srcPort: Int = 45678,
        dstPort: Int = 443,
        flags: Int = TunnelPackets.FLAG_SYN,
        seq: Long = 1000,
        ack: Long = 0,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val segment = ByteArray(20 + payload.size)
        TunnelPackets.writeU16(segment, 0, srcPort)
        TunnelPackets.writeU16(segment, 2, dstPort)
        TunnelPackets.writeU32(segment, 4, seq)
        TunnelPackets.writeU32(segment, 8, ack)
        segment[12] = (5 shl 4).toByte()
        segment[13] = flags.toByte()
        TunnelPackets.writeU16(segment, 14, 65535)
        System.arraycopy(payload, 0, segment, 20, payload.size)
        val source = TunnelPackets.ipv4Bytes(src)!!
        val destination = TunnelPackets.ipv4Bytes(dst)!!
        TunnelPackets.writeU16(segment, 16, TunnelPackets.transportChecksum(source, destination, 6, segment))
        return TunnelPackets.wrapIpv4(source, destination, 6, segment, 64)
    }

    private fun ipChecksumValid(packet: ByteArray): Boolean =
        TunnelPackets.ipChecksum(packet, 0, 20) == 0

    @Test
    fun udpPacketRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = TunnelPackets.buildUdpPacket("10.111.222.2", 53, "10.111.222.1", 44444, payload)
        assertTrue(TunnelPackets.isValid(packet, packet.size))
        assertEquals(4, TunnelPackets.version(packet))
        assertEquals(20, TunnelPackets.ihl(packet))
        assertEquals(TunnelPackets.PROTO_UDP, TunnelPackets.protocol(packet))
        assertEquals("10.111.222.2", TunnelPackets.srcAddress(packet))
        assertEquals("10.111.222.1", TunnelPackets.dstAddress(packet))
        assertEquals(53, TunnelPackets.srcPort(packet, 20))
        assertEquals(44444, TunnelPackets.dstPort(packet, 20))
        assertEquals(packet.size, TunnelPackets.totalLength(packet, packet.size))
        assertTrue(ipChecksumValid(packet))
        val offset = TunnelPackets.udpPayloadOffset(packet)
        assertArrayEquals(payload, packet.copyOfRange(offset, offset + payload.size))
    }

    @Test
    fun resetForSynSwapsEndpointsAndAcksSequence() {
        val syn = tcpPacket(seq = 5000)
        val reset = TunnelPackets.buildTcpReset(syn, syn.size)
        assertNotNull(reset)
        reset!!
        assertEquals(40, reset.size)
        assertEquals("10.111.222.1", TunnelPackets.srcAddress(reset))
        assertEquals("10.111.222.1", TunnelPackets.dstAddress(reset))
        assertEquals(443, TunnelPackets.srcPort(reset, 20))
        assertEquals(45678, TunnelPackets.dstPort(reset, 20))
        assertEquals(TunnelPackets.FLAG_RST or TunnelPackets.FLAG_ACK, TunnelPackets.tcpFlags(reset, 20))
        assertEquals(0L, TunnelPackets.u32(reset, 24)) // seq = 0 quando o original não tem ACK
        assertEquals(5001L, TunnelPackets.u32(reset, 28)) // ack = seq + 1 (SYN consome 1)
        assertTrue(ipChecksumValid(reset))
    }

    @Test
    fun resetForAckUsesIncomingAckAsSequence() {
        val ack = tcpPacket(flags = TunnelPackets.FLAG_ACK, seq = 700, ack = 4321)
        val reset = TunnelPackets.buildTcpReset(ack, ack.size)
        assertNotNull(reset)
        assertEquals(TunnelPackets.FLAG_RST, TunnelPackets.tcpFlags(reset!!, 20))
        assertEquals(4321L, TunnelPackets.u32(reset, 24))
        assertEquals(0L, TunnelPackets.u32(reset, 28))
    }

    @Test
    fun neverResetsAReset() {
        val rst = tcpPacket(flags = TunnelPackets.FLAG_RST)
        assertNull(TunnelPackets.buildTcpReset(rst, rst.size))
    }

    @Test
    fun countsSynPayloadIntoAck() {
        val synWithData = tcpPacket(seq = 10, flags = TunnelPackets.FLAG_SYN, payload = ByteArray(7))
        val reset = TunnelPackets.buildTcpReset(synWithData, synWithData.size)
        assertEquals(10L + 7 + 1, TunnelPackets.u32(reset!!, 28))
    }

    @Test
    fun rejectsNonIpv4() {
        val ipv6ish = ByteArray(40).also { it[0] = 0x60 }
        assertFalse(TunnelPackets.isValid(ipv6ish, ipv6ish.size))
        assertNull(TunnelPackets.buildTcpReset(ipv6ish, ipv6ish.size))
    }
}
