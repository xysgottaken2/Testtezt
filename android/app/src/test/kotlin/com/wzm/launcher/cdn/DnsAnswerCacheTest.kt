package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.6: o cache nome→IP responde "de onde veio esse destino?" sem tocar em payload.
 * Relógio injetado para o teste ser determinístico (a expiração faz parte do comportamento).
 */
class DnsAnswerCacheTest {

    private var now = 1_000_000L
    private val cache = DnsAnswerCache(maxEntries = 4, ttlMs = 60_000, clock = { now })

    @Test
    fun matchFindsTheNameThatProducedTheAddress() {
        cache.record("prod.cdni.callofduty.com", listOf("10.111.222.1"), "resposta virtual do túnel")
        val entry = cache.match("10.111.222.1")
        assertNotNull(entry)
        assertEquals("prod.cdni.callofduty.com", entry!!.host)
        assertTrue(entry.source.contains("virtual"))
        assertNull("endereço nunca visto não casa", cache.match("23.46.216.80"))
    }

    @Test
    fun entryExpiresAfterTtl() {
        cache.record("exemplo.test", listOf("203.0.113.7"), "DNS real encaminhado pelo túnel")
        assertNotNull(cache.match("203.0.113.7"))
        now += 61_000
        assertNull("depois do TTL o casamento não vale mais", cache.match("203.0.113.7"))
        assertTrue(cache.active().isEmpty())
    }

    @Test
    fun newestEntryWinsWhenTheSameAddressIsSeenAgain() {
        cache.record("antigo.test", listOf("198.51.100.9"), "origem A")
        now += 1_000
        cache.record("novo.test", listOf("198.51.100.9"), "origem B")
        val entry = cache.match("198.51.100.9")
        assertEquals("novo.test", entry!!.host)
        assertEquals("origem B", entry.source)
    }

    @Test
    fun cacheIsBoundedAndForgetsTheOldest() {
        for (index in 1..6) {
            now += 1
            cache.record("host$index.test", listOf("203.0.113.$index"), "origem")
        }
        assertNull("o mais antigo saiu do cache limitado", cache.match("203.0.113.1"))
        assertNotNull(cache.match("203.0.113.6"))
        assertTrue(cache.active(limit = 10).size <= 4)
    }

    @Test
    fun emptyAddressesAndDuplicatesAreIgnored() {
        cache.record("x.test", listOf("", "  "), "origem")
        assertNull(cache.match(""))
        cache.record("y.test", listOf("203.0.113.50", "203.0.113.50"), "origem")
        assertEquals(1, cache.active(limit = 10).count { it.address == "203.0.113.50" })
        cache.clear()
        assertTrue(cache.active().isEmpty())
    }
}
