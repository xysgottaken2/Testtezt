package com.wzm.launcher.cdn

/**
 * Rotas explícitas entregues a `VpnService.Builder`.
 *
 * Isto descreve só os prefixos configurados por `addRoute`: não simula a tabela local do Android,
 * exceções por UID, `Network.bindSocket`, `VpnService.protect`, nem prova que um pacote específico
 * chegou ao TUN. A allow-list per-app é outro filtro independente.
 */
object VpnCapturePlan {
    data class Route(val address: String, val prefixLength: Int)

    /** Mesmo plano consumido por [CdnVpnService]; atualmente apenas a sub-rede virtual IPv4 /24. */
    val routes: List<Route> = listOf(
        Route(CdnRouterConfig.VPN_ROUTE, CdnRouterConfig.VPN_ROUTE_PREFIX)
    )

    val hasDefaultRoute: Boolean get() = routes.any { it.prefixLength == 0 }

    val hasExplicitIpv6Route: Boolean get() = routes.any { ':' in it.address }

    /**
     * Testa se um literal IP casa com alguma rota explícita do plano. Nome/hostname não é resolvido
     * (não faz DNS). Isso é auditoria do plano, não previsão da decisão final do kernel.
     */
    fun matchesExplicitRoute(destination: String): Boolean {
        val destinationBytes = addressBytes(destination) ?: return false
        return routes.any { route ->
            val routeBytes = addressBytes(route.address) ?: return@any false
            prefixMatches(routeBytes, destinationBytes, route.prefixLength)
        }
    }

    private fun prefixMatches(network: ByteArray, address: ByteArray, prefixLength: Int): Boolean {
        if (network.size != address.size) return false
        val bitCount = network.size * 8
        if (prefixLength !in 0..bitCount) return false
        val fullBytes = prefixLength / 8
        for (index in 0 until fullBytes) {
            if (network[index] != address[index]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (network[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }

    /** Accepta IPv4 dotted decimal estrito e IPv6 literal; hostname nunca é passado ao resolver. */
    private fun addressBytes(value: String): ByteArray? {
        if (value.contains(':')) {
            return runCatching { java.net.InetAddress.getByName(value).address }
                .getOrNull()
                ?.takeIf { it.size == 16 }
        }
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return ByteArray(4) { octets[it].toByte() }
    }
}
