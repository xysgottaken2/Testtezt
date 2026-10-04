package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * M4.1: a janela de portas efêmeras deste processo. Ela existe para uma dedução **limitada**:
 * dizer "essa porta de origem está na janela que o kernel entregou a mim" — nunca "essa conexão é
 * minha". A janela é compartilhada por todo o aparelho, então o veredito é PROBABLE.
 *
 * O que É prova: uma porta que o próprio processo registrou ao abrir a conexão de propósito
 * (teste sintético / teste de controle).
 */
class SelfPortsTest {

    @After
    fun tearDown() {
        SelfPorts.reset()
    }

    @Test
    fun withoutCalibrationEveryPortIsIndeterminate() {
        SelfPorts.reset()
        assertEquals(SelfPorts.PortVerdict.INDETERMINADO, SelfPorts.verdictFor(40000))
        assertFalse(SelfPorts.snapshot().valid)
    }

    @Test
    fun windowSplitsInsideFromOutsideAndNeverCallsItProof() {
        SelfPorts.install(SelfPorts.Window(30000, 40000, 4))

        assertEquals(SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO, SelfPorts.verdictFor(30000))
        assertEquals(SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO, SelfPorts.verdictFor(35555))
        assertEquals(SelfPorts.PortVerdict.FORA_DA_JANELA, SelfPorts.verdictFor(29999))
        assertEquals(SelfPorts.PortVerdict.FORA_DA_JANELA, SelfPorts.verdictFor(40001))

        val label = SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO.label
        assertTrue("a janela não é prova de autoria: $label", label.contains("nao prova"))
    }

    @Test
    fun aRegisteredPortWinsOverTheWindowAndIsMarkedAsVerified() {
        SelfPorts.install(SelfPorts.Window(30000, 40000, 4))
        SelfPorts.register(45000)

        assertEquals(SelfPorts.PortVerdict.REGISTRADA_PELO_PROCESSO, SelfPorts.verdictFor(45000))
        assertTrue(SelfPorts.registeredPorts().contains(45000))
        assertEquals(SelfPorts.PortVerdict.FORA_DA_JANELA, SelfPorts.verdictFor(45001))
    }

    @Test
    fun invalidPortsAreIgnored() {
        SelfPorts.register(0)
        SelfPorts.register(-5)
        SelfPorts.register(70000)
        assertTrue(SelfPorts.registeredPorts().isEmpty())
    }

    @Test
    fun calibrationOnlyKeepsTheMinMaxWindow() {
        val window = SelfPorts.calibrate(count = 3, binder = { ServerSocket(0) })

        assertTrue("calibração precisa produzir janela válida", window.valid)
        assertEquals(3, window.samples)
        assertEquals(window, SelfPorts.snapshot())
        assertTrue("min <= max", window.min <= window.max)
        assertTrue(window.label().contains("janela-do-processo="))
        assertTrue("entrou pelo caminho da janela, não por registro", SelfPorts.registeredPorts().isEmpty())
        assertEquals(SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO, SelfPorts.verdictFor(window.min))
    }

    @Test
    fun calibrationFailureLeavesTheVerdictIndeterminate() {
        val window = SelfPorts.calibrate(count = 2, binder = { throw java.io.IOException("sem porta") })

        assertFalse(window.valid)
        assertEquals(SelfPorts.PortVerdict.INDETERMINADO, SelfPorts.verdictFor(40000))
        assertTrue(window.label().contains("INDISPONIVEL"))
    }

    @Test
    fun resetClearsWindowAndRegisteredPorts() {
        SelfPorts.install(SelfPorts.Window(30000, 40000, 4))
        SelfPorts.register(45000)

        SelfPorts.reset()

        assertEquals(SelfPorts.Window(0, 0, 0), SelfPorts.snapshot())
        assertTrue(SelfPorts.registeredPorts().isEmpty())
        assertEquals(SelfPorts.PortVerdict.INDETERMINADO, SelfPorts.verdictFor(45000))
    }
}
