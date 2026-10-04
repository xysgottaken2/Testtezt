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

    private fun baseFacts(
        perAppApplied: Boolean = true,
        readable: Boolean = true,
        mode: String? = "off",
        specifier: String? = null,
        tunnelListenerBound: Boolean = false,
        tunnelAddressAssigned: Boolean = true,
        uidTrafficAvailable: Boolean = false,
        uidTrafficBytesSinceStart: Long? = null,
        uidTrafficGrew: Boolean? = null,
        ipv6Profiles: List<String> = emptyList(),
        targetDeclaredProcesses: List<String> = listOf("com.activision.callofduty.warzone"),
        uidTrafficOutsideTunnel: Boolean = false
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
        routerPhase = RouterPhase.LOCAL_SERVER_READY.label,
        uidTrafficAvailable = uidTrafficAvailable,
        uidTrafficBytesSinceStart = uidTrafficBytesSinceStart,
        uidTrafficGrew = uidTrafficGrew,
        ipv6Profiles = ipv6Profiles,
        targetDeclaredProcesses = targetDeclaredProcesses,
        uidTrafficOutsideTunnel = uidTrafficOutsideTunnel
    )

    private fun claim(counters: RequestCounters, id: String, facts: DiagFacts = baseFacts()): EvidenceClaim =
        HypothesisBoard.claims(counters, facts).first { it.id == id }

    @Test
    fun perAppAndListenerClaimsAreVerifiedFromFacts() {
        assertEquals(Evidence.VERIFIED, claim(RequestCounters(), "app_alvo_na_vpn_per_app").level)
        assertEquals(Evidence.UNKNOWN, claim(RequestCounters(), "app_alvo_na_vpn_per_app", baseFacts(perAppApplied = false)).level)

        assertEquals(
            Evidence.PROBABLE,
            claim(RequestCounters(), "listener_no_endereco_do_tunel").level
        )
        assertEquals(
            Evidence.VERIFIED,
            claim(RequestCounters(), "listener_no_endereco_do_tunel", baseFacts(tunnelListenerBound = true)).level
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
    fun onlyTheTunnelListenerPromotesConnectionEvidence() {
        // Conexões de loopback (a evidência de 2026-10-04: 5 conexões sem dono) NÃO promovem nada.
        val loopbackOnly = RequestCounters(tcpConnectionsLoopback = 5, tlsFailedLoopback = 5)
        assertEquals(Evidence.UNKNOWN, claim(loopbackOnly, "conexoes_no_listener_do_tunel").level)
        assertEquals(Evidence.UNKNOWN, claim(loopbackOnly, "tls_no_listener_do_tunel").level)
        assertEquals(
            "só SYN observado no TUN promove tcp_para_o_alvo_cdni_443",
            Evidence.UNKNOWN,
            claim(loopbackOnly, "tcp_para_o_alvo_cdni_443").level
        )

        val loopbackClaim = claim(loopbackOnly, "conexoes_de_loopback_sao_diagnostico")
        assertEquals(Evidence.VERIFIED, loopbackClaim.level)
        assertTrue(loopbackClaim.detail.contains("NÃO são evidência"))
        assertFalse(
            "o detalhe do loopback não pode virar afirmação de autoria",
            loopbackClaim.detail.contains("do WZM chegou")
        )

        // Uma conexão no listener do túnel (com TLS) já promove os dois claims para VERIFIED.
        val tunnelOnly = RequestCounters(tcpConnectionsTunel = 1, tlsOkTunel = 1)
        assertEquals(Evidence.VERIFIED, claim(tunnelOnly, "conexoes_no_listener_do_tunel").level)
        assertEquals(Evidence.VERIFIED, claim(tunnelOnly, "tls_no_listener_do_tunel").level)
        assertTrue(claim(tunnelOnly, "tls_no_listener_do_tunel").detail.contains("1 aceito"))
        assertTrue(
            "o claim do listener precisa negar autoria de conexão feita pelo próprio launcher",
            claim(tunnelOnly, "conexoes_no_listener_do_tunel").detail.contains("prova o CAMINHO, NÃO o WZM")
        )

        assertEquals(
            Evidence.UNKNOWN,
            claim(RequestCounters(), "conexoes_de_loopback_sao_diagnostico").level
        )
    }

    @Test
    fun loopbackBeforeWzmStartIsReportedAsSuch() {
        val claim = claim(
            RequestCounters(tcpConnectionsLoopback = 5, loopbackAntesDoWzm = 5),
            "conexoes_de_loopback_sao_diagnostico"
        )
        assertTrue(claim.detail.contains("antes do WZM iniciado=5"))
        assertTrue(claim.detail.contains("depois=0"))
    }

    @Test
    fun uidTrafficGrowthWithoutAnythingInTunnelIsProbableTrafficOutsideIt() {
        // O cenário exato do device em 2026-10-04: TUN silencioso, mas o UID do alvo mexeu bytes.
        val facts = baseFacts(uidTrafficAvailable = true, uidTrafficBytesSinceStart = 1_500_000, uidTrafficGrew = true)
        val claim = claim(RequestCounters(), "trafego_do_app_alvo_fora_do_tunel", facts)
        assertEquals(Evidence.PROBABLE, claim.level)
        assertTrue(claim.detail, claim.detail.contains("CRESCEU"))
        assertTrue(claim.detail, claim.detail.contains("FORA do túnel"))
        assertFalse("não pode afirmar causalidade", claim.detail.contains("por causa"))
        assertTrue("precisa lembrar que a conta é por UID", claim.detail.contains("por UID"))
    }

    @Test
    fun noGrowthWithEmptyTunnelIsVerifiedThatTheAppDidNotUseTheNetwork() {
        val facts = baseFacts(uidTrafficAvailable = true, uidTrafficGrew = false)
        val claim = claim(RequestCounters(), "trafego_do_app_alvo_fora_do_tunel", facts)
        assertEquals(Evidence.VERIFIED, claim.level)
        assertTrue(claim.detail, claim.detail.contains("não fez rede"))
    }

    @Test
    fun withoutUidAccountingTheClaimStaysUnknown() {
        val claim = claim(RequestCounters(), "trafego_do_app_alvo_fora_do_tunel", baseFacts())
        assertEquals(Evidence.UNKNOWN, claim.level)
        assertTrue(claim.detail, claim.detail.contains("não é possível separar"))
    }

    @Test
    fun ipv6OnlyDiscoveryIsProbableAndNeverAttributedToTheGame() {
        val counters = RequestCounters(
            tunIpv6Packets = 10,
            tunIpv6DescobertaLocal = 10,
            tunIpv6AntesDoWzm = 3,
            tunIpv6DepoisDoWzm = 7
        )
        val claim = claim(counters, "ipv6_descartado_e_descoberta_local")
        assertEquals(Evidence.PROBABLE, claim.level)
        assertTrue(claim.detail, claim.detail.contains("descoberta local"))
        assertTrue("o antes/depois precisa aparecer", claim.detail.contains("depois=7"))
    }

    @Test
    fun ipv6UnicastRaisesTheClaimToVerified() {
        val counters = RequestCounters(
            tunIpv6Packets = 4,
            tunIpv6DescobertaLocal = 2,
            tunIpv6Unicast = 2,
            tunIpv6DepoisDoWzm = 4
        )
        val claim = claim(counters, "ipv6_descartado_e_descoberta_local")
        assertEquals(Evidence.VERIFIED, claim.level)
        assertTrue(claim.detail, claim.detail.contains("UNICAST"))
        assertTrue(claim.detail, claim.detail.contains("não é apenas descoberta local"))
    }

    @Test
    fun dnsDestinationMatchingDistinguishesResolvedFromUnresolvedDestinations() {
        val resolved = claim(RequestCounters(dnsRespostasRegistradas = 2, tunFluxosDestinoResolvido = 1),
            "dns_observado_para_os_destinos")
        assertEquals(Evidence.PROBABLE, resolved.level)
        assertTrue(resolved.detail, resolved.detail.contains("a resolução passou pelo túnel"))

        val unmatched = claim(RequestCounters(dnsRespostasRegistradas = 3), "dns_observado_para_os_destinos")
        assertTrue(unmatched.detail, unmatched.detail.contains("HYPOTHESIS"))
        assertTrue(unmatched.detail, unmatched.detail.contains("não causa"))

        val nothing = claim(RequestCounters(), "dns_observado_para_os_destinos")
        assertEquals(Evidence.UNKNOWN, nothing.level)
        assertTrue(nothing.detail, nothing.detail.contains("nenhuma resposta DNS passou pelo túnel"))
    }

    @Test
    fun declaredProcessesAreVerifiedButDoNotProveExecution() {
        val claim = claim(RequestCounters(), "processos_do_app_alvo",
            baseFacts(targetDeclaredProcesses = listOf("com.activision.callofduty.warzone", ":game")))
        assertEquals(Evidence.VERIFIED, claim.level)
        assertTrue(claim.detail, claim.detail.contains(":game"))
        assertTrue(claim.detail, claim.detail.contains("não prova execução"))
        assertTrue(claim.detail, claim.detail.contains("10692"))

        val unknown = claim(RequestCounters(), "processos_do_app_alvo", baseFacts(targetDeclaredProcesses = emptyList()))
        assertEquals(Evidence.UNKNOWN, unknown.level)
        assertTrue(unknown.detail, unknown.detail.contains("lista vazia não prova execução"))
    }

    @Test
    fun privateDnsIsEvidenceOfSettingAndOnlyHypothesisOfEffect() {
        val configured = claim(RequestCounters(), "dns_privado_configurado_no_aparelho", baseFacts(mode = "hostname", specifier = "dns.adguard.com"))
        assertEquals(Evidence.VERIFIED, configured.level)
        assertTrue(configured.detail.contains("dns.adguard.com"))

        assertEquals(
            Evidence.UNKNOWN,
            claim(RequestCounters(), "dns_privado_configurado_no_aparelho", baseFacts(readable = false)).level
        )

        val effect = claim(RequestCounters(), "efeito_do_dns_privado_sobre_o_wzm")
        assertEquals("o efeito nunca pode ser afirmado como fato nesta fase", Evidence.HYPOTHESIS, effect.level)

        val off = HypothesisBoard.claims(RequestCounters(), baseFacts(mode = "off"))
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
        val lines = HypothesisBoard.lines(RequestCounters(), baseFacts())
        assertTrue(lines.size >= 10)
        assertTrue(lines.all { it.startsWith("evidência ") && it.contains("—") })
        assertFalse("nenhuma linha pode afirmar causa do Private DNS", lines.any { it.contains("causa: Private") })
    }
}
