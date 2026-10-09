#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""arena_write_tracker.py — reconstrói o que o código escreve numa região `.bss`.

Contexto (M6.10): as 17 tabelas `JNINativeMethod` de `libgame.so` ficam em `.bss`
(`0x56f6f00` + `0x974f750`) e **nenhuma** realocação de `.rela.dyn` tem `r_offset` nelas
(0 de 140 773), então o conteúdo é escrito por código. Protetores evitam strings no arquivo
montando os bytes nos registradores (`MOVZ`/`MOVK`/`STRB`) — por isto este script mantém

  (a) composição correta de bitfields para `MOVZ`/`MOVK` (`hw` = 0/16/32/48),
  (b) um mapa byte-a-byte do que foi gravado (`STRB`/`STRH`/`STR w`/`STR x`/`STP`/`STUR`),

e no fim imprime, para cada região pedida, os bytes reconstruídos e os ponteiros de 8 bytes.

É execução simbólica *linear* de um bloco, não um emulador: `BL`/`BLR` invalidam os
registradores chamador-salvos (x0..x18) de propósito, para nunca atribuir a uma chamada um
valor que ela poderia ter alterado. Laços são tratados com iteração (`--pass`, 3 por padrão),
então um construtor que roda em laço preenche o mapa inteiro.

Uso:
  python3 scripts/arena_write_tracker.py LIB --at 0xLO-0xHI --to SPAN[,SPAN...] [--pass N]
                                              [--dump 0xADDR] [--trace]
  python3 scripts/arena_write_tracker.py --selftest
"""
import argparse
import array
import collections
import struct
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from so_jni_scan import Elf  # noqa: E402

CALLS_SAVED = tuple(range(0, 19))


def sign(v, bits):
    return v - (1 << bits) if v >> (bits - 1) else v


def decode(w, pc):
    """Subconjunto de AArch64 suficiente para rastrear construção de constantes e Stores.

    Retorna (opcode, tupla) ou None. Registrar: índice 0..30 = X0..X30 (X31 lido = SP,
    escrito = ignorado em chamadas de memória)."""
    m29 = (w >> 29) & 7
    if (w & 0x9F000000) == 0x90000000:                       # ADRP
        imm = sign((((w >> 5) & 0x7FFFF) << 2) | ((w >> 29) & 3), 21)
        return ("ADRP", (w & 0x1F, ((pc >> 12) + imm) << 12))
    if (w & 0x9F000000) == 0x10000000:                       # ADR
        imm = sign((((w >> 5) & 0x7FFFF) << 2) | ((w >> 29) & 3), 21)
        return ("ADR", (w & 0x1F, pc + imm))
    if (w & 0x7F800000) == 0x52800000:                       # MOVZ
        return ("MOVZ", (w & 0x1F, (w >> 21) & 3, (w >> 5) & 0xFFFF, (w >> 31) & 1))
    if (w & 0x7F800000) == 0x72800000:                       # MOVK
        return ("MOVK", (w & 0x1F, (w >> 21) & 3, (w >> 5) & 0xFFFF, (w >> 31) & 1))
    if (w & 0x7F800000) == 0x12800000:                       # MOVN
        return ("MOVN", (w & 0x1F, (w >> 21) & 3, (w >> 5) & 0xFFFF, (w >> 31) & 1))
    if (w & 0xFFE0FFE0) == 0xAA0003E0:                        # MOV r,r
        return ("MOV", (w & 0x1F, (w >> 16) & 0x1F, (w >> 30) & 1))
    if (w & 0x7F800000) == 0x53000000 or (w & 0x7F800000) == 0x53000000:  # LSR/UBFM
        if (w >> 22) & 1 == 0:
            return ("UBFM", (w & 0x1F, (w >> 5) & 0x1F, w >> 10 & 0x3F, w >> 16 & 0x3F, (w >> 31) & 1))
    if (w & 0xFF000000) == 0x91000000:                       # ADD imm 64
        return ("ADDi", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << (12 if (w >> 22) & 1 else 0)))
    if (w & 0xFF000000) == 0xD1000000:                       # SUB imm 64
        return ("SUBi", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << (12 if (w >> 22) & 1 else 0)))
    if (w & 0x7FE0FC00) == 0x0B000000:                       # ADD reg
        return ("ADDr", (w & 0x1F, (w >> 5) & 0x1F, (w >> 16) & 0x1F, (w >> 10) & 0x3F, 0))
    if (w & 0x7FE0FC00) == 0x0B200000:                       # SUB reg
        return ("ADDr", (w & 0x1F, (w >> 5) & 0x1F, (w >> 16) & 0x1F, (w >> 10) & 0x3F, 1))
    if (w & 0xFFC00000) == 0xF9400000:                        # LDR x, [x, #imm]
        return ("LDR", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << 3))
    if (w & 0xFFC00000) == 0xB9400000:                        # LDR w, [x, #imm]
        return ("LDR", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << 2))
    if (w & 0xFFC00000) == 0xF9400400:                        # LDR (pós)
        return ("LDRpost", (w & 0x1F, (w >> 5) & 0x1F, sign((w >> 12) & 0x1FF, 9)))
    if (w & 0xFFC00000) == 0xF9000000:                        # STR x, [x, #imm]
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << 3, 8))
    if (w & 0xFFC00000) == 0xB9000000:                        # STR w, [x, #imm]
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << 2, 4))
    if (w & 0xFFC00000) == 0x79000000:                        # STRH
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) << 1, 2))
    if (w & 0xFFC00000) == 0x39000000:                        # STRB
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, (w >> 10) & 0xFFF, 1))
    if (w & 0xFFE00C00) == 0xF8200000:                        # STUR x
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, sign((w >> 12) & 0x1FF, 9), 8))
    if (w & 0xFFE00C00) == 0x38200000:                        # STURB
        return ("STR", (w & 0x1F, (w >> 5) & 0x1F, sign((w >> 12) & 0x1FF, 9), 1))
    if (w & 0xFFC00C00) == 0xF8000400:                        # STR pós-indexa
        return ("STRpost", (w & 0x1F, (w >> 5) & 0x1F, sign((w >> 12) & 0x1FF, 9), 8))
    if (w & 0xFFC00C00) == 0xF9000C00:                        # STR pré-indexa
        return ("STRpre", (w & 0x1F, (w >> 5) & 0x1F, sign((w >> 12) & 0x1FF, 9), 8))
    if (w & 0x7FC00000) == 0x28000000:                        # STP (offset signed)
        return ("STP", (w & 0x1F, (w >> 10) & 0x1F, (w >> 5) & 0x1F, sign((w >> 15) & 0x7F, 7) * 8,
                        (w >> 23) & 3, 0))
    if (w & 0x7FC00000) == 0x29000000:                        # STP pré/pós
        return ("STP", (w & 0x1F, (w >> 10) & 0x1F, (w >> 5) & 0x1F, sign((w >> 15) & 0x7F, 7) * 8,
                        (w >> 23) & 3, 1))
    if (w & 0x7FC00000) == 0x29400000:                        # LDP
        return ("LDP", (w & 0x1F, (w >> 10) & 0x1F, (w >> 5) & 0x1F, sign((w >> 15) & 0x7F, 7) * 8,
                        (w >> 23) & 3))
    if (w & 0x7FC00400) == 0x38000400:                        # STRB reg-offset
        return ("STRr", (w & 0x1F, (w >> 5) & 0x1F, (w >> 16) & 0x1F, (w >> 10) & 0x3F, 1))
    if (w & 0x7FC00400) == 0x38400400:                        # LDRB reg-offset
        return ("LDRB", (w & 0x1F, (w >> 5) & 0x1F, (w >> 16) & 0x1F, (w >> 10) & 0x3F))
    if (w & 0xFFFFFC1F) == 0xD63F0000:
        return ("BLR", ((w >> 5) & 0x1F, ))
    if (w & 0xFFFFFC1F) == 0xD61F0000:
        return ("BR", ((w >> 5) & 0x1F, ))
    if w == 0xD65F03C0:
        return ("RET", ())
    if (w & 0xFC000000) == 0x94000000:
        return ("BL", (pc + sign(w & 0x03FFFFFF, 26) * 4, ))
    if (w & 0xFC000000) == 0x14000000:
        return ("B", (pc + sign(w & 0x03FFFFFF, 26) * 4, ))
    if (w & 0xFF000010) == 0x54000000:
        return ("B.cond", (None, pc + sign((w >> 5) & 0x7FFFF, 19) * 4))
    if (w & 0x7F000000) == 0x36000000 or (w & 0x7F000000) == 0x37000000:
        return ("TBZ", (None, pc + sign((w >> 5) & 0x7FFFF, 14) * 4, (w >> 31) & 1, (w >> 19) & 0x1F))
    return None


class Mem:
    """leitura de 8 bytes: conteúdo do arquivo; senão o addend da realocação RELATIVE
    (é o que o ld.so escreveria — `.data.rel.ro` de libgame.so é 94,5% zeros no arquivo)"""

    def __init__(self, e):
        self.e = e
        self.rel = {}
        ent = e.sec.get(".rela.dyn")
        if ent and ent[2]:
            a, o, sz = ent[:3]
            for i in range(sz // 24):
                ro, ri, ra = struct.unpack_from("<QQQ", e.F, o + 24 * i)
                if (ri & 0xFFFFFFFF) in (0x403, 0x101):      # RELATIVE / ABS64
                    self.rel.setdefault(ro, ra)

    def qword(self, va):
        if va is None:
            return None
        o = self.e.v2o(va)
        if o is not None and o + 8 <= len(self.e.F):
            v, = struct.unpack_from("<Q", self.e.F, o)
            if v:
                return v
        return self.rel.get(va)


def run(e, at_lo, at_hi, spans, passes=3, trace=False, max_instr=400000):
    sec = None
    for nm, (a, o, sz, t, f) in e.sec.items():
        if t == 1 and (f & 0x4) and a <= at_lo < a + sz:
            sec = (a, o, sz)
            break
    if sec is None:
        raise SystemExit("nenhuma seção executável cobre %s" % hex(at_lo))
    a, o, sz = sec
    lo_i, hi_i = at_lo - a, min(at_hi - a, sz)
    W = array.array("I")
    W.frombytes(e.F[o + lo_i:o + hi_i])
    mem = Mem(e)
    span_lo = min(s[0] for s in spans)
    span_hi = max(s[1] for s in spans)
    bytes_written = {}
    hits = collections.Counter()
    notes = []
    total = 0
    for p in range(passes):
        st = {}
        n = len(W)
        for i in range(n):
            if i >= max_instr:
                break
            pc = a + lo_i + 4 * i
            d = decode(W[i], pc)
            if d is None:
                continue
            op, f = d
            if op in ("ADRP", "ADR"):
                st[f[0]] = f[1]
            elif op == "MOVZ":
                v = f[2] << (f[1] * 16)
                st[f[0]] = v & 0xFFFFFFFF if not f[3] else v
            elif op == "MOVK":
                cur = st.get(f[0])
                if isinstance(cur, int):
                    pos = f[1] * 16
                    mask = 0xFFFF << pos
                    v = (cur & ~mask) | (f[2] << pos)
                    st[f[0]] = v & 0xFFFFFFFF if not f[3] else v
                else:
                    st[f[0]] = None
            elif op == "MOVN":
                st[f[0]] = None
            elif op == "MOV":
                st[f[0]] = st.get(f[1])
            elif op == "UBFM":
                st[f[0]] = None                                  # não proponho máscara aqui
            elif op in ("ADDi", "SUBi"):
                b = st.get(f[1])
                st[f[0]] = (b + f[2] if op == "ADDi" else b - f[2]) if isinstance(b, int) else None
            elif op == "ADDr":
                b, x = st.get(f[1]), st.get(f[2])
                if isinstance(b, int) and isinstance(x, int) and f[3] in (0, 3):
                    st[f[0]] = (b + (x << f[3])) if not f[4] else (b - (x << f[3]))
                else:
                    st[f[0]] = None
            elif op in ("LDR", "LDRpost", "LDRB"):
                if op == "LDRB":
                    b, x = st.get(f[1]), st.get(f[2])
                    st[f[0]] = None                              # byte-load não resolvo
                    continue
                b = st.get(f[1])
                if op == "LDR":
                    st[f[0]] = mem.qword(b + f[2]) if isinstance(b, int) else None
                else:
                    st[f[0]] = mem.qword(b) if isinstance(b, int) else None
                    if isinstance(b, int):
                        st[f[1]] = b + f[2]
            elif op == "LDP":
                b = st.get(f[2])
                if isinstance(b, int):
                    st[f[0]] = mem.qword(b + f[3])
                    st[f[1]] = mem.qword(b + f[3] + 8)
                    if f[4] & 1:
                        st[f[2]] = b + f[3]
                else:
                    st[f[0]] = st[f[1]] = None
            elif op in ("STR", "STRpost", "STRpre", "STUR", "STURB"):
                b = st.get(f[1])
                if not isinstance(b, int):
                    continue
                if op == "STRpost":
                    tgt = b
                    st[f[1]] = b + f[2]
                elif op == "STRpre":
                    st[f[1]] = b + f[2]
                    tgt = st[f[1]]
                else:
                    tgt = b + f[2]
                v = st.get(f[0])
                width = f[3] if op == "STR" else (8 if op in ("STUR", "STRpre", "STRpost") else 1)
                if op == "STUR":
                    width = 8
                if span_lo <= tgt < span_hi:
                    hits[pc] += 1
                    if isinstance(v, int):
                        for j in range(width):
                            bytes_written[tgt + j] = (v >> (8 * j)) & 0xFF
                    else:
                        for j in range(width):
                            bytes_written.setdefault(tgt + j, None)
            elif op == "STRr":
                b, x = st.get(f[1]), st.get(f[2])
                if isinstance(b, int) and isinstance(x, int):
                    tgt = b + (x << f[3])
                    if span_lo <= tgt < span_hi:
                        v = st.get(f[0])
                        hits[pc] += 1
                        if isinstance(v, int):
                            bytes_written[tgt] = v & 0xFF
                        else:
                            bytes_written.setdefault(tgt, None)
            elif op == "STP":
                b = st.get(f[2])
                if not isinstance(b, int):
                    continue
                if f[5]:                                          # pré/pós
                    if f[4] & 1:
                        b = st[f[2]] = b + f[3]
                for j, rj in ((0, f[0]), (8, f[1])):
                    tgt = b + f[3] + j
                    if span_lo <= tgt < span_hi:
                        v = st.get(rj)
                        hits[pc] += 1
                        if isinstance(v, int):
                            for k in range(8):
                                bytes_written[tgt + k] = (v >> (8 * k)) & 0xFF
                        else:
                            for k in range(8):
                                bytes_written.setdefault(tgt + k, None)
                if f[5] and not (f[4] & 1):
                    st[f[2]] = b + f[3]
            elif op in ("BL", "BLR"):
                for r in CALLS_SAVED:
                    st[r] = None
                if op == "BL" and trace:
                    notes.append("pc=%s BL %s" % (hex(pc), hex(f[0])))
            elif op == "RET":
                st = {}
            total += 1
    return bytes_written, notes, total, hits


def render(e, bytes_written, spans):
    out = []
    for s0, s1, lab in spans:
        got = [bytes_written.get(x) for x in range(s0, s1)]
        nb = sum(1 for x in got if x is not None)
        out.append("  região %s : %d/%d bytes reconstruídos" % (lab, nb, s1 - s0))
        for base in range(s0, s1, 24):
            row = got[base - s0: base - s0 + 24]
            asc = "".join(chr(c) if c is not None and 32 <= c < 127 else "." for c in row)
            hexs = " ".join("%02x" % c if c is not None else "--" for c in row)
            vals = []
            for off in (0, 8, 16):
                g = [bytes_written.get(base + off + k) for k in range(8)]
                if all(c is not None for c in g):
                    vals.append(struct.unpack("<Q", bytes(g))[0])
            tag = ""
            if len(vals) == 3 and all(v for v in vals):
                nm = e.str_at_vaddr(vals[0], 60)
                sg = e.str_at_vaddr(vals[1], 60)
                tag = "   ⇒ name=%r  sig=%r  fn=%s" % (nm, sg, hex(vals[2]))
            out.append("    %+4d %-52s %-26s%s" % (base - s0, hexs, asc, tag))
    return "\n".join(out)


def selftest():
    """o teste que importa: MOVZ+MOVK compõem um inteiro de 64 bits e STR/STRB têm width 8/1"""
    st = {}
    for x in (0xD2800000 | (0 << 21) | (0x7788 << 5) | 9,
              0xF2800000 | (1 << 21) | (0x5566 << 5) | 9,
              0xF2800000 | (2 << 21) | (0x4342 << 5) | 9,
              0xF2800000 | (3 << 21) | (0x4142 << 5) | 9):
        op, f = decode(x, 0)
        if op == "MOVZ":
            st[f[0]] = f[2] << (f[1] * 16)
        else:
            cur = st.get(f[0])
            pos = f[1] * 16
            st[f[0]] = (cur & ~(0xFFFF << pos)) | (f[2] << pos)
    assert st[9] == 0x4142434255667788, hex(st[9])
    # ADRP+ADD deve montar a página pedida (foi o bug da heurística agregada)
    pc = 0x1000
    w_adrp = 0x90000008 | (2 << 5)          # imhi=2 => alvo = (0x1000>>12 + 2) << 12 = 0x9000
    assert decode(w_adrp, pc) == ("ADRP", (8, 0x9000)), decode(w_adrp, pc)
    add = 0x91400000 | (2 << 10) | (8 << 5) | 8   # sh=1, imm12=2 -> +0x2000
    assert decode(add, pc) == ("ADDi", (8, 8, 0x2000)), decode(add, pc)
    assert decode(0xF9002509, pc) == ("STR", (9, 8, 0x48, 8))
    assert decode(0x39000109, pc) == ("STR", (9, 8, 0, 1))
    # BL deve invalidar x0..x18 e nada além disso
    st2 = {0: 1, 19: 2}
    assert decode(0x94000001, 0)[0] == "BL"
    print("PASS: composição MOVZ/MOVK, ADRP+ADD, widths de STR e BL")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib", nargs="?")
    ap.add_argument("--at", default="")
    ap.add_argument("--to", default="")
    ap.add_argument("--pass", dest="passes", type=int, default=3)
    ap.add_argument("--trace", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    e = Elf(a.lib)
    lo, hi = (int(x, 16) for x in a.at.split("-"))
    spans = []
    for part in [x for x in a.to.split(",") if x]:
        s0, s1 = part.split("-")
        spans.append((int(s0, 16), int(s1, 16), part))
    bw, notes, total, hits = run(e, lo, hi, spans, passes=a.passes, trace=a.trace)
    print("instruções decodificadas: %d (passes=%d)" % (total, a.passes))
    print(render(e, bw, spans))
    if a.trace:
        print("autores (pc : nº de escritas na região):")
        for pc, c in hits.most_common(24):
            print("  %-10s %d" % (hex(pc), c))
        for x in notes[:40]:
            print("  trace:", x)


if __name__ == "__main__":
    main()
