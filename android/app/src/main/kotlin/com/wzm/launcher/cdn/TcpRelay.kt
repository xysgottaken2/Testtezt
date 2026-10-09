package com.wzm.launcher.cdn

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * Atende UMA conexão já descriptografada (o TLS terminou no socket): lê o head HTTP,
 * decide a resposta em [CdnRouteTable] e registra tudo no [RequestLog].
 *
 * Este é o ponto onde o log do launcher passa a mostrar as requisições reais vindas do WZM.
 */
class TcpRelay(
    private val log: (String, String) -> Unit = { tag, message -> RequestLog.add(tag, message) },
    private val socketTimeoutMs: Int = 15_000
) {

    fun serve(socket: Socket, peer: String, context: String = "") {
        socket.use { connection ->
            runCatching { connection.soTimeout = socketTimeoutMs }
            val input = BufferedInputStream(connection.getInputStream())
            val readResult = try {
                HttpHeadReader.read(input)
            } catch (e: Exception) {
                log("CDNI?", "erro lendo head HTTP de $peer: ${e.javaClass.simpleName}: ${e.message}")
                return@use
            }
            if (readResult == null) {
                log("CDNI?", "conexão de $peer sem head HTTP válido (não-HTTP ou abortada) — descartada")
                return@use
            }
            val head = readResult.head
            val now = System.currentTimeMillis()
            // M4.4: a janela do teste sintético é CORRELAÇÃO — "fora dela" nunca significa "é o WZM"
            // (a atribuição continua exigindo owner UID/tupla, M4.1).
            val synthetic = RequestLog.isDuringSyntheticTest(now)
            val origin = CdniMetaFlow.originOf(synthetic)
            val outcome = CdnRouteTable.respond(head)
            RequestLog.incHttpRequest()
            if (!outcome.isKnown) RequestLog.incUnknownRequest()
            val metaSuffix = CdniMetaFlow.observe(head, outcome, peer, synthetic, now)
            val suffix = (if (context.isEmpty()) "" else " · $context") + metaSuffix
            log(outcome.logTag, "${outcome.logMessage} [cliente=$peer] origem=${origin.label}$suffix")
            // Linha dedicada ao status HTTP (filtro "[HTTP]" na tela de logs).
            log(
                "HTTP",
                "${head.method} ${head.path} -> ${outcome.status} " +
                    "${HttpResponses.reason(outcome.status)} (resposta ${outcome.bytes.size} B, " +
                    "cliente=$peer) origem=${origin.label}$suffix"
            )
            try {
                val output = BufferedOutputStream(connection.getOutputStream())
                output.write(outcome.bytes)
                output.flush()
            } catch (e: Exception) {
                log("CDNI?", "falha ao responder $peer: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
