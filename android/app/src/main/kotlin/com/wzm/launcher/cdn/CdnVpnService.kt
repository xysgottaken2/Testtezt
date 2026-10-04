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
 * VpnService do launcher (M3): NÃO encaminha tráfego para nenhum servidor remoto.
 *
 * O que ele faz:
 *  - cria uma interface tun com endereço [CdnRouterConfig.VPN_ADDRESS] e DNS [CdnRouterConfig.VPN_DNS];
 *  - restringe o túnel aos apps da lista (WZM) — `addAllowedApplication`;
 *  - responde em userspace as consultas DNS dos hosts CDNI comprovados, devolvendo
 *    [CdnRouterConfig.REDIRECT_TO] (o próprio endereço do túnel, local no aparelho);
 *  - encaminha as demais consultas DNS para resolvedores reais (sockets `protect()`ed);
 *  - devolve ao cliente ("bounce") os pacotes TCP endereçados ao endereço do túnel, para que a
 *    pilha TCP local do Android os entregue ao nosso listener HTTPS em :443;
 *  - rejeita com RST o que não temos como atender (nunca finge uma resposta).
 */
class CdnVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.wzm.launcher.cdn.action.START"
        const val ACTION_STOP = "com.wzm.launcher.cdn.action.STOP"
        const val EXTRA_WZM_PACKAGE = "wzm_package"

        /** Quantas consultas DNS do túnel aparecem por extenso no log (depois, 1x por nome). */
        const val DNS_QUERY_LOG_BUDGET = 12

        /** Intervalo do vigia (os limiares de inatividade são 60 s). */
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
    private var readStream: FileInputStream? = null
    private var writeStream: FileOutputStream? = null

    @Volatile
    private var running = false

    private val dnsResponder = DnsResponder()
    private lateinit var upstreamDns: UpstreamDns
    private var watchdogThread: Thread? = null

    /** Vigia de atividade: distingue "não usou o túnel" de "usou e falhou" (M3.3). */
    private val watchdog = TunActivityWatchdog()

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

        // Cabeçalho de diagnóstico da sessão (M3.3): prova qual app entrou na VPN, se é a
        // primeira execução e quais destinos o túnel espera. Sem isso, dois testes no mesmo
        // device ficam indistinguíveis (foi o que aconteceu em 2026-10-04).
        val target = wzmPackage ?: "(sem pacote alvo)"
        SessionReport.lines(AndroidDiagnostics.sessionFacts(this, target, perAppApplied, perAppError))
            .forEach { line -> RequestLog.add("DIAG", line) }

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
        watchdog.start(System.currentTimeMillis())
        watchdogThread = Thread({ watchdogLoop() }, "cdn-diag-watchdog").also { it.isDaemon = true; it.start() }
        AndroidDiagnostics.tunnelInterfaceLines().forEach { line -> RequestLog.add("DIAG", line) }
        AndroidDiagnostics.vpnNetworkLines(this).forEach { line -> RequestLog.add("DIAG", line) }
        RequestLog.add(
            "DIAG",
            "túnel pronto: se o app alvo resolver um host CDNI, o destino esperado é " +
                "${CdnRouterConfig.REDIRECT_TO}:${CdnRouterConfig.LOCAL_HTTPS_PORT} (bounce) e as consultas " +
                "DNS devem aparecer na tag DNS; inatividade e resumo saem a cada ${WATCHDOG_INTERVAL_MS / 1000} s"
        )
        CdnRouterController.onVpnStateChanged(true)
        RequestLog.add(
            "VPN",
            "túnel ativo: DNS ${CdnRouterConfig.VPN_DNS}, rota ${CdnRouterConfig.VPN_ROUTE}/" +
                "${CdnRouterConfig.VPN_ROUTE_PREFIX}, ${CdnRouterConfig.INTERCEPT_HOSTS.joinToString()} -> " +
                "${CdnRouterConfig.REDIRECT_TO}"
        )
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
        val view = TunDiagnostics.view(packet, length)
        if (view == null) {
            RequestLog.incTunDiscard()
            logThrottled("TUN", TunDiscardReason.PACOTE_INVALIDO.line(null, "lido=${length} B"))
            return
        }
        RequestLog.incTunPacket()
        watchdog.onPacket(System.currentTimeMillis())
        if (view.isRedirectDest) RequestLog.incTunToRedirect()
        when (view.protocol) {
            TunnelPackets.PROTO_UDP -> handleUdp(packet, length, view, output)
            TunnelPackets.PROTO_TCP -> handleTcp(packet, length, view, output)
            else -> {
                RequestLog.incTunDiscard()
                logThrottled("TUN", TunDiscardReason.PROTO_NAO_SUPORTADO.line(view))
            }
        }
    }

    private fun handleUdp(packet: ByteArray, length: Int, view: TunPacketView, output: FileOutputStream) {
        if (!view.isDnsPort) {
            RequestLog.incTunDiscard()
            logThrottled("TUN", TunDiscardReason.UDP_PORTA_NAO_DNS.line(view))
            return
        }
        val payloadOffset = TunnelPackets.udpPayloadOffset(packet)
        if (length <= payloadOffset) return
        val dnsPayload = packet.copyOfRange(payloadOffset, length)
        RequestLog.incDnsQuery()
        val question = DnsMessage.parseQuery(dnsPayload, dnsPayload.size)
        val name = question?.name ?: "?"
        if (dnsQueryLogBudget > 0) {
            dnsQueryLogBudget--
            RequestLog.add(
                "DNS",
                "consulta DNS no túnel: $name (tipo ${question?.qType ?: -1}) de " +
                    "${view.srcAddress}:${view.srcPort} -> ${view.dstAddress}:${view.dstPort}"
            )
        }

        val intercepted = dnsResponder.answer(dnsPayload, dnsPayload.size)
        if (intercepted != null) {
            RequestLog.incDnsIntercepted()
            watchdog.onCdnDns(System.currentTimeMillis())
            RequestLog.add(
                "DNS",
                "$name (tipo ${question?.qType ?: -1}) -> ${CdnRouterConfig.REDIRECT_TO} " +
                    "[DNS CDNI recebido e interceptado]"
            )
            writePacket(
                output,
                TunnelPackets.buildUdpPacket(
                    view.dstAddress, view.dstPort, view.srcAddress, view.srcPort, intercepted
                )
            )
            return
        }

        val upstream = upstreamDns.exchange(dnsPayload, dnsPayload.size)
        if (upstream == null) {
            logThrottled("DNS", "sem resposta para $name (encaminhamento falhou)")
            return
        }
        RequestLog.incDnsForwarded()
        logThrottled(
            "DNS",
            "$name (tipo ${question?.qType ?: -1}) -> encaminhado ao DNS real " +
                "(não é host CDNI; resposta ${upstream.size} B)"
        )
        writePacket(
            output,
            TunnelPackets.buildUdpPacket(view.dstAddress, view.dstPort, view.srcAddress, view.srcPort, upstream)
        )
    }

    private fun handleTcp(packet: ByteArray, length: Int, view: TunPacketView, output: FileOutputStream) {
        if (view.isLocalDest) {
            // Endereço LOCAL do aparelho (túnel ou loopback): devolver o pacote ao TUN faz a pilha
            // TCP do kernel entregá-lo ao nosso listener HTTPS (bounce).
            RequestLog.incTunBounce()
            if (view.isSyn) {
                RequestLog.add(
                    "CDNI",
                    "TCP SYN ${view.srcAddress}:${view.srcPort} -> ${view.dstAddress}:${view.dstPort} " +
                        "(devolvido ao TUN -> listener HTTPS, bounce #${RequestLog.counters.value.tunBounces})"
                )
            }
            writePacket(output, packet, length)
            return
        }

        val reset = TunnelPackets.buildTcpReset(packet, length)
        if (reset != null) {
            RequestLog.incTunDiscard()
            if (view.isSyn) {
                logThrottled(
                    "TUN",
                    TunDiscardReason.TCP_SEM_ATENDIMENTO.line(view, "respondido com RST (nada é inventado)")
                )
            }
            writePacket(output, reset)
        } else {
            RequestLog.incTunDiscard()
            logThrottled("TUN", "TCP ${view.dstAddress}:${view.dstPort} ignorado (sem RST seguro para este pacote)")
        }
    }

    /** Vigia de atividade: roda a cada [WATCHDOG_INTERVAL_MS] e escreve no log o que faltou. */
    private fun watchdogLoop() {
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
        }
    }

    private fun writePacket(output: FileOutputStream, packet: ByteArray, length: Int = packet.size) {
        try {
            output.write(packet, 0, length)
            output.flush()
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
        watchdogThread?.interrupt()
        watchdogThread = null
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
