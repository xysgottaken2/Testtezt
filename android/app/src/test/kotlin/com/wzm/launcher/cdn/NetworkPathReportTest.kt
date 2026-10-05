package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5.0: o diagnóstico que responde "em qual rede o WZM está resolvendo?".
 *
 * A motivação é o log do dispositivo: `DNS Requested by 8952, 10692(...warzone), 7(NODATA)`.
 * Precisamos imprimir o netId das redes (inclusive o da nossa VPN) para comparar com esse
 * número em vez de supor que a consulta passa pelo túnel.
 */
class NetworkPathReportTest {

    private fun facts(
        netId: Int?,
        transports: List<String>,
        isDefault: Boolean = false,
        dns: List<String> = emptyList()
    ) = NetworkPathReport.NetworkFacts(netId, transports, isDefault, dns)

    @Test
    fun parseNetIdAcceptsTheDecimalFormUsedByAndroidNetworkToString() {
        assertEquals(8952, NetworkPathReport.parseNetId("8952"))
        assertEquals(100, NetworkPathReport.parseNetId(" 100 "))
        assertNull("formato desconhecido não é adivinhado", NetworkPathReport.parseNetId("Network{42}"))
        assertNull(NetworkPathReport.parseNetId(null))
    }

    @Test
    fun vpnFlagComesFromTheTransportList() {
        assertTrue(facts(10, listOf("VPN", "WIFI")).isVpn)
        assertFalse(facts(10, listOf("WIFI")).isVpn)
    }

    @Test
    fun renderPutsOurVpnNetIdOnItsOwnComparableLine() {
        val lines = NetworkPathReport.render(
            facts = listOf(
                facts(8952, listOf("WIFI"), isDefault = true, dns = listOf("192.168.0.1")),
                facts(8960, listOf("VPN"), isDefault = false, dns = listOf("10.111.222.2"))
            ),
            alwaysOn = null,
            ownPackage = "com.wzm.launcher"
        )
        val vpnLine = lines.first { it.contains("netId da nossa VPN") }
        assertTrue(vpnLine.contains("8960"))
        assertTrue(
            "a linha deve dizer com o que comparar",
            vpnLine.contains("DNS Requested by")
        )
        assertTrue(lines.any { it.contains("netId=8952") && it.contains("default=true") })
        assertTrue(lines.any { it.contains("[VPN]") })
    }

    @Test
    fun renderReportsWhenOurVpnIsNotVisible() {
        val lines = NetworkPathReport.render(
            facts = listOf(facts(8952, listOf("WIFI"), isDefault = true)),
            alwaysOn = null,
            ownPackage = "com.wzm.launcher"
        )
        assertTrue(lines.any { it.contains("NAO_RESOLVIDO") })
    }

    @Test
    fun renderStatesWhetherAlwaysOnVpnPointsAtUs() {
        val ours = NetworkPathReport.render(
            facts = listOf(facts(8960, listOf("VPN"))),
            alwaysOn = NetworkPathReport.AlwaysOnState("com.wzm.launcher", true, null),
            ownPackage = "com.wzm.launcher"
        ).first { it.startsWith("always-on VPN") }
        assertTrue(ours.contains("nosso=true"))
        assertTrue(ours.contains("lockdown=true"))

        val other = NetworkPathReport.render(
            facts = listOf(facts(8960, listOf("VPN"))),
            alwaysOn = NetworkPathReport.AlwaysOnState("com.outra.vpn", false, null),
            ownPackage = "com.wzm.launcher"
        ).first { it.startsWith("always-on VPN") }
        assertTrue(other.contains("nosso=false"))
    }

    @Test
    fun renderRecommendsAlwaysOnWhenItIsOff() {
        val lines = NetworkPathReport.render(
            facts = listOf(facts(8960, listOf("VPN"))),
            alwaysOn = NetworkPathReport.AlwaysOnState(null, false, null),
            ownPackage = "com.wzm.launcher"
        )
        assertTrue(lines.any { it.contains("always-on VPN: desligado") })
    }

    @Test
    fun renderNeverInventsNetworks() {
        val lines = NetworkPathReport.render(emptyList(), null, "com.wzm.launcher")
        assertEquals(1, lines.size)
        assertTrue(lines.first().contains("INDISPONIVEL"))
    }
}
