package com.wzm.launcher.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import java.net.Inet4Address
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
    fun connectionOwner(context: Context, socket: Socket): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "dono=INDISPONIVEL (getConnectionOwnerUid exige API 29+)"
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return "dono=INDISPONIVEL (ConnectivityManager ausente)"
        val local = toInet(socket.localSocketAddress)
        val remote = toInet(socket.remoteSocketAddress)
        val attempts = buildList {
            if (local != null && remote != null) {
                add(local to remote)
                add(remote to local)
            }
        }
        if (attempts.isEmpty()) return "dono=NAO_RESOLVIDO (endereços indisponíveis)"
        for ((first, second) in attempts) {
            val uid = runCatching { manager.getConnectionOwnerUid(6 /* IPPROTO_TCP */, first, second) }
                .getOrNull() ?: continue
            if (uid == Process.INVALID_UID) continue
            val packages = runCatching { context.packageManager.getPackagesForUid(uid) }
                .getOrNull()?.joinToString(",") ?: "?"
            return "dono=uid=$uid ($packages)"
        }
        return "dono=NAO_RESOLVIDO (getConnectionOwnerUid devolveu INVALID_UID)"
    }

    private fun toInet(address: SocketAddress?): InetSocketAddress? =
        (address as? InetSocketAddress)?.takeIf { it.address != null }

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
