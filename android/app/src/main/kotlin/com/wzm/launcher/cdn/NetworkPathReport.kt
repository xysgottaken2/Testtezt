package com.wzm.launcher.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * Raio-X do caminho de rede que o WZM está usando (M5.0) — **leitura, nunca altera roteamento**.
 *
 * Motivo: o logcat do dispositivo mostra
 * `NetdEventListenerService: DNS Requested by 8952, 10692(com.activision.callofduty.warzone), 7(NODATA)`
 * e nós não sabemos **qual rede é o netId 8952**. Este relatório imprime o netId de cada rede
 * (VPN, Wi-Fi, celular…), qual é a default e o estado do Always-on VPN, para comparar com esse
 * número. Se o netId da consulta não for o da nossa VPN, o WZM está resolvendo fora do túnel —
 * e nenhum ajuste de DNS/servidor local adianta sem mudar isso (Always-on VPN + bloquear
 * conexões sem VPN, que são configurações do aparelho, sem root e sem mexer no APK).
 *
 * Regras: best-effort; API ausente/restrita vira `INDISPONIVEL`, nunca um valor inventado.
 * Nenhuma chamada aqui altera TLS, confiança, autenticação ou o app do jogo.
 */
object NetworkPathReport {

    /** Chaves do AOSP (Settings.Secure) — leitura permitida a qualquer app. */
    private const val KEY_ALWAYS_ON_VPN_APP = "always_on_vpn_app"
    private const val KEY_LOCKDOWN = "always_on_vpn_lockdown"
    private const val KEY_LOCKDOWN_WHITELIST = "always_on_vpn_lockdown_whitelist"

    data class NetworkFacts(
        val netId: Int?,
        val transports: List<String>,
        val isDefault: Boolean,
        val dnsServers: List<String>
    ) {
        val isVpn: Boolean get() = transports.contains("VPN")
    }

    data class AlwaysOnState(val app: String?, val lockdown: Boolean, val whitelist: String?) {
        val isEmpty: Boolean get() = app.isNullOrBlank() && !lockdown && whitelist.isNullOrBlank()
    }

    // ------------------------------------------------------------- puro (testável)

    /**
     * `android.net.Network.toString()` devolve o netId em decimal (AOSP).
     * Qualquer outro formato vira `null` — nunca adivinhamos.
     */
    fun parseNetId(text: String?): Int? = text?.trim()?.toIntOrNull()

    fun transportsOf(capabilities: NetworkCapabilities?): List<String> {
        if (capabilities == null) return emptyList()
        val out = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out += "VPN"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out += "WIFI"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out += "CELLULAR"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out += "ETHERNET"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) out += "BLUETOOTH"
        return out
    }

    /** Renderização pura: mesma entrada, mesma saída (coberto por teste unitário). */
    fun render(
        facts: List<NetworkFacts>,
        alwaysOn: AlwaysOnState?,
        ownPackage: String
    ): List<String> {
        val lines = mutableListOf<String>()
        if (facts.isEmpty()) {
            lines += "caminho de rede: INDISPONIVEL (nenhuma rede enumerada)"
        }
        for (fact in facts.sortedWith(compareByDescending<NetworkFacts> { it.isDefault }
            .thenByDescending { it.isVpn }
            .thenBy { it.netId ?: Int.MAX_VALUE })) {
            val id = fact.netId?.toString() ?: "NAO_RESOLVIDO"
            val transports = if (fact.transports.isEmpty()) "[]" else fact.transports.sorted().joinToString(",")
            val dns = if (fact.dnsServers.isEmpty()) "[]" else fact.dnsServers.joinToString(",")
            lines += "rede netId=$id transportes=[$transports] default=${fact.isDefault} dns=[$dns]"
        }
        val vpn = facts.firstOrNull { it.isVpn }
        lines += if (vpn?.netId != null) {
            "netId da nossa VPN = ${vpn.netId} (compare com 'DNS Requested by <netId>' do logcat)"
        } else {
            "netId da nossa VPN = NAO_RESOLVIDO (nenhuma rede TRANSPORT_VPN visível)"
        }
        if (alwaysOn == null) {
            lines += "always-on VPN: INDISPONIVEL (Settings.Secure não acessível)"
        } else if (alwaysOn.isEmpty) {
            lines += "always-on VPN: desligado (recomendado: ligar para o launcher + bloquear conexões sem VPN)"
        } else {
            val isOurs = !alwaysOn.app.isNullOrBlank() && alwaysOn.app == ownPackage
            lines += "always-on VPN: app=${alwaysOn.app} nosso=$isOurs lockdown=${alwaysOn.lockdown} whitelist=[${alwaysOn.whitelist ?: ""}]"
        }
        return lines
    }

    // ----------------------------------------------------------------- framework

    fun facts(context: Context): List<NetworkFacts> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val defaultNetId = runCatching { manager.activeNetwork?.let { parseNetId(it.toString()) } }.getOrNull()
        val networks: Array<Network> = runCatching { manager.allNetworks }.getOrNull() ?: return emptyList()
        return networks.mapNotNull { network ->
            val netId = parseNetId(network.toString())
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            val transports = transportsOf(capabilities)
            if (transports.isEmpty() && netId == null) return@mapNotNull null
            val link = runCatching { manager.getLinkProperties(network) }.getOrNull()
            val dns = link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
            NetworkFacts(
                netId = netId,
                transports = transports,
                isDefault = netId != null && netId == defaultNetId,
                dnsServers = dns
            )
        }
    }

    fun alwaysOnState(context: Context): AlwaysOnState? {
        val resolver = context.contentResolver ?: return null
        return runCatching {
            val app = Settings.Secure.getString(resolver, KEY_ALWAYS_ON_VPN_APP)
            val lockdown = Settings.Secure.getInt(resolver, KEY_LOCKDOWN, 0) == 1
            val whitelist = Settings.Secure.getString(resolver, KEY_LOCKDOWN_WHITELIST)
            AlwaysOnState(app = app, lockdown = lockdown, whitelist = whitelist)
        }.getOrNull()
    }

    fun lines(context: Context): List<String> =
        render(facts(context), alwaysOnState(context), context.packageName)
}
