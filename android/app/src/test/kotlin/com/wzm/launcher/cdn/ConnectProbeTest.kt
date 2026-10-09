package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * M6 — sonda CONNECT: o **único** objetivo é descobrir se o cliente (WZM) respeita o proxy HTTP
 * manual do sistema. Nenhum teste aqui afirma que um `CONNECT` recebido seja do WZM: sem owner
 * UID/tupla (M4.1), a autoria continua `UNKNOWN` — inclusive no teste que injeta um UID.
 *
 * Coberto:
 *
 *  1. o parser/detector de `CONNECT host:porta HTTP/1.x` — o que aceita e o que rejeita, caso a caso;
 *  2. o listener em claro em loopback: responde `405`, fecha a conexão e **não** encaminha bytes;
 *  3. o log traz método, host, porta, timestamp, peer e owner (quando resolvido).
 */
class ConnectProbeTest {

    private val captured = mutableListOf<String>()
    private var server: ConnectProbeServer? = null

    @Before
    fun setUp() {
        captured.clear()
        RequestLog.clear()
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
        RequestLog.clear()
    }

    /** Sonda em porta efêmera de loopback, com log capturado e owner UID injetado (M4.1). */
    private fun startProbe(): ConnectProbeServer {
        val created = ConnectProbeServer(
            port = 0,
            log = { tag, message -> captured.add("[$tag] $message") },
            ownerDescription = { "dono=uid=10692 (com.activision.callofduty.warzone)" }
        )
        server = created
        return created
    }

    private fun send(port: Int, requestLine: String): String {
        val client = Socket()
        client.soTimeout = 5_000
        client.use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.getOutputStream().write((requestLine + "\r\nHost: prod.cdni.callofduty.com:443\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            // readBytes() só retorna quando o servidor fecha — é a prova de que não há túnel.
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    private fun logLineContaining(fragment: String): String =
        captured.last { it.contains(fragment) }

    // ---------- 1. detector de CONNECT ----------

    @Test
    fun detectsConnectWithHostAndPort() {
        val request = ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:443 HTTP/1.1")
        assertNotNull("um CONNECT válido precisa ser detectado", request)
        assertEquals("prod.cdni.callofduty.com", request!!.host)
        assertEquals(443, request.port)
        assertEquals("HTTP/1.1", request.version)
        assertEquals("CONNECT prod.cdni.callofduty.com:443 HTTP/1.1", request.rawLine)
    }

    @Test
    fun toleratesCarriageReturnAtEndOfLine() {
        val request = ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:80 HTTP/1.1\r")
        assertNotNull(request)
        assertEquals(80, request!!.port)
        assertEquals("prod.cdni.callofduty.com", request.host)
    }

    @Test
    fun acceptsIpv6AuthorityInBrackets() {
        val request = ConnectProbeParser.parseLine("CONNECT [2001:db8::1]:443 HTTP/1.1")
        assertNotNull(request)
        assertEquals("2001:db8::1", request!!.host)
        assertEquals(443, request.port)
    }

    @Test
    fun rejectsEverythingThatIsNotAValidConnect() {
        assertNull(ConnectProbeParser.parseLine("GET /wzm/shard_cdn/android/_manifest/cdni.meta HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("connect prod.cdni.callofduty.com:443 HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com: HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:abc HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:0 HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:70000 HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT :443 HTTP/1.1"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:443 HTTP/2"))
        assertNull(ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:443"))
        assertNull(ConnectProbeParser.parseLine(""))
    }

    @Test
    fun connectDoesNotExposeThePath() {
        // Limitação registrada, não um defeito: CONNECT carrega apenas autoridade + porta.
        val request = ConnectProbeParser.parseLine("CONNECT prod.cdni.callofduty.com:443 HTTP/1.1")
        assertNotNull(request)
        assertFalse(request!!.rawLine.contains("/wzm/"))
    }

    // ---------- 2. listener em claro: recusa e fecha ----------

    @Test
    fun connectRequestIsLoggedAndRefusedWithoutTunnel() {
        val probe = startProbe()
        assertTrue("a sonda precisa subir em loopback", probe.start())
        val port = probe.localPort
        assertTrue("porta efêmera precisa ser conhecida", port > 0)

        val response = send(port, "CONNECT prod.cdni.callofduty.com:443 HTTP/1.1")

        assertTrue("a sonda precisa recusar sem abrir túnel: $response", response.startsWith("HTTP/1.1 405"))
        assertTrue("a sonda nunca devolve 200 (200 significaria túnel)", !response.contains("200 Connection"))
        assertTrue("a resposta precisa pedir fechamento", response.contains("Connection: close"))

        val line = logLineContaining("CONNECT detectado")
        assertTrue(line.contains("[PROXY]"))
        assertTrue("o método precisa aparecer: $line", line.contains("metodo=CONNECT"))
        assertTrue("o host precisa aparecer: $line", line.contains("host=prod.cdni.callofduty.com"))
        assertTrue("a porta precisa aparecer: $line", line.contains("porta=443"))
        assertTrue("o timestamp precisa aparecer: $line", line.contains("epochMs="))
        assertTrue("o owner UID precisa aparecer: $line", line.contains("dono=uid=10692"))
        assertTrue("a linha exata precisa aparecer: $line", line.contains("CONNECT prod.cdni.callofduty.com:443 HTTP/1.1"))
    }

    @Test
    fun plainGetIsLoggedAsRequestWithoutConnect() {
        val probe = startProbe()
        assertTrue(probe.start())

        val response = send(probe.localPort, "GET / HTTP/1.1")

        assertTrue("a sonda recusa qualquer método: $response", response.startsWith("HTTP/1.1 405"))

        val line = logLineContaining("pedido SEM CONNECT")
        assertTrue("o método recebido precisa aparecer: $line", line.contains("metodo=GET"))
        assertTrue("a linha exata precisa aparecer: $line", line.contains("GET / HTTP/1.1"))
        assertTrue("o timestamp precisa aparecer: $line", line.contains("epochMs="))
    }

    @Test
    fun ownerUidUnavailableIsStatedExplicitly() {
        val probe = ConnectProbeServer(
            port = 0,
            log = { tag, message -> captured.add("[$tag] $message") },
            ownerDescription = null
        )
        server = probe
        assertTrue(probe.start())

        send(probe.localPort, "CONNECT prod.cdni.callofduty.com:443 HTTP/1.1")

        val line = logLineContaining("CONNECT detectado")
        assertTrue("sem mecanismo de owner o log precisa dizer isso: $line", line.contains("NAO_RESOLVIDO"))
    }

    @Test
    fun stopClosesTheListener() {
        val probe = startProbe()
        assertTrue(probe.start())
        val port = probe.localPort

        probe.stop()
        server = null

        assertFalse("isRunning precisa cair para false", probe.isRunning)
        assertEquals("o listener precisa ser liberado (sem porta própria)", -1, probe.localPort)
        // Prova de que nada mais escuta: um bind novo no MESMO endereço/porta tem de funcionar.
        // Não usamos "conexão recusada" porque, no Linux, o socket cliente pode receber do SO a
        // porta efêmera recém-libertada e conectar-se a si mesmo — falso negativo intermitente.
        val rebound = runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }
        assertTrue(
            "a porta precisa estar livre depois de stop(): ${rebound.exceptionOrNull()?.message}",
            rebound.isSuccess
        )
        rebound.getOrNull()?.close()
        // stop() repetido não pode lançar nem ressuscitar o listener.
        probe.stop()
        assertFalse(probe.isRunning)
    }
}
