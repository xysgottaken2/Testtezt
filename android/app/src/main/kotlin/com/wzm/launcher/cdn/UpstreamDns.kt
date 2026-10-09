package com.wzm.launcher.cdn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Encaminha consultas DNS que NÃO são do CDNI para os resolvedores reais
 * (UDP 53). Os sockets são "protegidos" pelo VpnService para não voltarem ao túnel.
 */
class UpstreamDns(
    private val servers: List<String> = listOf(
        CdnRouterConfig.DNS_UPSTREAM_PRIMARY,
        CdnRouterConfig.DNS_UPSTREAM_FALLBACK
    ),
    private val timeoutMs: Int = CdnRouterConfig.DNS_UPSTREAM_TIMEOUT_MS,
    private val protect: (DatagramSocket) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {

    fun exchange(query: ByteArray, length: Int): ByteArray? {
        for (server in servers) {
            val socket = DatagramSocket()
            try {
                protect(socket)
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(server)
                socket.send(DatagramPacket(query, length, address, CdnRouterConfig.DNS_PORT))
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                if (response.length >= 12 &&
                    buffer[0] == query[0] && buffer[1] == query[1] &&
                    (buffer[2].toInt() and 0x80) != 0
                ) {
                    return buffer.copyOf(response.length)
                }
                onError("resposta DNS inválida de $server")
            } catch (e: Exception) {
                onError("falha ao consultar $server: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
        return null
    }
}
