#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""jni_fn_fingerprint.py — casa as tabelas `JNINativeMethod` da arena com um perfil JNI esperado.

Motivação (M6.11): a tabela `n=6` de `libgame.so` foi rejeitada como `JsBridge` porque os 6 `fn`
dela consomem `jstring` (`GetStringUTFChars`/`ReleaseStringUTFChars`) enquanto os nativos do DEX
recebem `byte` (`(BBB)V` etc.). Contar entradas não serve de seletor — o `n` lido do `MOVZ w3`
mais próximo pode ser só metade de um valor montado por predicado opaco. Este script usa o outro
lado: o **comportamento** de cada `fn`.

Método:
  1. reconstrói as 17 janelas de tabela por execução simbólica (`arena_write_tracker`), limitada
     à região dos construtores (para não sobregravar bytes com estado de fora);
  2. para cada entrada, lê `name`/`signature`/`fn`; exige que `fn` aponte para um início de função
     plausível em `.text` (prólogo real) — senão marca `descartado`;
  3. descreve cada `fn`: nº de palavras até o `RET`, slots de `JNIEnv` usados, alvos de `BL`;
  4. pontua contra um perfil pedido (`--profile jsbridge`) e alcança 2 níveis de `BL` para ver se
     chega a endereços conhecidos do dispatcher/CDNI.

Nada aqui é escrito no binário e nada é implementado no projeto: só leitura.

Uso:
  python3 scripts/jni_fn_fingerprint.py LIB --profile jsbridge
  python3 scripts/jni_fn_fingerprint.py LIB --tables 0xd4e7498:6 --depth 2
"""
import argparse
import collections
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from so_jni_scan import Elf                       # noqa: E402
import arena_write_tracker as A                   # noqa: E402

# deslocamentos de JNINativeInterface_ conferidos contra jni.h do AOSP (base 4 = GetVersion)
SLOT = {0x20: "GetVersion", 0x30: "FindClass", 0x28: "DefineClass", 0xb0: "DeleteGlobalRef",
        0xc8: "GetObjectClass", 0x108: "GetMethodID", 0x110: "CallObjectMethod",
        0x138: "CallBooleanMethod", 0x180: "CallIntMethod", 0x198: "CallLongMethod",
        0x1c0: "CallVoidMethod", 0x538: "NewStringUTF", 0x540: "GetStringUTFLength",
        0x548: "GetStringUTFChars", 0x550: "ReleaseStringUTFChars", 0x558: "GetArrayLength",
        0x5c0: "GetByteArrayElements", 0x600: "ReleaseByteArrayElements",
        0x638: "GetByteArrayRegion", 0x658: "SetByteArrayRegion",
        0x6e0: "GetPrimitiveArrayCritical", 0x6e8: "ReleasePrimitiveArrayCritical",
        0x6b8: "RegisterNatives", 0x120: "CallObjectMethodV", 0x520: "GetStringUTFRegion"}
KNOWN = {0x15af1d4: "cdni_httpServer", 0x159f524: "chamador_cdni_httpServer",
         0x11ce85c: "consulta", 0x11ce878: "consulta2", 0x11ce6d4: "saveKVP",
         0x11dd028: "use_dev_cdn", 0x11dcfc4: "proceed_anyway", 0x11dd04c: "dont_change",
         0x2bfd04c: "persist_kvp(tos)", 0x11cd710: "trio{chave,prop,handler}",
         0x11dc520: "0x11dc520(candidato_dispatcher)"}
# as 17 tabelas da arena, com o n lido em m6.10 §2 (n NÃO é confiável; serve só de janela)
ARENA = [(0xd4e6a58, 1), (0xd4e6ae0, 1), (0xd4e6b78, 3), (0xd4e6cd0, 1), (0xd4e6d50, 2),
         (0xd4e6e18, 22), (0xd4e73b0, 1), (0xd4e7498, 6), (0xd4e7700, 2), (0xd4e77a0, 2),
         (0xd4e7868, 5), (0xd4e79c8, 3), (0xd4e7aa8, 1), (0xd4e7b20, 2), (0xd4e7bd8, 12),
         (0xd4e80b0, 1), (0xd4e8128, 1)]
BUILDERS = (0x25e6000, 0x2620000)      # região dos construtores (cauda de JNI_OnLoad e vizinhos)

PROLOG = (0xd1a003ff, )                # PACIBSP


def is_func_start(e, va):
    """início de função plausível: PACIBSP, ou STP x29,x30 / SUB sp antes das primeiras palavras"""
    o = e.v2o(va)
    if o is None or o + 32 > len(e.F):
        return False
    for i in range(8):
        w, = struct.unpack_from("<I", e.F, o + 4 * i)
        if w in PROLOG:
            return True
        if (w & 0x7FC07FFF) == 0x290D4BFD or (w & 0x7FC07FFF) == 0x290D4BFD:
            return True
        if (w & 0xFFC00000) == 0xF9400000:
            pass
        if (w & 0x7FC00000) == 0xA9000000 and (w & 0x1F) == 29:      # STP x?,x?,[x31,#imm]
            return True
        if (w & 0xFF000400) == 0xD1000000 and (w & 0x1F) == 31:      # SUB x31,x31,#imm
            return True
    return False


def describe(e, fn, budget=220):
    """slots de JNIEnv + alvos de BL de uma função, até o primeiro RET após o prólogo"""
    o = e.v2o(fn)
    slots, bls, n = [], [], 0
    if o is None:
        return n, slots, bls
    for i in range(budget):
        if o + 4 * i + 4 > len(e.F):
            break
        w, = struct.unpack_from("<I", e.F, o + 4 * i)
        d = A.decode(w, fn + 4 * i)
        if d:
            op, f = d
            if op == "LDR" and f[1] == 8 and f[2] in SLOT:
                slots.append(f[2])
            elif op == "BL":
                bls.append(f[0])
            elif op == "RET" and i >= 2:
                n = i + 1
                break
        n = i + 1
    return n, slots, bls


def follow(e, fn, depth, seen=None):
    """BL-alvos a `depth` níveis; devolve (conjunto de alvos, {alvo: nome_conhecido})"""
    if seen is None:
        seen = set()
    out, hits = [], {}
    frontier = [fn]
    for lvl in range(depth):
        nxt = []
        for f in frontier:
            if f in seen:
                continue
            seen.add(f)
            _, _, bls = describe(e, f, budget=220)
            for b in bls:
                out.append((lvl + 1, f, b))
                if b in KNOWN:
                    hits[b] = KNOWN[b]
                o = e.v2o(b)
                if o is not None and is_func_start(e, b):
                    nxt.append(b)
        frontier = nxt
    return out, hits


PROFILES = {
    # JsBridge real (classes.dex): os 6 nativos e o que cada um *exige* de JNIEnv
    "jsbridge": {
        "must_any": [0x538],              # nativeLocalize devolve String -> NewStringUTF
        "must_not": [0x548],              # params byte[]/byte não pedem GetStringUTFChars
        "expect_mild": True,              # funções pequenas, poucos ou nenhum acessor
        "note": "nativeQuery(BBB)V nativePost(BB)V nativeSubscribe(BBB)J "
                "nativeSaveBoolean(BBB)Z nativeLocalize(B)String "
                "nativeUnsubscribe(BBBLandroid/app/NotificationChannel;)Z",
    }
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lib")
    ap.add_argument("--tables", default="", help="override: base:n,base:n,...")
    ap.add_argument("--at", default="", help="override da janela de construtores: lo-hi")
    ap.add_argument("--profile", default="")
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--pass", dest="passes", type=int, default=1)
    ap.add_argument("--show-slots", action="store_true")
    a = ap.parse_args()

    e = Elf(a.lib)
    if a.tables:
        tables = []
        for part in a.tables.split(","):
            b, n = part.split(":")
            tables.append((int(b, 16), int(n, 16)))
    else:
        tables = ARENA
    lo, hi = BUILDERS
    if a.at:
        lo, hi = (int(x, 16) for x in a.at.split("-"))
    spans = [(b, b + 24 * n, "t%x" % b) for b, n in tables]
    # janelas largas (n lido pode estar errado): cobre o dobro de entradas para achar mais fn
    spans = [(b, b + 24 * max(n, 24), "t%x/n%d" % (b, n)) for b, n in tables]
    sys.stderr.write("reconstruindo %d janelas em [%s..%s) ...\n" % (len(spans), hex(lo), hex(hi)))
    bw, notes, tot, hits = A.run(e, lo, hi, spans, passes=a.passes, max_instr=10 ** 7)
    sys.stderr.write("palavras decodificadas: %d; bytes reconstruídos: %d\n"
                     % (tot, sum(1 for v in bw.values() if v is not None)))

    def q(va):
        g = [bw.get(va + k) for k in range(8)]
        return struct.unpack("<Q", bytes(g))[0] if all(c is not None for c in g) else None

    prof = PROFILES.get(a.profile)
    rows = []
    for b, n, lab in [(x[0], y, x[2]) for x, y in zip(spans, [t[1] for t in tables])]:
        ents = []
        for k in range(24):                       # varre até 24 entradas por tabela
            base = b + 24 * k
            nm, sg, fn = q(base), q(base + 8), q(base + 16)
            if fn is None or not (0 < fn < 0x60000000) or not is_func_start(e, fn):
                continue
            nwd, slots, bls = describe(e, fn)
            ents.append(dict(k=k, name=nm, sig=sg, fn=fn, nwd=nwd, slots=slots, bls=bls))
        score, why = 0, []
        if prof:
            sset = set(sum([x["slots"] for x in ents], []))
            if any(s in sset for s in prof["must_any"]):
                score += 3
                why.append("tem slot obrigatório (%s)" % ",".join(hex(s) for s in prof["must_any"] if s in sset))
            bad = [s for s in prof["must_not"] if s in sset]
            if bad:
                score -= 3 * len(bad)
                why.append("usa slot proibido %s" % [SLOT[x] for x in bad])
            if ents and max(x["nwd"] for x in ents) < 150:
                score += 1
                why.append("funções pequenas/locais")
            big = [x for x in ents if x["nwd"] >= 200]
            if big:
                why.append("%d fn além do orçamento de decodificação" % len(big))
        for x in ents:
            x["reach"], x["known"] = follow(e, x["fn"], a.depth)
        anyknown = sorted({(k, v) for x in ents for k, v in x["known"].items()})
        if anyknown:
            score += 2
            why.append("chega a %s" % ", ".join("%s@%s" % (v, hex(k)) for k, v in anyknown))
        print("\n== tabela %s : %d fn legíveis (n lido=%d) | score=%d %s =="
              % (lab, len(ents), n, score, " ".join(why)))
        for x in ents:
            ss = collections.Counter(x["slots"])
            txt = " ".join("%s×%d" % (SLOT.get(s, hex(s)), c) for s, c in ss.items()) or "(nenhum acessor)"
            print("   [%2d] fn=%-9s %3d pal  slots: %-46s BL:%s"
                  % (x["k"], hex(x["fn"]), x["nwd"], txt,
                     " ".join(hex(t) + ("*" if t in KNOWN else "") for t in x["bls"][:8]) or "-"))
            if a.show_slots and x["reach"]:
                print("        nível2: %s" % " ".join(
                    "d%d:%s→%s%s" % (lv, hex(f), hex(t), "*" if t in KNOWN else "")
                    for lv, f, t in x["reach"][:14]))
        rows.append((score, lab, len(ents), anyknown))
    if rows:
        print("\n### ranking (score, tabela, nº de fn, alvos conhecidos) ###")
        for sc, lab, ne, kn in sorted(rows, reverse=True):
            print("  %+d  %-16s fn=%2d  conhecidos=%s" % (sc, lab, ne, sorted({v for _, v in kn}) or "-"))


if __name__ == "__main__":
    main()
