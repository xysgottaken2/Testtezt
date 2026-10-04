package com.wzm.launcher.server

interface LocalServer {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun getPort(): Int
    fun getHost(): String
}
