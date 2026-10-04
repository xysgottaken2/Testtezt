package com.wzm.launcher.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3.4: o quadro de evidências é o que impede o log de virar opinião. Cada afirmação sai com
 * VERIFIED/PROBABLE/HYPOTHESIS/UNKNOWN e o motivo — inclusive as negativas ("não observado").
 */
class HypothesisBoardTest {

    private fun facts(
        perAppApplied: Boolean = true,
        readable: Boolean = true,
        mode: String? = "off",
        specifier: String? = null,
        tunnelListenerBound: Boolean = false,
        tunnelAddressAssigned: Boolean = true
    ) = DiagFacts(
        perAppApplied = perAppApplied,
        perAppError = if (perAppApplied) null else "sem pacote alvo",
        targetPackage = "com.activision.callofduty.warzone",
        targetUid = 10692,
        privateDnsReadable = readable,
        privateDnsMode = mode,
        privateDnsSpecifier = specifier,
        tunnelAddressAssigned = tunnelAddressAssigned,
        tunnelListenerBound = tunnelListenerBound,
        routerPhase = RouterPhase.LOCAL_SERVER_READY.label
    )

    private fun claim(counters: RequestCounters, id: String, facts: DiagFacts = facts()): EvidenceClaim =
        HypothesisBoard.claims(counters, facts).first { it.id == id }

    @Test
    fun perAppAndListenerClaimsAreVerifiedFromFacts() {
        assertEquals(Evidence.VERIFIED, claim(RequestCounters(), "app_alvo_na_vpn_per_app").level)
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "app_alvo_na_vpn_per_app", facts(perAppApplied = false)).level)

        assertEquals(
            Evidence.PROBABLE,
            claim(RequestCounters(), "listener_no_endereco_do_tunel").level
        )
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(), "listener_no_endereco_do_tunel", facts(tunnelListenerBound = true)).level
        )
    }

    @Test
    fun trafficFromTargetIsOnlyProbableWithoutOwnerEvidence() {
        val packetsOnly = RequestCounters(tunPacketsTotal = 12)
        val claim = claim(packetsOnly, "trafego_do_app_alvo_no_tun")
        assertEquals(Evidence.PROBABLE, claim.level)
        assertTrue("deve explicar que per-app não é prova de autoria", claim.detail.contains("não é prova de autoria"))

        val withOwner = RequestCounters(tunPacketsTotal = 12, tunUidVerifiedFlows = 1)
        assertEquals(Evidence.VERIFIED, claim(withOwner, "trafego_do_app_alvo_no_tun").level)
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "trafego_do_app_alvo_no_tun").level)
    }

    @Test
    fun dnsAndCdnClaimsFollowTheCounters() {
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "consulta_dns_de_host_cdni").level)
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(dnsIntercepted = 2, dnsQueries = 5), "consulta_dns_de_host_cdni").level
        )
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(tunUdpDnsNoVirtualDns = 3), "dns_do_app_no_dns_virtual").level
        )
        assertEquals(
            Evidence.UNKNOWN,
            claim(RequestCounters(tunUdpDns53 = 3), "dns_do_app_no_dns_virtual").level
        )
    }

    @Test
    fun ipv6AndCdnTcpClaimsAreExplicit() {
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "pacotes_ipv6_no_tun").level)
        val ipv6 = claim(RequestCounters(tunIpv6Packets = 2, tunIpv6ToCdnTarget = 2), "pacotes_ipv6_no_tun")
        assertEquals(Evidence.VERIFIED, ipv6.level)
        assertTrue(ipv6.detail.contains("não atendidos por política"))

        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "tcp_para_o_alvo_cdni_443").level)
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(tunTcpSynToRedirect = 1), "tcp_para_o_alvo_cdni_443").level
        )
    }

    @Test
    fun privateDnsIsEvidenceOfSettingAndOnlyHypothesisOfEffect() {
        val configured = claim(RequestCounters(), "dns_privado_configurado_no_aparelho", facts(mode = "hostname", specifier = "dns.adguard.com"))
        assertEquals(Evidence.VERIFIED, configured.level)
        assertTrue(configured.detail.contains("dns.adguard.com"))

        assertEquals(
            Evidence.UNKNOWN,
            claim(RequestCounters(), "dns_privado_configurado_no_aparelho", facts(readable = false)).level
        )

        val effect = claim(RequestCounters(), "efeito_do_dns_privado_sobre_o_wzm")
        assertEquals("o efeito nunca pode ser afirmado como fato nesta fase", Evidence.HYPOTHESIS, effect.level)

        val off = HypothesisBoard.claims(RequestCounters(), facts(mode = "off"))
            .first { it.id == "dns_privado_configurado_no_aparelho" }
        assertTrue(off.detail.contains("off"))
    }

    @Test
    fun proprietaryResolutionStaysUnknownWithoutTrafficEvidence() {
        assertEquals(
            Evidence.UNKNOWN,
            claim(RequestCounters(dnsQueries = 2, dnsForwarded = 2), "wzm_resolve_cdni_por_mecanismo_proprio").level
        )
        assertEquals(
            Evidence.PROBABLE,
            claim(RequestCounters(tunDohCandidates = 1), "wzm_resolve_cdni_por_mecanismo_proprio").level
        )
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(tunDohCandidates = 1), "fluxo_quic_doh_no_tun").level
        )
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "fluxo_dot_no_tun").level)
    }

    @Test
    fun everyLineIsSelfDescribing() {
        val lines = HypothesisBoard.lines(RequestCounters(), facts())
        assertTrue(lines.size >= 10)
        assertTrue(lines.all { it.startsWith("evidência ") && it.contains("—") })
        assertFalse("nenhuma linha pode afirmar causa do Private DNS", lines.any { it.contains("causa: Private") })
    }
}
