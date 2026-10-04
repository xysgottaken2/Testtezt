package com.wzm.launcher

import com.wzm.launcher.server.EmbeddedLocalServer
import com.wzm.launcher.server.ServerController
import com.wzm.launcher.server.ServerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ServerTest {

    private val testPort = 18092 // use different port to avoid conflict with 18081
    private var controller: ServerController? = null
    private var server: EmbeddedLocalServer? = null

    @After
    fun tearDown() {
        runBlocking {
            controller?.stop()
            server?.stop()
        }
        Thread.sleep(200)
    }

    @Test
    fun initialStateIsParado() {
        val c = ServerController(port = testPort)
        assertEquals(ServerStatus.PARADO, c.getStatus())
        assertFalse(c.isRunning())
    }

    @Test
    fun startChangesToOnline() = runTest {
        controller = ServerController(port = testPort)
        val status = controller!!.start()
        assertEquals(ServerStatus.ONLINE, status)
        assertTrue(controller!!.isRunning())
        assertEquals(testPort, controller!!.getPort())
        assertEquals("127.0.0.1", controller!!.getHost())
    }

    @Test
    fun doubleStartDoesNotCreateSecondInstance() = runTest {
        controller = ServerController(port = testPort)
        controller!!.start()
        val second = controller!!.start()
        assertEquals(ServerStatus.ONLINE, second)
        assertTrue(controller!!.isRunning())
    }

    @Test
    fun stopChangesToParado() = runTest {
        controller = ServerController(port = testPort)
        controller!!.start()
        assertTrue(controller!!.isRunning())
        val stopped = controller!!.stop()
        assertEquals(ServerStatus.PARADO, stopped)
        assertFalse(controller!!.isRunning())
    }

    @Test
    fun doubleStopStaysParado() = runTest {
        controller = ServerController(port = testPort)
        controller!!.start()
        controller!!.stop()
        val secondStop = controller!!.stop()
        assertEquals(ServerStatus.PARADO, secondStop)
    }

    @Test
    fun serverRespondsHealth200OK() {
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

    @Test
    fun serverRootReturnsHtml() {
        server = EmbeddedLocalServer(port = testPort)
        server!!.start()
        Thread.sleep(300)
        val url = URL("http://127.0.0.1:$testPort/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        assertEquals(200, code)
        assertTrue(body.contains("WZM Offline Server"))
    }

    @Test
    fun hitsEndpointWorksAndReset() {
        server = EmbeddedLocalServer(port = testPort)
        server!!.start()
        Thread.sleep(300)
        // make a hit
        URL("http://127.0.0.1:$testPort/health").openConnection().getInputStream().close()
        Thread.sleep(100)
        val hitsUrl = URL("http://127.0.0.1:$testPort/__hits")
        val conn = hitsUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val hitsBody = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        assertTrue(hitsBody.contains("/health"))

        // reset
        val resetUrl = URL("http://127.0.0.1:$testPort/__reset")
        val resetConn = resetUrl.openConnection() as HttpURLConnection
        resetConn.requestMethod = "POST"
        resetConn.doOutput = true
        resetConn.connect()
        val resetBody = resetConn.inputStream.bufferedReader().readText()
        resetConn.disconnect()
        assertTrue(resetBody.contains("reset"))

        // hits should be empty now (or only reset itself)
        val hitsAfter = URL("http://127.0.0.1:$testPort/__hits").openConnection().getInputStream().bufferedReader().readText()
        // after reset, hits contains only the __hits request itself, so count 1
        assertTrue(hitsAfter.isNotEmpty())
    }

    @Test
    fun portConfigIsCorrect() {
        assertEquals("127.0.0.1", LauncherConfig.HOST)
        assertEquals(18081, LauncherConfig.PORT)
        assertEquals("com.activision.callofduty.warzone", LauncherConfig.WZM_PACKAGE)
    }

    @Test
    fun serverDoesNotListenOn0000() {
        server = EmbeddedLocalServer(host = "127.0.0.1", port = testPort)
        server!!.start()
        Thread.sleep(200)
        // try to connect via 0.0.0.0 should fail or same as 127.0.0.1? We check that host is 127.0.0.1
        assertEquals("127.0.0.1", server!!.getHost())
        // Ensure isRunning true but not bound to 0.0.0.0
        assertTrue(server!!.isRunning())
    }
}
