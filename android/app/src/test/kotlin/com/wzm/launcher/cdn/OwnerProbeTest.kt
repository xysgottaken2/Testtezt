package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * M4.1: o teste de controle de autoria. Ele existe porque o log antigo lia `INVALID_UID` como
 * "não é do jogo" — o que é falso: a API devolve `-1` tanto para conexão ausente quanto para uid
 * **fora da VPN que chamou**. Aqui a execução é 100% injetada (nenhum pacote sai do aparelho):
 * o que se testa é a lógica, o registro de portas próprias e a leitura declarada do resultado.
 */
class OwnerProbeTest {

    private class QueryLog {
        val tupleCalls = mutableListOf<Triple<Int, InetSocketAddress, InetSocketAddress>>()
        val socketCalls = mutableListOf<Socket>()
    }

    /** Socket conectado de verdade a um listener local — para o passo ter endereços utilizáveis. */
    private fun connectedPair(): Pair<ServerSocket, Socket> {
        val listener = ServerSocket(0, 1, InetSocketAddress("127.0.0.1", 0).address)
        val client = Socket("127.0.0.1", listener.localPort)
        return listener to client
    }

    @Test
    fun everyStepRunsAndTheOwnOriginPortsAreRegistered() {
        val (listener, client) = connectedPair()
        val queries = QueryLog()
        val ownPorts = mutableListOf<Int>()
        try {
            val report = OwnerProbe.run(
                loopbackPort = 44321,
                tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
                tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
                tunnelAddressAssigned = true,
                selfPortRegistrar = { port -> ownPorts += port },
                // O passo do túnel só precisa de um socket com endereços; a conexão real é para o
                // listener local (o teste não depende de tun0 existir na JVM).
                connect = { host, _ -> if (host == "127.0.0.1") client else Socket("127.0.0.1", listener.localPort) },
                querySocket = { socket ->
                    queries.socketCalls += socket
                    ConnectionOwnership.Result(ConnectionOwnership.Outcome.RESOLVIDO, uid = 10101)
                },
                queryTuple = { protocol, first, second ->
                    queries.tupleCalls += Triple(protocol, first, second)
                    ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID)
                }
            )

            assertEquals(
                listOf(
                    OwnerProbe.StepId.LOOPBACK_PROPRIO,
                    OwnerProbe.StepId.TUNEL_PROPRIO,
                    OwnerProbe.StepId.TUPLA_INEXISTENTE,
                    OwnerProbe.StepId.ESCOPO_DA_VPN
                ),
                report.steps.map { it.id }
            )
            assertTrue("os dois primeiros passos rodam de verdade", report.steps[0].ran && report.steps[1].ran)
            assertEquals(2, report.resolved)
            assertEquals(1, report.invalid)
            assertEquals(0, report.noPermission)

            assertEquals("as duas conexões do launcher tiveram a porta registrada", 2, ownPorts.size)
            assertTrue(ownPorts.all { it in 1..65535 })

            // A tupla inexistente é TCP 127.0.0.1:1 — é ela que separa "não encontrado" de "fora da VPN".
            assertEquals(1, queries.tupleCalls.size)
            val (protocol, first, second) = queries.tupleCalls.single()
            assertEquals(ConnectionOwnership.PROTOCOL_TCP, protocol)
            assertEquals(InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1), first)
            assertEquals(InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1), second)

            assertTrue(OwnerProbe.summaryLine(report).contains("resolvidos=2"))
            assertTrue(OwnerProbe.summaryLine(report).contains("INVALID_UID=1"))
            assertTrue(report.expectation().contains("RESOLVEU"))
            assertEquals(4, report.lines().size)
        } finally {
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun invalidUidOnlyResultIsReadAsAmbiguityNotAsSomeoneElse() {
        val (listener, client) = connectedPair()
        try {
            val report = OwnerProbe.run(
                loopbackPort = 44321,
                tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
                tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
                tunnelAddressAssigned = true,
                selfPortRegistrar = {},
                connect = { _, _ -> Socket("127.0.0.1", listener.localPort) },
                querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) },
                queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
            )

            assertEquals(3, report.invalid)
            assertEquals(0, report.resolved)
            val expectation = report.expectation()
            assertTrue("precisa declarar a ambiguidade: $expectation", expectation.contains("INVALID_UID"))
            assertTrue(
                "não pode afirmar que é de outro app: $expectation",
                !expectation.contains("de outro app")
            )
            assertTrue(
                "as linhas precisam repetir o motivo (appliesToUid não é opcional)",
                report.lines().any { it.contains("INVALID_UID") }
            )
            assertTrue(
                "a leitura precisa registrar o nível de confiança do que sobra (PROBABLE): $expectation",
                expectation.contains("PROBABLE")
            )
        } finally {
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun stepsAreSkippedWithReasonInsteadOfInventingResults() {
        val report = OwnerProbe.run(
            loopbackPort = null,
            tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
            tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
            tunnelAddressAssigned = false,
            selfPortRegistrar = {},
            connect = { _, _ -> error("não deveria conectar sem listener nem endereço") },
            querySocket = { error("não deveria consultar socket") },
            queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
        )

        assertFalse(report.steps[0].ran)
        assertNotNull(report.steps[0].skippedReason)
        assertFalse(report.steps[1].ran)
        assertNotNull(report.steps[1].skippedReason)
        assertTrue(report.steps[2].ran)
        assertTrue(report.lines().any { it.contains("PULADO") })
        assertTrue(report.lines().any { it.contains("ESCOPO") || it.contains("escopo_da_vpn") })
    }

    @Test
    fun securityExceptionIsCountedSeparatelyAndLeadsToInconclusiveReading() {
        val (listener, client) = connectedPair()
        try {
            val report = OwnerProbe.run(
                loopbackPort = 44321,
                tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
                tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
                tunnelAddressAssigned = true,
                selfPortRegistrar = {},
                connect = { _, _ -> Socket("127.0.0.1", listener.localPort) },
                querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.SECURITY_EXCEPTION) },
                queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.SECURITY_EXCEPTION) }
            )

            assertEquals(2, report.noPermission)
            assertEquals(0, report.resolved)
            val expectation = report.expectation()
            assertTrue("precisa dizer que é inconclusivo: $expectation", expectation.contains("inconclusivo"))
            assertTrue(expectation.contains("VPN ATIVO"))
        } finally {
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun connectionFailureIsSkipNotFabricatedResult() {
        val report = OwnerProbe.run(
            loopbackPort = 44321,
            tunnelAddress = CdnRouterConfig.VPN_ADDRESS,
            tunnelPort = CdnRouterConfig.LOCAL_HTTPS_PORT,
            tunnelAddressAssigned = true,
            selfPortRegistrar = {},
            connect = { _, _ -> throw java.net.ConnectException("recusada") },
            querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) },
            queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
        )

        assertFalse(report.steps[0].ran)
        assertFalse(report.steps[1].ran)
        assertTrue(report.steps[0].skippedReason!!.contains("não foi possível conectar"))
        assertEquals("nenhum passo útil → inconclusivo", 0, report.resolved + report.invalid)
    }
}
