package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.4: o vigia precisa separar três situações distintas — "nada no túnel", "houve tráfego mas
 * nenhuma consulta CDNI" e "houve pacote, mas nenhum fluxo para o alvo". Sem isso, os dois testes
 * do device (5 conexões × 0 conexões) viram adivinhação.
 */
class TunActivityWatchdogTest {

    private fun watchdog() = TunActivityWatchdog(
        noPacketAfterMs = 60_000,
        noCdnDnsAfterMs = 60_000,
        noTargetFlowAfterMs = 60_000,
        summaryEveryMs = 60_000
    )

    @Test
    fun silentTunnelWarnsOnceAfterThreshold() {
        val watchdog = watchdog()
        watchdog.start(0)
        assertTrue("antes do limiar nada é emitido", watchdog.poll(30_000).isEmpty())

        val first = watchdog.poll(61_000)
        assertTrue(first.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertTrue(first.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertFalse("sem pacote não há o que dizer sobre fluxo ao alvo", first.contains(TunWatchdogEvent.NENHUM_FLUXO_PARA_O_ALVO))

        val second = watchdog.poll(121_000)
        assertFalse("o aviso de inatividade não pode repetir", second.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertFalse(second.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertTrue("resumo periódico continua", second.contains(TunWatchdogEvent.RESUMO_PERIODICO))
    }

    @Test
    fun packetsWithoutCdnFlowWarnAboutBothDnsAndTargetFlow() {
        val watchdog = watchdog()
        watchdog.start(0)
        watchdog.onPacket(1_000)
        watchdog.onPacket(2_000)
        val events = watchdog.poll(70_000)
        assertFalse(events.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertTrue("sem DNS do CDNI: aviso obrigatório", events.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertTrue("sem fluxo ao alvo: aviso obrigatório", events.contains(TunWatchdogEvent.NENHUM_FLUXO_PARA_O_ALVO))
        assertEquals(2, watchdog.packetCount)
        assertEquals(68L, watchdog.secondsSinceLastPacket(70_000))
    }

    @Test
    fun targetFlowSuppressesOnlyTheFlowWarning() {
        val watchdog = watchdog()
        watchdog.start(0)
        watchdog.onPacket(1_000)
        watchdog.onCdnDns(1_500)
        watchdog.onTargetFlow()
        val events = watchdog.poll(70_000)
        assertTrue(events.all { it == TunWatchdogEvent.RESUMO_PERIODICO })
        assertEquals(1, watchdog.targetFlowCount)
        assertEquals(1, watchdog.cdnDnsCount)
    }

    @Test
    fun messagesExplainWhatToCheckWithoutPresumingCause() {
        val watchdog = watchdog()
        watchdog.start(0)
        val counters = RequestCounters(tunPacketsTotal = 9, tunInvalidPackets = 2, dnsQueries = 4, dnsForwarded = 4)
        val silent = watchdog.messageFor(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN, counters, 70_000)
        assertTrue(silent.contains("nenhum pacote no TUN"))
        assertTrue("não pode afirmar causa", silent.contains("sem afirmar causa"))

        val noCdn = watchdog.messageFor(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI, counters, 70_000)
        assertTrue(noCdn.contains("nenhuma consulta DNS de host CDNI"))
        assertTrue(noCdn.contains("encaminhadas: 4"))

        val noFlow = watchdog.messageFor(TunWatchdogEvent.NENHUM_FLUXO_PARA_O_ALVO, counters, 70_000)
        assertTrue(noFlow.contains("houve 9 pacote(s)"))
        assertTrue(noFlow.contains("ipv6=0"))
        assertTrue(noFlow.contains("invalidos=2"))

        val summary = watchdog.messageFor(TunWatchdogEvent.RESUMO_PERIODICO, counters, 70_000)
        assertTrue(summary.contains("resumo do túnel"))
    }

    @Test
    fun pollBeforeStartDoesNothing() {
        assertTrue(watchdog().poll(999_999).isEmpty())
    }
}
