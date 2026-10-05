package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Controle JVM: o socket é local/sintético e os resultados da API são injetados. */
class OwnerProbeTest {

    private class QueryLog {
        val tupleCalls = mutableListOf<Triple<Int, InetSocketAddress, InetSocketAddress>>()
        val socketCalls = mutableListOf<Socket>()
    }

    private fun loopbackListener(): ServerSocket =
        ServerSocket(0, 16, InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 0).address)

    @Test
    fun reportsOnlyTheOwnLoopbackControlAndTheMissingTuple() {
        val listener = loopbackListener()
        val queries = QueryLog()
        try {
            val report = OwnerProbe.run(
                loopbackPort = listener.localPort,
                targetPackage = "com.activision.callofduty.warzone",
                connect = { host, port -> Socket(host, port) },
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
                    OwnerProbe.StepId.TUPLA_INEXISTENTE,
                    OwnerProbe.StepId.ESCOPO_DA_VPN
                ),
                report.steps.map { it.id }
            )
            assertTrue("o controle loopback deve abrir e consultar um socket real", report.steps[0].ran)
            assertEquals(1, report.resolved)
            assertEquals(1, report.invalid)
            assertEquals(0, report.noPermission)
            assertEquals(1, queries.socketCalls.size)
            assertEquals(1, queries.tupleCalls.size)

            val (protocol, first, second) = queries.tupleCalls.single()
            assertEquals(ConnectionOwnership.PROTOCOL_TCP, protocol)
            assertEquals(InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1), first)
            assertEquals(InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1), second)

            val expectation = report.expectation()
            assertTrue(expectation, expectation.contains("VERIFIED"))
            assertTrue(expectation, expectation.contains("apenas para aquelas tuplas"))
            assertTrue(expectation, expectation.contains("não demonstra o resultado para o UID-alvo/WZM"))
            assertEquals(3, report.lines().size)
            assertTrue(report.lines().last().contains("CONTEXTO"))
            assertTrue(report.lines().last().contains("não consulta o UID do alvo"))
            assertTrue(OwnerProbe.summaryLine(report).contains("resolvidos=1"))
        } finally {
            runCatching { listener.close() }
        }
    }

    @Test
    fun invalidUidForControlSocketRemainsUnknownAndIsNotGeneralizedToWzm() {
        val listener = loopbackListener()
        try {
            val report = OwnerProbe.run(
                loopbackPort = listener.localPort,
                targetPackage = "com.activision.callofduty.warzone",
                connect = { host, port -> Socket(host, port) },
                querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) },
                queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
            )

            assertEquals(2, report.invalid)
            assertEquals(0, report.resolved)
            assertEquals(0, report.noPermission)
            val expectation = report.expectation()
            assertTrue(expectation, expectation.contains("UNKNOWN"))
            assertTrue(expectation, expectation.contains("não pode ser generalizado ao WZM"))
            assertFalse("não transformar pista em provável autoria", expectation.contains("PROBABLE"))
            assertTrue(report.lines().any { it.contains("INVALID_UID") })
        } finally {
            runCatching { listener.close() }
        }
    }

    @Test
    fun unavailableListenerIsSkippedAndScopeLineIsContextNotAResult() {
        val report = OwnerProbe.run(
            loopbackPort = null,
            targetPackage = "com.activision.callofduty.warzone",
            connect = { _, _ -> error("não deveria conectar sem listener") },
            querySocket = { error("não deveria consultar socket") },
            queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
        )

        assertFalse(report.steps[0].ran)
        assertNotNull(report.steps[0].skippedReason)
        assertTrue(report.steps[1].ran)
        assertFalse(report.steps[2].ran)
        assertTrue(report.lines().any { it.contains("PULADO") })
        assertTrue(report.lines().any { it.contains("CONTEXTO") && it.contains("com.activision.callofduty.warzone") })
        assertTrue(report.expectation().contains("UNKNOWN"))
    }

    @Test
    fun securityExceptionIsCountedSeparatelyAndLeadsToUnknown() {
        val listener = loopbackListener()
        try {
            val report = OwnerProbe.run(
                loopbackPort = listener.localPort,
                targetPackage = "com.activision.callofduty.warzone",
                connect = { host, port -> Socket(host, port) },
                querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.SECURITY_EXCEPTION) },
                queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.SECURITY_EXCEPTION) }
            )

            assertEquals(2, report.noPermission)
            assertEquals(0, report.resolved)
            assertEquals(0, report.invalid)
            assertTrue(report.expectation().contains("UNKNOWN"))
            assertTrue(report.expectation().contains("VPN ativo"))
        } finally {
            runCatching { listener.close() }
        }
    }

    @Test
    fun connectionFailureIsSkipNotFabricatedResult() {
        val report = OwnerProbe.run(
            loopbackPort = 44321,
            targetPackage = "com.activision.callofduty.warzone",
            connect = { _, _ -> throw java.net.ConnectException("recusada") },
            querySocket = { ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) },
            queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
        )

        assertFalse(report.steps[0].ran)
        assertTrue(report.steps[0].skippedReason!!.contains("não foi possível conectar"))
        assertTrue("a tupla ausente não depende do listener", report.steps[1].ran)
        assertEquals(0, report.resolved)
        assertEquals("uma única consulta foi INVALID_UID", 1, report.invalid)
    }

    @Test
    fun unexpectedQueryExceptionIsNotMisclassifiedAsMissingAddresses() {
        val listener = loopbackListener()
        try {
            val report = OwnerProbe.run(
                loopbackPort = listener.localPort,
                targetPackage = "com.activision.callofduty.warzone",
                connect = { host, port -> Socket(host, port) },
                querySocket = { throw IllegalStateException("platform failure") },
                queryTuple = { _, _, _ -> ConnectionOwnership.Result(ConnectionOwnership.Outcome.INVALID_UID) }
            )
            assertEquals(ConnectionOwnership.Outcome.CONSULTA_FALHOU, report.steps[0].result?.outcome)
            assertTrue(report.lines()[0].contains("CONSULTA_FALHOU"))
        } finally {
            runCatching { listener.close() }
        }
    }
}
