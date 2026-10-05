package com.wzm.launcher.cdn

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Primeira linha + cabeçalhos de uma requisição HTTP. */
data class HttpRequestHead(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>
) {
    /** Caminho sem query string (formato absoluto é convertido). */
    val path: String get() = BootstrapEndpoints.normalize(target)

    /** Query string sem '?', ou null. */
    val query: String? get() = target.substringAfter('?', "").takeIf { it.isNotEmpty() && target.contains('?') }

    val host: String? get() = headers["host"]

    /** Linha exata do pedido — é isso que vai para o log (URL/path exatos, sem invenção). */
    val rawLine: String get() = "$method $target $version"
}

/**
 * Lê o head HTTP de um socket. O WZM fala HTTPS de verdade contra o nosso servidor local,
 * então aqui já é texto puro (o TLS foi terminado no socket).
 */
object HttpHeadReader {

    const val MAX_HEAD_BYTES = 32 * 1024

    data class Result(val head: HttpRequestHead, val bytesRead: Int)

    fun read(input: InputStream): Result? {
        val buffer = ByteArrayOutputStream(1024)
        var previous = 0
        var beforePrevious = 0
        var beforeBeforePrevious = 0
        var count = 0
        while (count < MAX_HEAD_BYTES) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            count++
            // fim do head: "\r\n\r\n" ou "\n\n"
            if (value == '\n'.code) {
                val endsWithCrLfCrLf = previous == '\r'.code &&
                    beforePrevious == '\n'.code && beforeBeforePrevious == '\r'.code
                val endsWithLfLf = previous == '\n'.code
                if (endsWithCrLfCrLf || endsWithLfLf) {
                    return parse(String(buffer.toByteArray(), Charsets.ISO_8859_1))
                }
            }
            beforeBeforePrevious = beforePrevious
            beforePrevious = previous
            previous = value
        }
        return null
    }

    fun parse(raw: String): Result? {
        val lines = raw.split("\r\n", "\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(' ').filter { it.isNotEmpty() }
        if (requestLine.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val version = requestLine.getOrElse(2) { "HTTP/1.0" }
        if (!version.startsWith("HTTP/")) return null
        return Result(HttpRequestHead(requestLine[0].uppercase(), requestLine[1], version, headers), raw.length)
    }
}

/** Codificação de respostas HTTP/1.1 simples (Connection: close). */
object HttpResponses {

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        // Statuses outside this deliberately small response set must not be mislabeled as success.
        else -> "Unknown"
    }

    fun build(
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList()
    ): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        for ((name, value) in extraHeaders) head.append(name).append(": ").append(value).append("\r\n")
        head.append("\r\n")
        val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream(headBytes.size + body.size)
        out.write(headBytes)
        out.write(body)
        return out.toByteArray()
    }

    fun jsonEscape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
