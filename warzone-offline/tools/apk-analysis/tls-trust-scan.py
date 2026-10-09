#!/usr/bin/env python3
"""Scanner de TLS/trust/pinning do APK 3.10.0 (evidência para o bloqueio de certificado do M3.2).

NÃO modifica, NÃO extrai para o repo e NÃO distribui o APK: lê bytes e escreve um relatório
(JSON + resumo textual) em diretório EXTERNO ao repositório.

Por que existe: a evidência do device (S23 Ultra, 2026-10-04) mostrou o WZM alcançando o listener
local e recusando o certificado (`SSLV3_ALERT_CERTIFICATE_UNKNOWN`). Para saber se existe uma
cadeia de confiança suportável de forma legítima, precisamos responder no APK:

  1. O app declara `networkSecurityConfig`? Quais *trust anchors* (system/user)? Há `pin-set`?
  2. Existe bundle de CA embutido (assets/res) ou trust store própria?
  3. Há APIs de pinning no código (okhttp CertificatePinner, TrustKit, checkServerTrusted próprio)?
  4. O que o app usa para o CDNI (okhttp/cronet/nativo/WebView) e ele fala com loopback?
  5. Há literais de `127.0.0.1`/`localhost` (hipótese levantada pela evidência: as conexões chegaram
     em 127.0.0.1:443, e não no endereço do túnel)?

Uso:
    python3 tls-trust-scan.py --apk /caminho/externo/warzone.xapk --out /tmp/tls-trust-report.json
    python3 tls-trust-scan.py --dir /caminho/externo/apktool-out --out /tmp/tls-trust-report.json
    python3 tls-trust-scan.py --self-test

Confiança das evidências (regra do projeto): VERIFIED = encontrado em arquivo texto/decodificado;
PROBABLE = encontrado como sequência de bytes em binário (dex/so/arsc) — pode ser string morta.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime, timezone

MAX_ENTRY_BYTES = 64 * 1024 * 1024
TEXT_EXTENSIONS = {".xml", ".json", ".txt", ".properties", ".js", ".html", ".pem", ".crt", ".cer", ".der", ".cfg", ".ini"}
CERT_EXTENSIONS = {".cer", ".crt", ".pem", ".der", ".p12", ".pfx", ".bks", ".jks", ".keystore"}

# Assinaturas de pinning/trust no código (nome amigável -> padrões)
PINNING_SIGNATURES = {
    "okhttp CertificatePinner": [b"CertificatePinner", b"okhttp3/CertificatePinner"],
    "pin sha256 (SPKI)": [b"pin-sha256", b"sha256/"],
    "TrustKit": [b"TrustKit", b"com/datatheorem"],
    "trustmanager customizado": [b"checkServerTrusted", b"X509TrustManager", b"TrustManagerFactory"],
    "Conscrypt": [b"Conscrypt", b"org/conscrypt"],
    "Cronet": [b"Cronet", b"org/chromium/net"],
    "BoringSSL": [b"BoringSSL", b"boringssl"],
    "mbedTLS/wolfSSL": [b"mbedtls", b"wolfssl"],
    "pinning nome explícito": [b"ssl_pinning", b"SSLPinning", b"pinning"],
    "hostname verifier": [b"HostnameVerifier", b"hostnameVerifier"],
}

TLS_STACK_SIGNATURES = {
    "okhttp": [b"okhttp3", b"com/squareup/okhttp"],
    "Volley/HurlStack": [b"com/android/volley"],
    "Ktor/Retrofit": [b"retrofit2", b"io/ktor"],
    "WebView/Chromium": [b"android/webkit/WebView", b"WebViewClient", b"WebResourceRequest"],
    "libcurl nativo": [b"libcurl", b"curl_easy", b"CURLOPT"],
    "ssl nativo": [b"libssl", b"libcrypto", b"SSL_CTX"],
}

LOOPBACK_SIGNATURES = [b"127.0.0.1", b"localhost", b"::1"]
CDNI_HOST_SIGNATURES = [b"prod.cdni.callofduty.com", b"cdni.callofduty.com", b"shard_cdn"]


def _decode(data: bytes) -> str:
    for encoding in ("utf-8", "latin-1"):
        try:
            return data.decode(encoding)
        except Exception:
            continue
    return ""


def _scan_patterns(text: str, patterns: dict[str, list[bytes]] | list[bytes]) -> dict[str, list[str]]:
    """Devolve {nome: [padrões encontrados]} (nomes vazios quando a lista é simples)."""
    found: dict[str, list[str]] = {}
    if isinstance(patterns, list):
        hits = [pattern.decode("latin-1") for pattern in patterns if pattern in text.encode("latin-1", "ignore")]
        if hits:
            found["_"] = hits
        return found
    for name, needles in patterns.items():
        hits = [needle.decode("latin-1") for needle in needles if needle in text.encode("latin-1", "ignore")]
        if hits:
            found[name] = hits
    return found


class ScanResult:
    def __init__(self) -> None:
        self.entries_total = 0
        self.entries_scanned = 0
        self.manifest: dict[str, object] = {}
        self.network_security_configs: list[dict[str, object]] = []
        self.embedded_certificates: list[str] = []
        self.pinning: dict[str, list[str]] = {}
        self.tls_stacks: dict[str, list[str]] = {}
        self.loopback_references: list[str] = []
        self.cdni_references: list[str] = []
        self.notes: list[str] = []
        self.errors: list[str] = []

    def merge_patterns(self, target: dict[str, list[str]], new: dict[str, list[str]], source: str) -> None:
        for name, hits in new.items():
            bucket = target.setdefault(name, [])
            for hit in hits:
                entry = f"{hit} ({source})"
                if entry not in bucket and len(bucket) < 12:
                    bucket.append(entry)


def analyze_bytes(name: str, data: bytes, result: ScanResult, confidence: str) -> None:
    lower = name.lower()
    text = _decode(data)

    if lower.endswith("androidmanifest.xml"):
        attributes = ["networkSecurityConfig", "usesCleartextTraffic", "debuggable", "network_security_config"]
        present = {attribute: (attribute in text) for attribute in attributes}
        result.manifest = {
            "file": name,
            "confidence": confidence,
            "networkSecurityConfig_declared": present["networkSecurityConfig"] or present["network_security_config"],
            "usesCleartextTraffic_present": present["usesCleartextTraffic"],
            "debuggable_present": present["debuggable"],
            "note": (
                "AndroidManifest binário (ARSC): presença detectada por bytes; para VERIFIED é preciso "
                "apktool/jadx (--dir) ou aapt2 dump xmltree"
            ) if confidence == "PROBABLE" else "manifest decodificado (texto)",
        }
        return

    if "network_security_config" in lower or (lower.endswith(".xml") and "res/xml/" in lower):
        config: dict[str, object] = {
            "file": name,
            "confidence": confidence,
            "has_network_security_config": "<network-security-config" in text,
            "trust_anchors": [],
            "pin_set": "pin-set" in text or "pin digest" in text.lower(),
            "pin_digests": re.findall(r"sha256/[A-Za-z0-9+/=]{10,}", text)[:8],
            "cleartext_allowed": "cleartextTrafficPermitted=\"true\"" in text,
            "domain_configs": re.findall(r"<domain[^>]*>([^<]+)</domain>", text)[:20],
        }
        for src in re.findall(r'<certificates[^>]*src="([^"]+)"', text):
            config["trust_anchors"].append(src)  # type: ignore[union-attr]
        if "user" in (config["trust_anchors"] or []):  # type: ignore[operator]
            result.notes.append(f"{name}: o app permite CA instalada pelo USUÁRIO (src=\"user\")")
        if config["pin_set"]:
            result.notes.append(f"{name}: PIN-SET declarado (pinning por network security config)")
        if config["has_network_security_config"] or config["pin_set"]:
            result.network_security_configs.append(config)
        return

    if any(lower.endswith(extension) for extension in CERT_EXTENSIONS) or (
        "assets/" in lower and re.search(r"(ca|trust|root|cert)", lower)
    ):
        result.embedded_certificates.append(f"{name} ({len(data)} B, {confidence})")
        return

    if lower.endswith((".dex", ".so", ".arsc", ".jar", ".bin")) or confidence == "PROBABLE":
        result.merge_patterns(result.pinning, _scan_patterns(text, PINNING_SIGNATURES), name)
        result.merge_patterns(result.tls_stacks, _scan_patterns(text, TLS_STACK_SIGNATURES), name)
        for pattern in LOOPBACK_SIGNATURES:
            if pattern in text.encode("latin-1", "ignore") and len(result.loopback_references) < 12:
                result.loopback_references.append(f"{pattern.decode('latin-1')} ({name})")
        for pattern in CDNI_HOST_SIGNATURES:
            if pattern in text.encode("latin-1", "ignore") and len(result.cdni_references) < 12:
                result.cdni_references.append(f"{pattern.decode('latin-1')} ({name})")
        return

    if lower.endswith(".xml"):
        result.merge_patterns(result.tls_stacks, _scan_patterns(text, TLS_STACK_SIGNATURES), name)
        return


def scan_dir(root: str, result: ScanResult) -> None:
    for current, _dirs, files in os.walk(root):
        for file_name in files:
            path = os.path.join(current, file_name)
            relative = os.path.relpath(path, root)
            try:
                size = os.path.getsize(path)
                if size > MAX_ENTRY_BYTES:
                    result.notes.append(f"ignorado (>{MAX_ENTRY_BYTES} B): {relative}")
                    continue
                with open(path, "rb") as handle:
                    data = handle.read()
            except Exception as error:
                result.errors.append(f"{relative}: {error}")
                continue
            result.entries_scanned += 1
            extension = os.path.splitext(relative)[1].lower()
            confidence = "VERIFIED" if extension in TEXT_EXTENSIONS else "PROBABLE"
            analyze_bytes(relative, data, result, confidence)


def scan_zip(path: str, result: ScanResult) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        result.entries_total = len(names)
        for name in names:
            lower = name.lower()
            if lower.endswith((".apk", ".xapk")):  # XAPK contém APKs internos
                continue
            if not lower.endswith((
                ".dex", ".so", ".xml", ".arsc", ".json", ".js", ".html", ".txt", ".properties",
                ".pem", ".crt", ".cer", ".der", ".p12", ".pfx", ".bks", ".jks"
            )) and not ("assets/" in lower and re.search(r"(ca|trust|root|cert)", lower)):
                continue
            try:
                info = archive.getinfo(name)
                if info.file_size > MAX_ENTRY_BYTES:
                    result.notes.append(f"ignorado (>{MAX_ENTRY_BYTES} B): {name}")
                    continue
                data = archive.read(name)
            except Exception as error:
                result.errors.append(f"{name}: {error}")
                continue
            result.entries_scanned += 1
            extension = os.path.splitext(lower)[1]
            confidence = "VERIFIED" if extension in TEXT_EXTENSIONS else "PROBABLE"
            analyze_bytes(name, data, result, confidence)


def assess(result: ScanResult) -> dict[str, object]:
    """Matriz de decisão: existe cadeia de confiança local legítima?"""
    pinning_names = sorted(result.pinning.keys())
    user_ca = any("user" in (config.get("trust_anchors") or []) for config in result.network_security_configs)
    pin_set = any(config.get("pin_set") for config in result.network_security_configs)
    embedded = bool(result.embedded_certificates)
    nsc = bool(result.network_security_configs) or bool(result.manifest.get("networkSecurityConfig_declared"))

    def status(ok: bool, blocked: bool, unknown: bool = False) -> str:
        if ok:
            return "VIAVEL"
        if blocked:
            return "BLOQUEADO"
        return "UNKNOWN" if unknown else "UNKNOWN"

    return {
        "pinning_apis_encontradas": pinning_names,
        "pin_set_no_nsc": pin_set,
        "ca_embutida": embedded,
        "nsc_declarado": nsc,
        "ca_de_usuario_aceita": user_ca,
        "loopback_no_apk": bool(result.loopback_references),
        "opcoes_de_cadeia_de_confianca": [
            {
                "opcao": "CA local no armazenamento do USUÁRIO (sem root)",
                "status": status(
                    ok=user_ca and not pinning_names and not pin_set,
                    blocked=bool(pinning_names or pin_set),
                ),
                "observacao": "só funciona se o app confiar em CA de usuário (src=\"user\") e não houver pinning",
            },
            {
                "opcao": "CA local no armazenamento do SISTEMA (exige root/ADB root)",
                "status": "FORA_DE_ESCOPO" if not user_ca else "BLOQUEADO",
                "observacao": "contraria a restrição de projeto (sem root); documentar apenas como referência",
            },
            {
                "opcao": "Mudar trust/pinning do app (patch no APK)",
                "status": "PROIBIDO",
                "observacao": "decisão explícita do usuário: sem bypass de trust/pinning; não modificar nem distribuir o APK",
            },
            {
                "opcao": "Endpoints de desenvolvimento/loopback previstos pelo próprio app",
                "status": status(ok=bool(result.loopback_references), blocked=False, unknown=not result.loopback_references),
                "observacao": "se o app procura 127.0.0.1 por conta própria, a cadeia tem que ser validada mesmo assim",
            },
        ],
        "proxima_evidencia_necessaria": [
            "res/xml/network_security_config.xml decodificado (apktool/jadx) — trust anchors e pin-set",
            "classe que inicializa o cliente HTTP/CDNI (okhttp? cronet? WebView? nativo?)",
            "se há pinning: hosts pinados e origem (CertificatePinner, TrustKit, SSC pinning em .so)",
            "se o cliente CDNI é WebView: WebView historicamente aceita CA de usuário — validar no device",
        ],
    }


def render(result: ScanResult, assessment: dict[str, object], source: str) -> str:
    lines = [
        "== WZM Offline — scanner TLS/trust/pinning (M3.2) ==",
        f"fonte: {source}",
        f"entradas lidas: {result.entries_scanned} (total {result.entries_total or 'n/d'})",
        "",
        "-- manifest --",
        json.dumps(result.manifest, indent=2, ensure_ascii=False) if result.manifest else "(não encontrado)",
        "",
        "-- network security configs --",
    ]
    if result.network_security_configs:
        for config in result.network_security_configs:
            lines.append(json.dumps(config, indent=2, ensure_ascii=False))
    else:
        lines.append("(nenhum arquivo identificado)")
    lines += [
        "",
        f"-- certificados embutidos: {len(result.embedded_certificates)} --",
        *[f"  {item}" for item in result.embedded_certificates[:20]],
        "",
        f"-- assinaturas de pinning/trust: {len(result.pinning)} --",
        *[f"  {name}: {', '.join(hits[:4])}" for name, hits in result.pinning.items()],
        "",
        f"-- stacks TLS: {len(result.tls_stacks)} --",
        *[f"  {name}: {', '.join(hits[:3])}" for name, hits in result.tls_stacks.items()],
        "",
        f"-- referências a loopback: {len(result.loopback_references)} --",
        *[f"  {item}" for item in result.loopback_references[:10]],
        "",
        f"-- referências a CDNI: {len(result.cdni_references)} --",
        *[f"  {item}" for item in result.cdni_references[:10]],
        "",
        "-- avaliação (cadeia de confiança) --",
    ]
    for option in assessment["opcoes_de_cadeia_de_confianca"]:  # type: ignore[index]
        lines.append(f"  [{option['status']}] {option['opcao']} — {option['observacao']}")
    lines.append("")
    lines.append("-- próximas evidências --")
    for item in assessment["proxima_evidencia_necessaria"]:  # type: ignore[index]
        lines.append(f"  - {item}")
    if result.notes:
        lines += ["", "-- notas --", *[f"  {note}" for note in result.notes[:20]]]
    if result.errors:
        lines += ["", "-- erros --", *[f"  {error}" for error in result.errors[:20]]]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- self-test

def self_test() -> int:
    """Fixture sintética (não é o APK real): valida que o scanner detecta o que promete."""
    temp = tempfile.mkdtemp(prefix="tls-trust-selftest-")
    try:
        os.makedirs(os.path.join(temp, "res/xml"), exist_ok=True)
        os.makedirs(os.path.join(temp, "assets"), exist_ok=True)
        os.makedirs(os.path.join(temp, "lib/arm64-v8a"), exist_ok=True)

        with open(os.path.join(temp, "AndroidManifest.xml"), "w", encoding="utf-8") as handle:
            handle.write(
                '<manifest><application android:networkSecurityConfig="@xml/network_security_config" '
                'android:usesCleartextTraffic="false"/></manifest>'
            )
        with open(os.path.join(temp, "res/xml/network_security_config.xml"), "w", encoding="utf-8") as handle:
            handle.write(
                "<network-security-config>\n"
                '  <base-config cleartextTrafficPermitted="false">\n'
                "    <trust-anchors><certificates src=\"system\"/></trust-anchors>\n"
                "  </base-config>\n"
                "  <domain-config>\n"
                "    <domain>prod.cdni.callofduty.com</domain>\n"
                "    <pin-set><pin digest=\"SHA-256\">sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin></pin-set>\n"
                "  </domain-config>\n"
                "</network-security-config>"
            )
        with open(os.path.join(temp, "assets/roots.pem"), "w", encoding="utf-8") as handle:
            handle.write("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")
        with open(os.path.join(temp, "classes.dex"), "wb") as handle:
            handle.write(b"\x00okhttp3/CertificatePinner\x00CertificatePinner\x00127.0.0.1\x00prod.cdni.callofduty.com\x00")
        with open(os.path.join(temp, "lib/arm64-v8a/libssl.so"), "wb") as handle:
            handle.write(b"\x7fELF\x00libssl\x00SSL_CTX_new\x00sha256/\x00")

        result = ScanResult()
        scan_dir(temp, result)
        assessment = assess(result)
        report = render(result, assessment, temp)

        checks = {
            "manifest detectado": bool(result.manifest.get("networkSecurityConfig_declared")),
            "nsc encontrado": bool(result.network_security_configs),
            "pin-set detectado": bool(assessment["pin_set_no_nsc"]),
            "trust anchor system": any(
                "system" in (config.get("trust_anchors") or []) for config in result.network_security_configs
            ),
            "certificado embutido": bool(result.embedded_certificates),
            "pinning okhttp": "okhttp CertificatePinner" in result.pinning,
            "stack okhttp": "okhttp" in result.tls_stacks,
            "loopback detectado": bool(result.loopback_references),
            "host CDNI detectado": bool(result.cdni_references),
            "pinning bloqueia CA de usuário": any(
                option["status"] == "BLOQUEADO" and "USUÁRIO" in option["opcao"]
                for option in assessment["opcoes_de_cadeia_de_confianca"]  # type: ignore[index]
            ),
            "patch marcado como proibido": any(
                option["status"] == "PROIBIDO"
                for option in assessment["opcoes_de_cadeia_de_confianca"]  # type: ignore[index]
            ),
        }

        # variante ZIP (mesmo conteúdo, caminho de APK)
        zip_path = os.path.join(temp, "fixture.apk")
        with zipfile.ZipFile(zip_path, "w") as archive:
            for current, _dirs, files in os.walk(temp):
                for file_name in files:
                    if file_name.endswith(".apk"):
                        continue
                    full = os.path.join(current, file_name)
                    archive.write(full, os.path.relpath(full, temp))
        zip_result = ScanResult()
        scan_zip(zip_path, zip_result)
        checks["zip: nsc encontrado"] = bool(zip_result.network_security_configs)
        checks["zip: pin-set detectado"] = any(
            config.get("pin_set") for config in zip_result.network_security_configs
        )
        checks["zip: pinning okhttp"] = "okhttp CertificatePinner" in zip_result.pinning

        failed = [name for name, ok in checks.items() if not ok]
        for name, ok in checks.items():
            print(f"  [{'ok' if ok else 'FALHOU'}] {name}")
        if failed:
            print("\n--- relatório do self-test (para diagnóstico) ---")
            print(report)
            print(f"self-test FALHOU: {len(failed)} verificações")
            return 1
        print(f"self-test OK: {len(checks)} verificações (fixture sintética, não é o APK real)")
        return 0
    finally:
        shutil.rmtree(temp, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Scanner TLS/trust/pinning (read-only) — M3.2")
    parser.add_argument("--apk", help="APK/XAPK externo (não versionado)")
    parser.add_argument("--dir", help="diretório já decodificado (apktool/jadx) ou extraído")
    parser.add_argument("--out", help="relatório JSON (fora do repositório)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.apk and not args.dir:
        parser.print_help()
        return 2

    result = ScanResult()
    if args.apk:
        source = f"apk:{os.path.basename(args.apk)}"
        scan_zip(args.apk, result)
    else:
        source = f"dir:{os.path.basename(args.dir)}"
        scan_dir(args.dir, result)

    assessment = assess(result)
    report = render(result, assessment, source)
    print(report)

    if args.out:
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "source": source,
            "scan": {
                "entries_total": result.entries_total,
                "entries_scanned": result.entries_scanned,
                "manifest": result.manifest,
                "network_security_configs": result.network_security_configs,
                "embedded_certificates": result.embedded_certificates,
                "pinning": result.pinning,
                "tls_stacks": result.tls_stacks,
                "loopback_references": result.loopback_references,
                "cdni_references": result.cdni_references,
                "notes": result.notes,
                "errors": result.errors,
            },
            "assessment": assessment,
            "disclaimer": (
                "Relatório read-only. O APK do jogo NÃO é modificado, copiado para o repositório ou "
                "distribuído. Nenhuma técnica de bypass de trust/pinning é aplicada."
            ),
        }
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, ensure_ascii=False)
        print(f"\nrelatório JSON: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
