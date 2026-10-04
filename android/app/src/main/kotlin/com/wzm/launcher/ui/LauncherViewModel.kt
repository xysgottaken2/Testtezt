package com.wzm.launcher.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wzm.launcher.LauncherConfig
import com.wzm.launcher.cdn.CdnRouterController
import com.wzm.launcher.cdn.CdnRouterStatus
import com.wzm.launcher.cdn.RequestCounters
import com.wzm.launcher.cdn.RequestLog
import com.wzm.launcher.server.ServerController
import com.wzm.launcher.server.ServerStatus
import com.wzm.launcher.wzm.WzmLauncher
import com.wzm.launcher.wzm.WzmStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LauncherUiState(
    val serverStatus: ServerStatus = ServerStatus.PARADO,
    val wzmStatus: WzmStatus = WzmStatus.NAO_DETECTADO,
    val wzmVersion: String? = null,
    val isServerStarting: Boolean = false,
    val router: CdnRouterStatus = CdnRouterStatus(),
    val counters: RequestCounters = RequestCounters(),
    val logs: List<String> = emptyList()
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val serverController = ServerController()
    private val wzmLauncher = WzmLauncher(application.applicationContext)

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    /** Consentimento de VPN pendente — a Activity precisa lançar este Intent. */
    private val _consentRequest = MutableStateFlow<Intent?>(null)
    val consentRequest: StateFlow<Intent?> = _consentRequest.asStateFlow()

    private var pendingVpnStart = false

    init {
        log("Launcher iniciado (M3: roteamento CDNI local)")
        refreshWzmStatus()
        _uiState.value = _uiState.value.copy(serverStatus = serverController.getStatus())
        viewModelScope.launch {
            RequestLog.entries.collect { entries ->
                _uiState.value = _uiState.value.copy(logs = entries)
            }
        }
        viewModelScope.launch {
            RequestLog.counters.collect { counters ->
                _uiState.value = _uiState.value.copy(counters = counters)
            }
        }
        viewModelScope.launch {
            CdnRouterController.status.collect { status ->
                _uiState.value = _uiState.value.copy(router = status)
            }
        }
    }

    fun refreshWzmStatus() {
        val installed = wzmLauncher.isInstalled()
        val version = wzmLauncher.getInstalledVersion()
        val status = if (installed) WzmStatus.INSTALADO else WzmStatus.NAO_DETECTADO
        _uiState.value = _uiState.value.copy(wzmStatus = status, wzmVersion = version)
        if (installed) {
            log("WZM detectado: $version")
        } else {
            log("WZM não detectado (${LauncherConfig.WZM_PACKAGE})")
        }
    }

    // ---------------- servidor stub antigo (M2, porta 18081) ----------------

    fun startServer() {
        if (_uiState.value.serverStatus == ServerStatus.ONLINE || _uiState.value.serverStatus == ServerStatus.INICIANDO) {
            log("Servidor já está ${_uiState.value.serverStatus}")
            return
        }
        viewModelScope.launch {
            log("Servidor stub iniciando...")
            _uiState.value = _uiState.value.copy(serverStatus = ServerStatus.INICIANDO, isServerStarting = true)
            val result = serverController.start()
            _uiState.value = _uiState.value.copy(serverStatus = result, isServerStarting = false)
            when (result) {
                ServerStatus.ONLINE -> log("Servidor stub ONLINE em ${LauncherConfig.HOST}:${LauncherConfig.PORT}")
                ServerStatus.ERRO -> log("Erro ao iniciar servidor stub")
                else -> log("Servidor stub: $result")
            }
        }
    }

    fun stopServer() {
        if (_uiState.value.serverStatus == ServerStatus.PARADO || _uiState.value.serverStatus == ServerStatus.PARANDO) {
            log("Servidor já está ${_uiState.value.serverStatus}")
            return
        }
        viewModelScope.launch {
            log("Servidor stub parando...")
            _uiState.value = _uiState.value.copy(serverStatus = ServerStatus.PARANDO)
            val result = serverController.stop()
            _uiState.value = _uiState.value.copy(serverStatus = result)
            log("Servidor stub $result")
        }
    }

    // ---------------- roteador CDNI local (M3) ----------------

    fun startRouter() {
        log("Iniciando roteador CDNI local (HTTPS 443 + DNS)...")
        val started = CdnRouterController.startHttps(getApplication())
        val status = CdnRouterController.status.value
        if (started) {
            log("HTTPS local ativo em ${status.endpoints}")
        } else {
            log("Roteador: nenhum listener HTTPS ativo — ${status.serverError ?: "ver log"}")
        }
        val consent = CdnRouterController.vpnConsentIntent(getApplication())
        if (consent != null) {
            pendingVpnStart = true
            log("Autorização de VPN necessária — confirme o diálogo do sistema")
            _consentRequest.value = consent
        } else {
            startVpnNow()
        }
    }

    fun onConsentLaunched() {
        _consentRequest.value = null
    }

    fun onVpnConsentResult(granted: Boolean) {
        if (!granted) {
            pendingVpnStart = false
            log("Roteador: consentimento de VPN NEGADO pelo usuário")
            return
        }
        log("Roteador: consentimento concedido")
        if (pendingVpnStart) {
            pendingVpnStart = false
            startVpnNow()
        }
    }

    private fun startVpnNow() {
        CdnRouterController.startVpn(getApplication(), LauncherConfig.WZM_PACKAGE)
        log("VPN: pedido de túnel enviado ao sistema (per-app: ${LauncherConfig.WZM_PACKAGE})")
    }

    fun stopRouter() {
        log("Parando roteador CDNI local...")
        CdnRouterController.stopAll(getApplication())
        log("Roteador CDNI parado")
    }

    fun installCa() {
        val path = CdnRouterController.exportCaCertificate(getApplication())
        if (path != null) {
            log("CA local copiada para: $path")
            log("Instalação é MANUAL (Configurações > Segurança > Instalar certificado). Ver docs/launcher.md")
        } else {
            log("Falha ao copiar a CA local (ver log acima)")
        }
    }

    fun clearLog() {
        RequestLog.clear()
        log("Log limpo")
    }

    // ---------------- WZM ----------------

    fun launchWzm() {
        refreshWzmStatus()
        if (_uiState.value.wzmStatus == WzmStatus.NAO_DETECTADO) {
            log("Erro: WZM não está instalado")
            return
        }
        log("Iniciando Warzone Mobile...")
        _uiState.value = _uiState.value.copy(wzmStatus = WzmStatus.INICIANDO)
        val result = wzmLauncher.launch()
        if (result.success) {
            log("Warzone Mobile iniciado")
            _uiState.value = _uiState.value.copy(wzmStatus = WzmStatus.EXECUTANDO)
        } else {
            log("Erro: ${result.message}")
            _uiState.value = _uiState.value.copy(wzmStatus = WzmStatus.INSTALADO)
        }
    }

    fun addLog(message: String) = log(message)

    /** Fonte única de log: o mesmo buffer do roteador CDNI (DNS/TLS/HTTP). */
    private fun log(message: String) {
        RequestLog.add("LAUNCHER", message)
    }
}
