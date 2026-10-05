package com.wzm.launcher.cdn

/**
 * Origem do peer de uma conexão aceita pelo listener de loopback (M4.1).
 *
 * A atribuição desta camada é apenas sobre **quem abriu esta conexão local**. Ela não prova que o
 * WZM fez tráfego externo, acessou CDNI, passou pelo TUN ou concluiu uma requisição. O loopback fica
 * sempre como diagnóstico secundário e precisa ser correlacionado ao evento de lançamento do
 * launcher (que não é um evento de criação de processo).
 *
 * A API de ownership devolve UID, nunca PID. Logo um UID do launcher/app-alvo não identifica qual
 * processo dentro do pacote abriu o socket; somente a tupla completa registrada pelo próprio launcher
 * pode identificar com precisão uma conexão que esse processo abriu.
 */
object LoopbackOrigin {

    enum class Verdict(val label: String, val evidence: Evidence) {
        PROCESSO_LAUNCHER("processo-do-launcher", Evidence.VERIFIED),
        UID_LAUNCHER("uid-do-launcher", Evidence.VERIFIED),
        UID_APP_ALVO("uid-do-app-alvo", Evidence.VERIFIED),
        OUTRO_UID("outro-uid", Evidence.VERIFIED),
        POSSIVEL_LAUNCHER("possivel-launcher", Evidence.PROBABLE),
        INDETERMINADO("indeterminado", Evidence.UNKNOWN)
    }

    /** Fatos de uma conexão aceita no listener. */
    data class Facts(
        /** `true` quando o listener é o de loopback (diagnóstico secundário). */
        val roleLoopback: Boolean,
        val peerAddress: String,
        val peerPort: Int,
        /** Resultado estruturado da consulta peer -> listener; nulo se a consulta não foi executada. */
        val peerOwnerResult: ConnectionOwnership.Result?,
        val launcherUid: Int,
        val targetUid: Int?,
        /** Só marca coincidência temporal com teste; não é prova causal da conexão. */
        val duranteTesteSintetico: Boolean,
        /** Tupla exata registrada ou sinal limitado da porta do peer. */
        val portVerdict: SelfPorts.PortVerdict,
        /** PID do processo que registrou a tupla; só existe para sockets abertos pelo launcher. */
        val registeredProcessPid: Int? = null
    ) {
        /** UID só é considerado resolvido se o resultado estruturado o afirma. */
        val peerUidResolvido: Int?
            get() = peerOwnerResult?.takeIf { it.provesOwner }?.uid
    }

    data class Conclusion(val verdict: Verdict, val evidence: Evidence, val detail: String)

    fun conclude(facts: Facts): Conclusion {
        val peerUid = facts.peerUidResolvido
        return when {
            facts.portVerdict == SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO ->
                Conclusion(
                    Verdict.PROCESSO_LAUNCHER,
                    Evidence.VERIFIED,
                    "a tupla completa do peer casou com um socket registrado pelo processo launcher" +
                        (facts.registeredProcessPid?.let { " (PID=$it)" } ?: " (PID indisponível)")
                )
            peerUid != null && peerUid == facts.launcherUid ->
                Conclusion(
                    Verdict.UID_LAUNCHER,
                    Evidence.VERIFIED,
                    "getConnectionOwnerUid resolveu UID=${facts.launcherUid} do launcher; a API não identifica PID/processo"
                )
            peerUid != null && facts.targetUid != null && peerUid == facts.targetUid ->
                Conclusion(
                    Verdict.UID_APP_ALVO,
                    Evidence.VERIFIED,
                    "getConnectionOwnerUid resolveu o UID=${facts.targetUid} do app-alvo para este peer local; " +
                        "não identifica processo/PID nem prova tráfego externo do WZM"
                )
            peerUid != null ->
                Conclusion(
                    Verdict.OUTRO_UID,
                    Evidence.VERIFIED,
                    "getConnectionOwnerUid resolveu UID=$peerUid para este peer; a API não identifica processo/PID"
                )
            facts.duranteTesteSintetico &&
                facts.portVerdict == SelfPorts.PortVerdict.NA_JANELA_EFIMERA_OBSERVADA ->
                Conclusion(
                    Verdict.POSSIVEL_LAUNCHER,
                    Evidence.PROBABLE,
                    "coincidiu com a janela temporal do teste sintético e a porta caiu na faixa efêmera " +
                        "amostrada; ambas são pistas compartilhadas, não provam que este peer seja o launcher"
                )
            else -> Conclusion(
                Verdict.INDETERMINADO,
                Evidence.UNKNOWN,
                unknownReason(facts.peerOwnerResult) +
                    " e sem tupla exata do launcher" +
                    if (facts.roleLoopback) {
                        "; loopback pode ser alcançado por outros apps e esta conexão local não prova tráfego externo do WZM"
                    } else {
                        ""
                    }
            )
        }
    }

    private fun unknownReason(result: ConnectionOwnership.Result?): String = when (result?.outcome) {
        ConnectionOwnership.Outcome.INVALID_UID ->
            "sem UID resolvido: INVALID_UID pode significar tupla ausente ou UID fora do escopo da VPN"
        ConnectionOwnership.Outcome.SECURITY_EXCEPTION ->
            "sem UID resolvido: a API recusou a consulta (SecurityException); autoria UNKNOWN"
        ConnectionOwnership.Outcome.API_ANTIGA ->
            "sem UID resolvido: API 29+ necessária neste device"
        ConnectionOwnership.Outcome.SERVICO_INDISPONIVEL ->
            "sem UID resolvido: ConnectivityManager indisponível"
        ConnectionOwnership.Outcome.ENDERECOS_INDISPONIVEIS ->
            "sem UID resolvido: socket sem tupla local/remota utilizável"
        ConnectionOwnership.Outcome.ARGUMENTO_INVALIDO ->
            "sem UID resolvido: protocolo/parâmetros recusados pela API"
        ConnectionOwnership.Outcome.CONSULTA_FALHOU ->
            "sem UID resolvido: consulta falhou por erro da plataforma"
        ConnectionOwnership.Outcome.RESOLVIDO ->
            "sem UID utilizável no resultado resolvido"
        null ->
            "owner lookup não executado ou indisponível"
    }

    data class Report(val verdict: Verdict, val evidence: Evidence, val text: String)

    fun report(facts: Facts): Report {
        val conclusion = conclude(facts)
        return Report(conclusion.verdict, conclusion.evidence, line(facts, conclusion))
    }

    fun line(facts: Facts, conclusion: Conclusion = conclude(facts)): String = buildString {
        append("origem-da-conexao=").append(conclusion.verdict.label)
        append(" (").append(conclusion.evidence.name).append("): ").append(conclusion.detail)
        append(" · porta-do-peer=").append(facts.peerPort).append(" [").append(facts.portVerdict.label).append(']')
        append(" · peer=").append(facts.peerAddress)
        if (facts.peerOwnerResult != null) {
            append(" · owner-outcome=").append(facts.peerOwnerResult.outcome.code)
        }
        if (facts.portVerdict == SelfPorts.PortVerdict.TUPLA_REGISTRADA_PELO_PROCESSO &&
            facts.registeredProcessPid != null) {
            append(" · pid-do-processo-registrado=").append(facts.registeredProcessPid)
        }
        if (facts.roleLoopback) append(" · loopback=DIAGNOSTICO_NAO_PROVA_TRAFEGO_WZM")
    }
}
