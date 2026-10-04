package com.wzm.launcher.cdn

/**
 * Quem abriu aquela conexão no listener **de loopback**? (M4.1)
 *
 * O caminho de loopback (`127.0.0.1:443`) não passa pelo túnel: o kernel entrega o pacote na própria
 * pilha local (`local` table, prioridade 0), então **nenhum** método que olha o `tun` (uid por pacote,
 * contadores do túnel, rotas da VPN) enxerga essa conexão. E o listener é alcançável por **qualquer**
 * app do aparelho — a VPN per-app não restringe loopback.
 *
 * Este módulo junta as evidências disponíveis e devolve um veredito **com o nível de confiança embutido**,
 * nunca uma afirmação de autoria. Classificação pura: recebe os fatos prontos.
 */
object LoopbackOrigin {

    /**
     * Veredito de origem. `evidence` é o **nível típico** do veredito; o nível exato de uma conclusão
     * viaja em [Conclusion.evidence] — o mesmo "mesmo processo" pode ser `VERIFIED` (uid resolvido /
     * porta registrada pelo próprio processo) ou `PROBABLE` (porta na janela efêmera compartilhada).
     */
    enum class Verdict(val label: String, val evidence: Evidence) {
        MESMO_PROCESSO("mesmo-processo-do-launcher", Evidence.VERIFIED),
        APP_ALVO("app-alvo", Evidence.VERIFIED),
        OUTRO_UID("outro-uid", Evidence.VERIFIED),
        INDETERMINADO("indeterminado", Evidence.UNKNOWN)
    }

    /**
     * Fatos de uma conexão aceita no listener.
     *
     * @param roleLoopback `true` quando o listener é o de loopback (diagnóstico secundário).
     * @param ownerUidResolvido uid devolvido pela API (null = `INVALID_UID`/indisponível).
     * @param duranteTesteSintetico a conexão caiu na janela do teste sintético (o launcher conecta de propósito).
     * @param portVerdict o que a porta de origem diz em relação à janela efêmera deste processo.
     */
    data class Facts(
        val roleLoopback: Boolean,
        val peerAddress: String,
        val peerPort: Int,
        val ownerUidResolvido: Int?,
        val launcherUid: Int,
        val targetUid: Int?,
        val duranteTesteSintetico: Boolean,
        val portVerdict: SelfPorts.PortVerdict
    )

    data class Conclusion(val verdict: Verdict, val evidence: Evidence, val detail: String)

    fun conclude(facts: Facts): Conclusion = when {
        facts.ownerUidResolvido != null && facts.ownerUidResolvido == facts.launcherUid ->
            Conclusion(
                Verdict.MESMO_PROCESSO,
                Evidence.VERIFIED,
                "getConnectionOwnerUid resolveu uid=${facts.launcherUid}: é o próprio launcher"
            )
        facts.ownerUidResolvido != null && facts.targetUid != null && facts.ownerUidResolvido == facts.targetUid ->
            Conclusion(
                Verdict.APP_ALVO,
                Evidence.VERIFIED,
                "getConnectionOwnerUid resolveu uid=${facts.targetUid}: é o app alvo " +
                    "(a API só resolve uid coberto pela VPN — isto é evidência de autoria)"
            )
        facts.ownerUidResolvido != null ->
            Conclusion(
                Verdict.OUTRO_UID,
                Evidence.VERIFIED,
                "getConnectionOwnerUid resolveu uid=${facts.ownerUidResolvido} (nem launcher nem app alvo)"
            )
        facts.duranteTesteSintetico && facts.portVerdict == SelfPorts.PortVerdict.NA_JANELA_DO_PROCESSO ->
            Conclusion(
                Verdict.MESMO_PROCESSO,
                Evidence.PROBABLE,
                "conexão caiu na janela do teste sintético e a porta de origem está na janela efêmera " +
                    "deste processo — leitura: o próprio launcher (a janela é compartilhada: PROBABLE, não prova)"
            )
        facts.portVerdict == SelfPorts.PortVerdict.REGISTRADA_PELO_PROCESSO ->
            Conclusion(
                Verdict.MESMO_PROCESSO,
                Evidence.VERIFIED,
                "porta de origem foi registrada pelo próprio launcher ao abrir a conexão (VERIFIED)"
            )
        else -> Conclusion(
            Verdict.INDETERMINADO,
            Evidence.UNKNOWN,
            "sem uid resolvido (INVALID_UID) e sem marca do próprio processo" +
                if (facts.roleLoopback) {
                    "; loopback é alcançável por qualquer app e não passa pelo túnel — atribuição " +
                        "exige uid da API, que só resolve para uid DENTRO da VPN"
                } else {
                    ""
                }
        )
    }

    /**
     * Veredito + nível de evidência exato + linha pronta. O listener usa o veredito (contador) e o
     * texto (log); a UI/humano usam o nível — nunca o "nível típico" do enum.
     */
    data class Report(val verdict: Verdict, val evidence: Evidence, val text: String)

    fun report(facts: Facts): Report {
        val conclusion = conclude(facts)
        return Report(conclusion.verdict, conclusion.evidence, line(facts, conclusion))
    }

    /** Linha única para o log: veredito + por quê + a ressalva de confiança. */
    fun line(facts: Facts, conclusion: Conclusion = conclude(facts)): String = buildString {
        append("origem-da-conexao=").append(conclusion.verdict.label)
        append(" (").append(conclusion.evidence.name).append("): ").append(conclusion.detail)
        append(" · porta-de-origem=").append(facts.peerPort).append(" [").append(facts.portVerdict.label).append(']')
        append(" · peer=").append(facts.peerAddress)
    }
}
