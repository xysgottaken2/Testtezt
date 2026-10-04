package com.wzm.launcher.cdn

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.3: o cabeçalho de sessão é o que permite comparar dois testes do device (primeira execução
 * vs. seguintes, per-app aplicado ou não, qual versão do WZM estava instalada).
 */
class SessionReportTest {

    private fun facts(
        perAppApplied: Boolean = true,
        perAppError: String? = null,
        installed: Boolean = true,
        sessionNumber: Int = 3,
        previous: Int = 2
    ) = SessionFacts(
        sessionNumber = sessionNumber,
        previousSessions = previous,
        launcherVersion = "0.3.0",
        launcherTargetSdk = 34,
        targetPackage = "com.activision.callofduty.warzone",
        targetInstalled = installed,
        targetVersionName = if (installed) "3.10.0" else null,
        targetUid = if (installed) 10234 else null,
        perAppApplied = perAppApplied,
        perAppError = perAppError
    )

    private fun joined(facts: SessionFacts): String = SessionReport.lines(facts).joinToString("\n")

    @Test
    fun sessionHeaderIdentifiesRunAndTargetApp() {
        val text = joined(facts())
        assertTrue(text.contains("sessão=#3"))
        assertTrue(text.contains("execuções anteriores registradas=2"))
        assertTrue(text.contains("com.activision.callofduty.warzone"))
        assertTrue(text.contains("instalado=sim"))
        assertTrue(text.contains("versão=3.10.0"))
        assertTrue(text.contains("uid=10234"))
        assertTrue(text.contains("rota=10.111.222.0/24"))
        assertTrue(text.contains("listeners previstos=10.111.222.1:443, 127.0.0.1:443"))
    }

    @Test
    fun missingTargetPackageIsExplicit() {
        val text = joined(facts(perAppApplied = false, perAppError = "sem pacote alvo no intent (o túnel DNS valeria para TODOS os apps)", installed = false))
        assertTrue(text.contains("instalado=não"))
        assertTrue(text.contains("per-app: NÃO aplicado"))
        assertTrue(text.contains("vale para todos os apps"))
    }

    @Test
    fun perAppAppliedUsesTheSystemAnswer() {
        val text = joined(facts())
        assertTrue(text.contains("addAllowedApplication(com.activision.callofduty.warzone)"))
        assertTrue(text.contains("somente esse app deve entrar no túnel"))
    }
}
