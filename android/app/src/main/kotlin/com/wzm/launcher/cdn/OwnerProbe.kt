package com.wzm.launcher.cdn

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Controle de interpretação de `getConnectionOwnerUid` para M4.1.
 *
 * O runner real recebe um listener TCP efêmero ligado somente a 127.0.0.1, fora do HTTPS de produção.
 * O cliente é aberto pelo launcher; portanto o teste mede apenas o retorno da API para essa tupla local
 * conhecida. Não consulta o UID do WZM, não testa TUN e não prova origem/caminho de conexão do jogo.
 *
 * Um controle de tupla que não existe ajuda a demonstrar o limite da API: `INVALID_UID` também pode
 * significar tupla ausente. Ele não revela qual causa gerou um `INVALID_UID` numa conexão diferente.
 * A montagem do relatório é pura; os efeitos de conexão/consulta são injetados para testes JVM.
 */
object OwnerProbe {

    enum class StepId(val label: String) {
        LOOPBACK_PROPRIO("loopback_proprio"),
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
        val resolved: Int
            get() = steps.count { it.result?.provesOwner == true }
        val invalid: Int
            get() = steps.count { it.result?.outcome == ConnectionOwnership.Outcome.INVALID_UID }
        val noPermission: Int
            get() = steps.count { it.result?.outcome == ConnectionOwnership.Outcome.SECURITY_EXCEPTION }

        fun lines(): List<String> = steps.map { step ->
            when {
                step.skippedReason != null -> "controle ${step.id.label}: PULADO (${step.skippedReason})"
                step.result != null ->
                    "controle ${step.id.label} (${step.target}): ${ConnectionOwnership.describe(step.result)}" +
                        if (step.note.isEmpty()) "" else " · ${step.note}"
                step.note.isNotEmpty() -> "controle ${step.id.label} (${step.target}): CONTEXTO · ${step.note}"
                else -> "controle ${step.id.label} (${step.target}): SEM RESULTADO"
            }
        }

        /** Leitura limitada ao teste do launcher; nunca extrapola para UID/processo/caminho do WZM. */
        fun expectation(): String = when {
            resolved > 0 ->
                "VERIFIED: a API resolveu UID em $resolved consulta(s) de controle para socket(s) aberto(s) " +
                    "pelo próprio launcher; isso vale apenas para aquelas tuplas e não identifica PID/processo " +
                    "nem demonstra o resultado para o UID-alvo/WZM" +
                    if (invalid > 0 || noPermission > 0) {
                        " (outros passos: INVALID_UID=$invalid, sem-permissao=$noPermission)"
                    } else {
                        ""
                    }
            noPermission > 0 ->
                "UNKNOWN: SEM_PERMISSAO em $noPermission consulta(s); a API exige o VPN ativo (ou " +
                    "NETWORK_STACK); o controle não permite conclusão sobre loopback de outros UIDs/WZM"
            invalid > 0 ->
                "UNKNOWN: INVALID_UID em $invalid consulta(s) de controle; a API não distingue tupla ausente " +
                    "de UID fora do escopo observável, e esse resultado não pode ser generalizado ao WZM"
            else ->
                "UNKNOWN: nenhum controle de socket foi resolvido; verificar linhas PULADO/erro sem inferir " +
                    "autoria de outro app"
        }
    }

    /**
     * Executa o controle local injetável. `connect` só é chamado para loopback; nenhum socket remoto,
     * listener de produção, TLS ou caminho de túnel faz parte deste teste.
     */
    fun run(
        loopbackPort: Int?,
        targetPackage: String,
        connect: (String, Int) -> Socket?,
        querySocket: (Socket) -> ConnectionOwnership.Result,
        queryTuple: (Int, InetSocketAddress, InetSocketAddress) -> ConnectionOwnership.Result
    ): Report {
        val steps = mutableListOf<StepResult>()
        steps += if (loopbackPort == null || loopbackPort !in 1..65535) {
            StepResult(
                StepId.LOOPBACK_PROPRIO,
                "${CdnRouterConfig.LOOPBACK_ADDRESS}:?",
                null,
                skippedReason = "listener TCP efêmero de controle indisponível"
            )
        } else {
            val target = "${CdnRouterConfig.LOOPBACK_ADDRESS}:$loopbackPort"
            val socket = runCatching {
                connect(CdnRouterConfig.LOOPBACK_ADDRESS, loopbackPort)
            }.getOrNull()
            if (socket == null) {
                StepResult(
                    StepId.LOOPBACK_PROPRIO,
                    target,
                    null,
                    skippedReason = "não foi possível conectar ao listener local de controle"
                )
            } else {
                val result = runCatching { querySocket(socket) }.getOrElse { error ->
                    ConnectionOwnership.classifyException(error)
                }
                runCatching { socket.close() }
                StepResult(
                    StepId.LOOPBACK_PROPRIO,
                    target,
                    result,
                    note = "socket conhecido aberto pelo PRÓPRIO launcher; consulta deste socket não é evidência WZM"
                )
            }
        }

        // A tupla é intencionalmente inexistente: nenhum socket foi aberto para esses endpoints.
        val missing = runCatching {
            queryTuple(
                ConnectionOwnership.PROTOCOL_TCP,
                InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1),
                InetSocketAddress(CdnRouterConfig.LOOPBACK_ADDRESS, 1)
            )
        }.getOrElse { error -> ConnectionOwnership.classifyException(error) }
        steps += StepResult(
            StepId.TUPLA_INEXISTENTE,
            "127.0.0.1:1 -> 127.0.0.1:1",
            missing,
            note = "nenhum socket foi aberto para a tupla; controle do significado possível de INVALID_UID"
        )

        steps += StepResult(
            StepId.ESCOPO_DA_VPN,
            "pacote-alvo=$targetPackage",
            null,
            note = "contexto da sessão, não consulta de ownership: este controle não consulta o UID do alvo " +
                "nem prova que cada socket dele foi capturado"
        )
        return Report(steps)
    }

    /** Resumo curto para o card/log de sessão. */
    fun summaryLine(report: Report): String =
        "teste de controle de autoria (launcher, só loopback): resolvidos=${report.resolved} " +
            "INVALID_UID=${report.invalid} sem-permissao=${report.noPermission}"
}
