package com.wzm.launcher.cdn

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Teste de controle do item 2 do M4.1: **como `getConnectionOwnerUid` se comporta em conexões loopback**
 * (e no endereço do túnel), dentro do nosso cenário real (VPN per-app com allowlist estrita).
 *
 * O que ele mede, com socket de verdade:
 *
 *  1. `loopback_proprio` — este processo conecta em `127.0.0.1:443` (o nosso próprio listener) e
 *     pergunta à API quem é o dono. **Previsão do AOSP:** `INVALID_UID`, porque o uid do launcher
 *     **não** está coberto pela VPN (a allowlist é só o alvo). Se vier `uid=<launcher>`, a previsão
 *     está errada e isso é um achado.
 *  2. `tunel_proprio` — mesma coisa no endereço do túnel (só quando atribuído).
 *  3. `tupla_inexistente` — consulta uma tupla que **não** existe (sem abrir socket), para validar o
 *     outro significado de `INVALID_UID` ("conexão não encontrada").
 *  4. `escopo_da_vpn` — não é chamada de API: registra que a nossa allowlist contém **apenas** o alvo,
 *     portanto a API **não pode** resolver o dono de conexões de fora da VPN (incluindo as nossas).
 *
 * Nenhum pacote sai do aparelho: os alvos são o listener local e o endereço do próprio túnel. Nada é
 * descriptografado, nenhum handshake é feito — o socket é aberto (TCP) e fechado.
 *
 * A montagem do relatório é pura ([report]); o runner recebe `connect`/`query*` por injeção, então o
 * comportamento inteiro é exercitado em JVM nos testes.
 */
object OwnerProbe {

    enum class StepId(val label: String) {
        LOOPBACK_PROPRIO("loopback_proprio"),
        TUNEL_PROPRIO("tunel_proprio"),
        TUPLA_INEXISTENTE("tupla_inexistente"),
        ESCOPO_DA_VPN("escopo_da_vpn")
    }

    data class StepResult(
        val id: StepId,
        val target: String,
        val result: ConnectionOwnership.Result?,
        val note: String = "",
        val skippedReason: String? = null
    ) {
        val ran: Boolean get() = skippedReason == null && result != null
    }

    data class Report(val steps: List<StepResult>) {
        val resolved: Int get() = steps.count { it.result?.outcome == ConnectionOwnership.Outcome.RESOLVIDO }
        val invalid: Int get() = steps.count { it.result?.outcome == ConnectionOwnership.Outcome.INVALID_UID }
        val noPermission: Int
            get() = steps.count { it.result?.outcome == ConnectionOwnership.Outcome.SECURITY_EXCEPTION }

        fun lines(): List<String> = steps.map { step ->
            when {
                step.skippedReason != null -> "controle ${step.id.label}: PULADO (${step.skippedReason})"
                step.result == null -> "controle ${step.id.label}: SEM RESULTADO"
                else ->
                    "controle ${step.id.label} (${step.target}): ${ConnectionOwnership.describe(step.result)}" +
                        if (step.note.isEmpty()) "" else " · ${step.note}"
            }
        }

        /**
         * Leitura do teste — sempre com o nível de confiança e sem inverter o significado da API.
         */
        fun expectation(): String = when {
            noPermission > 0 ->
                "SEM_PERMISSAO em $noPermission passo(s): a API exige ser o VPN ATIVO (ou ter NETWORK_STACK) — " +
                    "resultado inconclusivo sobre loopback, investigar por que o serviço não é o VPN ativo"
            resolved > 0 ->
                "a API RESOLVEU $resolved passo(s): autoria comprovada para uid coberto pela VPN — " +
                    "para conexões de fora da VPN ela responde INVALID_UID por desenho"
            invalid > 0 ->
                "INVALID_UID em $invalid passo(s): confirma no device que a API não identifica conexão nem " +
                    "quando o dono é o próprio launcher sob allowlist estrita — logo as conexões de loopback " +
                    "não podem ser atribuídas por esta via (PROBABLE, com o AOSP como base)"
            else ->
                "nenhum passo executado de forma útil — resultado inconclusivo (registrar como UNKNOWN)"
        }
    }

    /**
     * Execução real/injetada do teste de controle. [querySocket] e [queryTuple] recebem as consultas;
     * `connect` abre o socket. Tudo é fechado no fim.
     */
    fun run(
        loopbackPort: Int?,
        tunnelAddress: String,
        tunnelPort: Int,
        tunnelAddressAssigned: Boolean,
        selfPortRegistrar: (Int) -> Unit,
        connect: (String, Int) -> Socket?,
        querySocket: (Socket) -> ConnectionOwnership.Result,
        queryTuple: (Int, InetSocketAddress, InetSocketAddress) -> ConnectionOwnership.Result
    ): Report {
        val steps = mutableListOf<StepResult>()

        steps += if (loopbackPort == null || loopbackPort <= 0) {
            StepResult(
                StepId.LOOPBACK_PROPRIO, "${CdnRouterConfig.LOOPBACK_ADDRESS}:?", null,
                skippedReason = "listener de loopback não subiu"
            )
        } else {
            val target = "${CdnRouterConfig.LOOPBACK_ADDRESS}:$loopbackPort"
            val socket = runCatching { connect(CdnRouterConfig.LOOPBACK_ADDRESS, loopbackPort) }.getOrNull()
            if (socket == null) {
                StepResult(StepId.LOOPBACK_PROPRIO, target, null, skippedReason = "não foi possível conectar (porta fechada?)")
            } else {
                runCatching { socket.localPort }.getOrNull()?.let(selfPortRegistrar)
                val result = runCatching { querySocket(socket) }.getOrNull()
                    ?: ConnectionOwnership.Result(
                        ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS,
                        detail = "consulta falhou"
                    )
                runCatching { socket.close() }
                StepResult(
                    StepId.LOOPBACK_PROPRIO, target, result,
                    note = "socket aberto pelo PRÓPRIO launcher (loopback não passa pelo túnel)"
                )
            }
        }

        steps += if (!tunnelAddressAssigned) {
            StepResult(
                StepId.TUNEL_PROPRIO, "$tunnelAddress:$tunnelPort", null,
                skippedReason = "endereço do túnel não está atribuído a nenhuma interface"
            )
        } else {
            val target = "$tunnelAddress:$tunnelPort"
            val socket = runCatching { connect(tunnelAddress, tunnelPort) }.getOrNull()
            if (socket == null) {
                StepResult(StepId.TUNEL_PROPRIO, target, null, skippedReason = "não foi possível conectar")
            } else {
                runCatching { socket.localPort }.getOrNull()?.let(selfPortRegistrar)
                val result = runCatching { querySocket(socket) }.getOrNull()
                    ?: ConnectionOwnership.Result(ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS)
                runCatching { socket.close() }
                StepResult(
                    StepId.TUNEL_PROPRIO, target, result,
                    note = "conexão para o endereço do próprio túnel (loopback do tun, não sai do aparelho)"
                )
            }
        }

        // Tupla que não existe em tabela nenhuma: valida o significado "conexão não encontrada".
        val closed = runCatching {
            queryTuple(
                ConnectionOwnership.PROTOCOL_TCP,
                InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1),
                InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1)
            )
        }.getOrNull() ?: ConnectionOwnership.Result(ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS)
        steps += StepResult(
            StepId.TUPLA_INEXISTENTE, "127.0.0.1:1 -> 127.0.0.1:1", closed,
            note = "nenhum socket foi aberto: serve para separar 'não encontrado' de 'fora da VPN'"
        )

        steps += StepResult(
            StepId.ESCOPO_DA_VPN, "allowlist=${CdnRouterConfig.SESSION_NAME}", null,
            note = "a VPN cobre APENAS o app alvo; a API responde INVALID_UID para uid fora dela " +
                "(AOSP: appliesToUid) — inclusive para o próprio launcher"
        )
        return Report(steps)
    }

    /** Resumo curto para o card/log de sessão. */
    fun summaryLine(report: Report): String =
        "teste de controle de autoria: resolvidos=${report.resolved} INVALID_UID=${report.invalid} " +
            "sem-permissao=${report.noPermission}"
}
