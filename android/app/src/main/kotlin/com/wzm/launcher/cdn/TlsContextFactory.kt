package com.wzm.launcher.cdn

import java.io.InputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Fonte do material TLS (implementada com assets no app e com arquivo nos testes). */
interface TlsMaterial {
    fun openKeyStore(): InputStream
    fun password(): CharArray
}

/** Carrega o PKCS12 local e monta o [SSLContext] servidor. */
object TlsContextFactory {

    fun create(material: TlsMaterial): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        material.openKeyStore().use { keyStore.load(it, material.password()) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, material.password())
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagerFactory.keyManagers, null, null)
        return context
    }

    /** Materiais TLS a partir de bytes (usado pelo app via assets e pelos testes via arquivo). */
    class FromBytes(private val bytes: ByteArray, private val secret: CharArray) : TlsMaterial {
        override fun openKeyStore(): InputStream = bytes.inputStream()
        override fun password(): CharArray = secret
    }
}
