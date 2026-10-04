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

    @Volatile
    var status: ServerStatus = ServerStatus.PARADO
        private set

    suspend fun start(): ServerStatus = mutex.withLock {
        if (status == ServerStatus.ONLINE || status == ServerStatus.INICIANDO) return status
        try {
            status = ServerStatus.INICIANDO
            val s = factory(host, port)
            s.start()
            Thread.sleep(100)
            if (!s.isRunning()) throw IllegalStateException("Server failed to start")
            server = s
            status = ServerStatus.ONLINE
        } catch (e: Exception) {
            e.printStackTrace()
            status = ServerStatus.ERRO
        }
        status
    }

    suspend fun stop(): ServerStatus = mutex.withLock {
        if (status == ServerStatus.PARADO || status == ServerStatus.PARANDO) return status
        try {
            status = ServerStatus.PARANDO
            server?.stop()
            server = null
            status = ServerStatus.PARADO
        } catch (e: Exception) {
            e.printStackTrace()
            status = ServerStatus.ERRO
        }
        status
    }

    fun isRunning(): Boolean = server?.isRunning() == true && status == ServerStatus.ONLINE
    fun getPort(): Int = port
    fun getHost(): String = host
    fun getStatus(): ServerStatus = status
}
