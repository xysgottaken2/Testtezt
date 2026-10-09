package com.wzm.launcher.cdn

import android.content.Context
import java.io.File

/**
 * Liga o [RequestLog] ao arquivo persistido no armazenamento privado do app.
 *
 * - No primeiro uso: restaura as últimas linhas da sessão anterior e passa a gravar tudo.
 * - Usado também pelo botão LIMPAR LOGS (apaga o arquivo) e pelo SALVAR/EXPORTAR (diretório).
 *
 * Nada aqui sai do armazenamento do próprio app: sem permissão de armazenamento, sem rede.
 */
object LogPersistence {

    private const val FILE_NAME = "request-log.txt"
    private const val EXPORT_DIR = "logs"

    private var sink: FileLogSink? = null

    @Synchronized
    fun initialize(context: Context): FileLogSink {
        sink?.let { return it }
        val created = FileLogSink(File(context.filesDir, FILE_NAME))
        val restored = runCatching { created.readTail(RequestLog.MAX_ENTRIES) }.getOrDefault(emptyList())
        if (restored.isNotEmpty()) {
            // restaura ANTES de ligar o sink (senão as linhas antigas seriam regravadas)
            RequestLog.restore(restored)
        }
        RequestLog.attachSink(created)
        sink = created
        if (restored.isNotEmpty()) {
            RequestLog.add(
                RequestLog.TAG_LAUNCHER,
                "log restaurado da sessão anterior (${restored.size} linhas; contadores zerados)"
            )
        }
        return created
    }

    @Synchronized
    fun clearFile() {
        sink?.clear()
    }

    /** Diretório de exportação (app-specific; não exige permissão de armazenamento). */
    fun exportDirectory(context: Context): File =
        context.getExternalFilesDir(EXPORT_DIR) ?: File(context.filesDir, EXPORT_DIR)

    fun sinkPath(): String? = sink?.path

    @Synchronized
    fun flush() {
        sink?.flush()
    }
}
