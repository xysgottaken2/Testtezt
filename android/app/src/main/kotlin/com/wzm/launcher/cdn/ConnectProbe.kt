package com.wzm.launcher.cdn

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Sonda CONNECT (M6) — existe para responder **uma** pergunta: o WZM respeita o proxy HTTP manual
 * do sistema (Ajustes → Wi-Fi → proxy)?
 *
 * Escopo deliberado — isto **não** é um proxy, nem um túnel, nem metade de um:
 *
 *  * escuta **em claro**, somente em [CdnRouterConfig.LOOPBACK_ADDRESS] (nunca `0.0.0.0`);
 *  * lê **a primeira linha** da requisição e nada mais (sem cabeçalhos, sem corpo);
 *  * detecta `CONNECT host:porta HTTP/1.x`;
 *  * registra método, host, porta, timestamp, peer e owner UID (M4.1, quando a API resolve);
 *  * responde `405 Method Not Allowed` e **fecha** a conexão.
 *
 * O que a sonda **não** faz, de propósito:
 *
 *  * não abre túnel — nunca responde `200 Connection established`;
 *  * não termina TLS e não toca no material de [TlsContextFactory];
 *  * não encaminha um único byte entre cliente e destino;
 *  * não altera DNS, TUN/VPN, [CdnRouteTable] nem o handler do `cdni.meta`.
 *
 * Limitação honesta: um `CONNECT` revela **host:porta**, nunca o path. O caminho
 * `/wzm/shard_cdn/android/_manifest/cdni.meta` só aparece depois do TLS, que aqui não é terminado.
 * Portanto esta sonda mede **transporte**, não entrega conteúdo.
 */
object ConnectProbeParser {

    const val METHOD = "CONNECT"

    /** Teto de leitura da primeira linha; acima disso a conexão é descartada. */
    const val MAX_REQUEST_LINE_BYTES = 4096

    data class ConnectRequest(
        /** Linha exata recebida, sem o final de linha — é isso que vai para o log. */
        val rawLine: String,
        val version: String,
        val host: String,
        val port: Int
    )

    /**
     * Detecta `CONNECT host:porta HTTP/1.x`.
     *
     * Devolve `null` para **qualquer** outra coisa: outro método, autoridade malformada (sem porta,
     * porta não numérica ou fora de 1..65535, host vazio) e versão diferente de HTTP/1.x. A sonda
     * nunca "adivinha" o alvo — não existe inferência aqui.
     */
    fun parseLine(line: String): ConnectRequest? {
        val rawLine = line.trimEnd('\r', '\n', ' ', '\t')
        val parts = rawLine.split(' ')
        if (parts.size != 3) return null
        val method = parts[0]
        val authority = parts[1]
        val version = parts[2]
        if (method != METHOD) return null
        if (!isHttp1x(version)) return null
        val target = splitAuthority(authority) ?: return null
        return ConnectRequest(
            rawLine = rawLine,
            version = version,
            host = target.first,
            port = target.second
        )
    }

    /**
     * Separa `host:porta`, aceitando também a forma literal IPv6 `[2001:db8::1]:443`.
     * Qualquer outro formato devolve `null`.
     */
    fun splitAuthority(authority: String): Pair<String, Int>? {
        if (authority.isEmpty()) return null
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 2) return null
            host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (!rest.startsWith(":")) return null
            portText = rest.substring(1)
        } else {
            val separator = authority.lastIndexOf(':')
            if (separator <= 0 || separator == authority.length - 1) return null
            host = authority.substring(0, separator)
            portText = authority.substring(separator + 1)
        }
        if (host.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port < 1 || port > 65535) return null
        return host to port
    }

    /**
     * Lê **só** a primeira linha, com teto de [MAX_REQUEST_LINE_BYTES]. Não lê cabeçalhos nem corpo:
     * a sonda não precisa deles e não deve consumir nada além do mínimo antes de fechar.
     */
    fun readRequestLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var count = 0
        while (count < MAX_REQUEST_LINE_BYTES) {
            val value = input.read()
            if (value < 0) break
            count++
            if (value == '\n'.code) break
            buffer.append(value.toChar())
        }
        return buffer.toString().takeIf { it.isNotBlank() }
    }

    private fun isHttp1x(version: String): Boolean =
        version.length == 8 && version.startsWith("HTTP/1.")
}

/**
 * Listener em claro da sonda: aceita, olha a primeira linha, registra, recusa e fecha.
 *
 * Nenhuma conexão sobrevive a [handle] — a sonda não mantém estado de túnel e não repassa bytes.
 */
class ConnectProbeServer(
    private val address: String = CdnRouterConfig.LOOPBACK_ADDRESS,
    private val port: Int = CdnRouterConfig.CONNECT_PROBE_PORT,
    private val log: (String, String) -> Unit = { tag, message -> RequestLog.add(tag, message) },
    /**
     * Texto do owner UID do peer (M4.1), já formatado por quem monta os fatos
     * ([ConnectionOwnership.describe]). `null` = mecanismo não configurado; nesse caso o log diz
     * explicitamente que a autoria não foi resolvida, em vez de omitir o campo.
     */
    private val ownerDescription: ((Socket) -> String)? = null
) {

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** Porta efetiva do listener (`0` pede porta efêmera — usado pelos testes em JVM). */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean get() = running && serverSocket != null

    fun start(): Boolean {
        if (running) return true
        val socket = try {
            ServerSocket(port, 8, InetAddress.getByName(address))
        } catch (e: Exception) {
            log(
                LOG_TAG,
                "sonda CONNECT não subiu em $address:$port: ${e.javaClass.simpleName}: ${e.message} — " +
                    "sem proxy detectável por esta via"
            )
            return false
        }
        serverSocket = socket
        running = true
        val thread = Thread({ acceptLoop(socket) }, "cdn-connect-probe")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        log(
            LOG_TAG,
            "sonda CONNECT escutando em claro em ${socket.inetAddress.hostAddress}:${socket.localPort} " +
                "(sem TLS, sem túnel, sem repasse de bytes) — para testar, aponte o proxy HTTP manual " +
                "do Wi-Fi para este endereço"
        )
        return true
    }

    fun stop() {
        running = false
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        val thread = acceptThread
        acceptThread = null
        runCatching { thread?.interrupt() }
        log(LOG_TAG, "sonda CONNECT encerrada")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) {
                    log(LOG_TAG, "accept encerrado: ${e.javaClass.simpleName}: ${e.message}")
                }
                return
            }
            handle(client)
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { connection ->
                connection.soTimeout = CONNECT_SOCKET_TIMEOUT_MS
                val peer = connection.remoteSocketAddress?.toString() ?: "?"
                val owner = runCatching { ownerDescription?.invoke(connection) }
                    .getOrElse { "dono=INDISPONIVEL (${it.javaClass.simpleName})" }
                    ?: "dono=NAO_RESOLVIDO (mecanismo de owner UID não configurado)"
                val line = try {
                    ConnectProbeParser.readRequestLine(connection.getInputStream())
                } catch (e: Exception) {
                    log(LOG_TAG, "sem primeira linha legível de $peer: ${e.javaClass.simpleName}")
                    null
                }
                val timestamp = System.currentTimeMillis()
                val request = line?.let { ConnectProbeParser.parseLine(it) }
                if (request == null) {
                    val method = line?.substringBefore(' ') ?: "(vazio)"
                    log(
                        LOG_TAG,
                        "pedido SEM CONNECT: metodo=$method linha=${line ?: "(vazia)"} · " +
                            "epochMs=$timestamp · cliente=$peer · $owner · resposta=405 " +
                            "(a sonda não atende nada; não é túnel)"
                    )
                } else {
                    log(
                        LOG_TAG,
                        "CONNECT detectado: metodo=${ConnectProbeParser.METHOD} host=${request.host} " +
                            "porta=${request.port} versao=${request.version} · epochMs=$timestamp · " +
                            "cliente=$peer · $owner · linha-exata=\"${request.rawLine}\" · " +
                            "resposta=405 (sem túnel: a sonda não encaminha bytes)"
                    )
                }
                writeMinimalResponse(connection)
            }
        } catch (e: Exception) {
            log(LOG_TAG, "falha ao atender a conexão da sonda: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun writeMinimalResponse(connection: Socket) {
        val output = BufferedOutputStream(connection.getOutputStream())
        output.write(REFUSAL_RESPONSE)
        output.flush()
    }

    companion object {

        /** Tag exclusiva da sonda (filtro `PROXY` na tela de logs). */
        const val LOG_TAG = "PROXY"

        /** Timeout de leitura da primeira linha; a sonda nunca fica presa em um peer silencioso. */
        const val CONNECT_SOCKET_TIMEOUT_MS = 5_000

        /**
         * `405` é a recusa mínima e segura: diz que o método não é atendido **sem nunca** sugerir
         * que existe um túnel (`200 Connection established` levaria o cliente a enviar um
         * `ClientHello` que não terminamos, produzindo ruído em vez de evidência).
         */
        val REFUSAL_RESPONSE: ByteArray = HttpResponses.build(405, "text/plain", ByteArray(0))
    }
}
