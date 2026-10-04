package com.wzm.launcher.cdn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Estado do roteador CDNI local (servidor HTTPS + túnel) exposto para a UI. */
data class CdnRouterStatus(
    val httpsRunning: Boolean = false,
    val endpoints: String = "",
    val serverError: String? = null,
    val vpnActive: Boolean = false,
    val vpnError: String? = null
)

/**
 * Orquestra o roteamento local: servidor HTTPS (kernel, com certificado nosso) + VpnService
 * que intercepta o DNS dos hosts CDNI comprovados.
 */
object CdnRouterController {

    private var server: LocalHttpsServer? = null

    private val _status = MutableStateFlow(CdnRouterStatus())
    val status: StateFlow<CdnRouterStatus> = _status.asStateFlow()

    /** Inicia o servidor HTTPS local. Devolve false se nenhum listener subiu. */
    fun startHttps(context: Context): Boolean {
        val current = server
        if (current?.isRunning == true) {
            _status.value = _status.value.copy(httpsRunning = true)
            return true
        }
        val material = try {
            TlsContextFactory.FromBytes(
                readAsset(context, CdnRouterConfig.CERT_ASSET),
                CdnRouterConfig.CERT_PASSWORD.toCharArray()
            )
        } catch (e: Exception) {
            val message = "asset ${CdnRouterConfig.CERT_ASSET} indisponível: ${e.javaClass.simpleName}: ${e.message}"
            RequestLog.add("CDNI", "FALHA: $message")
            _status.value = _status.value.copy(httpsRunning = false, serverError = message)
            return false
        }
        val created = LocalHttpsServer(tlsMaterial = material)
        val started = created.start()
        server = if (started) created else null
        _status.value = _status.value.copy(
            httpsRunning = started,
            endpoints = created.boundEndpoints.joinToString(", ") { it.toString() },
            serverError = if (started) null else "nenhum listener HTTPS ativo (ver log)"
        )
        return started
    }

    fun stopHttps() {
        server?.stop()
        server = null
        _status.value = _status.value.copy(httpsRunning = false, endpoints = "")
    }

    /** Consenso do sistema (VpnService.prepare) ou null quando já autorizado. */
    fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn(context: Context, wzmPackage: String) {
        CdnVpnService.start(context, wzmPackage)
    }

    fun stopVpn(context: Context) {
        CdnVpnService.stop(context)
    }

    fun stopAll(context: Context) {
        stopVpn(context)
        stopHttps()
        _status.value = CdnRouterStatus()
    }

    fun onVpnStateChanged(active: Boolean, error: String? = null) {
        _status.value = _status.value.copy(vpnActive = active, vpnError = error)
    }

    /**
     * Copia a CA local (em claro) para o armazenamento do app e devolve o caminho.
     * Instalação é manual e documentada; nada é instalado silenciosamente.
     */
    fun exportCaCertificate(context: Context): String? {
        val target = File(context.getExternalFilesDir(null) ?: context.filesDir, CdnRouterConfig.CA_EXPORT_NAME)
        return try {
            context.assets.open(CdnRouterConfig.CA_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            RequestLog.add("CA", "CA local copiada para ${target.absolutePath} (instalação é manual, ver docs)")
            target.absolutePath
        } catch (e: Exception) {
            RequestLog.add("CA", "falha ao copiar a CA local: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun readAsset(context: Context, name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }
}
