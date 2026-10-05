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
    /** SYNs no TUN cujo owner UID resolvido é o UID-alvo; UID não identifica PID/processo. */
    val tunUidVerifiedFlows: Int = 0,
    /** Subconjunto dos SYNs acima para 10.111.222.1:443. */
    val tunUidVerifiedCdniSyns: Int = 0,
    /** Consultas DNS que NÃO eram do CDNI e foram encaminhadas ao DNS real. */
    val dnsForwarded: Int = 0,
    // ---- Listener local: separação por papel (M3.5) ----
    /** Conexões aceitas no endereço do túnel, sem implicar autoria do WZM. */
    val tcpConnectionsTunel: Int = 0,
    /** Aceitas fora da janela sintética e com owner UID resolvido igual ao UID do pacote-alvo. */
    val tcpConnectionsTunelUidAlvo: Int = 0,
    /** Aceitas no endereço do túnel durante o teste sintético; caminho do launcher, nunca prova WZM. */
    val tcpConnectionsTunelSintetico: Int = 0,
    /** Conexões aceitas em 127.0.0.1:443 — DIAGNÓSTICO; nunca contam como evidência de tráfego externo do WZM. */
    val tcpConnectionsLoopback: Int = 0,
    val tlsOkTunel: Int = 0,
    val tlsFailedTunel: Int = 0,
    /** Handshakes na mesma conexão aceita cujo UID peer foi resolvido como UID-alvo. */
    val tlsOkTunelUidAlvo: Int = 0,
    val tlsFailedTunelUidAlvo: Int = 0,
    val tlsOkLoopback: Int = 0,
    val tlsFailedLoopback: Int = 0,
    /** Conexões de loopback antes do marcador de lançamento do launcher (não significa processo ausente). */
    val loopbackAntesDoWzm: Int = 0,
    /** Conexões de loopback depois do marcador (correlação temporal apenas; diagnóstico). */
    val loopbackDepoisDoWzm: Int = 0,
    // ---- M3.6: classificação do IPv6 descartado e rastreio do destino ----
    /** IPv6 descartado que é descoberta local (ICMPv6 vizinhança/MLD em multicast/link-local). */
    val tunIpv6DescobertaLocal: Int = 0,
    /** IPv6 descartado em multicast que NÃO é descoberta (ex.: outro tráfego de grupo). */
    val tunIpv6MulticastOutro: Int = 0,
    /** IPv6 descartado em endereço unicast (link-local ou global); origem UID/processo não inferida. */
    val tunIpv6Unicast: Int = 0,
    /** Pacotes IPv6 antes do marcador de lançamento do launcher (ordem temporal, não ausência do processo). */
    val tunIpv6AntesDoWzm: Int = 0,
    /** Pacotes IPv6 depois do marcador de lançamento do launcher (ordem temporal apenas). */
    val tunIpv6DepoisDoWzm: Int = 0,
    /** Fluxos cujo destino casou com uma resposta DNS observada no túnel (nome → IP). */
    val tunFluxosDestinoResolvido: Int = 0,
    /** Respostas DNS (nome → endereços) guardadas no cache observacional. */
    val dnsRespostasRegistradas: Int = 0,
    /** ICMPv4 observado no túnel (simetria de instrumentação com o ICMPv6). */
    val tunIcmpv4Flows: Int = 0,
    // ---- M4.1: autoria das conexões (teste de controle da API) e origem das conexões de loopback ----
    /** Passos do teste de controle em que a API resolveu UID da tupla do próprio launcher. */
    val ownerProbeResolvido: Int = 0,
    /** Passos em que a API devolveu INVALID_UID (ambíguo por desenho — ver ConnectionOwnership). */
    val ownerProbeInvalid: Int = 0,
    /** Passos em que a API recusou a chamada (não somos o VPN ativo / sem NETWORK_STACK). */
    val ownerProbeSemPermissao: Int = 0,
    /** Conexões do próprio processo provadas por match único da tupla cliente completa registrada. */
    val loopbackMesmoProcesso: Int = 0,
    /** Peer cujo UID resolvido é o UID do launcher (não identifica qual processo). */
    val loopbackUidLauncher: Int = 0,
    /** Peer cujo UID resolvido é o UID do app-alvo (não identifica processo nem tráfego externo). */
    val loopbackUidAlvo: Int = 0,
    /** Possível processo launcher por janela temporal + faixa efêmera compartilhada (PROBABLE). */
    val loopbackPossivelLauncher: Int = 0,
    /** Peer atribuído a um UID resolvido que não é launcher nem app-alvo. */
    val loopbackOutroUid: Int = 0,
    /** Conexões sem UID/processo identificável (INVALID_UID ou evidência insuficiente). */
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
            "listener: túnel $tcpConnectionsTunel conexão(ões) (UID-alvo $tcpConnectionsTunelUidAlvo; " +
            "sintético $tcpConnectionsTunelSintetico) / loopback $tcpConnectionsLoopback " +
            "(diagnóstico; antes-do-marcador $loopbackAntesDoWzm, depois $loopbackDepoisDoWzm) • " +
            "TUN: $tunPacketsTotal pacotes (IPv4 $tunIpv4Packets / IPv6 $tunIpv6Packets / inválidos $tunInvalidPackets; " +
            "TCP $tunTcpPackets / UDP $tunUdpPackets / ICMP $tunIcmpPackets) • " +
            "alvo-CDNI: $tunToRedirect bounce $tunBounces / $tunDiscards descartes • " +
            "IPv6 descartado: descoberta-local $tunIpv6DescobertaLocal / multicast-outro $tunIpv6MulticastOutro / " +
            "unicast $tunIpv6Unicast (antes-do-marcador $tunIpv6AntesDoWzm, depois $tunIpv6DepoisDoWzm) • " +
            "destino-resolvido $tunFluxosDestinoResolvido (respostas DNS guardadas $dnsRespostasRegistradas) • " +
            "autoria: api-resolvido $ownerProbeResolvido / INVALID_UID $ownerProbeInvalid / " +
            "sem-permissao $ownerProbeSemPermissao • loopback peer: processo-exato $loopbackMesmoProcesso / " +
            "uid-launcher $loopbackUidLauncher / uid-alvo $loopbackUidAlvo / possivel-launcher $loopbackPossivelLauncher / " +
            "outro-uid $loopbackOutroUid / indeterminado $loopbackIndeterminado • " +
            "DNS-para-loopback $dnsRespostasParaLoopback (deve ser 0)"

    /** Versão curta para os cards. */
    fun compact(): String =
        "DNS $dnsQueries/$dnsIntercepted/$dnsForwarded • TCP $tcpConnections • HTTP $httpRequests/$unknownRequests • " +
            "TLS $tlsOk/$tlsFailed (túnel $tlsOkTunel/$tlsFailedTunel; UID-alvo $tlsOkTunelUidAlvo/$tlsFailedTunelUidAlvo) • " +
            "listener túnel $tcpConnectionsTunel (UID-alvo $tcpConnectionsTunelUidAlvo; sintético $tcpConnectionsTunelSintetico) / " +
            "loopback $tcpConnectionsLoopback • " +
            "TUN $tunPacketsTotal(v4 $tunIpv4Packets/v6 $tunIpv6Packets/inv $tunInvalidPackets) " +
            "bounce $tunBounces desc $tunDiscards • v6-descoberta $tunIpv6DescobertaLocal/v6-unicast $tunIpv6Unicast • " +
            "destino-resolvido $tunFluxosDestinoResolvido • loopback processo-exato $loopbackMesmoProcesso / " +
            "uid-alvo $loopbackUidAlvo / uid-launcher $loopbackUidLauncher / indet $loopbackIndeterminado • " +
            "api INVALID_UID $ownerProbeInvalid"

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
            "tunUidVerifiedFlows=$tunUidVerifiedFlows tunUidVerifiedCdniSyns=$tunUidVerifiedCdniSyns " +
            "tcpConnectionsTunel=$tcpConnectionsTunel tcpConnectionsTunelUidAlvo=$tcpConnectionsTunelUidAlvo " +
            "tcpConnectionsTunelSintetico=$tcpConnectionsTunelSintetico tcpConnectionsLoopback=$tcpConnectionsLoopback " +
            "tlsOkTunel=$tlsOkTunel tlsFailedTunel=$tlsFailedTunel " +
            "tlsOkTunelUidAlvo=$tlsOkTunelUidAlvo tlsFailedTunelUidAlvo=$tlsFailedTunelUidAlvo " +
            "tlsOkLoopback=$tlsOkLoopback tlsFailedLoopback=$tlsFailedLoopback " +
            "loopbackAntesDoWzm=$loopbackAntesDoWzm loopbackDepoisDoWzm=$loopbackDepoisDoWzm " +
            "tunIpv6DescobertaLocal=$tunIpv6DescobertaLocal tunIpv6MulticastOutro=$tunIpv6MulticastOutro " +
            "tunIpv6Unicast=$tunIpv6Unicast tunIpv6AntesDoWzm=$tunIpv6AntesDoWzm " +
            "tunIpv6DepoisDoWzm=$tunIpv6DepoisDoWzm tunFluxosDestinoResolvido=$tunFluxosDestinoResolvido " +
            "dnsRespostasRegistradas=$dnsRespostasRegistradas tunIcmpv4Flows=$tunIcmpv4Flows " +
            "ownerProbeResolvido=$ownerProbeResolvido ownerProbeInvalid=$ownerProbeInvalid " +
            "ownerProbeSemPermissao=$ownerProbeSemPermissao loopbackMesmoProcesso=$loopbackMesmoProcesso " +
            "loopbackUidLauncher=$loopbackUidLauncher loopbackUidAlvo=$loopbackUidAlvo " +
            "loopbackPossivelLauncher=$loopbackPossivelLauncher loopbackOutroUid=$loopbackOutroUid " +
            "loopbackIndeterminado=$loopbackIndeterminado " +
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
 * Buffer único de log do launcher: eventos locais, pedidos aceitos pelos listeners e metadados observados
 * no TUN. Um evento de listener/TUN não é automaticamente atribuído ao WZM; autoria exige owner UID/tupla
 * conforme o nível de evidência registrado.
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
    private val tunUidVerifiedCdniSyns = AtomicInteger(0)
    private val tcpConnectionsTunel = AtomicInteger(0)
    private val tcpConnectionsTunelUidAlvo = AtomicInteger(0)
    private val tcpConnectionsTunelSintetico = AtomicInteger(0)
    private val tcpConnectionsLoopback = AtomicInteger(0)
    private val tlsOkTunel = AtomicInteger(0)
    private val tlsFailedTunel = AtomicInteger(0)
    private val tlsOkTunelUidAlvo = AtomicInteger(0)
    private val tlsFailedTunelUidAlvo = AtomicInteger(0)
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
    private val loopbackUidLauncher = AtomicInteger(0)
    private val loopbackUidAlvo = AtomicInteger(0)
    private val loopbackPossivelLauncher = AtomicInteger(0)
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
     * Total de conexões aceitas (legado). O total e o listener isolados não atribuem autoria.
     */
    fun incTcpConnection() { tcpConnections.incrementAndGet(); publishCounters() }
    fun incHttpRequest() { httpRequests.incrementAndGet(); publishCounters() }
    fun incUnknownRequest() { unknownRequests.incrementAndGet(); publishCounters() }
    fun incTlsOk() { tlsOk.incrementAndGet(); publishCounters() }
    fun incTlsFailed() { tlsFailed.incrementAndGet(); publishCounters() }

    /** Conexão aceita no listener do túnel; só UID resolvido + fora da janela sintética atribui o UID-alvo. */
    fun incTcpConnectionTunel(targetUidVerified: Boolean = false, synthetic: Boolean = false) {
        tcpConnectionsTunel.incrementAndGet()
        if (synthetic) tcpConnectionsTunelSintetico.incrementAndGet()
        if (targetUidVerified && !synthetic) tcpConnectionsTunelUidAlvo.incrementAndGet()
        tcpConnections.incrementAndGet()
        publishCounters()
    }

    /** Conexão aceita em loopback (diagnóstico). [beforeWzm] é somente ordem ao redor do marcador do launcher. */
    fun incTcpConnectionLoopback(beforeWzm: Boolean) {
        tcpConnectionsLoopback.incrementAndGet()
        tcpConnections.incrementAndGet()
        if (beforeWzm) loopbackAntesDoWzm.incrementAndGet() else loopbackDepoisDoWzm.incrementAndGet()
        publishCounters()
    }

    fun incTlsOkTunel(targetUidVerified: Boolean = false) {
        tlsOkTunel.incrementAndGet()
        if (targetUidVerified) tlsOkTunelUidAlvo.incrementAndGet()
        tlsOk.incrementAndGet()
        publishCounters()
    }

    fun incTlsFailedTunel(targetUidVerified: Boolean = false) {
        tlsFailedTunel.incrementAndGet()
        if (targetUidVerified) tlsFailedTunelUidAlvo.incrementAndGet()
        tlsFailed.incrementAndGet()
        publishCounters()
    }

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
    fun incTunUidVerifiedFlow(toCdniTarget: Boolean = false) {
        tunUidVerifiedFlows.incrementAndGet()
        if (toCdniTarget) tunUidVerifiedCdniSyns.incrementAndGet()
        publishCounters()
    }

    /** Registra uma observação de caminho ([TunObservation]) no contador correspondente. */
    /**
     * IPv6 descartado, classificado (M3.6) — descoberta local, multicast outro ou unicast — e
     * separado pela ordem do marcador de lançamento do launcher. Isso não mostra quando o processo
     * do jogo/helper iniciou e não atribui o pacote ao WZM.
     */
    fun incTunIpv6Category(category: TrafficClassifier.Ipv6Category, beforeWzm: Boolean) {
        val counter = when (category) {
            TrafficClassifier.Ipv6Category.DESCOBERTA_LOCAL -> tunIpv6DescobertaLocal
            TrafficClassifier.Ipv6Category.MULTICAST_OUTRO -> tunIpv6MulticastOutro
            TrafficClassifier.Ipv6Category.UNICAST -> tunIpv6Unicast
        }
        counter.incrementAndGet()
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

    /**
     * M4.1: desfecho de uma consulta de autoria (`getConnectionOwnerUid`) no teste de controle.
     * Só há contadores dedicados para RESOLVIDO, INVALID_UID e SEM_PERMISSAO; os cinco resultados
     * operacionais restantes são preservados no log do passo, mas não inflacionam esses contadores.
     * O `when` é uma expressão exaustiva: adicionar um Outcome exige revisar esta política.
     */
    fun incOwnerProbeResult(result: ConnectionOwnership.Result) {
        val counter = when (result.outcome) {
            ConnectionOwnership.Outcome.RESOLVIDO -> ownerProbeResolvido
            ConnectionOwnership.Outcome.INVALID_UID -> ownerProbeInvalid
            ConnectionOwnership.Outcome.SECURITY_EXCEPTION -> ownerProbeSemPermissao
            ConnectionOwnership.Outcome.API_ANTIGA,
            ConnectionOwnership.Outcome.SERVICO_INDISPONIVEL,
            ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS,
            ConnectionOwnership.Outcome.ARGUMENTO_INVALIDO,
            ConnectionOwnership.Outcome.CONSULTA_FALHOU -> null
        }
        counter?.incrementAndGet()
        publishCounters()
    }

    /** M4.1: origem de uma conexão aceita no listener (só o que os fatos permitem afirmar). */
    fun incLoopbackOrigin(verdict: LoopbackOrigin.Verdict) {
        val counter = when (verdict) {
            LoopbackOrigin.Verdict.PROCESSO_LAUNCHER -> loopbackMesmoProcesso
            LoopbackOrigin.Verdict.UID_LAUNCHER -> loopbackUidLauncher
            LoopbackOrigin.Verdict.UID_APP_ALVO -> loopbackUidAlvo
            LoopbackOrigin.Verdict.POSSIVEL_LAUNCHER -> loopbackPossivelLauncher
            LoopbackOrigin.Verdict.OUTRO_UID -> loopbackOutroUid
            LoopbackOrigin.Verdict.INDETERMINADO -> loopbackIndeterminado
        }
        counter.incrementAndGet()
        publishCounters()
    }

    /** M4.1: alarme defensivo — o DNS do túnel NUNCA deve devolver 127.0.0.1 (o destino é 10.111.222.1). */
    fun incDnsRespostaParaLoopback() {
        dnsRespostasParaLoopback.incrementAndGet()
        publishCounters()
    }

    fun incTunObservation(observation: TunObservation) {
        val counter = when (observation) {
            TunObservation.TCP_SYN -> tunTcpSyn
            TunObservation.TCP_SYN_PARA_ALVO_443 -> tunTcpSynToRedirect
            TunObservation.TCP_SYN_OUTRO_DESTINO -> tunTcpSynOther
            TunObservation.UDP_DNS_53 -> tunUdpDns53
            TunObservation.UDP_DNS_NO_DNS_VIRTUAL -> tunUdpDnsNoVirtualDns
            TunObservation.FLUXO_DOT -> tunDotFlows
            TunObservation.TCP_443_EXTERNO -> tunTcp443Externo
            TunObservation.UDP_443_QUIC_DOH -> tunDohCandidates
            TunObservation.ICMPV4 -> tunIcmpv4Flows
            // As três categorias IPv6 têm contador/ordenação temporal em incTunIpv6Category().
            TunObservation.IPV6_DESCOBERTA_LOCAL,
            TunObservation.IPV6_MULTICAST_OUTRO,
            TunObservation.IPV6_UNICAST -> null
        }
        counter?.incrementAndGet()
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
            tunUidVerifiedCdniSyns = tunUidVerifiedCdniSyns.get(),
            tcpConnectionsTunel = tcpConnectionsTunel.get(),
            tcpConnectionsTunelUidAlvo = tcpConnectionsTunelUidAlvo.get(),
            tcpConnectionsTunelSintetico = tcpConnectionsTunelSintetico.get(),
            tcpConnectionsLoopback = tcpConnectionsLoopback.get(),
            tlsOkTunel = tlsOkTunel.get(),
            tlsFailedTunel = tlsFailedTunel.get(),
            tlsOkTunelUidAlvo = tlsOkTunelUidAlvo.get(),
            tlsFailedTunelUidAlvo = tlsFailedTunelUidAlvo.get(),
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
            loopbackUidLauncher = loopbackUidLauncher.get(),
            loopbackUidAlvo = loopbackUidAlvo.get(),
            loopbackPossivelLauncher = loopbackPossivelLauncher.get(),
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
        tunDohCandidates.set(0); tunTcp443Externo.set(0); tunUidVerifiedFlows.set(0); tunUidVerifiedCdniSyns.set(0)
        tcpConnectionsTunel.set(0); tcpConnectionsTunelUidAlvo.set(0); tcpConnectionsTunelSintetico.set(0)
        tcpConnectionsLoopback.set(0)
        tlsOkTunel.set(0); tlsFailedTunel.set(0); tlsOkTunelUidAlvo.set(0); tlsFailedTunelUidAlvo.set(0)
        tlsOkLoopback.set(0); tlsFailedLoopback.set(0)
        loopbackAntesDoWzm.set(0); loopbackDepoisDoWzm.set(0)
        tunIpv6DescobertaLocal.set(0); tunIpv6MulticastOutro.set(0); tunIpv6Unicast.set(0)
        tunIpv6AntesDoWzm.set(0); tunIpv6DepoisDoWzm.set(0)
        tunFluxosDestinoResolvido.set(0); dnsRespostasRegistradas.set(0); tunIcmpv4Flows.set(0)
        ownerProbeResolvido.set(0); ownerProbeInvalid.set(0); ownerProbeSemPermissao.set(0)
        loopbackMesmoProcesso.set(0); loopbackUidLauncher.set(0); loopbackUidAlvo.set(0)
        loopbackPossivelLauncher.set(0); loopbackOutroUid.set(0); loopbackIndeterminado.set(0)
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

    // ---- Marcador de sessão: retorno bem-sucedido do lançamento do WZM pelo launcher (M3.5) ----
    // Não é evento de criação de processo: WZM/helper pode já estar em background antes do marcador.

    @Volatile
    private var wzmStartedAtMs: Long = -1L

    /**
     * Registra quando a chamada de lançamento do launcher retornou sucesso. O nome é legado: isto
     * não observa criação/execução de processo nem exclui um processo/helper já ativo.
     */
    fun markWzmStarted(atMillis: Long = System.currentTimeMillis()) {
        wzmStartedAtMs = atMillis
    }

    /** Apaga o marcador (usado por testes e por uma nova sessão explícita). */
    fun clearWzmMarker() {
        wzmStartedAtMs = -1L
    }

    val wzmStartedAt: Long? get() = wzmStartedAtMs.takeIf { it > 0 }

    /** `true` se a hora é anterior ao marcador de lançamento bem-sucedido (ou se ele não existe). */
    fun isBeforeWzmStart(atMillis: Long): Boolean = wzmStartedAt?.let { atMillis < it } ?: true

    // ---- Janela do teste sintético (M3.5) ----
    // O próprio launcher pode conectar no listener do túnel para validar o caminho; esta janela só
    // indica sobreposição temporal. A autoria de uma conexão exige correspondência da tupla registrada,
    // e nenhum teste sintético vira evidência de tráfego do WZM.

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
     * Relação temporal, não causal: o marcador do teste ou do lançamento só ordena eventos do
     * launcher. Uma conexão durante/depois do marcador não é automaticamente do launcher/WZM.
     */
    fun connectionOrigin(atMillis: Long): String =
        if (isDuringSyntheticTest(atMillis)) {
            "DURANTE a janela temporal do teste sintético do launcher — correlação apenas; " +
                "só uma tupla completa registrada identifica a conexão de controle; NÃO prova tráfego do WZM"
        } else {
            wzmRelation(atMillis)
        }

    /**
     * Relação temporal com o retorno bem-sucedido do lançamento pelo launcher (M3.5). Isso não
     * observa quando o processo/serviço do jogo começou; antes/depois é apenas correlação temporal.
     */
    fun wzmRelation(atMillis: Long): String {
        val marker = wzmStartedAt
            ?: return "nenhum marcador de lançamento do WZM nesta sessão — relação temporal UNKNOWN, " +
                "processo/helper pode já existir; autoria não resolvida"
        val deltaSeconds = (atMillis - marker) / 1000.0
        val formatted = String.format(Locale.US, "%.1f", kotlin.math.abs(deltaSeconds))
        return if (deltaSeconds < 0) {
            "antes do marcador de lançamento do WZM (Δ -$formatted s) — ordem temporal apenas; " +
                "processo/helper pode já existir"
        } else {
            "depois do marcador de lançamento do WZM (Δ +$formatted s) — ordem temporal apenas, " +
                "sem atribuição de processo"
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
