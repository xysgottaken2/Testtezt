package com.wzm.launcher.cdn

import java.net.ServerSocket
import java.net.Socket

/**
 * Evidência limitada sobre sockets que o próprio processo abriu (M4.1).
 *
 * Uma porta sozinha não identifica um processo: ephemeral ports são reutilizáveis e compartilhados
 * pelo aparelho. Por isso a evidência forte guarda a tupla completa do socket cliente
 * (endereço/porta local + remoto) e casa uma única vez com o socket retornado por `accept()`; a entrada
 * expira rapidamente. A janela min/max continua apenas PROBABLE e nunca atribui autoria.
 */
object SelfPorts {

    /** Tupla orientada como o socket cliente que o launcher abriu. */
    data class ConnectionIdentity(
        val localAddress: String,
        val localPort: Int,
        val remoteAddress: String,
        val remotePort: Int
    ) {
        val valid: Boolean
            get() = localAddress.isNotBlank() && localPort in 1..65535 &&
                remoteAddress.isNotBlank() && remotePort in 1..65535
    }

    /** Janela efêmera observada pelo processo (min/max das portas que o kernel entregou a nós). */
    data class Window(val min: Int, val max: Int, val samples: Int) {
        val valid: Boolean get() = min in 1..65535 && max in min..65535 && samples > 0

        fun contains(port: Int): Boolean = valid && port in min..max

        fun label(): String = if (valid) "janela-efimera-observada=$min..$max ($samples amostra(s))"
        else "janela-efimera-observada=INDISPONIVEL ($samples amostra(s))"
    }

    /** Veredito de uma porta de origem. A janela fora do registro exato nunca identifica processo. */
    enum class PortVerdict(val label: String) {
        TUPLA_REGISTRADA_PELO_PROCESSO(
            "tupla completa registrada pelo proprio processo (VERIFIED para esta conexao)"
        ),
        NA_JANELA_EFIMERA_OBSERVADA(
            "porta na janela efemera observada neste processo (PROBABLE, compartilhada, nao prova)"
        ),
        FORA_DA_AMOSTRA(
            "porta fora da janela amostrada (sem conclusao sobre processo)"
        ),
        INDETERMINADO("sem janela calibrada — porta nao ajuda a decidir")
    }

    data class AcceptedSocketEvidence(val portVerdict: PortVerdict, val registeredPid: Int? = null)

    private data class Registration(val processPid: Int?, val atMillis: Long)

    private const val REGISTERED_TTL_MS = 30_000L
    private const val MAX_REGISTERED_CONNECTIONS = 64

    private val lock = Any()
    private var window: Window = Window(0, 0, 0)
    private val registered = linkedMapOf<ConnectionIdentity, Registration>()
    /** Accepted tuples checked before the client thread registered; blocks a late/stale exact match. */
    private val acceptedBeforeRegistration = linkedMapOf<ConnectionIdentity, Long>()

    /** Injeta a janela (usado pelos testes e pela calibração real). */
    fun install(newWindow: Window) {
        synchronized(lock) { window = newWindow }
    }

    /**
     * Registra uma conexão cliente aberta pelo launcher. Deve ser chamada assim que `connect()` retorna,
     * quando endereço local/porta efêmera e peer já são conhecidos. Como o listener pode aceitar antes do
     * registro, uma corrida só pode perder o match exato e cair para janela PROBABLE/INDETERMINADO; não
     * promovemos peer por porta/tempo. Se a checagem do accept já ocorreu, o registro atrasado é
     * recusado para não ficar pendente e casar com uma conexão futura. A tupla completa e o TTL protegem
     * contra atribuição por porta reutilizada; o registro não deve ser descrito como pré-registro.
     */
    fun register(socket: Socket, atMillis: Long = System.currentTimeMillis()): Boolean {
        val identity = identityForSocket(socket) ?: return false
        val processPid = runCatching { android.os.Process.myPid() }.getOrNull()
        return register(identity, atMillis, processPid = processPid)
    }

    /** Entrada pura para testes; [identity] é a tupla cliente local -> servidor remoto. */
    fun register(
        identity: ConnectionIdentity,
        atMillis: Long = System.currentTimeMillis(),
        processPid: Int? = null
    ): Boolean {
        if (!identity.valid) return false
        return synchronized(lock) {
            expireOldEntries(atMillis)
            val key = identity.normalized()
            if (acceptedBeforeRegistration.remove(key) != null) {
                return@synchronized false
            }
            registered[key] = Registration(processPid?.takeIf { it > 0 }, atMillis)
            while (registered.size > MAX_REGISTERED_CONNECTIONS) {
                registered.remove(registered.keys.first())
            }
            true
        }
    }

    /**
     * Classifica o socket aceito, invertendo-o conceitualmente para a tupla cliente -> servidor.
     * Um match exato é consumido uma vez; sem match, só a janela efêmera pode produzir PROBABLE.
     */
    fun evidenceForAcceptedSocket(socket: Socket, atMillis: Long = System.currentTimeMillis()): AcceptedSocketEvidence {
        val peer = ConnectionOwnership.toInet(socket.remoteSocketAddress)
            ?: return AcceptedSocketEvidence(PortVerdict.INDETERMINADO)
        val local = ConnectionOwnership.toInet(socket.localSocketAddress)
            ?: return AcceptedSocketEvidence(PortVerdict.INDETERMINADO)
        val peerAddress = peer.address?.hostAddress
            ?: return AcceptedSocketEvidence(PortVerdict.INDETERMINADO)
        val localAddress = local.address?.hostAddress
            ?: return AcceptedSocketEvidence(PortVerdict.INDETERMINADO)
        return evidenceForAcceptedConnection(
            peerAddress = peerAddress,
            peerPort = peer.port,
            listenerAddress = localAddress,
            listenerPort = local.port,
            atMillis = atMillis
        )
    }

    fun verdictForAcceptedSocket(socket: Socket, atMillis: Long = System.currentTimeMillis()): PortVerdict =
        evidenceForAcceptedSocket(socket, atMillis).portVerdict

    /** Variante pura para auditoria/testes de correspondência da tupla completa. */
    fun evidenceForAcceptedConnection(
        peerAddress: String,
        peerPort: Int,
        listenerAddress: String,
        listenerPort: Int,
        atMillis: Long = System.currentTimeMillis()
    ): AcceptedSocketEvidence = synchronized(lock) {
        expireOldEntries(atMillis)
        val acceptedPeerIdentity = ConnectionIdentity(
            localAddress = peerAddress,
            localPort = peerPort,
            remoteAddress = listenerAddress,
            remotePort = listenerPort
        ).normalized()
        val registration = registered.remove(acceptedPeerIdentity)
        if (registration != null) {
            return@synchronized AcceptedSocketEvidence(
                PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO,
                registration.processPid
            )
        }
        acceptedBeforeRegistration[acceptedPeerIdentity] = atMillis
        while (acceptedBeforeRegistration.size > MAX_REGISTERED_CONNECTIONS) {
            acceptedBeforeRegistration.remove(acceptedBeforeRegistration.keys.first())
        }
        val verdict = when {
            window.contains(peerPort) -> PortVerdict.NA_JANELA_EFIMERA_OBSERVADA
            window.valid -> PortVerdict.FORA_DA_AMOSTRA
            else -> PortVerdict.INDETERMINADO
        }
        AcceptedSocketEvidence(verdict)
    }

    fun verdictForAcceptedConnection(
        peerAddress: String,
        peerPort: Int,
        listenerAddress: String,
        listenerPort: Int,
        atMillis: Long = System.currentTimeMillis()
    ): PortVerdict = evidenceForAcceptedConnection(
        peerAddress, peerPort, listenerAddress, listenerPort, atMillis
    ).portVerdict

    /** Snapshot de auditoria, sem expor timestamps internos. */
    fun registeredConnections(): Set<ConnectionIdentity> = synchronized(lock) { registered.keys.toSet() }

    fun snapshot(): Window = synchronized(lock) { window }

    fun reset() {
        synchronized(lock) {
            window = Window(0, 0, 0)
            registered.clear()
            acceptedBeforeRegistration.clear()
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
                binder?.invoke() ?: ServerSocket(0, 0, java.net.InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 0).address)
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

    private fun identityForSocket(socket: Socket): ConnectionIdentity? {
        val local = ConnectionOwnership.toInet(socket.localSocketAddress) ?: return null
        val remote = ConnectionOwnership.toInet(socket.remoteSocketAddress) ?: return null
        val localAddress = local.address?.hostAddress ?: return null
        val remoteAddress = remote.address?.hostAddress ?: return null
        return ConnectionIdentity(localAddress, local.port, remoteAddress, remote.port)
            .takeIf { it.valid }
    }

    private fun ConnectionIdentity.normalized(): ConnectionIdentity = copy(
        localAddress = localAddress.lowercase(),
        remoteAddress = remoteAddress.lowercase()
    )

    private fun expireOldEntries(nowMs: Long) {
        val cutoff = nowMs - REGISTERED_TTL_MS
        registered.entries.removeAll { (_, registration) ->
            registration.atMillis < cutoff || registration.atMillis > nowMs
        }
        acceptedBeforeRegistration.entries.removeAll { (_, acceptedAt) ->
            acceptedAt < cutoff || acceptedAt > nowMs
        }
    }
}
