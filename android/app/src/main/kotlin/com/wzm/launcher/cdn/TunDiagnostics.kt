package com.wzm.launcher.cdn

/**
 * Diagnóstico do caminho WZM → VPN per-app → TUN → DNS/roteamento (M3.3/M3.4).
 *
 * Kotlin puro (sem dependências Android) para rodar em JVM e produzir sempre o mesmo formato de log.
 *
 * Princípios desta etapa:
 *  * **nada de motivo genérico**: cada descarte tem código exato ([TunDiscardReason] ou [PacketFault]);
 *  * **IPv6 não é pacote inválido**: é classificado como IPv6 e descartado por política explícita;
 *  * **evidência, não opinião**: cada hipótese recebe VERIFIED/PROBABLE/HYPOTHESIS/UNKNOWN
 *    ([HypothesisBoard]) e nenhuma linha afirma causa sem prova;
 *  * **nada de payload**: só metadados de cabeçalho; hexadecimal limitado apenas para pacote inválido.
 */

/** Ação que o serviço toma para um pacote reconhecido. */
enum class TunAction { RESPOSTA_DNS, BOUNCE, DESCARTE }

/** Motivos de descarte **por política** (pacote reconhecido, mas sem atendimento local). */
enum class TunDiscardReason(val code: String, val hint: String) {
    IPV6_SEM_ATENDIMENTO(
        "IPV6_SEM_ATENDIMENTO",
        "o túnel tem apenas endereço IPv4 (10.111.222.1); IPv6 válido é registrado como IPv6 e não atendido"
    ),
    PROTO_NAO_SUPORTADO(
        "PROTO_NAO_SUPORTADO",
        "o túnel só trata TCP e UDP-DNS; ICMP e outros protocolos não têm resposta local"
    ),
    UDP_PORTA_NAO_DNS(
        "UDP_PORTA_NAO_DNS",
        "UDP só é atendido na porta 53 (DNS)"
    ),
    TCP_SEM_ATENDIMENTO(
        "TCP_SEM_ATENDIMENTO",
        "destino sem listener local: respondemos RST em vez de fingir uma resposta"
    );

    fun line(origin: String, extra: String = ""): String {
        val suffix = if (extra.isEmpty()) "" else " — $extra"
        return "pacote descartado: motivo=$code ($origin) — $hint$suffix"
    }
}

/** Decisão tomada para um pacote válido — o serviço só executa o que está aqui. */
data class TunDecision(
    val action: TunAction,
    val reason: TunDiscardReason? = null,
    /** Observações adicionais (QUIC/DoH/DoT/porta 443, etc.) — informativas, nunca payload. */
    val note: String = ""
)

/**
 * Política de atendimento do túnel. Pura e testável: recebe o cabeçalho parseado e decide.
 * **Nenhuma regra de TLS/trust/pinning aqui** — esta etapa é só caminho TCP/IP e DNS.
 */
object TunPolicy {

    fun decide(header: PacketHeader): TunDecision {
        if (header.isIpv6) {
            return TunDecision(TunAction.DESCARTE, TunDiscardReason.IPV6_SEM_ATENDIMENTO, ipv6Note(header))
        }
        if (header.isIcmp) {
            return TunDecision(
                TunAction.DESCARTE,
                TunDiscardReason.PROTO_NAO_SUPORTADO,
                "${header.protocol.label} de ${header.srcAddress} -> ${header.dstAddress}"
            )
        }
        if (header.isUdp) {
            if (!header.isDnsPort) {
                return TunDecision(
                    TunAction.DESCARTE,
                    TunDiscardReason.UDP_PORTA_NAO_DNS,
                    udpNote(header)
                )
            }
            return TunDecision(TunAction.RESPOSTA_DNS)
        }
        if (header.isTcp) {
            if (header.isCdnTargetV4 || header.dstAddress == CdnRouterConfig.LOOPBACK_ADDRESS) {
                return TunDecision(TunAction.BOUNCE)
            }
            return TunDecision(
                TunAction.DESCARTE,
                TunDiscardReason.TCP_SEM_ATENDIMENTO,
                tcpNote(header)
            )
        }
        return TunDecision(
            TunAction.DESCARTE,
            TunDiscardReason.PROTO_NAO_SUPORTADO,
            "protocolo ${header.protocolCode} (${header.protocol.label}) sem tratamento local"
        )
    }

    private fun ipv6Note(header: PacketHeader): String = buildString {
        val profile = TrafficClassifier.ipv6Profile(header)
        val category = TrafficClassifier.ipv6Category(header)
        append("IPv6 válido: next-header=${header.protocolCode} (${header.protocol.label})")
        append(" de ").append(header.srcAddress)
        append(" -> ").append(header.dstAddress)
        if (header.srcPort != null || header.dstPort != null) {
            append(" portas ").append(header.srcPort ?: -1).append("->").append(header.dstPort ?: -1)
        }
        if (header.extensionHeaders.isNotEmpty()) {
            append(" extensões=").append(header.extensionHeaders.joinToString(","))
        }
        append(" • perfil=").append(profile.label())
        append(" • categoria=").append(category.label)
        append(" • local=").append(TrafficClassifier.destinationClass(header))
        if (header.isDnsPort) {
            append(" (porta 53 em IPv6: sem atendimento local — o DNS virtual é IPv4)")
        } else if (header.isDotPort) {
            append(" (porta 853 = DoT candidato: apenas identificado, nunca descriptografado)")
        }
    }

    private fun udpNote(header: PacketHeader): String = when {
        header.dstPort == 443 ->
            "UDP :443 = QUIC/DoH candidato (HYPOTHESIS) — destino ${header.dstAddress} (${header.destinationClass})"
        header.isDotPort ->
            "UDP :853 = DoT candidato (HYPOTHESIS) — destino ${header.dstAddress}"
        else -> "destino ${header.dstAddress}:${header.dstPort} (${header.destinationClass})"
    }

    private fun tcpNote(header: PacketHeader): String = when {
        header.dstPort == 443 ->
            "HTTPS para IP externo (${header.destinationClass}): registrado só destino/porta/protocolo, sem payload"
        header.isDotPort ->
            "TCP :853 = DoT candidato (HYPOTHESIS): apenas identificado, nunca descriptografado"
        else -> "destino ${header.dstAddress}:${header.dstPort} (${header.destinationClass})"
    }

    /** Observações de porta/flags contadas separadamente (visibilidade do caminho TCP/DNS). */
    fun observations(header: PacketHeader): List<TunObservation> {
        val list = mutableListOf<TunObservation>()
        if (header.isTcp) {
            if (header.isSyn) {
                list += TunObservation.TCP_SYN
                if (header.isCdnTargetV4 && header.dstPort == CdnRouterConfig.LOCAL_HTTPS_PORT) {
                    list += TunObservation.TCP_SYN_PARA_ALVO_443
                } else {
                    list += TunObservation.TCP_SYN_OUTRO_DESTINO
                }
            }
            if (header.isDotPort) list += TunObservation.FLUXO_DOT
            if (header.isHttpsPort && !header.isCdnTarget) list += TunObservation.TCP_443_EXTERNO
        }
        if (header.isUdp) {
            if (header.isDnsPort) {
                list += TunObservation.UDP_DNS_53
                if (header.dstAddress == CdnRouterConfig.VPN_DNS) list += TunObservation.UDP_DNS_NO_DNS_VIRTUAL
            }
            if (header.isDotPort) list += TunObservation.FLUXO_DOT
            if (header.isHttpsPort) list += TunObservation.UDP_443_QUIC_DOH
        }
        // M3.6: ICMPv4 e a categoria do IPv6 descartado (descoberta local × multicast × unicast).
        if (header.isIpv4 && header.isIcmp) list += TunObservation.ICMPV4
        if (header.isIpv6) {
            list += when (TrafficClassifier.ipv6Category(header)) {
                TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL -> TunObservation.IPV6_DESCOBERTA_LOCAL
                TrafficClassifier.Ipv6Category.MULTICAST_OUTRO -> TunObservation.IPV6_MULTICAST_OUTRO
                TrafficClassifier.Ipv6Category.UNICAST -> TunObservation.IPV6_UNICAST
            }
        }
        return list
    }
}

/** Observações contáveis do caminho (cada uma tem contador próprio em [RequestCounters]). */
enum class TunObservation {
    TCP_SYN, TCP_SYN_PARA_ALVO_443, TCP_SYN_OUTRO_DESTINO, UDP_DNS_53, UDP_DNS_NO_DNS_VIRTUAL,
    FLUXO_DOT, TCP_443_EXTERNO, UDP_443_QUIC_DOH,
    // M3.6: classificação do IPv6 descartado (nunca "pacote inválido") e ICMPv4.
    IPV6_DESCOBERTA_LOCAL, IPV6_MULTICAST_OUTRO, IPV6_UNICAST, ICMPV4
}

/** Nível de evidência — mesma disciplina do projeto (nunca "achismo" como fato). */
enum class Evidence { VERIFIED, PROBABLE, HYPOTHESIS, UNKNOWN }

data class EvidenceClaim(val id: String, val level: Evidence, val detail: String) {
    fun line(): String = "evidência $id: ${level.name} — $detail"
}

/** Fatos da sessão usados para montar o quadro de evidências (nada é inferido sem eles). */
data class DiagFacts(
    val perAppApplied: Boolean,
    val perAppError: String?,
    val targetPackage: String,
    val targetUid: Int?,
    val privateDnsReadable: Boolean,
    val privateDnsMode: String?,
    val privateDnsSpecifier: String?,
    val tunnelAddressAssigned: Boolean,
    val tunnelListenerBound: Boolean,
    val routerPhase: String,
    // ---- M3.6: o que faltava para separar "app sem rede" de "tráfego fora do túnel" ----
    /** `true` quando TrafficStats respondeu para o UID do alvo nesta sessão. */
    val uidTrafficAvailable: Boolean = false,
    /** Bytes somados (tx+rx) que o UID do alvo movimentou desde o início da sessão (null = não sei). */
    val uidTrafficBytesSinceStart: Long? = null,
    /** `true` = houve crescimento; `false` = nenhum; `null` = não foi possível afirmar. */
    val uidTrafficGrew: Boolean? = null,
    /** Perfis IPv6 distintos descartados nesta sessão (ex.: "ICMPv6 neighbor-solicitation ..."). */
    val ipv6Profiles: List<String> = emptyList(),
    /** Processos declarados no manifesto do app alvo (fato estático). */
    val targetDeclaredProcesses: List<String> = emptyList(),
    /** `true` quando a contabilidade do UID do alvo cresceu mas nada apareceu no túnel. */
    val uidTrafficOutsideTunnel: Boolean = false
)

/**
 * Quadro de evidências: transforma contadores em afirmações **classificadas**.
 * Nenhuma linha aqui diz "o Private DNS está causando"; no máximo PROBABLE/HYPOTHESIS com o porquê.
 */
object HypothesisBoard {

    fun claims(counters: RequestCounters, facts: DiagFacts): List<EvidenceClaim> = listOf(
        EvidenceClaim(
            "app_alvo_na_vpn_per_app",
            if (facts.perAppApplied) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (facts.perAppApplied) {
                "addAllowedApplication(${facts.targetPackage}) aceito pelo sistema (uid=${facts.targetUid ?: "?"})"
            } else {
                "per-app NÃO aplicado (${facts.perAppError ?: "motivo desconhecido"})"
            }
        ),
        EvidenceClaim(
            "trafego_do_app_alvo_no_tun",
            when {
                counters.tunUidVerifiedFlows > 0 -> Evidence.VERIFIED
                counters.tunPacketsTotal > 0 -> Evidence.PROBABLE
                else -> Evidence.UNKNOWN
            },
            when {
                counters.tunUidVerifiedFlows > 0 ->
                    "UID do app alvo confirmado em ${counters.tunUidVerifiedFlows} fluxo(s) observado(s) no TUN"
                counters.tunPacketsTotal > 0 ->
                    "há ${counters.tunPacketsTotal} pacote(s) no TUN, mas nenhum fluxo com UID confirmado " +
                        "(o túnel só permite ${facts.targetPackage}, porém isso não é prova de autoria)"
                else -> "nenhum pacote recebido no TUN até agora"
            }
        ),
        EvidenceClaim(
            "dns_do_app_no_dns_virtual",
            if (counters.tunUdpDnsNoVirtualDns > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tunUdpDnsNoVirtualDns > 0) {
                "${counters.tunUdpDnsNoVirtualDns} consulta(s) chegaram em ${CdnRouterConfig.VPN_DNS}:53"
            } else {
                "nenhuma consulta chegou em ${CdnRouterConfig.VPN_DNS}:53 " +
                    "(consultas UDP:53 vistas: ${counters.tunUdpDns53})"
            }
        ),
        EvidenceClaim(
            "consulta_dns_de_host_cdni",
            if (counters.dnsIntercepted > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.dnsIntercepted > 0) {
                "${counters.dnsIntercepted} consulta(s) de host CDNI interceptada(s) -> ${CdnRouterConfig.REDIRECT_TO}"
            } else {
                "nenhuma consulta de host CDNI observada até agora " +
                    "(consultas DNS no túnel: ${counters.dnsQueries}, encaminhadas: ${counters.dnsForwarded})"
            }
        ),
        EvidenceClaim(
            "pacotes_ipv6_no_tun",
            if (counters.tunIpv6Packets > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tunIpv6Packets > 0) {
                "${counters.tunIpv6Packets} pacote(s) IPv6 válido(s) no TUN " +
                    "(${counters.tunIpv6ToCdnTarget} para ${CdnRouterConfig.CDN_TARGET_V6}) — não atendidos por política"
            } else {
                "nenhum pacote IPv6 observado"
            }
        ),
        EvidenceClaim(
            "tcp_para_o_alvo_cdni_443",
            if (counters.tunTcpSynToRedirect > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tunTcpSynToRedirect > 0) {
                "${counters.tunTcpSynToRedirect} SYN para ${CdnRouterConfig.VPN_ADDRESS}:" +
                    "${CdnRouterConfig.LOCAL_HTTPS_PORT} observado(s) no TUN — único caminho que promove " +
                    "esta afirmação (conexões em ${CdnRouterConfig.LOOPBACK_ADDRESS} NÃO promovem)"
            } else {
                "nenhum SYN para ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} " +
                    "(SYN no total: ${counters.tunTcpSyn}, para outros destinos: ${counters.tunTcpSynOther}); " +
                    "conexões de loopback (${counters.tcpConnectionsLoopback}) são diagnóstico e NÃO promovem"
            }
        ),
        EvidenceClaim(
            "listener_no_endereco_do_tunel",
            if (facts.tunnelListenerBound) Evidence.VERIFIED else Evidence.PROBABLE,
            if (facts.tunnelListenerBound) {
                "listener ativo em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT}"
            } else {
                "AUSENTE: bind no endereço do túnel não subiu (endereço atribuído=${facts.tunnelAddressAssigned}); " +
                    "só o caminho ${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} " +
                    "atende — e ele é DIAGNÓSTICO (não conta como evidência de tráfego do WZM)"
            }
        ),
        EvidenceClaim(
            "conexoes_no_listener_do_tunel",
            if (counters.tcpConnectionsTunel > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tcpConnectionsTunel > 0) {
                "${counters.tcpConnectionsTunel} conexão(ões) aceita(s) em ${CdnRouterConfig.VPN_ADDRESS}:" +
                    "${CdnRouterConfig.LOCAL_HTTPS_PORT} — caminho que veio pelo DNS do túnel " +
                    "(com UID quando resolvido; sem UID a autoria continua não provada); conexão feita " +
                    "pelo PRÓPRIO launcher no teste sintético prova o CAMINHO, NÃO o WZM"
            } else {
                "nenhuma conexão aceita em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} até agora"
            }
        ),
        EvidenceClaim(
            "conexoes_de_loopback_sao_diagnostico",
            if (counters.tcpConnectionsLoopback > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tcpConnectionsLoopback > 0) {
                "${counters.tcpConnectionsLoopback} conexão(ões) em " +
                    "${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} " +
                    "(antes do WZM iniciado=${counters.loopbackAntesDoWzm}, depois=${counters.loopbackDepoisDoWzm}) — " +
                    "DIAGNÓSTICO SECUNDÁRIO: NÃO são evidência de tráfego do WZM, nem a favor nem contra"
            } else {
                "nenhuma conexão em ${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} nesta sessão"
            }
        ),
        EvidenceClaim(
            "tls_no_listener_do_tunel",
            if (counters.tlsOkTunel + counters.tlsFailedTunel > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tlsOkTunel + counters.tlsFailedTunel > 0) {
                "handshakes em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT}: " +
                    "${counters.tlsOkTunel} aceito(s) / ${counters.tlsFailedTunel} recusado(s) pelo cliente"
            } else {
                "nenhum handshake no listener do túnel (loopback: ${counters.tlsOkLoopback} ok / " +
                    "${counters.tlsFailedLoopback} falha(s) — diagnóstico, fora do critério)"
            }
        ),
        EvidenceClaim(
            "dns_privado_configurado_no_aparelho",
            if (!facts.privateDnsReadable) Evidence.UNKNOWN else Evidence.VERIFIED,
            if (!facts.privateDnsReadable) {
                "não foi possível ler Settings.Global private_dns_mode neste app"
            } else {
                "private_dns_mode=${facts.privateDnsMode ?: "?"}" +
                    (facts.privateDnsSpecifier?.let { ", specifier=$it" } ?: "")
            }
        ),
        EvidenceClaim(
            "efeito_do_dns_privado_sobre_o_wzm",
            Evidence.HYPOTHESIS,
            "o DoT do sistema só explicaria consultas CDNI ausentes no túnel se o app não usar o DNS da VPN; " +
                "isso NÃO está confirmado — depende das linhas [DNS] e do quadro acima"
        ),
        EvidenceClaim(
            "fluxo_dot_no_tun",
            if (counters.tunDotFlows > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tunDotFlows > 0) {
                "${counters.tunDotFlows} fluxo(s) na porta ${CdnRouterConfig.DOT_PORT} (DoT candidato: só identificado)"
            } else {
                "nenhum fluxo na porta ${CdnRouterConfig.DOT_PORT}"
            }
        ),
        EvidenceClaim(
            "fluxo_quic_doh_no_tun",
            if (counters.tunDohCandidates > 0) Evidence.VERIFIED else Evidence.UNKNOWN,
            if (counters.tunDohCandidates > 0) {
                "${counters.tunDohCandidates} fluxo(s) UDP :443 (QUIC/DoH candidato: só identificado)"
            } else {
                "nenhum fluxo UDP :443"
            }
        ),
        EvidenceClaim(
            "trafego_do_app_alvo_fora_do_tunel",
            when {
                facts.uidTrafficGrew == true && counters.tunUidVerifiedFlows == 0 &&
                    counters.tunPacketsTotal == 0 -> Evidence.PROBABLE
                facts.uidTrafficGrew == true && counters.tunUidVerifiedFlows == 0 -> Evidence.PROBABLE
                facts.uidTrafficGrew == false && counters.tunPacketsTotal == 0 -> Evidence.VERIFIED
                else -> Evidence.UNKNOWN
            },
            when {
                facts.uidTrafficGrew == true && counters.tunUidVerifiedFlows == 0 ->
                    "a contabilidade do UID do alvo CRESCEU ${facts.uidTrafficBytesSinceStart?.let { "(+$it B)" } ?: ""} " +
                        "enquanto nada no TUN foi atribuído a ele — consistente com tráfego do app FORA do túnel " +
                        "(PROBABLE; a contabilidade é por UID, não por processo, e a VPN per-app não é prova de captura)"
                facts.uidTrafficGrew == false && counters.tunPacketsTotal == 0 ->
                    "contabilidade do UID do alvo NÃO cresceu e o TUN está vazio: nesta janela o app não fez rede " +
                        "(por UID; processos auxiliares do mesmo pacote entram nessa conta)"
                facts.uidTrafficGrew == true ->
                    "houve tráfego no UID do alvo e há fluxo verificado no TUN"
                !facts.uidTrafficAvailable ->
                    "sem contabilidade por UID (TrafficStats indisponível) — não é possível separar \"app sem rede\" " +
                        "de \"tráfego fora do túnel\""
                else -> "contabilidade por UID ainda sem variação legível"
            }
        ),
        EvidenceClaim(
            "ipv6_descartado_e_descoberta_local",
            when {
                counters.tunIpv6DescobertaLocal > 0 && counters.tunIpv6Unicast == 0 -> Evidence.PROBABLE
                counters.tunIpv6Unicast > 0 -> Evidence.VERIFIED
                else -> Evidence.UNKNOWN
            },
            when {
                counters.tunIpv6Unicast > 0 ->
                    "${counters.tunIpv6Unicast} pacote(s) IPv6 em endereço UNICAST descartado(s) — não é apenas " +
                        "descoberta local; antes-do-WZM=${counters.tunIpv6AntesDoWzm}, depois=${counters.tunIpv6DepoisDoWzm}"
                counters.tunIpv6DescobertaLocal > 0 ->
                    "todo o IPv6 descartado até agora (${counters.tunIpv6DescobertaLocal} pacote(s)) é descoberta local " +
                        "(multicast/link-local: vizinhança/MLD) — compatível com o sistema, NÃO com tráfego de jogo " +
                        "; unicast=${counters.tunIpv6Unicast}, multicast-outro=${counters.tunIpv6MulticastOutro}"
                counters.tunIpv6Packets > 0 ->
                    "há IPv6 no TUN mas sem classificação registrada ainda"
                else -> "nenhum pacote IPv6 observado no TUN"
            }
        ),
        EvidenceClaim(
            "dns_observado_para_os_destinos",
            when {
                counters.dnsRespostasRegistradas == 0 -> Evidence.UNKNOWN
                counters.tunFluxosDestinoResolvido > 0 -> Evidence.PROBABLE
                else -> Evidence.PROBABLE
            },
            when {
                counters.dnsRespostasRegistradas == 0 ->
                    "nenhuma resposta DNS passou pelo túnel — impossível casar destinos com nomes"
                counters.tunFluxosDestinoResolvido > 0 ->
                    "${counters.tunFluxosDestinoResolvido} fluxo(s) foram para endereço que consta de resposta DNS " +
                        "observada (${counters.dnsRespostasRegistradas} resposta(s) guardada(s)) — a resolução passou pelo túnel"
                else ->
                    "${counters.dnsRespostasRegistradas} resposta(s) DNS guardada(s) e nenhum fluxo casou com elas: " +
                        "os destinos usados não vieram do DNS do túnel (DoH/DoT/cache do sistema — HYPOTHESIS, não causa)"
            }
        ),
        EvidenceClaim(
            "processos_do_app_alvo",
            if (facts.targetDeclaredProcesses.isEmpty()) Evidence.UNKNOWN else Evidence.VERIFIED,
            if (facts.targetDeclaredProcesses.isEmpty()) {
                "não foi possível listar processos do app alvo (API restringe processos de outro UID; " +
                    "lista vazia não prova execução)"
            } else {
                "processos declarados no manifesto: " + facts.targetDeclaredProcesses.joinToString(", ") +
                    " — é fato estático (não prova execução); no device, cada um teria o MESMO UID ${facts.targetUid ?: "?"}"
            }
        ),
        EvidenceClaim(
            "wzm_resolve_cdni_por_mecanismo_proprio",
            when {
                counters.tunDohCandidates > 0 || counters.tunTcp443Externo > 0 -> Evidence.PROBABLE
                else -> Evidence.UNKNOWN
            },
            when {
                counters.tunDohCandidates > 0 || counters.tunTcp443Externo > 0 ->
                    "há tráfego :443 para IP externo sem consulta DNS correspondente no túnel " +
                        "(compatível com DoH/cache próprio; NÃO confirmado)"
                else -> "sem evidência no túnel (nem a favor, nem contra)"
            }
        )
    )

    fun lines(counters: RequestCounters, facts: DiagFacts): List<String> =
        claims(counters, facts).map { it.line() }
}

/** Visão de um pacote lido do TUN — usada só para log/sumário. */
data class TunPacketView(
    val protocol: Int,
    val srcAddress: String,
    val srcPort: Int,
    val dstAddress: String,
    val dstPort: Int,
    val flags: Int,
    val totalLength: Int,
    val isLocalDest: Boolean,
    val isRedirectDest: Boolean,
    val isLoopbackDest: Boolean,
    val isDnsPort: Boolean,
    val isHttpsPort: Boolean
) {
    fun brief(): String {
        val protocolName = when (protocol) {
            TunnelPackets.PROTO_TCP -> "TCP"
            TunnelPackets.PROTO_UDP -> "UDP"
            TunnelPackets.PROTO_ICMP -> "ICMP"
            else -> "proto$protocol"
        }
        return "$protocolName $srcAddress:$srcPort -> $dstAddress:$dstPort ($totalLength B)"
    }

    fun flagNames(): String {
        val names = mutableListOf<String>()
        if (flags and TunnelPackets.FLAG_SYN != 0) names += "SYN"
        if (flags and TunnelPackets.FLAG_ACK != 0) names += "ACK"
        if (flags and TunnelPackets.FLAG_FIN != 0) names += "FIN"
        if (flags and TunnelPackets.FLAG_RST != 0) names += "RST"
        if (flags and TunnelPackets.FLAG_PSH != 0) names += "PSH"
        return if (names.isEmpty()) "-" else names.joinToString("+")
    }
}

/**
 * Classificação estável de falhas de bind do listener HTTPS.
 * Puro: recebe o nome da exceção e a mensagem (testável em JVM).
 */
enum class ListenerFailure(val code: String, val hint: String) {
    ENDERECO_INDISPONIVEL(
        "ENDERECO_INDISPONIVEL",
        "o endereço não está atribuído à interface do túnel no momento do bind (EADDRNOTAVAIL); " +
            "nesse caso pacotes devolvidos pelo TUN para esse endereço ficam sem listener"
    ),
    PORTA_EM_USO(
        "PORTA_EM_USO",
        "já existe um socket escutando nessa porta/endereço (EADDRINUSE)"
    ),
    PORTA_NEGADA(
        "PORTA_NEGADA",
        "o sistema negou a porta privilegiada (EACCES/EPERM)"
    ),
    DESCONHECIDO(
        "DESCONHECIDO",
        "falha de bind não classificada — ver a exceção completa no log"
    )
}

object ListenerFailures {

    const val VIA_LOOPBACK = "loopback"
    const val VIA_TUNEL = "túnel"

    fun classify(exceptionName: String, message: String?): ListenerFailure {
        val text = "${exceptionName.orEmpty()} ${message.orEmpty()}".lowercase()
        return when {
            text.contains("eaddrnotavail") ||
                text.contains("cannot assign requested address") -> ListenerFailure.ENDERECO_INDISPONIVEL
            text.contains("eaddrinuse") || text.contains("address already in use") -> ListenerFailure.PORTA_EM_USO
            text.contains("eacces") || text.contains("eperm") ||
                text.contains("permission denied") -> ListenerFailure.PORTA_NEGADA
            else -> ListenerFailure.DESCONHECIDO
        }
    }

    fun via(address: String): String =
        if (address == CdnRouterConfig.LOOPBACK_ADDRESS) VIA_LOOPBACK else VIA_TUNEL
}

object TunDiagnostics {

    /**
     * Compatibilidade: visão resumida a partir do parser novo (IPv4 apenas).
     * Devolve null quando o pacote não é IPv4 válido — quem chama deve usar [IpPacketParser]
     * para saber o motivo exato.
     */
    fun view(packet: ByteArray, length: Int): TunPacketView? {
        val parsed = IpPacketParser.parse(packet, length)
        if (parsed !is PacketParse.Ok || parsed.header.version != IpVersion.IPV4) return null
        val header = parsed.header
        return TunPacketView(
            protocol = header.protocolCode,
            srcAddress = header.srcAddress,
            srcPort = header.srcPort ?: -1,
            dstAddress = header.dstAddress,
            dstPort = header.dstPort ?: -1,
            flags = header.tcpFlags ?: 0,
            totalLength = header.declaredTotalLength,
            isLocalDest = header.isCdnTargetV4 || header.dstAddress == CdnRouterConfig.LOOPBACK_ADDRESS,
            isRedirectDest = header.isCdnTargetV4,
            isLoopbackDest = header.dstAddress == CdnRouterConfig.LOOPBACK_ADDRESS,
            isDnsPort = header.isDnsPort,
            isHttpsPort = header.isHttpsPort
        )
    }

    /** Linha de descarte de um pacote inválido, com prévia hexadecimal limitada. */
    fun faultLine(fault: PacketParse.Fault): String =
        "pacote descartado: ${fault.fault.line(
            "lido=${fault.rawLength} B nibble=0x${fault.firstNibble.toString(16)} " +
                "hex[<=${IpPacketParser.HEX_PREVIEW_BYTES}]={${fault.hexPreview}}"
        )}"

    /** Resumo completo do caminho (heartbeat + fim de sessão + export). */
    fun summaryLine(counters: RequestCounters): String =
        "resumo do túnel: pacotes=${counters.tunPacketsTotal} " +
            "ipv4=${counters.tunIpv4Packets} ipv6=${counters.tunIpv6Packets} " +
            "tcp=${counters.tunTcpPackets} udp=${counters.tunUdpPackets} icmp=${counters.tunIcmpPackets} " +
            "invalidos=${counters.tunInvalidPackets} " +
            "para-alvo-ipv4=${counters.tunIpv4ToCdnTarget} para-alvo-ipv6=${counters.tunIpv6ToCdnTarget} " +
            "toRedirect=${counters.tunToRedirect} bounces=${counters.tunBounces} descartes=${counters.tunDiscards} " +
            "syn=${counters.tunTcpSyn}/alvo443=${counters.tunTcpSynToRedirect}/outros=${counters.tunTcpSynOther} " +
            "dns53=${counters.tunUdpDns53}/virtual=${counters.tunUdpDnsNoVirtualDns} " +
            "dot=${counters.tunDotFlows} quic/doh=${counters.tunDohCandidates} " +
            "dns-total=${counters.dnsQueries} dns-cdni-interceptado=${counters.dnsIntercepted} " +
            "dns-encaminhado=${counters.dnsForwarded} " +
            "tcp-conexoes=${counters.tcpConnections} tls-ok=${counters.tlsOk} tls-falha=${counters.tlsFailed} " +
            "listener-tunel=${counters.tcpConnectionsTunel} listener-loopback=${counters.tcpConnectionsLoopback} " +
            "(antes-do-wzm=${counters.loopbackAntesDoWzm} depois-do-wzm=${counters.loopbackDepoisDoWzm}) " +
            "tls-tunel=${counters.tlsOkTunel}/${counters.tlsFailedTunel} " +
            "tls-loopback=${counters.tlsOkLoopback}/${counters.tlsFailedLoopback} " +
            "ipv6-descartado=${counters.tunIpv6Packets} (descoberta-local=${counters.tunIpv6DescobertaLocal} " +
            "multicast-outro=${counters.tunIpv6MulticastOutro} unicast=${counters.tunIpv6Unicast} " +
            "antes-do-wzm=${counters.tunIpv6AntesDoWzm} depois-do-wzm=${counters.tunIpv6DepoisDoWzm}) " +
            "destino-resolvido=${counters.tunFluxosDestinoResolvido} respostas-dns=${counters.dnsRespostasRegistradas}"
}

/** Eventos que o vigia do túnel pode emitir — cada um vira uma linha no log. */
enum class TunWatchdogEvent(val code: String) {
    NENHUM_PACOTE_NO_TUN("NENHUM_PACOTE_NO_TUN"),
    NENHUMA_CONSULTA_CDNI("NENHUMA_CONSULTA_CDNI"),
    NENHUM_FLUXO_PARA_O_ALVO("NENHUM_FLUXO_PARA_O_ALVO"),
    RESUMO_PERIODICO("RESUMO_PERIODICO")
}

/**
 * Vigia de atividade do túnel: distingue "o app não usou o túnel" de "usou e não foi ao CDNI".
 * Puro (tempo entra por parâmetro) para ser testado em JVM.
 */
class TunActivityWatchdog(
    private val noPacketAfterMs: Long = 60_000,
    private val noCdnDnsAfterMs: Long = 60_000,
    private val noTargetFlowAfterMs: Long = 60_000,
    private val summaryEveryMs: Long = 60_000
) {

    private var startedAtMs = -1L
    private var lastPacketAtMs = -1L
    private var lastCdnDnsAtMs = -1L
    private var lastSummaryAtMs = -1L
    private var packets = 0
    private var cdnDns = 0
    private var targetFlows = 0
    private var warnedNoPacket = false
    private var warnedNoCdnDns = false
    private var warnedNoTargetFlow = false

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        lastSummaryAtMs = nowMs
    }

    fun onPacket(nowMs: Long) {
        packets++
        lastPacketAtMs = nowMs
    }

    fun onCdnDns(nowMs: Long) {
        cdnDns++
        lastCdnDnsAtMs = nowMs
    }

    /** Um fluxo destinado ao alvo CDNI (SYN para o endereço/porta locais) foi observado. */
    fun onTargetFlow() {
        targetFlows++
    }

    val packetCount: Int get() = packets
    val cdnDnsCount: Int get() = cdnDns
    val targetFlowCount: Int get() = targetFlows

    fun secondsSinceLastPacket(nowMs: Long): Long {
        val reference = if (lastPacketAtMs >= 0) lastPacketAtMs else startedAtMs
        if (reference < 0) return 0
        return ((nowMs - reference) / 1000).coerceAtLeast(0)
    }

    private fun secondsSinceLastCdnDns(nowMs: Long): Long {
        val reference = if (lastCdnDnsAtMs >= 0) lastCdnDnsAtMs else startedAtMs
        if (reference < 0) return 0
        return ((nowMs - reference) / 1000).coerceAtLeast(0)
    }

    fun poll(nowMs: Long): List<TunWatchdogEvent> {
        if (startedAtMs < 0) return emptyList()
        val events = mutableListOf<TunWatchdogEvent>()
        if (!warnedNoPacket && packets == 0 && nowMs - startedAtMs >= noPacketAfterMs) {
            warnedNoPacket = true
            events += TunWatchdogEvent.NENHUM_PACOTE_NO_TUN
        }
        if (!warnedNoCdnDns && cdnDns == 0 && nowMs - startedAtMs >= noCdnDnsAfterMs) {
            warnedNoCdnDns = true
            events += TunWatchdogEvent.NENHUMA_CONSULTA_CDNI
        }
        if (!warnedNoTargetFlow && targetFlows == 0 && packets > 0 && nowMs - startedAtMs >= noTargetFlowAfterMs) {
            warnedNoTargetFlow = true
            events += TunWatchdogEvent.NENHUM_FLUXO_PARA_O_ALVO
        }
        if (nowMs - lastSummaryAtMs >= summaryEveryMs) {
            lastSummaryAtMs = nowMs
            events += TunWatchdogEvent.RESUMO_PERIODICO
        }
        return events
    }

    fun messageFor(event: TunWatchdogEvent, counters: RequestCounters, nowMs: Long): String = when (event) {
        TunWatchdogEvent.NENHUM_PACOTE_NO_TUN ->
            "nenhum pacote no TUN nos primeiros ${secondsSinceLastPacket(nowMs)} s — " +
                "o app alvo não gerou tráfego dentro do túnel nesta sessão; " +
                "checar (sem afirmar causa): app em execução, per-app aplicado, rotas da VPN, tela de rede do jogo"
        TunWatchdogEvent.NENHUMA_CONSULTA_CDNI ->
            "nenhuma consulta DNS de host CDNI em ${secondsSinceLastCdnDns(nowMs)} s " +
                "(DNS no túnel: ${counters.dnsQueries}, encaminhadas: ${counters.dnsForwarded}, " +
                "no DNS virtual ${CdnRouterConfig.VPN_DNS}: ${counters.tunUdpDnsNoVirtualDns}) — " +
                "sem consulta CDNI não há como devolver ${CdnRouterConfig.REDIRECT_TO}"
        TunWatchdogEvent.NENHUM_FLUXO_PARA_O_ALVO ->
            "houve ${counters.tunPacketsTotal} pacote(s) no TUN, mas nenhum para " +
                "${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} " +
                "(ipv6=${counters.tunIpv6Packets}, invalidos=${counters.tunInvalidPackets}, " +
                "tcp=${counters.tunTcpPackets}, udp-dns=${counters.tunUdpDns53}) — " +
                "o app pode estar usando outro caminho (DoH/DoT/IPv6/cache): ver quadro de evidências"
        TunWatchdogEvent.RESUMO_PERIODICO -> TunDiagnostics.summaryLine(counters)
    }
}

/**
 * Fatos da sessão coletados no início do túnel (Android) — formatados aqui para o log
 * ficar idêntico em qualquer device e testável em JVM.
 */
data class SessionFacts(
    val sessionNumber: Int,
    val previousSessions: Int,
    val launcherVersion: String,
    val launcherTargetSdk: Int,
    val targetPackage: String,
    val targetInstalled: Boolean,
    val targetVersionName: String?,
    val targetUid: Int?,
    val perAppApplied: Boolean,
    val perAppError: String?,
    val extra: List<String> = emptyList()
)

object SessionReport {

    fun lines(facts: SessionFacts): List<String> = buildList {
        add(
            "sessão=#${facts.sessionNumber} (execuções anteriores registradas=${facts.previousSessions}) " +
                "launcher=${facts.launcherVersion} targetSdk=${facts.launcherTargetSdk}"
        )
        add(
            "pacote alvo=${facts.targetPackage} instalado=${yesNo(facts.targetInstalled)} " +
                "versão=${facts.targetVersionName ?: "?"} uid=${facts.targetUid ?: "?"}"
        )
        add(
            if (facts.perAppApplied) {
                "per-app: o sistema aceitou addAllowedApplication(${facts.targetPackage}) — " +
                    "somente esse app deve entrar no túnel"
            } else {
                "per-app: NÃO aplicado (${facts.perAppError ?: "motivo desconhecido"}) — " +
                    "se o túnel subir, ele vale para todos os apps"
            }
        )
        add(
            "destino do DNS CDNI=${CdnRouterConfig.REDIRECT_TO} · " +
                "rota=${CdnRouterConfig.VPN_ROUTE}/${CdnRouterConfig.VPN_ROUTE_PREFIX} · " +
                "dns-do-tunel=${CdnRouterConfig.VPN_DNS} · " +
                "listeners previstos=${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT}, " +
                "${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT}"
        )
        add(
            "etapas medidas desta sessão: VPN criada -> app na allow-list -> pacote no TUN -> " +
                "IPv4/IPv6 válido -> destino -> protocolo/porta -> (CDNI) -> listener local"
        )
        facts.extra.forEach { add(it) }
    }

    private fun yesNo(value: Boolean): String = if (value) "sim" else "não"
}
