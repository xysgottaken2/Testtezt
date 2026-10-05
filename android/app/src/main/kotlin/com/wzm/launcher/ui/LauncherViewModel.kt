package com.wzm.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wzm.launcher.LauncherConfig
import com.wzm.launcher.server.ServerController
import com.wzm.launcher.server.ServerStatus
import com.wzm.launcher.wzm.WzmLauncher
import com.wzm.launcher.wzm.WzmStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LauncherUiState(
    val serverStatus: ServerStatus = ServerStatus.PARADO,
    val wzmStatus: WzmStatus = WzmStatus.NAO_DETECTADO,
    val logs: List<String> = emptyList(),
    val wzmVersion: String? = null,
    val isServerStarting: Boolean = false
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val serverController = ServerController()
    private val wzmLauncher = WzmLauncher(application.applicationContext)

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        log("Launcher iniciado")
        refreshWzmStatus()
        // Observe initial server status
        _uiState.value = _uiState.value.copy(serverStatus = serverController.getStatus())
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

    fun startServer() {
        if (_uiState.value.serverStatus == ServerStatus.ONLINE || _uiState.value.serverStatus == ServerStatus.INICIANDO) {
            log("Servidor já está ${ _uiState.value.serverStatus }")
            return
        }
        viewModelScope.launch {
            log("Servidor iniciando...")
            _uiState.value = _uiState.value.copy(serverStatus = ServerStatus.INICIANDO, isServerStarting = true)
            val result = serverController.start()
            _uiState.value = _uiState.value.copy(serverStatus = result, isServerStarting = false)
            when (result) {
                ServerStatus.ONLINE -> log("Servidor ONLINE em ${LauncherConfig.HOST}:${LauncherConfig.PORT}")
                ServerStatus.ERRO -> log("Erro ao iniciar servidor")
                else -> log("Servidor: $result")
            }
        }
    }

    fun stopServer() {
        if (_uiState.value.serverStatus == ServerStatus.PARADO || _uiState.value.serverStatus == ServerStatus.PARANDO) {
            log("Servidor já está ${_uiState.value.serverStatus}")
            return
        }
        viewModelScope.launch {
            log("Servidor parando...")
            _uiState.value = _uiState.value.copy(serverStatus = ServerStatus.PARANDO)
            val result = serverController.stop()
            _uiState.value = _uiState.value.copy(serverStatus = result)
            log("Servidor $result")
        }
    }

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

    fun addLog(message: String) { log(message) }

    private fun log(message: String) {
        val ts = dateFormat.format(Date())
        val entry = "[$ts] $message"
        val current = _uiState.value.logs
        // keep last 100
        val updated = (current + entry).takeLast(100)
        _uiState.value = _uiState.value.copy(logs = updated)
    }
}
