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
    private val ownerDescription: (Socket) -> String = { "dono=NAO_RESOLVIDO (sem lookup neste ambiente)" }
) {

    data class BindEndpoint(val address: String, val port: Int) {
        override fun toString(): String = "$address:$port"
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
            try {
                val socket = factory.createServerSocket(
                    endpoint.port,
                    16,
                    InetAddress.getByName(endpoint.address)
                )
                serverSockets += socket
                bound += endpoint
                log("CDNI", "HTTPS local escutando em ${endpoint.address}:${endpoint.port}")
            } catch (e: Exception) {
                val failure = ListenerFailures.classify(e.javaClass.simpleName, e.message)
                log(
                    "CDNI",
                    "listener NÃO subiu em ${endpoint.address}:${endpoint.port}: motivo=${failure.code} " +
                        "(${e.javaClass.simpleName}: ${e.message}) — ${failure.hint}"
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
                "SEM listener em 10.111.222.1:443: o cliente que conectar no IP devolvido pelo DNS " +
                    "não será atendido por essa via — só o caminho 127.0.0.1:443 pode completar " +
                    "(motivo=ENDERECO_INDISPONIVEL no bind do endereço do túnel)"
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
            RequestLog.incTcpConnection()
            log(
                "CDNI",
                "tentativa de conexão em $endpoint (via $via): peer=$peer $owner — " +
                    "total=${RequestLog.counters.value.tcpConnections}"
            )
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
            log("CDNI?", "conexão não-TLS em $endpoint (classe ${client.javaClass.simpleName}) — tratada como HTTP em claro")
            relay.serve(client, client.remoteSocketAddress?.toString() ?: "?")
            return
        }
        try {
            sslSocket.soTimeout = 15_000
            sslSocket.startHandshake()
            RequestLog.incTlsOk()
            val sni = sniOf(sslSocket)
            log(
                "TLS",
                "handshake OK (SNI=${sni ?: "?"}, ${sslSocket.session.protocol}) — o cliente aceitou o certificado local"
            )
            relay.serve(sslSocket, sni ?: (client.remoteSocketAddress?.toString() ?: "?"))
        } catch (e: SSLHandshakeException) {
            RequestLog.incTlsFailed()
            val message = e.message ?: ""
            val failure = TlsTrust.analyze(message)
            // Linha com código estável (motivo=...) para leitura máquina/humana na tela VER LOGS.
            log(
                "TLS",
                "FALHA no handshake TLS em $endpoint: motivo=${failure.code} " +
                    "(${e.javaClass.simpleName}: $message) — ${failure.hint}"
            )
            if (failure.code == TlsFailure.CLIENT_CLEARTEXT) {
                relay.serve(client, client.remoteSocketAddress?.toString() ?: "?")
            }
        } catch (e: Exception) {
            RequestLog.incTlsFailed()
            log("TLS", "FALHA no handshake TLS em $endpoint: ${e.javaClass.simpleName}: ${e.message}")
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
        fun defaultEndpoints(): List<BindEndpoint> = listOf(
            BindEndpoint(CdnRouterConfig.LOCAL_BIND_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT),
            BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT),
            BindEndpoint(CdnRouterConfig.LOCAL_BIND_ADDRESS, CdnRouterConfig.LOCAL_FALLBACK_PORT)
        )
    }
}
