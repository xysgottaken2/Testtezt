package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.1: qual veredito dar a uma conexão que chega no listener — e com **qual nível de confiança**.
 *
 * Precedência (nunca invertida): uid resolvido pela API > marca do próprio processo (teste sintético
 * + janela de portas) > porta registrada pelo processo > INDETERMINADO. O veredito `VERIFIED` fica
 * reservado ao que a API resolveu e ao que o próprio processo abriu de propósito.
 */
class LoopbackOriginTest {

    private fun facts(
        roleLoopback: Boolean = true,
        peerPort: Int = 45000,
        ownerUid: Int? = null,
        launcherUid: Int = 10101,
        targetUid: Int? = 10692,
        duranteTeste: Boolean = false,
        portVerdict: SelfPorts.PortVerdict = SelfPorts.PortVerdict.INDETERMINADO
    ) = LoopbackOrigin.Facts(
        roleLoopback = roleLoopback,
        peerAddress = "127.0.0.1",
        peerPort = peerPort,
        ownerUidResolvido = ownerUid,
        launcherUid = launcherUid,
        targetUid = targetUid,
        duranteTesteSintetico = duranteTeste,
        portVerdict = portVerdict
    )

    @Test
    fun resolvedUidsDecideBeforeAnythingElse() {
        val launcher = LoopbackOrigin.conclude(facts(ownerUid = 10101))
        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, launcher.verdict)
        assertEquals(Evidence.VERIFIED, launcher.evidence)

        val target = LoopbackOrigin.conclude(facts(ownerUid = 10692))
        assertEquals(LoopbackOrigin.Verdict.APP_ALVO, target.verdict)
        assertEquals(Evidence.VERIFIED, target.evidence)
        assertTrue(
            "uid do app alvo é prova de autoria (a API só resolve uid dentro da VPN)",
            target.detail.contains("evidência de autoria")
        )

        val other = LoopbackOrigin.conclude(facts(ownerUid = 2000))
        assertEquals(LoopbackOrigin.Verdict.OUTRO_UID, other.verdict)
        assertEquals(Evidence.VERIFIED, other.evidence)
    }

    @Test
    fun launcherUidWinsOverTargetUidWhenTheyWouldBeEqual() {
        // Cinto de segurança de precedência: se os dois uids coincidirem, o veredito é "launcher".
        val conclusion = LoopbackOrigin.conclude(facts(ownerUid = 10101, launcherUid = 10101, targetUid = 10101))
        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, conclusion.verdict)
    }

    @Test
    fun syntheticWindowIsProbableNotProof() {
        val conclusion = LoopbackOrigin.conclude(
            facts(duranteTeste = true, portVerdict = SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO)
        )

        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, conclusion.verdict)
        assertEquals(
            "a mesma porta na janela do teste sintético é PROBABLE, não prova: ${conclusion.detail}",
            Evidence.PROBABLE,
            conclusion.evidence
        )
        assertTrue("precisa admitir que a janela é compartilhada: ${conclusion.detail}", conclusion.detail.contains("compartilhada"))
        assertTrue(
            "o nível exato precisa aparecer na linha do log",
            LoopbackOrigin.line(facts(duranteTeste = true, portVerdict = SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO))
                .contains("(PROBABLE)")
        )
    }

    @Test
    fun windowWithoutTheSyntheticMarkDoesNotAttributeAnything() {
        val conclusion = LoopbackOrigin.conclude(
            facts(duranteTeste = false, portVerdict = SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO)
        )
        assertEquals(LoopbackOrigin.Verdict.INDETERMINADO, conclusion.verdict)
    }

    @Test
    fun aPortRegisteredByThisProcessIsVerified() {
        val conclusion = LoopbackOrigin.conclude(
            facts(portVerdict = SelfPorts.PortVerdict.REGISTRADA_PELO_PROCESSO)
        )
        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, conclusion.verdict)
        assertEquals(Evidence.VERIFIED, conclusion.evidence)
    }

    @Test
    fun withoutUidAndWithoutMarkTheVerdictIsIndeterminateWithTheLoopbackCaveat() {
        val conclusion = LoopbackOrigin.conclude(facts())
        assertEquals(LoopbackOrigin.Verdict.INDETERMINADO, conclusion.verdict)
        assertEquals(Evidence.UNKNOWN, conclusion.evidence)

        val line = LoopbackOrigin.line(facts(), conclusion)
        assertTrue("a linha precisa citar o veredito", line.contains("origem-da-conexao=indeterminado"))
        assertTrue("a linha precisa citar a porta e o veredito dela", line.contains("porta-de-origem=45000"))
        assertTrue("loopback não passa pelo túnel precisa estar explícito", line.contains("não passa pelo túnel"))

        val viaTunnel = LoopbackOrigin.line(facts(roleLoopback = false))
        assertFalse(
            "a ressalva de loopback não vale para o endereço do túnel",
            viaTunnel.contains("não passa pelo túnel")
        )
    }

    @Test
    fun reportPairsVerdictWithTheSameTextUsedInTheLog() {
        val f = facts(portVerdict = SelfPorts.PortVerdict.REGISTRADA_PELO_PROCESSO)
        val report = LoopbackOrigin.report(f)

        assertEquals(LoopbackOrigin.conclude(f).verdict, report.verdict)
        assertEquals(LoopbackOrigin.line(f), report.text)
        assertTrue(report.text.contains("VERIFIED"))
    }

    @Test
    fun reportCarriesTheExactEvidenceLevelNotTheTypicalOne() {
        // O mesmo veredito (mesmo-processo) tem dois níveis: uid resolvido/porta registrada = VERIFIED;
        // janela do teste sintético = PROBABLE. O nível tem de vir da CONCLUSÃO, não do enum.
        val probable = LoopbackOrigin.report(
            facts(duranteTeste = true, portVerdict = SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO)
        )
        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, probable.verdict)
        assertEquals(Evidence.PROBABLE, probable.evidence)

        val verified = LoopbackOrigin.report(
            facts(portVerdict = SelfPorts.PortVerdict.REGISTRADA_PELO_PROCESSO)
        )
        assertEquals(LoopbackOrigin.Verdict.MESMO_PROCESSO, verified.verdict)
        assertEquals(Evidence.VERIFIED, verified.evidence)

        assertTrue(LoopbackOrigin.Verdict.INDETERMINADO.label.contains("indeterminado"))
    }
}
