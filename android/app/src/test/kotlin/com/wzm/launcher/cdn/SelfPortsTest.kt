package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** M4.1: porta isolada não identifica processo; somente a tupla exata e recente pode casar. */
class SelfPortsTest {

    @After
    fun tearDown() {
        SelfPorts.reset()
    }

    @Test
    fun withoutCalibrationOrExactTupleEveryPeerIsIndeterminate() {
        SelfPorts.reset()

        assertEquals(
            SelfPorts.PortVerdict.INDETERMINADO,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 40000, "127.0.0.1", 443)
        )
        assertFalse(SelfPorts.snapshot().valid)
    }

    @Test
    fun ephemeralWindowIsOnlyProbableAndOutsideSampleDoesNotIdentifyAnotherProcess() {
        SelfPorts.install(SelfPorts.Window(30000, 40000, 4))

        assertEquals(
            SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 30000, "127.0.0.1", 443)
        )
        assertEquals(
            SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 35555, "127.0.0.1", 443)
        )
        assertEquals(
            SelfPorts.PortVerdict.FORA_DA_AMOSTRA,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 29999, "127.0.0.1", 443)
        )
        assertTrue(
            "fora da amostra não pode virar autoria de outro processo",
            SelfPorts.PortVerdict.FORA_DA_AMOSTRA.label.contains("sem conclusao sobre processo")
        )
        assertTrue(
            "a janela não é prova de autoria",
            SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA.label.contains("nao prova")
        )
    }

    @Test
    fun exactFourTupleIsMatchedOnceAndWrongDestinationDoesNotMatchSameSourcePort() {
        val identity = SelfPorts.ConnectionIdentity("127.0.0.1", 45000, "127.0.0.1", 443)
        assertTrue(SelfPorts.register(identity, atMillis = 1_000, processPid = 1234))

        assertEquals(
            "mesma porta com outro listener não prova que seja o socket registrado",
            SelfPorts.PortVerdict.INDETERMINADO,
            SelfPorts.verdictForAcceptedConnection(
                peerAddress = "127.0.0.1",
                peerPort = 45000,
                listenerAddress = "127.0.0.1",
                listenerPort = 18443,
                atMillis = 1_001
            )
        )
        val matched = SelfPorts.evidenceForAcceptedConnection(
            peerAddress = "127.0.0.1",
            peerPort = 45000,
            listenerAddress = "127.0.0.1",
            listenerPort = 443,
            atMillis = 1_002
        )
        assertEquals(SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO, matched.portVerdict)
        assertEquals("PID só conhecido para socket registrado pelo próprio processo", 1234, matched.registeredPid)
        assertEquals("a evidência exata é consumida uma vez", 0, SelfPorts.registeredConnections().size)
    }

    @Test
    fun realAcceptedSocketMatchesTheRegisteredClientSocketInPeerDirection() {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val client = Socket()
        var accepted: Socket? = null
        try {
            client.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort))
            assertTrue(SelfPorts.register(client, atMillis = 1_000))
            accepted = listener.accept()

            assertEquals(
                SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO,
                SelfPorts.verdictForAcceptedSocket(accepted, atMillis = 1_001)
            )
        } finally {
            runCatching { accepted?.close() }
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun listenerAcceptBeforePostConnectRegistrationRejectsLateExactMatch() {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val client = Socket()
        var accepted: Socket? = null
        try {
            client.connect(java.net.InetSocketAddress("127.0.0.1", listener.localPort))
            accepted = listener.accept() // server may reach its attribution check before client registration
            val local = ConnectionOwnership.toInet(client.localSocketAddress)!!
            SelfPorts.install(SelfPorts.Window(local.port, local.port, 1))

            val early = SelfPorts.evidenceForAcceptedSocket(accepted, atMillis = 1_001)
            assertEquals(
                "a missing exact registration must not be promoted by port-only evidence",
                SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA,
                early.portVerdict
            )
            assertEquals(null, early.registeredPid)

            // The production client registers immediately after connect() returns. If accept wins this
            // race, the one-shot verdict stays conservative and its late registration is discarded.
            assertFalse(SelfPorts.register(client, atMillis = 1_002))
            assertTrue("late tuple must not remain available for a future connection", SelfPorts.registeredConnections().isEmpty())
        } finally {
            runCatching { accepted?.close() }
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun registeredTupleExpiresAndCannotBeReusedAsLaterAttribution() {
        assertTrue(
            SelfPorts.register(
                SelfPorts.ConnectionIdentity("127.0.0.1", 45000, "127.0.0.1", 443),
                atMillis = 1_000
            )
        )

        assertEquals(
            SelfPorts.PortVerdict.INDETERMINADO,
            SelfPorts.verdictForAcceptedConnection(
                "127.0.0.1", 45000, "127.0.0.1", 443, atMillis = 31_001
            )
        )
        assertTrue(SelfPorts.registeredConnections().isEmpty())
    }

    @Test
    fun invalidTuplesAreIgnored() {
        assertFalse(SelfPorts.register(SelfPorts.ConnectionIdentity("", 40000, "127.0.0.1", 443)))
        assertFalse(SelfPorts.register(SelfPorts.ConnectionIdentity("127.0.0.1", 0, "127.0.0.1", 443)))
        assertFalse(SelfPorts.register(SelfPorts.ConnectionIdentity("127.0.0.1", 40000, "127.0.0.1", 70000)))
        assertTrue(SelfPorts.registeredConnections().isEmpty())
    }

    @Test
    fun calibrationOnlyKeepsTheMinMaxWindow() {
        val window = SelfPorts.calibrate(count = 3, binder = { ServerSocket(0) })

        assertTrue("calibração precisa produzir janela válida", window.valid)
        assertEquals(3, window.samples)
        assertEquals(window, SelfPorts.snapshot())
        assertTrue("min <= max", window.min <= window.max)
        assertTrue(window.label().contains("janela-efimera-observada="))
        assertTrue("calibração não cria assinaturas de conexão", SelfPorts.registeredConnections().isEmpty())
        assertEquals(
            SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", window.min, "127.0.0.1", 443)
        )
    }

    @Test
    fun calibrationFailureLeavesTheVerdictIndeterminate() {
        val window = SelfPorts.calibrate(count = 2, binder = { throw java.io.IOException("sem porta") })

        assertFalse(window.valid)
        assertEquals(
            SelfPorts.PortVerdict.INDETERMINADO,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 40000, "127.0.0.1", 443)
        )
        assertTrue(window.label().contains("INDISPONIVEL"))
    }

    @Test
    fun resetClearsWindowAndRegisteredConnections() {
        SelfPorts.install(SelfPorts.Window(30000, 40000, 4))
        SelfPorts.register(SelfPorts.ConnectionIdentity("127.0.0.1", 45000, "127.0.0.1", 443))

        SelfPorts.reset()

        assertEquals(SelfPorts.Window(0, 0, 0), SelfPorts.snapshot())
        assertTrue(SelfPorts.registeredConnections().isEmpty())
        assertEquals(
            SelfPorts.PortVerdict.INDETERMINADO,
            SelfPorts.verdictForAcceptedConnection("127.0.0.1", 45000, "127.0.0.1", 443)
        )
    }
}
