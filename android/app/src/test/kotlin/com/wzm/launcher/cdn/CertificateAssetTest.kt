package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Garante que o certificado embarcado (assets) é carregável com a senha configurada e cobre
 * exatamente os hosts CDNI comprovados, e que NÃO é um certificado da Activision.
 * Protege contra asset corrompido/errado no CI.
 */
class CertificateAssetTest {

    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("android/app/src/main/assets/$name")
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val direct = File(directory, "android/app/src/main/assets/$name")
            if (direct.isFile) return direct
            val module = File(directory, "app/src/main/assets/$name")
            if (module.isFile) return module
            directory = directory.parentFile
        }
        throw AssertionError("asset $name não encontrado; rode: bash scripts/generate-local-cdni-cert.sh")
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            asset(CdnRouterConfig.CERT_ASSET).inputStream().use {
                load(it, CdnRouterConfig.CERT_PASSWORD.toCharArray())
            }
        }

    private fun aliases(store: KeyStore): List<String> = java.util.Collections.list(store.aliases())

    private fun leaf(): X509Certificate {
        val store = keyStore()
        val alias = aliases(store).firstOrNull()
        assertNotNull("PKCS12 sem alias", alias)
        return store.getCertificate(alias!!) as X509Certificate
    }

    @Test
    fun privateKeyIsPresentWithConfiguredPassword() {
        val store = keyStore()
        val alias = aliases(store).first()
        val entry = store.getEntry(alias, KeyStore.PasswordProtection(CdnRouterConfig.CERT_PASSWORD.toCharArray()))
        assertTrue("entrada não contém chave privada", entry is KeyStore.PrivateKeyEntry)
    }

    @Test
    fun certificateCoversVerifiedCdnHost() {
        val sans = leaf().subjectAlternativeNames
            ?.filter { it[0] == 2 }
            ?.map { it[1].toString() }
            ?: emptyList()
        assertTrue("SAN ausente: prod.cdni.callofduty.com", sans.contains("prod.cdni.callofduty.com"))
        assertTrue("SAN ausente: *.cdni.callofduty.com", sans.contains("*.cdni.callofduty.com"))
        assertTrue(CdnRouterConfig.INTERCEPT_HOSTS.all { it in sans })
    }

    @Test
    fun certificateIsOursNotActivision() {
        val certificate = leaf()
        val subject = certificate.subjectX500Principal.name.lowercase()
        val issuer = certificate.issuerX500Principal.name.lowercase()
        assertTrue("subject inesperado: $subject", subject.contains("wzm offline preservation"))
        assertTrue("issuer inesperado: $issuer", issuer.contains("wzm offline preservation"))
        assertTrue(
            "certificado não pode se passar por emissor oficial",
            !subject.contains("activision") && !issuer.contains("activision")
        )
        assertTrue("folha não pode ser CA", certificate.basicConstraints < 0)
    }

    @Test
    fun certificateIsCurrentlyValid() {
        leaf().checkValidity()
    }

    @Test
    fun caFileMatchesLeafIssuer() {
        val ca = CertificateFactory.getInstance("X.509")
            .generateCertificate(asset(CdnRouterConfig.CA_ASSET).inputStream()) as X509Certificate
        ca.checkValidity()
        assertTrue("CA precisa ter basicConstraints de CA", ca.basicConstraints >= 0)
        assertEquals(leaf().issuerX500Principal, ca.subjectX500Principal)
    }
}
