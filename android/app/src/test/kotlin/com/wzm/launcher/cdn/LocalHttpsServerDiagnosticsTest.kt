package com.wzm.launcher.cdn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * M3.3: o listener precisa dizer (a) por qual endereço a conexão entrou, (b) **qual processo**
 * conectou e (c) por que um bind falhou. Sem isso, o teste do device que registrou 5 conexões em
 * 127.0.0.1:443 fica sem explicação (não se sabe se eram do WZM ou de outro app).
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
            ownerDescription = { "dono=uid=10234 (com.activision.callofduty.warzone)" }
        )
        assertTrue(server.start())
        try {
            val client = Socket("127.0.0.1", port)
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline &&
                    !RequestLog.snapshot().contains("dono=uid=10234")
                ) {
                    Thread.sleep(50)
                }
            } finally {
                runCatching { client.close() }
            }
            val snapshot = RequestLog.snapshot()
            assertTrue("log deve citar a tentativa de conexão", snapshot.contains("tentativa de conexão em"))
            assertTrue("log deve citar o endereço do listener", snapshot.contains("127.0.0.1:$port"))
            assertTrue("log deve citar o caminho (via loopback)", snapshot.contains("via loopback"))
            assertTrue("log deve citar o dono da conexão", snapshot.contains("dono=uid=10234"))
            assertTrue(snapshot.contains("com.activision.callofduty.warzone"))
            assertTrue("conexão precisa ser contabilizada", RequestLog.counters.value.tcpConnections >= 1)
        } finally {
            server.stop()
        }
    }

    @Test
    fun unassignableAddressIsReportedWithStableReasonCode() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        // 192.0.2.0/24 é TEST-NET-1 (RFC 5737): nunca está atribuído, então o bind falha com
        // EADDRNOTAVAIL — o mesmo errno do endereço do túnel quando a interface ainda não subiu.
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(LocalHttpsServer.BindEndpoint("192.0.2.1", port))
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
    fun missingTunnelAddressIsExplicitWhenOnlyLoopbackBinds() {
        RequestLog.clear()
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.LOCAL_FALLBACK_PORT),
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, port)
            )
        )
        assertTrue("o loopback deve subir mesmo sem o endereço do túnel", server.start())
        try {
            val snapshot = RequestLog.snapshot()
            assertTrue(
                "o log precisa dizer que o endereço do túnel ficou sem listener",
                snapshot.contains("SEM listener em ${CdnRouterConfig.VPN_ADDRESS}:${CdnRouterConfig.LOCAL_HTTPS_PORT}")
            )
            assertTrue(snapshot.contains("listeners ativos=[${CdnRouterConfig.LOOPBACK_ADDRESS}:$port]"))
            assertTrue(snapshot.contains("motivo=ENDERECO_INDISPONIVEL"))
        } finally {
            server.stop()
        }
    }
}
