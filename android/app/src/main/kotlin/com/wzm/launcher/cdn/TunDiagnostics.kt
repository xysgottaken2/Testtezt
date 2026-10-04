package com.wzm.launcher.cdn

/**
 * Diagnóstico do tráfego do túnel (M3.3) — Kotlin puro, sem dependências Android,
 * para poder ser testado em JVM e para o log do device ser sempre o mesmo formato.
 *
 * Motivação: dois testes no mesmo S23 produziram resultados diferentes
 * (5 conexões/TLS recusado em um; 0 conexões e nenhuma consulta CDNI no outro).
 * Antes de mudar qualquer arquitetura, o launcher passa a **provar por log** o que
 * realmente chega ao túnel, o que é devolvido e o que é descartado, com motivo.
 *
 * Nada aqui altera o roteamento: só observa e descreve.
 */

/** Visão normalizada de um pacote lido do TUN. */
data class TunPacketView(
    val protocol: Int,
    val srcAddress: String,
    val srcPort: Int,
    val dstAddress: String,
    val dstPort: Int,
    val flags: Int,
    val totalLength: Int,
    /** Destino é o endereço do túnel ([CdnRouterConfig.VPN_ADDRESS]) ou o loopback. */
    val isLocalDest: Boolean,
    /** Destino é exatamente [CdnRouterConfig.VPN_ADDRESS] (o valor devolvido no DNS CDNI). */
    val isRedirectDest: Boolean,
    val isLoopbackDest: Boolean,
    val isDnsPort: Boolean,
    val isHttpsPort: Boolean
) {
    val protocolName: String
        get() = when (protocol) {
            TunnelPackets.PROTO_TCP -> "TCP"
            TunnelPackets.PROTO_UDP -> "UDP"
            TunnelPackets.PROTO_ICMP -> "ICMP"
            else -> "proto$protocol"
        }

    val isSyn: Boolean get() = protocol == TunnelPackets.PROTO_TCP &&
        (flags and TunnelPackets.FLAG_SYN) != 0 && (flags and TunnelPackets.FLAG_ACK) == 0

    val isSynAck: Boolean get() = protocol == TunnelPackets.PROTO_TCP &&
        (flags and TunnelPackets.FLAG_SYN) != 0 && (flags and TunnelPackets.FLAG_ACK) != 0

    /** `TCP 10.111.222.1:41234 -> 10.111.222.1:443 flags=SYN` — uma linha por pacote. */
    fun brief(): String = buildString {
        append(protocolName).append(' ')
        append(srcAddress).append(':').append(srcPort)
        append(" -> ")
        append(dstAddress).append(':').append(dstPort)
        if (protocol == TunnelPackets.PROTO_TCP) append(" flags=").append(flagNames())
        append(" (").append(totalLength).append(" B)")
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
 * Motivos estáveis de descarte (código legível por máquina + dica humana).
 * O código aparece no log como `motivo=<CODIGO>`.
 */
enum class TunDiscardReason(val code: String, val hint: String) {
    PACOTE_INVALIDO(
        "PACOTE_INVALIDO",
        "não é IPv4 válido ou o cabeçalho não cabe no pacote lido"
    ),
    PROTO_NAO_SUPORTADO(
        "PROTO_NAO_SUPORTADO",
        "o túnel só trata TCP e UDP-DNS; ICMP e outros protocolos não têm resposta local"
    ),
    UDP_PORTA_NAO_DNS(
        "UDP_PORTA_NAO_DNS",
        "UDP só é atendido na porta 53 (DNS)"
    ),
    FORA_DA_ROTA(
        "FORA_DA_ROTA",
        "destino não é 10.111.222.1 nem 127.0.0.1; nada é encaminhado para fora (decisão de escopo)"
    ),
    TCP_SEM_ATENDIMENTO(
        "TCP_SEM_ATENDIMENTO",
        "destino sem listener local: respondemos RST em vez de fingir uma resposta"
    );

    /** Linha de log padronizada do descarte. */
    fun line(view: TunPacketView?, extra: String = ""): String {
        val origin = view?.brief() ?: "pacote ilegível"
        val suffix = if (extra.isEmpty()) "" else " — $extra"
        return "pacote descartado: motivo=$code ($origin) — $hint$suffix"
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

    /** Devolve null quando o pacote não é IPv4 mínimo válido. */
    fun view(packet: ByteArray, length: Int): TunPacketView? {
        if (!TunnelPackets.isValid(packet, length)) return null
        val protocol = TunnelPackets.protocol(packet)
        val block = if (protocol == TunnelPackets.PROTO_TCP || protocol == TunnelPackets.PROTO_UDP) {
            val offset = TunnelPackets.transportOffset(packet)
            val hasPorts = length >= offset + 4
            Pair(
                if (hasPorts) TunnelPackets.srcPort(packet, offset) else -1,
                if (hasPorts) TunnelPackets.dstPort(packet, offset) else -1
            )
        } else {
            Pair(-1, -1)
        }
        val flags = if (protocol == TunnelPackets.PROTO_TCP &&
            length >= TunnelPackets.transportOffset(packet) + 14
        ) {
            TunnelPackets.tcpFlags(packet, TunnelPackets.transportOffset(packet))
        } else {
            0
        }
        val dst = TunnelPackets.dstAddress(packet)
        return TunPacketView(
            protocol = protocol,
            srcAddress = TunnelPackets.srcAddress(packet),
            srcPort = block.first,
            dstAddress = dst,
            dstPort = block.second,
            flags = flags,
            totalLength = TunnelPackets.totalLength(packet, length),
            isLocalDest = dst == CdnRouterConfig.VPN_ADDRESS || dst == CdnRouterConfig.LOOPBACK_ADDRESS,
            isRedirectDest = dst == CdnRouterConfig.VPN_ADDRESS,
            isLoopbackDest = dst == CdnRouterConfig.LOOPBACK_ADDRESS,
            isDnsPort = block.second == CdnRouterConfig.DNS_PORT,
            isHttpsPort = block.second == CdnRouterConfig.LOCAL_HTTPS_PORT
        )
    }

    /**
     * Motivo pelo qual este pacote **não** será tratado (devolvido ao túnel/respondido).
     * null = o pacote segue o fluxo normal (DNS ou bounce).
     */
    fun discardReason(view: TunPacketView): TunDiscardReason? = when {
        view.protocol == TunnelPackets.PROTO_UDP && !view.isDnsPort -> TunDiscardReason.UDP_PORTA_NAO_DNS
        view.protocol == TunnelPackets.PROTO_UDP && view.isDnsPort -> null
        view.protocol == TunnelPackets.PROTO_TCP && view.isLocalDest -> null
        view.protocol == TunnelPackets.PROTO_TCP -> TunDiscardReason.TCP_SEM_ATENDIMENTO
        else -> TunDiscardReason.PROTO_NAO_SUPORTADO
    }

    /** Linha de resumo usada no heartbeat e no fim da sessão. */
    fun summaryLine(counters: RequestCounters): String =
        "resumo do túnel: pacotes=${counters.tunPackets} " +
            "para-${CdnRouterConfig.VPN_ADDRESS}=${counters.tunToRedirect} " +
            "devolvidos-bounce=${counters.tunBounces} " +
            "descartados=${counters.tunDiscards} " +
            "dns-total=${counters.dnsQueries} dns-cdni-interceptado=${counters.dnsIntercepted} " +
            "dns-encaminhado=${counters.dnsForwarded} " +
            "tcp-conexoes=${counters.tcpConnections} tls-ok=${counters.tlsOk} tls-falha=${counters.tlsFailed}"
}

/** Eventos que o vigia (watchdog) do túnel pode emitir — cada um vira uma linha no log. */
enum class TunWatchdogEvent(val code: String) {
    NENHUM_PACOTE_NO_TUN("NENHUM_PACOTE_NO_TUN"),
    NENHUMA_CONSULTA_CDNI("NENHUMA_CONSULTA_CDNI"),
    RESUMO_PERIODICO("RESUMO_PERIODICO")
}

/**
 * Vigia de atividade do túnel: distingue "o WZM não usou o túnel nesta sessão" de
 * "usou e falhou depois" — a dúvida exata entre os dois testes no device.
 *
 * Puro (recebe o tempo por parâmetro) para ser testável em JVM sem threads.
 */
class TunActivityWatchdog(
    private val noPacketAfterMs: Long = 60_000,
    private val noCdnDnsAfterMs: Long = 60_000,
    private val summaryEveryMs: Long = 60_000
) {

    private var startedAtMs = -1L
    private var lastPacketAtMs = -1L
    private var lastCdnDnsAtMs = -1L
    private var lastSummaryAtMs = -1L
    private var packets = 0
    private var cdnDns = 0
    private var warnedNoPacket = false
    private var warnedNoCdnDns = false

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

    val packetCount: Int get() = packets
    val cdnDnsCount: Int get() = cdnDns

    /** Segundos desde o último pacote (ou desde o início, se nunca houve pacote). */
    fun secondsSinceLastPacket(nowMs: Long): Long {
        val reference = if (lastPacketAtMs >= 0) lastPacketAtMs else startedAtMs
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
        if (nowMs - lastSummaryAtMs >= summaryEveryMs) {
            lastSummaryAtMs = nowMs
            events += TunWatchdogEvent.RESUMO_PERIODICO
        }
        return events
    }

    /** Mensagem humana para cada evento (o texto que aparece no log). */
    fun messageFor(event: TunWatchdogEvent, counters: RequestCounters, nowMs: Long): String = when (event) {
        TunWatchdogEvent.NENHUM_PACOTE_NO_TUN ->
            "nenhum pacote recebido no TUN nos primeiros ${secondsSinceLastPacket(nowMs)} s — " +
                "o app alvo não usou o túnel nesta sessão (checar: WZM em execução, Private DNS ligado, " +
                "per-app aplicado, rotas do túnel)"
        TunWatchdogEvent.NENHUMA_CONSULTA_CDNI ->
            "nenhuma consulta DNS dos hosts CDNI chegou ao túnel até agora " +
                "(no túnel: dns-total=${counters.dnsQueries}, encaminhadas=${counters.dnsForwarded}) — " +
                "sem isso o DNS não devolve ${CdnRouterConfig.REDIRECT_TO} e não há como o app chegar ao listener"
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
        facts.extra.forEach { add(it) }
    }

    private fun yesNo(value: Boolean): String = if (value) "sim" else "não"
}
