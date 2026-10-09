package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Teste fim-a-fim do servidor HTTPS local com o certificado REAL do app
 * (`src/main/assets/cdn_local.p12`): abre socket TLS, faz requisições HTTP/1.1 e confere
 * respostas + o log que o launcher mostra. É a prova automatizada de que as requisições
 * do WZM (mesmo caminho de código) chegam ao servidor e aparecem no log.
 */
class LocalHttpsServerTest {

    private lateinit var server: LocalHttpsServer
    private lateinit var ca: X509Certificate
    private var port: Int = 0

    @Before
    fun setUp() {
        RequestLog.clear()
        port = ServerSocket(0).use { it.localPort }
        ca = CertificateFactory.getInstance("X.509")
            .generateCertificate(asset("cdn_local_ca.pem").inputStream()) as X509Certificate
        val material = TlsContextFactory.FromBytes(
            asset("cdn_local.p12").readBytes(),
            CdnRouterConfig.CERT_PASSWORD.toCharArray()
        )
        server = LocalHttpsServer(
            tlsMaterial = material,
            endpoints = listOf(LocalHttpsServer.BindEndpoint("127.0.0.1", port))
        )
        assertTrue("servidor HTTPS não subiu", server.start())
        assertEquals(port, server.primaryPort)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("android/app/src/main/assets/$name")
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val direct = File(directory, "android/app/src/main/assets/$name")
            if (direct.isFile) return direct
            val module = File(directory, "app/src/main/assets/$name")
            if (module.isFile) return module
            directory = directory.parentFile
        }
        throw AssertionError(
            "asset $name não encontrado (user.dir=${System.getProperty("user.dir")}); " +
                "rode: bash scripts/generate-local-cdni-cert.sh"
        )
    }

    private fun trustedContext(): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("local-ca", ca)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
    }

    private fun connect(sni: String = "prod.cdni.callofduty.com"): SSLSocket {
        val socket = trustedContext().socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        val parameters = socket.sslParameters
        parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName(sni))
        socket.sslParameters = parameters
        socket.startHandshake()
        return socket
    }

    private fun request(path: String, host: String = "prod.cdni.callofduty.com"): String =
        connect(host).use { socket ->
            val head = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: wzm-offline-test\r\n" +
                "Accept: */*\r\n\r\n"
            socket.outputStream.write(head.toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            String(socket.inputStream.readBytes(), Charsets.ISO_8859_1)
        }

    @Test
    fun servesVerifiedBootstrapEndpointOverRealTls() {
        val response = request("/manifest/build-selector-103.js")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("X-WZM-Offline: VERIFIED"))
        assertTrue(response.contains("wzm-offline-local"))
        assertEquals(1, RequestLog.counters.value.tlsOk)
        assertEquals(1, RequestLog.counters.value.httpRequests)
        val snapshot = RequestLog.snapshot()
        assertTrue("log deve conter o path real", snapshot.contains("/manifest/build-selector-103.js"))
        assertTrue("log deve identificar o cliente", snapshot.contains("cliente="))
        assertTrue("handshake precisa aparecer no log", snapshot.contains("[TLS]"))
    }

    @Test
    fun servesShardCdnMetaWithRealBody() {
        // M3.5: o cdni.meta deixou de ser placeholder e passou a devolver o corpo REAL observado;
        // a marcação de "servido localmente" fica no cabeçalho, não no JSON (não altera o conteúdo).
        val response = request("/wzm/shard_cdn/android/_manifest/cdni.meta")
        assertTrue(response.contains("200 OK"))
        assertTrue(response.contains("X-WZM-Offline: VERIFIED"))
        val body = response.substringAfter("\r\n\r\n")
        assertTrue("corpo precisa ser o cdni.meta real", CdniMetaBody.looksLikeRealBody(body))
        assertTrue("build mínimo precisa estar presente", body.contains("\"min_buildnum\": 19854920"))
        assertFalse("placeholder não pode mais ser servido neste path", body.contains("wzm-offline-local"))
    }

    @Test
    fun unknownEndpointGetsControlled404AndExactPathInLog() {
        val path = "/manifest/nao-existe-9.9.9.js"
        val response = request(path)
        assertTrue(response.contains("404 Not Found"))
        assertTrue(response.contains("unknown_cdni_endpoint"))
        val snapshot = RequestLog.snapshot()
        assertTrue("log deve registrar o path exato", snapshot.contains(path))
        assertEquals(1, RequestLog.counters.value.unknownRequests)
    }

    @Test
    fun httpHeadIsParsedWithQueryAndHost() {
        val head = HttpHeadReader.read(
            java.io.ByteArrayInputStream(
                "GET /prelogin/boot-15.0.0/web/manifest.json?b=15 HTTP/1.1\r\nHost: prod.cdni.callofduty.com\r\n\r\n".toByteArray()
            )
        )!!.head
        assertEquals("/prelogin/boot-15.0.0/web/manifest.json", head.path)
        assertEquals("b=15", head.query)
        assertEquals("prod.cdni.callofduty.com", head.host)
    }

    /**
     * Espelha a evidência do device (S23 Ultra, 2026-10-04): o cliente ALCANÇA o listener e é
     * recusado na validação do certificado. O servidor precisa contabilizar a falha de TLS,
     * **não** contabilizar HTTP e registrar o motivo classificado.
     */
    @Test
    fun untrustedClientHandshakeCountsAsTlsFailureAndNeverAsHttpRequest() {
        RequestLog.clear()
        val untrusting = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val client = untrusting.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        val clientFailure = try {
            client.startHandshake()
            null
        } catch (e: Exception) {
            e
        } finally {
            runCatching { client.close() }
        }
        assertNotNull("o cliente JVM SEM confiança no certificado local deve falhar", clientFailure)

        // O atendimento acontece na thread do servidor. IMPORTANTE: o contador é incrementado ANTES
        // de a linha de log ser escrita, então esperamos pela LINHA (senão o teste fica flaky).
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline &&
            !RequestLog.snapshot().contains("FALHA no handshake TLS")
        ) {
            Thread.sleep(50)
        }

        val snapshot = RequestLog.snapshot()
        assertTrue("o servidor precisa registrar a falha de handshake", snapshot.contains("FALHA no handshake TLS"))
        assertTrue("log deve citar a tentativa de conexão", snapshot.contains("tentativa de conexão em"))
        assertTrue("log precisa dizer o dono da conexão", snapshot.contains("dono="))
        assertTrue("conexão precisa indicar o caminho usado", snapshot.contains("via loopback"))

        val counters = RequestLog.counters.value
        assertTrue("falha de TLS deve ser contabilizada", counters.tlsFailed >= 1)
        assertEquals("nenhum handshake pode ter dado OK", 0, counters.tlsOk)
        assertEquals("HTTP só conta depois do TLS", 0, counters.httpRequests)
        assertTrue("conexão TCP deve ter sido contabilizada", counters.tcpConnections >= 1)

        // Quando o servidor vê um SSLHandshakeException, a linha traz o código classificado; quando a
        // pilha encerra de outra forma (sem alerta), o código não existe — nesse caso a classificação
        // já está coberta por TlsTrustTest (inclusive com a string exata do device).
        if (snapshot.contains("motivo=")) {
            val code = Regex("motivo=([A-Z_]+)").find(snapshot)?.groupValues?.get(1)
            val known = setOf(
                TlsFailure.CLIENT_REJECTED_CERTIFICATE,
                TlsFailure.CLIENT_CLEARTEXT,
                TlsFailure.HOSTNAME_MISMATCH,
                TlsFailure.CERTIFICATE_EXPIRED,
                TlsFailure.NO_COMMON_CIPHER,
                TlsFailure.PEER_CLOSED,
                TlsFailure.RECORD_LAYER_INTEGRITY,
                TlsFailure.UNKNOWN
            )
            assertTrue("código de falha desconhecido: $code", code != null && code in known)
        }
    }

    @Test
    fun serverOnlyListensOnRequestedEndpoint() {
        assertEquals(listOf(LocalHttpsServer.BindEndpoint("127.0.0.1", port)), server.boundEndpoints)
    }

    // ---------- M8: SNI registrado também nas FALHAS de handshake (só o nome; nunca payload) ----------

    /**
     * Por que isto existe: no run do device de 2026-10-08, 12 conexões com owner UID do alvo caíram no
     * listener e **nenhuma** linha de falha dizia contra qual host o cliente falou — `prod.cdni` e
     * `dev.cdni` resolvem para o mesmo IP e batem no mesmo listener, então o SNI é o único discriminador.
     * O SNI só era lido no caminho de sucesso, e a leitura usava `handshakeSession` (null depois de
     * completado), o que produziu `SNI=?` no handshake que **deu certo**.
     */

    /** Cliente que NÃO confia no certificado local e envia SNI — o cenário do WZM no aparelho. */
    private fun untrustedConnectWithSni(sni: String, targetPort: Int): SSLSocket {
        val untrusting = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val socket = untrusting.socketFactory.createSocket("127.0.0.1", targetPort) as SSLSocket
        val parameters = socket.sslParameters
        parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName(sni))
        socket.sslParameters = parameters
        return socket
    }

    /** Espera por uma linha do log (o atendimento acontece na thread do servidor). */
    private fun awaitLine(needle: String, timeoutMs: Long = 8_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            RequestLog.snapshot().lines().firstOrNull { it.contains(needle) }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("linha contendo \"$needle\" não apareceu no log em ${timeoutMs} ms")
    }

    @Test
    fun tlsFailureLineCarriesTheSniAskedByTheClient() {
        // Servidor próprio com a leitura de SNI injetada: prova o QUE a linha registra, sem depender do
        // que cada pilha TLS expõe durante um handshake quebrado (o ponto seguinte cobre o caminho real).
        val secondPort = ServerSocket(0).use { it.localPort }
        val lookupCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val instrumented = LocalHttpsServer(
            tlsMaterial = TlsContextFactory.FromBytes(
                asset("cdn_local.p12").readBytes(),
                CdnRouterConfig.CERT_PASSWORD.toCharArray()
            ),
            endpoints = listOf(LocalHttpsServer.BindEndpoint("127.0.0.1", secondPort)),
            sniLookup = { _ ->
                lookupCalls.incrementAndGet()
                "dev.cdni.callofduty.com"
            }
        )
        assertTrue("o segundo servidor HTTPS não subiu", instrumented.start())
        try {
            val client = untrustedConnectWithSni("dev.cdni.callofduty.com", secondPort)
            val clientFailure = runCatching { client.startHandshake() }.exceptionOrNull()
            runCatching { client.close() }
            assertNotNull("o cliente sem confiança deveria falhar no handshake", clientFailure)

            val line = awaitLine("FALHA no handshake TLS")
            assertTrue(
                "a linha de FALHA precisa trazer o SNI (é o que separa prod de dev)",
                line.contains("sni=dev.cdni.callofduty.com")
            )
            // O `motivo=` é escrito só pelo ramo que pega SSLHandshakeException. No JVM o alerta do cliente
            // pode chegar como SSLProtocolException (foi o que o CI mediu em 2026-10-08) e cair no catch
            // genérico; no device as 12 falhas reais vieram como SSLHandshakeException. O teste não pode
            // exigir o que a pilha TLS decide — exige, sim, que QUALQUER dos dois ramos registre o sni.
            if (line.contains("motivo=")) {
                val code = Regex("motivo=([A-Z_]+)").find(line)?.groupValues?.get(1)
                val known = setOf(
                    TlsFailure.CLIENT_REJECTED_CERTIFICATE,
                    TlsFailure.CLIENT_CLEARTEXT,
                    TlsFailure.HOSTNAME_MISMATCH,
                    TlsFailure.CERTIFICATE_EXPIRED,
                    TlsFailure.NO_COMMON_CIPHER,
                    TlsFailure.PEER_CLOSED,
                    TlsFailure.RECORD_LAYER_INTEGRITY,
                    TlsFailure.UNKNOWN
                )
                assertTrue("código de falha desconhecido: $code", code != null && code in known)
            }
            assertTrue(
                "o sni= tem de sair em QUALQUER ramo de falha (este foi o caminho que o JVM tomou)",
                line.contains("sni=dev.cdni.callofduty.com")
            )
            assertEquals("nenhum HTTP pode ser contado antes do TLS", 0, RequestLog.counters.value.httpRequests)
            assertEquals("nenhum cdni.meta servido nesta conexão", 0, RequestLog.counters.value.cdniMetaServidos)

            // Guarda de privacidade: o que se registra é o NOME do ClientHello — nada de requisição/payload.
            assertFalse("linha de requisição não pode entrar no log", line.contains("GET /"))
            assertFalse("nenhuma versão de protocolo no log de TLS", line.contains("HTTP/1.1"))
            assertFalse("cabeçalho HTTP não pode entrar no log", line.contains("Host:"))
            assertFalse(line.contains("Cookie"))
            assertFalse(line.contains("Authorization"))
            assertFalse("corpo jamais", line.contains("min_buildnum"))
            // O lookup é chamado na falha (antes só existia chamada no sucesso) — é isso que torna o dado
            // disponível para prod×dev. A contagem é exatamente uma por conexão atendida neste teste.
            assertEquals("a leitura do SNI precisa acontecer no caminho de falha", 1, lookupCalls.get())
        } finally {
            instrumented.stop()
        }
    }

    @Test
    fun tlsFailureLineNeverDropsTheSniSilently() {
        // Caminho REAL (sem injeção): onde a pilha TLS conseguir expor a sessão, o nome tem de aparecer;
        // onde não conseguir, a linha precisa DIZER isso — o placeholder mudo `?` está banido, porque
        // "não olhamos" e "o cliente não enviou SNI" são coisas diferentes e o log não pode confundi-las.
        val client = untrustedConnectWithSni("dev.cdni.callofduty.com", port)
        runCatching { client.startHandshake() }
        runCatching { client.close() }

        val line = awaitLine("FALHA no handshake TLS")
        assertTrue("sni= precisa estar presente na linha de falha", line.contains("sni="))
        assertTrue(
            "ou o nome exato, ou a indisponibilidade declarada",
            line.contains("sni=dev.cdni.callofduty.com") || line.contains("sni=INDISPONIVEL")
        )
        assertFalse("o '?' mudo não pode voltar", line.contains("sni=?") || line.contains("SNI=?"))
    }

    @Test
    fun successfulHandshakeLineReportsTheSniOrSaysItIsUnavailable() {
        val response = request("/manifest/build-selector-103.js")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))

        val line = awaitLine("[TLS] handshake OK")
        assertTrue(
            "regra do M8: nem no sucesso o SNI pode virar um silêncio",
            line.contains("sni=prod.cdni.callofduty.com") || line.contains("sni=INDISPONIVEL")
        )
        assertFalse("placeholder mudo banido", line.contains("sni=?") || line.contains("SNI=?"))
    }
}
