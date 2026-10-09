package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * M4.1: a autoria de uma conexão pela API pública precisa ser lida com o significado **exato** do
 * retorno. `getConnectionOwnerUid` devolve `INVALID_UID` tanto para "conexão não encontrada" quanto
 * para "dono existe mas está fora da VPN que chamou" (AOSP: `appliesToUid`) — colapsar os dois em
 * "não é do jogo" seria o erro que este arquivo existe para impedir.
 *
 * O único teste de socket abaixo usa loopback sintético local; nenhum tráfego externo é gerado.
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
    fun everyOperationalOutcomeIsExplicitlyDescribedAsUnavailable() {
        listOf(
            ConnectionOwnership.Outcome.API_ANTIGA,
            ConnectionOwnership.Outcome.SERVICO_INDISPONIVEL,
            ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS,
            ConnectionOwnership.Outcome.ARGUMENTO_INVALIDO,
            ConnectionOwnership.Outcome.CONSULTA_FALHOU
        ).forEach { outcome ->
            val line = ConnectionOwnership.describe(ConnectionOwnership.Result(outcome))
            assertTrue("$outcome deve ser indisponível: $line", line.contains("dono=INDISPONIVEL"))
            assertTrue("$outcome deve manter seu código: $line", line.contains(outcome.code))
        }
    }

    @Test
    fun onlyIllegalArgumentMeansBadInputAndOtherFailuresStayDistinct() {
        assertEquals(
            ConnectionOwnership.Outcome.ARGUMENTO_INVALIDO,
            ConnectionOwnership.classifyException(IllegalArgumentException("protocol")).outcome
        )
        assertEquals(
            ConnectionOwnership.Outcome.CONSULTA_FALHOU,
            ConnectionOwnership.classifyException(IOException("platform query failed")).outcome
        )
        assertEquals(
            ConnectionOwnership.Outcome.CONSULTA_FALHOU,
            ConnectionOwnership.classifyException(IllegalStateException("binder failed")).outcome
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
    fun acceptedServerSocketMustBeReversedToQueryItsClientPeer() {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val client = Socket()
        var accepted: Socket? = null
        try {
            client.connect(InetSocketAddress("127.0.0.1", listener.localPort))
            accepted = listener.accept()

            val clientTuple = ConnectionOwnership.tupleForSocket(client)!!
            val acceptedServerTuple = ConnectionOwnership.tupleForSocket(accepted)!!
            val acceptedPeerTuple = ConnectionOwnership.peerTupleForAcceptedSocket(accepted)!!

            assertEquals("peer query equals the connecting client's own tuple", clientTuple, acceptedPeerTuple)
            assertEquals(acceptedServerTuple.remote, acceptedPeerTuple.local)
            assertEquals(acceptedServerTuple.local, acceptedPeerTuple.remote)
            assertFalse("accepted server tuple identifies the server side, not the peer", acceptedServerTuple == acceptedPeerTuple)
        } finally {
            runCatching { accepted?.close() }
            runCatching { client.close() }
            runCatching { listener.close() }
        }
    }

    @Test
    fun protocolConstantsMatchThePublicApiContract() {
        assertEquals(6, ConnectionOwnership.PROTOCOL_TCP)
        assertEquals(17, ConnectionOwnership.PROTOCOL_UDP)
    }
}
