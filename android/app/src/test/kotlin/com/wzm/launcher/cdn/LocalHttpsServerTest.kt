package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
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
        socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
        socket.startHandshake()
        return socket
    }

    private fun request(path: String, host: String = "prod.cdni.callofduty.com"): String =
        connect(host).use { socket ->
            socket.outputStream.write(
                "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: wzm-offline-test\r\n" +
                    "Accept: */*\r\n\r\n".toByteArray()
            )
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
    fun servesShardCdnMeta() {
        val response = request("/wzm/shard_cdn/android/_manifest/cdni.meta")
        assertTrue(response.contains("200 OK"))
        assertTrue(response.contains("X-WZM-Offline: VERIFIED"))
        assertTrue(response.contains("wzm-offline-local"))
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

    @Test
    fun serverOnlyListensOnRequestedEndpoint() {
        assertEquals(listOf(LocalHttpsServer.BindEndpoint("127.0.0.1", port)), server.boundEndpoints)
    }
}
