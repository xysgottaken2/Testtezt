package com.wzm.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wzm.launcher.server.EmbeddedLocalServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class HealthEndpointTest {

    private val testPort = 18093
    private var server: EmbeddedLocalServer? = null

    @After
    fun tearDown() {
        server?.stop()
        Thread.sleep(200)
    }

    @Test
    fun healthReturns200OK() {
        server = EmbeddedLocalServer(port = testPort)
        server!!.start()
        Thread.sleep(300)
        assertTrue(server!!.isRunning())
        val url = URL("http://127.0.0.1:$testPort/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        assertEquals(200, code)
        assertEquals("OK", body.trim())
    }
}
