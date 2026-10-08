#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""jni_fn_survey.py — censo de funções-candidatas a `fn` de tabela JNI, por comportamento.

Por que isto existe: nas tabelas da arena de `libgame.so` os campos `name`/`signature`/`fn` são
montados por predicado opaco (`MOVZ`/`MOVK` + `AND`/`ORR`/`SUB` com spill na pilha), então *contar*
entradas da tabela não identifica nada — os rótulos de método só existem em runtime (M6.11 §5). O
que **dá** para separar estaticamente é o corpo de cada `fn`: um nativo declarado `(BBB)V` recebe
`byte`s em registradores e não chama acessor de objeto nenhum; `(... )Ljava/lang/String;` precisa
de `NewStringUTF`; um que recebe `Landroid/app/NotificationChannel;` precisa mexer no objeto.

O script, para um arquivo `.so`:
  1. acha inícios de função em `.text` por prólogo (PACIBSP, `SUB x31,x31,#n`, `STP/STR` em `x31`);
  2. decodifica até o primeiro `RET` (orçamento `--budget`), coletando slots de `JNIEnv` usados,
     alvos de `BL` e número de palavras;
  3. aplica filtros pedidos (`--need 0x538 --not 0x548 --max-pal 300 --min-pal 6`);
  4. agrupa por proximidade (`--group 0x1000`) — 6 funções seguidas é a impressão digital de um
     lote de registro de uma classe de 6 nativos;
  5. opcionalmente (`--known ADDR,ADDR`) marca quem alcança endereços pedidos em até `--depth`
     níveis de `BL`.

Só lê o arquivo. Rótulos de slot conferidos contra `jni.h` do AOSP (4 reservs; `GetVersion`=4;
`RegisterNatives`=215 → `0x6b8`).

Uso:
  python3 scripts/jni_fn_survey.py LIB --need 0x538 --not 0x548
  python3 scripts/jni_fn_survey.py LIB --need 0x5c0,0x6e0 --group 0x2000 --json out.json
"""
import argparse
import json
import collections
import struct
import sys
import array
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from so_jni_scan import Elf                      # noqa: E402
import arena_write_tracker as A                  # noqa: E402

SLOT = {0x20: "GetVersion", 0x28: "DefineClass", 0x30: "FindClass", 0xb0: "DeleteGlobalRef",
        0xc8: "GetObjectClass", 0x108: "GetMethodID", 0x110: "CallObjectMethod",
        0x138: "CallBooleanMethod", 0x180: "CallIntMethod", 0x198: "CallLongMethod",
        0x1c0: "CallVoidMethod", 0x520: "GetStringUTFRegion", 0x538: "NewStringUTF",
        0x540: "GetStringUTFLength", 0x548: "GetStringUTFChars", 0x550: "ReleaseStringUTFChars",
        0x558: "GetArrayLength", 0x5c0: "GetByteArrayElements", 0x600: "ReleaseByteArrayElements",
        0x638: "GetByteArrayRegion", 0x658: "SetByteArrayRegion", 0x6e0: "GetPrimitiveArrayCritical",
        0x6e8: "ReleasePrimitiveArrayCritical", 0x6b8: "RegisterNatives"}
OBJ = {0x548: "GetStringUTFChars", 0x538: "NewStringUTF"}


def prologue(e, va, o):
    """prólogo: PACIBSP / SUB sp / STP-ou-STR em x31 nos primeiros 6 palavras"""
    for i in range(6):
        if o + 4 * i + 4 > len(e.F):
            return False
        w, = struct.unpack_from("<I", e.F, o + 4 * i)
        if w == 0xD1A003FF or w == 0xD10003FF:
            return True
        d = A.decode(w, va + 4 * i)
        if not d:
            continue
        op, f = d
        if op in ("SUBi",) and f[0] == 31 and f[1] == 31:
            return True
        if op in ("STR", "STP") and (f[1] if op == "STR" else f[2]) == 31:
            return True
    return False


def body(e, va, o, budget):
    """devolve (palavras, slots, BLs) andando até o primeiro RET após 2 palavras"""
    slots, bls = [], []
    n = 0
    for i in range(budget):
        if o + 4 * i + 4 > len(e.F):
            break
        w, = struct.unpack_from("<I", e.F, o + 4 * i)
        d = A.decode(w, va + 4 * i)
        if d:
            op, f = d
            if op == "LDR" and f[1] == 8 and f[2] in SLOT:
                slots.append(f[2])
            elif op == "BL":
                bls.append(f[0])
            elif op == "RET" and i >= 2:
                return i + 1, slots, bls
        n = i + 1
    return n, slots, bls


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib")
    ap.add_argument("--need", default="", help="hex slots que a função DEVE usar (vírgula)")
    ap.add_argument("--not", dest="no", default="", help="hex slots que NÃO pode usar")
    ap.add_argument("--max-pal", type=int, default=300)
    ap.add_argument("--min-pal", type=int, default=4)
    ap.add_argument("--budget", type=int, default=400)
    ap.add_argument("--group", type=int, default=0x2000)
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--known", default="")
    ap.add_argument("--json", default="")
    ap.add_argument("--limit", type=int, default=60)
    a = ap.parse_args()

    e = Elf(a.lib)
    TX, TO, TS = e.sec[".text"][:3]
    need = set(int(x, 16) for x in a.need.split(",") if x)
    no = set(int(x, 16) for x in a.no.split(",") if x)
    known = set(int(x, 16) for x in a.known.split(",") if x)
    W = memoryview(e.F)[TO:TO + TS]

    # fases 1+2 numa varredura só: atribui cada palavra ao último prólogo confirmado.
    # Pré-filtro barato do prólogo: a palavra anterior tem de ser RET / B / BR / zero (fim da
    # função anterior) — sem isto eu chamaria prologue() ~1 M de vezes por engano.
    RET_W, STARTS = 0xD65F03C0, {}
    words = array.array("I")
    words.frombytes(bytes(W))
    cur = None
    nwd_of = {}
    for i2 in range(len(words)):
        w = words[i2]
        va = TX + 4 * i2
        prev = words[i2 - 1] if i2 else 0
        is_start = (w in (0xD1A003FF, 0xD10003FF) or (w & 0xFF000400) == 0xD1000000
                    or (w >> 24) == 0xA9)
        if is_start and (prev in (RET_W, 0) or (prev & 0xFC000000) in (0x14000000, 0x54000000)
                         or (prev & 0xFFFFFC1F) == 0xD61F0000):
            if prologue(e, va, TO + 4 * i2):
                cur = va
                STARTS[cur] = dict(fn=cur, nwd=0, slots=collections.Counter(), bls=[])
                continue
        if cur is None:
            continue
        d = A.decode(w, va)
        if d is None:
            STARTS[cur]["nwd"] += 1
            continue
        op, f = d
        if op == "LDR" and f[1] == 8 and f[2] in SLOT:
            STARTS[cur]["slots"][f[2]] += 1
        elif op == "BL" and len(STARTS[cur]["bls"]) < 48:
            STARTS[cur]["bls"].append(f[0])
        elif op == "RET":
            if STARTS[cur]["nwd"] >= 2:
                STARTS[cur]["nwd"] += 1
                cur = None
            else:
                STARTS[cur]["nwd"] += 1
        else:
            STARTS[cur]["nwd"] += 1
    sys.stderr.write("prólogos confirmados: %d\n" % len(STARTS))

    out = []
    for va, x in STARTS.items():
        ss = set(x["slots"])
        if need and not (need & ss):
            continue
        if no and (no & ss):
            continue
        if not (a.min_pal <= x["nwd"] <= a.max_pal):
            continue
        out.append(dict(fn=va, nwd=x["nwd"], slots=sorted(ss), bls=x["bls"],
                        ncalls=sum(x["slots"].values()) + len(x["bls"])))
    sys.stderr.write("funções que passam no filtro: %d\n" % len(out))

    # fase 3: 2 níveis de BL para marcar alcance de endereços conhecidos
    byva = {x["fn"]: x for x in out}
    for x in out:
        reach = []
        if known:
            frontier = list(x["bls"])
            for lvl in range(a.depth):
                nxt = []
                for b in frontier:
                    if b in known:
                        reach.append((lvl + 1, b))
                    y = byva.get(b)
                    if y and lvl + 1 < a.depth:
                        nxt += y["bls"]
                frontier = nxt
        x["reach"] = sorted(set(reach))

    # fase 4: agrupar por proximidade de endereço
    groups, cur = [], []
    for x in sorted(out, key=lambda z: z["fn"]):
        if cur and x["fn"] - cur[-1]["fn"] > a.group:
            groups.append(cur)
            cur = []
        cur.append(x)
    if cur:
        groups.append(cur)
    groups.sort(key=lambda g: -len(g))

    print("filtro: precisa de %s ; rejeita %s ; %d..%d palavras ; grupos por proximidade 0x%x"
          % (sorted(hex(s) for s in need), sorted(hex(s) for s in no), a.min_pal, a.max_pal, a.group))
    print("candidatas: %d em %d grupos\n" % (len(out), len(groups)))
    for g in groups[:a.limit]:
        print("### grupo %s..%s : %d funções" % (hex(g[0]["fn"]), hex(g[-1]["fn"]), len(g)))
        for x in g[:12]:
            print("   fn=%-9s %3dpal  %-38s BL:%s%s" % (
                hex(x["fn"]), x["nwd"],
                " ".join("%s×%d" % (SLOT[s], x["slots"].count(s)) for s in sorted(set(x["slots"])))
                or "(nenhum acessor)",
                " ".join(hex(t) for t in x["bls"][:6]) or "-",
                "  ** alcança " + ",".join(hex(k) for _, k in x["reach"]) if x["reach"] else ""))
        if len(g) > 12:
            print("   … +%d" % (len(g) - 12))
    if a.json:
        with open(a.json, "w") as fh:
            json.dump([{k: (hex(v) if isinstance(v, int) else v) for k, v in x.items()} for x in out],
                      fh, indent=1)
        sys.stderr.write("json: %s\n" % a.json)


if __name__ == "__main__":
    main()
