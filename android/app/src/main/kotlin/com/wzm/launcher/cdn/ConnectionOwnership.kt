package com.wzm.launcher.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Autoria de uma conexão pela API pública do Android (M4.1).
 *
 * **O que a documentação e o AOSP dizem** (fontes registradas em
 * `docs/research/m4.1-dono-das-conexoes-loopback.md`):
 *
 *  * `ConnectivityManager.getConnectionOwnerUid` (API 29+) recebe a tupla orientada do socket:
 *    endereço/porta **local** e **remoto**; o AOSP grava `local` como origem (`idiag_src/sport`) e
 *    `remote` como destino (`idiag_dst/dport`) na consulta `inet_diag`;
 *  * só o **VPN ativo** (ou quem tem `NETWORK_STACK`) pode chamar; caso contrário → `SecurityException`;
 *  * devolve `Process.INVALID_UID` (`-1`) se a tupla não for encontrada **ou** se o UID encontrado não
 *    estiver coberto pela VPN chamadora (`ConnectivityService`: `!vpn.appliesToUid(uid)`).
 *
 * **Consequência:** em um socket retornado por `ServerSocket.accept()`, `local` é o endpoint do
 * servidor; para perguntar pelo dono do cliente/peer, a tupla tem de ser invertida (`remote -> local`).
 * Não tentamos a direção oposta como fallback: isso poderia responder pelo socket do servidor e
 * atribuir a conexão à parte errada.
 *
 * `INVALID_UID` não autoriza dizer "não é do jogo" nem "é do jogo". Só um UID resolvido na direção
 * correta pode atribuir o peer; ainda assim, a API dá **UID, não PID**.
 */
object ConnectionOwnership {

    /** Endpoints orientados do socket cujo UID estamos consultando. */
    data class ConnectionTuple(val local: InetSocketAddress, val remote: InetSocketAddress)

    /** Desfecho de uma consulta de autoria — estados distintos, nunca colapsados em um booleano. */
    enum class Outcome(val code: String, val hint: String) {
        RESOLVIDO("RESOLVIDO", "uid devolvido pela API; está coberto pela VPN que chamou"),
        INVALID_UID(
            "INVALID_UID",
            "a API devolve -1 tanto para conexão ausente quanto para uid FORA da VPN que chama " +
                "(AOSP filtra por appliesToUid) — não autoriza conclusão sobre autoria"
        ),
        SECURITY_EXCEPTION(
            "SEM_PERMISSAO",
            "SecurityException: quem chama não é o VPN ativo nem tem NETWORK_STACK"
        ),
        API_ANTIGA("API_ANTIGA", "getConnectionOwnerUid exige API 29+"),
        SERVICO_INDISPONIVEL("SERVICO_INDISPONIVEL", "ConnectivityManager não está disponível"),
        ENDERECOS_INDISPONIVEIS("ENDERECOS_INDISPONIVEIS", "socket sem par de endereços utilizável"),
        ARGUMENTO_INVALIDO("ARGUMENTO_INVALIDO", "protocolo não suportado (só TCP e UDP)"),
        CONSULTA_FALHOU("CONSULTA_FALHOU", "a consulta falhou por erro da plataforma; autoria desconhecida")
    }

    data class Result(
        val outcome: Outcome,
        val uid: Int? = null,
        val packages: List<String> = emptyList(),
        val detail: String = outcome.hint
    ) {
        /** `true` só quando a API provou a autoria (uid resolvido e coberto pela VPN). */
        val provesOwner: Boolean get() = outcome == Outcome.RESOLVIDO && uid != null
    }

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    /** Classificação pura do retorno da API. */
    fun classifyUid(uid: Int, packages: List<String> = emptyList()): Result =
        if (uid == Process.INVALID_UID) {
            Result(Outcome.INVALID_UID, null, emptyList(), Outcome.INVALID_UID.hint)
        } else {
            Result(Outcome.RESOLVIDO, uid, packages, "uid=$uid coberto pela VPN")
        }

    /** Classificação pura de exceção (a API lança `SecurityException` fora do VPN ativo). */
    fun classifyException(error: Throwable): Result {
        val outcome = when (error) {
            is SecurityException -> Outcome.SECURITY_EXCEPTION
            is IllegalArgumentException -> Outcome.ARGUMENTO_INVALIDO
            else -> Outcome.CONSULTA_FALHOU
        }
        return Result(outcome, null, emptyList(), "${error.javaClass.simpleName}: ${error.message}")
    }

    /** Linha de log — nunca promove `INVALID_UID` a "de outro app" nem a "não é do jogo". */
    fun describe(result: Result): String = when (result.outcome) {
        Outcome.RESOLVIDO ->
            "dono=uid=${result.uid}" + (if (result.packages.isNotEmpty()) " (${result.packages.joinToString(",")})" else "")
        Outcome.INVALID_UID ->
            "dono=NAO_RESOLVIDO (INVALID_UID — ${result.detail})"
        Outcome.SECURITY_EXCEPTION,
        Outcome.API_ANTIGA,
        Outcome.SERVICO_INDISPONIVEL,
        Outcome.ENDERECOS_INDISPONIVEIS,
        Outcome.ARGUMENTO_INVALIDO,
        Outcome.CONSULTA_FALHOU ->
            "dono=INDISPONIVEL (${result.outcome.code} — ${result.detail})"
    }

    /**
     * Consulta uma tupla na direção explícita do socket que queremos identificar.
     * Não inverte nem tenta fallback: `local` e `remote` têm semântica de origem/destino no AOSP.
     */
    fun query(
        context: Context,
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress
    ): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Result(Outcome.API_ANTIGA)
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return Result(Outcome.SERVICO_INDISPONIVEL)
        return try {
            val uid = manager.getConnectionOwnerUid(protocol, local, remote)
            val packages = if (uid == Process.INVALID_UID) emptyList() else packagesFor(context, uid)
            classifyUid(uid, packages)
        } catch (error: Exception) {
            classifyException(error)
        }
    }

    /** Tupla orientada do socket conectado, isto é, do processo dono desse próprio socket. */
    fun tupleForSocket(socket: Socket): ConnectionTuple? {
        val local = toInet(socket.localSocketAddress) ?: return null
        val remote = toInet(socket.remoteSocketAddress) ?: return null
        return ConnectionTuple(local, remote)
    }

    /**
     * Tupla do cliente para um socket aceito pelo servidor. `accept()` devolve o socket do lado
     * servidor; o peer cliente tem os endpoints invertidos.
     */
    fun peerTupleForAcceptedSocket(socket: Socket): ConnectionTuple? =
        tupleForSocket(socket)?.let { ConnectionTuple(local = it.remote, remote = it.local) }

    /** Consulta para um [Socket] conectado que foi aberto por este processo (lado cliente). */
    fun querySocket(context: Context, socket: Socket): Result {
        val tuple = tupleForSocket(socket) ?: return Result(Outcome.ENDERECOS_INDISPONIVEIS)
        return query(context, PROTOCOL_TCP, tuple.local, tuple.remote)
    }

    /** Consulta pelo processo peer, quando [socket] veio de `ServerSocket.accept()`. */
    fun queryPeerOfAcceptedSocket(context: Context, socket: Socket): Result {
        val tuple = peerTupleForAcceptedSocket(socket) ?: return Result(Outcome.ENDERECOS_INDISPONIVEIS)
        return query(context, PROTOCOL_TCP, tuple.local, tuple.remote)
    }

    private fun packagesFor(context: Context, uid: Int): List<String> = runCatching {
        context.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun toInet(address: SocketAddress?): InetSocketAddress? =
        (address as? InetSocketAddress)?.takeIf { it.address != null }
}
