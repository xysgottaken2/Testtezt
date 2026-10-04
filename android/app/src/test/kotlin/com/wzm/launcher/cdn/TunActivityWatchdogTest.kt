package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3: o vigia precisa distinguir "não chegou nada no túnel" de "chegou DNS, mas nenhum do CDNI"
 * — exatamente a diferença entre os dois testes do device (2026-10-04).
 */
class TunActivityWatchdogTest {

    private fun watchdog() = TunActivityWatchdog(
        noPacketAfterMs = 60_000,
        noCdnDnsAfterMs = 60_000,
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

        val second = watchdog.poll(121_000)
        assertFalse("o aviso de inatividade não pode repetir", second.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertFalse(second.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertTrue("resumo periódico continua", second.contains(TunWatchdogEvent.RESUMO_PERIODICO))
    }

    @Test
    fun packetsButNoCdnDnsStillWarnsAboutMissingCdnDns() {
        val watchdog = watchdog()
        watchdog.start(0)
        watchdog.onPacket(1_000)
        watchdog.onPacket(2_000)
        val events = watchdog.poll(70_000)
        assertFalse("houve pacote: sem aviso de túnel vazio", events.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertTrue("mas nenhum DNS do CDNI: aviso obrigatório", events.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertEquals(2, watchdog.packetCount)
        assertEquals(68L, watchdog.secondsSinceLastPacket(70_000))
    }

    @Test
    fun cdnDnsSeenSuppressesTheMissingCdnWarning() {
        val watchdog = watchdog()
        watchdog.start(0)
        watchdog.onPacket(1_000)
        watchdog.onCdnDns(1_500)
        val events = watchdog.poll(70_000)
        assertFalse("houve DNS do CDNI: sem aviso de consulta ausente", events.contains(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI))
        assertFalse("houve pacote: sem aviso de túnel vazio", events.contains(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN))
        assertTrue("resumo periódico continua acontecendo", events.all { it == TunWatchdogEvent.RESUMO_PERIODICO })
        assertEquals(1, watchdog.cdnDnsCount)
    }

    @Test
    fun messagesExplainWhatToCheck() {
        val watchdog = watchdog()
        watchdog.start(0)
        val counters = RequestCounters(tunPackets = 3, dnsQueries = 4, dnsForwarded = 4)
        val silent = watchdog.messageFor(TunWatchdogEvent.NENHUM_PACOTE_NO_TUN, counters, 70_000)
        assertTrue(silent.contains("nenhum pacote recebido no TUN"))
        assertTrue(silent.contains("Private DNS"))

        watchdog.onCdnDns(70_000)
        val noCdn = watchdog.messageFor(TunWatchdogEvent.NENHUMA_CONSULTA_CDNI, counters, 70_000)
        assertTrue(noCdn.contains("nenhuma consulta DNS dos hosts CDNI"))
        assertTrue(noCdn.contains("encaminhadas=4"))

        val summary = watchdog.messageFor(TunWatchdogEvent.RESUMO_PERIODICO, counters, 70_000)
        assertTrue(summary.contains("resumo do túnel"))
    }

    @Test
    fun pollBeforeStartDoesNothing() {
        assertTrue(watchdog().poll(999_999).isEmpty())
    }
}
