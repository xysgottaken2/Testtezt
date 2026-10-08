package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 — contrato **byte-a-byte** da resposta local do `cdni.meta` (android).
 *
 * Por que este arquivo existe se `CdniMetaFlowTest`, `CdnRouteTableTest` e `LocalHttpsServerTest`
 * já cobrem o endpoint: eles travam o **conteúdo** (um literal próprio, ou a assinatura frouxa
 * `looksLikeRealBody`) e o **status**; ninguém travava a **resposta inteira** — a ordem e o conjunto
 * exato de cabeçalhos, o `Content-Length` real, o `Content-Type` sem duplicata, a ausência de BOM,
 * as quebras CRLF do arquivo original e o fato de o caminho android nunca receber o corpo iOS.
 * Esta é a garantia pedida no M7 (item 11): a resposta do experimento não pode derivar.
 *
 * O corpo abaixo é escrito **por extenso**, independente de [CdniMetaBody]: se alguém editar o corpo
 * e o teste que o compara juntos, continua existindo uma segunda âncora — a especificação do
 * experimento. As linhas são unidas por `CRLF` porque é isso que o arquivo do CDN usa (observação
 * ao vivo de 2026-10-08); LF aqui seria um corpo diferente em 11 bytes.
 *
 * Nada neste arquivo interpreta as chaves `#x…` (item 5): elas são comparadas exatamente como vieram.
 */
class CdniMetaResponseContractTest {

    /** Ordem e valores exatos: 10 membros, nenhuma chave a mais, nenhuma a menos, sem quebra final. */
    private val expectedLines = listOf(
        "{",
        "    \"min_tu\": 0,",
        "    \"min_buildnum\": 19854920,",
        "    \"app_store_url\": \"https://play.google.com/store/apps/details?id=com.activision.callofduty.warzone\",",
        "    \"#x3a74898c63cb5c55a\": true,",
        "    \"#x3e9cc40e792dcfdcc\": true,",
        "    \"#x3e93a69101751bfa8\": false,",
        "    \"#x3b20ca17f00dfb4c9\": false,",
        "    \"#x3c839f93c3b076242\": true,",
        "    \"#x3bc57a21a42173b49\": true,",
        "    \"#x377addea98016dad6\": true",
        "}"
    )

    /** O JSON especificado para o experimento, como o CDN o entrega: CRLF, 4 espaços, sem linha final. */
    private val expectedBody: String = expectedLines.joinToString("\r\n")

    /** Tamanho do arquivo real observado em 2026-10-08 (386 B em LF + 11 quebras CRLF). */
    private val expectedSize = 397

    /**
     * Cabeçalhos da resposta, **nesta ordem e só estes** ([HttpResponses] escreve status, Content-Type,
     * Content-Length, Connection, Cache-Control e depois os extras; a marcação de "servido localmente"
     * vai em cabeçalho, nunca no corpo). Igualdade de lista: um cabeçalho a mais, a menos ou duplicado
     * também quebra o teste.
     */
    private val expectedHeadLines = listOf(
        "HTTP/1.1 200 OK",
        "Content-Type: application/json; charset=utf-8",
        "Content-Length: $expectedSize",
        "Connection: close",
        "Cache-Control: no-store",
        "X-WZM-Offline: VERIFIED",
        "X-WZM-Offline-Path: ${CdnRouterConfig.CDNI_META_PATH}"
    )

    private val separator = "\r\n\r\n"

    @Test
    fun servedBodyIsTheDocumentedJsonByteForByte() {
        assertEquals(
            "o corpo servido é exatamente o cdni.meta real especificado (M7 item 2)",
            expectedBody,
            CdniMetaBody.ANDROID
        )
        assertEquals("nem um membro a mais, nem um a menos", expectedLines, expectedBody.split("\r\n"))
        assertEquals(
            "o corpo é entregue sem modificação de um byte",
            expectedBody.toByteArray(Charsets.UTF_8).toList(),
            CdniMetaBody.android().toList()
        )
        assertEquals("corpo real = CRLF, como no arquivo do CDN", expectedLines.size - 1, expectedBody.count { it == '\r' })
    }

    @Test
    fun responseHeadIsExactlyTheSpecifiedHeaders() {
        val text = String(CdnRouteTable.respond(metaHead()).bytes, Charsets.ISO_8859_1)
        val head = text.substringBefore(separator)

        assertEquals(
            "linhas de cabeçalho exatas (inclusive o Content-Length real)",
            expectedHeadLines,
            head.split("\r\n")
        )
        assertEquals("um só Content-Type", 1, head.lines().count { it.startsWith("Content-Type:") })
        assertTrue(
            "Content-Type precisa declarar JSON (M7 item 4)",
            head.contains("Content-Type: application/json")
        )
        assertEquals("status 200 (M7 item 3)", 200, CdnRouteTable.respond(metaHead()).status)
    }

    @Test
    fun bodyIsUtf8WithoutBomAndWithoutTrailingNewline() {
        val bytes = CdniMetaBody.android()

        assertEquals("tamanho travado: qualquer byte a mais no corpo muda o Content-Length", expectedSize, bytes.size)
        assertEquals("primeiro byte é a chave de abertura, não BOM", '{'.code.toByte(), bytes.first())
        assertFalse("sem BOM", String(bytes, Charsets.UTF_8).startsWith("\uFEFF"))
        assertFalse("sem quebra de linha final", bytes.last() == '\n'.code.toByte())
        assertEquals("fechado pela chave de fechamento", '}'.code.toByte(), bytes.last())
    }

    @Test
    fun responseCarriesTheBodyAfterTheHeadAndNothingElse() {
        val outcome = CdnRouteTable.respond(metaHead())
        val index = String(outcome.bytes, Charsets.ISO_8859_1).indexOf(separator)

        assertTrue("a resposta precisa separar cabeçalho e corpo com CRLFCRLF", index > 0)
        assertEquals(
            "corpo = bytes depois do cabeçalho, sem acréscimo nem truncamento",
            expectedBody.toByteArray(Charsets.UTF_8).toList(),
            outcome.bytes.copyOfRange(index + separator.length, outcome.bytes.size).toList()
        )
        val whole = String(outcome.bytes, Charsets.ISO_8859_1)
        assertFalse("nenhum placeholder no lugar do corpo real", whole.contains(PLACEHOLDER_MARKER))
        assertFalse("nenhum campo acrescentado ao JSON", whole.contains("wzm-offline-local"))
    }

    @Test
    fun hashedFlagKeysTravelUnchanged() {
        val expectedFlags = listOf(
            "#x3a74898c63cb5c55a" to true,
            "#x3e9cc40e792dcfdcc" to true,
            "#x3e93a69101751bfa8" to false,
            "#x3b20ca17f00dfb4c9" to false,
            "#x3c839f93c3b076242" to true,
            "#x3bc57a21a42173b49" to true,
            "#x377addea98016dad6" to true
        )

        assertEquals(
            "as 7 chaves, na ordem e a quantidade em que chegaram do CDN",
            expectedFlags.map { it.first },
            CdniMetaBody.flagKeys()
        )
        for ((key, value) in expectedFlags) {
            assertEquals(
                "\"$key\" aparece exatamente uma vez",
                1,
                expectedLines.count { it.startsWith("    \"$key\"") }
            )
            assertTrue(
                "a chave $key precisa sair com o valor observado ($value)",
                expectedLines.contains("    \"$key\": $value") ||
                    expectedLines.contains("    \"$key\": $value,")
            )
        }
        // Opacidade (item 5): nenhuma tradução/renomeação acontece no corpo servido.
        assertFalse("nada de nome legível no lugar do hash", expectedBody.contains("force_update"))
        assertFalse(expectedBody.contains("maintenance"))
    }

    @Test
    fun androidPathNeverServesTheIosBody() {
        val text = String(CdnRouteTable.respond(metaHead()).bytes, Charsets.UTF_8)

        assertFalse(
            "o corpo iOS (future_*/apps.apple.com) não pode vazar no caminho android",
            text.contains("future_")
        )
        assertFalse(text.contains("apps.apple.com"))
        assertTrue("o corpo iOS segue disponível para o caminho iOS", CdniMetaBody.IOS.contains("future_app_id"))
    }

    @Test
    fun queryStringDoesNotChangeTheServedResponse() {
        val withQuery = CdnRouteTable.respond(
            HttpRequestHead(
                "GET",
                "${CdnRouterConfig.CDNI_META_PATH}?build=15&x=1",
                "HTTP/1.1",
                mapOf("host" to "prod.cdni.callofduty.com")
            )
        )

        assertEquals(
            "o path é comparado normalizado (sem query): mesma resposta byte a byte",
            CdnRouteTable.respond(metaHead()).bytes.toList(),
            withQuery.bytes.toList()
        )
    }

    // ---------- auxiliares ----------

    private fun metaHead(): HttpRequestHead =
        HttpRequestHead(
            "GET",
            CdnRouterConfig.CDNI_META_PATH,
            "HTTP/1.1",
            mapOf("host" to "prod.cdni.callofduty.com")
        )
}
