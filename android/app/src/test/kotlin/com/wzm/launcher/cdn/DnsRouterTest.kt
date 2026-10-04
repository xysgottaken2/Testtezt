package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsRouterTest {

    private val responder = DnsResponder()

    private fun query(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
        out.write(byteArrayOf(0x01, 0x00)) // RD
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf((type shr 8).toByte(), (type and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // IN
        return out.toByteArray()
    }

    @Test
    fun parsesQuestion() {
        val raw = query("prod.cdni.callofduty.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuery(raw, raw.size)
        assertNotNull(question)
        assertEquals("prod.cdni.callofduty.com", question!!.name)
        assertEquals(DnsMessage.TYPE_A, question.qType)
        assertEquals(DnsMessage.CLASS_IN, question.qClass)
    }

    @Test
    fun interceptsOnlyVerifiedCdnHosts() {
        assertTrue(responder.isIntercepted("prod.cdni.callofduty.com"))
        assertTrue(responder.isIntercepted("PROD.CDNI.CALLOFDUTY.COM"))
        assertTrue(responder.isIntercepted("cdn.cdni.callofduty.com"))
        assertFalse(responder.isIntercepted("a.b.cdni.callofduty.com"))
        assertFalse(responder.isIntercepted("callofduty.com"))
        assertFalse(responder.isIntercepted("prod.cdni.callofduty.com.evil.example"))
        assertFalse(responder.isIntercepted("example.com"))
    }

    @Test
    fun answersInterceptedHostWithTunnelAddress() {
        val request = query("prod.cdni.callofduty.com", DnsMessage.TYPE_A)
        val answer = responder.answer(request, request.size)
        assertNotNull(answer)
        assertEquals(0x1234, ((answer!![0].toInt() and 0xFF) shl 8) or (answer[1].toInt() and 0xFF))
        assertTrue("QR deve estar setado", (answer[2].toInt() and 0x80) != 0)
        assertTrue("RCODE deve ser 0 (NOERROR)", (answer[3].toInt() and 0x0F) == 0)
        assertEquals("ANCOUNT", 1, DnsMessage.u16(answer, 6))
        val ip = CdnRouterConfig.REDIRECT_TO.split('.').map { it.toInt() }
        val tail = answer.takeLast(4).map { it.toInt() and 0xFF }
        assertEquals(ip, tail)
    }

    @Test
    fun returnsNoAnswersForIpv6ToForceIpv4() {
        val request = query("prod.cdni.callofduty.com", DnsMessage.TYPE_AAAA)
        val answer = responder.answer(request, request.size)
        assertNotNull(answer)
        assertEquals(0, DnsMessage.u16(answer!!, 6))
        assertTrue((answer[3].toInt() and 0x0F) == 0)
    }

    @Test
    fun doesNotAnswerOtherDomains() {
        val other = query("example.com", DnsMessage.TYPE_A)
        assertNull(responder.answer(other, other.size))
        val deep = query("a.b.cdni.callofduty.com", DnsMessage.TYPE_A)
        assertNull(responder.answer(deep, deep.size))
    }
}
