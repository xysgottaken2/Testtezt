package com.wzm.launcher.cdn

/** Motivo classificado de uma falha de handshake TLS observada pelo servidor local. */
data class TlsFailure(
    val code: String,
    val hint: String
) {
    companion object {
        /** O cliente recusou a cadeia de certificados (CA não confiável para o app). */
        const val CLIENT_REJECTED_CERTIFICATE = "CLIENTE_RECUSOU_CERTIFICADO"

        /** Cliente falou HTTP em claro na porta TLS. */
        const val CLIENT_CLEARTEXT = "CLIENTE_FALOU_HTTP_EM_CLARO"

        /** Nome/hostname do certificado não bate com o host pedido. */
        const val HOSTNAME_MISMATCH = "HOSTNAME_DIVERGENTE"

        /** Certificado expirado/fora da validade. */
        const val CERTIFICATE_EXPIRED = "CERTIFICADO_EXPIRADO"

        /** Sem cifras/protocolo TLS em comum. */
        const val NO_COMMON_CIPHER = "SEM_CIFRA_COMUM"

        /** Cliente encerrou a conexão no meio do handshake. */
        const val PEER_CLOSED = "CLIENTE_ENCERROU"

        /**
         * O servidor não conseguiu abrir/descriptografar um registro TLS do cliente. **Não** é recusa de
         * certificado, e não é aceitação: é a camada de registro. Observado no device (2026-10-08:
         * `BAD_PACKET_LENGTH` + `DECRYPTION_FAILED_OR_BAD_RECORD_MAC` em 4 de 12 falhas) — antes disso
         * essas falhas caíam em [UNKNOWN] e corriam o risco de ser lidas como mais uma recusa de cadeia.
         */
        const val RECORD_LAYER_INTEGRITY = "FALHA_REGISTRO_TLS"

        const val UNKNOWN = "DESCONHECIDO"
    }
}

/**
 * Classifica a mensagem de falha de handshake TLS em um código estável ([TlsFailure.code]) + dica
 * legível. O código vai para o log do launcher (`motivo=<CÓDIGO>`), permitindo distinguir
 * "cliente recusou o certificado" de "cliente falou HTTP em claro", etc.
 *
 * Origem dos padrões: evidência real do device (S23 Ultra, 2026-10-04) — o cliente WZM devolveu
 * `SSLV3_ALERT_CERTIFICATE_UNKNOWN` ao receber o certificado local — mais as mensagens usuais de
 * Conscrypt/OkHttp/Java (`Received fatal alert: ...`, `PKIX path building failed`, ...).
 *
 * Limite honesto: o alerta TLS visto pelo *servidor* não permite distinguir "CA não confiável"
 * de "pinning rejeitou" — os dois chegam como `certificate_unknown`/`bad_certificate`.
 *
 * E o complementar: uma falha da **camada de registro** ([TlsFailure.RECORD_LAYER_INTEGRITY]) também não
 * permite concluir nada sobre confiança — por isso tem código próprio e nunca é dobrada em
 * [TlsFailure.CLIENT_REJECTED_CERTIFICATE].
 * Ver docs/research/m3.2-apk-tls-trust-investigation.md.
 */
object TlsTrust {

    fun analyze(rawMessage: String?): TlsFailure {
        val message = (rawMessage ?: "").lowercase()

        fun containsAny(vararg needles: String) = needles.any { message.contains(it) }

        return when {
            // Ordem importa: validade/hostname antes do bloco genérico de "certificate"
            containsAny("expired", "certificate_expired", "notvalid", "not valid", "out of date") ->
                TlsFailure(
                    TlsFailure.CERTIFICATE_EXPIRED,
                    "certificado fora da validade — rodar scripts/generate-local-cdni-cert.sh --force"
                )

            // Cliente falou HTTP (ou algo que não é TLS) na porta 443
            containsAny(
                "unrecognized ssl message",
                "not an ssl/tls record",
                "first record does not look like a tls",
                "plaintext",
                "http request"
            ) -> TlsFailure(
                TlsFailure.CLIENT_CLEARTEXT,
                "o cliente enviou HTTP em claro nesta porta TLS — provavelmente não é o boot do WZM"
            )

            // Cliente recusou o certificado (cadeia não confiável / pinning / hostname / validade)
            containsAny(
                "sslv3_alert_certificate_unknown",
                "certificate_unknown",
                "certificate unknown",
                "certificateunknown",
                "bad_certificate",
                "bad certificate",
                "unknown_ca",
                "pkix",
                "certpathvalidator",
                "unable to find valid certification path",
                "no trusted certificate",
                "trust anchor",
                "trustanchor",
                "certificate verify failed",
                "certificate_verify_failed",
                "certificateexception"
            ) -> {
                val hint = when {
                    containsAny("trust anchor", "trustanchor", "unable to find valid certification path", "pkix") ->
                        "o cliente não achou uma cadeia válida até uma CA confiável — é exatamente o bloqueio " +
                            "documentado (a CA local não é confiável para o WZM). A requisição CHEGOU ao servidor."
                    containsAny("pin", "pinning") ->
                        "falha compatível com PINNING de certificado — verificar a análise do APK " +
                            "(docs/research/m3.2-apk-tls-trust-investigation.md)"
                    else ->
                        "o cliente RECUSOU o certificado local (CA não confiável para o app, ou pinning). " +
                            "O ponto importante: a requisição CHEGOU ao servidor local."
                }
                TlsFailure(TlsFailure.CLIENT_REJECTED_CERTIFICATE, hint)
            }

            // Camada de registro (M8): inserido depois do bloco de certificado de propósito — quando uma
            // mensagem contém os dois sinais, o alerta de certificado é a evidência interpretável; aqui, não.
            containsAny(
                "bad_record_mac",
                "bad record mac",
                "record_layer_failure",
                "bad_packet_length",
                "decryption_failed",
                "ssl_aead_ctx",
                "decrypt_error"
            ) -> TlsFailure(
                TlsFailure.RECORD_LAYER_INTEGRITY,
                "o servidor não conseguiu abrir o registro TLS recebido (BAD_PACKET_LENGTH / " +
                    "DECRYPTION_FAILED_OR_BAD_RECORD_MAC). Isto NÃO é evidência de que o cliente recusou o " +
                    "certificado, nem de que aceitou: as causas candidatas (cliente abortou antes da troca " +
                    "de chaves, registro truncado/corrompido no caminho, estado de cifra divergente entre " +
                    "as pontas) não são distinguíveis com o que este log tem — ver " +
                    "docs/research/m7-cdni-meta-experimento-local.md"
            )

            containsAny("hostname", "subject alternative", "no name matching", "not match") ->
                TlsFailure(
                    TlsFailure.HOSTNAME_MISMATCH,
                    "o nome do certificado não bate com o host pedido — conferir se o SNI do cliente é um host " +
                        "coberto pelos SANs do certificado local"
                )

            containsAny(
                "no cipher suites in common",
                "handshake_failure",
                "no appropriate protocol",
                "no protocols available",
                "insufficient security"
            ) -> TlsFailure(
                TlsFailure.NO_COMMON_CIPHER,
                "cliente e servidor não acordaram cifra/protocolo TLS em comum"
            )

            containsAny(
                "connection closed",
                "closed during handshake",
                "remote host terminated",
                "eof",
                "reset by peer",
                "socket is closed",
                "broken pipe"
            ) -> TlsFailure(
                TlsFailure.PEER_CLOSED,
                "o cliente encerrou a conexão durante o handshake (pode ser um cliente que valida e desiste " +
                    "sem enviar alerta)"
            )

            else -> TlsFailure(TlsFailure.UNKNOWN, "sem classificação conhecida — ver a mensagem original no log")
        }
    }

    /** Atalho: a falha é do tipo "cliente recusou o certificado" (o cenário do device)? */
    fun isClientTrustFailure(message: String?): Boolean =
        analyze(message).code == TlsFailure.CLIENT_REJECTED_CERTIFICATE
}
