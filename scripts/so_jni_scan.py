#!/usr/bin/env python3
"""Varredor ELF64/AArch64 para cadeia JNI (sem capstone/pyelftools — só stdlib).

Feito para responder, em qualquer `.so` do APK, onde ficam os métodos nativos de uma classe Java e
como as bibliotecas se ligam entre si. Todas as saídas são medições brutas; a classificação
(VERIFIED/PROBABLE/UNKNOWN) é feita no documento de análise.

O que ele faz, por biblioteca:
  A. cabeçalho ELF + tabela de seções + `.dynamic` (SONAME / DT_NEEDED / RPATH)
  B. `.dynsym`: exportações globais (nome, valor, tamanho) e importações; `--filter` casa por regex
  C. strings de interesse (.rodata/.data/.data.rel.ro/.got): lista de agulhas + offset do arquivo
     e, quando `--deep`, a string inteira e o tamanho da região varrida
  D. tabelas `JNINativeMethod` mineradas de `.rela.dyn`: trinomios {nome→.rodata, assinatura→"(",
     fn→.text} e também pares {nome, assinatura} (stride livre) — é assim que se acha registro
     estático sem depender de símbolo `Java_*`
  E. idioma de vtable `JNIEnv*`: `LDR x?,[x?,#imm]` seguido (em até `--win` instruções) de `BLR` no
     mesmo registrador ⇒ chamada `env->função`, com índice = imm/8 e nome traduzido pela ordem do
     JNINativeInterface (JNI 1.6). Também conta `BL`/`BLR` por função.
  F. resolve ADRP+ADD em cadeias (par linear simples) e reporta os alvos que são strings — é o que
     prova "esta função chama FindClass(\"com/foo/Bar\")"
  G. `--cross`: grafo de ligações entre as bibliotecas passadas (símbolo importado por X exportado
     por Y; e strings/`DT_NEEDED`), mais faixas de `.text` sobrepostas por tamanho de função

Uso:
  python3 scripts/so_jni_scan.py analysis/wzm310/libvmpc.so [mais.so ...] --deep
  python3 scripts/so_jni_scan.py <libs...> --cross --out /tmp/scan.txt
  python3 scripts/so_jni_scan.py <lib> --dis 0x1234 64      # desmontagem parcial (idiomas + BL/BR)
"""
import struct, sys, re, argparse, collections, bisect

# ordem dos ponteiros em JNINativeInterface (JNI 1.6); índice = offset/8
JNI_NAMES = {                       # indice -> nome, na ordem de `JNINativeInterface` (jni.h)
    4: "GetVersion", 5: "DefineClass", 6: "FindClass", 7: "FromReflectedMethod",
    8: "FromReflectedField", 9: "ToReflectedMethod", 10: "GetSuperclass", 11: "IsAssignableFrom",
    12: "ToReflectedField", 13: "Throw", 14: "ThrowNew", 15: "ExceptionOccurred",
    16: "ExceptionDescribe", 17: "ExceptionClear", 18: "FatalError", 19: "PushLocalFrame",
    20: "PopLocalFrame", 21: "NewGlobalRef", 22: "DeleteGlobalRef", 23: "DeleteLocalRef",
    24: "IsSameObject", 25: "NewLocalRef", 26: "EnsureLocalCapacity", 27: "AllocObject",
    28: "NewObject", 29: "NewObjectV", 30: "NewObjectA", 31: "GetObjectClass", 32: "IsInstanceOf",
    33: "GetMethodID",
    34: "CallObjectMethod", 35: "CallObjectMethodV", 36: "CallObjectMethodA",
    37: "CallBooleanMethod", 38: "CallBooleanMethodV", 39: "CallBooleanMethodA",
    40: "CallByteMethod", 41: "CallByteMethodV", 42: "CallByteMethodA",
    43: "CallCharMethod", 44: "CallCharMethodV", 45: "CallCharMethodA",
    46: "CallShortMethod", 47: "CallShortMethodV", 48: "CallShortMethodA",
    49: "CallIntMethod", 50: "CallIntMethodV", 51: "CallIntMethodA",
    52: "CallLongMethod", 53: "CallLongMethodV", 54: "CallLongMethodA",
    55: "CallFloatMethod", 56: "CallFloatMethodV", 57: "CallFloatMethodA",
    58: "CallDoubleMethod", 59: "CallDoubleMethodV", 60: "CallDoubleMethodA",
    61: "CallVoidMethod", 62: "CallVoidMethodV", 63: "CallVoidMethodA",
    84: "GetFieldID", 85: "GetObjectField", 86: "GetBooleanField", 87: "GetByteField",
    88: "GetCharField", 89: "GetShortField", 90: "GetIntField", 91: "GetLongField",
    92: "GetFloatField", 93: "GetDoubleField", 94: "SetObjectField", 100: "SetIntField",
    113: "GetStaticMethodID",
    114: "CallStaticObjectMethod", 115: "CallStaticObjectMethodV", 116: "CallStaticObjectMethodA",
    117: "CallStaticBooleanMethod", 120: "CallStaticByteMethod", 123: "CallStaticCharMethod",
    126: "CallStaticShortMethod", 129: "CallStaticIntMethod", 132: "CallStaticLongMethod",
    135: "CallStaticFloatMethod", 138: "CallStaticDoubleMethod", 141: "CallStaticVoidMethod",
    142: "GetStaticFieldID", 143: "GetStaticObjectField", 166: "GetStringUTFLength",
    167: "NewStringUTF", 168: "GetStringChars", 169: "GetStringUTFChars",
    170: "ReleaseStringUTFChars", 171: "GetArrayLength", 172: "NewArray",
    176: "GetObjectArrayElement", 177: "SetObjectArrayElement",
    215: "RegisterNatives", 216: "UnregisterNatives",
}

NEEDLES_DEFAULT = [
    "JsBridge", "jsBridge", "nativeQuery", "nativePost", "nativeSubscribe", "nativeUnsubscribe",
    "nativeSaveBoolean", "nativeLocalize", "callJavascriptFunction", "initializeJni", "JNI_OnLoad",
    "Java_", "RegisterNatives", "com/activision", "WebViewDialog", "loadWeb", "showAlert",
    "getWebUserAgent", "nativeBootstrapPermissionsResult", "nativeCDNiANRHandler",
    "nativeGetCDNiMetaUrl", "CDNIDebugInfo", "dlogError", "use_dev_cdn", "proceed_anyway", "dont_change",
]


class Elf:
    def __init__(self, path):
        self.path = path
        self.F = open(path, "rb").read()
        F = self.F
        if F[:4] != b"\x7fELF":
            raise SystemExit("não é ELF: " + path)
        self.e_machine = struct.unpack_from("<H", F, 18)[0]
        self.e_type = struct.unpack_from("<H", F, 16)[0]
        e_shoff = struct.unpack_from("<Q", F, 0x28)[0]
        esz = struct.unpack_from("<H", F, 0x3A)[0]
        n = struct.unpack_from("<H", F, 0x3C)[0]
        sidx = struct.unpack_from("<H", F, 0x3E)[0]

        def sh(i):
            return struct.unpack_from("<IIQQQQIIQQ", F, e_shoff + i * esz)
        ss = sh(sidx)

        def nm(off):
            p = ss[4] + off
            return F[p:F.index(b"\x00", p)].decode("latin1", "replace")
        self.sec = {}
        for i in range(n):
            h = sh(i)
            self.sec[nm(h[0])] = (h[3], h[4], h[5], h[1], h[2])   # addr, off, size, type, flags
        # phdrs p/ .bss (SHT_NOBITS) e para vaddr↔off robusto
        e_phoff = struct.unpack_from("<Q", F, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", F, 0x36)[0]
        e_phnum = struct.unpack_from("<H", F, 0x38)[0]
        # (p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align)
        self.loads = [struct.unpack_from("<IIQQQQQQ", F, e_phoff + i * e_phentsize)
                      for i in range(e_phnum)]

    def has(self, k):
        return k in self.sec and self.sec[k][2] > 0

    def v2o(self, v):
        """VA -> deslocamento no arquivo. phdr: (type, flags, offset, vaddr, paddr,
        filesz, memsz, align) = indices 0..7. Só `p_vaddr`/`p_filesz`/`p_offset` servem
        aqui; usar `p_flags`/`p_paddr` por engano desloca tudo (foi o bug que esta função
        tinha antes de 2026-10-08: as anotações de VA de `--sig`/`--dis` liam o arquivo
        no lugar errado em bibliotecas onde VA != off, como `libvmpc.so`)."""
        for p in self.loads:
            if p[0] == 1 and p[3] <= v < p[3] + p[5]:
                return p[2] + (v - p[3])
        for nm, (a, o, sz, t, f) in self.sec.items():
            if a and t in (1, 8) and a <= v < a + sz:
                return o + (v - a)
        return None

    def o2v(self, o):
        """deslocamento no arquivo -> VA (mesma tabela de phdrs de `v2o`)"""
        for p in self.loads:
            if p[0] == 1 and p[2] <= o < p[2] + p[5]:
                return p[3] + (o - p[2])
        for nm, (a, off, sz, t, f) in self.sec.items():
            if t in (1, 8) and off <= o < off + sz:
                return a + (o - off)
        return None

    def sec_of(self, v):
        for k, (a, o, s, t, f) in self.sec.items():
            if a and t in (1, 8) and a <= v < a + s:
                return k
        return "?"

    def str_at_vaddr(self, v, cap=200):
        if v is None:
            return None
        o = self.v2o(v)
        if o is None:
            return None
        e = self.F.find(b"\x00", o, o + cap + 1)
        if e < 0:
            return None
        return self.F[o:e].decode("latin1", "replace")

    def dyn(self):
        """entradas .dynamic como dict de tag→lista de valores"""
        out = collections.defaultdict(list)
        if not self.has(".dynamic"):
            return out
        a, o, s = self.sec[".dynamic"][:3]
        for i in range(s // 16):
            tag, val = struct.unpack_from("<QQ", self.F, o + 16 * i)
            out[tag].append(val)
        return out

    def syms(self):
        """(nome, bind, type, shndx, value, size) de .dynsym/.dynstr"""
        if not self.has(".dynsym"):
            return []
        a, o, s = self.sec[".dynsym"][:3]
        da, doff, dsz = self.sec[".dynstr"][:3]
        out = []
        for i in range(s // 24):
            p = o + 24 * i
            st_name, st_info, st_other, st_shndx = struct.unpack_from("<IBBH", self.F, p)
            value, size = struct.unpack_from("<QQ", self.F, p + 8)
            q = doff + st_name
            e = self.F.find(b"\x00", q)
            n = self.F[q:e].decode("latin1", "replace") if 0 <= st_name < dsz else ""
            out.append((n, st_info >> 4, st_info & 0xf, st_shndx, value, size))
        return out

    def rela(self, which=".rela.dyn"):
        if not self.has(which):
            return []
        a, o, s = self.sec[which][:3]
        out = []
        for i in range(s // 24):
            off, info, add = struct.unpack_from("<QQQ", self.F, o + 24 * i)
            out.append((off, info & 0xFFFFFFFF, info >> 32, add))
        return out

    def words(self, secname=".text"):
        a, o, s = self.sec[secname][:3]
        return a, o, [struct.unpack_from("<I", self.F, o + 4 * i)[0] for i in range(s // 4)]

    def words_at(self, vaddr, count):
        o = self.v2o(vaddr)
        if o is None:
            return None
        return [(vaddr + 4 * i, struct.unpack_from("<I", self.F, o + 4 * i)[0])
                for i in range(count) if o + 4 * i + 4 <= len(self.F)]

    def jni_calls(self, start, count):
        """idiomas prováveis de JNIEnv: LDR x,[x,#off8] ... BLR mesmo reg"""
        w = self.words_at(start, count)
        if w is None:
            return []
        out = []
        for i, (pc, word) in enumerate(w):
            if (word & 0xFFC003C0) == 0xF9400000 or (word & 0xFFC003C0) == 0xF97FF000 - 0x10000:
                imm = (word >> 10) & 0xFFF
                rt = word & 0x1F
                rn = (word >> 5) & 0x1F
                if imm and imm % 8 == 0 and imm <= 0x1000:
                    for j in range(i + 1, min(i + 7, len(w))):
                        w2 = w[j][1]
                        if (w2 & 0xFFFFFC1F) == 0xD63F0000 and ((w2 >> 5) & 0x1F) == rt:
                            idx = imm // 8
                            out.append((pc, rn, imm, idx, JNI_NAMES.get(idx, "?%d" % idx)))
                            break
        return out

    def adrp_add(self, start, count, want_str=True):
        """parelhas ADRP+ADD → resolve alvo; se cair em string, traz a string"""
        w = self.words_at(start, count)
        if w is None:
            return []
        page = {}
        out = []
        for pc, word in w:
            if (word & 0x9F000000) == 0x90000000:                       # ADRP
                rd = word & 0x1F
                immlo = (word >> 29) & 3
                immhi = (word >> 5) & 0x7FFFF
                imm = (immhi << 2) | immlo
                if imm & (1 << 20):
                    imm -= (1 << 21)
                page[rd] = (pc & ~0xFFF) + (imm << 12)
                continue
            if (word & 0xFFC00000) == 0x91000000:                       # ADD imm12 (lsl #0/12)
                rd = word & 0x1F
                rn = (word >> 5) & 0x1F
                imm12 = (word >> 10) & 0xFFF
                sh = (word >> 22) & 1
                if rd in page and rn == rd:
                    tgt = page[rd] + (imm12 << (12 if sh else 0))
                    s = self.str_at_vaddr(tgt) if want_str else None
                    out.append((pc, rd, tgt, s))
        return (out, w) if want_str else out


def sec_table(e, out):
    out("\n--- seções ---")
    for k, (a, o, s, t, f) in sorted(e.sec.items(), key=lambda kv: kv[1][1]):
        if not k:
            continue
        kind = {1: "PROGBITS", 2: "SYMTAB", 3: "STRTAB", 4: "RELA", 6: "DYNAMIC", 8: "NOBITS",
                0x6FFFFFFF: "ARM_EXIDX", 0x6FFFFFF0: "GNU_VERNEED", 0x6FFFFFF1: "GNU_VERDEF",
                0x6FFFFFF2: "GNU_VERNUM", 0x70000003: "ARCH_ATTR"}.get(t, hex(t))
        out("  %-18s addr=%-10s off=%-10s size=%-10s %s" % (k, hex(a), hex(o), hex(s), kind))


def dyn_table(e, out):
    d = e.dyn()
    if not d:
        out("\n--- .dynamic: ausente ---")
        return
    da = e.sec.get(".dynstr")
    doff = da[1] if da else 0

    def so(v):
        q = doff + v
        return e.F[q:e.F.index(b"\x00", q)].decode("latin1", "replace")
    out("\n--- .dynamic ---")
    names = {1: "NEEDED", 14: "SONAME", 30: "FLAGS", 3: "PLTGOT", 2: "PLTRELSZ", 15: "RELACOUNT",
             0x6FFFFFF9: "VERNEED", 0x6FFFFFFA: "VERNEEDNUM", 0x6FFFFFFB: "RELA", 28: "RUNPATHx",
             5: "STRTAB", 6: "SYMTAB", 10: "STRSZ", 11: "SYMENT", 12: "INIT", 13: "FINI",
             17: "INIT_ARRAYSZ", 27: "FINI_ARRAYSZ", 26: "DEBUG", 7: "RELSZ", 8: "RELENT"}
    for tag, vals in sorted(d.items()):
        if tag in (1, 14):
            out("  DT_%s: %s" % (names[tag], ", ".join(so(v) for v in vals)))
        elif tag in (12, 13):
            fn = e.str_at_vaddr(vals[0])
            out("  DT_%s: %s%s" % (names[tag], hex(vals[0]), ("  <- %s" % fn) if fn else ""))
        elif tag in (2, 15, 17, 27, 0x6FFFFFFA):
            out("  DT_%s: %s" % (names.get(tag, hex(tag)), [hex(v) for v in vals]))
    if 25 in d:
        out("  DT_FLAGS_1: %s" % [hex(v) for v in d[25]])
    for arr in (".init_array", ".fini_array"):
        if e.has(arr):
            aa, ao, asz = e.sec[arr][:3]
            ptrs = [struct.unpack_from("<Q", e.F, ao + 8 * i)[0] for i in range(asz // 8)]
            out("  %s: %s" % (arr, ", ".join("%s(=%s)" % (hex(p), e.str_at_vaddr(p) or "?")[:40]
                                             for p in ptrs[:8])))


def string_scan(e, needles, out, deep=False):
    out("\n--- strings de interesse (bytes planos nas seções de dados) ---")
    regions = [k for k in (".rodata", ".data", ".data.rel.ro", ".got", ".dynstr") if e.has(k)]
    total = 0
    found = {}
    for reg in regions:
        a, o, s = e.sec[reg][:3]
        blob = e.F[o:o + s]
        total += s
        for nd in needles:
            pat = nd.encode()
            p = 0
            while True:
                i = blob.find(pat, p)
                if i < 0:
                    break
                p = i + 1
                fo = o + i
                va = e.o2v(fo)
                full = e.str_at_vaddr(va) if va else None
                if full is None:
                    continue
                start_ok = (i == 0 or blob[i - 1] == 0)
                if not start_ok:
                    continue
                found.setdefault(nd, []).append((reg, fo, va, full))
    for nd in needles:
        v = found.get(nd, [])
        if not v:
            out("  %-32s — nenhum" % nd)
            continue
        out("  %-32s %d ocorr." % (nd, len(v)))
        for reg, fo, va, full in v[:14 if deep else 6]:
            out("      %s off=%s vaddr=%s  %r" % (reg, hex(fo), hex(va), full[:110]))
        if len(v) > (14 if deep else 6):
            out("      ... +%d" % (len(v) - (14 if deep else 6)))
    out("  (volume varrido: %.2f MB em %s)" % (total / 1e6, ", ".join(regions)))
    return found


def jni_tables(e, out):
    out("\n--- tabelas JNINativeMethod (mineradas de .rela.dyn) ---")
    rel = {}
    for off, rtype, sym, add in e.rela(".rela.dyn"):
        if rtype in (1027, 268436483, 257):     # R_AARCH64_RELATIVE (e variações)
            rel[off] = add
    if not rel:
        rel = {off: add for off, rt, _s, add in e.rela(".rela.dyn") if rt == 1027}
    ta = e.sec.get(".text", (0, 0, 0))
    def in_text(v):
        return ta[0] <= v < ta[0] + ta[2]
    namepat = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]{0,63}$")
    entries = []
    for off in sorted(rel):
        n = e.str_at_vaddr(rel[off])
        if not n or not namepat.match(n):
            continue
        s = e.str_at_vaddr(rel.get(off + 8))
        if not (s and s.startswith("(")):
            continue
        fn = rel.get(off + 16)
        entries.append((off, n, s, fn, in_text(fn) if fn is not None else False))
    if not entries:
        out("  nenhuma (nenhum par {nome, assinatura} relocado)")
        return []
    triples = [x for x in entries if x[3] is not None and x[4]]
    out("  pares {nome,assinatura} relocados: %d   dos quais trinomios com fn em .text: %d"
        % (len(entries), len(triples)))
    # agrupar em tabelas por contiguidade de 24 bytes
    tables, cur, prev = [], [], None
    for x in triples:
        if prev is not None and x[0] == prev + 24:
            cur.append(x)
        else:
            if cur:
                tables.append(cur)
            cur = [x]
        prev = x[0]
    if cur:
        tables.append(cur)
    out("  tabelas contíguas: %d  (tamanhos: %s)" % (len(tables), [len(t) for t in tables]))
    for t in tables:
        out("  TABELA @%s  (%d entradas)" % (hex(t[0][0]), len(t)))
        for off, n, s, fn, _ok in t:
            out("     %-30s %-52s fn=%s" % (n, s[:52], hex(fn)))
    out("  (fora das tabelas contíguas) entradas isoladas:")
    intab = {x[0] for t in tables for x in t}
    for off, n, s, fn, ok in entries:
        if off not in intab:
            out("     @%-12s %-30s %-40s fn=%s%s" % (hex(off), n, s[:40], hex(fn) if fn else ".", "" if ok else " (fn fora de .text)"))
    return triples


def env_idiom(e, out, only=None, limit=40):
    """Idioma real de chamada ART→JNI em AArch64: `LDR x?, [x?, #off]` (off = slot*8)
    seguido de `BLR x?`. A tabela de funções de `JNIEnv*` começa no offset 0 e tem
    ~0x560 bytes; `off` é `imm12<<3` no encoding de load com imediato."""
    out("\n--- idioma de vtable JNIEnv* (LDR x?,[x?,#slot*8] + BLR) em .text ---")
    if not e.has(".text"):
        out("  (.text ausente)")
        return []
    a, o, s = e.sec[".text"][:3]
    n = s // 4
    hits = collections.Counter()
    detail = []
    F = e.F
    pending = {}
    for i in range(n):
        word = struct.unpack_from("<I", F, o + 4 * i)[0]
        pc = a + 4 * i
        if (word & 0xFFC00000) == 0xF9400000:          # LDR Xt,[Xn,#pimm] 64 bits
            off = ((word >> 10) & 0xFFF) << 3
            rt, rn = word & 0x1F, (word >> 5) & 0x1F
            if 4 <= off <= 0x600:
                pending[rt] = (pc, rn, off)
        elif (word & 0xFFFFFC1F) == 0xD63F0000:         # BLR Xn
            rb = (word >> 5) & 0x1F
            if rb in pending:
                ppc, prn, off = pending.pop(rb)
                if pc - ppc <= 8 * 6:
                    idx = off // 8
                    nm = JNI_NAMES.get(idx, "slot%d" % idx)
                    hits[nm] += 1
                    detail.append((ppc, prn, off, nm))
                    if only and nm in only:
                        out("     %-22s pc=%s  (LDR x%d,[x%d,#%s])" % (nm, hex(ppc), rb, prn, hex(off)))
        elif (word & 0xFC000000) == 0x14000000:          # B (imediato) — termina o bloco
            pending.clear()
    out("  chamadas pela tabela de `JNIEnv*` em .text: total=%d" % sum(hits.values()))
    for nm, c in hits.most_common(28):
        out("     %-26s %5d" % (nm, c))
    if not hits:
        out("     (nenhum par load+BLR compatível com a vtable em todo .text)")
    return detail


def dis_partial(e, out, start, count):
    w = e.words_at(start, count)
    if w is None:
        out("  (endereço fora das cargas)")
        return
    out("\n--- desmontagem parcial %s +%d palavras (só o que sei decodificar com certeza) ---"
        % (hex(start), count))
    for pc, word in w:
        tags = []
        if (word & 0xFC000000) == 0x94000000:
            im = word & 0x3FFFFFF
            if im & (1 << 25):
                im -= (1 << 26)
            tags.append("BL %s" % hex(pc + im * 4))
        elif (word & 0xFC000000) == 0x97000000:
            im = word & 0x3FFFFFF
            if im & (1 << 25):
                im -= (1 << 26)
            tags.append("BL %s (faixa out)" % hex(pc + im * 4))
        elif (word & 0xFFFFFC1F) == 0xD63F0000:
            tags.append("BLR x%d" % ((word >> 5) & 0x1F))
        elif (word & 0xFFFFFC1F) == 0xD61F0000:
            tags.append("BR x%d" % ((word >> 5) & 0x1F))
        elif (word & 0xFF000010) == 0x71000000 or (word & 0xFF00001F) == 0xF100001F:
            tags.append("SUBS/CMPI")
        elif (word & 0x7FC00000) == 0x0B000000:
            tags.append("ADD")
        elif (word & 0xFFC003C0) == 0xF9400000:
            tags.append("LDR x%d,[x%d,#%d]" % (word & 0x1F, (word >> 5) & 0x1F, ((word >> 10) & 0xFFF) * 8))
        elif (word & 0xFFC003C0) == 0xF9000000:
            tags.append("STR x%d,[x%d,#%d]" % (word & 0x1F, (word >> 5) & 0x1F, ((word >> 10) & 0xFFF) * 8))
        elif (word & 0x9F000000) == 0x90000000:
            rd = word & 0x1F
            immlo = (word >> 29) & 3
            immhi = (word >> 5) & 0x7FFFF
            imm = (immhi << 2) | immlo
            if imm & (1 << 20):
                imm -= (1 << 21)
            tags.append("ADRP x%d,#%s" % (rd, hex((pc & ~0xFFF) + (imm << 12))))
        elif (word & 0xFFC00000) == 0x91000000:
            tags.append("ADD x%d,x%d,#%d%s" % (word & 0x1F, (word >> 5) & 0x1F,
                                                ((word >> 10) & 0xFFF) * (4096 if (word >> 22) & 1 else 1),
                                                " lsl12" if (word >> 22) & 1 else ""))
        elif (word & 0xFF000010) == 0x54000000:
            imm19 = (word >> 5) & 0x7FFFF
            if imm19 & (1 << 18):
                imm19 -= (1 << 19)
            tags.append("B.cond→%s" % hex(pc + imm19 * 4))
        elif (word & 0x7F800000) == 0x34000000 or (word & 0x7F000000) == 0x36000000:
            tags.append("TBZ/TBNZ")
        elif (word & 0xFFC00000) == 0x92800000:
            tags.append("MOVN")
        elif (word & 0x7FC00000) == 0x52800000:
            tags.append("MOVZ #%d" % ((word >> 5) & 0xFFFF))
        elif word == 0xD65F03C0:
            tags.append("RET")
        elif word == 0xD503201F or word == 0xD503203F:
            tags.append("NOP/PAC")
        out("  %s  %08x  %s" % (hex(pc), word, "  ".join(tags) if tags else ".word"))


def cross(e_list, out):
    out("\n=== GRAFO DE LIGAÇÕES ENTRE AS BIBLIOTECAS ===")
    exports = {}
    for e in e_list:
        for n, bind, ty, shndx, v, sz in e.syms():
            if bind == 1 and v:
                exports.setdefault(n, []).append((e.path, v, sz))
    for e in e_list:
        imports = [n for n, bind, ty, shndx, v, sz in e.syms() if shndx == 0 and n]
        links = [(n, exports[n]) for n in imports if n in exports]
        d = e.dyn()
        need = []
        da = e.sec.get(".dynstr")
        if da and 1 in d:
            for v in d[1]:
                q = da[1] + v
                need.append(e.F[q:e.F.index(b"\x00", q)].decode("latin1", "replace"))
        out("\n  %s" % e.path)
        out("    DT_NEEDED: %s" % (", ".join(need) or "—"))
        out("    importações: %d ; das libs daqui, resolve: %d" % (len(imports), len(links)))
        for n, who in links[:40]:
            out("      %-46s ← %s" % (n, ", ".join("%s@%s" % (p, hex(v)) for p, v, _s in who)))
        if len(links) > 40:
            out("      ... +%d" % (len(links) - 40))
        if any(n.startswith("Java_") for n in imports):
            out("    (importa símbolos Java_* !)")


def report(e, out, a):
    """Relatório padrão: símbolos, seções, `.dynamic`, strings, tabelas JNI."""
    syms = e.syms()
    exp = [x for x in syms if x[1] == 1 and x[4]]
    und = [x for x in syms if x[3] == 0 and x[0]]
    out("\n--- símbolos ---")
    out("  .dynsym: total=%d  exportados(global,definido)=%d  importados(SHN_UNDEF)=%d  "
        "Java_* exportados=%d" % (len(syms), len(exp), len(und),
                                 sum(1 for x in exp if x[0].startswith("Java_"))))
    jni_like = [n for n, b, t, sh, v, sz in und if any(k in n for k in
              ("dlopen", "dlsym", "android_dl", "RegisterNative", "JNIEnv", "_ZN3jni"))]
    if jni_like:
        out("    importações relevantes a carregamento/JNI: %s" % ", ".join(sorted(set(jni_like))))
    rx = re.compile(a.filter) if a.filter else re.compile(
        r"JNI_OnLoad|Java_|native|Bridge|bridge|Js|js|CDNI|cdn|WebView|webview|query|post|"
        r"subscribe|save", re.I)
    shown = 0
    for n, bind, ty, shndx, v, sz in exp:
        if rx.search(n):
            out("    * %-58s off=%-12s size=%-8d" % (n, hex(v), sz))
            shown += 1
    if not shown:
        out("    (nenhum exportado casa com o filtro; amostra: %s)"
            % ", ".join(n for n, b, t, sh, v, sz in exp[:12]))
    if und:
        out("    importados (%d): %s"
            % (len(und), ", ".join(n for n, b, t, sh, v, sz in und[:24])
               + (" …" if len(und) > 24 else "")))
    sec_table(e, out)
    dyn_table(e, out)
    string_scan(e, NEEDLES_DEFAULT, out, deep=a.deep)
    jni_tables(e, out)
    env_idiom(e, out)


def ent(buf):
    """Entropia de Shannon em bits/byte (0.0 = constante, ~8.0 = imprevisivel)."""
    if not buf:
        return 0.0
    import math
    from collections import Counter
    c = Counter(buf)
    n = float(len(buf))
    return -sum((v / n) * math.log2(v / n) for v in c.values())


def vtable_scan(F, lo, hi, base=0, cap=6000, pair=True, minslot=4, maxslot=233):
    """Instrumento para o idioma ART->JNI em AArch64: `LDR x?, [x0, #slot*8]` com
    `x0` = `JNIEnv*` (primeiro argumento de toda funcao nativa) e, em `pair`, um
    `BLR x?` dessa origem ate 6 instrucoes depois.

    Slots 0..3 sao os ponteiros reservados no topo de `JNINativeInterface`, e
    `LDR x?,[x0,#8]`/`#0x10`/`#0x18` e tambem o padrao de vtable C++ e de acesso a
    structs - por isso `minslot=4` e o pareamento com `BLR` sao obrigatorios para
    afirmar "esta biblioteca chama a ART". O imediato e escalado por 8 (`imm12<<3`).
    Janela: `JNINativeInterface` tem ~229 ponteiros, entao slot 4..233 = offsets
    0x20..0x748; `RegisterNatives` = 215 = 0x6b8 (uma janela curta omitiria justo ele).
    FALSO POSITIVO CONHECIDO: qualquer struct de ponteiros de funcao (ex.:
    `ANativeActivity->callbacks->onResume`) produz exatamente o mesmo par de instrucoes,
    entao "slot N" so e prova quando o slot e incomum (215, 33, 153, 136), nunca para
    4..15 nem 5.
    Retorna (hits [(va, slot)], histograma por slot, paginas, n de palavras).
    Limitacao: nao e desmontador - perde acessos via registrador != x0 e loads
    indexados (`LDR x?,[x?,x?]`)."""
    import struct
    hits, hist, page, n = [], collections.Counter(), {}, 0
    pending = {}
    for w, in struct.iter_unpack("<I", F[lo:hi]):
        va = base + lo + n * 4
        n += 1
        if (w & 0xFFC00000) == 0xF9400000 and ((w >> 5) & 31) == 0:
            off = ((w >> 10) & 0xFFF) << 3
            slot = off // 8
            if minslot <= slot <= maxslot:
                if pair:
                    pending[w & 0x1F] = (va, slot)
                else:
                    hits.append((va, slot))
                    hist[slot] += 1
                    page[va & ~0xFFFF] = page.get(va & ~0xFFFF, 0) + 1
        elif pair and (w & 0xFFFFFC1F) == 0xD63F0000:            # BLR Xn
            rb = (w >> 5) & 0x1F
            if rb in pending:
                pva, slot = pending.pop(rb)
                if va - pva <= 8 * 6:
                    hits.append((pva, slot))
                    hist[slot] += 1
                    page[pva & ~0xFFFF] = page.get(pva & ~0xFFFF, 0) + 1
                    if len(hits) >= cap:
                        break
        elif pair and (w & 0xFC000000) == 0x14000000:            # B imediato
            pending.clear()
    return hits, hist, page, n


def _exec_secs(e, only=None):
    """secoes com SHF_EXECINSTR (e tamanho plausivel); `only` forc uma pelo nome"""
    r = []
    for nm, (a, o, sz, t, f) in e.sec.items():
        if not nm or sz <= 0 or sz > 0x70000000 or o + sz > len(e.F):
            continue
        if only:
            if nm == only:
                r.append((nm, a, o, sz))
            continue
        if f & 0x4:
            r.append((nm, a, o, sz))
    return sorted(r, key=lambda x: x[2])


def cmd_vtable(e, args):
    exp = [(n, v, sz) for n, b, t, sh, v, sz in e.syms() if sh and v and sz]
    out = ["\n--- chamadas pela tabela de funcoes de `JNIEnv*` (idioma ART->JNI) ---"]
    out.append("  criterio: `LDR x?,[x0,#slot*8]` com 4 <= slot <= 233 (offsets 0x20..0x748, "
               "tabela inteira) seguido de `BLR` em ate 6 instrucoes (x0 = `JNIEnv*`)")
    secs = _exec_secs(e, getattr(args, "sec", "") or None)
    rg = getattr(args, "range", "") or ""
    if rg:
        a0, a1 = (int(x, 16) for x in rg.replace("-", " ").split())
        secs2 = []
        for nm, a, o, sz in secs:
            s0, s1 = max(a, a0), min(a + sz, a1)
            if s1 > s0:
                secs2.append((nm + " [" + hex(s0) + ".." + hex(s1) + ")", o + (s0 - a), s1 - s0, s0))
        secs = [(nm.split(" [")[0], va, off, sz) for nm, off, sz, va in secs2]
    out.append("  secoes executaveis: %s"
               % (", ".join("%s %s..%s" % (nm, hex(a), hex(a + sz)) for nm, a, o, sz in secs)
                  or "nenhuma"))
    tot, hist_all = 0, collections.Counter()
    for nm, a, o, sz in secs:
        base = a - o
        hits, hist, page, nwords = vtable_scan(e.F, o, o + sz, base)
        hist_all.update(hist)
        out.append("  %-40s palavras=%-10d chamadas=%-6d %s"
                   % (nm, nwords, len(hits),
                      ("densidade=%.5f%%" % (100.0 * len(hits) / nwords)) if nwords else ""))
        for va, slot in hits[:40]:
            real = va + base
            owner = [x[0] for x in exp if x[1] <= real < x[1] + x[2]]
            out.append("      VA %-14s slot=%-4d %-26s%s"
                       % (hex(real), slot, JNI_NAMES.get(slot, "(fora da tabela 4..171)"),
                          ("  [exportacao %s]" % owner[0]) if owner else ""))
        if len(hits) > 40:
            out.append("      ... (%d omitidos)" % (len(hits) - 40))
        for pg, c in sorted(page.items(), key=lambda x: -x[1])[:8]:
            out.append("      aglomeracao: pagina %#x com %d chamadas" % (pg + base, c))
        tot += len(hits)
    if hist_all:
        out.append("  chamadas por slot: %s"
                   % ", ".join("%s=%d" % (JNI_NAMES.get(k, "slot%d" % k), c)
                               for k, c in hist_all.most_common(16)))
    out.append("  TOTAL: %d chamadas pela tabela de `JNIEnv*` em %s" % (tot, e.path))
    out.append("  leitura: `RegisterNatives` (slot 215) e exigido ao menos uma vez por classe "
               "para expor metodos `native`; 0 chamadas em todo o codigo executavel = a "
               "biblioteca nem registra nativos nem chama a ART.")
    return "\n".join(out)


SIG_SLOTS = ["FindClass", "GetMethodID", "GetStaticMethodID", "NewStringUTF",
             "GetStringUTFChars", "CallObjectMethod", "CallVoidMethod", "RegisterNatives",
             "UnregisterNatives"]


def cmd_sig(e, args):
    """Assinatura bruta de chamada JNI: `LDR x?, [x?, #slot*8]` para slots que interessam.

    Sozinho isso NAO prova nada: em dezenas de megabytes de codigo, todo deslocamento de
    12 bits aparece por acaso (structs e vtables C++ usam os mesmos immediatos). Portanto
    a saida traz (a) a contagem do offset, (b) a distribuicao de todos os offsets da janela
    0x20..0x748 como ruido de fundo, e (c) quantos daqueles sao o par real do idioma ART
    (`LDR x?,[x0,#off]` + `BLR` em <=6 instrucoes), contado por `vtable_scan`."""
    import struct
    inv = {v: k for k, v in JNI_NAMES.items()}
    want = [x for x in (getattr(args, "sig", "") or "").split(",") if x in inv] or SIG_SLOTS
    offs = {w: inv[w] * 8 for w in want}
    hist_all = collections.Counter()
    for w, in struct.iter_unpack("<I", e.F):
        if (w & 0xFFC00000) == 0xF9400000:
            off = ((w >> 10) & 0xFFF) << 3
            if 0x20 <= off <= 0x748 and off % 8 == 0:
                hist_all[off] += 1
    base = sorted(hist_all.values())
    med = base[len(base) // 2] if base else 0
    p90 = base[int(len(base) * 0.9)] if base else 0
    mx = base[-1] if base else 0
    # par do idioma real, por secao executavel
    paired = collections.Counter()
    where = {}
    for nm, a, o, sz in _exec_secs(e):
        hits, hh, _pg, _n = vtable_scan(e.F, o, o + sz, a - o, cap=200000)
        for va, slot in hits:
            paired[slot] += 1
            where.setdefault(slot, []).append(hex(va + (a - o)))
    out = ["\n--- assinatura de chamada JNI, com ruido de fundo ---"]
    out.append("  janela: offsets 0x20..0x748 (slots 4..233) em %s (%d bytes)"
               % (e.path, len(e.F)))
    out.append("  ruido de fundo por offset: mediana=%d  p90=%d  maximo=%d (n=%d offsets vistos)"
               % (med, p90, mx, len(base)))
    out.append("  %-24s %-9s %-9s %-7s %s"
               % ("funcao", "offset", "bruto", "x/med", "pares do idioma (BLR em <=6)"))
    for w in want:
        o = offs[w]
        c = hist_all.get(o, 0)
        out.append("  %-24s %-9s %-9d %-7s %s"
                   % (w, hex(o), c, ("%.2f" % (c / med) if med else "-"),
                      "%d  %s" % (paired.get(o // 8, 0),
                                  (", ".join(where[o // 8][:4]) if paired.get(o // 8) else ""))))
    reg = paired.get(inv["RegisterNatives"], 0)
    out.append("  leitura: o numero que importa e o da ultima coluna - `RegisterNatives` "
               "chamado pelo registrador `x0` (o `JNIEnv*`). Aqui: %s"
               % ("0 em todo codigo executavel => a biblioteca nao registra nenhum metodo "
                  "`native` (e, pelo mesmo instrumento, nao chama a ART pela tabela)."
                  if reg == 0 else "%d chamada(s) - inspecionar." % reg))
    return "\n".join(out)


def e_jni_names():
    return JNI_NAMES


def cmd_ent(e, args):
    only = getattr(args, "sec", "") or None
    out = ["\n--- entropia por secao (0 = constante, ~8 = imprevisivel) ---"]
    for nm, (a, o, sz, t, f) in sorted(e.sec.items(), key=lambda kv: kv[1][1]):
        if not nm or sz <= 0 or o + sz > len(e.F):
            continue
        if only and nm != only:
            continue
        raw = e.F[o:o + sz]
        nw = len(raw) // 4
        nz = sum(1 for i in range(nw) if 0x10 < raw[i * 4] < 0x7f)
        kind = "codigo" if f & 0x4 else ("dados" if (f & 0xC0) == 0xC0 else "outros")
        out.append("  %-18s %-6s bytes=%-10d entropia=%.3f  nulos=%5.1f%%  imprimivel=%5.1f%%  "
                   "1o-byte-fora-de-texto=%5.1f%%"
                   % (nm, kind, sz, ent(raw), 100.0 * raw.count(0) / len(raw),
                      100.0 * sum(1 for c in raw if 32 <= c < 127) / len(raw),
                      100.0 * (1 - nz / max(1, nw))))
    return "\n".join(out)



def selftest():
    class A:
        sec = ""
        sig = ""
        range = ""

    a = A()
    # 1. entropia
    assert ent(b"\0" * 4096) == 0.0
    assert ent(bytes(range(256)) * 64) > 7.9
    # 2. idioma da tabela JNIEnv: `ldr x8, [x0, #0x108]` = slot 33 = GetMethodID
    import struct
    LDR_x0_m0x108 = 0xF9400000 | (33 << 10) | 8          # 0xF9408408
    words = [0xD10003FF,                      # sub sp, sp, #0
             0xF9400008,                      # ldr x8, [x0]  (imediato 0 → fora)
             LDR_x0_m0x108,                   # ldr x8, [x0, #0x108]  (slot 33)
             0xD63F0100,                      # blr x8
             0xAA0003E0]                      # mov x0, x0
    blob = b"".join(struct.pack("<I", w) for w in words)
    hits, hist, page, n = vtable_scan(blob, 0, len(blob))
    assert n == 5, n
    assert hits == [(8, 33)], hits
    assert page == {0: 1}, page
    # 3. imediato além da tabela (0x2000) e base != x0 não contam (o predizado é estreito)
    assert vtable_scan(struct.pack("<I", 0xF9400000 | (0x800 << 10) | 8), 0, 4)[0] == []
    assert vtable_scan(struct.pack("<I", LDR_x0_m0x108 | (1 << 5)), 0, 4)[0] == []
    # 4b. RegisterNatives = slot 215 = offset 0x6b8 deve estar na janela
    w215 = 0xF9400000 | (215 << 10) | 8
    hits215, _h, _p, _n = vtable_scan(struct.pack("<I", w215) + struct.pack("<I", 0xD63F0100), 0, 8)
    assert hits215 == [(0, 215)], hits215
    # 4c. cmd_sig roda numa lib real, se presente
    import os
    for cand in ("analysis/wzm310/libvmpc.so",):
        if os.path.exists(cand):
            r = cmd_sig(Elf(cand), A())
            assert "RegisterNatives" in r and "0 " in r, r[:200]
            print("(auto-teste em %s: RegisterNatives = %s)"
                  % (cand, [l for l in r.splitlines() if "RegisterNatives" in l][0].split()[-2]))
            break
    # 5. biblioteca real, se presente no workspace
    for cand in ("analysis/wzm310/libvmpc.so", "libvmpc.so"):
        import os
        if os.path.exists(cand):
            e = Elf(cand)
            r = cmd_vtable(e, a)
            assert "TOTAL" in r
            print("(auto-teste em %s: %s)" % (cand, [l for l in r.splitlines() if "TOTAL" in l][0]))
            break
    print("PASS: entropia, detector de vtable `JNIEnv*` (positivo e dois negativos) e "
          "leitura de ELF64 rodam juntos")


def main():
    ap = argparse.ArgumentParser(
        description="scanner JNI/ELF (AArch64) para as bibliotecas nativas do WZM 3.10.0.19854920")
    ap.add_argument("libs", nargs="*")
    ap.add_argument("--out", default="")
    ap.add_argument("--deep", action="store_true", help="varre todas as seções com busca de cifra")
    ap.add_argument("--filter", default="", help="regex para exportações (padrão: JNI/ponte)")
    ap.add_argument("--needles", default="", help="agulhas separadas por vírgula")
    ap.add_argument("--sec", default="", help="restringe --vtable/--ent a uma seção")
    ap.add_argument("--range", default="", help="recorta --vtable a um intervalo de VA: 0xLO-0xHI")
    ap.add_argument("--cross", action="store_true", help="gráfico de dependências entre as libs")
    ap.add_argument("--vtable", action="store_true", help="acessos à tabela de JNIEnv* por seção")
    ap.add_argument("--ent", action="store_true", help="entropia/densidade de bytes nulos por seção")
    ap.add_argument("--sig", nargs="?", const="", default=None, metavar="LISTA",
                    help="assinatura bruta LDR x?,[x?,#slot*8] no arquivo inteiro "
                         "(LISTA opcional: FindClass,GetMethodID,...)")
    ap.add_argument("--dis", nargs="+", metavar="ARG",
                    help="desmontagem parcial: --dis ENDEREÇO [N] (N=instruções, padrão 64)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    if not a.libs:
        ap.error("informe uma ou mais bibliotecas (ou --selftest)")
    fh = open(a.out, "w", encoding="utf-8") if a.out else sys.stdout

    def out(*x):
        line = " ".join(str(i) for i in x)
        fh.write(line + "\n")
        if a.out:
            sys.stdout.write(line + "\n")

    libs = []
    for pth in a.libs:
        try:
            libs.append(Elf(pth))
        except SystemExit as ex:
            out("!! " + str(ex))
    if not libs:
        sys.exit(2)
    if a.needles:
        global NEEDLES_DEFAULT
        NEEDLES_DEFAULT = [x for x in a.needles.split(",") if x]

    # comando de análise cruzada: não percorre as bibliotecas individualmente
    if a.cross:
        cross(libs, out)
        if a.out:
            fh.close()
            print("[ok] %s" % a.out)
        return

    for e in libs:
        out("\n" + "#" * 78)
        out("# %s  (%d bytes, e_machine=%d e_type=%d)"
            % (e.path, len(e.F), e.e_machine, e.e_type))
        out("#" * 78)
        if not (a.vtable or a.ent or a.dis or a.deep or a.sig is not None):
            report(e, out, a)
        if a.vtable:
            out(cmd_vtable(e, a))
        if a.ent:
            out(cmd_ent(e, a))
        if a.sig is not None:
            out(cmd_sig(e, a))
        if a.dis:
            addr = int(a.dis[0], 16)
            n = int(a.dis[1]) if len(a.dis) > 1 else 64
            out(dis_partial(e, out, addr, n))
    if a.out:
        fh.close()
        print("[ok] %s" % a.out)


if __name__ == "__main__":
    main()
