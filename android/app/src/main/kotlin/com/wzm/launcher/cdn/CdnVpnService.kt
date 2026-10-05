package com.wzm.launcher.cdn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections

/**
 * VpnService do launcher (M3/M3.4): NÃO encaminha tráfego para nenhum servidor remoto.
 *
 * O que ele faz:
 *  - cria uma interface tun com endereço [CdnRouterConfig.VPN_ADDRESS] e DNS [CdnRouterConfig.VPN_DNS];
 *  - restringe o túnel aos apps da lista (WZM) — `addAllowedApplication`;
 *  - responde em userspace as consultas DNS dos hosts CDNI comprovados, devolvendo
 *    [CdnRouterConfig.REDIRECT_TO] (o próprio endereço do túnel, local no aparelho);
 *  - encaminha as demais consultas DNS para resolvedores reais (sockets `protect()`ed);
 *  - devolve ao cliente ("bounce") os pacotes TCP endereçados ao endereço do túnel, para que a
 *    pilha TCP local do Android os entregue ao listener HTTPS em :443;
 *  - rejeita com RST o que não temos como atender (nunca finge uma resposta).
 *
 * M3.4 (esta etapa): o foco é o **caminho** WZM → per-app VPN → TUN → DNS/roteamento.
 * Cada pacote lido é classificado por versão IPv4/IPv6, protocolo, endereços, portas e flags
 * (IPv6 **não** é mais "pacote inválido"); cada descarte carrega `motivo=<CODIGO>`; o log traz
 * o quadro de evidências (VERIFIED/PROBABLE/HYPOTHESIS/UNKNOWN). Nada de TLS/trust/pinning aqui.
 */
class CdnVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.wzm.launcher.cdn.action.START"
        const val ACTION_STOP = "com.wzm.launcher.cdn.action.STOP"
        const val EXTRA_WZM_PACKAGE = "wzm_package"

        /** Quantas consultas DNS do túnel aparecem por extenso no log (depois, 1x por nome). */
        const val DNS_QUERY_LOG_BUDGET = 12

        /** Intervalo do vigia (limiares de inatividade: 60 s). */
        const val WATCHDOG_INTERVAL_MS = 20_000L

        fun start(context: Context, wzmPackage: String) {
            val intent = Intent(context, CdnVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_WZM_PACKAGE, wzmPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CdnVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                RequestLog.add("VPN", "falha ao pedir parada do serviço: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var lifecycleThread: Thread? = null
    private var readStream: FileInputStream? = null
    private var writeStream: FileOutputStream? = null

    @Volatile
    private var running = false

    private val dnsResponder = DnsResponder()
    private lateinit var upstreamDns: UpstreamDns
    private val watchdog = TunActivityWatchdog()

    /** Fatos da sessão para o quadro de evidências (atualizados sem bloquear a UI). */
    @Volatile
    private var diagFacts: DiagFacts = DiagFacts(
        perAppApplied = false,
        perAppError = "sessão não iniciada",
        targetPackage = "",
        targetUid = null,
        privateDnsReadable = false,
        privateDnsMode = null,
        privateDnsSpecifier = null,
        tunnelAddressAssigned = false,
        tunnelListenerBound = false,
        routerPhase = RouterPhase.PARADO.label
    )

    /** Quantas consultas DNS são registradas por extenso antes de passar a logar 1x por nome. */
    private var dnsQueryLogBudget = DNS_QUERY_LOG_BUDGET

    /** Evita repetir a mesma linha de log (pacotes repetidos geram milhares de eventos). */
    private val loggedOnce: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // ---- M3.6: instrumentação para separar "app sem rede" de "tráfego fora do túnel" ----

    /** Cache observacional nome→IP das respostas DNS que passaram pelo túnel. */
    private val dnsAnswers = DnsAnswerCache()

    /** Amostra inicial dos contadores do UID do alvo (null quando indisponível/não iniciada). */
    @Volatile
    private var uidTrafficStart: TrafficAccounting.UidStats? = null

    /** Última amostra do UID do alvo (base do delta do vigia). */
    @Volatile
    private var uidTrafficLast: TrafficAccounting.UidStats? = null

    /** Perfis IPv6 distintos já logados nesta sessão (limitado; só metadados de cabeçalho). */
    private val ipv6Profiles: MutableSet<String> = Collections.synchronizedSet(linkedSetOf<String>())

    /** Processos declarados no manifesto do app alvo (fato estático, lido uma vez). */
    @Volatile
    private var targetDeclaredProcesses: List<String> = emptyList()

    /** Quantas amostras de contabilidade o vigia já fez (usado só para não repetir linhas iguais). */
    private var trafficSampleTicks = 0

    /** Pacote/UID do alvo da sessão (preenchidos no `establish`, antes de qualquer amostra). */
    @Volatile
    private var targetPackageName: String = ""

    @Volatile
    private var targetUidValue: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            shutdown("parada solicitada pelo launcher")
            return Service.START_NOT_STICKY
        }
        CdnNotifications.ensureChannel(this)
        try {
            startAsForeground()
        } catch (e: Exception) {
            RequestLog.add("VPN", "falha ao entrar em foreground: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (running) {
            RequestLog.add("VPN", "túnel já estava ativo")
            return Service.START_STICKY
        }
        establish(intent?.getStringExtra(EXTRA_WZM_PACKAGE))
        return Service.START_STICKY
    }

    private fun startAsForeground() {
        val notification = CdnNotifications.build(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                CdnNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(CdnNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun establish(wzmPackage: String?) {
        AndroidDiagnostics.remember(this)
        val builder = Builder()
            .setSession(CdnRouterConfig.SESSION_NAME)
            .setMtu(CdnRouterConfig.MTU)
            .addAddress(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.VPN_PREFIX)
            .addDnsServer(CdnRouterConfig.VPN_DNS)

        // O plano explícito é só 10.111.222.0/24; não há rota default nem prefixo IPv6.
        // addAllowedApplication abaixo restringe UIDs separadamente e não amplia essas rotas.
        VpnCapturePlan.routes.forEach { route -> builder.addRoute(route.address, route.prefixLength) }

        var perAppApplied = false
        var perAppError: String? = null
        if (wzmPackage.isNullOrBlank()) {
            perAppError = "sem pacote alvo no intent (o túnel DNS valeria para TODOS os apps)"
            RequestLog.add("VPN", "AVISO: sem pacote alvo; o túnel DNS valeria para todos os apps")
        } else {
            try {
                builder.addAllowedApplication(wzmPackage)
                perAppApplied = true
                RequestLog.add("VPN", "per-app: somente $wzmPackage usará o túnel")
            } catch (e: PackageManager.NameNotFoundException) {
                val message = "pacote $wzmPackage não encontrado: ${e.message}"
                RequestLog.add("VPN", "ERRO: $message")
                CdnRouterController.onVpnStateChanged(false, message)
                shutdown("pacote alvo ausente")
                return
            }
        }

        val target = wzmPackage ?: "(sem pacote alvo)"
        targetPackageName = target
        targetUidValue = AndroidDiagnostics.targetUid(this, target)
        val privateDnsInfo = AndroidDiagnostics.privateDns()
        val privateDnsReadable = privateDnsInfo.first
        val pDnsMode = privateDnsInfo.second
        val pDnsSpecifier = privateDnsInfo.third

        // Cabeçalho de sessão (M3.3/M3.4): prova qual app entrou na VPN, se é a primeira execução e
        // quais destinos o túnel espera. Sem isso, dois testes no mesmo device ficam indistinguíveis.
        SessionReport.lines(
            AndroidDiagnostics.sessionFacts(this, target, perAppApplied, perAppError)
        ).forEach { line -> RequestLog.add("DIAG", line) }
        AndroidDiagnostics.privateDnsLines(this).forEach { line -> RequestLog.add("DIAG", line) }
        AndroidDiagnostics.tunnelInterfaceLines().forEach { line -> RequestLog.add("DIAG", line) }
        AndroidDiagnostics.vpnNetworkLines(this).forEach { line -> RequestLog.add("DIAG", line) }
        // M5.0: em qual rede o WZM resolve? Comparável com "DNS Requested by <netId>" do logcat.
        NetworkPathReport.lines(this).forEach { line -> RequestLog.add("DIAG", line) }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            val message = "${e.javaClass.simpleName}: ${e.message}"
            RequestLog.add("VPN", "falha ao estabelecer o túnel: $message")
            CdnRouterController.onVpnStateChanged(false, message)
            shutdown("establish() falhou")
            return
        }
        if (descriptor == null) {
            val message = "establish() devolveu null (consentimento negado ou já existe outra VPN ativa)"
            RequestLog.add("VPN", "ERRO: $message")
            CdnRouterController.onVpnStateChanged(false, message)
            shutdown("sem descritor do túnel")
            return
        }

        tunnel = descriptor
        readStream = FileInputStream(descriptor.fileDescriptor)
        writeStream = FileOutputStream(descriptor.fileDescriptor)
        upstreamDns = UpstreamDns(
            protect = { socket -> runCatching { protect(socket) }.getOrDefault(false) },
            onError = { message -> logThrottled("DNS", message) }
        )
        running = true
        worker = Thread({ loop() }, "cdn-tun-loop").also { it.isDaemon = true; it.start() }
        lifecycleThread = Thread(
            { lifecycleAndWatchdog(target, perAppApplied, perAppError, privateDnsReadable, pDnsMode, pDnsSpecifier) },
            "cdn-lifecycle-watchdog"
        ).also { it.isDaemon = true; it.start() }
        CdnRouterController.onVpnStateChanged(true)
        RequestLog.add(
            "VPN",
            "túnel ativo: DNS ${CdnRouterConfig.VPN_DNS}, rota ${CdnRouterConfig.VPN_ROUTE}/" +
                "${CdnRouterConfig.VPN_ROUTE_PREFIX}, ${CdnRouterConfig.INTERCEPT_HOSTS.joinToString()} -> " +
                "${CdnRouterConfig.REDIRECT_TO}"
        )
        // M4.1: janela de portas efêmeras que o kernel entrega a ESTE processo. Serve para o veredito
        // limitado de origem das conexões de loopback (a janela é compartilhada no aparelho: PROBABLE).
        val window = SelfPorts.calibrate()
        RequestLog.add(
            "DIAG",
            "calibração de portas do próprio processo: ${window.label()} — usada só para indicar origem " +
                "PROVÁVEL de conexões no listener (nunca prova de autoria)"
        )
        // M3.6 (item 1/2): baseline da contabilidade por UID e retrato dos processos. Sem eles,
        // "nada no TUN" não distingue "o app não fez rede" de "a rede não passou pelo túnel".
        logUidAccounting("início da sessão", rememberBaseline = true)
        logProcessInventory(target)
    }

    /** Amostra a contabilidade do UID do alvo e, opcionalmente, guarda como baseline da sessão. */
    private fun logUidAccounting(label: String, rememberBaseline: Boolean = false) {
        val target = targetPackageName
        val uid = targetUidValue
        if (target.isEmpty() || uid == null) {
            RequestLog.add("DIAG", "contabilidade do UID do alvo: INDISPONIVEL (sem pacote/UID do alvo)")
            return
        }
        val stats = TrafficAccounting.snapshot(uid)
        if (rememberBaseline) {
            uidTrafficStart = stats
            uidTrafficLast = stats
        }
        val baseline = if (rememberBaseline) null else uidTrafficStart
        val delta = baseline?.let { TrafficAccounting.delta(it, stats) }
        val grew = baseline?.let { TrafficAccounting.grew(it, stats) }
        RequestLog.add("DIAG", TrafficAccounting.line(label, stats, delta))
        if (grew != null) {
            RequestLog.add(
                "DIAG",
                "contabilidade do UID $uid ($label): variação desde o início = " +
                    (if (grew) "CRESCEU (houve tráfego de rede do app)" else "sem crescimento (nenhum byte novo)") +
                    " — conta por UID: processos auxiliares do mesmo pacote entram juntos"
            )
        }
        refreshTrafficFacts(stats)
    }

    /**
     * Inventário de processos (M3.6 item 1): processos do launcher (observáveis), processos
     * **declarados** no manifesto do alvo (fato estático) e o resultado — frequentemente vazio e
     * sempre explicado — da tentativa de listar processos do alvo em execução.
     */
    private fun logProcessInventory(target: String) {
        val uid = targetUidValue
        val facts = ProcessDiscovery.collect(this, target, uid)
        targetDeclaredProcesses = facts.targetDeclaredProcesses
        RequestLog.add("DIAG", "inventário de processos (leitura apenas; nenhum processo é alterado):")
        ProcessDiscovery.describe(facts).forEach { line -> RequestLog.add("DIAG", line) }
    }

    /** Atualiza os fatos do quadro de evidências com a contabilidade e com os perfis IPv6 vistos. */
    private fun refreshTrafficFacts(stats: TrafficAccounting.UidStats) {
        val baseline = uidTrafficStart
        val grew = baseline?.let { TrafficAccounting.grew(it, stats) }
        val bytes = baseline?.let { before ->
            val a = stats.totalBytes
            val b = before.totalBytes
            if (a != null && b != null) a - b else null
        }
        diagFacts = diagFacts.copy(
            uidTrafficAvailable = stats.available,
            uidTrafficBytesSinceStart = bytes,
            uidTrafficGrew = grew,
            ipv6Profiles = ipv6Profiles.toList(),
            targetDeclaredProcesses = targetDeclaredProcesses
        )
    }

    /**
     * Ordem correta do ciclo de vida (M3.4): **espera limitada** pelo endereço do túnel → avisa o
     * controlador (que só então inicia o listener no endereço do túnel) → entra em vigia periódico.
     * Roda em thread própria: nunca bloqueia a thread principal do serviço.
     */
    private fun lifecycleAndWatchdog(
        target: String,
        perAppApplied: Boolean,
        perAppError: String?,
        privateDnsReadable: Boolean,
        privateDnsMode: String?,
        privateDnsSpecifier: String?
    ) {
        val wait = AndroidDiagnostics.waitForAddress()
        if (wait.found) {
            RequestLog.add(
                "DIAG",
                "endereço do túnel ${CdnRouterConfig.VPN_ADDRESS} confirmado nas interfaces após " +
                    "${wait.elapsedMs} ms (tun0 pronto)"
            )
        } else {
            RequestLog.add(
                "DIAG",
                "endereço do túnel ${CdnRouterConfig.VPN_ADDRESS} NÃO apareceu em ${wait.elapsedMs} ms: " +
                    "o bind direto deve falhar (EADDRNOTAVAIL) e só o caminho " +
                    "${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} pode completar"
            )
        }
        watchdog.start(System.currentTimeMillis())
        refreshFacts(
            target, perAppApplied, perAppError, privateDnsReadable, privateDnsMode, privateDnsSpecifier,
            tunnelAddressAssigned = wait.found
        )
        // Só agora o controlador inicia o listener no endereço do túnel.
        CdnRouterController.onVpnEstablished(this, wait.found, wait.elapsedMs)

        while (running) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val now = System.currentTimeMillis()
            for (event in watchdog.poll(now)) {
                RequestLog.add("DIAG", watchdog.messageFor(event, RequestLog.counters.value, now))
            }
            emitTrafficSample()
            emitEvidenceBoard("periódico")
        }
    }

    private fun refreshFacts(
        target: String,
        perAppApplied: Boolean,
        perAppError: String?,
        privateDnsReadable: Boolean,
        privateDnsMode: String?,
        privateDnsSpecifier: String?,
        tunnelAddressAssigned: Boolean
    ) {
        val status = CdnRouterController.status.value
        val previous = diagFacts
        diagFacts = DiagFacts(
            perAppApplied = perAppApplied,
            perAppError = perAppError,
            targetPackage = target,
            targetUid = AndroidDiagnostics.targetUid(this, target),
            privateDnsReadable = privateDnsReadable,
            privateDnsMode = privateDnsMode,
            privateDnsSpecifier = privateDnsSpecifier,
            tunnelAddressAssigned = tunnelAddressAssigned,
            tunnelListenerBound = status.tunnelBound,
            routerPhase = status.phase.label,
            // M3.6: campos de contabilidade/perfis/processos não vêm do lifecycle — são preservados.
            uidTrafficAvailable = previous.uidTrafficAvailable,
            uidTrafficBytesSinceStart = previous.uidTrafficBytesSinceStart,
            uidTrafficGrew = previous.uidTrafficGrew,
            ipv6Profiles = previous.ipv6Profiles,
            targetDeclaredProcesses = previous.targetDeclaredProcesses
        )
    }

    /**
     * Amostra periódica do vigia (M3.6): contabilidade do UID do alvo desde a última amostra e
     * perfis IPv6 vistos. Crescimento do UID com TUN vazio pode apoiar PROBABLE para tráfego daquele
     * UID fora do TUN; a conta inclui helpers. Sem crescimento ou sem contadores, ausência e causa
     * permanecem inconclusivas.
     */
    private fun emitTrafficSample() {
        val uid = targetUidValue
        if (uid == null) return
        val stats = TrafficAccounting.snapshot(uid)
        val last = uidTrafficLast
        uidTrafficLast = stats
        val deltaWindow = last?.let { TrafficAccounting.delta(it, stats) }
        val deltaSession = uidTrafficStart?.let { TrafficAccounting.delta(it, stats) }
        val changed = last?.let { TrafficAccounting.grew(it, stats) } == true
        trafficSampleTicks++
        // Só imprime quando mudou (o evento interessante) ou a cada 5 ciclos (~100 s), para a
        // contabilidade não empurrar as outras evidências para fora do buffer de 400 linhas.
        if (changed || trafficSampleTicks % 5 == 1) {
            RequestLog.add("DIAG", TrafficAccounting.line("vigia", stats, deltaWindow))
            if (deltaSession != null) {
                RequestLog.add("DIAG", "contabilidade do UID $uid desde o início da sessão: $deltaSession")
            }
        }
        refreshTrafficFacts(stats)
        val counters = RequestLog.counters.value
        val profiles = ipv6Profiles.toList()
        if (counters.tunIpv6Packets > 0) {
            RequestLog.add(
                "DIAG",
                "IPv6 descartado até agora: descoberta-local=${counters.tunIpv6DescobertaLocal} " +
                    "multicast-outro=${counters.tunIpv6MulticastOutro} unicast=${counters.tunIpv6Unicast} " +
                    "(antes-do-WZM=${counters.tunIpv6AntesDoWzm}, depois=${counters.tunIpv6DepoisDoWzm}); " +
                    "perfis: " + (profiles.ifEmpty { listOf("nenhum") }.joinToString(" | "))
            )
            TunDiagnostics.ipv6DiscoveryInterpretation(counters)?.let { interpretation ->
                RequestLog.add("DIAG", interpretation)
            }
        }
        // Sem baseline (nenhuma conta disponível) NÃO se afirma nada: `grew(null, null)` não existe.
        val sessionGrew = uidTrafficStart?.let { TrafficAccounting.grew(it, stats) }
        val trafficNote = when {
            !stats.available ->
                "contabilidade por UID indisponível — não dá para afirmar se houve tráfego do app"
            sessionGrew == null ->
                "contabilidade por UID sem amostra inicial — não dá para afirmar se houve tráfego do app"
            sessionGrew && counters.tunPacketsTotal == 0 ->
                "a contabilidade do UID-alvo cresceu, mas nenhum pacote foi observado no TUN: PROBABLE que parte " +
                    "do tráfego desse UID não passou pelo TUN; pode incluir helper e não identifica rota/processo"
            !sessionGrew && counters.tunPacketsTotal == 0 ->
                "sem crescimento medido na contabilidade do UID-alvo e sem pacote observado no TUN; " +
                    "a ausência nesta amostra não prova ausência de tentativa/rede nem determina a causa"
            else -> null
        }
        if (trafficNote != null && loggedOnce.add("trafficNote|" + trafficNote.substringBefore(':'))) {
            RequestLog.add("DIAG", trafficNote)
        }
    }

    /** Escreve o quadro de evidências (VERIFIED/PROBABLE/HYPOTHESIS/UNKNOWN) no log. */
    private fun emitEvidenceBoard(trigger: String) {
        val facts = diagFacts.copy(
            tunnelListenerBound = CdnRouterController.status.value.tunnelBound,
            tunnelAddressAssigned = diagFacts.tunnelAddressAssigned,
            routerPhase = CdnRouterController.status.value.phase.label
        )
        RequestLog.add("DIAG", "quadro de evidências ($trigger) — nada aqui é causa presumida:")
        HypothesisBoard.lines(RequestLog.counters.value, facts).forEach { line ->
            RequestLog.add("DIAG", line)
        }
    }

    private fun loop() {
        val input = readStream ?: return
        val output = writeStream ?: return
        val buffer = ByteArray(CdnRouterConfig.MTU + 512)
        RequestLog.add("TUN", "loop do túnel iniciado (MTU ${CdnRouterConfig.MTU})")
        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running) RequestLog.add("TUN", "leitura do túnel falhou: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            if (length <= 0) continue
            try {
                handlePacket(buffer, length, output)
            } catch (e: Exception) {
                logThrottled("TUN", "erro ao processar pacote: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        RequestLog.add("TUN", "loop do túnel encerrado")
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        RequestLog.incTunPacketsTotal()
        when (val parsed = IpPacketParser.parse(packet, length)) {
            is PacketParse.Fault -> {
                RequestLog.incTunInvalidPacket()
                RequestLog.incTunDiscard()
                logThrottled("TUN", TunDiagnostics.faultLine(parsed))
            }
            is PacketParse.Ok -> handleParsed(parsed.header, packet, length, output)
        }
    }

    private fun handleParsed(header: PacketHeader, packet: ByteArray, length: Int, output: FileOutputStream) {
        // Classificação por versão/protocolo ANTES de qualquer decisão (IPv6 nunca é "inválido").
        when (header.version) {
            IpVersion.IPV4 -> RequestLog.incTunIpv4Packet()
            IpVersion.IPV6 -> RequestLog.incTunIpv6Packet()
            IpVersion.DESCONHECIDA -> Unit
        }
        when {
            header.isTcp -> RequestLog.incTunTcpPacket()
            header.isUdp -> RequestLog.incTunUdpPacket()
            header.isIcmp -> RequestLog.incTunIcmpPacket()
        }
        if (header.isCdnTargetV4) RequestLog.incTunIpv4ToCdnTarget()
        if (header.isCdnTargetV6) RequestLog.incTunIpv6ToCdnTarget()
        if (header.isCdnTargetV4) RequestLog.incTunToRedirect()

        watchdog.onPacket(System.currentTimeMillis())
        TunPolicy.observations(header).forEach { RequestLog.incTunObservation(it) }
        // M3.6: o IPv6 descartado é classificado (descoberta local × multicast × unicast) e separado
        // pelo marcador temporal do launcher; isso não identifica processo nem atribui autoria.
        if (header.isIpv6) {
            val category = TrafficClassifier.ipv6Category(header)
            RequestLog.incTunIpv6Category(category, RequestLog.isBeforeWzmStart(System.currentTimeMillis()))
            val profile = TrafficClassifier.ipv6Profile(header).label()
            if (ipv6Profiles.size < 32) ipv6Profiles.add(profile)
        }
        // M3.6: destino externo que casa com resposta DNS observada — a resolução passou pelo túnel.
        if (!header.isCdnTarget && header.dstPort != null && !header.isDnsPort) {
            dnsAnswers.match(header.dstAddress)?.let { entry ->
                RequestLog.incTunFluxoDestinoResolvido()
                logThrottled(
                    "TUN",
                    "destino ${header.dstAddress} consta de resposta DNS observada para ${entry.host} " +
                        "(${String.format(java.util.Locale.US, "%.1f", entry.ageSeconds(System.currentTimeMillis()))} s atrás, " +
                        "via ${entry.source}) — a resolução passou pelo túnel (autoria continua sendo do UID)"
                )
            }
        }
        val decision = TunPolicy.decide(header)

        when (decision.action) {
            TunAction.RESPOSTA_DNS -> handleDns(header, packet, length, output)
            TunAction.BOUNCE -> handleBounce(header, packet, length, output)
            TunAction.DESCARTE -> handleDiscard(header, packet, length, output, decision)
        }
    }

    /** DNS: registra consulta completa (nome, tipo, origem/destino, resposta, CDNI sim/não). */
    private fun handleDns(header: PacketHeader, packet: ByteArray, length: Int, output: FileOutputStream) {
        val payloadOffset = TunnelPackets.udpPayloadOffset(packet)
        if (length <= payloadOffset) return
        val dnsPayload = packet.copyOfRange(payloadOffset, length)
        RequestLog.incDnsQuery()
        val question = DnsMessage.parseQuery(dnsPayload, dnsPayload.size)
        val name = question?.name ?: "?"
        val type = question?.qType ?: -1
        val serverClass = if (header.dstAddress == CdnRouterConfig.VPN_DNS) "virtual-do-tunel"
        else "externo(${header.dstAddress})"
        val origin = "${header.srcAddress}:${header.srcPort ?: -1} -> ${header.dstAddress}:${header.dstPort ?: -1}"

        val intercepted = dnsResponder.answer(dnsPayload, dnsPayload.size)
        if (intercepted != null) {
            RequestLog.incDnsIntercepted()
            // M3.6: guarda o par nome→IP que NÓS devolvemos (é assim que o destino do jogo entra no
            // rastreio) — somente endereços, nenhum conteúdo de payload.
            val addresses = DnsMessage.extractARecords(intercepted, intercepted.size)
            if (addresses.isNotEmpty()) {
                dnsAnswers.record(question?.name ?: "?", addresses, source = "resposta virtual do túnel")
                RequestLog.incDnsRespostaRegistrada()
            }
            // M4.1: invariante do projeto — o DNS do túnel NUNCA devolve 127.0.0.1 (o destino é
            // CdnRouterConfig.REDIRECT_TO). Se isto disparar, a hipótese "cliente foi mandado ao
            // loopback" ganha fundamento e há regressão de configuração a corrigir.
            if (addresses.any { it == CdnRouterConfig.LOOPBACK_ADDRESS }) {
                RequestLog.incDnsRespostaParaLoopback()
                RequestLog.add(
                    "DNS",
                    "ATENÇÃO: resposta DNS apontou ${CdnRouterConfig.LOOPBACK_ADDRESS} (esperado " +
                        "${CdnRouterConfig.REDIRECT_TO}) — conferir CdnRouterConfig.REDIRECT_TO"
                )
            }
            watchdog.onCdnDns(System.currentTimeMillis())
            RequestLog.add(
                "DNS",
                "consulta: $name (tipo $type) $origin servidor=$serverClass -> " +
                    "RESPOSTA A=${CdnRouterConfig.REDIRECT_TO} [DNS CDNI recebido e interceptado: sim] " +
                    "(${intercepted.size} B)"
            )
            writePacket(
                output,
                TunnelPackets.buildUdpPacket(
                    header.dstAddress,
                    checkNotNull(header.dstPort) { "porta de origem ausente no pacote DNS" },
                    header.srcAddress,
                    checkNotNull(header.srcPort) { "porta do cliente ausente no pacote DNS" },
                    intercepted
                ),
                direction = "tunel->app"
            )
            return
        }

        if (dnsQueryLogBudget > 0) {
            dnsQueryLogBudget--
            RequestLog.add(
                "DNS",
                "consulta: $name (tipo $type) $origin servidor=$serverClass " +
                    "[DNS CDNI recebido e interceptado: não]"
            )
        }
        val upstream = upstreamDns.exchange(dnsPayload, dnsPayload.size)
        if (upstream == null) {
            logThrottled("DNS", "sem resposta para $name (encaminhamento falhou)")
            return
        }
        RequestLog.incDnsForwarded()
        // M3.6: guarda os endereços da resposta do DNS real (só endereços) para casar destinos depois.
        val upstreamAddresses = DnsMessage.extractARecords(upstream, upstream.size)
        if (upstreamAddresses.isNotEmpty()) {
            dnsAnswers.record(name, upstreamAddresses, source = "DNS real encaminhado pelo túnel")
            RequestLog.incDnsRespostaRegistrada()
        }
        logThrottled(
            "DNS",
            "$name (tipo $type) $origin servidor=$serverClass -> encaminhado ao DNS real " +
                "(não é host CDNI; resposta ${upstream.size} B; A=${upstreamAddresses.joinToString(",").ifEmpty { "sem registros A" }})"
        )
        writePacket(
            output,
            TunnelPackets.buildUdpPacket(
                header.dstAddress,
                checkNotNull(header.dstPort) { "porta de origem ausente no pacote DNS" },
                header.srcAddress,
                checkNotNull(header.srcPort) { "porta do cliente ausente no pacote DNS" },
                upstream
            ),
            direction = "tunel->app"
        )
    }

    /** Endereço LOCAL do aparelho (túnel ou loopback): devolve o pacote ao TUN (bounce). */
    private fun handleBounce(header: PacketHeader, packet: ByteArray, length: Int, output: FileOutputStream) {
        RequestLog.incTunBounce()
        if (header.isSyn) {
            watchdog.onTargetFlow()
            val ownerResult = AndroidDiagnostics.connectionOwnerForFlowResult(this, header)
            val owner = ConnectionOwnership.describe(ownerResult)
            val synthetic = RequestLog.isDuringSyntheticTest(System.currentTimeMillis())
            val ownerIsTargetUid = ownerResult.provesOwner && ownerResult.uid == targetUidValue
            if (ownerIsTargetUid && !synthetic) {
                RequestLog.incTunUidVerifiedFlow(
                    toCdniTarget = header.isCdnTargetV4 && header.dstPort == CdnRouterConfig.LOCAL_HTTPS_PORT
                )
                RequestLog.add(
                    "CDNI",
                    "fluxo do UID do app alvo confirmado (sem atribuição de processo/PID): $owner — ${header.brief()}"
                )
            } else if (ownerIsTargetUid) {
                RequestLog.add(
                    "CDNI",
                    "teste sintético: resultado UID-alvo observado, mas excluído da evidência WZM — " +
                        "$owner — ${header.brief()}"
                )
            }
            RequestLog.add(
                "CDNI",
                "TCP SYN ${header.srcAddress}:${header.srcPort ?: -1} -> " +
                    "${header.dstAddress}:${header.dstPort ?: -1} " +
                    "(${owner}) devolvido ao TUN -> listener HTTPS " +
                    "(bounce #${RequestLog.counters.value.tunBounces})"
            )
        }
        writePacket(output, packet, length, direction = "app->tun(origem)/tunel->app(bounce)")
    }

    private fun handleDiscard(
        header: PacketHeader,
        packet: ByteArray,
        length: Int,
        output: FileOutputStream,
        decision: TunDecision
    ) {
        RequestLog.incTunDiscard()
        val reason = decision.reason ?: TunDiscardReason.PROTO_NAO_SUPORTADO
        val reset = if (header.isTcp && !header.isIpv6) TunnelPackets.buildTcpReset(packet, length) else null
        val suffix = buildString {
            if (decision.note.isNotEmpty()) append(decision.note)
            if (reset != null) {
                if (isNotEmpty()) append(" ")
                append("respondido com RST (nada é inventado)")
            }
        }
        // IPv6 é registrado por extenso (uma vez por perfil de fluxo) — nunca como "inválido",
        // junto da ordem temporal do marcador de lançamento (não de criação de processo).
        if (header.isIpv6) {
            val relation = RequestLog.connectionOrigin(System.currentTimeMillis())
            val withRelation = (if (suffix.isEmpty()) "" else "$suffix ") + "• $relation"
            logThrottled("TUN", reason.line(header.brief(), withRelation))
        } else {
            logThrottled("TUN", reason.line(header.brief(), suffix))
        }
        if (reset != null) writePacket(output, reset, direction = "tunel->app(RST)")
    }

    private fun writePacket(
        output: FileOutputStream,
        packet: ByteArray,
        length: Int = packet.size,
        direction: String = "tunel->app"
    ) {
        try {
            output.write(packet, 0, length)
            output.flush()
            logThrottled(
                "TUN",
                "escrita no túnel: direcao=$direction ${length} B " +
                    "(metadados apenas; nenhum payload é registrado)"
            )
        } catch (e: Exception) {
            if (running) RequestLog.add("TUN", "escrita no túnel falhou: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun logThrottled(tag: String, message: String) {
        val key = "$tag|$message"
        if (loggedOnce.size > 400) loggedOnce.clear()
        if (loggedOnce.add(key)) RequestLog.add(tag, message)
    }

    private fun shutdown(reason: String) {
        if (running || tunnel != null) {
            RequestLog.add("VPN", "encerrando roteador local: $reason")
            emitEvidenceBoard("fim da sessão")
            RequestLog.add("DIAG", "resumo final da sessão: " + TunDiagnostics.summaryLine(RequestLog.counters.value))
        }
        running = false
        try {
            tunnel?.close()
        } catch (_: Exception) {
        }
        tunnel = null
        readStream = null
        writeStream = null
        worker?.interrupt()
        worker = null
        lifecycleThread?.interrupt()
        lifecycleThread = null
        CdnRouterController.onVpnStateChanged(false)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onRevoke() {
        shutdown("revogado pelo sistema")
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown("serviço destruído")
        super.onDestroy()
    }
}
