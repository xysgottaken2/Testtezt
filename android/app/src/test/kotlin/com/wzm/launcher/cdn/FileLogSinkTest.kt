package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

/**
 * Persistência do log em arquivo (usada pelo launcher para não perder o RequestLog e para
 * o botão SALVAR/EXPORTAR .TXT). Tudo JVM, sem Android.
 */
class FileLogSinkTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var sink: FileLogSink? = null

    @After
    fun tearDown() {
        sink?.close()
    }

    private fun file() = File(folder.root, "request-log.txt")

    @Test
    fun appendsOneLinePerEventAndReadsTail() {
        val target = file()
        sink = FileLogSink(target)
        sink!!.append("[10:00:00.000] [DNS] primeira")
        sink!!.append("[10:00:01.000] [CDNI] segunda")
        sink!!.append("[10:00:02.000] [HTTP] terceira")
        sink!!.flush()

        assertEquals(3, sink!!.readTail(10).size)
        assertEquals(
            listOf("[10:00:01.000] [CDNI] segunda", "[10:00:02.000] [HTTP] terceira"),
            sink!!.readTail(2)
        )
        assertTrue(target.readText(Charsets.UTF_8).startsWith("[10:00:00.000] [DNS] primeira"))
        assertTrue(sink!!.sizeBytes() > 0)
    }

    @Test
    fun readTailIsEmptyWhenFileDoesNotExist() {
        sink = FileLogSink(file())
        assertTrue(sink!!.readTail(5).isEmpty())
        assertEquals(0L, sink!!.sizeBytes())
    }

    @Test
    fun rotatesKeepingTheNewestLines() {
        val target = file()
        sink = FileLogSink(target, maxBytes = 400, keepLinesOnRotation = 10)
        repeat(60) { index -> sink!!.append("[10:00:00.000] [DNS] linha $index") }
        sink!!.flush()

        val lines = sink!!.readTail(100)
        assertTrue("rotação deveria cortar linhas antigas", lines.size <= 12)
        assertTrue(lines.last().endsWith("linha 59"))
        assertTrue("linha 0 deveria ter sido descartada", lines.none { it.endsWith("linha 0") })
        assertTrue("arquivo precisa continuar pequeno", sink!!.sizeBytes() < 400 + 200)
    }

    @Test
    fun clearRemovesFileAndNextAppendRecreatesIt() {
        val target = file()
        sink = FileLogSink(target)
        sink!!.append("[10:00:00.000] [DNS] antes do clear")
        sink!!.flush()
        assertTrue(target.exists())

        sink!!.clear()
        sink!!.flush()
        assertFalse("clear deveria apagar o arquivo", target.exists())
        assertTrue(sink!!.readTail(5).isEmpty())

        sink!!.append("[10:00:05.000] [LAUNCHER] log limpo pelo usuário")
        sink!!.flush()
        assertTrue(target.exists())
        assertEquals(1, sink!!.readTail(5).size)
    }

    @Test
    fun createsParentDirectoryIfMissing() {
        val nested = File(File(folder.root, "files/logs"), "request-log.txt")
        sink = FileLogSink(nested)
        sink!!.append("[10:00:00.000] [LAUNCHER] criado")
        sink!!.flush()
        assertTrue(nested.isFile)
    }

    @Test
    fun exportWritesTextFileWithCountersHeader() {
        RequestLog.clear()
        RequestLog.attachSink(null)
        RequestLog.add("CDNI", "GET /manifest/build-selector-103.js -> 200 (VERIFICADO, 63 B)")
        RequestLog.incTcpConnection()

        val directory = File(folder.root, "logs")
        val target = File(directory, LogExport.fileName(Date(0)))
        val written = LogExport.writeText(target, RequestLog.exportText(Date(0)))

        assertNotNull(written)
        assertTrue(written.isFile)
        assertTrue(
            "nome inesperado: ${written.name}",
            Regex("^wzm-requestlog-\\d{8}-\\d{6}\\.txt$").matches(written.name)
        )
        val text = written.readText(Charsets.UTF_8)
        assertTrue(text.contains("# Project Rezone — RequestLog do roteador CDNI local"))
        assertTrue(text.contains("tcpConnections=1"))
        assertTrue(text.contains("/manifest/build-selector-103.js -> 200"))
        RequestLog.clear()
    }

    @Test
    fun appendsAreThreadSafeAndNonBlocking() {
        val target = file()
        sink = FileLogSink(target)
        val threads = (1..4).map { worker ->
            Thread {
                repeat(25) { index -> sink!!.append("[10:00:00.000] [DNS] worker $worker linha $index") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        sink!!.flush()

        assertEquals(100, sink!!.readTail(200).size)
    }
}
