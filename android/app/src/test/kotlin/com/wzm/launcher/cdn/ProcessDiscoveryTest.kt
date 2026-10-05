package com.wzm.launcher.cdn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.6 (item 1 do pedido): "diferencie app em primeiro plano, processo de jogo e processos auxiliares".
 *
 * O teste fixa a disciplina: quando a API não devolve processos de OUTRO pacote (comportamento normal
 * desde a API 26), o log diz `INDISPONIVEL` com o motivo — **nunca** converte lista vazia em
 * "o jogo não está rodando".
 */
class ProcessDiscoveryTest {

    private fun facts(
        own: List<String> = listOf("pid=100 nome=com.wzm.launcher.debug importance=100"),
        ownAvailable: Boolean = true,
        declared: List<String> = listOf("com.activision.callofduty.warzone", ":game"),
        declaredAvailable: Boolean = true,
        running: List<String> = emptyList()
    ) = ProcessDiscovery.ProcessFacts(
        targetPackage = "com.activision.callofduty.warzone",
        targetUid = 10692,
        ownUid = 10123,
        ownProcesses = own,
        ownProcessesAvailable = ownAvailable,
        ownProcessesNote = "getRunningAppProcesses devolveu null; processo do launcher indisponível nesta amostra",
        targetRunningProcesses = running,
        targetRunningNote = "nenhum processo do UID 10692 apareceu nesta lista informativa; " +
            "a API pode filtrar processos de outros apps e não prova ausência de execução",
        targetDeclaredProcesses = declared,
        declaredAvailable = declaredAvailable
    )

    @Test
    fun ownProcessesAreListedAndAuxiliaryProcessesAreVisible() {
        val lines = ProcessDiscovery.describe(facts())
        assertTrue(lines.first(), lines.first().contains("processos do launcher"))
        assertTrue(lines.first(), lines.first().contains("importance=100"))
    }

    @Test
    fun unavailableOwnProcessesAreExplicit() {
        val lines = ProcessDiscovery.describe(facts(own = emptyList(), ownAvailable = false))
        assertTrue(lines.first(), lines.first().contains("INDISPONIVEL"))
        assertTrue("o motivo exato precisa aparecer", lines.first().contains("getRunningAppProcesses"))
    }

    @Test
    fun declaredProcessesAreStaticFactsNotProofOfExecution() {
        val line = ProcessDiscovery.describe(facts()).first { it.contains("declarados") }
        assertTrue(line, line.contains(":game"))
        assertTrue("precisa dizer que é fato estático", line.contains("não prova execução"))
    }

    @Test
    fun emptyRunningListIsIndisponivelNeverAManifestationOfAbsence() {
        val line = ProcessDiscovery.describe(facts(running = emptyList())).first { it.contains("em execução") }
        assertTrue(line, line.contains("INDISPONIVEL"))
        assertTrue(
            "lista vazia não pode virar afirmação de que o app não roda",
            line.contains("lista vazia NÃO prova")
        )
    }

    @Test
    fun runningTargetProcessesAreReportedWhenTheApiDoesReturnThem() {
        val line = ProcessDiscovery
            .describe(facts(running = listOf("pid=4242 nome=com.activision.callofduty.warzone")))
            .first { it.contains("em execução") }
        assertTrue(line, line.contains("pid=4242"))
        assertTrue(line, line.contains("retornado(s) nesta amostra informativa"))
        assertTrue(line, line.contains("não liga PID a socket/tráfego"))
    }

    @Test
    fun uidAccountingCaveatIsAlwaysStated() {
        val line = ProcessDiscovery.describe(facts()).first { it.contains("contadores de rede são por UID") }
        assertTrue(line, line.contains("10692"))
        assertTrue("processos auxiliares entram na mesma conta", line.contains("auxiliares"))
    }
}
