package com.wzm.launcher.cdn

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persistência do [RequestLog] em arquivo (uma linha por evento), usada para que o log sobreviva
 * a recriações do processo e para exportação.
 *
 * - Escritas acontecem em uma thread própria (nada de bloquear o loop do TUN ou o servidor HTTPS).
 * - O arquivo é rotacionado quando passa de [maxBytes] (mantém as [keepLinesOnRotation] últimas linhas).
 * - Nenhuma API Android: é testável em JVM (ver `FileLogSinkTest`).
 *
 * Conteúdo: apenas as linhas do log (tag + horário + método/path/status). Sem cabeçalhos/corpos.
 */
class FileLogSink(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keepLinesOnRotation: Int = DEFAULT_KEEP_LINES,
    private val executor: ExecutorService = defaultExecutor()
) : LogSink {

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024
        const val DEFAULT_KEEP_LINES = 1000

        private fun defaultExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "request-log-sink").apply { isDaemon = true }
            }
    }

    val path: String get() = file.absolutePath

    override fun append(line: String) {
        executor.execute {
            try {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
                if (file.length() > maxBytes) rotate()
            } catch (_: Exception) {
                // logging nunca deve derrubar o app
            }
        }
    }

    /** Mantém apenas as últimas linhas quando o arquivo cresce demais. */
    private fun rotate() {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.size <= keepLinesOnRotation) return
        file.writeText(lines.takeLast(keepLinesOnRotation).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    /** Últimas [maxLines] linhas do arquivo (vazio se não existir). */
    fun readTail(maxLines: Int): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8).takeLast(maxLines) }.getOrDefault(emptyList())
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0

    /** Apaga o arquivo (o próximo [append] recria). */
    fun clear() {
        executor.execute { runCatching { file.delete() }; Unit }
    }

    /** Bloqueia até todas as escritas enfileiradas terminarem (testes/exportação). */
    fun flush(timeoutMs: Long = 2_000) {
        try {
            executor.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    fun close() {
        flush()
        executor.shutdown()
    }
}
