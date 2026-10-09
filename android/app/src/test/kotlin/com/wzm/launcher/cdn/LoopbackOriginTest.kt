package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4.1: distingue UID, processo exato e mera correlação temporal sem promover localhost a tráfego WZM. */
class LoopbackOriginTest {

    private fun facts(
        roleLoopback: Boolean = true,
        peerPort: Int = 45000,
        peerUid: Int? = null,
        ownerOutcome: ConnectionOwnership.Outcome =
            if (peerUid == null) ConnectionOwnership.Outcome.INVALID_UID else ConnectionOwnership.Outcome.RESOLVIDO,
        launcherUid: Int = 10101,
        targetUid: Int? = 10692,
        duranteTeste: Boolean = false,
        portVerdict: SelfPorts.PortVerdict = SelfPorts.PortVerdict.INDETERMINADO,
        registeredProcessPid: Int? = null
    ) = LoopbackOrigin.Facts(
        roleLoopback = roleLoopback,
        peerAddress = "127.0.0.1",
        peerPort = peerPort,
        peerOwnerResult = ConnectionOwnership.Result(ownerOutcome, uid = peerUid),
        launcherUid = launcherUid,
        targetUid = targetUid,
        duranteTesteSintetico = duranteTeste,
        portVerdict = portVerdict,
        registeredProcessPid = registeredProcessPid
    )

    @Test
    fun resolvedUidIdentifiesUidButNotPidOrProcess() {
        val launcher = LoopbackOrigin.conclude(facts(peerUid = 10101))
        assertEquals(LoopbackOrigin.Verdict.UID_LAUNCHER, launcher.verdict)
        assertEquals(Evidence.VERIFIED, launcher.evidence)
        assertTrue(launcher.detail.contains("não identifica PID/processo"))

        val target = LoopbackOrigin.conclude(facts(peerUid = 10692))
        assertEquals(LoopbackOrigin.Verdict.UID_APP_ALVO, target.verdict)
        assertEquals(Evidence.VERIFIED, target.evidence)
        assertTrue(target.detail.contains("nem prova tráfego externo do WZM"))

        val other = LoopbackOrigin.conclude(facts(peerUid = 2000))
        assertEquals(LoopbackOrigin.Verdict.OUTRO_UID, other.verdict)
        assertEquals(Evidence.VERIFIED, other.evidence)
        assertTrue(other.detail.contains("não identifica processo/PID"))
    }

    @Test
    fun launcherUidWinsOverTargetUidWhenTheyWouldBeEqual() {
        val conclusion = LoopbackOrigin.conclude(
            facts(peerUid = 10101, launcherUid = 10101, targetUid = 10101)
        )
        assertEquals(LoopbackOrigin.Verdict.UID_LAUNCHER, conclusion.verdict)
    }

    @Test
    fun syntheticWindowIsOnlyAProbableHintAndNotCausalProof() {
        val synthetic = facts(
            duranteTeste = true,
            portVerdict = SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA
        )
        val conclusion = LoopbackOrigin.conclude(synthetic)
        assertEquals(LoopbackOrigin.Verdict.POSSIVEL_LAUNCHER, conclusion.verdict)
        assertEquals(Evidence.PROBABLE, conclusion.evidence)
        assertTrue(conclusion.detail.contains("não provam"))
        assertTrue(LoopbackOrigin.line(synthetic).contains("(PROBABLE)"))
    }

    @Test
    fun windowWithoutTheSyntheticMarkDoesNotAttributeAnything() {
        val conclusion = LoopbackOrigin.conclude(
            facts(
                duranteTeste = false,
                portVerdict = SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA
            )
        )
        assertEquals(LoopbackOrigin.Verdict.INDETERMINADO, conclusion.verdict)
    }

    @Test
    fun exactRegisteredConnectionTupleIdentifiesTheLauncherControlSocket() {
        val conclusion = LoopbackOrigin.conclude(
            facts(
                peerUid = 10101,
                portVerdict = SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO,
                registeredProcessPid = 1234
            )
        )
        assertEquals(LoopbackOrigin.Verdict.PROCESSO_LAUNCHER, conclusion.verdict)
        assertEquals(Evidence.VERIFIED, conclusion.evidence)
        assertTrue(conclusion.detail.contains("tupla completa"))
        assertTrue(conclusion.detail.contains("PID=1234"))
        assertTrue(
            LoopbackOrigin.line(
                facts(
                    portVerdict = SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO,
                    registeredProcessPid = 1234
                )
            ).contains("pid-do-processo-registrado=1234")
        )
    }

    @Test
    fun invalidUidRemainsDistinctFromPermissionOrApiFailure() {
        val invalid = LoopbackOrigin.report(facts())
        assertEquals(LoopbackOrigin.Verdict.INDETERMINADO, invalid.verdict)
        assertEquals(Evidence.UNKNOWN, invalid.evidence)
        assertTrue(invalid.text.contains("INVALID_UID"))
        assertTrue(invalid.text.contains("owner-outcome=INVALID_UID"))
        assertTrue(invalid.text.contains("fora do escopo da VPN"))

        val denied = LoopbackOrigin.report(facts(ownerOutcome = ConnectionOwnership.Outcome.SECURITY_EXCEPTION))
        assertEquals(Evidence.UNKNOWN, denied.evidence)
        assertTrue(denied.text.contains("SecurityException"))
        assertFalse("erro de permissão não pode ser rotulado como INVALID_UID", denied.text.contains("owner-outcome=INVALID_UID"))

        val failed = LoopbackOrigin.report(facts(ownerOutcome = ConnectionOwnership.Outcome.CONSULTA_FALHOU))
        assertTrue(failed.text.contains("erro da plataforma"))
    }

    @Test
    fun reportPairsVerdictWithTheSameTextUsedInTheLog() {
        val f = facts(portVerdict = SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO)
        val report = LoopbackOrigin.report(f)

        assertEquals(LoopbackOrigin.conclude(f).verdict, report.verdict)
        assertEquals(LoopbackOrigin.line(f), report.text)
        assertTrue(report.text.contains("VERIFIED"))
    }

    @Test
    fun reportKeepsProbableUidAndUnknownEvidenceLevelsSeparate() {
        val probable = LoopbackOrigin.report(
            facts(
                duranteTeste = true,
                portVerdict = SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA
            )
        )
        assertEquals(LoopbackOrigin.Verdict.POSSIVEL_LAUNCHER, probable.verdict)
        assertEquals(Evidence.PROBABLE, probable.evidence)

        val uid = LoopbackOrigin.report(facts(peerUid = 10692))
        assertEquals(LoopbackOrigin.Verdict.UID_APP_ALVO, uid.verdict)
        assertEquals(Evidence.VERIFIED, uid.evidence)

        assertEquals(Evidence.UNKNOWN, LoopbackOrigin.Verdict.INDETERMINADO.evidence)
    }
}
