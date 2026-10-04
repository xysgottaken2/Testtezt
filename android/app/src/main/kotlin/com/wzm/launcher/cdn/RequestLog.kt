package com.wzm.launcher.cdn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Contadores exibidos no card do roteador CDNI. */
data class RequestCounters(
    val dnsQueries: Int = 0,
    val dnsIntercepted: Int = 0,
    val httpRequests: Int = 0,
    val unknownRequests: Int = 0,
    val tlsOk: Int = 0,
    val tlsFailed: Int = 0
)

/**
 * Log único do app: eventos do launcher + DNS/TLS/HTTP interceptados do WZM.
 * Mantém um buffer em memória (sem persistência) e contadores para a UI.
 */
object RequestLog {

    private const val MAX_ENTRIES = 400

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    private val _counters = MutableStateFlow(RequestCounters())
    val counters: StateFlow<RequestCounters> = _counters.asStateFlow()

    private val dnsQueries = AtomicInteger(0)
    private val dnsIntercepted = AtomicInteger(0)
    private val httpRequests = AtomicInteger(0)
    private val unknownRequests = AtomicInteger(0)
    private val tlsOk = AtomicInteger(0)
    private val tlsFailed = AtomicInteger(0)

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "[${timeFormat.format(Date())}] [$tag] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    fun incDnsQuery() { dnsQueries.incrementAndGet(); publishCounters() }
    fun incDnsIntercepted() { dnsIntercepted.incrementAndGet(); publishCounters() }
    fun incHttpRequest() { httpRequests.incrementAndGet(); publishCounters() }
    fun incUnknownRequest() { unknownRequests.incrementAndGet(); publishCounters() }
    fun incTlsOk() { tlsOk.incrementAndGet(); publishCounters() }
    fun incTlsFailed() { tlsFailed.incrementAndGet(); publishCounters() }

    private fun publishCounters() {
        _counters.value = RequestCounters(
            dnsQueries = dnsQueries.get(),
            dnsIntercepted = dnsIntercepted.get(),
            httpRequests = httpRequests.get(),
            unknownRequests = unknownRequests.get(),
            tlsOk = tlsOk.get(),
            tlsFailed = tlsFailed.get()
        )
    }

    fun resetCounters() {
        dnsQueries.set(0); dnsIntercepted.set(0); httpRequests.set(0)
        unknownRequests.set(0); tlsOk.set(0); tlsFailed.set(0)
        publishCounters()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
        resetCounters()
    }

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")
}
