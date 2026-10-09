package com.wzm.launcher.cdn

/**
 * Ciclo de vida explícito do roteador local (M3.4).
 *
 * Motivo: na evidência de 2026-10-04 o listener foi iniciado **antes** de o endereço do túnel
 * existir, o bind em `10.111.222.1:443` falhou com EADDRNOTAVAIL e a única saída foi
 * `127.0.0.1:443`. A ordem correta é:
 *
 * ```
 * VPN_STARTING -> VPN_READY -> LOCAL_SERVER_STARTING -> LOCAL_SERVER_READY -> ROUTER_READY
 * ```
 *
 * regra dura: **só** se declara [RouterPhase.LOCAL_SERVER_READY] com o listener no endereço do túnel
 * efetivamente ativo; se ele for impossível, o estado **não** avança para `ROUTER_READY` e a limitação
 * fica registrada (nada de "pronto" falso). O listener de loopback continua existindo como caminho
 * **separado** de diagnóstico.
 *
 * Pura (Kotlin puro, sem Android) para ser testável em JVM.
 */
enum class RouterPhase(val label: String) {
    PARADO("PARADO"),
    VPN_STARTING("VPN_STARTING"),
    VPN_READY("VPN_READY"),
    LOCAL_SERVER_STARTING("LOCAL_SERVER_STARTING"),
    LOCAL_SERVER_READY("LOCAL_SERVER_READY"),
    ROUTER_READY("ROUTER_READY"),
    ERRO("ERRO")
}

class RouterLifecycle(private val log: (String) -> Unit = {}) {

    @Volatile
    var phase: RouterPhase = RouterPhase.PARADO
        private set

    @Volatile
    var lastReason: String = "inicial"
        private set

    /** O endereço do túnel está atribuído a uma interface (checado por NetworkInterface). */
    @Volatile
    var tunnelAddressAssigned: Boolean = false
        private set

    /** O listener no endereço do túnel (10.111.222.1:443) está ativo. */
    @Volatile
    var tunnelListenerBound: Boolean = false
        private set

    /** Registro do motivo quando o listener do túnel não sobe (limitação documentada). */
    @Volatile
    var tunnelListenerMissingReason: String? = null
        private set

    private val events = mutableListOf<String>()

    fun history(): List<String> = synchronized(events) { events.toList() }

    /** Descrição curta para a UI/log: fase + por qual caminho o servidor atende. */
    fun describe(): String = buildString {
        append(phase.label)
        when {
            tunnelListenerBound -> append(" (listener no endereço do túnel + loopback)")
            phase == RouterPhase.LOCAL_SERVER_READY || phase == RouterPhase.ROUTER_READY ->
                append(" (somente loopback — ver limitação registrada)")
            else -> Unit
        }
    }

    fun onVpnStarting(reason: String = "consentimento/pedido enviado ao sistema"): Boolean =
        transition(RouterPhase.VPN_STARTING, reason)

    /**
     * A VPN está pronta. [tunnelAddressAssigned] diz se `10.111.222.1` já está atribuído
     * (resultado da espera limitada por [CdnRouterConfig.VPN_ADDRESS_READY_TIMEOUT_MS]).
     */
    fun onVpnReady(tunnelAddressAssigned: Boolean, reason: String = ""): Boolean {
        this.tunnelAddressAssigned = tunnelAddressAssigned
        val detail = buildString {
            append(CdnRouterConfig.VPN_ADDRESS)
            if (tunnelAddressAssigned) {
                append(" atribuído a uma interface")
            } else {
                append(" NÃO atribuído dentro do tempo limite (")
                    .append(CdnRouterConfig.VPN_ADDRESS_READY_TIMEOUT_MS)
                    .append(" ms) — o bind direto no endereço do túnel deve falhar (EADDRNOTAVAIL)")
            }
            if (reason.isNotEmpty()) append(" · ").append(reason)
        }
        return transition(RouterPhase.VPN_READY, detail)
    }

    fun onLocalServerStarting(reason: String = "iniciando listener no endereço do túnel"): Boolean =
        transition(RouterPhase.LOCAL_SERVER_STARTING, reason)

    /**
     * O servidor local subiu. [tunnelListenerBound] só pode ser true se o bind no endereço do
     * túnel realmente aconteceu (o chamador obtém isso de `LocalHttpsServer.boundEndpoints`).
     */
    fun onLocalServerReady(tunnelListenerBound: Boolean, endpoints: String): Boolean {
        this.tunnelListenerBound = tunnelListenerBound
        tunnelListenerMissingReason = if (tunnelListenerBound) null else
            "listener em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} não subiu " +
                "(endereço atribuído=$tunnelAddressAssigned); mantido apenas o listener de loopback " +
                "${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} (caminho de diagnóstico)"
        val detail = "listeners=[$endpoints] direto-pelo-tunel=$tunnelListenerBound"
        val moved = transition(RouterPhase.LOCAL_SERVER_READY, detail)
        if (moved && !tunnelListenerBound) {
            log(
                "LISTENER_DO_TUNEL_AUSENTE: ${tunnelListenerMissingReason} — " +
                    "ROUTER_READY NÃO é declarado (Estado: ${phase.label})"
            )
        }
        return moved
    }

    /** Só declara ROUTER_READY com o listener do túnel ativo. */
    fun onRouterReady(): Boolean {
        if (!tunnelListenerBound) {
            log(
                "não é possível declarar ROUTER_READY: listener no endereço do túnel ausente " +
                    "(limitação registrada; o caminho do loopback segue separado)"
            )
            return false
        }
        return transition(RouterPhase.ROUTER_READY, "listener no endereço do túnel ativo")
    }

    fun onError(message: String): Boolean = transition(RouterPhase.ERRO, message)

    fun onStopped(reason: String = "parada solicitada"): Boolean {
        tunnelAddressAssigned = false
        tunnelListenerBound = false
        tunnelListenerMissingReason = null
        return transition(RouterPhase.PARADO, reason)
    }

    private fun transition(next: RouterPhase, reason: String): Boolean {
        val current = phase
        if (!allowed(current, next)) {
            log("transição recusada: ${current.label} -> ${next.label} ($reason)")
            return false
        }
        phase = next
        lastReason = reason
        val line = "estado do roteador: ${current.label} -> ${next.label} ($reason)"
        synchronized(events) {
            events += line
            if (events.size > 64) events.removeAt(0)
        }
        log(line)
        return true
    }

    private fun allowed(current: RouterPhase, next: RouterPhase): Boolean = when (current) {
        RouterPhase.PARADO -> next == RouterPhase.VPN_STARTING || next == RouterPhase.PARADO
        RouterPhase.VPN_STARTING -> next in setOf(RouterPhase.VPN_READY, RouterPhase.ERRO, RouterPhase.PARADO)
        RouterPhase.VPN_READY -> next in setOf(RouterPhase.LOCAL_SERVER_STARTING, RouterPhase.ERRO, RouterPhase.PARADO)
        RouterPhase.LOCAL_SERVER_STARTING -> next in setOf(RouterPhase.LOCAL_SERVER_READY, RouterPhase.ERRO, RouterPhase.PARADO)
        RouterPhase.LOCAL_SERVER_READY -> next in setOf(RouterPhase.ROUTER_READY, RouterPhase.ERRO, RouterPhase.PARADO)
        RouterPhase.ROUTER_READY -> next in setOf(RouterPhase.ERRO, RouterPhase.PARADO)
        RouterPhase.ERRO -> next in setOf(RouterPhase.PARADO, RouterPhase.VPN_STARTING)
    }
}
