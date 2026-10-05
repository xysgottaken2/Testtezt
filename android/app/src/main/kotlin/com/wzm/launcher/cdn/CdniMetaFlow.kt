package com.wzm.launcher.cdn

/**
 * Experimento M4.4 — `cdni.meta` **real** servido pelo servidor local: classificação de origem de
 * cada requisição e registro do **próximo pedido** depois do meta.
 *
 * O que este rastreio responde:
 *  - qual pedido chegou ao listener depois de servirmos o `cdni.meta`, com Δt, método, path, host e
 *    status — inclusive quando esse pedido é desconhecido (404 controlado, que é justamente o dado
 *    que revela a próxima URL tentada pelo cliente);
 *  - se esse pedido caiu **dentro** da janela do teste sintético do launcher ou **fora** dela.
 *
 * O que ele **NÃO** responde: quem abriu a conexão.
 *  - `SINTETICO-LAUNCHER` = sobreposição temporal com a janela do [SyntheticFlowTest] (correlação);
 *  - `FORA-DA-JANELA-SINTETICA` = apenas "não foi durante o nosso teste". **Nunca** significa
 *    "é o WZM": a atribuição continua exigindo owner UID/tupla, conforme M4.1.
 *
 * Nada aqui altera TLS/trust/pinning, o TUN, o DNS virtual, o corpo servido nem o roteamento.
 */
object CdniMetaFlow {

    /** Tag própria (aparece como chip na tela VER LOGS). */
    const val TAG = "CDNI-META"

    /**
     * Origem declarada de uma requisição. É uma **classificação temporal**, não uma autoria.
     */
    enum class Origin(val label: String, val synthetic: Boolean) {
        /** Dentro da janela do teste sintético aberto pelo próprio launcher. */
        SINTETICO_LAUNCHER("SINTETICO-LAUNCHER", true),

        /** Fora da janela sintética: não foi o nosso teste — não implica que seja o WZM. */
        FORA_DA_JANELA_SINTETICA("FORA-DA-JANELA-SINTETICA", false)
    }

    /** Entrega do log (os testes substituem para não depender do buffer global). */
    @Volatile
    var log: (String, String) -> Unit = { tag, message -> RequestLog.add(tag, message) }

    private data class Hit(val peer: String, val origin: Origin, val atMillis: Long)

    @Volatile
    private var lastMeta: Hit? = null

    /** O 1º pedido depois do meta ganha linha própria; os seguintes só levam o Δ no sufixo. */
    @Volatile
    private var nextLogged = false

    fun originOf(synthetic: Boolean): Origin =
        if (synthetic) Origin.SINTETICO_LAUNCHER else Origin.FORA_DA_JANELA_SINTETICA

    /** O path do meta é comparado normalizado (minúsculas, sem query). */
    fun isMetaPath(path: String): Boolean =
        BootstrapEndpoints.normalize(path) == CdnRouterConfig.CDNI_META_PATH

    /**
     * Registra uma requisição já atendida e devolve o sufixo curto que entra nas linhas
     * `[CDNI]`/`[HTTP]`. Quando o pedido é o próprio meta, reinicia o rastreio do "próximo pedido".
     */
    @Synchronized
    fun observe(
        head: HttpRequestHead,
        outcome: HttpOutcome,
        peer: String,
        synthetic: Boolean,
        atMillis: Long
    ): String {
        val origin = originOf(synthetic)
        if (isMetaPath(head.path)) {
            lastMeta = Hit(peer, origin, atMillis)
            nextLogged = false
            RequestLog.incCdniMeta(synthetic)
            log(
                TAG,
                "cdni.meta servido: status=${outcome.status} resposta=${outcome.bytes.size} B · cliente=$peer · " +
                    "origem=${origin.label} · corpo real (min_buildnum=${CdniMetaBody.MIN_BUILDNUM}) sem alteração; " +
                    "chaves #x… registradas como opacas, nenhuma decodificada"
            )
            return " · origem=${origin.label} · cdni.meta=servido"
        }
        val meta = lastMeta ?: return " · origem=${origin.label}"
        val deltaMs = atMillis - meta.atMillis
        RequestLog.incPedidoAposCdniMeta()
        if (!nextLogged) {
            nextLogged = true
            log(
                TAG,
                "PROXIMO-PEDIDO-APOS-CDNI.META: ${head.method} ${head.path} · status=${outcome.status} · " +
                    "host=${head.host ?: "?"} · Δ=${deltaMs} ms depois do meta · cliente=$peer · " +
                    "origem=${origin.label} · (origem é correlação temporal; atribuição exige owner UID/tupla, M4.1)"
            )
        }
        return " · origem=${origin.label} · apos-cdni.meta=Δ${deltaMs}ms"
    }

    /** Limpa o rastreio (testes e início de sessão). */
    @Synchronized
    fun reset() {
        lastMeta = null
        nextLogged = false
    }
}
