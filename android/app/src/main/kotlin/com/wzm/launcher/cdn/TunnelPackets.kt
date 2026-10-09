package com.wzm.launcher.cdn

/**
 * Leitura/escrita de pacotes IPv4 crus do TUN (sem dependências Android — testável em JVM).
 * Somente o necessário para: responder DNS em userspace, devolver pacotes ao cliente e
 * rejeitar (RST) conexões que não podemos atender.
 */
object TunnelPackets {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun u32(data: ByteArray, offset: Int): Long =
        (u16(data, offset).toLong() shl 16) or u16(data, offset + 2).toLong()

    fun version(packet: ByteArray): Int = (packet[0].toInt() and 0xF0) shr 4

    fun ihl(packet: ByteArray): Int = (packet[0].toInt() and 0x0F) * 4

    fun protocol(packet: ByteArray): Int = packet[9].toInt() and 0xFF

    fun totalLength(packet: ByteArray, length: Int): Int = minOf(u16(packet, 2), length).coerceAtLeast(20)

    fun srcAddress(packet: ByteArray): String = address(packet, 12)

    fun dstAddress(packet: ByteArray): String = address(packet, 16)

    fun srcPort(packet: ByteArray, ipHeaderLength: Int): Int = u16(packet, ipHeaderLength)

    fun dstPort(packet: ByteArray, ipHeaderLength: Int): Int = u16(packet, ipHeaderLength + 2)

    fun tcpFlags(packet: ByteArray, ipHeaderLength: Int): Int = packet[ipHeaderLength + 13].toInt() and 0xFF

    fun hasFlag(packet: ByteArray, ipHeaderLength: Int, flag: Int): Boolean =
        tcpFlags(packet, ipHeaderLength) and flag != 0

    /** Offset do payload TCP/UDP (= tamanho do cabeçalho IP). */
    fun transportOffset(packet: ByteArray): Int = ihl(packet)

    /** Offset do payload DNS dentro de um pacote UDP. */
    fun udpPayloadOffset(packet: ByteArray): Int = ihl(packet) + 8

    fun isValid(packet: ByteArray, length: Int): Boolean {
        if (length < 20 || packet.size < 20) return false
        if (version(packet) != 4) return false
        val ipHeaderLength = ihl(packet)
        return ipHeaderLength in 20..60 && ipHeaderLength <= length
    }

    private fun address(packet: ByteArray, offset: Int): String =
        "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}." +
            "${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"

    /** Pacote IPv4+UDP completo (usado para devolver respostas DNS ao cliente). */
    fun buildUdpPacket(
        srcAddress: String,
        srcPort: Int,
        dstAddress: String,
        dstPort: Int,
        payload: ByteArray,
        ttl: Int = 64
    ): ByteArray {
        val source = ipv4Bytes(srcAddress) ?: throw IllegalArgumentException("IPv4 inválido: $srcAddress")
        val destination = ipv4Bytes(dstAddress) ?: throw IllegalArgumentException("IPv4 inválido: $dstAddress")
        val udp = ByteArray(8 + payload.size)
        writeU16(udp, 0, srcPort)
        writeU16(udp, 2, dstPort)
        writeU16(udp, 4, udp.size)
        writeU16(udp, 6, 0) // checksum: opcional em IPv4/UDP
        System.arraycopy(payload, 0, udp, 8, payload.size)
        return wrapIpv4(source, destination, PROTO_UDP, udp, ttl)
    }

    /**
     * RST conforme RFC 793 §3.4 (porta fechada). Devolve null se o pacote não permitir
     * uma resposta segura (não-TCP, cabeçalho inválido, pacote que já é RST).
     */
    fun buildTcpReset(packet: ByteArray, length: Int): ByteArray? {
        if (!isValid(packet, length)) return null
        if (protocol(packet) != PROTO_TCP) return null
        val ipHeaderLength = ihl(packet)
        if (length < ipHeaderLength + 20) return null
        val flags = tcpFlags(packet, ipHeaderLength)
        if (flags and FLAG_RST != 0) return null // não responder a RST (evita loops)

        val tcpHeaderLength = ((packet[ipHeaderLength + 12].toInt() and 0xF0) shr 4) * 4
        val payloadLength = (length - ipHeaderLength - tcpHeaderLength).coerceAtLeast(0)
        val incomingSeq = u32(packet, ipHeaderLength + 4)
        val incomingAck = u32(packet, ipHeaderLength + 8)
        val hasAck = flags and FLAG_ACK != 0

        val sequence = if (hasAck) incomingAck else 0L
        val acknowledgement = if (hasAck) 0L else incomingSeq + payloadLength +
            (if (flags and FLAG_SYN != 0) 1 else 0) + (if (flags and FLAG_FIN != 0) 1 else 0)
        val newFlags = if (hasAck) FLAG_RST else FLAG_RST or FLAG_ACK

        val segment = ByteArray(20)
        writeU16(segment, 0, dstPort(packet, ipHeaderLength)) // src = porta de destino original
        writeU16(segment, 2, srcPort(packet, ipHeaderLength))
        writeU32(segment, 4, sequence)
        writeU32(segment, 8, acknowledgement)
        segment[12] = (5 shl 4).toByte() // data offset = 20 bytes
        segment[13] = newFlags.toByte()
        writeU16(segment, 14, 0) // window
        writeU16(segment, 16, 0) // checksum provisório
        writeU16(segment, 18, 0) // urgent pointer

        val source = ipv4Bytes(dstAddress(packet))!!
        val destination = ipv4Bytes(srcAddress(packet))!!
        writeU16(segment, 16, transportChecksum(source, destination, PROTO_TCP, segment))
        return wrapIpv4(source, destination, PROTO_TCP, segment, ttl = 64)
    }

    fun wrapIpv4(
        src: ByteArray,
        dst: ByteArray,
        protocol: Int,
        segment: ByteArray,
        ttl: Int
    ): ByteArray {
        val packet = ByteArray(20 + segment.size)
        packet[0] = 0x45 // IPv4, IHL=5
        packet[1] = 0
        writeU16(packet, 2, packet.size)
        writeU16(packet, 4, 0) // identificação
        writeU16(packet, 6, 0x4000) // DF
        packet[8] = ttl.toByte()
        packet[9] = protocol.toByte()
        writeU16(packet, 10, 0)
        System.arraycopy(src, 0, packet, 12, 4)
        System.arraycopy(dst, 0, packet, 16, 4)
        writeU16(packet, 10, ipChecksum(packet, 0, 20))
        System.arraycopy(segment, 0, packet, 20, segment.size)
        return packet
    }

    fun ipv4Bytes(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[index] = value.toByte()
        }
        return out
    }

    fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeU32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    /** Checksum de cabeçalho IPv4: soma em complemento de um, invertida. */
    fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += u16(data, index).toLong()
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xFF).toLong() shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Checksum de transporte com pseudo-cabeçalho (TCP). */
    fun transportChecksum(src: ByteArray, dst: ByteArray, protocol: Int, segment: ByteArray): Int {
        var sum = 0L
        var index = 0
        while (index + 1 < src.size) { sum += u16(src, index).toLong(); index += 2 }
        index = 0
        while (index + 1 < dst.size) { sum += u16(dst, index).toLong(); index += 2 }
        sum += protocol.toLong()
        sum += segment.size.toLong()
        index = 0
        while (index + 1 < segment.size) { sum += u16(segment, index).toLong(); index += 2 }
        if (index < segment.size) sum += (segment[index].toInt() and 0xFF).toLong() shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
