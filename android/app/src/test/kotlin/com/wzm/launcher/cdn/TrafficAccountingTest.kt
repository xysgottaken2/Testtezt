package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.6: testes **puros** de diferenças e formatação da contabilidade UID (sem Android). `false` significa
 * apenas "nenhum crescimento foi medido entre estas amostras"; não prova que um processo específico não
 * tentou rede nem determina o caminho. A leitura real de [android.net.TrafficStats] é outro passo.
 */
class TrafficAccountingTest {

    private fun stats(
        bytes: Long? = null,
        txPackets: Long? = null,
        rxPackets: Long? = null
    ): TrafficAccounting.UidStats {
        val tx = bytes?.let { it / 2 }
        val rx = bytes?.let { it - it / 2 }
        return TrafficAccounting.UidStats(
            uid = 10692,
            txBytes = tx,
            rxBytes = rx,
            txPackets = txPackets,
            rxPackets = rxPackets,
            note = "ok"
        )
    }

    @Test
    fun grewReportsMeasuredDeltaWithoutTreatingNoGrowthAsAbsenceOfNetwork() {
        assertEquals(true, TrafficAccounting.grew(stats(bytes = 1_000), stats(bytes = 5_000)))
        assertEquals(false, TrafficAccounting.grew(stats(bytes = 5_000), stats(bytes = 5_000)))
        assertNull(
            "sem contadores não se pode afirmar nada",
            TrafficAccounting.grew(stats(), stats())
        )
    }

    @Test
    fun grewFallsBackToPacketsWhenBytesAreUnavailable() {
        val before = stats(bytes = null, txPackets = 10, rxPackets = 4)
        val after = stats(bytes = null, txPackets = 12, rxPackets = 4)
        assertEquals(true, TrafficAccounting.grew(before, after))
    }

    @Test
    fun deltaShowsBytesAndPacketsWithSign() {
        val before = stats(bytes = 1_000, txPackets = 10, rxPackets = 4)
        val after = stats(bytes = 3_072, txPackets = 13, rxPackets = 9)
        val line = TrafficAccounting.delta(before, after)
        assertTrue(line, line.contains("bytes=+2072 B"))
        assertTrue(line, line.contains("tx-pacotes=+3"))
        assertTrue(line, line.contains("rx-pacotes=+5"))
    }

    @Test
    fun unavailableAccountingIsSaidOutLoudNotAsZero() {
        val unavailable = TrafficAccounting.UidStats(10692, null, null, null, null, "INDISPONIVEL (SecurityException)")
        val line = TrafficAccounting.delta(unavailable, unavailable)
        assertTrue(line, line.contains("sem contabilidade"))
        assertFalse("não pode sair como 0 B", line.contains("+0 B"))
        assertFalse(TrafficAccounting.line("teste", unavailable).contains("tx=0"))
    }

    @Test
    fun formatBytesUsesHumanUnits() {
        assertEquals("512 B", TrafficAccounting.formatBytes(512))
        assertEquals("1.0 KiB", TrafficAccounting.formatBytes(1024))
        assertEquals("1.5 MiB", TrafficAccounting.formatBytes(1024L * 1024 * 3 / 2))
    }

    @Test
    fun accountingLineCarriesUidAndBothDirections() {
        val line = TrafficAccounting.line("vigia", stats(bytes = 4_000, txPackets = 2, rxPackets = 3))
        assertTrue(line, line.contains("uid 10692"))
        assertTrue(line, line.contains("tx=2000 B"))
        assertTrue(line, line.contains("rx=2000 B"))
        assertTrue(line, line.contains("pacotes tx=2 rx=3"))
    }
}
