package com.wzm.launcher.cdn

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Servidor HTTPS local (M3) — usa TCP/TLS do próprio kernel, com um certificado nosso.
 *
 * Endereços: SOMENTE endereços específicos do dispositivo (endereço do túnel e loopback).
 * Nunca 0.0.0.0 (ver decisão de escopo: servidor só local).
 *
 * O objetivo é fazer as requisições CDNI do WZM chegarem aqui e aparecerem no log do launcher.
 */
class LocalHttpsServer(
    private val tlsMaterial: TlsMaterial? = null,
    private val endpoints: List<BindEndpoint> = defaultEndpoints(),
    private val relay: TcpRelay = TcpRelay(),
    private val log: (String, String) -> Unit = { tag, message -> RequestLog.add(tag, message) },
    private val tlsContextOverride: SSLContext? = null,
    private val maxConcurrentConnections: Int = 8,
    /**
     * Dono (UID/pacote) do socket cliente — em Android usa `getConnectionOwnerUid` (M3.3).
     * Responde "o processo está mesmo na VPN per-app?" com evidência, não com suposição.
     */
    private val ownerDescription: (Socket) -> String = { "dono=NAO_RESOLVIDO (sem lookup neste ambiente)" },
    /**
     * Tentativas LIMITADAS de bind em endereço que ainda não existe (EADDRNOTAVAIL enquanto a
     * interface tun sobe). Nunca é um retry cego: cada tentativa é logada e há um teto.
     */
    private val tunnelBindAttempts: Int = CdnRouterConfig.TUNNEL_BIND_ATTEMPTS,
    private val tunnelBindRetryDelayMs: Long = CdnRouterConfig.TUNNEL_BIND_RETRY_DELAY_MS,
    private val addressAssigned: (String) -> Boolean = { LocalHttpsServer.isAddressAssigned(it) },
    private val sleep: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    /** Ponto de injeção do bind (testes em JVM simulam EADDRNOTAVAIL→sucesso sem tocar na rede). */
    private val bindOverride: ((BindEndpoint) -> ServerSocket)? = null
) {

    /** Papel do listener: o do túnel é o caminho principal; o de loopback é diagnóstico separado. */
    enum class EndpointRole { TUNEL_PRIMARIO, TUNEL_FALLBACK, LOOPBACK_DIAGNOSTICO, OUTRO }

    data class BindEndpoint(val address: String, val port: Int) {
        override fun toString(): String = "$address:$port"

        val role: EndpointRole
            get() = when {
                address == CdnRouterConfig.VPN_ADDRESS &&
                    port == CdnRouterConfig.LOCAL_HTTPS_PORT -> EndpointRole.TUNEL_PRIMARIO
                address == CdnRouterConfig.VPN_ADDRESS -> EndpointRole.TUNEL_FALLBACK
                address == CdnRouterConfig.LOOPBACK_ADDRESS -> EndpointRole.LOOPBACK_DIAGNOSTICO
                else -> EndpointRole.OUTRO
            }
    }

    @Volatile
    var boundEndpoints: List<BindEndpoint> = emptyList()
        private set

    @Volatile
    private var running = false

    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val acceptThreads = CopyOnWriteArrayList<Thread>()
    private val connections = Semaphore(maxConcurrentConnections, true)

    /** Porta "principal" (443 se disponível, senão a de fallback). */
    val primaryPort: Int
        get() = boundEndpoints.firstOrNull { it.port == CdnRouterConfig.LOCAL_HTTPS_PORT }?.port
            ?: boundEndpoints.firstOrNull()?.port ?: -1

    val isRunning: Boolean get() = running && serverSockets.isNotEmpty()

    fun start(): Boolean {
        if (running) return true
        val context = tlsContextOverride ?: run {
            val material = tlsMaterial
            if (material == null) {
                log("CDNI", "servidor não iniciado: material TLS ausente")
                return false
            }
            try {
                TlsContextFactory.create(material)
            } catch (e: Exception) {
                log(
                    "CDNI",
                    "FALHA ao carregar o certificado local (${CdnRouterConfig.CERT_ASSET}): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                return false
            }
        }
        val factory = context.serverSocketFactory
        val bound = mutableListOf<BindEndpoint>()
        for (endpoint in endpoints) {
            val socket = bindWithBoundedRetry(factory, endpoint)
            if (socket != null) {
                serverSockets += socket
                bound += endpoint
                log(
                    "CDNI",
                    "HTTPS local escutando em ${endpoint.address}:${endpoint.port} " +
                        "(papel=${endpoint.role.name})"
                )
            }
        }
        if (bound.isEmpty()) {
            log("CDNI", "NENHUM listener HTTPS local ativo — o boot do WZM não terá para onde ir")
            return false
        }
        val tunnelBound = bound.any {
            it.address == CdnRouterConfig.VPN_ADDRESS && it.port == CdnRouterConfig.LOCAL_HTTPS_PORT
        }
        val loopbackBound = bound.any { it.address == CdnRouterConfig.LOOPBACK_ADDRESS }
        log(
            "CDNI",
            "listeners ativos=[${bound.joinToString()}] direto-pelo-tunel(10.111.222.1:443)=$tunnelBound " +
                "loopback(127.0.0.1:443)=$loopbackBound"
        )
        if (!tunnelBound) {
            log(
                "CDNI",
                "LIMITAÇÃO DOCUMENTADA: sem listener em ${CdnRouterConfig.VPN_ADDRESS}:" +
                    "${CdnRouterConfig.LOCAL_HTTPS_PORT} (bind falhou; endereço atribuído=" +
                    "${addressAssigned(CdnRouterConfig.VPN_ADDRESS)}) — o cliente que conectar no IP " +
                    "devolvido pelo DNS não será atendido por essa via; o caminho de loopback " +
                    "${CdnRouterConfig.LOOPBACK_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT} continua " +
                    "separado, apenas para diagnóstico. O estado do roteador NÃO avança para ROUTER_READY."
            )
        }
        boundEndpoints = bound
        running = true
        for ((index, socket) in serverSockets.withIndex()) {
            val endpoint = bound.getOrNull(index) ?: continue
            val thread = Thread({ acceptLoop(socket, endpoint) }, "cdn-https-accept-$index")
            thread.isDaemon = true
            acceptThreads += thread
            thread.start()
        }
        return true
    }

    /**
     * Bind com tentativas LIMITADAS: só repete quando o endereço ainda não está atribuído
     * (EADDRNOTAVAIL). Qualquer outro erro de bind é definitivo e sai do loop imediatamente.
     */
    private fun bindWithBoundedRetry(
        factory: javax.net.ssl.SSLServerSocketFactory,
        endpoint: BindEndpoint
    ): ServerSocket? {
        val attempts = if (endpoint.role == EndpointRole.LOOPBACK_DIAGNOSTICO) 1
        else tunnelBindAttempts.coerceAtLeast(1)
        var lastFailure: ListenerFailure? = null
        var lastMessage: String? = null
        for (attempt in 1..attempts) {
            if (attempt > 1) {
                log(
                    "CDNI",
                    "tentativa $attempt/$attempts de bind em ${endpoint.address}:${endpoint.port} " +
                        "(endereço atribuído=${addressAssigned(endpoint.address)}; espera " +
                        "${tunnelBindRetryDelayMs} ms — retry limitado, nunca infinito)"
                )
                runCatching { sleep(tunnelBindRetryDelayMs) }
            }
            try {
                val override = bindOverride
                if (override != null) return override(endpoint)
                return factory.createServerSocket(
                    endpoint.port,
                    16,
                    InetAddress.getByName(endpoint.address)
                )
            } catch (e: Exception) {
                val failure = ListenerFailures.classify(e.javaClass.simpleName, e.message)
                lastFailure = failure
                lastMessage = "${e.javaClass.simpleName}: ${e.message}"
                log(
                    "CDNI",
                    "listener NÃO subiu em ${endpoint.address}:${endpoint.port} (tentativa $attempt/$attempts): " +
                        "motivo=${failure.code} ($lastMessage) — ${failure.hint}"
                )
                if (failure != ListenerFailure.ENDERECO_INDISPONIVEL) break
            }
        }
        if (lastFailure != null && endpoint.role != EndpointRole.LOOPBACK_DIAGNOSTICO) {
            log(
                "CDNI",
                "bind definitivo em ${endpoint.address}:${endpoint.port} esgotado após as tentativas: " +
                    "motivo=${lastFailure.code} ($lastMessage)"
            )
        }
        return null
    }

    fun stop() {
        running = false
        for (socket in serverSockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        serverSockets.clear()
        boundEndpoints = emptyList()
        for (thread in acceptThreads) thread.interrupt()
        acceptThreads.clear()
    }

    private fun acceptLoop(serverSocket: ServerSocket, endpoint: BindEndpoint) {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (running) log("CDNI", "accept em $endpoint encerrado: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            val peer = client.remoteSocketAddress?.toString() ?: "?"
            // "via" diz por qual endereço a conexão entrou; "dono" diz qual processo conectou.
            // O teste de 2026-10-04 registrou 5 conexões em 127.0.0.1:443 sem dono identificado —
            // por isso o par via+dono passou a ser obrigatório no log (M3.3).
            val via = ListenerFailures.via(endpoint.address)
            val owner = runCatching { ownerDescription(client) }
                .getOrElse { "dono=NAO_RESOLVIDO (${it.javaClass.simpleName})" }
            // M3.5: papel do listener + relação temporal com o WZM iniciado.
            // Loopback é DIAGNÓSTICO SECUNDÁRIO: não conta como evidência de tráfego do WZM.
            val now = System.currentTimeMillis()
            val relation = RequestLog.wzmRelation(now)
            if (endpoint.role == EndpointRole.LOOPBACK_DIAGNOSTICO) {
                RequestLog.incTcpConnectionLoopback(RequestLog.isBeforeWzmStart(now))
                log(
                    "CDNI",
                    "tentativa de conexão em $endpoint (via $via, papel=${endpoint.role.name}, " +
                        "DIAGNÓSTICO SECUNDÁRIO): peer=$peer $owner · epochMs=$now · $relation — " +
                        "este caminho NÃO conta como evidência de tráfego do WZM"
                )
            } else {
                RequestLog.incTcpConnectionTunel()
                log(
                    "CDNI",
                    "conexão aceita em $endpoint (via $via, papel=${endpoint.role.name}): peer=$peer " +
                        "$owner · epochMs=$now · $relation · total-no-túnel=" +
                        "${RequestLog.counters.value.tcpConnectionsTunel}"
                )
            }
            if (!connections.tryAcquire()) {
                log("CDNI", "conexões simultâneas no limite ($maxConcurrentConnections) — conexão descartada")
                closeQuietly(client)
                continue
            }
            val thread = Thread({
                try {
                    handle(client, endpoint)
                } finally {
                    connections.release()
                    closeQuietly(client)
                }
            }, "cdn-https-conn")
            thread.isDaemon = true
            thread.start()
        }
    }

    private fun handle(client: Socket, endpoint: BindEndpoint) {
        val sslSocket = client as? SSLSocket
        if (sslSocket == null) {
            log(
                "CDNI?",
                "conexão não-TLS em $endpoint (papel=${endpoint.role.name}, " +
                    "classe ${client.javaClass.simpleName}, peer=${client.remoteSocketAddress}) — " +
                    "tratada como HTTP em claro"
            )
            relay.serve(
                client,
                client.remoteSocketAddress?.toString() ?: "?",
                "papel=${endpoint.role.name}"
            )
            return
        }
        val loopback = endpoint.role == EndpointRole.LOOPBACK_DIAGNOSTICO
        val peer = client.remoteSocketAddress?.toString() ?: "?"
        val relation = RequestLog.wzmRelation(System.currentTimeMillis())
        val roleNote = if (loopback) {
            "papel=${endpoint.role.name} (DIAGNÓSTICO: não é evidência de tráfego do WZM)"
        } else {
            "papel=${endpoint.role.name} (caminho do túnel: é este que pode promover evidência do WZM)"
        }
        try {
            sslSocket.soTimeout = 15_000
            sslSocket.startHandshake()
            if (loopback) RequestLog.incTlsOkLoopback() else RequestLog.incTlsOkTunel()
            val sni = sniOf(sslSocket)
            log(
                "TLS",
                "handshake OK em $endpoint (peer=$peer, $roleNote, SNI=${sni ?: "?"}, " +
                    "${sslSocket.session.protocol}) · $relation — o cliente aceitou o certificado local"
            )
            relay.serve(
                sslSocket,
                peer,
                "papel=${endpoint.role.name} · $relation"
            )
        } catch (e: SSLHandshakeException) {
            if (loopback) RequestLog.incTlsFailedLoopback() else RequestLog.incTlsFailedTunel()
            val message = e.message ?: ""
            val failure = TlsTrust.analyze(message)
            // Linha com código estável (motivo=...) para leitura máquina/humana na tela VER LOGS.
            log(
                "TLS",
                "FALHA no handshake TLS em $endpoint (peer=$peer, $roleNote): motivo=${failure.code} " +
                    "(${e.javaClass.simpleName}: $message) — ${failure.hint} · $relation"
            )
            if (failure.code == TlsFailure.CLIENT_CLEARTEXT) {
                relay.serve(client, peer, "papel=${endpoint.role.name} · $relation")
            }
        } catch (e: Exception) {
            if (loopback) RequestLog.incTlsFailedLoopback() else RequestLog.incTlsFailedTunel()
            log(
                "TLS",
                "FALHA no handshake TLS em $endpoint (peer=$peer, $roleNote): " +
                    "${e.javaClass.simpleName}: ${e.message} · $relation"
            )
        }
    }

    private fun sniOf(socket: SSLSocket): String? = runCatching {
        val session = socket.handshakeSession as? ExtendedSSLSession ?: return@runCatching null
        session.requestedServerNames.filterIsInstance<SNIHostName>().firstOrNull()?.asciiName
    }.getOrNull()

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {

        /** O endereço está atribuído a alguma interface do device? (usado para explicar EADDRNOTAVAIL) */
        fun isAddressAssigned(address: String): Boolean {
            if (address == CdnRouterConfig.LOOPBACK_ADDRESS) return true
            return runCatching {
                val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                interfaces.any { iface ->
                    java.util.Collections.list(iface.inetAddresses).any { it.hostAddress == address }
                }
            }.getOrDefault(false)
        }

        fun defaultEndpoints(): List<BindEndpoint> = listOf(
            BindEndpoint(CdnRouterConfig.LOCAL_BIND_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT),
            BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT),
            BindEndpoint(CdnRouterConfig.LOCAL_BIND_ADDRESS, CdnRouterConfig.LOCAL_FALLBACK_PORT)
        )
    }
}
