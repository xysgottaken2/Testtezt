#!/usr/bin/env python3
"""Decodificador DEX (Dalvik/ART) puro-stdlib, com auto-validação.

Existe porque o ambiente não tem `baksmali`/`dexdump`/`capstone`/java. Os formatos dos opcodes
0x00..0x78 vêm da fonte autoritativa (`org.jf.dexlib2.Opcode`, JesusFreke/smali); as *larguras*
de 0x79..0xff (que só interessam para manter o alinhamento) são calibradas contra o próprio DEX.

Validações (falha = se recusa a sustentar conclusões):
  V1 alinhamento: a caminhada de cada método termina exatamente em `insns_size`;
  V2 índices: todo índice de string/type/field/method cabe na sua tabela;
  V3 payload: switch/array detectados e ignorados;
  V4 plausibilidade: descritor de classe resolvido em todo invoke casa `^L[\w/$-]+;$`.

Modos:
  --stats                                  contagens + resultado das validações
  --smali DIR [--filter RE]                emite smali interno (para scripts/dex_bridge_scan.py)
  --xref-str TEXTO                         métodos que carregam a string dada (const-string)
  --callers Lcls; nome                     métodos que chamam cls;->nome
  --exposed                                métodos com anotação .../JavascriptInterface;
  --method CLASSE NOME                     desmontagem de um método
  --locks                                  usos de latch/semáforo/wait/notify/handler

O smali emitido é um formato interno: registradores são v0..vN (sem pN) e invoke/3rc é expandido.
"""
import struct, os, re, sys, argparse, collections

def chars_of(types):
    o = []
    for t in types:
        if not t or t == "?":
            o.append("?")
        elif t[0] in "L[":
            o.append("L")
        elif t in ("J", "D"):
            o.append("L")
        else:
            o.append(t[0].upper())
    return "".join(o)


W10 = {"10x":1,"12x":1,"11n":1,"11x":1,"10t":1,
       "20t":2,"22x":2,"21t":2,"21s":2,"21h":2,"21c":2,"23x":2,"22b":2,"22c":2,"22s":2,"22t":2,
       "30t":3,"31c":3,"31i":3,"31t":3,"32x":3,"35c":3,"3rc":3,"45cc":4,"4rcc":4,"51l":5}
OP = {}                                    # opcode -> (nome, formato, índice)


def _o(code, name, fmt, idx=None):
    OP[code] = (name, fmt, idx)


def _init_table():
    _o(0x00, "nop", "10x")
    for c, n, f in [(0x01,"move","12x"),(0x02,"move/from16","22x"),(0x03,"move/16","32x"),
                    (0x04,"move-wide","12x"),(0x05,"move-wide/from16","22x"),(0x06,"move-wide/16","32x"),
                    (0x07,"move-object","12x"),(0x08,"move-object/from16","22x"),(0x09,"move-object/16","32x"),
                    (0x0a,"move-result","11x"),(0x0b,"move-result-wide","11x"),
                    (0x0c,"move-result-object","11x"),(0x0d,"move-exception","11x"),
                    (0x0e,"return-void","10x"),(0x0f,"return","11x"),(0x10,"return-wide","11x"),
                    (0x11,"return-object","11x"),(0x12,"const/4","11n"),(0x13,"const/16","21s"),
                    (0x14,"const","31i"),(0x15,"const/high16","21h"),(0x16,"const-wide/16","21s"),
                    (0x17,"const-wide/32","31i"),(0x18,"const-wide","51l"),(0x19,"const-wide/high16","21h")]:
        _o(c, n, f)
    _o(0x1a, "const-string", "21c", "str");   _o(0x1b, "const-string/jumbo", "31c", "str")
    _o(0x1c, "const-class", "21c", "type");   _o(0x1d, "monitor-enter", "11x")
    _o(0x1e, "monitor-exit", "11x");           _o(0x1f, "check-cast", "21c", "type")
    _o(0x20, "instance-of", "22c", "type");    _o(0x21, "array-length", "12x")
    _o(0x22, "new-instance", "21c", "type");   _o(0x23, "new-array", "22c", "type")
    _o(0x24, "filled-new-array", "35c", "type"); _o(0x25, "filled-new-array/range", "3rc", "type")
    _o(0x26, "fill-array-data", "31t");        _o(0x27, "throw", "11x")
    _o(0x28, "goto", "10t"); _o(0x29, "goto/16", "20t"); _o(0x2a, "goto/32", "30t")
    _o(0x2b, "packed-switch", "31t"); _o(0x2c, "sparse-switch", "31t")
    for c, n in [(0x2d,"cmpl-float"),(0x2e,"cmpg-float"),(0x2f,"cmpl-double"),(0x30,"cmpg-double"),
                 (0x31,"cmp-long")]:
        _o(c, n, "23x")
    for i, n in enumerate(["if-eq","if-ne","if-lt","if-ge","if-gt","if-le"]):
        _o(0x32 + i, n, "22t")
    for i, n in enumerate(["if-eqz","if-nez","if-ltz","if-gez","if-gtz","if-lez"]):
        _o(0x38 + i, n, "21t")
    for i, n in enumerate(["aget","aget-wide","aget-object","aget-boolean","aget-byte","aget-char","aget-short"]):
        _o(0x44 + i, n, "23x")
    for i, n in enumerate(["aput","aput-wide","aput-object","aput-boolean","aput-byte","aput-char","aput-short"]):
        _o(0x4b + i, n, "23x")
    for i, n in enumerate(["iget","iget-wide","iget-object","iget-boolean","iget-byte","iget-char","iget-short"]):
        _o(0x52 + i, n, "22c", "field")
    for i, n in enumerate(["iput","iput-wide","iput-object","iput-boolean","iput-byte","iput-char","iput-short"]):
        _o(0x59 + i, n, "22c", "field")
    for i, n in enumerate(["sget","sget-wide","sget-object","sget-boolean","sget-byte","sget-char","sget-short"]):
        _o(0x60 + i, n, "21c", "field")
    for i, n in enumerate(["sput","sput-wide","sput-object","sput-boolean","sput-byte","sput-char","sput-short"]):
        _o(0x67 + i, n, "21c", "field")
    for i, n in enumerate(["invoke-virtual","invoke-super","invoke-direct","invoke-static","invoke-interface"]):
        _o(0x6e + i, n, "35c", "method")
    for i, n in enumerate(["invoke-virtual/range","invoke-super/range","invoke-direct/range",
                           "invoke-static/range","invoke-interface/range"]):
        _o(0x74 + i, n, "3rc", "method")
    for i, n in enumerate(["filled-new-array/range"]):
        pass


_init_table()
PAYLOAD_AFTER = {"packed-switch", "sparse-switch"}


class Dex:
    def __init__(self, path):
        self.path = path
        self.b = open(path, "rb").read()
        b = self.b
        if b[:4] != b"dex\n":
            raise SystemExit("não é DEX: " + path)
        h = struct.unpack_from("<14I", b, 0x38)
        (self.ssz, self.soff, self.tsz, self.toff, self.psz, self.poff,
         self.fsz, self.foff, self.msz, self.moff, self.cds, self.cdoff) = h[:12]
        self.endian = struct.unpack_from("<I", b, 0x28)[0]
        self._strings(); self._types(); self._protos(); self._fields(); self._methods(); self._classes()

    # ---------- tabelas ----------
    def uleb(self, p):
        r = s = 0
        b = self.b
        while True:
            x = b[p]; p += 1
            r |= (x & 0x7F) << s
            if not x & 0x80:
                return r, p
            s += 7

    def _strings(self):
        b, S = self.b, []
        for i in range(self.ssz):
            o = struct.unpack_from("<I", b, self.soff + 4 * i)[0]
            n, o2 = self.uleb(o)
            S.append(b[o2:o2 + n].decode("utf-8", "replace"))
        self.S = S

    def _types(self):
        self.T = [self.S[struct.unpack_from("<I", self.b, self.toff + 4 * i)[0]]
                  for i in range(self.tsz)]

    def _protos(self):
        """proto_id_item = { shorty_idx, return_type_idx, parameters_off }.
        Este dex tem type_lists em dois layouts (uleb128 clássico e entradas uint32, escritos
        por ferramenta de empacotamento). Cada proto é resolvido testando os dois candidatos e
        aceitando o cujo tipo casa o shorty; senão casa, marca '?'. (V7 reporta o placar.)"""
        self.P = []
        self.v7 = collections.Counter()
        for i in range(self.psz):
            sh_idx, ret_idx, par_off = struct.unpack_from("<III", self.b, self.poff + 12 * i)
            ret = self.T[ret_idx] if ret_idx < self.tsz else "?"
            shorty = self.S[sh_idx] if sh_idx < self.ssz else ""
            exp = shorty[1:]
            cands = []
            if par_off:
                a = []
                try:
                    n, q = self.uleb(par_off)
                    for _ in range(n):
                        t, q = self.uleb(q)
                        a.append(self.T[t] if t < self.tsz else "?")
                except Exception:
                    a = None
                b2 = []
                try:
                    n = struct.unpack_from("<I", self.b, par_off)[0]
                    for k in range(n):
                        t = struct.unpack_from("<I", self.b, par_off + 4 + 4 * k)[0]
                        b2.append(self.T[t] if t < self.tsz else "?")
                except Exception:
                    b2 = None
                c3 = []
                try:
                    n, q = self.uleb(par_off)
                    q = (q + 3) & ~3
                    for k in range(n):
                        t, q = self.uleb(q)
                        c3.append(self.T[t] if t < self.tsz else "?")
                except Exception:
                    c3 = None
                cands = [("uleb", a), ("u32", b2), ("uleb-align", c3)]
            best, score = None, -1
            if not exp:
                for tag, c in cands:
                    if c == [] or c is None:
                        best, score = [], 3
                        break
            if best is None:
                for tag, c in cands:
                    if not c:
                        continue
                    sc = 3 if chars_of(c) == exp else (1 if all(x != "?" for x in c) else 0)
                    if sc > score:
                        score, best, which = sc, c, tag
                if score == 3:
                    self.v7["casa-shorty:" + which] += 1
                elif best is not None:
                    self.v7["ambíguo"] += 1
                else:
                    self.v7["vazio"] += 1
                    best = []
            self.P.append((ret, best if best is not None else []))

    def _fields(self):
        self.F = [struct.unpack_from("<HHI", self.b, self.foff + 8 * i) for i in range(self.fsz)]
        self.FT = [(self.T[c], self.T[t], self.S[n]) for c, t, n in self.F
                   if c < self.tsz and t < self.tsz and n < self.ssz]

    def _methods(self):
        self.M = []
        for i in range(self.msz):
            c, p, n = struct.unpack_from("<HHI", self.b, self.moff + 8 * i)
            self.M.append((self.T[c] if c < self.tsz else "?", self.S[n] if n < self.ssz else "?", p))

    def v6(self):
        """anotações: todo method_idx marcado precisa pertencer à própria classe."""
        ok = bad = 0
        for d in self.korder:
            k = self.K[d]
            mine = {mi for mi, _a, _c in k["methods"]}
            for mi in k["mannot"]:
                if mi in mine:
                    ok += 1
                else:
                    bad += 1
        return ok, bad

    def _classes(self):
        """class_data: o PRIMEIRO idx_diff de métodos e de campos é ABSOLUTO (não delta
        da classe anterior). Este DEX tem class_defs fora de ordem, então qualquer
        acúmulo global deslocaria a atribuição classe<->método. Validado por V5."""
        self.K, self.korder = {}, []
        for i in range(self.cds):
            cidx, _af, sup, _itf, src, anndir, cdata, _sv = struct.unpack_from("<8I", self.b,
                                                                                self.cdoff + 32 * i)
            if cidx >= self.tsz:
                continue
            desc = self.T[cidx]
            if desc in self.K:          # classe duplicada: mantém a primeira
                continue
            self.K[desc] = dict(desc=desc, cidx=cidx, access=_af,
                                super=self.T[sup] if sup != 0xFFFFFFFF else None,
                                src=self.S[src] if src and src < self.ssz else None,
                                anndir=anndir, cdata=cdata, methods=[], fields=[], mannot={},
                                mrun=None, frun=None)
            self.korder.append(desc)
        # runs de method_ids/field_ids por classe (métodos são agrupados por class_idx)
        mlo = mhi = flo = fhi = None
        mlo, mhi = {}, {}
        for i in range(self.msz):
            c = struct.unpack_from("<H", self.b, self.moff + 8 * i)[0]
            mlo.setdefault(c, i); mhi[c] = i
        flo, fhi = {}, {}
        for i in range(len(self.F)):
            c = self.F[i][0]
            flo.setdefault(c, i); fhi[c] = i
        self.mlo = mlo
        self.mhi = mhi
        v5 = [0, 0]                     # [ok, inválido] — V5: dono de cada índice decodificado
        for d in self.korder:
            k = self.K[d]
            cidx = k["cidx"]
            cd = k["cdata"]
            if not cd:
                continue
            off = cd
            sf, off = self.uleb(off); ifl, off = self.uleb(off)
            dm, off = self.uleb(off); vm, off = self.uleb(off)
            # quatro cadeias independentes: statics, instâncias, directs, virtuais.
            # Em cada uma o PRIMEIRO idx_diff é ABSOLUTO (este dex tem class_defs fora de
            # ordem, então acumular entre classes deslocaria a atribuição — ver docs).
            for cnt in (sf, ifl):
                cur = 0
                for _ in range(cnt):
                    dd, off = self.uleb(off); acc, off = self.uleb(off)
                    cur += dd
                    if cur < len(self.FT) and self.FT[cur][0] == d:
                        v5[0] += 1
                    else:
                        v5[1] += 1
                    k["fields"].append((cur, acc))
            for cnt in (dm, vm):
                cur = 0
                for _ in range(cnt):
                    dd, off = self.uleb(off); acc, off = self.uleb(off); code, off = self.uleb(off)
                    cur += dd
                    if cur < self.msz and self.M[cur][0] == d:
                        v5[0] += 1
                    else:
                        v5[1] += 1
                    k["methods"].append((cur, acc, code))
            k["nmethods"] = dm + vm
        self.v5 = tuple(v5)
        for d in self.korder:
            self._annot(self.K[d])

    def _annot(self, k):
        """annotation_directory_item: sizes são uint32 e method_idx/field_idx são ABSOLUTOS.
        O tipo da anotação é lido do encoded_annotation_item (skipa element_values)."""
        ad = k["anndir"]
        k["clsannot"] = []
        if not ad:
            return
        try:
            ca_off, fsz, msz, csz = struct.unpack_from("<4I", self.b, ad)
            p = ad + 16
            for _ in range(fsz):
                p += 8                                   # field_idx, annotations_off
            for _ in range(msz):
                midx, aoff = struct.unpack_from("<II", self.b, p); p += 8
                if aoff:
                    k["mannot"][midx] = self.ann_types(aoff)
                    k["mannraw"] = k.get("mannraw", {})
                    k["mannraw"][midx] = aoff
            for _ in range(csz):
                p += 4
            if ca_off:
                k["clsannot"] = self.ann_types(ca_off)
        except Exception:
            pass

    def ann_types(self, off):
        """lista de tipos de anotação de um annotation_set_item (sem element_values)"""
        out = []
        n, q = struct.unpack_from("<I", self.b, off)[0], off + 4
        for _ in range(n):
            vis, q = self.uleb(q)
            t, q = self.uleb(q)
            if t < self.tsz:
                out.append(self.T[t])
            q = self.skip_elements(q)
            if q is None:
                break
        return out

    def skip_elements(self, p, _d=0):
        if _d > 6:
            return None
        b = self.b
        n, p = self.uleb(p)
        for _ in range(n):
            _name_idx, p = self.uleb(p)
            p = self._skip_value(p, _d)
            if p is None:
                return None
        return p

    def skip_value(self, p):
        return self._skip_value(p, 0)

    def _skip_value(self, p, d):
        if d > 6:
            return None
        b = self.b
        t = b[p]; p += 1
        sz = t >> 5
        arg = t & 0x1F
        if t == 0x1c or t == 0x00:                        # ANNOTATION / encoding zero
            p = self.uleb(p)[1]                          # type_idx
            return self.skip_elements(p, 1) if p else None
        if t == 0x1d:                                    # ARRAY
            cnt, p = self.uleb(p)
            for _ in range(cnt):
                p = self.skip_value(p)
                if p is None:
                    return None
            return p
        if arg in (0, 1, 2, 3, 4):                       # 0..(arg) bytes seguintes
            return p + arg + 1
        if t in (0x20, 0x24, 0x27):                      # false/true/null
            return p
        if sz in (0, 1, 2, 3, 4, 5, 6, 7):
            return p + sz + 1
        return p + 1

    # ---------- código ----------
    def marker_hits(self, type_desc, size_limit=192):
        """métodos cuja anotação contém `type_desc` com visibilidade RUNTIME e zero elementos
        (padrão de anotação-marcador, imune a falhas no skip de element_values)."""
        if type_desc not in self.T:
            return []
        pat = bytes([0x02]) + self.uleb_enc(self.T.index(type_desc)) + b"\x00"
        out = []
        for d in self.korder:
            k = self.K[d]
            for mi, aoff in sorted(k.get("mannraw", {}).items()):
                try:
                    n = struct.unpack_from("<I", self.b, aoff)[0]
                except Exception:
                    continue
                span = self.b[aoff + 4:aoff + 4 + min(size_limit, 8 + 48 * n)]
                if pat in span:
                    out.append((d, mi))
        return out

    @staticmethod
    def uleb_enc(n):
        o = bytearray()
        while True:
            c = n & 0x7F
            n >>= 7
            o.append(c | 0x80 if n else c)
            if not n:
                return bytes(o)

    def code_of(self, c):
        regs, _ins, _outs, _tries = struct.unpack_from("<4H", self.b, c)
        size = struct.unpack_from("<I", self.b, c + 12)[0]
        return c + 16, size, regs

    def payload_len(self, off, i):
        """se em [off+2i] houver um payload, devolve unidades consumidas"""
        b = self.b
        try:
            sig = struct.unpack_from("<H", b, off + 2 * i)[0]
        except Exception:
            return 0
        if sig == 0x0100:                       # sparse-switch-payload
            size = struct.unpack_from("<I", b, off + 2 * i + 2)[0]
            return 2 + 2 * size if size < 4096 else 0
        if sig == 0x0000:                       # packed-switch-payload
            size = struct.unpack_from("<I", b, off + 2 * i + 2)[0]
            return 2 + size if 0 < size < 4096 else 0
        if sig == 0x0300:                       # fill-array-data-payload
            ew = struct.unpack_from("<H", b, off + 2 * i + 2)[0]
            cnt = struct.unpack_from("<I", b, off + 2 * i + 4)[0]
            return 4 + (ew * cnt + 1) // 2 if ew in (1, 2, 4, 8) and cnt < 1 << 20 else 0
        return 0

    def walk(self, off, n, learn):
        b = self.b
        out = []
        i = 0
        bad = 0
        while i < n:
            pl = self.payload_len(off, i)
            if pl:
                out.append((i, 0, "payload", None, None, []))
                i += pl
                continue
            u = [struct.unpack_from("<H", b, off + 2 * min(j, n - 1))[0] for j in range(i, min(i + 5, n + 4))]
            op = u[0] & 0xFF
            if op in OP:
                name, fmt, idx = OP[op]
                wd = W10[fmt]
                val = None
                if idx == "str":
                    val = (u[1] << 16 | u[2]) if fmt == "31c" else u[1]
                    if not (0 <= val < self.ssz):
                        bad += 1; val = None
                elif idx in ("type", "field"):
                    val = u[1]
                    lim = self.tsz if idx == "type" else len(self.FT)
                    if not (0 <= val < lim):
                        bad += 1; val = None
                elif idx == "method":
                    val = u[1]
                    if not (0 <= val < self.msz):
                        bad += 1; val = None
                regs = self.regs_of(fmt, u)
                out.append((i, op, name, fmt, val, regs))
                i += wd
                if name in PAYLOAD_AFTER:
                    while i < n and not (off + 2 * i) % 4:
                        break
                    if i < n and (off + 2 * i) % 4:
                        i += 1
                continue
            wd = learn.get(op, 1)
            out.append((i, op, "op_%02x" % op, None, None, []))
            i += wd
        return out, bad

    @staticmethod
    def regs_of(fmt, u):
        """registradores, na ordem canônica (receiver primeiro quando virtual)"""
        if fmt == "12x":
            return [u[0] >> 8 & 0xF, u[0] >> 12 & 0xF]
        if fmt in ("11x", "11n", "21c", "21s", "21h", "21t", "22x", "10t"):
            return [u[0] >> 8 & 0xF]
        if fmt == "22x":
            return [u[0] >> 8 & 0xFF, u[1]]
        if fmt in ("22c", "22s", "22t", "22b"):
            return [u[0] >> 8 & 0xF, u[0] >> 12 & 0xF, u[1] if fmt == "22c" else u[1]]
        if fmt == "23x":
            return [u[0] >> 8 & 0xF, u[0] >> 12 & 0xF, u[1] & 0xFF]
        if fmt == "35c":
            cnt = u[0] >> 12 & 0xF
            g = u[0] >> 8 & 0xF
            r = [u[2] >> s & 0xF for s in (0, 4, 8, 12)]
            return (r[:cnt - 1] + [g])[:cnt] if cnt == 5 else r[:cnt]
        if fmt == "3rc":
            cnt = u[0] >> 8 & 0xFF
            first = u[2]
            return [ (first + k) & 0xFFFF for k in range(cnt if cnt else 0)]
        if fmt == "31c":
            return [u[0] >> 8 & 0xF]
        return []


def learn_widths(dx, sample=1500):
    """calibra as larguras de 0x79..0xff: minimiza métodos que não terminam alinhados."""
    learn = {c: 1 for c in range(0x79, 0x100)}
    learn.update({0x7f: 2, 0x80: 2, 0x81: 2, 0x82: 2, 0x83: 2, 0x84: 2, 0x85: 2, 0x86: 2,
                  0x87: 2, 0x88: 2, 0x89: 2, 0x8a: 2, 0x8b: 2, 0x8c: 2, 0x8d: 2, 0x8e: 2,
                  0x8f: 2, 0x90: 2, 0x91: 2, 0x92: 2, 0x93: 2, 0x94: 2, 0x95: 2, 0x96: 2,
                  0x97: 2, 0x98: 2, 0x99: 2, 0x9a: 2, 0x9b: 2, 0x9c: 2, 0x9d: 2, 0x9e: 2,
                  0x9f: 2, 0xfa: 3, 0xfb: 3, 0xfc: 4, 0xfd: 4})
    bodies = []
    for d in dx.korder:
        for mi, _a, code in dx.K[d]["methods"]:
            if code:
                bodies.append(code)
        if len(bodies) >= sample * 6:
            break
    bodies = bodies[:sample * 6]

    def score(learn):
        bad = 0
        for c in bodies:
            off, n, _r = dx.code_of(c)
            res, b = dx.walk(off, n, learn)
            if res:
                endpc = res[-1][0]
                lw = W10.get(res[-1][3] or "", learn.get(res[-1][1], 1))
                if res[-1][2] == "payload":
                    continue
                if endpc + lw != n:
                    bad += 1
            bad += b
        return bad

    best = score(learn)
    for _round in range(2):
        ops = collections.Counter()
        for c in bodies:
            off, n, _r = dx.code_of(c)
            for i in range(n):
                op = struct.unpack_from("<H", dx.b, off + 2 * i)[0] & 0xFF
                if op >= 0x79:
                    ops[op] += 1
        for op, _cnt in ops.most_common(80):
            cur = learn.get(op, 1)
            for cand in (2, 3, 1, 4, 5):
                if cand == cur:
                    continue
                learn[op] = cand
                v = score(learn)
                if v < best:
                    best = v
                    break
            else:
                learn[op] = cur
        if best == 0:
            break
    return learn, best, len(bodies)


def resolve(dx, idx, mtype):
    if idx is None:
        return None
    if mtype == "str":
        return dx.S[idx]
    if mtype == "type":
        return dx.T[idx] if idx < dx.tsz else None
    if mtype == "field":
        return dx.FT[idx] if idx < len(dx.FT) else None
    if mtype == "method":
        return dx.M[idx] if idx < dx.msz else None
    return None


def fmt_instr(dx, name, fmt, val, regs, mtype):
    r = ", ".join("v%d" % x for x in regs)
    if name == "const-string":
        return '    const-string %s, "%s"' % (r, (resolve(dx, val, "str") or "").replace('"', "'")[:180])
    if name in ("new-instance", "const-class", "check-cast"):
        return "    %s %s, %s" % (name, r, resolve(dx, val, "type") or "?")
    if name in ("iget-object", "iput-object"):
        f = resolve(dx, val, "field")
        if not f:
            return "    # %s idx inválido" % name
        cls, typ, fn = f
        return "    %s %s, %s->%s:%s" % (name, r, cls, fn, typ)
    if name in ("sget-object", "sput-object"):
        f = resolve(dx, val, "field")
        if not f:
            return "    # %s idx inválido" % name
        cls, typ, fn = f
        return "    %s %s, %s->%s:%s" % (name, r, cls, fn, typ)
    if name.startswith("invoke-"):
        m = resolve(dx, val, "method")
        if not m:
            return "    # invoke idx inválido"
        cls, mn, proto = m
        ret, params = dx.P[proto] if proto < len(dx.P) else ("?", [])
        return "    %s {%s}, %s->%s(%s)%s" % (name, r, cls, mn, "".join(params), ret)
    if name in ("monitor-enter", "monitor-exit"):
        return "    %s %s" % (name.replace("-", "-"), r)
    return "    # %s %s" % (name, r)


def iterate_methods(dx, learn):
    for d in dx.korder:
        k = dx.K[d]
        for mi, acc, code in k["methods"]:
            cls, mn, proto = dx.M[mi]
            ret, params = dx.P[proto] if proto < len(dx.P) else ("?", [])
            ann = k["mannot"].get(mi, [])
            yield d, k, mi, acc, code, cls, mn, "(" + "".join(params) + ")" + ret, ann


KEYWORDS = ("addJavascriptInterface", "loadUrl", "bootstrap/", "index.html", "loadWeb",
            "MetaFetchTask", "preenginetasks", "BootstrapPermissionsResult", "WebViewAssetLoader",
            "shouldInterceptRequest", "shouldOverrideUrlLoading", "passPreloginFence", "saveKVP",
            "CDNI", "cdn", "server_url", "environment", "http://", "https://localhost", "file://",
            "asset", "WebViewClient", "setJavaScriptEnabled", "setWebContentsDebuggingEnabled",
            "SharedPreferences", "CountDownLatch", "Semaphore", "JavascriptInterface",
            "callStatic", "getStaticMethodID", "RegisterNatives", "FindClass", "dispatch")


def c1(dx, learn, out_path, tsv_path):
    """Um passe: sítios de WebView/bridge, métodos expostos com 1 nível de cadeia,
    xrefs das palavras-chave, sincronização, e TSV completo."""
    out = open(out_path, "w", encoding="utf-8")
    def P(*a):
        line = " ".join(str(x) for x in a)
        out.write(line + "\n")
        print(line)
    ts = open(tsv_path, "w", encoding="utf-8") if tsv_path else None
    if ts:
        ts.write("class\tmethod\tpc\top\tcallee_or_string\tregs\n")
    exposed_by_cls = collections.defaultdict(list)
    wv_sites = collections.defaultdict(list)         # (cls,meth) -> [ (caller,sig,pc,regs) ]
    str_sites = collections.defaultdict(list)        # keyword -> [ (cls,meth,string) ]
    native_in_cls = collections.defaultdict(list)    # classe -> métodos nativos
    sync = collections.defaultdict(list)
    total = 0
    for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
        total += 1
        is_exposed = any(x.endswith("JavascriptInterface;") for x in ann)
        if acc & 0x100:
            native_in_cls[cls].append(mn + sig)
        if not code:
            if is_exposed:
                exposed_by_cls[cls].append((mn, sig, [], [], ann, True))
            continue
        off, cnt, _r = dx.code_of(code)
        res, _b = dx.walk(off, cnt, learn)
        calls, strs = [], []
        for pc, op, name, fmt, val, regs in res:
            if ts:
                extra = ""
                if name.startswith("invoke-") and val is not None:
                    m = resolve(dx, val, "method")
                    extra = "%s->%s" % (m[0], m[1]) if m else ""
                elif name == "const-string" and val is not None:
                    extra = repr(dx.S[val])[:120]
                ts.write("%s\t%s\t%x\t%s\t%s\t%s\n" %
                         (cls, mn, pc * 2, name, extra, ",".join("v%d" % x for x in regs)))
            if name.startswith("invoke-") and val is not None:
                m = resolve(dx, val, "method")
                if not m:
                    continue
                calls.append((m[0], m[1], m[2], pc, regs))
                tc, tm = m[0], m[1]
                if "webkit" in tc or "WebView" in tc or "WebSettings" in tc or tm in (
                        "addJavascriptInterface", "loadUrl", "postUrl", "loadData",
                        "loadDataWithBaseURL", "evaluateJavascript", "setJavaScriptEnabled",
                        "shouldInterceptRequest", "setWebViewClient", "destroy", "clearCache"):
                    wv_sites[(tc, tm)].append((cls, mn, sig, pc, regs))
                if tm in ("countDown", "await", "release", "acquire", "notify", "notifyAll",
                          "wait", "park", "post", "postDelayed", "join", "take", "poll"):
                    sync[cls + "::" + mn].append(tc[1:-1].replace("/", ".") + "->" + tm)
            elif name == "const-string" and val is not None:
                sval = dx.S[val]
                strs.append(sval)
                for kw in KEYWORDS:
                    if kw.lower() in sval.lower():
                        str_sites[kw].append((cls, mn, sig, sval))
        if is_exposed:
            exposed_by_cls[cls].append((mn, sig, calls, strs, ann, False))
    P("=== C1 (classes.dex de %s) — %d classes, %d métodos, %d com corpo ===" %
      (os.path.basename(dx.path), dx.cds, dx.msz, total))
    P("\n## C1-1/C1-2  sítios de WebView (criação/config/bridge/load*), por alvo")
    for (tc, tm), sites in sorted(wv_sites.items()):
        P("  %s->%s   (%d sítios)" % (tc, tm, len(sites)))
        for (cls, mn, sig, pc, regs) in sites[:14]:
            P("     %s::%s%s  +0x%04x  {%s}" % (cls, mn, sig, pc * 2, ", ".join("v%d" % x for x in regs)))
        if len(sites) > 14:
            P("     ... +%d sítios" % (len(sites) - 14))
    if not wv_sites:
        P("   (nenhum)")
    P("\n## C1-3  métodos com @JavascriptInterface e o que chamam")
    nex = 0
    for cls in sorted(exposed_by_cls):
        P("  %s" % cls)
        for (mn, sig, calls, strs, ann, nocode) in sorted(exposed_by_cls[cls]):
            nex += 1
            P("    %s%s%s" % (mn, sig, "  [sem corpo/nativo]" if nocode else ""))
            for (tc, tm, _p, pc, regs) in calls[:10]:
                P("        -> %s->%s" % (tc, tm))
            if strs:
                P('        strings: %s' % " | ".join(repr(x)[:44] for x in strs[:6]))
    P("   total métodos expostos: %d" % nex)
    P("\n## C1-5/6/7  palavras-chave: onde a string é carregada (const-string)")
    for kw in KEYWORDS:
        v = str_sites.get(kw, [])
        if v:
            P("  %r: %d sítio(s)" % (kw, len(v)))
            for (cls, mn, sig, sval) in v[:10]:
                P("     %s::%s%s  %s" % (cls, mn, sig, repr(sval)[:90]))
            if len(v) > 10:
                P("     ... +%d" % (len(v) - 10))
    P("\n## métodos nativos por classe (candidatos a JNI da ponte)")
    for cls in sorted(native_in_cls):
        if any(x in cls for x in ("WebKit", "webview", "WebView", "Bootstrap", "bootstrap", "Meta",
                                  "Bridge", "Js", "CDNI", "cdn", "Native", "Engine", "Activity")):
            P("  %s: %s" % (cls, ", ".join(sorted(set(native_in_cls[cls]))[:12])))
    P("\n## C1-5  sincronização em métodos que também tocam WebView/bridge")
    for kk, vv in sorted(sync.items()):
        if any(t in kk for t in ("prelogin", "Prelogin", "Fence", "fence", "Bootstrap", "bootstrap",
                                 "Bridge", "Js", "WebView", "webview")):
            P("  %s: %s" % (kk, " ".join(sorted(set(vv))[:6])))
    P("\n## subclasses de WebView (quem É um WebView)")
    subs = [d for d in dx.korder if dx.K[d]["super"] and "WebView" in dx.K[d]["super"]]
    for d in subs[:40]:
        flds = [dx.FT[fi][2] + ":" + dx.FT[fi][1] for fi, _a in dx.K[d]["fields"]][:8]
        P("  %s  campos=%s" % (d[1:-1].replace("/", "."), ", ".join(flds[:6])))
    P("   total subclasses de WebView: %d" % len(subs))
    out.close()
    if ts:
        ts.close()
    print("[ok] relatório: %s%s" % (out_path, ("  tsv: " + tsv_path) if tsv_path else ""))
    P("[ok] relatório completo")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dex", required=True)
    ap.add_argument("--stats", action="store_true")
    ap.add_argument("--smali")
    ap.add_argument("--filter")
    ap.add_argument("--xref-str")
    ap.add_argument("--callers", nargs=2)
    ap.add_argument("--exposed", action="store_true")
    ap.add_argument("--method", nargs=2)
    ap.add_argument("--locks", action="store_true")
    ap.add_argument("--limit", type=int, default=400)
    ap.add_argument("--dump-class")
    ap.add_argument("--grep")
    ap.add_argument("--learn-cache", default="/tmp/dex_dasm_learn.json")
    ap.add_argument("--c1-report")
    ap.add_argument("--tsv")
    a = ap.parse_args()
    dx = Dex(a.dex)
    print("[dex] %s strings=%d tipos=%d métodos=%d campos=%d classes=%d endian=%x" %
          (os.path.basename(a.dex), dx.ssz, dx.tsz, dx.msz, len(dx.FT), dx.cds, dx.endian))
    import json
    learn = None
    if a.learn_cache and os.path.exists(a.learn_cache) and not a.stats:
        try:
            learn = {int(k): v for k, v in json.load(open(a.learn_cache)).items()}
            viol, nb = 0, 1
            print("[calibração] cache %s (%d opcodes)" % (a.learn_cache, len(learn)))
        except Exception:
            learn = None
    if learn is None:
        learn, viol, nb = learn_widths(dx)
        if a.learn_cache:
            json.dump({str(k): v for k, v in learn.items()}, open(a.learn_cache, "w"))
    pct = 100.0 * viol / max(1, nb)
    print("[calibração] %d corpos amostrados; desalinhamentos+índices inválidos = %d (%.3f%%)" % (nb, viol, pct))
    ok = viol == 0
    print("[calibração] %s" % ("V1/V2 OK" if ok else "V1/V2 COM FALHAS — trate saídas como indício, não prova"))
    if hasattr(dx, "v7"):
        print("[V7 protos: %s]" % (", ".join("%s=%d" % kv for kv in dx.v7.most_common())))
    if hasattr(dx, "v5"):
        o_, b_ = dx.v5
        print("[V5 atribuição classe<->método/campo: ok=%d inválidos=%d (%.4f%%)] %s" %
              (o_, b_, 100.0 * b_ / max(1, o_ + b_), "OK" if b_ == 0 else "RUIM"))
        ok = ok and b_ == 0
    if a.stats:
        st = collections.Counter(); tot = 0
        for _d, _k, _mi, _a, code, _c, _m, _s, _an in iterate_methods(dx, learn):
            if not code:
                continue
            tot += 1
            off, n, _r = dx.code_of(code)
            res, _b = dx.walk(off, n, learn)
            for pc, op, name, fmt, val, regs in res:
                st[name] += 1
        print("[instruções] (top 20 de %d métodos com corpo)" % tot)
        for kk, vv in st.most_common(20):
            print("   %-26s %9d" % (kk, vv))
    o6, b6 = dx.v6()
    print("[V6 anotações: idx dentro da classe ok=%d fora=%d]" % (o6, b6))
    if a.exposed:
        print("\n### @JavascriptInterface (duas fontes cruzadas)")
        mk = {(c, m) for c, m in dx.marker_hits("Landroid/webkit/JavascriptInterface;")}
        print("   padrão de marcador: %d métodos" % len(mk))
        n = 0
        for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
            isex = (d, mi) in mk or any(x and x.endswith("JavascriptInterface;") for x in ann)
            if isex:
                n += 1
                print("  %s::%s%s   (classe=%s, nativo=%s)" %
                      (cls, mn, sig, d[1:-1].replace("/", "."), bool(acc & 0x100)))
        print("   total: %d" % n)
    if a.xref_str:
        want = a.xref_str
        ids = {i for i, s in enumerate(dx.S) if want in s}
        print("\n### const-string contendo %r → métodos" % want)
        n = 0
        for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
            if not code:
                continue
            off, cnt, _r = dx.code_of(code)
            res, _b = dx.walk(off, cnt, learn)
            for pc, op, name, fmt, val, regs in res:
                if name == "const-string" and val in ids:
                    n += 1
                    print("  %s::%s%s  +0x%04x  \"%s\"%s" %
                          (cls, mn, sig, pc * 2, (dx.S[val] or "")[:70],
                           "  [@JavascriptInterface]" if any(
                               x.endswith("JavascriptInterface;") for x in ann) else ""))
                    if n >= a.limit:
                        print("  (limitado a %d)" % a.limit); return
        if not n:
            print("   (nenhum const-string — a string pode ser construída ou estar em outro dex)")
    if a.callers:
        wc, wm = a.callers
        print("\n### chamadores de %s->%s" % (wc, wm))
        n = 0
        for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
            if not code:
                continue
            off, cnt, _r = dx.code_of(code)
            res, _b = dx.walk(off, cnt, learn)
            for pc, op, name, fmt, val, regs in res:
                if name.startswith("invoke-") and val is not None:
                    m = resolve(dx, val, "method")
                    if m and m[0] == wc and m[1] == wm:
                        n += 1
                        print("  %s::%s%s  +0x%04x  {%s}" % (cls, mn, sig, pc * 2,
                                                              ", ".join("v%d" % x for x in regs)))
        print("   total: %d" % n)
    if a.locks:
        print("\n### primitivas de sincronização (dono : chamada)")
        want = {"countDown", "await", "release", "acquire", "notify", "notifyAll", "wait",
                "park", "post", "postDelayed", "join", "sleep", "poll", "take"}
        n = 0
        for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
            if not code:
                continue
            off, cnt, _r = dx.code_of(code)
            res, _b = dx.walk(off, cnt, learn)
            hits = []
            for pc, op, name, fmt, val, regs in res:
                if name.startswith("invoke-") and val is not None:
                    m = resolve(dx, val, "method")
                    if m and m[1] in want:
                        hits.append("%s->%s" % (m[0][1:-1].replace("/", "."), m[1]))
            if hits:
                n += 1
                if n <= 120:
                    print("  %s::%s  %s%s" % (cls, mn + sig, " ".join(sorted(set(hits))),
                                               "  [@JavascriptInterface]" if any(
                                                   x.endswith("JavascriptInterface;") for x in ann) else ""))
        print("   total de métodos com primitivas: %d" % n)
    if a.method:
        want_c, want_m = a.method
        for d, k, mi, acc, code, cls, mn, sig, ann in iterate_methods(dx, learn):
            if cls != want_c and d[1:-1].replace("/", ".") != want_c:
                continue
            if mn != want_m:
                continue
            print("\n### %s::%s%s  flags=0x%x  anotações=%s" % (cls, mn, sig, acc,
                                                                  [x for x in ann]))
            if not code:
                print("   (sem corpo: abstract/native)"); continue
            off, cnt, regs = dx.code_of(code)
            res, _b = dx.walk(off, cnt, learn)
            print("    .registers %d" % regs)
            for pc, op, name, fmt, val, rg in res:
                print("    %04x: %s" % (pc * 2, fmt_instr(dx, name, fmt, val, rg, None)))
    if a.c1_report:
        c1(dx, learn, a.c1_report, a.tsv)
    if a.dump_class:
        for d in dx.korder:
            if a.dump_class.strip("L;") not in d:
                continue
            k = dx.K[d]
            print("\n########## %s   super=%s  src=%s" % (d, k["super"], k["src"]))
            for fi, acc in k["fields"]:
                if fi < len(dx.FT):
                    cls, typ, fn = dx.FT[fi]
                    print("  .field %s:%s   (acc=0x%x)" % (fn, typ, acc))
            for mi, acc, code in k["methods"]:
                if a.grep and a.grep not in dx.M[mi][1]:
                    continue
                cls, mn, proto = dx.M[mi]
                ret, params = dx.P[proto] if proto < len(dx.P) else ("?", [])
                fl = [n for b, n in ((0x1, "public"), (0x2, "private"), (0x4, "protected"),
                                     (0x8, "static"), (0x10, "final"), (0x20, "synchronized"),
                                     (0x100, "native"), (0x400, "abstract")) if acc & b]
                print("\n  .method %s %s%s%s" % (" ".join(fl) or "?", mn,
                                                 "(" + "".join(params) + ")" + ret,
                                                 "   ANOT=%s" % k["mannot"].get(mi) if k["mannot"].get(mi) else ""))
                if not code:
                    print("     (sem corpo)"); continue
                off, cnt, rg = dx.code_of(code)
                res, _b = dx.walk(off, cnt, learn)
                print("    .registers %d" % rg)
                for pc, op, name, fmt, val, regs in res:
                    print("    %04x: %s" % (pc * 2, fmt_instr(dx, name, fmt, val, regs, None)))
    if a.smali:
        os.makedirs(a.smali, exist_ok=True)
        st = collections.Counter()
        files = 0
        for d in dx.korder:
            k = dx.K[d]
            if a.filter and not re.search(a.filter, d):
                continue
            L = [".class synthetic %s" % d]
            if k["super"]:
                L.append(".super %s" % k["super"])
            if k["src"]:
                L.append('.source "%s"' % k["src"])
            for fi, acc2 in k["fields"]:
                if fi >= len(dx.FT):
                    continue
                cls, typ, fn = dx.FT[fi]
                L.append(".field synthetic %s:%s" % (fn, typ))
            for mi, acc, code in k["methods"]:
                cls, mn, proto = dx.M[mi]
                ret, params = dx.P[proto] if proto < len(dx.P) else ("?", [])
                fl = [n for b, n in ((0x1, "public"), (0x2, "private"), (0x4, "protected"),
                                     (0x8, "static"), (0x40, "final"), (0x80, "synchronized"),
                                     (0x100, "native"), (0x400, "abstract")) if acc & b]
                L.append(".method %s %s%s" % (" ".join(fl) or "synthetic", mn,
                                              "(" + "".join(params) + ")" + ret))
                for an in k["mannot"].get(mi, []):
                    L.append(".annotation runtime %s" % an)
                    L.append(".end annotation")
                if code:
                    off, cnt, rg = dx.code_of(code)
                    L.append("    .registers %d" % rg)
                    res, _b = dx.walk(off, cnt, learn)
                    for pc, op, name, fmt, val, regs2 in res:
                        st[name] += 1
                        L.append(fmt_instr(dx, name, fmt, val, regs2, None))
                L.append(".end method")
            with open(os.path.join(a.smali, d[1:-1].replace("/", ".") + ".smali"), "w") as f:
                f.write("\n".join(L) + "\n")
            files += 1
        print("\n[smali] %d arquivos em %s (%d instruções)" % (files, a.smali, sum(st.values())))
        print("[smali] invokes=%d const-string=%d new-instance=%d iget/iput-object=%d" %
              (sum(v for kk, v in st.items() if kk.startswith("invoke-")), st["const-string"],
               st["new-instance"], st["iget-object"] + st["iput-object"]))
        print("[smali] V1/V2: %s" % ("OK" if ok else "FALHAS (%d)" % viol))


if __name__ == "__main__":
    main()
