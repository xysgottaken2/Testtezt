package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.4: o ciclo de vida precisa ser explícito e **não** declarar "pronto" quando o listener no
 * endereço do túnel não subiu (foi exatamente isso que aconteceu na evidência de 2026-10-04:
 * o bind em 10.111.222.1:443 falhou com EADDRNOTAVAIL e só o loopback atendeu).
 */
class RouterLifecycleTest {

    private fun lifecycle(log: MutableList<String> = mutableListOf()): Pair<RouterLifecycle, MutableList<String>> {
        val target = RouterLifecycle { line -> log += line }
        return target to log
    }

    @Test
    fun happyPathReachesRouterReadyOnlyWithTunnelListener() {
        val (lifecycle, log) = lifecycle()
        assertTrue(lifecycle.onVpnStarting())
        assertEquals(RouterPhase.VPN_STARTING, lifecycle.phase)
        assertTrue(lifecycle.onVpnReady(tunnelAddressAssigned = true, reason = "espera limitada de 300 ms"))
        assertEquals(RouterPhase.VPN_READY, lifecycle.phase)
        assertTrue(lifecycle.onLocalServerStarting())
        assertTrue(lifecycle.onLocalServerReady(tunnelListenerBound = true, endpoints = "10.111.222.1:443, 127.0.0.1:443"))
        assertTrue(lifecycle.onRouterReady())
        assertEquals(RouterPhase.ROUTER_READY, lifecycle.phase)
        assertTrue(lifecycle.describe().contains("listener no endereço do túnel"))
        assertTrue(log.any { it.contains("LOCAL_SERVER_READY -> ROUTER_READY") })
    }

    @Test
    fun missingTunnelListenerBlocksRouterReadyAndRegistersLimitation() {
        val (lifecycle, log) = lifecycle()
        lifecycle.onVpnStarting()
        lifecycle.onVpnReady(tunnelAddressAssigned = true)
        lifecycle.onLocalServerStarting()
        assertTrue(lifecycle.onLocalServerReady(tunnelListenerBound = false, endpoints = "127.0.0.1:443"))
        assertEquals(RouterPhase.LOCAL_SERVER_READY, lifecycle.phase)

        assertFalse("sem listener no túnel não existe ROUTER_READY", lifecycle.onRouterReady())
        assertEquals(RouterPhase.LOCAL_SERVER_READY, lifecycle.phase)
        val reason = lifecycle.tunnelListenerMissingReason
        assertNotNull(reason)
        assertTrue(reason!!.contains("127.0.0.1:443"))
        assertTrue("limitação precisa estar registrada no log", log.any { it.contains("LIMITAÇÃO") || it.contains("LISTENER_DO_TUNEL_AUSENTE") })
        assertTrue(lifecycle.describe().contains("somente loopback"))
    }

    @Test
    fun addressNotAssignedInTimeIsRecordedInTheState() {
        val (lifecycle, log) = lifecycle()
        lifecycle.onVpnStarting()
        assertTrue(lifecycle.onVpnReady(tunnelAddressAssigned = false))
        assertFalse(lifecycle.tunnelAddressAssigned)
        assertEquals(RouterPhase.VPN_READY, lifecycle.phase)
        assertTrue(log.any { it.contains("EADDRNOTAVAIL") })
    }

    @Test
    fun invalidTransitionsAreRejectedWithoutChangingPhase() {
        val (lifecycle, log) = lifecycle()
        assertFalse("não se declara servidor pronto antes da VPN", lifecycle.onLocalServerReady(true, "x"))
        assertFalse(lifecycle.onRouterReady())
        assertEquals(RouterPhase.PARADO, lifecycle.phase)
        assertTrue(log.any { it.contains("transição recusada") })

        lifecycle.onVpnStarting()
        assertFalse("não se declara ROUTER_READY a partir de VPN_STARTING", lifecycle.onRouterReady())
        assertEquals(RouterPhase.VPN_STARTING, lifecycle.phase)
    }

    @Test
    fun errorAndStopResetTheFlags() {
        val (lifecycle, _) = lifecycle()
        lifecycle.onVpnStarting()
        lifecycle.onVpnReady(tunnelAddressAssigned = true)
        assertTrue(lifecycle.onError("establish() falhou"))
        assertEquals(RouterPhase.ERRO, lifecycle.phase)
        assertTrue(lifecycle.onStopped("parada solicitada pelo launcher"))
        assertEquals(RouterPhase.PARADO, lifecycle.phase)
        assertNull(lifecycle.tunnelListenerMissingReason)
        assertFalse(lifecycle.tunnelListenerBound)
        assertTrue(lifecycle.history().isNotEmpty())
    }
}
