package com.wzm.launcher.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections

/**
 * Coleta de fatos do device para o diagnóstico do túnel (M3.3).
 *
 * Tudo aqui é **best-effort e explícito**: quando uma API não existe, não responde ou é
 * restrita, o log diz `INDISPONIVEL`/`NAO_RESOLVIDO` em vez de inventar um valor.
 * Nenhuma chamada altera o roteamento.
 */
object AndroidDiagnostics {

    private const val PREFS = "wzm-offline-diag"
    private const val KEY_SESSIONS = "sessions_total"

    /** (número desta sessão, quantas sessões já foram registradas antes). */
    fun sessionNumber(context: Context): Pair<Int, Int> {
        val prefs = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return 1 to 0
        val previous = runCatching { prefs.getInt(KEY_SESSIONS, 0) }.getOrDefault(0)
        val current = previous + 1
        runCatching { prefs.edit().putInt(KEY_SESSIONS, current).apply() }
        return current to previous
    }

    fun targetUid(context: Context, packageName: String): Int? = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    fun targetVersionName(context: Context, packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun launcherVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun launcherTargetSdk(context: Context): Int = context.applicationInfo.targetSdkVersion

    /** Fatos da sessão para o cabeçalho `[DIAG]` do log. */
    fun sessionFacts(
        context: Context,
        targetPackage: String,
        perAppApplied: Boolean,
        perAppError: String?,
        extra: List<String> = emptyList()
    ): SessionFacts {
        val (current, previous) = sessionNumber(context)
        val installed = isInstalled(context, targetPackage)
        return SessionFacts(
            sessionNumber = current,
            previousSessions = previous,
            launcherVersion = launcherVersion(context),
            launcherTargetSdk = launcherTargetSdk(context),
            targetPackage = targetPackage,
            targetInstalled = installed,
            targetVersionName = if (installed) targetVersionName(context, targetPackage) else null,
            targetUid = if (installed) targetUid(context, targetPackage) else null,
            perAppApplied = perAppApplied,
            perAppError = perAppError,
            extra = extra
        )
    }

    /**
     * Dono (UID/pacote) de uma conexão aceita no listener — responde "o processo está mesmo
     * na VPN per-app?" com evidência, em vez de suposição.
     *
     * `ConnectivityManager.getConnectionOwnerUid` existe desde a API 29; tentamos as duas
     * orientações do par local/remoto porque a API não documenta qual delas o netd indexa.
     */
    fun connectionOwner(context: Context, socket: Socket): String =
        ConnectionOwnership.describe(ConnectionOwnership.querySocket(context, socket))

    /**
     * Fatos para o veredito de origem (M4.1) de uma conexão aceita no listener: peer, uid resolvido
     * (ou não), janela de portas efêmeras deste processo e a marca do teste sintético.
     */
    fun loopbackOriginFacts(
        context: Context,
        socket: Socket,
        roleLoopback: Boolean,
        targetUid: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): LoopbackOrigin.Facts? {
        val remote = ConnectionOwnership.toInet(socket.remoteSocketAddress) ?: return null
        val peerAddress = remote.address?.hostAddress ?: return null
        val owner = ConnectionOwnership.querySocket(context, socket)
        return LoopbackOrigin.Facts(
            roleLoopback = roleLoopback,
            peerAddress = peerAddress,
            peerPort = remote.port,
            ownerUidResolvido = owner.uid,
            launcherUid = Process.myUid(),
            targetUid = targetUid,
            duranteTesteSintetico = RequestLog.isDuringSyntheticTest(nowMs),
            portVerdict = SelfPorts.verdictFor(remote.port)
        )
    }

    /**
     * Dono (UID/pacote) de um fluxo observado no TUN — responde "esse pacote é do WZM?" com a API
     * pública que o sistema usa para firewall/VPN (API 29+). Delega a classificação a
     * [ConnectionOwnership], que documenta o duplo significado de `INVALID_UID`.
     */
    fun connectionOwnerForFlow(context: Context, header: PacketHeader): String {
        val srcPort = header.srcPort ?: return "dono=NAO_RESOLVIDO (sem porta de origem)"
        val dstPort = header.dstPort ?: return "dono=NAO_RESOLVIDO (sem porta de destino)"
        val protocol = when {
            header.isTcp -> ConnectionOwnership.PROTOCOL_TCP
            header.isUdp -> ConnectionOwnership.PROTOCOL_UDP
            else -> return "dono=NAO_RESOLVIDO (protocolo ${header.protocolCode} nao suportado pela consulta)"
        }
        val sourceAddress = runCatching { InetAddress.getByName(header.srcAddress) }.getOrNull()
            ?: return "dono=NAO_RESOLVIDO (endereco de origem invalido)"
        val destinationAddress = runCatching { InetAddress.getByName(header.dstAddress) }.getOrNull()
            ?: return "dono=NAO_RESOLVIDO (endereco de destino invalido)"
        val result = ConnectionOwnership.query(
            context,
            protocol,
            InetSocketAddress(sourceAddress, srcPort),
            InetSocketAddress(destinationAddress, dstPort)
        )
        return ConnectionOwnership.describe(result)
    }

    /** `true` quando o endereço está atribuído a alguma interface do device. */
    fun isAddressAssigned(address: String = CdnRouterConfig.VPN_ADDRESS): Boolean =
        LocalHttpsServer.isAddressAssigned(address)

    data class AddressWait(val found: Boolean, val elapsedMs: Long)

    /**
     * Espera LIMITADA (nunca retry infinito) até [address] aparecer nas interfaces.
     * **Bloqueante**: deve rodar fora da thread principal (o serviço usa uma thread própria).
     */
    fun waitForAddress(
        address: String = CdnRouterConfig.VPN_ADDRESS,
        timeoutMs: Long = CdnRouterConfig.VPN_ADDRESS_READY_TIMEOUT_MS,
        pollMs: Long = CdnRouterConfig.VPN_ADDRESS_POLL_MS,
        sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) }
    ): AddressWait {
        val startedAt = System.currentTimeMillis()
        val effectivePoll = pollMs.coerceAtLeast(20L)
        while (true) {
            if (isAddressAssigned(address)) {
                return AddressWait(true, System.currentTimeMillis() - startedAt)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= timeoutMs) return AddressWait(false, elapsed)
            runCatching { sleep(minOf(effectivePoll, timeoutMs - elapsed)) }
        }
    }

    /**
     * DNS privado (DoT) do sistema: leitura **best-effort** de `Settings.Global`.
     * Isso é evidência do que está *configurado*, não prova de causa — ver [HypothesisBoard].
     */
    fun privateDns(): Triple<Boolean, String?, String?> {
        val resolver = appContext?.contentResolver
            ?: return Triple(false, null, null)
        val mode = runCatching { Settings.Global.getString(resolver, "private_dns_mode") }.getOrNull()
        val specifier = runCatching { Settings.Global.getString(resolver, "private_dns_specifier") }.getOrNull()
        if (mode == null) return Triple(false, null, null)
        return Triple(true, mode, specifier)
    }

    fun privateDnsLines(context: Context): List<String> {
        appContext = context.applicationContext
        val (readable, mode, specifier) = privateDns()
        if (!readable) {
            return listOf(
                "DNS privado (DoT): UNKNOWN — Settings.Global private_dns_mode não legível neste app " +
                    "(não se pode afirmar nem negar)"
            )
        }
        val detail = buildString {
            append("Settings.Global private_dns_mode=")
            append(mode)
            if (!specifier.isNullOrBlank()) append(" specifier=").append(specifier)
        }
        val effect = if (mode == "off") {
            "DNS privado desligado: não pode ser a explicação para consultas ausentes no túnel"
        } else {
            "efeito sobre o WZM: HYPOTHESIS (só contorna o DNS do túnel se o app deixar de usar " +
                "o resolvedor do sistema; depende das consultas vistas na tag DNS)"
        }
        return listOf("DNS privado (DoT) configurado: VERIFIED ($detail) — $effect")
    }

    @Volatile
    private var appContext: Context? = null

    /** Guarda o contexto do app para as consultas que não recebem `Context` por parâmetro. */
    fun remember(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Interfaces de rede do device que carregam o endereço do túnel.
     * É isto que explica EADDRNOTAVAIL (ou a ausência dele) no bind do listener.
     */
    fun tunnelInterfaceLines(expectedAddress: String = CdnRouterConfig.VPN_ADDRESS): List<String> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return listOf("interfaces de rede: INDISPONIVEL (NetworkInterface.getNetworkInterfaces falhou)")
        val lines = mutableListOf<String>()
        var found = false
        for (iface in interfaces) {
            val addresses = runCatching { Collections.list(iface.inetAddresses) }.getOrDefault(emptyList())
            val match = addresses.filterIsInstance<Inet4Address>().any { it.hostAddress == expectedAddress }
            if (!match) continue
            found = true
            val up = runCatching { iface.isUp }.getOrDefault(false)
            val names = addresses.joinToString(",") { it.hostAddress ?: "?" }
            lines += "interface $expectedAddress: ${iface.name} up=$up endereços=[$names]"
        }
        if (!found) {
            lines += "interface $expectedAddress: NÃO ENCONTRADA nas interfaces do device — " +
                "bind nesse endereço deve falhar (EADDRNOTAVAIL) e o caminho do túnel fica sem listener"
        }
        return lines
    }

    /** Rotas/DNS que o sistema realmente aplicou na rede VPN (via ConnectivityManager). */
    fun vpnNetworkLines(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return listOf("rede VPN: INDISPONIVEL (ConnectivityManager ausente)")
        val networks = runCatching { manager.allNetworks }.getOrNull()
            ?: return listOf("rede VPN: INDISPONIVEL (allNetworks falhou)")
        val lines = mutableListOf<String>()
        for (network in networks) {
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val link = runCatching { manager.getLinkProperties(network) }.getOrNull()
            val routes = link?.routes?.joinToString(",") { route ->
                route.destination?.toString() ?: "?"
            } ?: "?"
            val dns = link?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" } ?: "?"
            val iface = link?.interfaceName ?: "?"
            lines += "rede VPN: interface=$iface rotas=[$routes] dns=[$dns]"
        }
        if (lines.isEmpty()) lines += "rede VPN: nenhuma rede TRANSPORT_VPN visível para o app"
        return lines
    }
}
