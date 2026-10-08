#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""arena_const_bind.py — liga cada `fn`/`name`/`signature` da arena ao seu valor por *composição local*.

Problema (M6.11): as tabelas `JNINativeMethod` de `libgame.so` ficam em `.bss` e o que as escreve é
código ofuscado. Uma propagação linear de estado por função inteira é contaminada por laços e por
estado que entra de fora — vi isso acontecer (um `fn` de `0x25f66f0` apareceu no slot `name`).

Aqui não há estado global: para cada `STR` cujo registrador-base vem de um par `ADRP`+`ADD` que
monta um endereço dentro da arena, o valor gravado é resolvido **olhando para trás, no mesmo bloco**,
pelas únicas formas que o ofuscador usa de produzir um ponteiro:
  * corrente `MOVZ x#lo16` + `MOVK x#…,lsl #16/32/48`  (valor imediato completo, 4 palavras);
  * `MOV xR,xS` (encadeia para a corrente de xS);
  * `ADRP`+`ADD` (ponteiro para uma página do próprio arquivo).
Janela de busca para trás: `--back` palavras (padrão 60) e paramos no `BL`/`RET` mais próximo, para
não atravessar fronteira de chamada.

Saída: por tabela, entradas de 24 B com `name`/`signature`/`fn` resolvidos e, para cada `fn`, os
slots de `JNIEnv` que ele usa (nível 0 e 1 de `BL`), além de uma pontuação contra o perfil do
`JsBridge` do DEX (`nativeLocalize` precisa de `NewStringUTF` `0x538`; parâmetros `byte` não pedem
acessor nenhum, e `GetStringUTFChars` `0x548` é sinal de *outra* classe).

Só lê o binário; não implementa nada no projeto.

Uso:
  python3 scripts/arena_const_bind.py LIB --arena 0xd4e6000-0xd4e9000 --tables 0xd4e7498:6,...
  python3 scripts/arena_const_bind.py LIB            # usa as 17 janelas de m6.10 §2
"""
import argparse
import collections
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from so_jni_scan import Elf                       # noqa: E402
from arena_write_tracker import decode, sign      # noqa: E402
from jni_fn_survey import body, SLOT, prologue    # noqa: E402

ARENA17 = [(0xd4e6a58, 1), (0xd4e6ae0, 1), (0xd4e6b78, 3), (0xd4e6cd0, 1), (0xd4e6d50, 2),
           (0xd4e6e18, 22), (0xd4e73b0, 1), (0xd4e7498, 6), (0xd4e7700, 2), (0xd4e77a0, 2),
           (0xd4e7868, 5), (0xd4e79c8, 3), (0xd4e7aa8, 1), (0xd4e7b20, 2), (0xd4e7bd8, 12),
           (0xd4e80b0, 1), (0xd4e8128, 1)]
KNOWN = {0x15af1d4: "cdni_httpServer", 0x159f524: "chamador_cdni_httpServer",
         0x11ce85c: "consulta", 0x11ce878: "consulta2", 0x11ce6d4: "saveKVP",
         0x11dd028: "use_dev_cdn", 0x11dcfc4: "proceed_anyway", 0x11dd04c: "dont_change",
         0x2bfd04c: "persist_kvp(tos)", 0x11cd710: "trio{chave,prop,handler}",
         0x11dc520: "candidato_dispatcher"}


def chains(words, tx):
    """todas as correntes MOVZ(+MOVK*) de 64 bits: pc_inicial -> valor composto"""
    out = {}
    n = len(words)
    i = 0
    while i < n:
        w = words[i]
        if (w & 0x7F800000) == 0x52800000 and (w >> 31) & 1:     # MOVZ 64-bit
            rd = w & 0x1F
            val = ((w >> 5) & 0xFFFF) << ((w >> 21) & 3) * 16
            hws = {(w >> 21) & 3}
            j = i + 1
            while j < n and j - i <= 8:
                x = words[j]
                if (x & 0x7F800000) == 0x72800000 and (x & 0x1F) == rd:
                    hw = (x >> 21) & 3
                    if hw not in hws:
                        hws.add(hw)
                        val |= ((x >> 5) & 0xFFFF) << (hw * 16)
                    j += 1
                    continue
                break
            if len(hws) >= 2:                       # corrente de verdade (não um zero extendido)
                out[i] = (rd, val)
            i = j
            continue
        i += 1
    return out


def resolve_reg(words, chains_at, pc_i, reg, back, tx, avals=None):
    """último valor conhecido de `reg` antes da instrução `pc_i` (sem atravessar BL/RET)"""
    for k in range(1, back + 1):
        j = pc_i - k
        if j < 0:
            return None
        w = words[j]
        d = decode(w, tx + 4 * j)
        if d is None:
            continue
        op, f = d
        if op in ("BL", "BLR", "RET", "BR"):
            return None
        if avals and j in avals:
            rd, val = avals[j]
            if rd == reg:
                return val
        if j in chains_at:
            rd, val = chains_at[j]
            if rd == reg:
                return val
        if op == "MOV" and f[0] == reg:
            return resolve_reg(words, chains_at, j, f[1], back, tx, avals)
        if op in ("ADDi", "SUBi") and f[0] == reg:
            b = resolve_reg(words, chains_at, j, f[1], back, tx, avals)
            if isinstance(b, int):
                return b + f[2] if op == "ADDi" else b - f[2]
            return None
        if op == "ADRP" and f[0] == reg:
            return None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib")
    ap.add_argument("--arena", default="0xd4e6000-0xd4e9000")
    ap.add_argument("--tables", default="")
    ap.add_argument("--back", type=int, default=60)
    ap.add_argument("--depth", type=int, default=1)
    a = ap.parse_args()
    e = Elf(a.lib)
    TX, TO, TS = e.sec[".text"][:3]
    words = collections.OrderedDict()
    raw = struct.unpack_from("<%dI" % (TS // 4), e.F, TO)
    a0, a1 = (int(x, 16) for x in a.arena.split("-"))
    tables = ARENA17
    if a.tables:
        tables = [(int(p.split(":")[0], 16), int(p.split(":")[1], 16)) for p in a.tables.split(",")]
    wins = [(b, b + 24 * max(n, 26)) for b, n in tables]

    def in_arena(v):
        return any(s <= v < t for s, t in wins)

    # 1) correntes de constantes
    ch = {}
    for i, (rd, val) in chains(raw, TX).items():
        ch[i] = (rd, val)
    sys.stderr.write("correntes MOVZ/MOVK compostas: %d\n" % len(ch))

    # 2) bases montadas por ADRP+ADD que caem nas janelas de tabela
    pend = {}                                     # reg -> (pc_adrp, page)
    bases = []                                    # (pc_add, reg, alvo)
    avals = {}                                    # pc_add -> (reg, alvo) para TODOS os pares
    for i in range(len(raw)):
        w = raw[i]
        d = decode(w, TX + 4 * i)
        if d is None:
            continue
        op, f = d
        if op == "ADRP":
            pend[f[0]] = (i, f[1])
        elif op == "ADDi" and f[1] in pend:
            tgt = pend[f[1]][1] + f[2]
            avals[i] = (f[0], tgt)
            if in_arena(tgt):
                bases.append((i, f[0], tgt))
    sys.stderr.write("ADRP+ADD apontando para janelas de tabela: %d\n" % len(bases))

    # 3) STRs que usam cada base, resolvendo o registrador-fonte por composição local
    ent = collections.defaultdict(dict)           # table_base -> {(entry, field): (value, pc)}
    for i, reg, tgt in bases:
        for k in range(1, 40):
            j = i + k
            if j >= len(raw):
                break
            d = decode(raw[j], TX + 4 * j)
            if d is None:
                continue
            op, f = d
            if op in ("STR", "STP"):
                base_r = f[1] if op == "STR" else f[2]
                imm = f[2] if op == "STR" else f[3]
                regs = [f[0]] if op == "STR" else [f[0], f[1]]
                if base_r != reg:
                    continue
                for m, r in enumerate(regs):
                    addr = tgt + imm + (8 * m if op == "STP" else 0)
                    val = resolve_reg(raw, ch, j, r, a.back, TX, avals)
                    for b, t2 in wins:
                        if b <= addr < t2:
                            key = ((addr - b) // 24, (addr - b) % 24 // 8)
                            ent[b][key] = (val, TX + 4 * j)
            elif op in ("BL", "BLR") and k > 6:
                break
    # 4) relatório + pontuação
    rows = []
    for b, n in tables:
        fields = ent.get(b, {})
        entrie = collections.defaultdict(dict)
        for (idx, fld), (val, pc) in list(fields.items()):
            if isinstance(idx, int):
                entrie[idx][fld] = (val, pc)
        prof = {"n": len(entrie), "nsu": 0, "gsc": 0, "arr": 0, "known": set(), "fn": []}
        for idx in sorted(entrie):
            fn = entrie[idx].get(2, (None, None))[0]
            nmv = entrie[idx].get(0, (None, None))[0]
            sgv = entrie[idx].get(1, (None, None))[0]
            if fn is None or fn >= 0x60000000 or e.v2o(fn) is None:
                print("   %-12s [%2d] name=%s sig=%s fn=%s  (fn fora de .text ou não resolvido)" % (
                    hex(b), idx, hex(nmv) if nmv else "--", hex(sgv) if sgv else "--",
                    hex(fn) if fn else "--"))
                continue
            prof["fn"].append((idx, fn))
            o = e.v2o(fn)
            if not prologue(e, fn, o):
                continue
            n0, s0, b0 = body(e, fn, o, 240)
            print("   %-12s [%2d] name=%s sig=%s" % (hex(b), idx, hex(nmv) if nmv else "--",
                                                      hex(sgv) if sgv else "--"))
            acc = collections.Counter(s0)
            kn = {x for x in b0 if x in KNOWN}
            if a.depth >= 1:
                for t in b0[:12]:
                    ot = e.v2o(t)
                    if ot is None or t in KNOWN:
                        continue
                    if prologue(e, t, ot):
                        _, s1, b1 = body(e, t, ot, 200)
                        acc.update(s1)
                        kn |= {x for x in b1 if x in KNOWN}
            if 0x538 in acc:
                prof["nsu"] += 1
            if 0x548 in acc:
                prof["gsc"] += 1
            if acc.keys() & {0x5c0, 0x6e0, 0x638, 0x600, 0x658, 0x558}:
                prof["arr"] += 1
            prof["known"] |= kn
            print("   %-12s [%2d] fn=%-9s %3dpal  %-52s  %s" % (
                hex(b), idx, hex(fn), n0,
                " ".join("%s×%d" % (SLOT.get(s, hex(s)), c) for s, c in sorted(acc.items()))
                or "(nenhum acessor de objeto)",
                "KNOWN:" + ",".join(KNOWN[x] for x in sorted(kn)) if kn else ""))
        sc = 3 * (1 if prof["nsu"] else 0) - 3 * (1 if prof["gsc"] else 0) + 2 * (1 if prof["known"] else 0) \
            + (1 if prof["arr"] else 0) + (2 if prof["n"] == 6 else 0)
        rows.append((sc, b, prof))
    print("\n### perfil JsBridge: 6 entradas, NewStringUTF≥1, sem GetStringUTFChars, com acessor de byte[]/objeto ###")
    for sc, b, p in sorted(rows, key=lambda r: -r[0]):
        print("  %+3d  tabela %-9s fn_lidos=%2d  NewStringUTF=%d  GetStringUTFChars=%d  "
              "acessor_byte[]=%d  alcança=%s"
              % (sc, hex(b), p["n"], p["nsu"], p["gsc"], p["arr"],
                 ",".join(sorted(KNOWN[x] for x in p["known"])) or "-"))


if __name__ == "__main__":
    main()
