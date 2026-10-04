package com.wzm.launcher.cdn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contadores exibidos no card do roteador e na tela de logs.
 *
 * Os contadores do TUN (M3.4) são separados por versão/protocolo/porta para que o log responda
 * "o que exatamente chegou no túnel" — não existe mais um número único e genérico.
 */
data class RequestCounters(
    val dnsQueries: Int = 0,
    val dnsIntercepted: Int = 0,
    val tcpConnections: Int = 0,
    val httpRequests: Int = 0,
    val unknownRequests: Int = 0,
    val tlsOk: Int = 0,
    val tlsFailed: Int = 0,
    // ---- TUN: volume e classificação por versão/protocolo (M3.4) ----
    val tunPacketsTotal: Int = 0,
    val tunIpv4Packets: Int = 0,
    val tunIpv6Packets: Int = 0,
    val tunTcpPackets: Int = 0,
    val tunUdpPackets: Int = 0,
    val tunIcmpPackets: Int = 0,
    val tunInvalidPackets: Int = 0,
    val tunIpv4ToCdnTarget: Int = 0,
    val tunIpv6ToCdnTarget: Int = 0,
    val tunToRedirect: Int = 0,
    val tunBounces: Int = 0,
    val tunDiscards: Int = 0,
    // ---- TUN: observações de caminho (TCP/DNS/DoT/QUIC) ----
    val tunTcpSyn: Int = 0,
    val tunTcpSynToRedirect: Int = 0,
    val tunTcpSynOther: Int = 0,
    val tunUdpDns53: Int = 0,
    val tunUdpDnsNoVirtualDns: Int = 0,
    val tunDotFlows: Int = 0,
    val tunDohCandidates: Int = 0,
    val tunTcp443Externo: Int = 0,
    val tunUidVerifiedFlows: Int = 0,
    /** Consultas DNS que NÃO eram do CDNI e foram encaminhadas ao DNS real. */
    val dnsForwarded: Int = 0,
    // ---- Listener local: separação por papel (M3.5) ----
    /** Conexões aceitas no listener do ENDEREÇO DO TÚNEL (10.111.222.1:443) — único caminho com valor de evidência. */
    val tcpConnectionsTunel: Int = 0,
    /** Conexões aceitas em 127.0.0.1:443 — DIAGNÓSTICO; nunca contam como evidência de tráfego do WZM. */
    val tcpConnectionsLoopback: Int = 0,
    val tlsOkTunel: Int = 0,
    val tlsFailedTunel: Int = 0,
    val tlsOkLoopback: Int = 0,
    val tlsFailedLoopback: Int = 0,
    /** Conexões de loopback que ocorreram ANTES do WZM iniciado (não podem ser dele). */
    val loopbackAntesDoWzm: Int = 0,
    /** Conexões de loopback que ocorreram DEPOIS do WZM iniciado (ainda assim: só diagnóstico). */
    val loopbackDepoisDoWzm: Int = 0,
    // ---- M3.6: classificação do IPv6 descartado e rastreio do destino ----
    /** IPv6 descartado que é descoberta local (ICMPv6 vizinhança/MLD em multicast/link-local). */
    val tunIpv6DescobertaLocal: Int = 0,
    /** IPv6 descartado em multicast que NÃO é descoberta (ex.: outro tráfego de grupo). */
    val tunIpv6MulticastOutro: Int = 0,
    /** IPv6 descartado em endereço unicast (link-local ou global) — tráfego "de verdade". */
    val tunIpv6Unicast: Int = 0,
    /** Pacotes IPv6 vistos ANTES do WZM iniciado (separação temporal, como no loopback). */
    val tunIpv6AntesDoWzm: Int = 0,
    /** Pacotes IPv6 vistos DEPOIS do WZM iniciado. */
    val tunIpv6DepoisDoWzm: Int = 0,
    /** Fluxos cujo destino casou com uma resposta DNS observada no túnel (nome → IP). */
    val tunFluxosDestinoResolvido: Int = 0,
    /** Respostas DNS (nome → endereços) guardadas no cache observacional. */
    val dnsRespostasRegistradas: Int = 0,
    /** ICMPv4 observado no túnel (simetria de instrumentação com o ICMPv6). */
    val tunIcmpv4Flows: Int = 0,
    // ---- M4.1: autoria das conexões (teste de controle da API) e origem das conexões de loopback ----
    /** Passos do teste de controle em que a API resolveu o uid (autoria comprovada). */
    val ownerProbeResolvido: Int = 0,
    /** Passos em que a API devolveu INVALID_UID (ambíguo por desenho — ver ConnectionOwnership). */
    val ownerProbeInvalid: Int = 0,
    /** Passos em que a API recusou a chamada (não somos o VPN ativo / sem NETWORK_STACK). */
    val ownerProbeSemPermissao: Int = 0,
    /** Conexões de loopback cuja origem foi atribuída ao próprio launcher. */
    val loopbackMesmoProcesso: Int = 0,
    /** Conexões de loopback atribuídas a outro uid (ou a um uid que não é o launcher). */
    val loopbackOutroUid: Int = 0,
    /** Conexões de loopback sem atribuição possível (INVALID_UID sem marca do processo). */
    val loopbackIndeterminado: Int = 0,
    /** Respostas DNS em que NÓS devolvemos 127.0.0.1 — deve ser sempre 0 (o destino é 10.111.222.1). */
    val dnsRespostasParaLoopback: Int = 0,
    /**
     * Resultado (multi-linha) do **teste de controle de autoria** do M4.1, quando ele já rodou.
     * String vazia = ainda não rodou. Mostrar na UI, junto do que foi para o log.
     */
    val ownerProbeResumo: String = ""
) {
    /** Linha única com todos os contadores (UI e cabeçalho de exportação). */
    fun summary(): String =
        "DNS: $dnsQueries consultas / $dnsIntercepted interceptadas / $dnsForwarded encaminhadas • " +
            "TCP: $tcpConnections conexões • HTTP: $httpRequests requests / $unknownRequests desconhecidos • " +
            "TLS: $tlsOk ok / $tlsFailed falhas " +
            "(túnel $tlsOkTunel/$tlsFailedTunel · loopback $tlsOkLoopback/$tlsFailedLoopback) • " +
            "listener: túnel $tcpConnectionsTunel conexão(ões) / loopback $tcpConnectionsLoopback " +
            "(diagnóstico; antes-do-WZM $loopbackAntesDoWzm, depois $loopbackDepoisDoWzm) • " +
            "TUN: $tunPacketsTotal pacotes (IPv4 $tunIpv4Packets / IPv6 $tunIpv6Packets / inválidos $tunInvalidPackets; " +
            "TCP $tunTcpPackets / UDP $tunUdpPackets / ICMP $tunIcmpPackets) • " +
            "alvo-CDNI: $tunToRedirect bounce $tunBounces / $tunDiscards descartes • " +
            "IPv6 descartado: descoberta-local $tunIpv6DescobertaLocal / multicast-outro $tunIpv6MulticastOutro / " +
            "unicast $tunIpv6Unicast (antes-do-WZM $tunIpv6AntesDoWzm, depois $tunIpv6DepoisDoWzm) • " +
            "destino-resolvido $tunFluxosDestinoResolvido (respostas DNS guardadas $dnsRespostasRegistradas) • " +
            "autoria: api-resolvido $ownerProbeResolvido / INVALID_UID $ownerProbeInvalid / " +
            "sem-permissao $ownerProbeSemPermissao • loopback: mesmo-processo $loopbackMesmoProcesso / " +
            "outro-uid $loopbackOutroUid / indeterminado $loopbackIndeterminado • " +
            "DNS-para-loopback $dnsRespostasParaLoopback (deve ser 0)"

    /** Versão curta para os cards. */
    fun compact(): String =
        "DNS $dnsQueries/$dnsIntercepted/$dnsForwarded • TCP $tcpConnections • HTTP $httpRequests/$unknownRequests • " +
            "TLS $tlsOk/$tlsFailed (túnel $tlsOkTunel/$tlsFailedTunel) • " +
            "listener túnel $tcpConnectionsTunel / loopback $tcpConnectionsLoopback • " +
            "TUN $tunPacketsTotal(v4 $tunIpv4Packets/v6 $tunIpv6Packets/inv $tunInvalidPackets) " +
            "bounce $tunBounces desc $tunDiscards • v6-descoberta $tunIpv6DescobertaLocal/v6-unicast $tunIpv6Unicast • " +
            "destino-resolvido $tunFluxosDestinoResolvido • loopback mesmo-proc $loopbackMesmoProcesso/" +
            "indet $loopbackIndeterminado • api INVALID_UID $ownerProbeInvalid"

    /** Linha `chave=valor` com os nomes exatos usados no log e no relatório (export .txt). */
    fun exportLine(): String =
        "dnsQueries=$dnsQueries dnsIntercepted=$dnsIntercepted dnsForwarded=$dnsForwarded " +
            "tcpConnections=$tcpConnections httpRequests=$httpRequests unknownRequests=$unknownRequests " +
            "tlsOk=$tlsOk tlsFailed=$tlsFailed " +
            "tunPacketsTotal=$tunPacketsTotal tunIpv4Packets=$tunIpv4Packets tunIpv6Packets=$tunIpv6Packets " +
            "tunTcpPackets=$tunTcpPackets tunUdpPackets=$tunUdpPackets tunIcmpPackets=$tunIcmpPackets " +
            "tunInvalidPackets=$tunInvalidPackets " +
            "tunIpv4ToCdnTarget=$tunIpv4ToCdnTarget tunIpv6ToCdnTarget=$tunIpv6ToCdnTarget " +
            "tunToRedirect=$tunToRedirect tunBounces=$tunBounces tunDiscards=$tunDiscards " +
            "tunTcpSyn=$tunTcpSyn tunTcpSynToRedirect=$tunTcpSynToRedirect tunTcpSynOther=$tunTcpSynOther " +
            "tunUdpDns53=$tunUdpDns53 tunUdpDnsNoVirtualDns=$tunUdpDnsNoVirtualDns " +
            "tunDotFlows=$tunDotFlows tunDohCandidates=$tunDohCandidates tunTcp443Externo=$tunTcp443Externo " +
            "tunUidVerifiedFlows=$tunUidVerifiedFlows " +
            "tcpConnectionsTunel=$tcpConnectionsTunel tcpConnectionsLoopback=$tcpConnectionsLoopback " +
            "tlsOkTunel=$tlsOkTunel tlsFailedTunel=$tlsFailedTunel " +
            "tlsOkLoopback=$tlsOkLoopback tlsFailedLoopback=$tlsFailedLoopback " +
            "loopbackAntesDoWzm=$loopbackAntesDoWzm loopbackDepoisDoWzm=$loopbackDepoisDoWzm " +
            "tunIpv6DescobertaLocal=$tunIpv6DescobertaLocal tunIpv6MulticastOutro=$tunIpv6MulticastOutro " +
            "tunIpv6Unicast=$tunIpv6Unicast tunIpv6AntesDoWzm=$tunIpv6AntesDoWzm " +
            "tunIpv6DepoisDoWzm=$tunIpv6DepoisDoWzm tunFluxosDestinoResolvido=$tunFluxosDestinoResolvido " +
            "dnsRespostasRegistradas=$dnsRespostasRegistradas tunIcmpv4Flows=$tunIcmpv4Flows " +
            "ownerProbeResolvido=$ownerProbeResolvido ownerProbeInvalid=$ownerProbeInvalid " +
            "ownerProbeSemPermissao=$ownerProbeSemPermissao loopbackMesmoProcesso=$loopbackMesmoProcesso " +
            "loopbackOutroUid=$loopbackOutroUid loopbackIndeterminado=$loopbackIndeterminado " +
            "dnsRespostasParaLoopback=$dnsRespostasParaLoopback"
}

/**
 * Destino opcional de persistência: cada linha adicionada é entregue ao sink.
 * Ver [FileLogSink] (JVM-testável) e [LogPersistence] (ligação com o app).
 */
interface LogSink {
    fun append(line: String)
}

/**
 * Buffer único de log do launcher: eventos do próprio app + DNS/TLS/HTTP interceptados do WZM.
 *
 * Este é o `RequestLog` mostrado na tela "VER LOGS". Requisitos de privacidade (M3/M3.4):
 * registramos apenas tag, horário, método, host/path, status e **metadados de cabeçalho IP**
 * (versão, protocolo, endereço, porta, flags). Nunca corpos de requisição, cabeçalhos HTTP,
 * cookies, tokens, credenciais ou payload — nem mesmo dos pacotes do TUN.
 */
object RequestLog {

    /** Buffer em memória (linhas visíveis na UI). */
    const val MAX_ENTRIES = 400

    /** Limite por linha: evita uma linha gigante travar a UI/exportação. */
    const val MAX_LINE_CHARS = 800

    const val TAG_LAUNCHER = "LAUNCHER"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    @Volatile
    private var ownerProbeResumo: String = ""

    private val _counters = MutableStateFlow(RequestCounters())
    val counters: StateFlow<RequestCounters> = _counters.asStateFlow()

    private val dnsQueries = AtomicInteger(0)
    private val dnsIntercepted = AtomicInteger(0)
    private val dnsForwarded = AtomicInteger(0)
    private val tcpConnections = AtomicInteger(0)
    private val httpRequests = AtomicInteger(0)
    private val unknownRequests = AtomicInteger(0)
    private val tlsOk = AtomicInteger(0)
    private val tlsFailed = AtomicInteger(0)
    private val tunPacketsTotal = AtomicInteger(0)
    private val tunIpv4Packets = AtomicInteger(0)
    private val tunIpv6Packets = AtomicInteger(0)
    private val tunTcpPackets = AtomicInteger(0)
    private val tunUdpPackets = AtomicInteger(0)
    private val tunIcmpPackets = AtomicInteger(0)
    private val tunInvalidPackets = AtomicInteger(0)
    private val tunIpv4ToCdnTarget = AtomicInteger(0)
    private val tunIpv6ToCdnTarget = AtomicInteger(0)
    private val tunToRedirect = AtomicInteger(0)
    private val tunBounces = AtomicInteger(0)
    private val tunDiscards = AtomicInteger(0)
    private val tunTcpSyn = AtomicInteger(0)
    private val tunTcpSynToRedirect = AtomicInteger(0)
    private val tunTcpSynOther = AtomicInteger(0)
    private val tunUdpDns53 = AtomicInteger(0)
    private val tunUdpDnsNoVirtualDns = AtomicInteger(0)
    private val tunDotFlows = AtomicInteger(0)
    private val tunDohCandidates = AtomicInteger(0)
    private val tunTcp443Externo = AtomicInteger(0)
    private val tunUidVerifiedFlows = AtomicInteger(0)
    private val tcpConnectionsTunel = AtomicInteger(0)
    private val tcpConnectionsLoopback = AtomicInteger(0)
    private val tlsOkTunel = AtomicInteger(0)
    private val tlsFailedTunel = AtomicInteger(0)
    private val tlsOkLoopback = AtomicInteger(0)
    private val tlsFailedLoopback = AtomicInteger(0)
    private val loopbackAntesDoWzm = AtomicInteger(0)
    private val loopbackDepoisDoWzm = AtomicInteger(0)
    private val tunIpv6DescobertaLocal = AtomicInteger(0)
    private val tunIpv6MulticastOutro = AtomicInteger(0)
    private val tunIpv6Unicast = AtomicInteger(0)
    private val tunIpv6AntesDoWzm = AtomicInteger(0)
    private val tunIpv6DepoisDoWzm = AtomicInteger(0)
    private val tunFluxosDestinoResolvido = AtomicInteger(0)
    private val dnsRespostasRegistradas = AtomicInteger(0)
    private val tunIcmpv4Flows = AtomicInteger(0)
    private val ownerProbeResolvido = AtomicInteger(0)
    private val ownerProbeInvalid = AtomicInteger(0)
    private val ownerProbeSemPermissao = AtomicInteger(0)
    private val loopbackMesmoProcesso = AtomicInteger(0)
    private val loopbackOutroUid = AtomicInteger(0)
    private val loopbackIndeterminado = AtomicInteger(0)
    private val dnsRespostasParaLoopback = AtomicInteger(0)

    @Volatile
    private var sink: LogSink? = null

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "[${timeFormat.format(Date())}] [$tag] ${sanitize(message)}"
        buffer.addLast(line)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
        sink?.let { current -> runCatching { current.append(line) } }
    }

    /** Uma linha por evento (quebras viram espaço) e com tamanho limitado. */
    private fun sanitize(message: String): String {
        val singleLine = message.replace('\n', ' ').replace('\r', ' ')
        return if (singleLine.length > MAX_LINE_CHARS) singleLine.take(MAX_LINE_CHARS) + "…" else singleLine
    }

    fun incDnsQuery() { dnsQueries.incrementAndGet(); publishCounters() }
    fun incDnsIntercepted() { dnsIntercepted.incrementAndGet(); publishCounters() }
    fun incDnsForwarded() { dnsForwarded.incrementAndGet(); publishCounters() }
    /**
     * Total de conexões aceitas (legado). **Não é evidência:** para o quadro use
     * [incTcpConnectionTunel] (túnel) e [incTcpConnectionLoopback] (diagnóstico).
     */
    fun incTcpConnection() { tcpConnections.incrementAndGet(); publishCounters() }
    fun incHttpRequest() { httpRequests.incrementAndGet(); publishCounters() }
    fun incUnknownRequest() { unknownRequests.incrementAndGet(); publishCounters() }
    fun incTlsOk() { tlsOk.incrementAndGet(); publishCounters() }
    fun incTlsFailed() { tlsFailed.incrementAndGet(); publishCounters() }

    /**
     * Conexão aceita no listener do endereço do túnel (papel principal).
     * Este é o único contador de conexão com valor de evidência sobre o WZM (M3.5).
     */
    fun incTcpConnectionTunel() {
        tcpConnectionsTunel.incrementAndGet()
        tcpConnections.incrementAndGet()
        publishCounters()
    }

    /** Conexão aceita em 127.0.0.1:443 (diagnóstico). [beforeWzm] separa "antes" de "depois" do WZM. */
    fun incTcpConnectionLoopback(beforeWzm: Boolean) {
        tcpConnectionsLoopback.incrementAndGet()
        tcpConnections.incrementAndGet()
        if (beforeWzm) loopbackAntesDoWzm.incrementAndGet() else loopbackDepoisDoWzm.incrementAndGet()
        publishCounters()
    }

    fun incTlsOkTunel() { tlsOkTunel.incrementAndGet(); tlsOk.incrementAndGet(); publishCounters() }

    fun incTlsFailedTunel() { tlsFailedTunel.incrementAndGet(); tlsFailed.incrementAndGet(); publishCounters() }

    fun incTlsOkLoopback() { tlsOkLoopback.incrementAndGet(); tlsOk.incrementAndGet(); publishCounters() }

    fun incTlsFailedLoopback() {
        tlsFailedLoopback.incrementAndGet()
        tlsFailed.incrementAndGet()
        publishCounters()
    }

    fun incTunPacketsTotal() { tunPacketsTotal.incrementAndGet(); publishCounters() }
    fun incTunIpv4Packet() { tunIpv4Packets.incrementAndGet(); publishCounters() }
    fun incTunIpv6Packet() { tunIpv6Packets.incrementAndGet(); publishCounters() }
    fun incTunTcpPacket() { tunTcpPackets.incrementAndGet(); publishCounters() }
    fun incTunUdpPacket() { tunUdpPackets.incrementAndGet(); publishCounters() }
    fun incTunIcmpPacket() { tunIcmpPackets.incrementAndGet(); publishCounters() }
    fun incTunInvalidPacket() { tunInvalidPackets.incrementAndGet(); publishCounters() }
    fun incTunIpv4ToCdnTarget() { tunIpv4ToCdnTarget.incrementAndGet(); publishCounters() }
    fun incTunIpv6ToCdnTarget() { tunIpv6ToCdnTarget.incrementAndGet(); publishCounters() }
    fun incTunToRedirect() { tunToRedirect.incrementAndGet(); publishCounters() }
    fun incTunBounce() { tunBounces.incrementAndGet(); publishCounters() }
    fun incTunDiscard() { tunDiscards.incrementAndGet(); publishCounters() }
    fun incTunUidVerifiedFlow() { tunUidVerifiedFlows.incrementAndGet(); publishCounters() }

    /** Registra uma observação de caminho ([TunObservation]) no contador correspondente. */
    /**
     * IPv6 descartado, classificado (M3.6) — descoberta local, multicast outro ou unicast — e
     * separado por relação com o WZM iniciado. É o que permite dizer se o IPv6 descartado
     * ocorreu antes ou depois de o jogo abrir, em vez de supor que é dele.
     */
    fun incTunIpv6Category(category: TrafficClassifier.Ipv6Category, beforeWzm: Boolean) {
        when (category) {
            TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL -> tunIpv6DescobertaLocal.incrementAndGet()
            TrafficClassifier.Ipv6Category.MULTICAST_OUTRO -> tunIpv6MulticastOutro.incrementAndGet()
            TrafficClassifier.Ipv6Category.UNICAST -> tunIpv6Unicast.incrementAndGet()
        }
        if (beforeWzm) tunIpv6AntesDoWzm.incrementAndGet() else tunIpv6DepoisDoWzm.incrementAndGet()
        publishCounters()
    }

    fun incTunFluxoDestinoResolvido() {
        tunFluxosDestinoResolvido.incrementAndGet()
        publishCounters()
    }

    fun incDnsRespostaRegistrada() {
        dnsRespostasRegistradas.incrementAndGet()
        publishCounters()
    }

    fun incTunIcmpv4Flow() {
        tunIcmpv4Flows.incrementAndGet()
        publishCounters()
    }

    /** M4.1: desfecho de uma consulta de autoria (`getConnectionOwnerUid`) no teste de controle. */
    fun incOwnerProbeResult(result: ConnectionOwnership.Result) {
        when (result.outcome) {
            ConnectionOwnership.Outcome.RESOLVIDO -> ownerProbeResolvido.incrementAndGet()
            ConnectionOwnership.Outcome.INVALID_UID -> ownerProbeInvalid.incrementAndGet()
            ConnectionOwnership.Outcome.SECURITY_EXCEPTION -> ownerProbeSemPermissao.incrementAndGet()
            else -> Unit
        }
        publishCounters()
    }

    /** M4.1: origem de uma conexão aceita no listener (só o que os fatos permitem afirmar). */
    fun incLoopbackOrigin(verdict: LoopbackOrigin.Verdict) {
        when (verdict) {
            LoopbackOrigin.Verdict.MESMO_PROCESSO -> loopbackMesmoProcesso.incrementAndGet()
            LoopbackOrigin.Verdict.APP_ALVO, LoopbackOrigin.Verdict.OUTRO_UID -> loopbackOutroUid.incrementAndGet()
            LoopbackOrigin.Verdict.INDETERMINADO -> loopbackIndeterminado.incrementAndGet()
        }
        publishCounters()
    }

    /** M4.1: alarme defensivo — o DNS do túnel NUNCA deve devolver 127.0.0.1 (o destino é 10.111.222.1). */
    fun incDnsRespostaParaLoopback() {
        dnsRespostasParaLoopback.incrementAndGet()
        publishCounters()
    }

    fun incTunObservation(observation: TunObservation) {
        when (observation) {
            TunObservation.TCP_SYN -> tunTcpSyn.incrementAndGet()
            TunObservation.TCP_SYN_PARA_ALVO_443 -> tunTcpSynToRedirect.incrementAndGet()
            TunObservation.TCP_SYN_OUTRO_DESTINO -> tunTcpSynOther.incrementAndGet()
            TunObservation.UDP_DNS_53 -> tunUdpDns53.incrementAndGet()
            TunObservation.UDP_DNS_NO_DNS_VIRTUAL -> tunUdpDnsNoVirtualDns.incrementAndGet()
            TunObservation.FLUXO_DOT -> tunDotFlows.incrementAndGet()
            TunObservation.TCP_443_EXTERNO -> tunTcp443Externo.incrementAndGet()
            TunObservation.UDP_443_QUIC_DOH -> tunDohCandidates.incrementAndGet()
            // M3.6: categorias de IPv6 e ICMPv4 são contadas com relação temporal própria
            // (incTunIpv6Category) — aqui só o ICMPv4 genérico.
            TunObservation.ICMPV4 -> tunIcmpv4Flows.incrementAndGet()
            TunObservation.IPV6_DESCOBERTA_LOCAL,
            TunObservation.IPV6_MULTICAST_OUTRO,
            TunObservation.IPV6_UNICAST -> Unit
        }
        publishCounters()
    }

    private fun publishCounters() {
        _counters.value = RequestCounters(
            dnsQueries = dnsQueries.get(),
            dnsIntercepted = dnsIntercepted.get(),
            dnsForwarded = dnsForwarded.get(),
            tcpConnections = tcpConnections.get(),
            httpRequests = httpRequests.get(),
            unknownRequests = unknownRequests.get(),
            tlsOk = tlsOk.get(),
            tlsFailed = tlsFailed.get(),
            tunPacketsTotal = tunPacketsTotal.get(),
            tunIpv4Packets = tunIpv4Packets.get(),
            tunIpv6Packets = tunIpv6Packets.get(),
            tunTcpPackets = tunTcpPackets.get(),
            tunUdpPackets = tunUdpPackets.get(),
            tunIcmpPackets = tunIcmpPackets.get(),
            tunInvalidPackets = tunInvalidPackets.get(),
            tunIpv4ToCdnTarget = tunIpv4ToCdnTarget.get(),
            tunIpv6ToCdnTarget = tunIpv6ToCdnTarget.get(),
            tunToRedirect = tunToRedirect.get(),
            tunBounces = tunBounces.get(),
            tunDiscards = tunDiscards.get(),
            tunTcpSyn = tunTcpSyn.get(),
            tunTcpSynToRedirect = tunTcpSynToRedirect.get(),
            tunTcpSynOther = tunTcpSynOther.get(),
            tunUdpDns53 = tunUdpDns53.get(),
            tunUdpDnsNoVirtualDns = tunUdpDnsNoVirtualDns.get(),
            tunDotFlows = tunDotFlows.get(),
            tunDohCandidates = tunDohCandidates.get(),
            tunTcp443Externo = tunTcp443Externo.get(),
            tunUidVerifiedFlows = tunUidVerifiedFlows.get(),
            tcpConnectionsTunel = tcpConnectionsTunel.get(),
            tcpConnectionsLoopback = tcpConnectionsLoopback.get(),
            tlsOkTunel = tlsOkTunel.get(),
            tlsFailedTunel = tlsFailedTunel.get(),
            tlsOkLoopback = tlsOkLoopback.get(),
            tlsFailedLoopback = tlsFailedLoopback.get(),
            loopbackAntesDoWzm = loopbackAntesDoWzm.get(),
            tunIpv6DescobertaLocal = tunIpv6DescobertaLocal.get(),
            tunIpv6MulticastOutro = tunIpv6MulticastOutro.get(),
            tunIpv6Unicast = tunIpv6Unicast.get(),
            tunIpv6AntesDoWzm = tunIpv6AntesDoWzm.get(),
            tunIpv6DepoisDoWzm = tunIpv6DepoisDoWzm.get(),
            tunFluxosDestinoResolvido = tunFluxosDestinoResolvido.get(),
            dnsRespostasRegistradas = dnsRespostasRegistradas.get(),
            ownerProbeResolvido = ownerProbeResolvido.get(),
            ownerProbeInvalid = ownerProbeInvalid.get(),
            ownerProbeSemPermissao = ownerProbeSemPermissao.get(),
            loopbackMesmoProcesso = loopbackMesmoProcesso.get(),
            loopbackOutroUid = loopbackOutroUid.get(),
            loopbackIndeterminado = loopbackIndeterminado.get(),
            dnsRespostasParaLoopback = dnsRespostasParaLoopback.get(),
            tunIcmpv4Flows = tunIcmpv4Flows.get(),
            loopbackDepoisDoWzm = loopbackDepoisDoWzm.get(),
            ownerProbeResumo = ownerProbeResumo
        )
    }

    /** Publica (UI + estado) o resumo do teste de controle de autoria do M4.1. */
    fun setOwnerProbeResumo(texto: String) {
        ownerProbeResumo = texto
        publishCounters()
    }

    fun resetCounters() {
        dnsQueries.set(0); dnsIntercepted.set(0); dnsForwarded.set(0)
        tcpConnections.set(0); httpRequests.set(0); unknownRequests.set(0)
        tlsOk.set(0); tlsFailed.set(0)
        tunPacketsTotal.set(0); tunIpv4Packets.set(0); tunIpv6Packets.set(0)
        tunTcpPackets.set(0); tunUdpPackets.set(0); tunIcmpPackets.set(0); tunInvalidPackets.set(0)
        tunIpv4ToCdnTarget.set(0); tunIpv6ToCdnTarget.set(0)
        tunToRedirect.set(0); tunBounces.set(0); tunDiscards.set(0)
        tunTcpSyn.set(0); tunTcpSynToRedirect.set(0); tunTcpSynOther.set(0)
        tunUdpDns53.set(0); tunUdpDnsNoVirtualDns.set(0); tunDotFlows.set(0)
        tunDohCandidates.set(0); tunTcp443Externo.set(0); tunUidVerifiedFlows.set(0)
        tcpConnectionsTunel.set(0); tcpConnectionsLoopback.set(0)
        tlsOkTunel.set(0); tlsFailedTunel.set(0); tlsOkLoopback.set(0); tlsFailedLoopback.set(0)
        loopbackAntesDoWzm.set(0); loopbackDepoisDoWzm.set(0)
        tunIpv6DescobertaLocal.set(0); tunIpv6MulticastOutro.set(0); tunIpv6Unicast.set(0)
        tunIpv6AntesDoWzm.set(0); tunIpv6DepoisDoWzm.set(0)
        tunFluxosDestinoResolvido.set(0); dnsRespostasRegistradas.set(0); tunIcmpv4Flows.set(0)
        ownerProbeResolvido.set(0); ownerProbeInvalid.set(0); ownerProbeSemPermissao.set(0)
        loopbackMesmoProcesso.set(0); loopbackOutroUid.set(0); loopbackIndeterminado.set(0)
        dnsRespostasParaLoopback.set(0)
        ownerProbeResumo = ""
        publishCounters()
    }

    /** Limpa buffer **e** contadores (o arquivo persistido é limpo por [LogPersistence.clearFile]). */
    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        resetCounters()
        clearSyntheticWindow()
    }

    // ---- Marcador de sessão: quando o WZM foi iniciado (M3.5) ----
    // Sem isso, as conexões de loopback de 2026-10-04 (13:20:16) pareciam do WZM (iniciado 13:20:21).

    @Volatile
    private var wzmStartedAtMs: Long = -1L

    /** Registra o instante em que o WZM foi iniciado (chamado pelo launcher ao iniciá-lo). */
    fun markWzmStarted(atMillis: Long = System.currentTimeMillis()) {
        wzmStartedAtMs = atMillis
    }

    /** Apaga o marcador (usado por testes e por uma nova sessão explícita). */
    fun clearWzmMarker() {
        wzmStartedAtMs = -1L
    }

    val wzmStartedAt: Long? get() = wzmStartedAtMs.takeIf { it > 0 }

    /** `true` quando [atMillis] é anterior ao WZM iniciado (ou quando ele não foi iniciado). */
    fun isBeforeWzmStart(atMillis: Long): Boolean = wzmStartedAt?.let { atMillis < it } ?: true

    // ---- Janela do teste sintético (M3.5) ----
    // O próprio launcher conecta no listener do túnel para provar o CAMINHO; essa conexão tem o UID do
    // launcher, não o do WZM. Sem esta marca alguém poderia ler "conexão aceita no túnel" como evidência
    // de tráfego do jogo — por isso a janela tem precedência sobre a relação com o WZM.

    @Volatile
    private var syntheticStartedAtMs: Long = -1L

    @Volatile
    private var syntheticFinishedAtMs: Long = -1L

    fun markSyntheticTestStarted(atMillis: Long = System.currentTimeMillis()) {
        syntheticStartedAtMs = atMillis
        syntheticFinishedAtMs = -1L
    }

    fun markSyntheticTestFinished(atMillis: Long = System.currentTimeMillis()) {
        if (syntheticStartedAtMs > 0) syntheticFinishedAtMs = atMillis
    }

    fun clearSyntheticWindow() {
        syntheticStartedAtMs = -1L
        syntheticFinishedAtMs = -1L
    }

    val syntheticWindowOpen: Boolean get() = syntheticStartedAtMs > 0 && syntheticFinishedAtMs <= 0

    /** `true` quando [atMillis] caiu dentro do teste sintético (janela aberta = até ser fechada). */
    fun isDuringSyntheticTest(atMillis: Long): Boolean {
        val start = syntheticStartedAtMs
        if (start <= 0 || atMillis < start) return false
        val end = syntheticFinishedAtMs
        return end <= 0 || atMillis <= end
    }

    /**
     * Relação da conexão para o log: durante o teste sintético a conexão é **prova do caminho**, feita
     * pelo launcher (UID do launcher) — nunca do WZM; fora da janela vale a relação com o WZM iniciado.
     */
    fun connectionOrigin(atMillis: Long): String =
        if (isDuringSyntheticTest(atMillis)) {
            "DURANTE o teste sintético do launcher — prova o CAMINHO CDNI, NÃO o WZM " +
                "(autoria do jogo exige dono=uid=<pacote do WZM>)"
        } else {
            wzmRelation(atMillis)
        }

    /**
     * Relação temporal de uma conexão com o WZM iniciado — parte obrigatória do log de conexão (M3.5):
     * "antes" nunca pode ser atribuído ao WZM; "sem WZM iniciado" também não.
     */
    fun wzmRelation(atMillis: Long): String {
        val started = wzmStartedAt
            ?: return "WZM não iniciado nesta sessão — conexão NÃO pode ser atribuída ao WZM"
        val deltaSeconds = (atMillis - started) / 1000.0
        val formatted = String.format(Locale.US, "%.1f", kotlin.math.abs(deltaSeconds))
        return if (deltaSeconds < 0) {
            "antes do WZM iniciado (Δ -$formatted s) — NÃO pode ser atribuída ao WZM"
        } else {
            "depois do WZM iniciado (Δ +$formatted s)"
        }
    }

    /** Linhas do buffer, mais recentes por último. */
    @Synchronized
    fun lines(): List<String> = buffer.toList()

    /** Consulta por tag exata (ex.: "DNS", "CDNI", "TUN", "DIAG", "TLS", "HTTP", "VPN", "LAUNCHER"). */
    fun tagOf(line: String): String? {
        val separator = line.indexOf("] [")
        if (separator < 0) return null
        val end = line.indexOf(']', separator + 3)
        if (end < 0) return null
        return line.substring(separator + 3, end)
    }

    /** Filtra as linhas por tag; tag null devolve tudo (usado pela tela de logs e pelos testes). */
    fun filterTags(lines: List<String>, tag: String?): List<String> =
        if (tag == null) lines else lines.filter { tagOf(it) == tag }

    /** Todas as tags presentes nas linhas (para os chips da UI). */
    fun tagsIn(lines: List<String>): List<String> = lines.mapNotNull { tagOf(it) }.distinct().sorted()

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")

    /** Texto completo para COPIAR/EXPORTAR (cabeçalho com contadores + todas as linhas). */
    @Synchronized
    fun exportText(now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(now)
        val current = counters.value
        return buildString {
            append("# WZM Offline Launcher — RequestLog do roteador CDNI local\n")
            append("# exportado em: $stamp\n")
            append("# contadores: ${current.exportLine()}\n")
            append("# resumo: ${TunDiagnostics.summaryLine(current)}\n")
            append("# linhas: ${buffer.size} (buffer máximo: $MAX_ENTRIES)\n")
            append(
                "# privacidade: não são registrados corpos de requisição nem cabeçalhos HTTP (sem cookies, tokens\n" +
                    "#              ou credenciais); dos pacotes do TUN só metadados de cabeçalho (versão, protocolo,\n" +
                    "#              endereço, porta, flags) — nada de payload; hexadecimal apenas de pacote inválido (até 32 B).\n"
            )
            append("\n")
            buffer.forEach { append(it).append("\n") }
        }
    }

    /**
     * Repõe o buffer com linhas de uma sessão anterior (arquivo persistido).
     * Contadores **não** são restaurados (não há como reconstruí-los com fidelidade).
     */
    @Synchronized
    fun restore(lines: List<String>) {
        buffer.clear()
        lines.takeLast(MAX_ENTRIES).forEach { buffer.addLast(it) }
        _entries.value = buffer.toList()
    }

    /** Liga/desliga o destino de persistência. `null` desliga (usado nos testes). */
    fun attachSink(newSink: LogSink?) {
        sink = newSink
    }

    fun currentSink(): LogSink? = sink
}
