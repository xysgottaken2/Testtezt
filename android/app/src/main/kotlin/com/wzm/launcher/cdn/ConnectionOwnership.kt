package com.wzm.launcher.cdn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * Autoria de uma conexão pela API pública do Android (M4.1).
 *
 * **O que a documentação e o AOSP dizem** (pesquisa registrada em
 * `docs/research/m4.1-dono-das-conexoes-loopback.md`):
 *
 *  * `ConnectivityManager.getConnectionOwnerUid` (API 29+) devolve o uid do **socket** proprietário
 *    de um par (protocolo, endereço/porta local, endereço/porta remoto), usando netlink `inet_diag`;
 *  * só o **VPN ativo** (ou quem tem `NETWORK_STACK`) pode chamar; caso contrário `SecurityException`;
 *  * devolve `Process.INVALID_UID` (`-1`) em **dois** casos distintos: (a) a conexão não está na
 *    tabela do sistema; (b) a conexão existe, mas o uid dono **não está coberto** pela VPN que chama
 *    (`ConnectivityService`: `if (vpn != null && !vpn.appliesToUid(uid)) return INVALID_UID`).
 *
 * **Consequência que muda a leitura do log antigo:** com allowlist estrita (só o WZM dentro da VPN),
 * a API responde `INVALID_UID` para **tudo** que não seja do WZM — inclusive para as conexões do
 * próprio launcher. `INVALID_UID` **não** autoriza dizer "não é do jogo" nem "é do jogo".
 *
 * O caminho simétrico é o que interessa: se a API **resolver** um uid, esse uid está dentro da VPN —
 * e isso é evidência de autoria (autorização) que nenhuma outra técnica sem root oferece.
 */
object ConnectionOwnership {

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
        ENDERECOS_INDISPONIVEIS("ENDERECOS_INDISPONIVEIS", "socket sem par de endereços utilizável"),
        ARGUMENTO_INVALIDO("ARGUMENTO_INVALIDO", "protocolo não suportado (só TCP e UDP)")
    }

    data class Result(
        val outcome: Outcome,
        val uid: Int? = null,
        val packages: List<String> = emptyList(),
        val detail: String = ""
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
            else -> Outcome.ENDERECOS_INDISPONIVEIS
        }
        return Result(outcome, null, emptyList(), "${error.javaClass.simpleName}: ${error.message}")
    }

    /** Linha de log — nunca promove `INVALID_UID` a "de outro app" nem a "não é do jogo". */
    fun describe(result: Result): String = when (result.outcome) {
        Outcome.RESOLVIDO ->
            "dono=uid=${result.uid}" + (if (result.packages.isNotEmpty()) " (${result.packages.joinToString(",")})" else "")
        Outcome.INVALID_UID ->
            "dono=NAO_RESOLVIDO (INVALID_UID — ${result.detail})"
        else ->
            "dono=INDISPONIVEL (${result.outcome.code} — ${result.detail})"
    }

    /**
     * Consulta real. Tenta as duas orientações (local→remoto e remoto→local) porque o `inet_diag`
     * indexa a tupla na direção do cliente, que nem sempre é a que temos em mãos.
     */
    fun query(
        context: Context,
        protocol: Int,
        first: InetSocketAddress,
        second: InetSocketAddress
    ): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Result(Outcome.API_ANTIGA)
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return Result(Outcome.ENDERECOS_INDISPONIVEIS, detail = "ConnectivityManager ausente")
        val order = listOf(first to second, second to first)
        var last: Result? = null
        for ((local, remote) in order) {
            val result = try {
                val uid = manager.getConnectionOwnerUid(protocol, local, remote)
                classifyUid(uid, packagesFor(context, uid))
            } catch (error: Throwable) {
                classifyException(error)
            }
            if (result.provesOwner) return result
            last = result
        }
        return last ?: Result(Outcome.ENDERECOS_INDISPONIVEIS)
    }

    /** Consulta para um [java.net.Socket] já aceito. */
    fun querySocket(context: Context, socket: java.net.Socket): Result {
        val local = toInet(socket.localSocketAddress)
        val remote = toInet(socket.remoteSocketAddress)
        if (local == null || remote == null) return Result(Outcome.ENDERECOS_INDISPONIVEIS)
        return query(context, PROTOCOL_TCP, local, remote)
    }

    private fun packagesFor(context: Context, uid: Int): List<String> = runCatching {
        context.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun toInet(address: SocketAddress?): InetSocketAddress? =
        (address as? InetSocketAddress)?.takeIf { it.address != null }
}
