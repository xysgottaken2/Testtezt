package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classificação das falhas de handshake TLS vistas pelo servidor local.
 *
 * O caso central é a **evidência real do device (S23 Ultra, 2026-10-04)**: 5 conexões do WZM
 * chegaram ao listener e falharam com `SSLV3_ALERT_CERTIFICATE_UNKNOWN` — ou seja, o cliente
 * recusou a cadeia de certificados local. Esses testes garantem que o launcher classifica e
 * explica esse cenário (e não "desaparece" com a falha).
 */
class TlsTrustTest {

    @Test
    fun classifiesDeviceEvidenceAlert() {
        // String exata observada no device
        val failure = TlsTrust.analyze("Received fatal alert: SSLV3_ALERT_CERTIFICATE_UNKNOWN")
        assertEquals(TlsFailure.CLIENT_REJECTED_CERTIFICATE, failure.code)
        assertTrue(failure.hint.contains("CHEGOU ao servidor"))
        assertTrue(TlsTrust.isClientTrustFailure("SSLV3_ALERT_CERTIFICATE_UNKNOWN"))
    }

    @Test
    fun classifiesCommonClientTrustErrors() {
        for (message in listOf(
            "Received fatal alert: certificate_unknown",
            "javax.net.ssl.SSLHandshakeException: Received fatal alert: bad_certificate",
            "PKIX path building failed: unable to find valid certification path to requested target",
            "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
            "No trusted certificate found",
            "SSLHandshakeException: certificate verify failed"
        )) {
            assertEquals(
                "mensagem não classificada como recusa de certificado: $message",
                TlsFailure.CLIENT_REJECTED_CERTIFICATE,
                TlsTrust.analyze(message).code
            )
        }
    }

    @Test
    fun mentionsPinningWhenMessageSuggestsIt() {
        val failure = TlsTrust.analyze("Certificate pinning failure: certificate_unknown")
        assertEquals(TlsFailure.CLIENT_REJECTED_CERTIFICATE, failure.code)
        assertTrue(failure.hint.contains("PINNING"))
        assertTrue(failure.hint.contains("m3.2"))
    }

    @Test
    fun classifiesCleartextTrafficOnTlsPort() {
        assertEquals(TlsFailure.CLIENT_CLEARTEXT, TlsTrust.analyze("Unrecognized SSL message, plaintext connection?").code)
        assertFalse(TlsTrust.isClientTrustFailure("Unrecognized SSL message, plaintext connection?"))
    }

    @Test
    fun classifiesHostnameMismatch() {
        val failure = TlsTrust.analyze("No subject alternative names matching IP address 10.111.222.1 found")
        assertEquals(TlsFailure.HOSTNAME_MISMATCH, failure.code)
    }

    @Test
    fun classifiesExpiredCertificate() {
        val failure = TlsTrust.analyze("Received fatal alert: certificate_expired")
        assertEquals(TlsFailure.CERTIFICATE_EXPIRED, failure.code)
        assertTrue(failure.hint.contains("generate-local-cdni-cert.sh"))
    }

    @Test
    fun classifiesCipherAndClosedConnection() {
        assertEquals(TlsFailure.NO_COMMON_CIPHER, TlsTrust.analyze("no cipher suites in common").code)
        assertEquals(TlsFailure.NO_COMMON_CIPHER, TlsTrust.analyze("Received fatal alert: handshake_failure").code)
        assertEquals(TlsFailure.PEER_CLOSED, TlsTrust.analyze("Remote host terminated the handshake").code)
        assertEquals(TlsFailure.PEER_CLOSED, TlsTrust.analyze("Connection reset by peer").code)
    }

    @Test
    fun unknownMessageKeepsOriginalForInspection() {
        val failure = TlsTrust.analyze("algo totalmente inesperado")
        assertEquals(TlsFailure.UNKNOWN, failure.code)
        assertTrue(failure.hint.contains("mensagem original"))
        assertEquals(TlsFailure.UNKNOWN, TlsTrust.analyze(null).code)
        assertEquals(TlsFailure.UNKNOWN, TlsTrust.analyze("").code)
    }

    @Test
    fun everyFailureCarriesNonEmptyCodeAndHint() {
        val samples = listOf(
            "SSLV3_ALERT_CERTIFICATE_UNKNOWN",
            "Unrecognized SSL message",
            "no cipher suites in common",
            "Remote host terminated the handshake",
            "",
            "qualquer coisa"
        )
        for (sample in samples) {
            val failure = TlsTrust.analyze(sample)
            assertTrue("código vazio para '$sample'", failure.code.isNotBlank())
            assertTrue("dica vazia para '$sample'", failure.hint.isNotBlank())
        }
    }
}
