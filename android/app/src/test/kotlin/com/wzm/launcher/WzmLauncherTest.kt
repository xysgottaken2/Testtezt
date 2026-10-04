package com.wzm.launcher

import org.junit.Assert.*
import org.junit.Test

// Unit test for LauncherConfig constants — não requer mock de Context (evita flakiness em JVM sem Robolectric)
// Testes com mock de PackageManager ficam em androidTest (instrumented) onde Context real está disponível
class WzmLauncherConfigTest {
    @Test
    fun packageNameIsCorrect() {
        assertEquals("com.activision.callofduty.warzone", LauncherConfig.WZM_PACKAGE)
        assertEquals("127.0.0.1", LauncherConfig.HOST)
        assertEquals(18081, LauncherConfig.PORT)
    }

    @Test
    fun wzmPackageIsActivisionWarzone() {
        // garante que não houve regressão para pacote DbD ou outro
        assertTrue(LauncherConfig.WZM_PACKAGE.startsWith("com.activision"))
        assertTrue(LauncherConfig.WZM_PACKAGE.contains("warzone"))
    }

    @Test
    fun hostIsLoopbackOnly() {
        // Launcher deve só bindar em 127.0.0.1, nunca 0.0.0.0
        assertEquals("127.0.0.1", LauncherConfig.HOST)
        assertNotEquals("0.0.0.0", LauncherConfig.HOST)
    }
}
