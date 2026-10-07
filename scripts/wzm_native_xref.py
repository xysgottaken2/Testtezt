#!/usr/bin/env python3
"""Analisador estático de `libgame.so` (WZM 3.10.0.19854920) sem dependências externas.

Só precisa de: ELF64 little-endian, AArch64, e as seções `.text`/`.rodata`/`.plt`/
`.dynsym`/`.dynstr`/`.rela.plt`. Feito para o caso em que o sandbox não tem `capstone`
nem `pyelftools` (ver D5 em docs/research/m6.2-pesquisa-estatica-binaria.md).

Uso:
  python3 scripts/wzm_native_xref.py [so] sect
  python3 scripts/wzm_native_xref.py [so] str  webview_url | str 0x507c33
  python3 scripts/wzm_native_xref.py [so] refs 0x11d1730
  python3 scripts/wzm_native_xref.py [so] dis  0x11d1730 200
  python3 scripts/wzm_native_xref.py [so] callers 0x11d1730
  python3 scripts/wzm_native_xref.py [so] flags 0x567c454 0x5f84a38 0x5f84a39
  python3 scripts/wzm_native_xref.py [so] reach 0x11d1730 --to 0x1597000-0x15b0000,0x2e28000-0x2e4c000 --max 8

Rótulos de evidência seguem a convenção do repositório (D1_MEDIDO / POS_META_CADEIA_ESTATICA_CONCLUIDA):
o que este script imprime é medição estática bruta; a interpretação fica nos docs.
"""
import sys, struct, array, bisect, collections

SO_DEFAULT = "analysis/wzm310/libgame.so"
X = [f"x{i}" for i in range(31)] + ["sp"]


class So:
    def __init__(self, path):
        self.F = open(path, "rb").read()
        F = self.F
        e_shoff, = struct.unpack_from("<Q", F, 0x28)
        esz, = struct.unpack_from("<H", F, 0x3A)
        n, = struct.unpack_from("<H", F, 0x3C)
        sidx, = struct.unpack_from("<H", F, 0x3E)

        def sh(i):
            return struct.unpack_from("<IIQQQQIIQQ", F, e_shoff + i * esz)

        ss = sh(sidx)

        def nm(off):
            p = ss[4] + off
            return F[p:F.index(b"\0", p)].decode("latin1")

        # nome -> (addr, off, size, type, flags)
        self.sec = {}
        for i in range(n):
            h = sh(i)
            self.sec[nm(h[0])] = (h[3], h[4], h[5], h[1], h[2])
        g = lambda k: self.sec[k]
        self.TX, self.TO, self.TS = g(".text")[0], g(".text")[1], g(".text")[2]
        if ".rodata" in self.sec:
            self.RO, self.ROFF, self.RS = g(".rodata")[0], g(".rodata")[1], g(".rodata")[2]
        else:  # WARZONE 3.3.4: dados read-only dentro de .data
            self.RO, self.ROFF, self.RS = (None,) * 3
        t = array.array("I")
        t.frombytes(F[self.TO:self.TO + self.TS])
        self.t = t
        self.N = len(t)
        # PLT stub -> nome importado
        self.stub2sym = {}
        if ".rela.plt" in self.sec and ".dynsym" in self.sec:
            ds, dn, rp, plt = g(".dynsym"), g(".dynstr"), g(".rela.plt"), g(".plt")

            def symname(idx):
                p = ds[1] + idx * 24
                st, = struct.unpack_from("<I", F, p)
                q = dn[1] + st
                return F[q:F.index(b"\0", q)].decode("latin1")

            for i in range(rp[2] // 24):
                _, ri, _ = struct.unpack_from("<QQQ", F, rp[1] + i * 24)
                idx = ri >> 32
                if idx:
                    self.stub2sym.setdefault(plt[0] + 32 + 16 * i, symname(idx))
        # tabela de funções: alvos internos de BL
        ent = {self.TX}
        for i in range(self.N):
            w = self.t[i]
            if (w & 0xFC000000) == 0x94000000:
                pc = self.TX + i * 4
                tg = pc + self.s32(w & 0x3FFFFFF, 26) * 4
                if self.TX <= tg < self.TX + self.TS and tg not in self.stub2sym:
                    ent.add(tg)
        self.ent = sorted(ent)
        # cache: pc -> word index
        self._pages = None

    @staticmethod
    def s32(x, b):
        return x - (1 << b) if x & (1 << (b - 1)) else x

    # ---------- utilitários de endereço ----------
    def page(self, pc, w):
        """endereço da página referenciada por ADRP"""
        imm = self.s32((((w >> 5) & 0x7FFFF) << 2) | ((w >> 29) & 3), 21)
        return (pc & ~0xFFF) + (imm << 12)

    def tooff(self, va):
        if self.RO is not None and self.RO <= va < self.RO + self.RS:
            return self.ROFF + (va - self.RO)
        for name, (a, o, s, _t, _f) in self.sec.items():
            if a and s and a <= va < a + s:
                return o + (va - a)
        return None

    def cstr(self, va):
        off = self.tooff(va)
        if off is None or off >= len(self.F) - 1:
            return None
        try:
            end = self.F.index(b"\0", off)
        except ValueError:
            return None
        s = self.F[off:end]
        if not (0 < len(s) < 400):
            return None
        try:
            return s.decode("ascii")
        except Exception:
            return None

    # ---------- índices ----------
    def xrefs(self, va, window=30):
        """instruções `ADRP xR, página(va); ADD xR, xR, #offset(va)`"""
        page, lo, out = va & ~0xFFF, va & 0xFFF, []
        for i in range(self.N):
            w = self.t[i]
            if (w & 0x9F000000) != 0x90000000:
                continue
            pc = self.TX + i * 4
            if self.page(pc, w) != page:
                continue
            rd = w & 0x1F
            for j in range(i + 1, i + 1 + window):
                if j >= self.N:
                    break
                w2 = self.t[j]
                if (w2 & 0xFFC00000) == 0x91000000 and ((w2 >> 5) & 0x1F) == rd:
                    if ((w2 >> 10) & 0xFFF) == lo:
                        out.append(pc)
                    break
                if (w2 & 0x9F000000) == 0x90000000 and (w2 & 0x1F) == rd:
                    break
        return out

    def callers(self, addr):
        out = []
        for i in range(self.N):
            w = self.t[i]
            if (w & 0xFC000000) == 0x94000000:
                pc = self.TX + i * 4
                if pc + self.s32(w & 0x3FFFFFF, 26) * 4 == addr:
                    out.append(pc)
        return out

    def fnstart(self, pc):
        k = bisect.bisect_right(self.ent, pc) - 1
        return self.ent[k] if k >= 0 else pc

    def fnrange(self, addr):
        k = bisect.bisect_left(self.ent, addr)
        s = addr if (k < len(self.ent) and self.ent[k] == addr) else self.ent[max(0, k - 1)]
        j = self.ent.index(s) + 1
        return s, (self.ent[j] if j < len(self.ent) else self.TX + self.TS)

    def refs(self, a, b):
        """strings, globais (ADRP+ADD) e BL do bloco [a,b)"""
        strs, globs, calls, regs = [], [], [], {}
        for i in range((a - self.TX) // 4, (b - self.TX) // 4):
            w = self.t[i]
            pc = self.TX + i * 4
            if (w & 0x9F000000) == 0x90000000:
                regs[w & 0x1F] = self.page(pc, w)
            elif (w & 0xFFC00000) == 0x91000000:
                rn = (w >> 5) & 0x1F
                if rn in regs:
                    v = regs[rn] + (((w >> 10) & 0xFFF) << ((w >> 22) & 3))
                    s = self.cstr(v)
                    if s is not None:
                        strs.append((v, s, pc))
                    elif v > 0x5000000:
                        globs.append((v, pc))
            elif (w & 0xFC000000) == 0x94000000:
                tg = pc + self.s32(w & 0x3FFFFFF, 26) * 4
                calls.append((self.stub2sym.get(tg), tg, pc))
        return strs, globs, calls

    def accesses(self, targets):
        """varre TODA a .text e reporta acessos com offset imediato
        (`ADRP reg, página` [+`ADD reg,reg,#off`] então `LDR/STR [reg,#off]`)
        que recaem exatamente sobre os endereços pedidos.
        Família: (w & 0x3B000000) == 0x39000000  (size=bits31:30, load=bit22)."""
        targets = set(targets)
        pages = collections.defaultdict(list)
        for t in targets:
            pages[t & ~0xFFF].append(t)
        hits = collections.defaultdict(list)
        for i in range(self.N):
            w = self.t[i]
            if (w & 0x9F000000) != 0x90000000:
                continue
            pc = self.TX + i * 4
            p = self.page(pc, w)
            if p not in pages:
                continue
            rd = w & 0x1F
            for j in range(i + 1, min(self.N, i + 12)):
                w2 = self.t[j]
                p2 = self.TX + j * 4
                if (w2 & 0x3B000000) == 0x39000000 and ((w2 >> 5) & 0x1F) == rd:
                    sc = (w2 >> 30) & 3
                    imm = ((w2 >> 10) & 0xFFF) << sc
                    for t in pages[p]:
                        if (t & 0xFFF) == imm:
                            hits[t].append((p2, "LDR" if (w2 >> 22) & 1 else "STR", 1 << sc))
                    break
                if (w2 & 0xFFC00000) == 0x91000000 and ((w2 >> 5) & 0x1F) == rd:
                    imm = ((w2 >> 10) & 0xFFF) << ((w2 >> 22) & 3)
                    for t in pages[p]:
                        if (t & 0xFFF) == imm:
                            hits[t].append((p2, "ADDbase", 0))
                    break
                if (w2 & 0x9F000000) == 0x90000000 and (w2 & 0x1F) == rd:
                    break
        return hits

    # ---------- decaimento mínimo ----------
    def dis(self, a, b):
        regs = {}
        for i in range((a - self.TX) // 4, (b - self.TX) // 4):
            w, pc = self.t[i], self.TX + i * 4
            mn = f".word 0x{w:08x}"
            note = ""
            if (w & 0x9F000000) == 0x90000000:
                regs[w & 0x1F] = self.page(pc, w)
                mn = f"ADRP x{w & 0x1F}, #{regs[w & 0x1F]:x}"
            elif (w & 0xFFC00000) == 0x91000000:
                rd, rn = w & 0x1F, (w >> 5) & 0x1F
                imm = ((w >> 10) & 0xFFF) << ((w >> 22) & 3)
                if rn in regs:
                    regs[rd] = regs[rn] + imm
                else:
                    regs.pop(rd, None)
                v = regs.get(rd)
                s = self.cstr(v) if v else None
                mn = f"ADD x{rd}, x{rn}, #{imm:x}"
                note = f'   ; "{s[:44]}"' if s is not None else (f"   ; ->0x{v:x}" if v else "")
            elif (w & 0xFC000000) == 0x94000000:
                tg = pc + self.s32(w & 0x3FFFFFF, 26) * 4
                mn = f"BL {tg:x}" + (f"  <{self.stub2sym[tg]}>" if tg in self.stub2sym else "")
                regs = {r: regs[r] for r in (8, 9, 19, 20, 21, 22) if r in regs}
            elif (w & 0xFC000000) == 0x14000000:
                mn = f"B {pc + self.s32(w & 0x3FFFFFF, 26) * 4:x}"
            elif w == 0xD65F03C0:
                mn = "RET"
            elif (w & 0x7FC00000) == 0x52800000:
                mn = f"MOVZ x{w & 0x1F}, #{(w >> 5) & 0xFFFF:x}"
            elif (w & 0x3B800000) == 0x38000000:
                op = {(0x20): "STRB", (0x24): "LDRB", (0x28): "LDRSB"}.get((w >> 24) & 0x3F, "LDSTB")
                rn, imm = (w >> 5) & 0x1F, (w >> 10) & 0xFFF
                mn = f"{op} x{w & 0x1F}, [x{rn}, #{imm:x}]"
                if rn in regs:
                    note = f"   ; ->0x{regs[rn] + imm:x}"
            elif (w & 0x7FC00000) in (0xB9400000, 0xB9000000, 0xF9400000, 0xF9000000):
                is64 = (w >> 30) == 3
                size = 8 if is64 else 4
                rn, imm = (w >> 5) & 0x1F, ((w >> 10) & 0xFFF) * size
                mn = ("LDR " if not ((w >> 22) & 1) else "STR ") + f"x{w & 0x1F}, [x{rn}, #{imm:x}]"
                if rn in regs:
                    v = regs[rn] + imm
                    note = f"   ; ->0x{v:x}"
                    s = self.cstr(v)
                    if s:
                        note += f' ; "{s[:32]}"'
            elif (w & 0x7F800000) == 0x71000000:
                mn = f"CMP x{(w >> 5) & 0x1F}, #{((w >> 10) & 0xFFF) << ((w >> 22) & 3):x}"
            elif (w & 0xFF000010) in (0x37000000, 0x36000000):
                kind = "TBNZ" if (w & 0xFF000010) == 0x37000000 else "TBZ"
                off = self.s32((w >> 5) & 0x7F, 7) << (((w >> 31) & 1) + 1)
                mn = f"{kind} x{w & 0x1F}, #{(w >> 19) & 0x1F}, #{pc + off:x}"
            elif (w & 0x7E000000) == 0x54000000:
                cond = ["eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc",
                        "hi", "ls", "ge", "lt", "gt", "le"][w & 0xF]
                mn = f"B.{cond} {pc + self.s32((w >> 5) & 0x7FFFF, 21) * 4:x}"
            print(f"  {pc:x}  {mn}{note}")


# ------------------------- subcomandos -------------------------
def cmd_sect(a, _args):
    print(f"{'seção':14s} {'VA':>12s} {'off':>12s} {'size':>12s}")
    for k, (va, off, sz, _t, _f) in a.sec.items():
        print(f"{k:14s} {va:12x} {off:12x} {sz:12x}")
    print(f"\ntamanho .text = 0x{a.TS:x}; PLT stubs nomeados = {len(a.stub2sym)}; funções = {len(a.ent)}")


def cmd_str(a, args):
    key = args[0]
    if key.startswith("0x"):
        va = int(key, 16)
        show(a, va)
        return
    pat = key.encode()
    hits = []
    F, RO, RS, ROFF = a.F, a.RO, a.RS, a.ROFF
    i = 0
    while True:
        i = F.find(pat, i)
        if i < 0:
            break
        if RO and ROFF <= i < ROFF + RS:
            p = i
            while p > ROFF and F[p - 1]:
                p -= 1
            va = RO + (p - ROFF)
            if not hits or hits[-1][0] != va:
                hits.append((va, a.cstr(va)))
        i += 1
    print(f"{len(hits)} ocorrência(s) de {key!r} em .rodata (limitado a 40):")
    for va, s in hits[:40]:
        show(a, va, s)


def show(a, va, s=None):
    if s is None:
        s = a.cstr(va)
    xs = a.xrefs(va)
    fns = sorted({a.fnstart(p) for p in xs})
    print(f"\n0x{va:x}  \"{(s or '')[:70]}\"")
    print(f"   xrefs={len(xs)} {[hex(x) for x in xs[:6]]}")
    for f in fns[:10]:
        fs, fe = a.fnrange(f)
        print(f"   fn {f:x} ({(fe - fs) // 4} instr) refs a {va:x}")


def cmd_refs(a, args):
    f = int(args[0], 16)
    s, e = a.fnrange(f)
    strs, globs, calls = a.refs(s, e)
    print(f"função {s:x}..{e:x} ({(e - s) // 4} instr)")
    print(f"\nstrings ({len(strs)}):")
    for v, t, pc in sorted(set(strs)):
        print(f"   {v:x} @{pc:x}  \"{t[:64]}\"")
    print(f"\nglobais ({len(set(v for v, _ in globs))}): " + " ".join(f"{v:x}" for v in sorted(set(v for v, _ in globs))[:30]))
    print(f"\nBL ({len(set(t for _, t, _ in calls))} alvos):")
    for n, t, pc in sorted(set(calls), key=lambda x: x[1])[:60]:
        print(f"   {t:x}" + (f"  <{n}>" if n else f"  [{a.fnstart(t):x}]") + f"   (de {pc:x})")


def cmd_dis(a, args):
    f = int(args[0], 16)
    n = int(args[1]) if len(args) > 1 else 200
    s, e = a.fnrange(f)
    a.dis(s, min(e, s + n * 4))


def cmd_callers(a, args):
    t = int(args[0], 16)
    ps = a.callers(t)
    print(f"{len(ps)} chamadas para {t:x}, em {len({a.fnstart(p) for p in ps})} funções:")
    for f in sorted({a.fnstart(p) for p in ps}):
        s, e = a.fnrange(f)
        strs = a.refs(s, e)[0]
        print(f"   {f:x} ({(e - s) // 4:6d} instr)  {[t[:28] for _, t, _ in sorted(set(strs))][:3]}")


def cmd_flags(a, args):
    tg = [int(x, 16) for x in args]
    hits = a.accesses(tg)
    for t in tg:
        hs = hits.get(t, [])
        fns = sorted({a.fnstart(p) for p, _, _ in hs})
        print(f"\n=== {t:x}: {len(hs)} acessos em {len(fns)} funções ===")
        for pc, kind, sz in hs:
            print(f"   {kind}{sz} @{pc:x}  [fn {a.fnstart(pc):x}]")
        print("   funções:", " ".join(f"{x:x}" for x in fns))


def cmd_reach(a, args):
    start = int(args[0], 16)
    to, mx = None, 6
    if "--to" in args:
        to = [tuple(int(x, 16) for x in r.split("-")) for r in args[args.index("--to") + 1].split(",")]
    if "--max" in args:
        mx = int(args[args.index("--max") + 1])
    print("indizando grafo de BL ...", flush=True)
    g = collections.defaultdict(set)
    for i in range(a.N):
        w = a.t[i]
        if (w & 0xFC000000) != 0x94000000:
            continue
        pc = a.TX + i * 4
        tg = pc + a.s32(w & 0x3FFFFFF, 26) * 4
        if a.TX <= tg < a.TX + a.TS:
            g[a.fnstart(pc)].add(a.fnstart(tg))
    inb = lambda x: to is None or any(lo <= x < hi for lo, hi in to)
    print(f"de {start:x}: caminhos até a região {to} (máx {mx} hops)")
    par = {start: None}
    frontier = [start]
    hit = []
    for h in range(mx):
        nxt = []
        for f in frontier:
            for t in g.get(f, ()):
                if t in par:
                    continue
                par[t] = f
                nxt.append(t)
                if to and inb(t):
                    hit.append((t, h + 1))
        frontier = nxt
    if to:
        nedges = sum(1 for f in g if not inb(f) for t in g[f] if inb(t))
        print(f"   nós-alcance={len(hit)}; arestas DIRETAS de fora p/ região={nedges}")
        for t, h in sorted(hit, key=lambda x: x[1])[:12]:
            pth, cur = [], t
            while cur is not None:
                pth.append(cur)
                cur = par[cur]
            print(f"   {h} hops: " + " → ".join(f"{x:x}" for x in reversed(pth)))
        # arestas diretas entre os dois lados
        print("\narestas DIRETAS (lado de origem -> alvo):")
        src = lambda f: inb(f)
        for f in sorted(g):
            if src(f):
                continue
            for t in sorted(g[f]):
                if inb(t):
                    print(f"   {f:x} → {t:x}")


def main():
    argv = sys.argv[1:]
    so = SO_DEFAULT
    if argv and argv[0].endswith(".so"):
        so, argv = argv[0], argv[1:]
    if not argv:
        print(__doc__)
        return 2
    a = So(so)
    {"sect": cmd_sect, "str": cmd_str, "refs": cmd_refs, "dis": cmd_dis,
     "callers": cmd_callers, "flags": cmd_flags, "reach": cmd_reach}[argv[0]](a, argv[1:])


if __name__ == "__main__":
    main()
