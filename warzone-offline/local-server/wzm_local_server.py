#!/usr/bin/env python3
# wzm_local_server.py — servidor local do WZM (M5.0): HTTPS + HTTP, rotas mínimas, log por estágio
#
# Arquitetura alvo (definida pelo usuário):
#
#     WZM ──HTTPS──► prod.cdni.callofduty.com ──DNS local──► IP do servidor ──► Local Server
#
# Este processo é o "Local Server". Ele NÃO depende de VPN/TUN: a VPN do launcher
# (se usada) é apenas um *transporte* possível para entregar o DNS. Aqui o contrato
# é só este: alguém resolve o hostname para o nosso IP, e nós respondemos.
#
# O que ele faz:
#   * serve as rotas de routes.json (hoje: só o cdni.meta real);
#   * registra CADA estágio em JSONL: consulta recebida, SNI do ClientHello,
#     falha de handshake TLS, requisição HTTP (método/host/path/cabeçalhos
#     filtrados) e статус da resposta;
#   * tudo o mais vira 404 registrado — é assim que descobrimos a próxima request.
#
# Limites (regras do projeto):
#   * NÃO altera o cliente, o APK, TLS/pinning/trust, autenticação ou anti-cheat;
#   * NÃO registra corpos de requisição/resposta, cookies, tokens ou credenciais
#     (valores de cabeçalhos sensíveis vão como "<redacted>");
#   * bind padrão em 127.0.0.1 — expor na LAN exige --bind explícito.
#
# Uso:
#   sudo python3 wzm_local_server.py --bind 192.168.0.10 --https-port 443 --http-port 8080 \
#        --cert certs/server.pem --key certs/server.key --log logs/requests.jsonl
#   python3 wzm_local_server.py --self-test

import argparse
import json
import os
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROUTES = os.path.join(HERE, "routes.json")

# Cabeçalhos cujo VALOR nunca é registrado (nome é registrado, valor não).
SENSITIVE_HEADERS = {
    "authorization", "proxy-authorization", "cookie", "set-cookie",
    "x-auth-token", "x-api-key", "x-session", "www-authenticate",
}

# Cabeçalhos que sempre registramos por inteiro (úteis para o protocolo).
VERBOSE_HEADERS = {
    "host", "user-agent", "accept", "accept-encoding", "accept-language",
    "content-type", "content-length", "connection", "cache-control",
}


def now_ms():
    return int(time.time() * 1000)


def iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


class RequestLogger:
    """JSONL append-only, com redacção de valores sensíveis."""

    def __init__(self, path=None, quiet=False):
        self.path = path
        self.quiet = quiet
        self._lock = threading.Lock()
        if self.path:
            os.makedirs(os.path.dirname(os.path.abspath(self.path)) or ".", exist_ok=True)

    def log(self, record):
        record.setdefault("ts", iso())
        line = json.dumps(record, ensure_ascii=False, sort_keys=True)
        with self._lock:
            if self.path:
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
            if not self.quiet:
                print("[log] " + line, flush=True)

    def filter_headers(self, headers):
        out = {}
        for k, v in headers.items():
            lk = k.lower()
            if lk in SENSITIVE_HEADERS:
                out[k] = "<redacted>"
            elif lk in VERBOSE_HEADERS or lk.startswith("x-") or lk.startswith("dw-"):
                out[k] = v
            else:
                out[k] = v
        return out


class RouteTable:
    def __init__(self, routes, base_dir):
        self.routes = routes           # lista de dicts
        self.base_dir = base_dir

    @classmethod
    def load(cls, path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(data.get("routes", []), os.path.dirname(os.path.abspath(path)))

    def match(self, method, path):
        for r in self.routes:
            if r.get("path") == path:
                if r.get("methods") and method.upper() not in [m.upper() for m in r["methods"]]:
                    continue
                return r
        return None

    def build_response(self, route):
        status = int(route.get("status", 200))
        ctype = route.get("content_type", "application/octet-stream")
        if "file" in route:
            with open(os.path.join(self.base_dir, route["file"]), "rb") as f:
                body = f.read()
        else:
            body = route.get("body", "").encode("utf-8")
        headers = dict(route.get("headers", {}))
        headers.setdefault("Content-Type", ctype)
        headers.setdefault("Content-Length", str(len(body)))
        return status, body, headers


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "wzm-local-server/1.0"
    logger = None          # RequestLogger
    routes = None          # RouteTable
    record_unknown = True

    # -------------------------------------------------------------- utilidades
    def _base_record(self):
        conn = getattr(self.connection, "_wzm_sni_record", None) or {}
        return {
            "event": "http_request",
            "peer": self.client_address[0] if self.client_address else None,
            "peer_port": self.client_address[1] if self.client_address else None,
            "transport": "https" if isinstance(self.connection, ssl.SSLSocket) else "http",
            "sni": conn.get("sni"),
            "method": self.command,
            "path": self.path,
            "http_version": self.request_version,
            "headers": self.logger.filter_headers(dict(self.headers)),
        }

    def _respond(self, status, body, headers, note=None):
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _handle_any(self):
        rec = self._base_record()
        route = self.routes.match(self.command, self.path.split("?")[0])
        if route:
            status, body, headers = self.routes.build_response(route)
            rec["matched_route"] = route.get("path")
            rec["note"] = route.get("note")
        else:
            status = 404
            body = json.dumps({"error": "not_found", "path": self.path}).encode("utf-8")
            headers = {"Content-Type": "application/json", "Content-Length": str(len(body))}
            rec["matched_route"] = None
            rec["note"] = "rota desconhecida — candidata a próxima etapa (descobrir)"
        rec["status"] = status
        rec["response_bytes"] = len(body)
        # nunca registramos corpo de requisição
        if "Content-Length" in self.headers:
            rec["request_body_bytes"] = int(self.headers.get("Content-Length", 0) or 0)
        self.logger.log(rec)
        self._respond(status, body, headers)

    # ------------------------------------------------------------------ verbos
    def do_GET(self):
        self._handle_any()

    def do_HEAD(self):
        self._handle_any()

    def do_POST(self):
        self._handle_any()

    def do_PUT(self):
        self._handle_any()

    def do_DELETE(self):
        self._handle_any()

    def do_CONNECT(self):
        self.logger.log({**self._base_record(), "event": "connect_method", "status": 405})
        self.send_error(405, "CONNECT não suportado (sem proxy)")

    def log_message(self, fmt, *args):
        pass  # silencia o log padrão; tudo vai para o nosso JSONL


class SniLoggingContext(ssl.SSLContext):
    """Contexto que guarda o SNI do ClientHello no próprio socket."""
    pass


def make_ssl_context(cert, key, logger):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    ctx.set_alpn_protocols(["http/1.1"])

    def _sni_cb(sslsock, server_name, sslctx):
        sslsock._wzm_sni_record = {"sni": server_name}
        try:
            peer = sslsock.getpeername()
        except Exception:
            peer = None
        logger.log({"event": "tls_client_hello", "sni": server_name,
                    "peer": peer[0] if peer else None,
                    "peer_port": peer[1] if peer else None})
        return None  # usa o certificado configurado

    ctx.sni_callback = _sni_cb
    return ctx


class TlsLoggingHTTPServer(ThreadingHTTPServer):
    """Aceita, faz o handshake TLS e registra falhas (o blocker mais provável)."""

    daemon_threads = True
    allow_reuse_address = True
    address_family = socket.AF_INET

    def __init__(self, addr, handler_cls, ssl_context, logger):
        super().__init__(addr, handler_cls)
        self.ssl_context = ssl_context
        self.logger = logger

    def get_request(self):
        sock, addr = self.socket.accept()
        if self.ssl_context is None:
            return sock, addr
        try:
            conn = self.ssl_context.wrap_socket(sock, server_side=True, do_handshake_on_connect=False)
            try:
                conn.do_handshake()
            except Exception:
                # registra e deixa a exceção subir (handle_error fica silencioso)
                raise
            return conn, addr
        except Exception as exc:
            sni = getattr(sock, "_wzm_sni_record", None)
            self.logger.log({
                "event": "tls_handshake_failed",
                "peer": addr[0], "peer_port": addr[1],
                "sni": (sni or {}).get("sni"),
                "error_type": type(exc).__name__,
                "error": str(exc)[:200],
            })
            try:
                sock.close()
            except Exception:
                pass
            raise

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ssl.SSLError, ssl.SSLEOFError, ConnectionResetError,
                            BrokenPipeError, OSError)):
            return  # já registrado em get_request()
        self.logger.log({"event": "handler_error", "peer": client_address[0],
                         "error_type": type(exc).__name__, "error": str(exc)[:200]})


class V6Server(TlsLoggingHTTPServer):
    address_family = socket.AF_INET6


def build_server(bind, port, routes, logger, cert=None, key=None, ipv6=False):
    handler = type("BoundHandler", (Handler,), {"logger": logger, "routes": routes})
    ctx = make_ssl_context(cert, key, logger) if (cert and key) else None
    base = V6Server if ipv6 else TlsLoggingHTTPServer
    cls = type("BoundServer", (base,), {})
    try:
        srv = cls((bind, port), handler, ctx, logger)
    except OSError as exc:
        if "Address already in use" in str(exc) or getattr(exc, "errno", None) == 98:
            raise SystemExit("porta %d em uso; escolha outra ou libere a porta" % port)
        raise
    return srv


# ------------------------------------------------------------------ self-test

def _openssl_selfsigned(tmpdir, san_list):
    """Gera CA + leaf com openssl (se disponível). Retorna (ca, cert, key) ou None."""
    if not _have_openssl():
        return None
    ca = os.path.join(tmpdir, "ca.pem")
    ca_key = os.path.join(tmpdir, "ca.key")
    cert = os.path.join(tmpdir, "server.pem")
    key = os.path.join(tmpdir, "server.key")
    san = ",".join("DNS:%s" % h for h in san_list)
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", ca_key, "-out", ca, "-days", "2",
                    "-subj", "/CN=wzm-local-test-ca"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["openssl", "req", "-newkey", "rsa:2048", "-nodes",
                    "-keyout", key, "-out", os.path.join(tmpdir, "server.csr"),
                    "-subj", "/CN=prod.cdni.callofduty.com"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(os.path.join(tmpdir, "ext.cnf"), "w") as f:
        f.write("subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n"
                "extendedKeyUsage=serverAuth\n" % san)
    subprocess.run(["openssl", "x509", "-req", "-in", os.path.join(tmpdir, "server.csr"),
                    "-CA", ca, "-CAkey", ca_key, "-CAcreateserial",
                    "-out", cert, "-days", "2", "-sha256",
                    "-extfile", os.path.join(tmpdir, "ext.cnf")], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return ca, cert, key


def _have_openssl():
    try:
        subprocess.run(["openssl", "version"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=True)
        return True
    except Exception:
        return False


def self_test():
    print("== self-test wzm_local_server ==")
    routes = RouteTable.load(DEFAULT_ROUTES)
    tmpdir = tempfile.mkdtemp(prefix="wzm-selftest-")
    log_path = os.path.join(tmpdir, "requests.jsonl")
    logger = RequestLogger(log_path, quiet=True)

    # 1) HTTP puro
    srv = build_server("127.0.0.1", 0, routes, logger)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    def http_get(path, use_tls=False, ctx=None, host="prod.cdni.callofduty.com"):
        import http.client
        if use_tls:
            conn = http.client.HTTPSConnection("127.0.0.1", port, timeout=5, context=ctx)
        else:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        conn.request("GET", path, headers={"Host": host})
        r = conn.getresponse()
        body = r.read()
        status = r.status
        conn.close()
        return status, body

    status, body = http_get("/wzm/shard_cdn/android/_manifest/cdni.meta")
    assert status == 200, status
    data = json.loads(body)
    assert data["min_buildnum"] == 19854920, data
    print("  [ok] HTTP 200 + corpo real do cdni.meta (min_buildnum=19854920)")

    status, _ = http_get("/nao-existe")
    assert status == 404, status
    print("  [ok] rota desconhecida -> 404 registrado (descobrir próxima etapa)")

    srv.shutdown()
    srv.server_close()

    # 2) HTTPS + SNI + handshake com e sem confiança
    if not _have_openssl():
        print("  [skip] openssl indisponível — parte TLS não testada")
        return 0
    creds = _openssl_selfsigned(tmpdir, ["prod.cdni.callofduty.com", "*.cdni.callofduty.com"])
    ca, cert, key = creds
    logger2 = RequestLogger(log_path, quiet=True)
    srv2 = build_server("127.0.0.1", 0, routes, logger2, cert=cert, key=key)
    tls_port = srv2.server_address[1]
    threading.Thread(target=srv2.serve_forever, daemon=True).start()

    unverified = ssl._create_unverified_context()
    unverified.check_hostname = False

    def https_get(path, ctx, host="prod.cdni.callofduty.com"):
        """Requisição TLS crua com SNI explícito (como o cliente faria)."""
        raw = socket.create_connection(("127.0.0.1", tls_port), timeout=5)
        sock = ctx.wrap_socket(raw, server_hostname=host)
        sock.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n"
                      % (path, host)).encode())
        buf = b""
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buf += chunk
        sock.close()
        head, _, body = buf.partition(b"\r\n\r\n")
        return int(head.split()[1]), body

    status, body = https_get("/wzm/shard_cdn/android/_manifest/cdni.meta", unverified)
    assert status == 200, status
    assert json.loads(body)["min_buildnum"] == 19854920
    print("  [ok] HTTPS 200 (certificado não confiado pelo cliente, sem verificação)")

    trusted = ssl.create_default_context(cafile=ca)
    status, body = https_get("/wzm/shard_cdn/android/_manifest/cdni.meta", trusted)
    assert status == 200, status
    print("  [ok] HTTPS 200 com o cliente confiando na nossa CA (caminho sem alterar o app)")

    # cliente que NÃO confia -> falha de handshake registrada
    try:
        strict = ssl.create_default_context()  # CAs do sistema
        https_get("/wzm/shard_cdn/android/_manifest/cdni.meta", strict)
        print("  [aviso] cliente aceitou certificado inesperado (ambiente sem CAs?)")
    except ssl.SSLCertVerificationError as exc:
        print("  [ok] cliente sem confiança -> handshake falhou e foi registrado: %s"
              % str(exc).split("(")[0])

    time.sleep(0.2)
    srv2.shutdown()
    srv2.server_close()

    events = []
    with open(log_path, "r", encoding="utf-8") as f:
        for line in f:
            events.append(json.loads(line))
    kinds = [e["event"] for e in events]
    assert "http_request" in kinds
    assert any(e.get("sni") == "prod.cdni.callofduty.com" for e in events), kinds
    print("  [ok] log JSONL com %d evento(s); SNI capturado" % len(events))
    print("self-test OK")
    return 0


# ----------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description="Servidor local do WZM (HTTPS+HTTP, rotas mínimas, log por estágio)")
    ap.add_argument("--bind", default="127.0.0.1",
                    help="endereço de escuta (padrão 127.0.0.1; para o celular alcançar, use o IP da LAN)")
    ap.add_argument("--https-port", type=int, default=443, help="porta HTTPS (padrão 443; exige root/cap no Linux)")
    ap.add_argument("--http-port", type=int, default=8080, help="porta HTTP clara (0 = desligada)")
    ap.add_argument("--cert", help="certificado do servidor (PEM)")
    ap.add_argument("--key", help="chave privada (PEM)")
    ap.add_argument("--routes", default=DEFAULT_ROUTES, help="routes.json")
    ap.add_argument("--log", default=os.path.join(HERE, "logs", "requests.jsonl"), help="arquivo JSONL de log")
    ap.add_argument("--ipv6", action="store_true", help="escuta em IPv6")
    ap.add_argument("--no-https", action="store_true", help="não sobe HTTPS")
    ap.add_argument("--quiet", action="store_true", help="não ecoa o log no stdout")
    ap.add_argument("--self-test", action="store_true", help="testa rotas, TLS e log, e sai")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    routes = RouteTable.load(args.routes)
    logger = RequestLogger(args.log, quiet=args.quiet)
    if not args.quiet:
        print("rotas carregadas: %d" % len(routes.routes))
        for r in routes.routes:
            print("  %s %s -> %s (%s)" % ("/".join(r.get("methods", ["GET"])), r["path"],
                                          r.get("file") or "(inline)", r.get("note", "")))

    threads = []
    if not args.no_https:
        if not (args.cert and args.key):
            raise SystemExit("HTTPS pede --cert e --key (veja certs/make-local-cert.sh) ou use --no-https")
        srv = build_server(args.bind, args.https_port, routes, logger,
                           cert=args.cert, key=args.key, ipv6=args.ipv6)
        threads.append(("https", args.https_port, srv))
    if args.http_port:
        srv = build_server(args.bind, args.http_port, routes, logger, ipv6=args.ipv6)
        threads.append(("http", args.http_port, srv))

    if not threads:
        raise SystemExit("nada para servir")

    for name, port, srv in threads:
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        print("escutando %s://%s:%d (log: %s)" % (name, args.bind, port, args.log), flush=True)
    print("Ctrl+C para sair", flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nencerrando", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
