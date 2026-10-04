package com.wzm.launcher.cdn

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Teste sintético controlado do caminho CDNI (M3.5) — **antes** de depender do WZM.
 *
 * O que ele prova, passo a passo (nenhum passo é presumido):
 *  1. `dns_bytes_virtual` — bytes de uma consulta A real para `prod.cdni.callofduty.com` passam pelo
 *     mesmo [DnsResponder] do túnel e a resposta contém `A = 10.111.222.1` (**puro**, roda em JVM);
 *  2. `dns_socket_virtual` — a mesma consulta enviada de verdade (UDP) para o DNS virtual
 *     `10.111.222.2:53`; é observação: o app do launcher **não** está na allow-list da VPN per-app, então
 *     o esperado é não haver resposta por socket — o motivo exato é registrado, nunca escondido;
 *  3. `tcp_alvo_443` — TCP real para o endereço que o DNS devolve (`10.111.222.1:443`);
 *  4. `tls_ca_local` — handshake TLS com a CA local (asset `cdn_local_ca.pem`) e SNI do host CDNI;
 *  5. `http_cdni_meta` — `GET /wzm/shard_cdn/android/_manifest/cdni.meta` no listener local, exigindo
 *     `200` + corpo real (`min_buildnum=19854920`).
 *
 * Os passos 3–5 são o que o WZM faria; se eles passam, o caminho
 * `DNS CDNI → 10.111.222.1 → TCP 443 → listener local → cdni.meta` está **VERIFIED** naquele aparelho.
 *
 * Privacidade: só metadados (endereço, porta, status, tamanho, protocolo). Nenhum payload de jogo,
 * cookie, token ou cabeçalho de terceiro é lido ou registrado — o único corpo lido é o `cdni.meta`,
 * que é o próprio recurso sob teste.
 */
object SyntheticFlowTest {

    const val TAG = "SINTETICO"

    /** Teto de leitura da resposta HTTP do teste (o `cdni.meta` tem ~320 B). */
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    data class Step(val id: String, val ok: Boolean, val verified: Boolean, val detail: String)

    data class Report(val steps: List<Step>, val verified: Boolean) {

        /** Linha curta para a UI. */
        fun summary(): String {
            val failed = steps.filter { it.verified && !it.ok }.map { it.id }
            val head = if (verified) "VERIFIED" else "PARCIAL"
            val suffix = if (failed.isEmpty()) "" else " — falhou: ${failed.joinToString(", ")}"
            return "TESTE SINTÉTICO DNS→10.111.222.1:443→cdni.meta: $head$suffix"
        }

        fun lines(): List<String> = steps.map { step ->
            val mark = when {
                step.ok -> "ok"
                step.verified -> "FALHOU"
                else -> "observação"
            }
            "passo ${step.id}: $mark — ${step.detail}"
        }
    }

    /**
     * Passo puro (JVM-testável): monta a consulta DNS real do host CDNI, roda pelo respondente do túnel
     * e confere se a resposta devolve exatamente [CdnRouterConfig.REDIRECT_TO].
     */
    fun dnsVirtualStep(host: String = CdnRouterConfig.EXACT_HOST): Step {
        val query = DnsMessage.buildQuery(host, DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuery(query, query.size)
        if (question == null) {
            return Step(
                "dns_bytes_virtual",
                ok = false,
                verified = true,
                // a prévia é da NOSSA consulta montada (não é payload de terceiro) e evita um CI cego
                detail = "consulta montada não foi parseada (prévia: ${DnsMessage.hexPreview(query)})"
            )
        }
        val answer = DnsResponder().answer(query, query.size)
            ?: return Step(
                "dns_bytes_virtual",
                ok = false,
                verified = true,
                detail = "$host não é interceptado pelo DnsResponder (resposta nula)"
            )
        val addresses = DnsMessage.extractARecords(answer, answer.size)
        val ok = addresses == listOf(CdnRouterConfig.REDIRECT_TO)
        return Step(
            "dns_bytes_virtual",
            ok = ok,
            verified = true,
            detail = if (ok) {
                "consulta A de $host (bytes reais) → resposta virtual A=${addresses.first()} (${answer.size} B)"
            } else {
                "consulta A de $host devolveu A=$addresses (esperado ${CdnRouterConfig.REDIRECT_TO})"
            }
        )
    }

    /**
     * Executa o teste completo. [context] é necessário para o handshake TLS com a CA local
     * (`null` = passo TLS marcado como não executado, nunca como sucesso).
     */
    fun run(
        context: Context?,
        host: String = CdnRouterConfig.EXACT_HOST,
        port: Int = CdnRouterConfig.LOCAL_HTTPS_PORT,
        dnsTimeoutMs: Int = 1_500,
        tcpTimeoutMs: Int = 4_000,
        readTimeoutMs: Int = 4_000
    ): Report {
        RequestLog.add(TAG, "teste sintético do caminho CDNI iniciado (host=$host, alvo=${CdnRouterConfig.REDIRECT_TO}:$port)")
        val steps = mutableListOf<Step>()

        // 1) puro: bytes reais de consulta → resposta virtual
        steps += dnsVirtualStep(host)
        RequestLog.add(TAG, "passo dns_bytes_virtual: ${steps.last().detail}")

        // 2) observação: consulta UDP real no DNS virtual do túnel
        steps += dnsSocketStep(host, dnsTimeoutMs)
        RequestLog.add(TAG, "passo dns_socket_virtual: ${steps.last().detail}")

        // 3) TCP real para o endereço devolvido pelo DNS
        val tcp = tcpStep(port, tcpTimeoutMs)
        steps += tcp.step
        RequestLog.add(TAG, "passo tcp_alvo_443: ${tcp.step.detail}")

        // 4) TLS com a CA local + SNI do host CDNI
        val tls = tlsStep(context, tcp.socket, host, port)
        steps += tls.step
        RequestLog.add(TAG, "passo tls_ca_local: ${tls.step.detail}")

        // 5) GET do cdni.meta no listener local
        val http = httpStep(tls.socket, host, readTimeoutMs)
        steps += http.step
        RequestLog.add(TAG, "passo http_cdni_meta: ${http.step.detail}")

        runCatching { http.socket?.close() }
        runCatching { tls.socket?.close() }
        runCatching { tcp.socket?.close() }

        val verified = steps.filter { it.verified }.all { it.ok }
        val report = Report(steps, verified)
        RequestLog.add(
            TAG,
            if (verified) {
                "caminho VERIFIED pelo teste sintético: DNS (bytes) → ${CdnRouterConfig.REDIRECT_TO}:$port → " +
                    "TLS com CA local → GET ${CdnRouterConfig.CDNI_META_PATH} → 200 com min_buildnum"
            } else {
                "caminho NÃO verificado por completo: ${report.summary()}"
            }
        )
        return report
    }

    private fun dnsSocketStep(host: String, timeoutMs: Int): Step {
        val query = DnsMessage.buildQuery(host, DnsMessage.TYPE_A)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val server = InetAddress.getByName(CdnRouterConfig.VPN_DNS)
                socket.send(DatagramPacket(query, query.size, server, CdnRouterConfig.DNS_PORT))
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val addresses = DnsMessage.extractARecords(buffer, response.length)
                val ok = addresses.contains(CdnRouterConfig.REDIRECT_TO)
                Step(
                    "dns_socket_virtual",
                    ok = ok,
                    verified = false,
                    detail = "consulta real para ${CdnRouterConfig.VPN_DNS}:${CdnRouterConfig.DNS_PORT} " +
                        "respondeu A=$addresses (${response.length} B)"
                )
            }
        } catch (e: Exception) {
            Step(
                "dns_socket_virtual",
                ok = false,
                verified = false,
                detail = "sem resposta por socket em ${CdnRouterConfig.VPN_DNS}:${CdnRouterConfig.DNS_PORT} " +
                    "(${e.javaClass.simpleName}: ${e.message}) — esperado quando o app do launcher não está " +
                    "na allow-list da VPN per-app; o passo dns_bytes_virtual cobre o respondente"
            )
        }
    }

    private data class TcpResult(val step: Step, val socket: Socket?)

    private fun tcpStep(port: Int, timeoutMs: Int): TcpResult {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(CdnRouterConfig.REDIRECT_TO, port), timeoutMs)
            socket.soTimeout = timeoutMs
            TcpResult(
                Step(
                    "tcp_alvo_443",
                    ok = true,
                    verified = true,
                    detail = "conexão TCP aceita em ${CdnRouterConfig.REDIRECT_TO}:$port " +
                        "(origem local=${socket.localSocketAddress})"
                ),
                socket
            )
        } catch (e: Exception) {
            runCatching { socket.close() }
            TcpResult(
                Step(
                    "tcp_alvo_443",
                    ok = false,
                    verified = true,
                    detail = "falha ao conectar em ${CdnRouterConfig.REDIRECT_TO}:$port " +
                        "(${e.javaClass.simpleName}: ${e.message}) — o listener do túnel está ativo?"
                ),
                null
            )
        }
    }

    private data class TlsResult(val step: Step, val socket: SSLSocket?)

    private fun tlsStep(context: Context?, plain: Socket?, host: String, port: Int): TlsResult {
        if (plain == null) {
            return TlsResult(
                Step("tls_ca_local", ok = false, verified = true, detail = "não executado: sem TCP estabelecido"),
                null
            )
        }
        val sslContext = context?.let { caContext(it) }
            ?: return TlsResult(
                Step(
                    "tls_ca_local",
                    ok = false,
                    verified = true,
                    detail = "não executado: CA local indisponível (asset ${CdnRouterConfig.CA_ASSET} ou Context ausente)"
                ),
                null
            )
        return try {
            val ssl = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            ssl.soTimeout = 6_000
            val parameters = ssl.sslParameters
            parameters.serverNames = mutableListOf<javax.net.ssl.SNIServerName>(javax.net.ssl.SNIHostName(host))
            ssl.sslParameters = parameters
            ssl.startHandshake()
            TlsResult(
                Step(
                    "tls_ca_local",
                    ok = true,
                    verified = true,
                    detail = "handshake TLS ok (${ssl.session.protocol}, SNI=$host) — o listener local " +
                        "apresentou o certificado e a CA local o aceitou"
                ),
                ssl
            )
        } catch (e: Exception) {
            runCatching { plain.close() }
            TlsResult(
                Step(
                    "tls_ca_local",
                    ok = false,
                    verified = true,
                    detail = "handshake TLS falhou (${e.javaClass.simpleName}: ${e.message})"
                ),
                null
            )
        }
    }

    private data class HttpResult(val step: Step, val socket: Socket?)

    private fun httpStep(ssl: SSLSocket?, host: String, readTimeoutMs: Int): HttpResult {
        if (ssl == null) {
            return HttpResult(
                Step("http_cdni_meta", ok = false, verified = true, detail = "não executado: sem TLS estabelecido"),
                null
            )
        }
        return try {
            val request = "GET ${CdnRouterConfig.CDNI_META_PATH} HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Accept: application/json\r\n" +
                "Connection: close\r\n\r\n"
            ssl.soTimeout = readTimeoutMs
            ssl.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.flush()
            val response = readAll(ssl, readTimeoutMs)
            val text = String(response, Charsets.ISO_8859_1)
            val statusLine = text.lineSequence().firstOrNull() ?: ""
            val body = text.substringAfter("\r\n\r\n", "")
            val ok = statusLine.contains("200") && CdniMetaBody.looksLikeRealBody(body)
            HttpResult(
                Step(
                    "http_cdni_meta",
                    ok = ok,
                    verified = true,
                    detail = if (ok) {
                        "GET ${CdnRouterConfig.CDNI_META_PATH} → $statusLine " +
                            "(${response.size} B; corpo real com min_buildnum=19854920)"
                    } else {
                        "GET ${CdnRouterConfig.CDNI_META_PATH} → $statusLine (${response.size} B) — " +
                            "esperado 200 com o cdni.meta real"
                    }
                ),
                null
            )
        } catch (e: Exception) {
            HttpResult(
                Step(
                    "http_cdni_meta",
                    ok = false,
                    verified = true,
                    detail = "falha no GET (${e.javaClass.simpleName}: ${e.message})"
                ),
                null
            )
        }
    }

    private fun readAll(socket: SSLSocket, readTimeoutMs: Int): ByteArray {
        val out = ByteArrayOutputStream(2048)
        val buffer = ByteArray(1024)
        val deadline = System.currentTimeMillis() + readTimeoutMs
        while (out.size() < MAX_RESPONSE_BYTES && System.currentTimeMillis() < deadline) {
            val read = try {
                socket.inputStream.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Contexto TLS que confia **somente** na CA local do launcher (nada de CA de sistema/usuário). */
    private fun caContext(context: Context): SSLContext? = try {
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = context.assets.open(CdnRouterConfig.CA_ASSET).use { factory.generateCertificate(it) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("cdn-local-ca", certificate)
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        SSLContext.getInstance("TLS").apply { init(null, trustManagerFactory.trustManagers, null) }
    } catch (e: Exception) {
        RequestLog.add(TAG, "CA local indisponível para o teste sintético: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
