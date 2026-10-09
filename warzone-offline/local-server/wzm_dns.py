#!/usr/bin/env python3
# wzm_dns.py — DNS local do WZM (M5.0): responde os hostnames do jogo apontando para o servidor local
#
# Papel na arquitetura (definida pelo usuário):
#
#     prod.cdni.callofduty.com ──DNS──► IP do nosso servidor ──► Local Server
#
# Ele é AUTORITATIVO só para os nomes que você disser (--host / --wildcard) e
# encaminha o resto para um upstream. Não é proxy, não é VPN: é um resolvedor
# que você aponta no aparelho (DNS estático do Wi-Fi, /etc/hosts com root, ou
# a VPN do launcher como *transporte*).
#
# Privacidade: por padrão só registra as consultas dos nomens vigiados; as
# demais entram apenas em contadores agregados (--log-all-queries desliga isso).
#
# Uso:
#   sudo python3 wzm_dns.py --bind 0.0.0.0 --port 53 \
#        --host prod.cdni.callofduty.com=192.168.0.10 \
#        --wildcard cdni.callofduty.com=192.168.0.10 \
#        --upstream 8.8.8.8 --log logs/dns.jsonl
#   python3 wzm_dns.py --self-test

import argparse
import json
import os
import socket
import socketserver
import struct
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))

TYPE_A = 1
TYPE_AAAA = 28
TYPE_HTTPS = 65
TYPE_ANY = 255
CLASS_IN = 1

RCODE_NOERROR = 0
RCODE_SERVFAIL = 2
RCODE_NXDOMAIN = 3
RCODE_REFUSED = 5

TYPE_NAMES = {1: "A", 5: "CNAME", 28: "AAAA", 65: "HTTPS", 255: "ANY"}


def iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


# --------------------------------------------------------------- codec de DNS

def parse_question(data):
    """Lê a seção de pergunta (a partir do offset 12). Retorna (nome, tipo, classe, fim)."""
    labels = []
    i = 12
    n = len(data)
    while i < n:
        length = data[i]
        if length == 0:
            i += 1
            break
        if length & 0xC0 == 0xC0:          # compressão (não esperado em pergunta)
            i += 2
            break
        labels.append(data[i + 1:i + 1 + length])
        i += 1 + length
    name = b".".join(labels).decode("ascii", errors="replace").lower()
    if i + 4 > n:
        return name, None, None, n
    qtype, qclass = struct.unpack_from("!HH", data, i)
    return name, qtype, qclass, i + 4


def encode_name(name):
    out = b""
    for label in name.split("."):
        if not label:
            continue
        b = label.encode("ascii")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def build_response(query, qname, qtype, answers, rcode=RCODE_NOERROR, ttl=30, aa=True):
    """answers: lista de (tipo, rdata_bytes)."""
    qid = struct.unpack_from("!H", query, 0)[0]
    rd = (struct.unpack_from("!H", query, 2)[0] >> 8) & 0x01   # copia RD
    flags = 0x8000 | (rd << 8) | 0x0080 | (0x0400 if aa else 0) | (rcode & 0x0F)
    header = struct.pack("!HHHHHH", qid, flags, 1, len(answers), 0, 0)
    question = encode_name(qname) + struct.pack("!HH", qtype, CLASS_IN)
    body = b""
    for atype, rdata in answers:
        body += b"\xc0\x0c" + struct.pack("!HHIH", atype, CLASS_IN, ttl, len(rdata)) + rdata
    return header + question + body


def ipv4_to_bytes(ip):
    return socket.inet_aton(ip)


def forward(query, upstream, timeout=3.0):
    """Encaminha a consulta crua ao upstream e devolve a resposta (ou None)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(timeout)
        s.sendto(query, (upstream, 53))
        data, _ = s.recvfrom(4096)
        s.close()
        return data
    except Exception:
        return None


# ------------------------------------------------------------------ resolvedor

class Resolver:
    def __init__(self, hosts, wildcards, upstream, ttl=30, aaaa="nodata", no_upstream=False):
        self.hosts = {k.lower(): v for k, v in hosts.items()}
        self.wildcards = {k.lower().lstrip("."): v for k, v in wildcards.items()}
        self.upstream = upstream
        self.ttl = ttl
        self.aaaa = aaaa                  # nodata | ipv6 | forward
        self.no_upstream = no_upstream

    def is_watched(self, name):
        if name in self.hosts:
            return True
        for suffix in self.wildcards:
            if name == suffix or name.endswith("." + suffix):
                return True
        return False

    def local_ip(self, name):
        if name in self.hosts:
            return self.hosts[name]
        best = None
        for suffix, ip in self.wildcards.items():
            if name == suffix or name.endswith("." + suffix):
                if best is None or len(suffix) > len(best[0]):
                    best = (suffix, ip)
        return best[1] if best else None

    def answer(self, query):
        """Retorna (resposta_bytes, registro_para_log)."""
        name, qtype, qclass, qend = parse_question(query)
        record = {"event": "dns_query", "name": name,
                  "type": TYPE_NAMES.get(qtype, str(qtype)), "qtype": qtype}
        if qtype is None:
            record["result"] = "formato_inválido"
            return build_response(query, name or "", 1, [], rcode=RCODE_SERVFAIL), record

        if not self.is_watched(name):
            record["watched"] = False
            if self.no_upstream or not self.upstream:
                record["result"] = "recusado_sem_upstream"
                return build_response(query, name, qtype, [], rcode=RCODE_REFUSED), record
            resp = forward(query, self.upstream)
            if resp is None:
                record["result"] = "upstream_sem_resposta"
                return build_response(query, name, qtype, [], rcode=RCODE_SERVFAIL), record
            record["result"] = "encaminhado"
            return resp, record

        record["watched"] = True
        ip = self.local_ip(name)
        if qtype == TYPE_A and ip:
            record["result"] = "autoritativo_a"
            record["answer"] = ip
            return build_response(query, name, qtype, [(TYPE_A, ipv4_to_bytes(ip))],
                                  ttl=self.ttl), record
        if qtype == TYPE_AAAA:
            if self.aaaa == "nodata":
                record["result"] = "nodata_ipv6"
                return build_response(query, name, qtype, [], ttl=self.ttl), record
            if self.aaaa == "forward" and self.upstream and not self.no_upstream:
                resp = forward(query, self.upstream)
                if resp is not None:
                    record["result"] = "ipv6_encaminhado"
                    return resp, record
            record["result"] = "nodata_ipv6"
            return build_response(query, name, qtype, [], ttl=self.ttl), record
        # HTTPS/SVCB e outros tipos: sem dados (não inventamos registros)
        record["result"] = "nodata_outro_tipo"
        return build_response(query, name, qtype, [], ttl=self.ttl), record


class DnsLogger:
    def __init__(self, path=None, log_all=False, quiet=False):
        self.path = path
        self.log_all = log_all
        self.quiet = quiet
        self.counts = {}
        self._lock = threading.Lock()
        if self.path:
            os.makedirs(os.path.dirname(os.path.abspath(self.path)) or ".", exist_ok=True)

    def log(self, record, peer=None):
        record = dict(record)
        record.setdefault("ts", iso())
        if peer:
            record["client"] = peer[0]
        watched = record.get("watched", False)
        with self._lock:
            key = record.get("name") if (watched or self.log_all) else "<outras>"
            self.counts[key] = self.counts.get(key, 0) + 1
            if watched or self.log_all:
                if self.path:
                    with open(self.path, "a", encoding="utf-8") as f:
                        f.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
                if not self.quiet:
                    print("[dns] " + json.dumps(record, ensure_ascii=False, sort_keys=True), flush=True)

    def summary(self):
        with self._lock:
            return dict(sorted(self.counts.items(), key=lambda kv: -kv[1]))


class DnsUDPHandler(socketserver.BaseRequestHandler):
    resolver = None
    logger = None

    def handle(self):
        data, sock = self.request
        try:
            resp, record = self.resolver.answer(data)
            sock.sendto(resp, self.client_address)
        except Exception as exc:
            record = {"event": "dns_error", "error": str(exc)[:200]}
            resp = None
        self.logger.log(record, peer=self.client_address)


class DnsTCPHandler(socketserver.BaseRequestHandler):
    resolver = None
    logger = None

    def handle(self):
        try:
            raw = self.request.recv(2)
            if len(raw) < 2:
                return
            (length,) = struct.unpack("!H", raw)
            data = b""
            while len(data) < length:
                chunk = self.request.recv(length - len(data))
                if not chunk:
                    return
                data += chunk
            resp, record = self.resolver.answer(data)
            self.request.sendall(struct.pack("!H", len(resp)) + resp)
        except Exception as exc:
            record = {"event": "dns_error", "error": str(exc)[:200]}
        self.logger.log(record, peer=self.client_address)


class ThreadingUDPServer(socketserver.ThreadingMixIn, socketserver.UDPServer):
    daemon_threads = True
    allow_reuse_address = True


class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    daemon_threads = True
    allow_reuse_address = True


def parse_mapping(value):
    if "=" not in value:
        raise argparse.ArgumentTypeError("use NOME=IP (ex.: prod.cdni.callofduty.com=192.168.0.10)")
    k, v = value.split("=", 1)
    return k.strip().lower(), v.strip()


# ------------------------------------------------------------------ self-test

def _query(server_addr, name, qtype=TYPE_A, timeout=3.0, tcp=False):
    qid = 0x4242
    header = struct.pack("!HHHHHH", qid, 0x0100, 1, 0, 0, 0)
    question = encode_name(name) + struct.pack("!HH", qtype, CLASS_IN)
    msg = header + question
    if tcp:
        s = socket.create_connection(server_addr, timeout=timeout)
        s.sendall(struct.pack("!H", len(msg)) + msg)
        raw = s.recv(2)
        (length,) = struct.unpack("!H", raw)
        data = b""
        while len(data) < length:
            data += s.recv(length - len(data))
        s.close()
        return data
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    s.sendto(msg, server_addr)
    data, _ = s.recvfrom(4096)
    s.close()
    return data


def _rcode(data):
    return struct.unpack_from("!H", data, 2)[0] & 0x0F


def _ancount(data):
    return struct.unpack_from("!H", data, 6)[0]


def _answers(data):
    """Retorna [(tipo, rdata)] lendo do fim do cabeçalho+pergunta (usa ponteiro 0xC00C)."""
    out = []
    n = struct.unpack_from("!H", data, 6)[0]
    # pergunta começa em 12; localiza o fim dela
    i = 12
    while i < len(data) and data[i] != 0:
        i += 1 + data[i]
    i += 5
    for _ in range(n):
        # nome: ponteiro de 2 bytes (nós sempre emitimos 0xC00C)
        if i + 2 > len(data):
            break
        assert data[i] & 0xC0 == 0xC0, "esperava ponteiro de nome"
        i += 2
        atype, aclass, ttl, rdlen = struct.unpack_from("!HHIH", data, i)
        i += 10
        out.append((atype, data[i:i + rdlen]))
        i += rdlen
    return out


def self_test():
    print("== self-test wzm_dns ==")
    resolver = Resolver(
        hosts={"prod.cdni.callofduty.com": "192.168.0.10"},
        wildcards={"cdni.callofduty.com": "192.168.0.10"},
        upstream=None, no_upstream=True, aaaa="nodata")
    logger = DnsLogger(path=None, log_all=True, quiet=True)

    udp = ThreadingUDPServer(("127.0.0.1", 0),
                             type("H", (DnsUDPHandler,), {"resolver": resolver, "logger": logger}))
    addr = udp.server_address
    threading.Thread(target=udp.serve_forever, daemon=True).start()

    tcp = ThreadingTCPServer(("127.0.0.1", 0),
                             type("H", (DnsTCPHandler,), {"resolver": resolver, "logger": logger}))
    taddr = tcp.server_address
    threading.Thread(target=tcp.serve_forever, daemon=True).start()

    # 1) nome exato -> A para o servidor local
    r = _query(addr, "prod.cdni.callofduty.com", TYPE_A)
    assert _rcode(r) == RCODE_NOERROR and _ancount(r) == 1, (r.hex(), _rcode(r), _ancount(r))
    atype, rdata = _answers(r)[0]
    assert atype == TYPE_A and socket.inet_ntoa(rdata) == "192.168.0.10", rdata
    print("  [ok] prod.cdni.callofduty.com A -> 192.168.0.10 (UDP)")

    # 2) curinga
    r = _query(addr, "qualquer.coisa.cdni.callofduty.com", TYPE_A)
    assert _ancount(r) == 1 and socket.inet_ntoa(_answers(r)[0][1]) == "192.168.0.10"
    print("  [ok] curinga *.cdni.callofduty.com -> 192.168.0.10")

    # 3) AAAA -> NOERROR sem resposta (cliente cai para IPv4)
    r = _query(addr, "prod.cdni.callofduty.com", TYPE_AAAA)
    assert _rcode(r) == RCODE_NOERROR and _ancount(r) == 0
    print("  [ok] AAAA -> NOERROR/0 respostas (sem inventar IPv6)")

    # 4) nome não vigiado, sem upstream -> REFUSED (não mente)
    r = _query(addr, "example.com", TYPE_A)
    assert _rcode(r) == RCODE_REFUSED, _rcode(r)
    print("  [ok] nome não vigiado sem upstream -> REFUSED (comportamento explícito)")

    # 5) TCP
    r = _query(taddr, "prod.cdni.callofduty.com", TYPE_A, tcp=True)
    assert _ancount(r) == 1 and socket.inet_ntoa(_answers(r)[0][1]) == "192.168.0.10"
    print("  [ok] mesma resposta por TCP/53")

    # 6) contadores de log
    summary = logger.summary()
    assert summary.get("prod.cdni.callofduty.com", 0) >= 3, summary
    print("  [ok] log/contadores: %s" % summary)

    udp.shutdown(); udp.server_close()
    tcp.shutdown(); tcp.server_close()
    print("self-test OK")
    return 0


# ----------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description="DNS local do WZM: responde os hostnames do jogo com o IP do servidor local")
    ap.add_argument("--bind", default="127.0.0.1", help="endereço de escuta (padrão 127.0.0.1)")
    ap.add_argument("--port", type=int, default=53, help="porta (padrão 53; exige root/cap no Linux)")
    ap.add_argument("--host", action="append", default=[], metavar="NOME=IP",
                    help="nome exato apontando para o IP do servidor local (repetível)")
    ap.add_argument("--wildcard", action="append", default=[], metavar="SUFIXO=IP",
                    help=" curinga: qualquer nome sob SUFIXO (repetível)")
    ap.add_argument("--upstream", default="8.8.8.8",
                    help="para onde encaminhar o que não for nosso (padrão 8.8.8.8)")
    ap.add_argument("--no-upstream", action="store_true", help="recusa tudo o que não for nosso")
    ap.add_argument("--aaaa", choices=["nodata", "forward"], default="nodata",
                    help="AAAA dos nomes vigiados: nodata (padrão, força IPv4) ou forward")
    ap.add_argument("--ttl", type=int, default=30, help="TTL das respostas autoritativas")
    ap.add_argument("--log", default=os.path.join(HERE, "logs", "dns.jsonl"), help="JSONL de log")
    ap.add_argument("--log-all-queries", action="store_true",
                    help="registra também as consultas não vigiadas (privacidade: off por padrão)")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    pairs = [parse_mapping(v) for v in args.host]
    wpairs = [parse_mapping(v) for v in args.wildcard]
    if not pairs and not wpairs:
        raise SystemExit("informe ao menos um --host NOME=IP ou --wildcard SUFIXO=IP")

    resolver = Resolver(dict(pairs), dict(wpairs), None if args.no_upstream else args.upstream,
                        ttl=args.ttl, aaaa=args.aaaa, no_upstream=args.no_upstream)
    logger = DnsLogger(args.log, log_all=args.log_all_queries, quiet=args.quiet)

    udp = ThreadingUDPServer((args.bind, args.port),
                             type("H", (DnsUDPHandler,), {"resolver": resolver, "logger": logger}))
    tcp = ThreadingTCPServer((args.bind, args.port),
                             type("H", (DnsTCPHandler,), {"resolver": resolver, "logger": logger}))
    threading.Thread(target=udp.serve_forever, daemon=True).start()
    threading.Thread(target=tcp.serve_forever, daemon=True).start()

    print("DNS local escutando em %s:%d" % (args.bind, args.port), flush=True)
    for name, ip in sorted(dict(pairs).items()):
        print("  %s -> %s" % (name, ip), flush=True)
    for suffix, ip in sorted(dict(wpairs).items()):
        print("  *.%s -> %s" % (suffix, ip), flush=True)
    print("  upstream=%s  aaaa=%s" % ("(nenhum)" if args.no_upstream else args.upstream, args.aaaa),
          flush=True)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nconsultas por nome: %s" % logger.summary(), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
