package com.wzm.launcher.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wzm.launcher.LauncherConfig
import com.wzm.launcher.cdn.CdnRouterController
import com.wzm.launcher.cdn.CdnRouterStatus
import com.wzm.launcher.cdn.LogExport
import com.wzm.launcher.cdn.LogPersistence
import com.wzm.launcher.cdn.RequestCounters
import com.wzm.launcher.cdn.RequestLog
import com.wzm.launcher.cdn.SyntheticFlowTest
import com.wzm.launcher.server.ServerController
import com.wzm.launcher.server.ServerStatus
import com.wzm.launcher.wzm.WzmLauncher
import com.wzm.launcher.wzm.WzmStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LauncherUiState(
    val serverStatus: ServerStatus = ServerStatus.PARADO,
    val wzmStatus: WzmStatus = WzmStatus.NAO_DETECTADO,
    val wzmVersion: String? = null,
    val isServerStarting: Boolean = false,
    val router: CdnRouterStatus = CdnRouterStatus(),
    val counters: RequestCounters = RequestCounters(),
    val logs: List<String> = emptyList(),
    /** Resultado do teste sintético do caminho CDNI (M3.5) — null = ainda não rodou. */
    val syntheticReport: String? = null
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
        // Tela "VER LOGS": restaura o log da sessão anterior e passa a persistir tudo em arquivo.
        LogPersistence.initialize(application)
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
        // Ordem correta (M3.4): a VPN sobe primeiro; o listener em :443 só começa depois que o
        // endereço do túnel existe. Antes disso o bind em 10.111.222.1 falhava com EADDRNOTAVAIL.
        log("Iniciando roteador CDNI local (VPN -> endereço do túnel -> listener HTTPS 443 + DNS)")
        val consent = CdnRouterController.vpnConsentIntent(getApplication())
        if (consent != null) {
            pendingVpnStart = true
            log("Autorização de VPN necessária — confirme o diálogo do sistema")
            _consentRequest.value = consent
        } else {
            startRouterNow()
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
            startRouterNow()
        }
    }

    private fun startRouterNow() {
        CdnRouterController.startRouter(getApplication(), LauncherConfig.WZM_PACKAGE)
        log("VPN: pedido de túnel enviado (per-app: ${LauncherConfig.WZM_PACKAGE}); fase ${CdnRouterController.currentPhase}")
    }

    fun stopRouter() {
        log("Parando roteador CDNI local (fase ${CdnRouterController.currentPhase})...")
        CdnRouterController.stopAll(getApplication())
        log("Roteador CDNI parado (fase ${CdnRouterController.currentPhase})")
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

    // ---------------- teste sintético do caminho CDNI (M3.5) ----------------

    /**
     * Executa `DNS CDNI → 10.111.222.1:443 → listener local → cdni.meta` sem envolver o WZM.
     * Se este caminho não estiver VERIFIED, o teste com o WZM não teria como concluir nada.
     */
    fun runSyntheticTest() {
        val state = _uiState.value
        if (!state.router.httpsRunning && !state.router.vpnActive) {
            log("Teste sintético: inicie o roteador CDNI primeiro (VPN + listener em :443)")
            return
        }
        log("Teste sintético do caminho CDNI iniciado (DNS → 10.111.222.1:443 → cdni.meta; sem WZM)")
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { SyntheticFlowTest.run(getApplication()) }
            _uiState.value = _uiState.value.copy(syntheticReport = report.summary())
            log(report.summary())
        }
    }

    // ---------------- tela de logs (VER LOGS) ----------------

    fun clearLog() {
        RequestLog.clear()
        LogPersistence.clearFile()
        log("Log limpo pelo usuário")
    }

    /** Texto completo (cabeçalho com contadores + todas as linhas) para COPIAR LOGS. */
    fun logTextForClipboard(): String = RequestLog.exportText()

    /** SALVAR/EXPORTAR .TXT: grava em diretório do próprio app (sem permissão de armazenamento). */
    fun saveLogFile(): File? = try {
        val directory = LogPersistence.exportDirectory(getApplication())
        val file = LogExport.writeText(File(directory, LogExport.fileName()), RequestLog.exportText())
        LogPersistence.flush()
        log("Log exportado: ${file.absolutePath} (${file.length()} B)")
        file
    } catch (e: Exception) {
        log("Falha ao exportar o log: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Intent de compartilhamento do .txt exportado (FileProvider; sem permissão extra). */
    fun shareLogIntent(file: File): Intent? = try {
        val uri = FileProvider.getUriForFile(
            getApplication(),
            getApplication<Application>().packageName + ".fileprovider",
            file
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WZM Offline Launcher — RequestLog")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (e: Exception) {
        log("Falha ao compartilhar o log: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** Caminho do arquivo persistido, para mostrar na tela de logs. */
    fun logFilePath(): String? = LogPersistence.sinkPath()

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
            // M3.5: marcador de sessão — conexões anteriores a este instante não podem ser do WZM.
            val startedAt = System.currentTimeMillis()
            RequestLog.markWzmStarted(startedAt)
            log(
                "Warzone Mobile iniciado — marcador de sessão epochMs=$startedAt: conexões anteriores a " +
                    "este instante (inclusive em 127.0.0.1:443) NÃO podem ser atribuídas ao WZM"
            )
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
