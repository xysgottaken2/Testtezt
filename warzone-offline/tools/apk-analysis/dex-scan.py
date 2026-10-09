#!/usr/bin/env python3
# dex-scan.py — localizador de strings/endpoints em classes*.dex (M5.0) — read-only, Python puro
#
# Pergunta que este scanner responde:
#     "Quais classes/métodos do WZM 3.10.0 referenciam tal endpoint/host/chave,
#      e que outras strings aquele método usa?"
#
# Ele NÃO descompila: faz parse do DEX (cabeçalho, string/type/proto/method ids,
# class_defs, class_data_item, code_item) e caminha pelas instruções coletando
# `const-string*`, `invoke-*` e `new-instance`. Isso basta para mapear
# MetaFetchTask -> cdni.meta -> próximas strings, sem um descompilador.
#
# Garantias (regras do projeto):
#   * read-only: não modifica, não copia e não distribui o APK/DEX; nenhuma rede;
#   * nunca imprime payload proprietário inteiro: strings são truncadas em
#     --max-len (padrão 120) e há --redact para suprimir valores sensíveis;
#   * nenhum binário do jogo é gravado no repositório.
#
# Uso:
#   python3 dex-scan.py --dir /caminho/extraido/do/apk            # procura classes*.dex
#   python3 dex-scan.py --apk /caminho/base.apk                   # lê os dex do zip (sem extrair)
#   python3 dex-scan.py --dex classes.dex classes2.dex
#   python3 dex-scan.py --dir . --class-filter preenginetasks --dump-strings
#   python3 dex-scan.py --dir . --search cdni.meta --search pre-login.json --json /tmp/wzm/dex.json
#   python3 dex-scan.py --self-test
#
# Termos padrão de busca (podem ser substituídos por --search):
#   MetaFetchTask, cdni.meta, prod.cdni.callofduty.com, pre-login.json, build-selector,
#   manifest.json, environment.config, cdni_manifest, download, update, content, shard

import argparse
import io
import json
import os
import re
import struct
import sys
import zipfile

MAX_LEN_DEFAULT = 120

DEFAULT_TERMS = [
    "MetaFetchTask",
    "cdni.meta",
    "prod.cdni.callofduty.com",
    "pre-login.json",
    "build-selector",
    "manifest.json",
    "environment.config",
    "cdni_manifest",
    "download",
    "update",
    "content",
    "shard",
]

# ---------------------------------------------------------------- leitores base

def read_uleb128(data, off):
    """Lê um LEB128 sem sinal. Retorna (valor, novo_offset)."""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise ValueError("uleb128 truncado")
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7
        if shift > 63:
            raise ValueError("uleb128 longo demais")

def read_sleb128(data, off):
    """Lê um LEB128 com sinal. Retorna (valor, novo_offset)."""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise ValueError("sleb128 truncado")
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            if b & 0x40:
                result -= 1 << shift
            return result, off
        if shift > 63:
            raise ValueError("sleb128 longo demais")

def decode_mutf8(data):
    """Decodifica MUTF-8 (Modified UTF-8) -> str (pares surrogados são unidos)."""
    out = []
    pending_high = None
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if b == 0x00:
            break
        if b < 0x80:
            cp = b
            i += 1
        elif b & 0xE0 == 0xC0:
            if i + 1 >= n:
                break
            cp = ((b & 0x1F) << 6) | (data[i + 1] & 0x3F)
            i += 2
        elif b & 0xF0 == 0xE0:
            if i + 2 >= n:
                break
            cp = ((b & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F)
            i += 3
        else:
            cp = b
            i += 1
        if pending_high is not None:
            if 0xDC00 <= cp <= 0xDFFF:
                out.append(chr(0x10000 + ((pending_high - 0xD800) << 10) + (cp - 0xDC00)))
                pending_high = None
                continue
            out.append(chr(pending_high))
            pending_high = None
        if 0xD800 <= cp <= 0xDBFF:
            pending_high = cp
        else:
            out.append(chr(cp))
    if pending_high is not None:
        out.append(chr(pending_high))
    return "".join(out)

# ------------------------------------------------------- tamanhos de instrução

def _fill(table, lo, hi, size):
    for op in range(lo, hi + 1):
        table[op] = size

def build_insn_size_table():
    """Tamanho em unidades de 16 bits de cada opcode Dalvik."""
    t = [1] * 256
    _fill(t, 0x00, 0x00, 1)     # nop
    _fill(t, 0x01, 0x0D, 1)     # move*, move-result*, move-exception
    _fill(t, 0x0E, 0x11, 1)     # return*
    _fill(t, 0x12, 0x12, 1)     # const/4
    _fill(t, 0x13, 0x13, 2)     # const/16
    _fill(t, 0x14, 0x14, 3)     # const
    _fill(t, 0x15, 0x15, 2)     # const/high16
    _fill(t, 0x16, 0x16, 2)     # const-wide/16
    _fill(t, 0x17, 0x17, 3)     # const-wide/32
    _fill(t, 0x18, 0x18, 5)     # const-wide
    _fill(t, 0x19, 0x19, 2)     # const-wide/high16
    _fill(t, 0x1A, 0x1A, 2)     # const-string
    _fill(t, 0x1B, 0x1B, 3)     # const-string/jumbo
    _fill(t, 0x1C, 0x1C, 2)     # const-class
    _fill(t, 0x1D, 0x1E, 1)     # monitor-enter/exit
    _fill(t, 0x1F, 0x1F, 2)     # check-cast
    _fill(t, 0x20, 0x20, 2)     # instance-of
    _fill(t, 0x21, 0x21, 1)     # array-length
    _fill(t, 0x22, 0x22, 2)     # new-instance
    _fill(t, 0x23, 0x23, 2)     # new-array
    _fill(t, 0x24, 0x24, 3)     # filled-new-array
    _fill(t, 0x25, 0x25, 3)     # filled-new-array/range
    _fill(t, 0x26, 0x26, 3)     # fill-array-data
    _fill(t, 0x27, 0x27, 1)     # throw
    _fill(t, 0x28, 0x28, 1)     # goto
    _fill(t, 0x29, 0x29, 2)     # goto/16
    _fill(t, 0x2A, 0x2A, 3)     # goto/32
    _fill(t, 0x2B, 0x2C, 3)     # packed-switch / sparse-switch
    _fill(t, 0x2D, 0x31, 2)     # cmp*
    _fill(t, 0x32, 0x37, 2)     # if-*
    _fill(t, 0x38, 0x3D, 2)     # if-*z
    _fill(t, 0x3E, 0x43, 1)     # (não usados)
    _fill(t, 0x44, 0x51, 2)     # arrayop (aget*..aput*)
    _fill(t, 0x52, 0x5F, 2)     # i*get*/i*put* (instance)
    _fill(t, 0x60, 0x6D, 2)     # s*get*/s*put* (static)
    _fill(t, 0x6E, 0x72, 3)     # invoke-* (kind)
    _fill(t, 0x73, 0x73, 1)     # (não usado)
    _fill(t, 0x74, 0x78, 3)     # invoke-*/range
    _fill(t, 0x79, 0x7A, 1)     # (não usados)
    _fill(t, 0x7B, 0x8F, 1)     # unop (neg-*, not-*, *-to-*)
    _fill(t, 0x90, 0xAF, 2)     # binop
    _fill(t, 0xB0, 0xCF, 1)     # binop/2addr
    _fill(t, 0xD0, 0xD7, 2)     # binop/lit16
    _fill(t, 0xD8, 0xDF, 2)     # binop/lit8
    _fill(t, 0xE0, 0xF9, 1)     # (não usados)
    _fill(t, 0xFA, 0xFA, 4)     # invoke-polymorphic
    _fill(t, 0xFB, 0xFB, 3)     # invoke-polymorphic/range
    _fill(t, 0xFC, 0xFF, 1)     # (não usados)
    return t

INSN_SIZE = build_insn_size_table()

INVOKE_KINDS = {
    0x6E: "invoke-virtual",
    0x6F: "invoke-super",
    0x70: "invoke-direct",
    0x71: "invoke-static",
    0x72: "invoke-interface",
    0x74: "invoke-virtual/range",
    0x75: "invoke-super/range",
    0x76: "invoke-direct/range",
    0x77: "invoke-static/range",
    0x78: "invoke-interface/range",
    0xFA: "invoke-polymorphic",
    0xFB: "invoke-polymorphic/range",
}

# ------------------------------------------------------------------ parse DEX

class DexFile:
    def __init__(self, data, name):
        self.data = data
        self.name = name
        self._parse()

    def _u4(self, off):
        return struct.unpack_from("<I", self.data, off)[0]

    def _u2(self, off):
        return struct.unpack_from("<H", self.data, off)[0]

    def _parse(self):
        d = self.data
        if len(d) < 0x70:
            raise ValueError("arquivo pequeno demais para ser DEX")
        magic = d[0:8]
        if not magic.startswith(b"dex\n"):
            raise ValueError("magic DEX inválido: %r" % magic[:8])
        self.endian_tag = self._u4(0x28)
        if self.endian_tag != 0x12345678:
            raise ValueError("endian_tag inesperado (DEX big-endian não suportado)")
        self.file_size = self._u4(0x20)
        self.map_off = self._u4(0x34)
        self.string_ids_size = self._u4(0x38)
        self.string_ids_off = self._u4(0x3C)
        self.type_ids_size = self._u4(0x40)
        self.type_ids_off = self._u4(0x44)
        self.proto_ids_size = self._u4(0x48)
        self.proto_ids_off = self._u4(0x4C)
        self.field_ids_size = self._u4(0x50)
        self.field_ids_off = self._u4(0x54)
        self.method_ids_size = self._u4(0x58)
        self.method_ids_off = self._u4(0x5C)
        self.class_defs_size = self._u4(0x60)
        self.class_defs_off = self._u4(0x64)
        self.strings = self._parse_strings()
        self.types = self._parse_types()
        self.method_ids = self._parse_method_ids()
        self.field_ids = self._parse_field_ids()
        self.classes = self._parse_class_defs()

    # -- tabelas
    def _parse_strings(self):
        out = []
        for i in range(self.string_ids_size):
            off = self._u4(self.string_ids_off + i * 4)
            # string_data_item: uleb128 utf16_size + MUTF-8 terminada em 0
            _, p = read_uleb128(self.data, off)
            end = self.data.find(b"\x00", p)
            if end < 0:
                end = len(self.data)
            out.append(decode_mutf8(self.data[p:end]))
        return out

    def _parse_types(self):
        return [self.strings[self._u4(self.type_ids_off + i * 4)]
                if self._u4(self.type_ids_off + i * 4) < len(self.strings) else "?"
                for i in range(self.type_ids_size)]

    def _parse_method_ids(self):
        out = []
        for i in range(self.method_ids_size):
            base = self.method_ids_off + i * 8
            class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
            cls = self.types[class_idx] if class_idx < len(self.types) else "?"
            name = self.strings[name_idx] if name_idx < len(self.strings) else "?"
            out.append((cls, name))
        return out

    def _parse_field_ids(self):
        out = []
        for i in range(self.field_ids_size):
            base = self.field_ids_off + i * 8
            class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
            cls = self.types[class_idx] if class_idx < len(self.types) else "?"
            typ = self.types[type_idx] if type_idx < len(self.types) else "?"
            name = self.strings[name_idx] if name_idx < len(self.strings) else "?"
            out.append((cls, name, typ))
        return out

    def _parse_class_defs(self):
        out = []
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            (class_idx, access_flags, super_idx, _iface_off,
             src_idx, _anno_off, class_data_off, _static_off) = struct.unpack_from("<8I", self.data, base)
            out.append({
                "name": self.types[class_idx] if class_idx < len(self.types) else "?",
                "super": self.types[super_idx] if 0xFFFFFFFF > super_idx and super_idx < len(self.types) else None,
                "source_file": self.strings[src_idx] if src_idx != 0xFFFFFFFF and src_idx < len(self.strings) else None,
                "access_flags": access_flags,
                "class_data_off": class_data_off,
                "index": i,
            })
        return out

    # -- métodos de uma classe
    def class_methods(self, cdef):
        """Retorna [{name, proto_idx, access_flags, code_off, kind}] em ordem do class_data."""
        off = cdef["class_data_off"]
        if off == 0:
            return []
        d = self.data
        p = off
        n_static, p = read_uleb128(d, p)
        n_instance, p = read_uleb128(d, p)
        n_direct, p = read_uleb128(d, p)
        n_virtual, p = read_uleb128(d, p)
        for _ in range(n_static + n_instance):          # encoded_field
            _, p = read_uleb128(d, p)
            _, p = read_uleb128(d, p)
        methods = []
        idx = 0
        for kind, count in (("direct", n_direct), ("virtual", n_virtual)):
            for _ in range(count):
                diff, p = read_uleb128(d, p)
                flags, p = read_uleb128(d, p)
                code_off, p = read_uleb128(d, p)
                idx += diff
                cls, name = self.method_ids[idx] if idx < len(self.method_ids) else ("?", "?")
                methods.append({
                    "class": cls, "name": name, "access_flags": flags,
                    "code_off": code_off, "kind": kind, "method_idx": idx,
                })
        return methods

    # -- caminhada nas instruções de um code_item
    def scan_code(self, code_off):
        """Retorna (strings_idx, invoked_method_idx, type_idx, line_hits)."""
        d = self.data
        if code_off == 0 or code_off + 16 > len(d):
            return [], [], [], []
        (registers, ins, outs, tries, debug_off, insns_size) = struct.unpack_from("<4HII", d, code_off)
        insns_off = code_off + 16
        end = insns_off + insns_size * 2
        if end > len(d):
            end = len(d)
        s_idx, m_idx, t_idx = [], [], []
        pos = insns_off
        while pos + 2 <= end:
            raw = struct.unpack_from("<H", d, pos)[0]
            if raw in (0x0100, 0x0200, 0x0300):        # payload de switch/array
                if raw == 0x0100:                       # packed-switch
                    count = struct.unpack_from("<H", d, pos + 2)[0]
                    pos += 4 + count * 4
                elif raw == 0x0200:                     # sparse-switch
                    count = struct.unpack_from("<H", d, pos + 2)[0]
                    pos += 4 + count * 8
                else:                                   # fill-array-data
                    width = struct.unpack_from("<H", d, pos + 2)[0]
                    size = struct.unpack_from("<I", d, pos + 4)[0]
                    pos += 8 + ((size * width + 1) // 2) * 2
                continue
            op = raw & 0xFF
            size = INSN_SIZE[op]
            if pos + size * 2 > end:
                break
            if op == 0x1A:                              # const-string
                s_idx.append(struct.unpack_from("<H", d, pos + 2)[0])
            elif op == 0x1B:                            # const-string/jumbo
                s_idx.append(struct.unpack_from("<I", d, pos + 2)[0])
            elif op in INVOKE_KINDS or op == 0x1C or op == 0x22 or op == 0x1F or op == 0x20 or op == 0x23:
                m_idx.append(struct.unpack_from("<H", d, pos + 2)[0])
            elif 0x52 <= op <= 0x6D:                    # field access
                t_idx.append(struct.unpack_from("<H", d, pos + 2)[0])
            pos += size * 2
        return s_idx, m_idx, t_idx, []

    def method_body_strings(self, code_off):
        s_idx, _, _, _ = self.scan_code(code_off)
        out = []
        for i in s_idx:
            if i < len(self.strings):
                out.append(self.strings[i])
        return out

    def method_invokes(self, code_off):
        _, m_idx, _, _ = self.scan_code(code_off)
        out = []
        for i in m_idx:
            if i < len(self.method_ids):
                out.append(self.method_ids[i])
        return out


# ------------------------------------------------------------------- utilidades

def truncate(s, max_len):
    if max_len <= 0 or len(s) <= max_len:
        return s
    return s[:max_len] + "…(+%d)" % (len(s) - max_len)

REDACT_RE = re.compile(
    r"(?i)(authorization|cookie|token|secret|password|passwd|api[-_]?key|bearer|session)")

def redact(s, enabled):
    if not enabled:
        return s
    return "<redacted>" if REDACT_RE.search(s) else s

def load_inputs(args):
    """Retorna lista de (nome, bytes) de DEX a partir de --dex/--dir/--apk."""
    out = []
    if args.dex:
        for p in args.dex:
            with open(p, "rb") as f:
                out.append((os.path.basename(p), f.read()))
    if args.apk:
        with zipfile.ZipFile(args.apk) as z:
            for n in sorted(z.namelist()):
                if n.endswith(".dex") and n.startswith("classes"):
                    out.append((os.path.basename(args.apk) + "!" + n, z.read(n)))
    if args.dir:
        root = args.dir
        for name in sorted(os.listdir(root)):
            if re.match(r"^classes\d*\.dex$", name):
                with open(os.path.join(root, name), "rb") as f:
                    out.append((name, f.read()))
        # subdiretório comum de extração de split apks
        for sub in os.listdir(root):
            path = os.path.join(root, sub)
            if os.path.isdir(path):
                for name in sorted(os.listdir(path)):
                    if re.match(r"^classes\d*\.dex$", name):
                        with open(os.path.join(path, name), "rb") as f:
                            out.append((sub + "/" + name, f.read()))
    return out

def pretty_class(raw):
    return raw

def pretty_method(cls, name):
    return "%s->%s" % (cls, name)


# ---------------------------------------------------------------------- saída

def run_list_classes(dexes, args):
    rx = re.compile(args.class_filter, re.I) if args.class_filter else None
    total = 0
    for dex in dexes:
        for c in dex.classes:
            if rx and not rx.search(c["name"]):
                continue
            total += 1
            print("%s  (dex=%s, super=%s, src=%s)"
                  % (pretty_class(c["name"]), dex.name, c["super"], c["source_file"]))
    print("\n-- %d classe(s) listada(s) em %d dex" % (total, len(dexes)))
    return total


def iter_methods(dexes):
    for dex in dexes:
        for c in dex.classes:
            try:
                methods = dex.class_methods(c)
            except Exception:
                methods = []
            for m in methods:
                yield dex, c, m


def run_search(dexes, args, json_sink):
    terms = args.search if args.search else DEFAULT_TERMS
    lowered = [(t, t.lower()) for t in terms]
    class_rx = re.compile(args.class_filter, re.I) if args.class_filter else None
    hits = {t: [] for t, _ in lowered}
    scanned = 0
    for dex, c, m in iter_methods(dexes):
        if class_rx and not (class_rx.search(c["name"]) or class_rx.search(m["class"])):
            continue
        scanned += 1
        if m["code_off"] == 0:
            continue
        body = dex.method_body_strings(m["code_off"])
        if not body:
            continue
        blob = "\n".join(body).lower()
        for term, low in lowered:
            if low in blob:
                matched = [truncate(s, args.max_len) for s in body if low in s.lower()]
                hits[term].append({
                    "dex": dex.name,
                    "class": m["class"],
                    "method": m["name"],
                    "kind": m["kind"],
                    "code_off": hex(m["code_off"]),
                    "matched_strings": [redact(s, args.redact) for s in matched[: args.max_per_method]],
                })
    for term, _ in lowered:
        lst = hits[term]
        print("\n=== %r — %d método(s) ===" % (term, len(lst)))
        for h in lst[: args.limit]:
            print("  %s" % pretty_method(h["class"], h["method"]))
            print("      dex=%s code_off=%s (%s)" % (h["dex"], h["code_off"], h["kind"]))
            for s in h["matched_strings"]:
                print("      · %s" % s)
        if len(lst) > args.limit:
            print("  … %d outro(s) omitido(s) (use --limit)" % (len(lst) - args.limit))
    if json_sink is not None:
        json_sink["search"] = {"terms": terms, "methods_scanned": scanned,
                               "hits": {k: v for k, v in hits.items() if v}}


def run_dump_strings(dexes, args, json_sink):
    rx = re.compile(args.dump_strings, re.I)
    printed = 0
    dump = []
    for dex, c, m in iter_methods(dexes):
        if not (rx.search(c["name"]) or rx.search(m["class"])):
            continue
        body = dex.method_body_strings(m["code_off"]) if m["code_off"] else []
        calls = dex.method_invokes(m["code_off"]) if m["code_off"] else []
        interesting = [pretty_method(a, b) for a, b in calls if rx.search(a)]
        print("\n--- %s  [%s, dex=%s, code_off=%s]"
              % (pretty_method(m["class"], m["name"]), m["kind"], dex.name,
                 hex(m["code_off"]) if m["code_off"] else "-"))
        if body:
            print("    strings:")
            for s in body[: args.max_per_method]:
                print("      · %s" % redact(truncate(s, args.max_len), args.redact))
        if interesting:
            print("    chama (mesmo filtro):")
            for s in interesting[: args.max_per_method]:
                print("      → %s" % s)
        printed += 1
        dump.append({"dex": dex.name, "class": m["class"], "method": m["name"],
                     "strings": [truncate(s, args.max_len) for s in body],
                     "calls_same_filter": interesting})
    print("\n-- %d método(s) dumpado(s)" % printed)
    if json_sink is not None:
        json_sink["dump"] = dump


def run_stats(dexes, args):
    print("dex carregado(s): %d" % len(dexes))
    for dex in dexes:
        print("  %-40s strings=%d types=%d methods=%d fields=%d classes=%d"
              % (dex.name, len(dex.strings), len(dex.types), len(dex.method_ids),
                 len(dex.field_ids), len(dex.classes)))


# ------------------------------------------------------------------- self-test

def self_test():
    # 1) uleb128 / sleb128
    assert read_uleb128(b"\x00", 0) == (0, 1)
    assert read_uleb128(b"\x7f", 0) == (0x7F, 1)
    assert read_uleb128(b"\x80\x01", 0) == (0x80, 2)
    assert read_uleb128(b"\xff\xff\x7f", 0) == (0x1FFFFF, 3)
    assert read_uleb128(b"\x80\x80\x80\x80\x08", 0) == (0x80000000, 5)
    assert read_sleb128(b"\x7f", 0) == (-1, 1)
    assert read_sleb128(b"\x00", 0) == (0, 1)
    # 2) MUTF-8
    assert decode_mutf8(b"abc") == "abc"
    assert decode_mutf8(b"a\xc0\x80b") == "a\x00b"
    assert decode_mutf8(b"\xe4\xb8\xad") == "中"
    # par surrogado (emoji) deve virar um único codepoint
    emoji = decode_mutf8(b"\xed\xa0\xbd\xed\xb8\x80")
    assert len(emoji) == 1 and ord(emoji) == 0x1F600, repr(emoji)
    # 3) tabela de opcodes
    assert INSN_SIZE[0x1A] == 2 and INSN_SIZE[0x1B] == 3 and INSN_SIZE[0x18] == 5
    assert INSN_SIZE[0x71] == 3 and INSN_SIZE[0x0E] == 1
    # 4) payloads
    d = struct.pack("<HH", 0x0100, 2) + b"\x00" * 8
    assert d[0:2] == b"\x00\x01"
    print("self-test OK (uleb128, sleb128, MUTF-8, tabela de opcodes, payloads)")
    return 0


# ------------------------------------------------------------------------ main

def main(argv=None):
    # nunca quebrar por causa de um codepoint inesperado vindo do DEX
    try:
        sys.stdout.reconfigure(errors="backslashreplace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(
        description="Localiza strings/endpoints em classes*.dex (parse puro em Python, sem descompilar).",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dex", nargs="*", help="arquivo(s) .dex")
    ap.add_argument("--apk", help="APK; lê classes*.dex do zip sem extrair")
    ap.add_argument("--dir", help="diretório já extraído (procura classes*.dex)")
    ap.add_argument("--search", nargs="*", help="termos de busca (padrão: lista do WZM)")
    ap.add_argument("--class-filter", help="regex aplicada ao nome da classe (pré-filtro)")
    ap.add_argument("--dump-strings", metavar="REGEX",
                    help="dumpa strings e chamadas dos métodos cuja classe casa com REGEX")
    ap.add_argument("--list-classes", action="store_true", help="lista classes (use com --class-filter)")
    ap.add_argument("--stats", action="store_true", help="mostra contagens dos dex")
    ap.add_argument("--limit", type=int, default=40, help="máx. de métodos por termo (padrão 40)")
    ap.add_argument("--max-per-method", type=int, default=25, help="máx. de strings por método")
    ap.add_argument("--max-len", type=int, default=MAX_LEN_DEFAULT, help="trunca strings neste tamanho")
    ap.add_argument("--redact", action="store_true", help="suprime valores que pareçam segredo")
    ap.add_argument("--json", help="grava o resultado em JSON (sem corpo de resposta, só referências)")
    ap.add_argument("--self-test", action="store_true", help="testa os decodificadores e sai")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    if not (args.dex or args.apk or args.dir):
        ap.error("informe --dex, --apk ou --dir (ou --self-test)")

    inputs = load_inputs(args)
    if not inputs:
        print("nenhum classes*.dex encontrado", file=sys.stderr)
        return 2

    dexes = []
    for name, blob in inputs:
        try:
            dexes.append(DexFile(blob, name))
        except Exception as exc:
            print("aviso: ignorando %s (%s)" % (name, exc), file=sys.stderr)

    if not dexes:
        return 2

    sink = {}
    if args.stats:
        run_stats(dexes, args)
    if args.list_classes:
        run_list_classes(dexes, args)
    if args.dump_strings:
        run_dump_strings(dexes, args, sink)
    if not (args.list_classes or args.dump_strings) or args.search:
        run_search(dexes, args, sink)

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(sink, f, ensure_ascii=False, indent=2)
        print("\nJSON gravado em %s" % args.json)
    return 0


if __name__ == "__main__":
    sys.exit(main())
