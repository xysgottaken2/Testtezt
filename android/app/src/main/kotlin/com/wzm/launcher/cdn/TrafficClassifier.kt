package com.wzm.launcher.cdn

/**
 * Classificação de tráfego do TUN (M3.6) — pedido: "distinga IPv4, IPv6, TCP, UDP, DNS, DoT e DoH
 * **sem** registrar payloads".
 *
 * Aqui só se olha **metadados de cabeçalho** (versão, protocolo, tipo ICMP, endereços, portas).
 *
 * Regra que este módulo existe para tornar explícita: **multicast/link-local ICMPv6 é descoberta
 * local do vizinho**, não tráfego de jogo; endereço unicast é tráfego de verdade. A classificação é
 * factual ("este perfil é vizinhança"), não uma afirmação de autoria — quem decide autoria é o UID.
 */
object TrafficClassifier {

    /** Escopos possíveis de um endereço IPv6 (o que o parser já formatou). */
    object Scope {
        const val LOOPBACK = "loopback"
        const val LINK_LOCAL = "link-local"
        const val MULTICAST = "multicast"
        const val ULA = "ULA"
        const val MAPEADO_IPV4 = "IPv4-mapeado"
        const val NAO_ESPECIFICADO = "não-especificado"
        const val GLOBAL = "global"
    }

    /** Tipo de ICMPv6 em nome legível (RFC 4443 / 4861 / 4862 / 3810). */
    fun icmpv6TypeName(type: Int?): String = when (type) {
        1 -> "destino-inalcancavel"
        2 -> "pacote-grande-demais"
        3 -> "tempo-excedido"
        4 -> "problema-de-parametro"
        128 -> "echo-request"
        129 -> "echo-reply"
        133 -> "router-solicitation"
        134 -> "router-advertisement"
        135 -> "neighbor-solicitation"
        136 -> "neighbor-advertisement"
        137 -> "redirect"
        130, 131, 132, 143 -> "mld-multicast-listener"
        null -> "tipo-desconhecido"
        else -> "outro($type)"
    }

    /** Tipo de ICMPv4 em nome legível (RFC 792) — usado quando o pacote é IPv4. */
    fun icmpv4TypeName(type: Int?): String = when (type) {
        0 -> "echo-reply"
        3 -> "destino-inalcancavel"
        8 -> "echo-request"
        11 -> "tempo-excedido"
        null -> "tipo-desconhecido"
        else -> "outro($type)"
    }

    /** Escopo do endereço IPv6 já formatado (a forma comprimida do parser é suficiente). */
    fun scopeOf(address: String): String {
        val lower = address.lowercase()
        if (lower == "::1") return Scope.LOOPBACK
        if (lower == "::") return Scope.NAO_ESPECIFICADO
        if (lower.startsWith("::ffff:")) return Scope.MAPEADO_IPV4
        if (lower.startsWith("ff")) return Scope.MULTICAST
        val firstGroup = lower.substringBefore(':')
        val value = firstGroup.toIntOrNull(16)
        if (value != null) {
            if (value in 0xFE80..0xFEBF) return Scope.LINK_LOCAL
            if (value in 0xFC00..0xFDFF) return Scope.ULA
        }
        return Scope.GLOBAL
    }

    /**
     * Perfil de um pacote IPv6 — o que aparece no log de descarte e no resumo.
     * [localDiscovery] = vizinhança/multicast típica de descoberta local do Android.
     */
    data class Ipv6Profile(
        val transport: String,
        val detail: String,
        val srcScope: String,
        val dstScope: String,
        val multicast: Boolean,
        val localDiscovery: Boolean
    ) {
        fun label(): String = buildString {
            append(transport)
            append(' ').append(detail)
            append(" [").append(srcScope).append(" → ").append(dstScope).append(']')
            if (localDiscovery) append(" (descoberta local)")
        }
    }

    fun ipv6Profile(header: PacketHeader): Ipv6Profile {
        val srcScope = scopeOf(header.srcAddress)
        val dstScope = scopeOf(header.dstAddress)
        val multicast = dstScope == Scope.MULTICAST
        val transport = header.protocol.label
        val detail = when {
            header.protocol == TransportKind.ICMPV6 ->
                "tipo ${header.icmpType ?: -1} (${icmpv6TypeName(header.icmpType)})"
            header.srcPort != null || header.dstPort != null ->
                "portas ${header.srcPort ?: -1}->${header.dstPort ?: -1}"
            else -> "sem portas"
        }
        val discoveryType = header.protocol == TransportKind.ICMPV6 &&
            header.icmpType in setOf(133, 134, 135, 136, 137, 130, 131, 132, 143)
        val localDiscovery = multicast && (discoveryType || header.protocol == TransportKind.ICMPV6)
        return Ipv6Profile(
            transport = transport,
            detail = detail,
            srcScope = srcScope,
            dstScope = dstScope,
            multicast = multicast,
            localDiscovery = localDiscovery
        )
    }

    /**
     * Classificação de um pacote IPv6 descartado, em uma de três categorias observáveis:
     * `DESCOBERTA_LOCAL`, `MULTICAST_OUTRO` ou `UNICAST`.
     */
    enum class Ipv6Category(val label: String) {
        DESCOBERTA_LOCAL("descoberta-local"),
        MULTICAST_OUTRO("multicast-outro"),
        UNICAST("unicast")
    }

    fun ipv6Category(header: PacketHeader): Ipv6Category {
        val profile = ipv6Profile(header)
        return when {
            profile.localDiscovery -> Ipv6Category.DESCOBERTA_LOCAL
            profile.multicast -> Ipv6Category.MULTICAST_OUTRO
            else -> Ipv6Category.UNICAST
        }
    }

    /**
     * Classe de destino em uma linha (usada nas observações e no log de descarte):
     * separa DNS, DoT, QUIC/DoH, HTTPS e "outras portas" **sem** olhar conteúdo.
     */
    fun destinationClass(header: PacketHeader): String = when {
        header.isDnsPort -> "DNS:53"
        header.isDotPort -> "DoT:853"
        header.dstPort == 443 && header.isUdp -> "QUIC/DoH:443-udp"
        header.dstPort == 443 -> "HTTPS:443-tcp"
        header.dstPort == 80 -> "HTTP:80"
        else -> "outra:${header.dstPort ?: -1}"
    }
}
