package com.wzm.launcher.cdn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * M3.4: o listener precisa dizer (a) por qual endereço a conexão entrou, (b) **qual processo**
 * conectou, (c) por que um bind falhou e (d) como o retry limitado se comporta quando o endereço
 * do túnel demora a existir — a falha exata (EADDRNOTAVAIL) da evidência de 2026-10-04.
 */
class LocalHttpsServerDiagnosticsTest {

    private fun contextWithoutKeys(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, null, null) }

    @Test
    fun acceptLogCarriesEndpointPathAndOwner() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(LocalHttpsServer.BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, port)),
            ownerDescription = { "dono=uid=10692 (com.activision.callofduty.warzone)" }
        )
        assertTrue(server.start())
        try {
            val client = Socket("127.0.0.1", port)
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && !RequestLog.snapshot().contains("dono=uid=10692")) {
                    Thread.sleep(50)
                }
            } finally {
                runCatching { client.close() }
            }
            val snapshot = RequestLog.snapshot()
            assertTrue("log deve citar a tentativa de conexão", snapshot.contains("tentativa de conexão em"))
            assertTrue("log deve citar o endereço do listener", snapshot.contains("127.0.0.1:$port"))
            assertTrue("log deve citar o caminho (via loopback)", snapshot.contains("via loopback"))
            assertTrue("log deve citar o dono da conexão", snapshot.contains("dono=uid=10692"))
            assertTrue(snapshot.contains("com.activision.callofduty.warzone"))
            assertTrue("listener de loopback é separado", snapshot.contains("papel=LOOPBACK_DIAGNOSTICO"))
            assertTrue(RequestLog.counters.value.tcpConnections >= 1)
        } finally {
            server.stop()
        }
    }

    /**
     * O coração da correção de ciclo de vida: o bind no endereço do túnel pode falhar no começo
     * (EADDRNOTAVAIL) e funcionar depois. O retry é **limitado** e cada tentativa é registrada.
     */
    @Test
    fun tunnelBindRetriesAreBoundedAndSucceedWhenAddressShowsUp() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        var attempts = 0
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT)
            ),
            tunnelBindAttempts = 5,
            tunnelBindRetryDelayMs = 1,
            addressAssigned = { attempts >= 3 },
            sleep = { },
            bindOverride = { _ ->
                attempts++
                if (attempts < 3) {
                    throw BindException("Cannot assign requested address")
                }
                // Porta efêmera: no runner do CI não há privilégio para bindar :443.
                ServerSocket(0, 16, InetAddress.getByName(CdnRouterConfig.LOOPBACK_ADDRESS))
            }
        )
        assertTrue("com retry limitado o listener do túnel precisa subir", server.start())
        try {
            assertTrue(server.boundEndpoints.any { it.role == LocalHttpsServer.EndpointRole.TUNEL_PRIMARIO })
            val snapshot = RequestLog.snapshot()
            assertTrue("cada tentativa falha precisa de motivo", snapshot.contains("motivo=ENDERECO_INDISPONIVEL"))
            assertTrue("o retry precisa ser explícito no log", snapshot.contains("tentativa 2/5"))
            assertTrue(snapshot.contains("tentativa 3/5"))
            assertTrue(
                "com o listener do túnel ativo não há limitação a registrar",
                !snapshot.contains("LIMITAÇÃO DOCUMENTADA")
            )
            assertTrue(snapshot.contains("HTTPS local escutando em ${CdnRouterConfig.VPN_ADDRESS}:443"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun exhaustedTunnelBindStopsAndRegistersDocumentedLimitation() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        var attempts = 0
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT),
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, port)
            ),
            tunnelBindAttempts = 3,
            tunnelBindRetryDelayMs = 1,
            addressAssigned = { false },
            sleep = { },
            bindOverride = { endpoint ->
                attempts++
                if (endpoint.address == CdnRouterConfig.VPN_ADDRESS) {
                    throw BindException("Cannot assign requested address")
                }
                ServerSocket(endpoint.port, 16, InetAddress.getByName(endpoint.address))
            }
        )
        assertTrue("o loopback (caminho separado) deve subir mesmo sem o endereço do túnel", server.start())
        try {
            assertTrue("retry precisa parar no teto", attempts <= 4)
            assertFalse(server.boundEndpoints.any { it.role == LocalHttpsServer.EndpointRole.TUNEL_PRIMARIO })
            val snapshot = RequestLog.snapshot()
            assertTrue("motivo exato do bind", snapshot.contains("motivo=ENDERECO_INDISPONIVEL"))
            assertTrue("esgotamento precisa ser explícito", snapshot.contains("esgotado após as tentativas"))
            assertTrue(
                "quando o listener do túnel não sobe, a limitação fica DOCUMENTADA",
                snapshot.contains("LIMITAÇÃO DOCUMENTADA")
            )
            assertTrue(
                "e o estado não avança para ROUTER_READY",
                snapshot.contains("NÃO avança para ROUTER_READY")
            )
            assertTrue(snapshot.contains("papel=LOOPBACK_DIAGNOSTICO"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun unassignableAddressIsReportedWithStableReasonCode() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        // 192.0.2.0/24 é TEST-NET-1 (RFC 5737): nunca está atribuído → EADDRNOTAVAIL real.
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(LocalHttpsServer.BindEndpoint("192.0.2.1", port)),
            tunnelBindAttempts = 2,
            tunnelBindRetryDelayMs = 1,
            sleep = { }
        )
        val started = server.start()
        try {
            assertFalse("sem nenhum listener o servidor não pode dizer que subiu", started)
            val snapshot = RequestLog.snapshot()
            assertTrue("bind falho precisa de motivo estável", snapshot.contains("motivo=ENDERECO_INDISPONIVEL"))
            assertTrue(snapshot.contains("192.0.2.1:$port"))
            assertTrue(snapshot.contains("NENHUM listener HTTPS local ativo"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun loopbackEndpointIsNeverRetried() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        var attempts = 0
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(LocalHttpsServer.BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, port)),
            tunnelBindAttempts = 8,
            tunnelBindRetryDelayMs = 1,
            sleep = { },
            bindOverride = { endpoint ->
                attempts++
                ServerSocket(endpoint.port, 16, InetAddress.getByName(endpoint.address))
            }
        )
        assertTrue(server.start())
        try {
            assertTrue("loopback sobe na primeira tentativa", attempts == 1)
            assertTrue(!RequestLog.snapshot().contains("tentativa 2/8"))
        } finally {
            server.stop()
        }
    }
}
