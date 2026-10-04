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

        if (wzmPackage.isNullOrBlank()) {
            RequestLog.add("VPN", "AVISO: sem pacote alvo; o túnel DNS valeria para todos os apps")
        } else {
            try {
                builder.addAllowedApplication(wzmPackage)
                RequestLog.add("VPN", "per-app: somente $wzmPackage usará o túnel")
            } catch (e: PackageManager.NameNotFoundException) {
                val message = "pacote $wzmPackage não encontrado: ${e.message}"
                RequestLog.add("VPN", "ERRO: $message")
                CdnRouterController.onVpnStateChanged(false, message)
                shutdown("pacote alvo ausente")
                return
            }
        }

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
        if (!TunnelPackets.isValid(packet, length)) {
            logThrottled("TUN", "pacote não-IPv4/inválido descartado (${length} B)")
            return
        }
        when (TunnelPackets.protocol(packet)) {
            TunnelPackets.PROTO_UDP -> handleUdp(packet, length, output)
            TunnelPackets.PROTO_TCP -> handleTcp(packet, length, output)
            else -> logThrottled(
                "TUN",
                "protocolo ${TunnelPackets.protocol(packet)} de ${TunnelPackets.srcAddress(packet)} -> " +
                    "${TunnelPackets.dstAddress(packet)} descartado (túnel existe só para DNS/CDNI)"
            )
        }
    }

    private fun handleUdp(packet: ByteArray, length: Int, output: FileOutputStream) {
        val offset = TunnelPackets.transportOffset(packet)
        val srcAddress = TunnelPackets.srcAddress(packet)
        val dstAddress = TunnelPackets.dstAddress(packet)
        val srcPort = TunnelPackets.srcPort(packet, offset)
        val dstPort = TunnelPackets.dstPort(packet, offset)
        if (dstPort != CdnRouterConfig.DNS_PORT) {
            logThrottled("TUN", "UDP ${srcAddress}:${srcPort} -> ${dstAddress}:${dstPort} descartado (não é DNS)")
            return
        }
        val payloadOffset = TunnelPackets.udpPayloadOffset(packet)
        if (length <= payloadOffset) return
        val dnsPayload = packet.copyOfRange(payloadOffset, length)
        RequestLog.incDnsQuery()

        val intercepted = dnsResponder.answer(dnsPayload, dnsPayload.size)
        if (intercepted != null) {
            RequestLog.incDnsIntercepted()
            val question = DnsMessage.parseQuery(dnsPayload, dnsPayload.size)
            RequestLog.add(
                "DNS",
                "${question?.name ?: "?"} (tipo ${question?.qType ?: -1}) -> ${CdnRouterConfig.REDIRECT_TO} [interceptado]"
            )
            writePacket(output, TunnelPackets.buildUdpPacket(dstAddress, dstPort, srcAddress, srcPort, intercepted))
            return
        }

        val question = DnsMessage.parseQuery(dnsPayload, dnsPayload.size)
        val upstream = upstreamDns.exchange(dnsPayload, dnsPayload.size)
        if (upstream == null) {
            logThrottled("DNS", "sem resposta para ${question?.name ?: "consulta desconhecida"} (encaminhamento falhou)")
            return
        }
        // Diagnóstico: registra (uma vez por nome) que a consulta NÃO era do CDNI e foi encaminhada.
        // Foi a ausência desse dado que deixou dúvida na evidência do device de 2026-10-04
        // (5 conexões TCP com dnsIntercepted=0) — ver docs/research/m3-cdni-integration.md §6.1.
        logThrottled(
            "DNS",
            "${question?.name ?: "consulta"} (tipo ${question?.qType ?: -1}) -> encaminhado ao DNS real " +
                "(não é host CDNI; resposta ${upstream.size} B)"
        )
        writePacket(output, TunnelPackets.buildUdpPacket(dstAddress, dstPort, srcAddress, srcPort, upstream))
    }

    private fun handleTcp(packet: ByteArray, length: Int, output: FileOutputStream) {
        val offset = TunnelPackets.transportOffset(packet)
        val srcAddress = TunnelPackets.srcAddress(packet)
        val dstAddress = TunnelPackets.dstAddress(packet)
        val srcPort = TunnelPackets.srcPort(packet, offset)
        val dstPort = TunnelPackets.dstPort(packet, offset)
        val isSyn = TunnelPackets.hasFlag(packet, offset, TunnelPackets.FLAG_SYN)

        if (dstAddress == CdnRouterConfig.VPN_ADDRESS || dstAddress == CdnRouterConfig.LOOPBACK_ADDRESS) {
            // O destino é um endereço LOCAL do aparelho: devolver o pacote à interface faz a pilha
            // TCP do kernel entregá-lo ao nosso listener :443 (garante o caminho mesmo quando a
            // tabela de rotas da VPN vence a tabela `local`).
            if (isSyn) {
                RequestLog.add(
                    "CDNI",
                    "TCP SYN ${srcAddress}:${srcPort} -> ${dstAddress}:${dstPort} " +
                        "(devolvido à pilha local p/ o listener HTTPS)"
                )
            }
            writePacket(output, packet, length)
            return
        }

        val reset = TunnelPackets.buildTcpReset(packet, length)
        if (reset != null) {
            if (isSyn) {
                logThrottled(
                    "TUN",
                    "TCP ${dstAddress}:${dstPort} sem atendimento local -> RST (nada é inventado)"
                )
            }
            writePacket(output, reset)
        } else {
            logThrottled("TUN", "TCP ${dstAddress}:${dstPort} ignorado")
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
