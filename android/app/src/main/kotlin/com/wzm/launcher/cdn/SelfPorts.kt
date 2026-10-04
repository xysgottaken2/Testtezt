package com.wzm.launcher.cdn

import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Portas que o **próprio processo do launcher** acabou de receber do kernel (M4.1).
 *
 * Serve para uma dedução limitada e explícita: quando uma conexão chega no listener de loopback com
 * porta de origem conhecida, dá para dizer se essa porta está na janela efêmera que o kernel acabou de
 * entregar a este processo. **Não é prova de autoria** (a janela é compartilhada por todo o aparelho):
 * é `PROBABLE`, e o texto no log diz exatamente isso.
 *
 * Também guarda as portas de origem de conexões que o launcher abriu **de propósito** (teste
 * sintético / teste de controle): essas são `VERIFIED` como do próprio processo, porque o processo
 * registrou a porta que ele mesmo usou.
 */
object SelfPorts {

    /** Janela efêmera observada pelo processo (min/max das portas que o kernel entregou a nós). */
    data class Window(val min: Int, val max: Int, val samples: Int) {
        val valid: Boolean get() = min in 1..65535 && max in min..65535 && samples > 0

        fun contains(port: Int): Boolean = valid && port in min..max

        fun label(): String = if (valid) "janela-do-processo=$min..$max ($samples amostra(s))"
        else "janela-do-processo=INDISPONIVEL ($samples amostra(s))"
    }

    /** Veredito de uma porta de origem. Nunca é "prova": a janela é compartilhada no aparelho. */
    enum class PortVerdict(val label: String) {
        REGISTRADA_PELO_PROCESSO("porta registrada pelo proprio processo (VERIFIED como nossa)"),
        NA_JANELA_DO_PROCESSO("porta dentro da janela efemera observada neste processo (PROBABLE, nao prova)"),
        FORA_DA_JANELA("porta fora da janela observada (indicio de OUTRO processo, nao prova)"),
        INDETERMINADO("sem janela calibrada — porta nao ajuda a decidir")
    }

    private val lock = Any()
    private var window: Window = Window(0, 0, 0)
    private val registered = linkedSetOf<Int>()

    /** Injeta a janela (usado pelos testes e pela calibração real). */
    fun install(newWindow: Window) {
        synchronized(lock) { window = newWindow }
    }

    /** Registra uma porta de origem que este processo abriu de propósito. */
    fun register(port: Int) {
        if (port in 1..65535) synchronized(lock) { registered += port }
    }

    fun rememberAll(ports: Iterable<Int>) = ports.forEach { register(it) }

    fun snapshot(): Window = synchronized(lock) { window }

    fun registeredPorts(): Set<Int> = synchronized(lock) { registered.toSet() }

    fun verdictFor(peerPort: Int): PortVerdict = synchronized(lock) {
        when {
            peerPort in registered -> PortVerdict.REGISTRADA_PELO_PROCESSO
            window.contains(peerPort) -> PortVerdict.NA_JANELA_DO_PROCESSO
            window.valid -> PortVerdict.FORA_DA_JANELA
            else -> PortVerdict.INDETERMINADO
        }
    }

    fun reset() {
        synchronized(lock) {
            window = Window(0, 0, 0)
            registered.clear()
        }
    }

    /**
     * Calibração real: o kernel entrega portas efêmeras a quem faz `bind(0)`; coletamos algumas e
     * fechamos os sockets na hora (nenhum tráfego é gerado). Só a janela min..max é guardada.
     */
    fun calibrate(count: Int = 8, binder: (() -> ServerSocket)? = null): Window {
        val ports = mutableListOf<Int>()
        repeat(count.coerceIn(1, 64)) {
            val socket = try {
                binder?.invoke() ?: ServerSocket(0, 0, InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 0).address)
            } catch (_: Exception) {
                null
            }
            if (socket != null) {
                ports += socket.localPort
                runCatching { socket.close() }
            }
        }
        val result = if (ports.isEmpty()) Window(0, 0, 0)
        else Window(ports.min(), ports.max(), ports.size)
        install(result)
        return result
    }
}
