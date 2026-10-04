package com.wzm.launcher.cdn

/**
 * Configuração central do roteamento CDNI local (M3).
 *
 * Estratégia (sem root): VpnService com DNS virtual → intercepta consultas DNS do WZM
 * e responde com [REDIRECT_TO] para os hosts comprovados em M2/M2.2; o cliente então
 * conecta no servidor HTTPS local (127.0.0.1:443) que serve os endpoints de bootstrap
 * implementados e registra tudo que não é conhecido.
 *
 * Nenhum valor aqui é "inventado": hosts e paths vêm de M2/M2.2 (VERIFIED).
 */
object CdnRouterConfig {

    // ---- Túnel VPN (pertence somente ao app com allowedApplication) ----
    const val SESSION_NAME = "WZM Offline CDNI Router"
    const val MTU = 1400
    const val VPN_ADDRESS = "10.111.222.1"
    const val VPN_PREFIX = 32
    /** DNS virtual: NÃO pode ser loopback/any (VpnService.Builder rejeita). */
    const val VPN_DNS = "10.111.222.2"
    const val VPN_ROUTE = "10.111.222.0"
    const val VPN_ROUTE_PREFIX = 24

    // ---- Hosts interceptados (VERIFIED em M2/M2.2) ----
    /** `prod.cdni.callofduty.com` — cadeia bootstrap do WebView (M2) + manifests (M2.2). */
    const val EXACT_HOST = "prod.cdni.callofduty.com"
    /** Sufixo coberto pelo SAN `*.cdni.callofduty.com` do certificado local (1 nível). */
    const val WILDCARD_SUFFIX = "cdni.callofduty.com"
    val INTERCEPT_HOSTS = listOf(EXACT_HOST)

    /**
     * Caminho do meta do shard CDN — **VERIFIED** (M2.2 2026-10-03, reconfirmado 2026-10-04/M4.0).
     * Desde o M3.5 o servidor local devolve o **corpo real** (~320 B) deste caminho em vez de placeholder.
     */
    const val CDNI_META_PATH = "/wzm/shard_cdn/android/_manifest/cdni.meta"

    /**
     * Destino devolvido no DNS: o próprio endereço do túnel.
     *
     * Motivo (ver docs/research/m3-cdni-integration.md §3): com VPN per-app, o tráfego do WZM
     * passa pela tabela de rotas da VPN; 10.111.222.1 é um endereço LOCAL (pertence à interface
     * tun), então a tabela `local` (prioridade 0) entrega o pacote ao kernel do dispositivo —
     * onde nosso listener real em :443 o atende. Se, em algum aparelho, o pacote vier pelo túnel,
     * o loop do TUN devolve ("bounce") o pacote para a interface, garantindo a entrega local.
     */
    const val REDIRECT_TO = VPN_ADDRESS

    // ---- Servidor local ----
    /** O listener HTTPS usa SOMENTE o endereço do túnel (nunca 0.0.0.0; loopback p/ diagnóstico). */
    const val LOCAL_BIND_ADDRESS = VPN_ADDRESS
    const val LOOPBACK_ADDRESS = "127.0.0.1"
    const val LOCAL_HTTPS_PORT = 443
    /** Porta de diagnóstico alternativa (usada se 443 for negada — só para log/health). */
    const val LOCAL_FALLBACK_PORT = 18443

    // ---- Certificado local (não-Activision; gerado por nós) ----
    const val CERT_ASSET = "cdn_local.p12"
    const val CERT_PASSWORD = "wzm-offline-local"
    const val CERT_ALIAS = "cdnlocal"
    const val CA_ASSET = "cdn_local_ca.pem"
    const val CA_EXPORT_NAME = "wzm-offline-local-ca.crt"

    /**
     * Forma IPv4-mapeada do endereço do túnel (`::ffff:10.111.222.1`): é o que um cliente
     * dual-stack usaria se resolvesse o alvo em IPv6. Só serve para CLASSIFICAR pacotes no log.
     */
    const val CDN_TARGET_V6 = "::ffff:10.111.222.1"

    /**
     * IPs de CDNI já observados em M2.2 (Akamai). Uso exclusivamente **informativo** no log
     * (`IP-CDNI-CONHECIDO(M2.2)`); nenhum roteamento depende disso.
     */
    val KNOWN_CDNI_IPS = listOf("23.46.216.80")

    // ---- Ciclo de vida do listener no endereço do túnel (M3.4) ----
    /** Espera LIMITADA pelo endereço do túnel (nunca retry infinito). */
    const val VPN_ADDRESS_READY_TIMEOUT_MS = 5_000L
    const val VPN_ADDRESS_POLL_MS = 250L
    /** Tentativas LIMITADAS de bind no endereço do túnel (EADDRNOTAVAIL enquanto a interface sobe). */
    const val TUNNEL_BIND_ATTEMPTS = 8
    const val TUNNEL_BIND_RETRY_DELAY_MS = 350L

    // ---- DNS ----
    const val DNS_PORT = 53
    /** DNS over TLS — só para IDENTIFICAR o fluxo no log (nunca descriptografar). */
    const val DOT_PORT = 853
    const val DNS_TTL_SECONDS = 30
    const val DNS_UPSTREAM_PRIMARY = "1.1.1.1"
    const val DNS_UPSTREAM_FALLBACK = "8.8.8.8"
    const val DNS_UPSTREAM_TIMEOUT_MS = 2500
}
