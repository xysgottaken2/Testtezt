package com.wzm.launcher.cdn

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportação do log como `.txt` (botão SALVAR/EXPORTAR da tela de logs).
 *
 * A escrita em si não usa APIs Android (testável em JVM); o diretório de destino e o
 * compartilhamento ficam em [LogPersistence] / `LauncherViewModel`.
 */
object LogExport {

    fun fileName(now: Date = Date()): String =
        "wzm-requestlog-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".txt"

    /** Escreve [text] em [target] (criando diretórios) e devolve o arquivo. */
    fun writeText(target: File, text: String): File {
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
        return target
    }
}
