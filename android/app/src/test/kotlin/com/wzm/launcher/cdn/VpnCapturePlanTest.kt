package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auditoria pura das rotas explicitamente passadas a VpnService.Builder; não simula o kernel. */
class VpnCapturePlanTest {

    @Test
    fun serviceUsesOnlyTheConfiguredVirtualIpv4Prefix() {
        assertEquals(
            listOf(VpnCapturePlan.Route("10.111.222.0", 24)),
            VpnCapturePlan.routes
        )
        assertFalse("não há rota default configurada", VpnCapturePlan.hasDefaultRoute)
        assertFalse("não há prefixo IPv6 explícito", VpnCapturePlan.hasExplicitIpv6Route)
    }

    @Test
    fun explicitIpv4RouteMatchesOnlyTheVirtualSubnet() {
        assertTrue(VpnCapturePlan.matchesExplicitRoute("10.111.222.1"))
        assertTrue(VpnCapturePlan.matchesExplicitRoute("10.111.222.2"))
        assertTrue(VpnCapturePlan.matchesExplicitRoute("10.111.222.255"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("10.111.223.1"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("127.0.0.1"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("23.46.216.80"))
    }

    @Test
    fun noExplicitIpv6RouteMatchesLoopbackOrMappedTunnelAddress() {
        assertFalse(VpnCapturePlan.matchesExplicitRoute("::1"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("2001:db8::1"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("::ffff:10.111.222.1"))
    }

    @Test
    fun hostnamesAndMalformedLiteralsAreNotResolvedOrMatched() {
        assertFalse(VpnCapturePlan.matchesExplicitRoute("prod.cdni.callofduty.com"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("10.111.222.256"))
        assertFalse(VpnCapturePlan.matchesExplicitRoute("10.111.222"))
    }
}
