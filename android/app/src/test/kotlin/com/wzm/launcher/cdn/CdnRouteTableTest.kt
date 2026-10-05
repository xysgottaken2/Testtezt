package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class CdnRouteTableTest {

    @Before
    fun clearLog() {
        RequestLog.clear()
    }

    private fun headOf(raw: String): HttpRequestHead =
        HttpHeadReader.parse(raw)!!.head

    private fun rawRequest(target: String, host: String = "prod.cdni.callofduty.com"): String =
        "GET $target HTTP/1.1\r\nHost: $host\r\nUser-Agent: test\r\n\r\n"

    @Test
    fun readsHeadFromStream() {
        val stream = ByteArrayInputStream(rawRequest("/manifest/build-selector-103.js").toByteArray())
        val result = HttpHeadReader.read(stream)
        assertNotNull(result)
        val head = result!!.head
        assertEquals("GET", head.method)
        assertEquals("/manifest/build-selector-103.js", head.path)
        assertEquals("prod.cdni.callofduty.com", head.host)
    }

    @Test
    fun readsHeadWithQueryString() {
        val head = headOf(rawRequest("/manifest/manifest.json?v=15&lang=pt"))
        assertEquals("/manifest/manifest.json", head.path)
        assertEquals("v=15&lang=pt", head.query)
    }

    @Test
    fun normalizesAbsoluteFormTarget() {
        val head = headOf("GET https://prod.cdni.callofduty.com/static/web/index.html HTTP/1.1\r\nHost: prod.cdni.callofduty.com\r\n\r\n")
        assertEquals("/static/web/index.html", head.path)
    }

    @Test
    fun unknownHttpStatusIsNotLabeledAsSuccess() {
        assertEquals("Unknown", HttpResponses.reason(599))
        assertTrue(
            String(HttpResponses.build(599, "text/plain", byteArrayOf()), Charsets.ISO_8859_1)
                .startsWith("HTTP/1.1 599 Unknown\r\n")
        )
    }

    @Test
    fun servesVerifiedBootstrapEndpointWithMarker() {
        val outcome = CdnRouteTable.respond(headOf(rawRequest("/manifest/build-selector-103.js")))
        assertEquals(200, outcome.status)
        assertEquals(Confidence.VERIFIED, outcome.confidence)
        val text = String(outcome.bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("HTTP/1.1 200 OK"))
        assertTrue(text.contains("X-WZM-Offline: VERIFIED"))
        assertTrue("corpo deve ser marcado como local", text.contains(PLACEHOLDER_MARKER))
        assertTrue("não deve inventar manifest", !text.contains("\"builds\""))
    }

    @Test
    fun servesBootAndShardManifestEndpoints() {
        for (path in listOf(
            "/manifest/build-selector-102.js",
            "/manifest/manifest.json",
            "/static/web/index.html",
            "/prelogin/boot-15.0.0/web/manifest.json",
            "/prelogin/boot-15.0.0/web/static/js/main.js",
            "/prelogin/boot-15.0.0/web/static/css/main.css",
            "/wzm/shard_cdn/android/_manifest/cdni.meta",
            "/wzm/shard_cdn/ios/_manifest/cdni.meta"
        )) {
            val outcome = CdnRouteTable.respond(headOf(rawRequest(path)))
            assertEquals("path=$path", 200, outcome.status)
            assertEquals("path=$path", Confidence.VERIFIED, outcome.confidence)
        }
    }

    @Test
    fun hypothesisEndpointIsServedButMarked() {
        val outcome = CdnRouteTable.respond(headOf(rawRequest("/manifest/popup.js")))
        assertEquals(200, outcome.status)
        assertEquals(Confidence.HYPOTHESIS, outcome.confidence)
        assertTrue(String(outcome.bytes, Charsets.ISO_8859_1).contains("HYPOTHESIS"))
    }

    @Test
    fun unknownEndpointReturnsControlled404AndLogsExactPath() {
        val target = "/_svc/unknown-endpoint-v9.js?build=15"
        val outcome = CdnRouteTable.respond(headOf(rawRequest(target)))
        assertEquals(404, outcome.status)
        assertFalse(outcome.isKnown)
        val text = String(outcome.bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("HTTP/1.1 404 Not Found"))
        assertTrue(text.contains("unknown_cdni_endpoint"))
        assertTrue("o path exato precisa aparecer no corpo", text.contains("/_svc/unknown-endpoint-v9.js"))
        assertTrue("a query exata precisa aparecer", text.contains("build=15"))
        assertTrue("nada pode ser inventado", !text.contains("manifest.json\":{\"builds\""))

        RequestLog.incHttpRequest()
        RequestLog.incUnknownRequest()
        RequestLog.add(outcome.logTag, outcome.logMessage)
        val snapshot = RequestLog.snapshot()
        assertTrue("log precisa registrar a URL exata", snapshot.contains(target))
        assertEquals(1, RequestLog.counters.value.unknownRequests)
    }

    @Test
    fun healthEndpointListsVerifiedEndpoints() {
        val outcome = CdnRouteTable.respond(headOf(rawRequest(CdnRouteTable.HEALTH_PATH, host = "10.111.222.1")))
        assertEquals(200, outcome.status)
        val text = String(outcome.bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("local-cdn-router"))
        assertTrue(text.contains("/manifest/build-selector-103.js"))
        assertTrue(text.contains("VERIFIED"))
    }

    @Test
    fun everyVerifiedEndpointCarriesItsEvidenceNote() {
        for (endpoint in BootstrapEndpoints.all()) {
            assertTrue("nota de evidência ausente em ${endpoint.path}", endpoint.note.length > 10)
            assertTrue("path normalizado difere em ${endpoint.path}", endpoint.path.startsWith("/"))
            val body = String(endpoint.body(), Charsets.UTF_8)
            if (endpoint.realUpstreamBody) {
                assertTrue(
                    "corpo real de ${endpoint.path} precisa manter a assinatura observada",
                    CdniMetaBody.looksLikeRealBody(body)
                )
                assertFalse(
                    "corpo real de ${endpoint.path} não pode virar placeholder",
                    body.contains(PLACEHOLDER_MARKER)
                )
            } else {
                assertTrue("corpo sem marcador em ${endpoint.path}", body.contains(PLACEHOLDER_MARKER))
            }
        }
    }
}
