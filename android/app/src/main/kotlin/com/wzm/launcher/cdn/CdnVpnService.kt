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
            .addRoute(CdnRouterConfig.VPN_ROUTE, CdnRouterConfig.VPN_ROUTE_PREFIX)

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
            routerPhase = status.phase.label
        )
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
        val origin = "${header.srcAddress}:${header.srcPort} -> ${header.dstAddress}:${header.dstPort}"

        val intercepted = dnsResponder.answer(dnsPayload, dnsPayload.size)
        if (intercepted != null) {
            RequestLog.incDnsIntercepted()
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
                    header.dstAddress, header.dstPort, header.srcAddress, header.srcPort, intercepted
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
        logThrottled(
            "DNS",
            "$name (tipo $type) $origin servidor=$serverClass -> encaminhado ao DNS real " +
                "(não é host CDNI; resposta ${upstream.size} B)"
        )
        writePacket(
            output,
            TunnelPackets.buildUdpPacket(
                header.dstAddress, header.dstPort, header.srcAddress, header.srcPort, upstream
            ),
            direction = "tunel->app"
        )
    }

    /** Endereço LOCAL do aparelho (túnel ou loopback): devolve o pacote ao TUN (bounce). */
    private fun handleBounce(header: PacketHeader, packet: ByteArray, length: Int, output: FileOutputStream) {
        RequestLog.incTunBounce()
        if (header.isSyn) {
            watchdog.onTargetFlow()
            val owner = AndroidDiagnostics.connectionOwnerForFlow(this, header)
            if (owner.contains("dono=uid=")) {
                RequestLog.incTunUidVerifiedFlow()
                if (diagFacts.targetPackage.isNotEmpty() && owner.contains(diagFacts.targetPackage)) {
                    RequestLog.add(
                        "CDNI",
                        "fluxo do app alvo confirmado: $owner — ${header.brief()}"
                    )
                }
            }
            RequestLog.add(
                "CDNI",
                "TCP SYN ${header.srcAddress}:${header.srcPort} -> ${header.dstAddress}:${header.dstPort} " +
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
        // IPv6 é registrado por extenso (uma vez por perfil de fluxo) — nunca como "inválido".
        logThrottled("TUN", reason.line(header.brief(), suffix))
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
