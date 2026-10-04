package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
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

    @Test
    fun loopbackConnectionsAreCountedApartAndNeverAsWzmEvidence() {
        RequestLog.clear()
        RequestLog.clearWzmMarker()
        RequestLog.markWzmStarted(System.currentTimeMillis())

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
            val counters = RequestLog.counters.value
            assertEquals("loopback conta em separado", 1, counters.tcpConnectionsLoopback)
            assertEquals("nada de loopback pode virar conexão do túnel", 0, counters.tcpConnectionsTunel)
            assertEquals("conexão feita depois do marcador", 1, counters.loopbackDepoisDoWzm)
            assertEquals(0, counters.loopbackAntesDoWzm)

            val snapshot = RequestLog.snapshot()
            assertTrue("relação com o WZM é obrigatória", snapshot.contains("depois do WZM iniciado"))
            assertTrue(
                "o log precisa dizer que loopback não é evidência",
                snapshot.contains("NÃO conta como evidência de tráfego do WZM")
            )
            assertTrue(snapshot.contains("DIAGNÓSTICO SECUNDÁRIO"))
        } finally {
            server.stop()
        }
    }

    /**
     * M3.5: durante o teste sintético, a conexão no listener do túnel é prova do CAMINHO feita pelo
     * launcher (UID do launcher) — o log precisa dizer isso, e nunca atribuir a conexão ao WZM.
     */
    @Test
    fun syntheticWindowMarksTunnelConnectionsAsPathOnly() {
        RequestLog.clear()
        RequestLog.clearWzmMarker()
        RequestLog.markWzmStarted(System.currentTimeMillis())

        var bound: ServerSocket? = null
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT)
            ),
            tunnelBindAttempts = 1,
            tunnelBindRetryDelayMs = 1,
            sleep = { },
            addressAssigned = { true },
            ownerDescription = { "dono=uid=12345 (com.wzm.launcher)" },
            bindOverride = { _ ->
                ServerSocket(0, 16, InetAddress.getByName(CdnRouterConfig.LOOPBACK_ADDRESS)).also { bound = it }
            }
        )
        assertTrue(server.start())
        RequestLog.markSyntheticTestStarted()
        try {
            val client = Socket(CdnRouterConfig.LOOPBACK_ADDRESS, checkNotNull(bound).localPort)
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && !RequestLog.snapshot().contains("dono=uid=12345")) {
                    Thread.sleep(50)
                }
            } finally {
                runCatching { client.close() }
            }
            // olha a LINHA da conexão (não o buffer inteiro): outras suítes deixam threads daemon
            val line = RequestLog.snapshot().lines()
                .firstOrNull { it.contains("conexão aceita em ${CdnRouterConfig.VPN_ADDRESS}:443") }
                ?: ""
            assertTrue("a linha da conexão precisa existir: $line", line.isNotEmpty())
            assertTrue("a janela do teste sintético é obrigatória: $line", line.contains("DURANTE o teste sintético"))
            assertTrue("a conexão do teste é prova do caminho: $line", line.contains("prova o CAMINHO CDNI, NÃO o WZM"))
            assertFalse("dentro da janela o log não pode atribuir a conexão ao WZM: $line", line.contains("depois do WZM iniciado"))
        } finally {
            RequestLog.markSyntheticTestFinished()
            server.stop()
        }
        assertFalse(RequestLog.syntheticWindowOpen)
    }

    @Test
    fun tunnelListenerCountsAndLogsTheTunnelPath() {
        RequestLog.clear()
        RequestLog.clearWzmMarker()
        RequestLog.markWzmStarted(System.currentTimeMillis())

        var bound: ServerSocket? = null
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(
                LocalHttpsServer.BindEndpoint(CdnRouterConfig.VPN_ADDRESS, CdnRouterConfig.LOCAL_HTTPS_PORT)
            ),
            tunnelBindAttempts = 1,
            tunnelBindRetryDelayMs = 1,
            sleep = { },
            addressAssigned = { true },
            ownerDescription = { "dono=uid=10692 (com.activision.callofduty.warzone)" },
            // :443 exige privilégio no runner; o socket real vai para uma porta efêmera do loopback,
            // mas o PAPEL do endpoint continua sendo TUNEL_PRIMARIO (é o papel que decide a contagem).
            bindOverride = { _ ->
                ServerSocket(0, 16, InetAddress.getByName(CdnRouterConfig.LOOPBACK_ADDRESS)).also { bound = it }
            }
        )
        assertTrue(server.start())
        try {
            val client = Socket(CdnRouterConfig.LOOPBACK_ADDRESS, checkNotNull(bound).localPort)
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && !RequestLog.snapshot().contains("dono=uid=10692")) {
                    Thread.sleep(50)
                }
            } finally {
                runCatching { client.close() }
            }
            val counters = RequestLog.counters.value
            assertEquals("conexão do listener do túnel", 1, counters.tcpConnectionsTunel)
            assertEquals("nada de loopback nesta contagem", 0, counters.tcpConnectionsLoopback)

            val snapshot = RequestLog.snapshot()
            assertTrue(snapshot.contains("conexão aceita em ${CdnRouterConfig.VPN_ADDRESS}:443"))
            assertTrue(snapshot.contains("papel=TUNEL_PRIMARIO"))
            assertTrue("a relação temporal é obrigatória", snapshot.contains("depois do WZM iniciado"))
            assertFalse(
                "o listener do túnel nunca pode ser rotulado como diagnóstico secundário",
                snapshot.contains("conexão aceita em ${CdnRouterConfig.VPN_ADDRESS}:443 (via túnel, " +
                    "papel=TUNEL_PRIMARIO, DIAGNÓSTICO SECUNDÁRIO)")
            )
            assertFalse(
                "a negação de evidência é exclusiva do loopback",
                snapshot.contains("NÃO conta como evidência de tráfego do WZM")
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun loopbackBeforeTheWzmMarkerIsExplicitlyBefore() {
        RequestLog.clear()
        RequestLog.clearWzmMarker()
        // Marcador no futuro: qualquer conexão agora é "antes do WZM iniciado".
        RequestLog.markWzmStarted(System.currentTimeMillis() + 60_000)

        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpsServer(
            tlsContextOverride = contextWithoutKeys(),
            endpoints = listOf(LocalHttpsServer.BindEndpoint(CdnRouterConfig.LOOPBACK_ADDRESS, port)),
            ownerDescription = { "dono=NAO_RESOLVIDO (getConnectionOwnerUid devolveu INVALID_UID)" }
        )
        assertTrue(server.start())
        try {
            val client = Socket("127.0.0.1", port)
            try {
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && !RequestLog.snapshot().contains("INVALID_UID")) {
                    Thread.sleep(50)
                }
            } finally {
                runCatching { client.close() }
            }
            val counters = RequestLog.counters.value
            assertEquals(1, counters.loopbackAntesDoWzm)
            assertEquals(0, counters.loopbackDepoisDoWzm)
            val snapshot = RequestLog.snapshot()
            assertTrue(snapshot.contains("antes do WZM iniciado"))
            assertTrue(
                "a linha precisa negar a atribuição",
                snapshot.contains("NÃO pode ser atribuída ao WZM")
            )
        } finally {
            server.stop()
        }
        RequestLog.clearWzmMarker()
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
