package com.wzm.launcher.cdn

/**
 * Parser de pacotes IP lidos do TUN (M3.4) — Kotlin puro, testável em JVM.
 *
 * Por que existe: na evidência de 2026-10-04 o launcher registrou dois pacotes (76 B e 48 B)
 * como `PACOTE_INVALIDO` genérico. Isso é inútil para diagnóstico: os mesmos tamanhos batem
 * exatamente com **IPv6** (40 B de cabeçalho + 8 B de ICMPv6 = 48 B; 40 + 8 + consulta DNS = 76 B).
 * O parser agora identifica versão, cabeçalho, protocolo, endereços e portas **antes** de decidir.
 *
 * Regras de segurança (nada de payload):
 *  * pacote **válido**: nenhum byte de conteúdo é registrado — só metadados de cabeçalho;
 *  * pacote **inválido**: é registrada uma prévia hexadecimal de no máximo [HEX_PREVIEW_BYTES]
 *    bytes apenas para identificar o formato; nunca de pacotes válidos.
 */

enum class IpVersion(val label: String) {
    IPV4("IPv4"),
    IPV6("IPv6"),
    DESCONHECIDA("versão-desconhecida")
}

/** Protocolo de transporte (IPv4 `protocol` / IPv6 `next header`). */
enum class TransportKind(val code: Int, val label: String, val isIcmp: Boolean = false) {
    ICMP(1, "ICMP", true),
    TCP(6, "TCP"),
    UDP(17, "UDP"),
    ICMPV6(58, "ICMPv6", true),
    OUTRO(-1, "outro"),
    NENHUM(-2, "nenhum");

    companion object {
        fun of(code: Int): TransportKind = values().firstOrNull { it.code == code } ?: OUTRO
    }
}

/**
 * Motivo exato de um pacote não parseável. O [code] é o que aparece no log
 * (`motivo=<CODIGO>`), substituindo o antigo `PACOTE_INVALIDO` genérico.
 */
enum class PacketFault(val code: String, val hint: String) {
    CURTO_DEMAIS(
        "CURTO_DEMAIS",
        "menos de 20 bytes: não há cabeçalho IP completo no que foi lido do TUN"
    ),
    VERSAO_DESCONHECIDA(
        "VERSAO_DESCONHECIDA",
        "o primeiro nibble não é 4 (IPv4) nem 6 (IPv6)"
    ),
    IPV4_CABECALHO_INCONSISTENTE(
        "IPV4_CABECALHO_INCONSISTENTE",
        "IHL fora de 20..60 bytes ou maior que o pacote lido"
    ),
    TAMANHO_DECLARADO_MENOR_QUE_CABECALHO(
        "TAMANHO_DECLARADO_MENOR_QUE_CABECALHO",
        "o total length declarado é menor que o próprio cabeçalho IP"
    ),
    TAMANHO_DECLARADO_MAIOR_QUE_LIDO(
        "TAMANHO_DECLARADO_MAIOR_QUE_LIDO",
        "o tamanho declarado pelo cabeçalho IP é maior que os bytes lidos do TUN"
    ),
    IPV6_CABECALHO_CURTO(
        "IPV6_CABECALHO_CURTO",
        "IPv6 exige 40 bytes de cabeçalho"
    ),
    TRANSPORTE_CABECALHO_CURTO(
        "TRANSPORTE_CABECALHO_CURTO",
        "o cabeçalho TCP/UDP (ou a extensão IPv6) não cabe nos bytes lidos"
    );

    fun line(details: String): String = "motivo=$code ($details) — $hint"
}

/** Cabeçalho reconhecido — só metadados, nunca conteúdo. */
data class PacketHeader(
    val version: IpVersion,
    val rawLength: Int,
    val declaredTotalLength: Int,
    val headerLength: Int,
    /** Bytes depois do cabeçalho IP (inclui o cabeçalho de transporte). */
    val payloadLength: Int,
    /** Bytes depois do cabeçalho de transporte (quando TCP/UDP). */
    val transportPayloadLength: Int?,
    val protocolCode: Int,
    val protocol: TransportKind,
    val srcAddress: String,
    val dstAddress: String,
    val srcPort: Int?,
    val dstPort: Int?,
    val tcpFlags: Int?,
    val hopLimit: Int?,
    val extensionHeaders: List<Int> = emptyList(),
    /**
     * Tipo de ICMP/ICMPv6 (M3.6) — só o primeiro byte do cabeçalho de transporte, nunca o payload.
     * É o que permite dizer "ICMPv6 neighbor-solicitation" em vez de "ICMPv6" genérico.
     */
    val icmpType: Int? = null
) {

    val isTcp: Boolean get() = protocol == TransportKind.TCP
    val isUdp: Boolean get() = protocol == TransportKind.UDP
    val isIcmp: Boolean get() = protocol.isIcmp
    val isIpv4: Boolean get() = version == IpVersion.IPV4
    val isIpv6: Boolean get() = version == IpVersion.IPV6

    val isSyn: Boolean get() = isTcp && tcpFlags != null &&
        (tcpFlags and TunnelPackets.FLAG_SYN) != 0 && (tcpFlags and TunnelPackets.FLAG_ACK) == 0
    val isSynAck: Boolean get() = isTcp && tcpFlags != null &&
        (tcpFlags and TunnelPackets.FLAG_SYN) != 0 && (tcpFlags and TunnelPackets.FLAG_ACK) != 0

    /** Destino é o endereço IPv4 do túnel — o valor que o DNS local devolve. */
    val isCdnTargetV4: Boolean get() = dstAddress == CdnRouterConfig.VPN_ADDRESS

    /** Destino é a forma IPv4-mapeada do endereço do túnel (`::ffff:10.111.222.1`). */
    val isCdnTargetV6: Boolean get() = dstAddress == CdnRouterConfig.CDN_TARGET_V6

    val isCdnTarget: Boolean get() = isCdnTargetV4 || isCdnTargetV6

    val isDnsPort: Boolean get() = dstPort == CdnRouterConfig.DNS_PORT
    val isDotPort: Boolean get() = dstPort == CdnRouterConfig.DOT_PORT
    val isHttpsPort: Boolean get() = dstPort == CdnRouterConfig.LOCAL_HTTPS_PORT

    /** Classificação **informativa** do destino: nunca muda o roteamento. */
    val destinationClass: String
        get() = when {
            isCdnTarget -> "ALVO-CDNI-LOCAL"
            CdnRouterConfig.KNOWN_CDNI_IPS.contains(dstAddress) -> "IP-CDNI-CONHECIDO(M2.2)"
            dstAddress == CdnRouterConfig.VPN_DNS -> "DNS-VIRTUAL-DO-TUNEL"
            dstAddress == CdnRouterConfig.LOOPBACK_ADDRESS -> "LOOPBACK"
            else -> "EXTERNO/DESCONHECIDO"
        }

    fun flagNames(): String {
        val flagsValue = tcpFlags
        if (!isTcp || flagsValue == null) return "-"
        val names = mutableListOf<String>()
        if (flagsValue and TunnelPackets.FLAG_SYN != 0) names += "SYN"
        if (flagsValue and TunnelPackets.FLAG_ACK != 0) names += "ACK"
        if (flagsValue and TunnelPackets.FLAG_FIN != 0) names += "FIN"
        if (flagsValue and TunnelPackets.FLAG_RST != 0) names += "RST"
        if (flagsValue and TunnelPackets.FLAG_PSH != 0) names += "PSH"
        return if (names.isEmpty()) "-" else names.joinToString("+")
    }

    /** `IPv4 TCP 10.111.222.1:41234 -> 10.111.222.1:443 flags=SYN (lido=60 B total=60 payload=0)`. */
    fun brief(): String = buildString {
        append(version.label).append(' ').append(protocol.label)
        append(' ').append(endpoint(srcAddress, srcPort))
        append(" -> ").append(endpoint(dstAddress, dstPort))
        if (isTcp) append(" flags=").append(flagNames())
        if (isIcmp) {
            val name = if (isIpv6) TrafficClassifier.icmpv6TypeName(icmpType)
            else TrafficClassifier.icmpv4TypeName(icmpType)
            append(" tipo=").append(icmpType ?: -1).append(" (").append(name).append(')')
        }
        append(" hop=").append(hopLimit?.toString() ?: "?")
        append(" (lido=").append(rawLength).append(" B total=").append(declaredTotalLength)
        append(" payload=").append(payloadLength).append(" B)")
        if (extensionHeaders.isNotEmpty()) {
            append(" extensões=").append(extensionHeaders.joinToString(","))
        }
    }

    private fun endpoint(address: String, port: Int?): String =
        if (address.contains(':')) "[$address]" + (port?.let { ":$it" } ?: "") else
            address + (port?.let { ":$it" } ?: "")
}

/** Resultado da leitura de um pacote do TUN. */
sealed class PacketParse {
    data class Ok(val header: PacketHeader) : PacketParse()

    data class Fault(
        val rawLength: Int,
        val firstNibble: Int,
        val fault: PacketFault,
        val hexPreview: String
    ) : PacketParse()
}

object IpPacketParser {

    /** Quantos bytes no máximo são mostrados em hexadecimal — e só para pacotes inválidos. */
    const val HEX_PREVIEW_BYTES = 32

    fun parse(packet: ByteArray, length: Int): PacketParse {
        val read = minOf(length, packet.size)
        if (read < 20) return fault(packet, read, PacketFault.CURTO_DEMAIS)
        val version = (packet[0].toInt() and 0xF0) shr 4
        return when (version) {
            4 -> parseIpv4(packet, read)
            6 -> parseIpv6(packet, read)
            else -> fault(packet, read, PacketFault.VERSAO_DESCONHECIDA)
        }
    }

    private fun parseIpv4(packet: ByteArray, read: Int): PacketParse {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > 60 || ihl > read) {
            return fault(packet, read, PacketFault.IPV4_CABECALHO_INCONSISTENTE)
        }
        val declared = TunnelPackets.u16(packet, 2)
        if (declared < ihl) {
            return fault(packet, read, PacketFault.TAMANHO_DECLARADO_MENOR_QUE_CABECALHO)
        }
        if (declared > read) {
            return fault(packet, read, PacketFault.TAMANHO_DECLARADO_MAIOR_QUE_LIDO)
        }
        val protocolCode = packet[9].toInt() and 0xFF
        val protocol = TransportKind.of(protocolCode)
        val ports = transportPorts(packet, read, ihl, protocol) ?: return fault(
            packet, read, PacketFault.TRANSPORTE_CABECALHO_CURTO
        )
        return PacketParse.Ok(
            PacketHeader(
                version = IpVersion.IPV4,
                rawLength = read,
                declaredTotalLength = declared,
                headerLength = ihl,
                payloadLength = declared - ihl,
                transportPayloadLength = transportPayload(declared, ihl, ports.headerBytes),
                protocolCode = protocolCode,
                protocol = protocol,
                srcAddress = TunnelPackets.srcAddress(packet),
                dstAddress = TunnelPackets.dstAddress(packet),
                srcPort = ports.src,
                dstPort = ports.dst,
                tcpFlags = ports.tcpFlags,
                hopLimit = packet[8].toInt() and 0xFF,
                icmpType = icmpType(packet, read, ihl, protocol)
            )
        )
    }

    private fun parseIpv6(packet: ByteArray, read: Int): PacketParse {
        if (read < 40) return fault(packet, read, PacketFault.IPV6_CABECALHO_CURTO)
        val declaredPayload = TunnelPackets.u16(packet, 4)
        val declared = 40 + declaredPayload
        if (declared > read) return fault(packet, read, PacketFault.TAMANHO_DECLARADO_MAIOR_QUE_LIDO)

        var nextHeader = packet[6].toInt() and 0xFF
        var offset = 40
        val extensions = mutableListOf<Int>()
        var guard = 0
        while (guard++ < 8) {
            when (nextHeader) {
                0, 43, 60 -> { // hop-by-hop, routing, destination options
                    if (offset + 2 > read) return fault(packet, read, PacketFault.TRANSPORTE_CABECALHO_CURTO)
                    extensions += nextHeader
                    val extensionLength = (packet[offset + 1].toInt() and 0xFF) * 8 + 8
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += extensionLength
                }
                44 -> { // fragmento: só o primeiro fragmento tem cabeçalho de transporte
                    if (offset + 8 > read) return fault(packet, read, PacketFault.TRANSPORTE_CABECALHO_CURTO)
                    extensions += nextHeader
                    val fragmentOffset = ((packet[offset + 2].toInt() and 0xFF) shl 8 or
                        (packet[offset + 3].toInt() and 0xFF)) shr 3
                    nextHeader = packet[offset].toInt() and 0xFF
                    offset += 8
                    if (fragmentOffset != 0) {
                        return PacketParse.Ok(
                            header(
                                packet, read, declared, offset, TransportKind.OUTRO, nextHeader,
                                extensions, null, null, null, payloadStart = offset
                            )
                        )
                    }
                }
                else -> break
            }
            if (offset > read) return fault(packet, read, PacketFault.TRANSPORTE_CABECALHO_CURTO)
        }

        val protocolCode = nextHeader
        val protocol = TransportKind.of(protocolCode)
        val ports = transportPorts(packet, read, offset, protocol) ?: return fault(
            packet, read, PacketFault.TRANSPORTE_CABECALHO_CURTO
        )
        return PacketParse.Ok(
            header(
                packet, read, declared, offset, protocol, protocolCode, extensions,
                ports.src, ports.dst, ports.tcpFlags, ports.headerBytes
            )
        )
    }

    private fun header(
        packet: ByteArray,
        read: Int,
        declared: Int,
        headerEnd: Int,
        protocol: TransportKind,
        protocolCode: Int,
        extensions: List<Int>,
        srcPort: Int?,
        dstPort: Int?,
        tcpFlags: Int?,
        payloadStart: Int
    ): PacketHeader = PacketHeader(
        version = IpVersion.IPV6,
        rawLength = read,
        declaredTotalLength = declared,
        headerLength = payloadStart,
        payloadLength = declared - payloadStart,
        transportPayloadLength = null,
        protocolCode = protocolCode,
        protocol = protocol,
        srcAddress = ipv6Address(packet, 8),
        dstAddress = ipv6Address(packet, 24),
        srcPort = srcPort,
        dstPort = dstPort,
        tcpFlags = tcpFlags,
        hopLimit = packet[7].toInt() and 0xFF,
        extensionHeaders = extensions,
        icmpType = icmpType(packet, read, payloadStart, protocol)
    )

    private data class TransportPorts(
        val src: Int?,
        val dst: Int?,
        val tcpFlags: Int?,
        val headerBytes: Int
    )

    /** Portas/flags quando o cabeçalho de transporte realmente existe; null quando ele não cabe. */
    private fun transportPorts(
        packet: ByteArray,
        read: Int,
        offset: Int,
        protocol: TransportKind
    ): TransportPorts? {
        if (protocol != TransportKind.TCP && protocol != TransportKind.UDP) {
            return TransportPorts(null, null, null, 0)
        }
        if (offset + 8 > read) return null
        val src = TunnelPackets.u16(packet, offset)
        val dst = TunnelPackets.u16(packet, offset + 2)
        if (protocol == TransportKind.UDP) return TransportPorts(src, dst, null, 8)
        if (offset + 20 > read) return null
        val flags = packet[offset + 13].toInt() and 0xFF
        val tcpHeaderLength = ((packet[offset + 12].toInt() and 0xF0) shr 4) * 4
        return TransportPorts(src, dst, flags, tcpHeaderLength.coerceAtLeast(20))
    }

    /** Primeiro byte do cabeçalho ICMP/ICMPv6 (tipo) — metadado, não payload. */
    private fun icmpType(packet: ByteArray, read: Int, offset: Int, protocol: TransportKind): Int? =
        if (protocol.isIcmp && offset < read) packet[offset].toInt() and 0xFF else null

    private fun transportPayload(declaredTotal: Int, headerLength: Int, transportHeaderBytes: Int): Int {
        val afterIp = declaredTotal - headerLength
        return (afterIp - transportHeaderBytes).coerceAtLeast(0)
    }

    private fun fault(packet: ByteArray, read: Int, fault: PacketFault): PacketParse.Fault =
        PacketParse.Fault(
            rawLength = read,
            firstNibble = if (read > 0) (packet[0].toInt() and 0xF0) shr 4 else -1,
            fault = fault,
            hexPreview = hexPreview(packet, read)
        )

    /** Prévia hexadecimal LIMITADA ([HEX_PREVIEW_BYTES]) — só é chamada para pacotes inválidos. */
    fun hexPreview(packet: ByteArray, length: Int, maxBytes: Int = HEX_PREVIEW_BYTES): String {
        val count = minOf(length, packet.size, maxBytes).coerceAtLeast(0)
        if (count == 0) return "(vazio)"
        val text = StringBuilder(count * 3)
        for (index in 0 until count) {
            if (index > 0) text.append(' ')
            val value = packet[index].toInt() and 0xFF
            text.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return text.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Endereço IPv6 em forma comprimida (RFC 5952 simplificado); IPv4-mapeado vira `::ffff:a.b.c.d`. */
    fun ipv6Address(packet: ByteArray, offset: Int): String {
        val groups = IntArray(8) { index ->
            ((packet[offset + index * 2].toInt() and 0xFF) shl 8) or
                (packet[offset + index * 2 + 1].toInt() and 0xFF)
        }
        val mapped = groups[0] == 0 && groups[1] == 0 && groups[2] == 0 &&
            groups[3] == 0 && groups[4] == 0 && groups[5] == 0xFFFF
        if (mapped) {
            return "::ffff:${groups[6] shr 8}.${groups[6] and 0xFF}.${groups[7] shr 8}.${groups[7] and 0xFF}"
        }
        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < 8) {
            if (groups[index] != 0) {
                index++
                continue
            }
            var end = index
            while (end < 8 && groups[end] == 0) end++
            if (end - index > bestLength) {
                bestStart = index
                bestLength = end - index
            }
            index = end
        }
        if (bestLength < 2) bestStart = -1
        val text = StringBuilder()
        index = 0
        while (index < 8) {
            if (bestStart >= 0 && index == bestStart) {
                text.append("::")
                index += bestLength
                continue
            }
            if (index > 0 && text.isNotEmpty() && !text.endsWith(":")) text.append(':')
            text.append(Integer.toHexString(groups[index]))
            index++
        }
        return if (text.isEmpty()) "::" else text.toString()
    }
}
