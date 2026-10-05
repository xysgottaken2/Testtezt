package com.wzm.launcher.server

import com.wzm.launcher.LauncherConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ServerStatus { PARADO, INICIANDO, ONLINE, PARANDO, ERRO }

class ServerController(
    private val host: String = LauncherConfig.HOST,
    private val port: Int = LauncherConfig.PORT,
    serverFactory: ((String, Int) -> LocalServer)? = null
) {
    private val mutex = Mutex()
    private var server: LocalServer? = null
    private val factory: (String, Int) -> LocalServer = serverFactory ?: { h, p -> EmbeddedLocalServer(h, p) }

    // NOTA: campo privado `currentStatus` (não `status`) para não colidir com fun getStatus()
    // — a JVM geraria dois métodos getStatus()Lcom/wzm/launcher/server/ServerStatus; (clash).
    @Volatile
    private var currentStatus: ServerStatus = ServerStatus.PARADO

    suspend fun start(): ServerStatus = mutex.withLock {
        if (currentStatus == ServerStatus.ONLINE || currentStatus == ServerStatus.INICIANDO) return currentStatus
        try {
            currentStatus = ServerStatus.INICIANDO
            val s = factory(host, port)
            s.start()
            Thread.sleep(100)
            if (!s.isRunning()) throw IllegalStateException("Server failed to start")
            server = s
            currentStatus = ServerStatus.ONLINE
        } catch (e: Exception) {
            e.printStackTrace()
            currentStatus = ServerStatus.ERRO
        }
        currentStatus
    }

    suspend fun stop(): ServerStatus = mutex.withLock {
        if (currentStatus == ServerStatus.PARADO || currentStatus == ServerStatus.PARANDO) return currentStatus
        try {
            currentStatus = ServerStatus.PARANDO
            server?.stop()
            server = null
            currentStatus = ServerStatus.PARADO
        } catch (e: Exception) {
            e.printStackTrace()
            currentStatus = ServerStatus.ERRO
        }
        currentStatus
    }

    fun isRunning(): Boolean = server?.isRunning() == true && currentStatus == ServerStatus.ONLINE
    fun getPort(): Int = port
    fun getHost(): String = host
    fun getStatus(): ServerStatus = currentStatus
}
