package com.wzm.launcher.cdn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M4.4 — experimento mínimo do `cdni.meta`:
 *
 *  1. o corpo servido é **exatamente** o JSON real do CDN (nada inventado, nenhum campo a mais);
 *  2. toda requisição sai com `origem=` (janela sintética do launcher × fora dela);
 *  3. o **primeiro** pedido depois do meta é registrado em linha própria, com Δt e path exato —
 *     é assim que o 404 controlado revela a próxima URL tentada pelo cliente.
 *
 * Nenhum teste aqui afirma que um pedido "fora da janela" seja do WZM: sem owner UID/tupla, a
 * autoria continua `UNKNOWN` (M4.1).
 */
class CdniMetaFlowTest {

    private val captured = mutableListOf<String>()

    @Before
    fun setUp() {
        captured.clear()
        RequestLog.clear()
        CdniMetaFlow.reset()
        CdniMetaFlow.log = { tag, message -> captured.add("[$tag] $message") }
    }

    @After
    fun tearDown() {
        CdniMetaFlow.log = { tag, message -> RequestLog.add(tag, message) }
        CdniMetaFlow.reset()
        RequestLog.clear()
    }

    // ---------- 1. o corpo é o JSON real ----------

    private val upstreamJson = """
        {
            "min_tu": 0,
            "min_buildnum": 19854920,
            "app_store_url": "https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone",
            "#x3a74898c63cb5c55a": true,
            "#x3e9cc40e792dcfdcc": true,
            "#x3e93a69101751bfa8": false,
            "#x3b20ca17f00dfb4c9": false,
            "#x3c839f93c3b076242": true,
            "#x3bc57a21a42173b49": true,
            "#x377addea98016dad6": true
        }
    """.trimIndent()

    @Test
    fun servedBodyIsByteForByteTheUpstreamJson() {
        assertEquals(
            "o corpo servido precisa ser exatamente o cdni.meta real (sem campo inventado)",
            upstreamJson,
            CdniMetaBody.ANDROID
        )
        assertTrue(CdniMetaBody.looksLikeRealBody(CdniMetaBody.ANDROID))
    }

    @Test
    fun minBuildnumMatchesTheInstalledBuild() {
        assertTrue(
            "min_buildnum do CDN é igual ao build instalado (M4.0): ${CdniMetaBody.INSTALLED_BUILD_LABEL}",
            CdniMetaBody.ANDROID.contains("\"min_buildnum\": ${CdniMetaBody.MIN_BUILDNUM}")
        )
        assertEquals("19854920", CdniMetaBody.INSTALLED_BUILD_LABEL.substringAfterLast('.'))
    }

    @Test
    fun hashedFlagKeysAreRegisteredButNeverInterpreted() {
        val keys = CdniMetaBody.flagKeys()
        assertEquals(
            listOf(
                "#x3a74898c63cb5c55a",
                "#x3e9cc40e792dcfdcc",
                "#x3e93a69101751bfa8",
                "#x3b20ca17f00dfb4c9",
                "#x3c839f93c3b076242",
                "#x3bc57a21a42173b49",
                "#x377addea98016dad6"
            ),
            keys
        )
        // Opaquicidade: a única coisa que o código faz com elas é devolvê-las como vieram.
        assertTrue("nenhuma chave pode ser renomeada/traduzida", keys.all { it.startsWith("#x") })
        assertTrue("o corpo continua sem nenhum nome inventado", !CdniMetaBody.ANDROID.contains("force_update"))
    }

    // ---------- 2. resposta: 200 + application/json ----------

    @Test
    fun cdniMetaRouteAnswers200JsonWithTheRealBody() {
        val outcome = CdnRouteTable.respond(headOf(CdnRouterConfig.CDNI_META_PATH))
        val text = String(outcome.bytes, Charsets.ISO_8859_1)

        assertEquals(200, outcome.status)
        assertTrue(text.startsWith("HTTP/1.1 200 OK"))
        assertTrue(text.contains("Content-Type: application/json; charset=utf-8"))
        assertTrue(text.contains("\"min_buildnum\": 19854920"))
        assertTrue("nada de placeholder no lugar do corpo real", !text.contains(PLACEHOLDER_MARKER))
        assertTrue(
            "a resposta termina com o corpo real, sem alteração de um byte",
            text.endsWith(CdniMetaBody.ANDROID)
        )
    }

    // ---------- 3. origem: sintético × fora da janela ----------

    @Test
    fun requestInsideSyntheticWindowIsLabelledAsLauncher() {
        RequestLog.markSyntheticTestStarted()
        val suffix = CdniMetaFlow.observe(
            headOf(CdnRouterConfig.CDNI_META_PATH),
            outcome(),
            peer = "10.111.222.1:51244",
            synthetic = RequestLog.isDuringSyntheticTest(System.currentTimeMillis()),
            atMillis = System.currentTimeMillis()
        )
        RequestLog.markSyntheticTestFinished()

        assertTrue(suffix.contains("origem=SINTETICO-LAUNCHER"))
        assertTrue(suffix.contains("cdni.meta=servido"))
        assertEquals(1, RequestLog.counters.value.cdniMetaServidos)
        assertEquals(1, RequestLog.counters.value.cdniMetaSintetico)
        assertEquals(0, RequestLog.counters.value.cdniMetaForaDaJanela)
        assertTrue(captured.any { it.contains("origem=SINTETICO-LAUNCHER") })
    }

    @Test
    fun requestOutsideSyntheticWindowIsNotLabelledAsLauncher() {
        val suffix = CdniMetaFlow.observe(
            headOf(CdnRouterConfig.CDNI_META_PATH),
            outcome(),
            peer = "10.111.222.1:51244",
            synthetic = false,
            atMillis = System.currentTimeMillis()
        )

        assertTrue(suffix.contains("origem=FORA-DA-JANELA-SINTETICA"))
        assertEquals(1, RequestLog.counters.value.cdniMetaForaDaJanela)
        assertEquals(0, RequestLog.counters.value.cdniMetaSintetico)
    }

    // ---------- 4. próximo pedido depois do cdni.meta ----------

    @Test
    fun firstRequestAfterCdniMetaIsLoggedWithDeltaAndExactPath() {
        val start = 1_700_000_000_000L
        CdniMetaFlow.observe(headOf(CdnRouterConfig.CDNI_META_PATH), outcome(), "10.111.222.1:51244", false, start)
        CdniMetaFlow.observe(headOf("/wzm/shard_cdn/android/_manifest/manifest.json"), outcome(), "10.111.222.1:51299", false, start + 250)

        val next = captured.first { it.startsWith("[${CdniMetaFlow.TAG}]") && it.contains("PROXIMO-PEDIDO-APOS-CDNI.META") }
        assertTrue("o path exato do próximo pedido precisa aparecer", next.contains("/wzm/shard_cdn/android/_manifest/manifest.json"))
        assertTrue("o Δ desde o meta precisa aparecer", next.contains("Δ=250 ms"))
        assertTrue("o host precisa aparecer", next.contains("host=prod.cdni.callofduty.com"))
        assertEquals(1, RequestLog.counters.value.pedidosAposCdniMeta)
    }

    @Test
    fun onlyTheFirstRequestAfterCdniMetaGetsADedicatedLine() {
        val start = 1_700_000_000_000L
        CdniMetaFlow.observe(headOf(CdnRouterConfig.CDNI_META_PATH), outcome(), "10.111.222.1:51244", false, start)
        CdniMetaFlow.observe(headOf("/a"), outcome(), "10.111.222.1:51299", false, start + 100)
        CdniMetaFlow.observe(headOf("/b"), outcome(), "10.111.222.1:51300", false, start + 200)

        assertEquals(
            "só o 1º pedido depois do meta ganha linha própria (os demais levam só o Δ)",
            1,
            captured.count { it.contains("PROXIMO-PEDIDO-APOS-CDNI.META") }
        )
        assertEquals(2, RequestLog.counters.value.pedidosAposCdniMeta)
    }

    @Test
    fun unknownPathAfterCdniMetaIsRecordedWithItsExactPath() {
        val start = 1_700_000_000_000L
        CdniMetaFlow.observe(headOf(CdnRouterConfig.CDNI_META_PATH), outcome(), "10.111.222.1:51244", false, start)
        val unknownPath = "/wzm/shard_cdn/android/_manifest/qualquer-coisa-ainda-desconhecida"
        CdniMetaFlow.observe(headOf(unknownPath), outcome(status = 404), "10.111.222.1:51310", false, start + 75)

        val next = captured.first { it.contains("PROXIMO-PEDIDO-APOS-CDNI.META") }
        assertTrue("o 404 controlado é o dado que revela a próxima URL", next.contains(unknownPath))
        assertTrue(next.contains("status=404"))
        assertTrue("a linha precisa dizer que origem não é autoria", next.contains("atribuição exige owner UID/tupla"))
    }

    @Test
    fun resetClearsTheCdniMetaTracking() {
        val start = 1_700_000_000_000L
        CdniMetaFlow.observe(headOf(CdnRouterConfig.CDNI_META_PATH), outcome(), "10.111.222.1:51244", false, start)
        CdniMetaFlow.reset()

        val suffix = CdniMetaFlow.observe(headOf("/a"), outcome(), "10.111.222.1:51299", false, start + 10)
        assertTrue("sem meta anterior não há sufixo de 'depois do meta'", !suffix.contains("apos-cdni.meta"))
    }

    // ---------- auxiliares ----------

    private fun headOf(target: String): HttpRequestHead =
        HttpRequestHead("GET", target, "HTTP/1.1", mapOf("host" to "prod.cdni.callofduty.com"))

    private fun outcome(status: Int = 200): HttpOutcome =
        HttpOutcome(
            status = status,
            confidence = Confidence.VERIFIED,
            bytes = HttpResponses.build(status, "application/json; charset=utf-8", ByteArray(0)),
            logTag = "CDNI",
            logMessage = "GET ${CdnRouterConfig.CDNI_META_PATH} HTTP/1.1 -> $status"
        )
}
