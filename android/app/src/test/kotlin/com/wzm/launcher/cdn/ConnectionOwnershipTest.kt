package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket

/**
 * M4.1: a autoria de uma conexão pela API pública precisa ser lida com o significado **exato** do
 * retorno. `getConnectionOwnerUid` devolve `INVALID_UID` tanto para "conexão não encontrada" quanto
 * para "dono existe mas está fora da VPN que chamou" (AOSP: `appliesToUid`) — colapsar os dois em
 * "não é do jogo" seria o erro que este arquivo existe para impedir.
 *
 * Nada aqui toca rede ou Android: são classificações puras.
 */
class ConnectionOwnershipTest {

    @Test
    fun invalidUidIsAmbiguousAndNeverMeansAnotherApp() {
        val result = ConnectionOwnership.classifyUid(-1)

        assertEquals(ConnectionOwnership.Outcome.INVALID_UID, result.outcome)
        assertEquals(null, result.uid)
        assertFalse("INVALID_UID não prova autoria", result.provesOwner)

        val line = ConnectionOwnership.describe(result)
        assertTrue("a linha deve dizer que NÃO foi resolvido: $line", line.contains("dono=NAO_RESOLVIDO"))
        assertTrue("a linha deve citar INVALID_UID: $line", line.contains("INVALID_UID"))
        assertFalse("não pode concluir \"de outro app\": $line", line.contains("OUTRO"))
        assertFalse("não pode concluir \"não é do jogo\": $line", line.contains("nao e do jogo"))
        assertFalse("não pode concluir \"não é do jogo\": $line", line.contains("não é do jogo"))
    }

    @Test
    fun invalidUidHintCarriesTheAospReason() {
        val hint = ConnectionOwnership.Outcome.INVALID_UID.hint
        assertTrue("o motivo (appliesToUid) precisa viajar no texto: $hint", hint.contains("appliesToUid"))
        assertTrue(
            "o texto precisa admitir as duas causas distintas: $hint",
            hint.contains("ausente") && hint.contains("FORA da VPN")
        )
    }

    @Test
    fun resolvedUidProvesOwnerAndShowsThePackage() {
        val result = ConnectionOwnership.classifyUid(10692, listOf("com.activision.callofduty.warzone"))

        assertEquals(ConnectionOwnership.Outcome.RESOLVIDO, result.outcome)
        assertEquals(10692, result.uid)
        assertTrue("uid resolvido é a única prova de autoria", result.provesOwner)
        assertEquals("dono=uid=10692 (com.activision.callofduty.warzone)", ConnectionOwnership.describe(result))
    }

    @Test
    fun resolvedUidWithoutPackageStillDescribesTheUid() {
        assertEquals("dono=uid=2000", ConnectionOwnership.describe(ConnectionOwnership.classifyUid(2000)))
    }

    @Test
    fun securityExceptionIsNotConfusedWithMissingConnection() {
        val result = ConnectionOwnership.classifyException(SecurityException("no permission"))

        assertEquals(ConnectionOwnership.Outcome.SECURITY_EXCEPTION, result.outcome)
        assertFalse(result.provesOwner)
        val line = ConnectionOwnership.describe(result)
        assertTrue("precisa marcar indisponível, não \"não resolvido\": $line", line.contains("INDISPONIVEL"))
        assertTrue("precisa citar a causa: $line", line.contains("SEM_PERMISSAO"))
    }

    @Test
    fun illegalArgumentMeansUnsupportedProtocolAndOthersMeanUnusableAddresses() {
        assertEquals(
            ConnectionOwnership.Outcome.ARGUMENTO_INVALIDO,
            ConnectionOwnership.classifyException(IllegalArgumentException("protocol")).outcome
        )
        assertEquals(
            ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS,
            ConnectionOwnership.classifyException(IOException("sem tupla")).outcome
        )
    }

    @Test
    fun socketWithoutAddressesCannotProduceAQueryTuple() {
        // Socket não conectado: sem par de endereços. A guarda de `querySocket` usa exatamente estas
        // duas conversões — se elas devolvem null, a consulta é INDISPONIVEL e não inventa uid.
        val socket = Socket()
        assertEquals(null, ConnectionOwnership.toInet(socket.localSocketAddress))
        assertEquals(null, ConnectionOwnership.toInet(socket.remoteSocketAddress))
        assertEquals(null, ConnectionOwnership.toInet(null))
    }

    @Test
    fun protocolConstantsMatchThePublicApiContract() {
        assertEquals(6, ConnectionOwnership.PROTOCOL_TCP)
        assertEquals(17, ConnectionOwnership.PROTOCOL_UDP)
    }
}
