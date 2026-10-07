#!/usr/bin/env python3
"""C1 — reconstrói a cadeia `página → WebView → addJavascriptInterface → método Java → ponte`.

`dex_scan_webview.py` responde "que strings existem". C1 exige provar a *chamada*: quem chama
`addJavascriptInterface` (com que objeto e que nome), quem chama `loadUrl` (com que URL), quais
métodos têm `@JavascriptInterface`, o que cada um faz, e se há sincronização (latch/semáforo) na
frente de `passPreloginFence`. Por isso este script consome **smali** (baksmali) e nunca liga
A→B por proximidade de strings: ele mantém um mapa de registradores por método (taint simples)
e só afirma o que uma instrução demonstra.

Rode na sua máquina (o APK não entra no repo):

  # modo A (recomendado)
  baksmali d -o /tmp/wzm/sm wzm_base.apk           # ou: apktool d -f -s -o /tmp/wzm wzm_base.apk
  python3 scripts/dex_bridge_scan.py --smali /tmp/wzm/sm --tsv /tmp/wzm/bridge.tsv

  # se não tiver baksmali: dexdump é parseado de forma parcial (modo B)
  cd /tmp/wzm && for f in *.dex; do dexdump -d $f; done > dexdump.txt
  python3 scripts/dex_bridge_scan.py --dump /tmp/wzm/dexdump.txt

Seções do relatório: C1-1 (quem carrega página), C1-2 (addJavascriptInterface), C1-3 (superfície
exposta), C1-4 (linhagem do objeto WebView), C1-5/6/7 (corpos + sincronização), C1-8 (grep livre),
e o `--tsv` com todas as instruções parseadas (cole o TSV se faltar algo: nada é jogado fora).

Rótulos usados: VERIFIED (instrução demonstra), PROBABLE (demonstrado até um wrapper/chamada
dinâmica), UNKNOWN (não determinado). Nada é inferido por nome de classe.
"""
import os, re, sys, argparse, collections

LOAD_METHODS = {"loadUrl", "loadData", "loadDataWithBaseURL", "postUrl", "loadFileUrl", "evaluateJavascript"}
BRIDGE_WORDS = {"addJavascriptInterface", "removeJavascriptInterface", "setWebViewClient",
                "setWebChromeClient", "getSettings", "setJavaScriptEnabled", "clearCache",
                "destroy", "reload", "postUrl"}
LOCK_METHODS = {"await", "countDown", "release", "acquire", "notify", "notifyAll", "wait",
                "park", "post", "postDelayed", "join", "sleep", "synchronized"}
WATCH = ("passPreloginFence", "closeWebView", "CDNI_UseDevServer", "CDNI_UseProdServer",
         "saveKVP", "isPreloginFencePassed", "onWebViewClosed", "mobile_webview_hide_loader")

R = {
    "class":   re.compile(r"^\.class\s+.*?(L[^;]+;)\s*$"),
    "super":   re.compile(r"^\.super\s+(L[^;]+;)"),
    "source":  re.compile(r'^\.source\s+"?([^"\n]+)"?'),
    "method":  re.compile(r"^\.method\s+(.*?)((?:[A-Za-z0-9_$]+)\(.*)$"),
    "endm":    re.compile(r"^\.end method"),
    "annot":   re.compile(r"^\.annotation\s+\S+\s+(L[^;]+;)"),
    "field":   re.compile(r"^\.field\s+(.*?)\s+([A-Za-z0-9_$]+):([ZBCSIJFDV]|L[^;]+;)\s*$"),
    "cstr":    re.compile(r"^const-string(?:/jumbo)?\s+v(\d+),\s+\"(.*)\"\s*$"),
    "new":     re.compile(r"^new-instance\s+v(\d+),\s+(L[^;]+;)"),
    "iget":    re.compile(r"^iget-object(?:/[a-z]+)?\s+v(\d+),\s+v(\d+),\s+(L[^;]+;)->([A-Za-z0-9_$]+):(?:.*?)(L[^;]+;)\s*$"),
    "iput":    re.compile(r"^iput-object(?:/[a-z]+)?\s+v(\d+),\s+v(\d+),\s+(L[^;]+;)->([A-Za-z0-9_$]+):"),
    "sget":    re.compile(r"^sget-object(?:/[a-z]+)?\s+v(\d+),\s+(L[^;]+;)->([A-Za-z0-9_$]+):(?:.*?)(L[^;]+;)\s*$"),
    "mro":     re.compile(r"^move-result-object\s+v(\d+)"),
    "mvo":     re.compile(r"^move-object(?:/from16|/16)?\s+v(\d+),\s+v(\d+)"),
    "ccls":    re.compile(r"^const-class\s+v(\d+),\s+(L[^;]+;)"),
    "ccast":   re.compile(r"^check-cast\s+v(\d+),\s+(L[^;]+;)"),
    "inv":     re.compile(r"^invoke-(virtual|super|direct|static|interface)(/range)?\s*\{([^}]*)\},\s*"
                          r"(L[^;]+;)->([A-Za-z0-9_$]+)\(([^)]*)\)(\S+)"),
    "invpoly": re.compile(r"^invoke-(polymorphic|custom)\s*\{([^}]*)\},\s*(L[^;]+;)->([A-Za-z0-9_$]+)"),
}


def d2c(desc):
    return desc[1:-1].replace("/", ".") if desc and desc.startswith("L") else (desc or "?")


class Method:
    __slots__ = ("name", "sig", "flags", "annots", "insns", "calls", "taint", "cls")

    def __init__(self, name, sig, flags):
        self.name, self.sig, self.flags = name, sig, set(flags)
        self.annots, self.insns, self.calls, self.taint = [], [], [], {}

    @property
    def native(self):
        return "native" in self.flags

    @property
    def exposed(self):
        return any(a.endswith("JavascriptInterface;") for a in self.annots)

    @property
    def sync(self):
        return sorted({"%s->%s" % (d2c(c), n) for c, n, _a, _t in self.calls if n in LOCK_METHODS})

    def strings(self):
        return [g[1] for k, g in self.insns if k == "cstr"]


class Klass:
    def __init__(self, desc):
        self.desc, self.super_, self.src = desc, None, None
        self.methods, self.fields = {}, {}          # fields: nome -> tipo
        self.order = []

    def add(self, m):
        key = (m.name, m.sig)
        if key not in self.methods:
            self.methods[key] = m
            self.order.append(key)
        return m


def parse_file(path, classes):
    cur = None
    mth = None
    pending_annot = None
    for raw in open(path, "r", encoding="utf-8", errors="replace"):
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        for tag in ("class", "super", "source"):
            mm = R[tag].match(s)
            if mm:
                if tag == "class":
                    desc = mm.group(1)
                    cur = classes.setdefault(desc, Klass(desc))
                elif cur is not None:
                    setattr(cur, "super_" if tag == "super" else "src", mm.group(1))
                break
        else:
            if cur is None:
                continue
            if s.startswith(".method"):
                body = s[len(".method"):].strip()
                mm = re.match(r"^(.*?)\s*([A-Za-z0-9_$]+)(\(.*\)\S*)$", " " + body)
                if mm:
                    flags = (mm.group(1) or "").split()
                    name, sig = mm.group(2), mm.group(3)
                    if "<init>" in body:
                        name = "<init>"
                    elif "<clinit>" in body:
                        name = "<clinit>"
                else:
                    flags, name, sig = [], body.split("(")[0].split()[-1], body
                mth = cur.add(Method(name, sig, flags))
                mth.cls = cur
                continue
            if R["endm"].match(s):
                mth = None
                pending_annot = None
                continue
            mm = R["annot"].match(s)
            if mm:
                if mth is not None:
                    mth.annots.append(mm.group(1))
                pending_annot = mm.group(1)
                continue
            if s == ".end annotation":
                pending_annot = None
                continue
            if cur is not None and mth is None:
                mm = R["field"].match(s)
                if mm:
                    cur.fields[mm.group(2)] = mm.group(3)
                continue
            if mth is None:
                continue
            for tag in ("cstr", "new", "iget", "iput", "sget", "mro", "mvo", "ccls", "ccast",
                         "inv", "invpoly"):
                mm = R[tag].match(s)
                if mm:
                    g = mm.groups()
                    mth.insns.append((tag, g))
                    if tag == "inv":
                        regs = [x.strip() for x in g[2].replace("..", ",").split(",") if x.strip()]
                        mth.calls.append((g[3], g[4], regs, g[0]))
                    elif tag == "invpoly":
                        mth.calls.append((g[2], g[3], [], "poly"))
                    break
            else:
                mth.insns.append(("other", (s,)))
    return cur


def taint_method(m):
    """vN -> ('str',v) | ('type',desc) | ('field',owner,name,tipo) | ('ret',owner,name) | ('copy',vN)"""
    t = {}
    last_inv = None
    for tag, g in m.insns:
        if tag == "cstr":
            t["v" + g[0]] = ("str", g[1])
        elif tag in ("new", "ccls", "ccast"):
            t["v" + g[0]] = ("type", g[1])
        elif tag == "iget":
            t["v" + g[0]] = ("field", g[2], g[3], g[4])
        elif tag == "sget":
            t["v" + g[0]] = ("field", g[1], g[2], g[3])
        elif tag == "mro":
            t["v" + g[0]] = ("ret", last_inv) if last_inv else ("unknown",)
        elif tag == "mvo":
            t["v" + g[0]] = t.get("v" + g[1], ("unknown",))
        elif tag in ("inv", "invpoly"):
            if tag == "inv":
                last_inv = (g[3], g[4])
                # arg0 do receiver moveia taint quando invoke->this.x
                pass   # (sem remapear registradores p/ parâmetros: evitar ligações falsas)
        elif tag == "other":
            if g[0].startswith(("const/4", "const/16", "const ", "aget", "move v", "move-wide")):
                mm = re.match(r"^\S+\s+v(\d+)", g[0])
                if mm:
                    t.pop("v" + mm.group(1), None)
    m.taint = t
    return t


def args_of(m, regs, kind):
    """para invoke-virtual/super/interface o 1º registrador é o receptor; static não tem."""
    if kind == "static" or not regs:
        return regs, None
    return regs[1:], regs[0]


def arg_desc(m, reg):
    tt = m.taint.get(reg)
    if not tt:
        return reg + "=?"
    if tt[0] == "str":
        return f'{reg}="{tt[1][:44]}"'
    if tt[0] == "type":
        return f"{reg}:{d2c(tt[1])}"
    if tt[0] == "field":
        return f"{reg}:{d2c(tt[1])}.{tt[2]}:{d2c(tt[3]) if tt[3] else '?'}"
    if tt[0] == "ret":
        o = tt[1]
        return f"{reg}=({d2c(o[0])}.{o[1]}())" if o else f"{reg}=ret?"
    return f"{reg}:{tt[0]}"


def field_origin(k, fname):
    """tipo do objeto atribuído a this.<fname> em qualquer método da classe (VERIFIED intra-classe)"""
    for key in k.order:
        m = k.methods[key]
        for tag, g in m.insns:
            if tag == "iput" and g[2] == k.desc and g[3] == fname:
                tt = m.taint.get("v" + g[0])
                if tt:
                    return (m.name, tt)
    return None


def collect(smali_dir):
    classes = {}
    files = 0
    for root, _d, fs in os.walk(smali_dir):
        for f in fs:
            if f.endswith(".smali"):
                files += 1
                try:
                    parse_file(os.path.join(root, f), classes)
                except Exception as e:
                    print(f"  !! {f}: {e}")
    for k in classes.values():
        for key in k.order:
            taint_method(k.methods[key])
    print(f"[modo A] {files} arquivos .smali, {len(classes)} classes")
    return classes


def all_methods(classes):
    for kd, k in sorted(classes.items()):
        for key in k.order:
            yield k, k.methods[key]


def show_calls(classes, names, title, extra=None):
    print(f"\n### {title}")
    n = 0
    for k, m in all_methods(classes):
        for (oc, on, regs, kind) in m.calls:
            if on not in names:
                continue
            n += 1
            who = f"{d2c(k.desc)}::{m.name}"
            a2, rcv = args_of(m, regs, kind)
            args = ", ".join(arg_desc(m, r) for r in ([rcv] if rcv else []) + a2[:3])
            line = f"  {who}: {d2c(oc)}->{on}({', '.join(args.split(', '))[:110]})"
            if kind == "range":
                line += "   [invoke/range]"
            print(line)
            if m.annots:
                print(f"      anotações: {[d2c(a) for a in m.annots]}")
            for a in (extra or ()):
                a(k, m, oc, on, regs, kind)
    if not n:
        print("   (nenhuma chamada — confirme se o smali é do APK certo / não foi filtrado)")
    return n


def report(classes, cmds, grep=None, tsv=None, depth=3):
    show_calls(classes, LOAD_METHODS, "C1-1  quem carrega página (loadUrl/loadData*/postUrl/evaluateJavascript)")
    def bridge_ctx(k, m, oc, on, regs, kind="virtual"):
        if on == "addJavascriptInterface":
            a2, rcv = args_of(m, regs, kind)
            print(f"      WebView que recebe = {arg_desc(m, rcv) if rcv else '?'}   "
                  f"objeto = {arg_desc(m, a2[0]) if len(a2) > 0 else '?'}   "
                  f"nome_js = {arg_desc(m, a2[1]) if len(a2) > 1 else '?'}")
    show_calls(classes, {"addJavascriptInterface", "removeJavascriptInterface"},
               "C1-2  addJavascriptInterface (objeto + nome da interface)", extra=[bridge_ctx])
    print("\n### C1-3  superfície exposta (métodos com @JavascriptInterface) e para onde vão")
    tot = 0
    for k, m in all_methods(classes):
        if not m.exposed:
            continue
        tot += 1
        hits = sorted({s for s in m.strings() if s in cmds}) or (
            [m.name] if m.name in cmds else [])
        nat = "  [native]" if m.native else ""
        print(f"  {d2c(k.desc)}::{m.name}{m.sig[m.sig.find('('):]}{nat}")
        print(f"      nomes de comando nativo casados: {hits or 'nenhum'}"
              + ("  (por nome do método, não por string — PROBABLE)" if hits and hits == [m.name] and m.name not in m.strings() else ""))
        for (oc, on, regs, kind) in m.calls[:14]:
            print(f"      chama {d2c(oc)}->{on}  " + ", ".join(arg_desc(m, r) for r in regs[:3]))
        if m.sync:
            print(f"      sincronização: {m.sync}")
    if not tot:
        print("   (nenhum @JavascriptInterface visível — baksmali sem anotações? verifique o smali)")
    print("\n### C1-3b  cobertura: cada comando nativo tem um método JS-exposto que o invoca?")
    if cmds:
        exp_strings = collections.defaultdict(list)
        exp_names = {}
        for k, m in all_methods(classes):
            if not m.exposed:
                continue
            exp_names.setdefault(m.name, []).append(f"{d2c(k.desc)}::{m.name}")
            for st in m.strings():
                exp_strings[st].append(f"{d2c(k.desc)}::{m.name}")
        both = 0
        for n in sorted(cmds):
            via_name = exp_names.get(n)
            via_str = exp_strings.get(n)
            if not (via_name or via_str):
                continue
            both += 1
            how = "string no corpo (VERIFIED)" if via_str else "só nome do método (PROBABLE)"
            print(f"  {n:34s} ← {', '.join((via_str or via_name)[:3])}   [{how}]")
        print(f"  → {both} de {len(cmds)} comandos do menu nativo são alcançáveis por método "
              f"@JavascriptInterface; os demais {len(cmds)-both} não têm chamada demonstrada "
              f"no que foi fornecido (UNKNOWN).")
    print("\n### C1-4  linhagem do objeto WebView (new-instance → campo → bridge/loadUrl)")
    INFO = collections.defaultdict(lambda: {"new": set(), "calls": set(), "meth": []})
    for k, m in all_methods(classes):
        inf = INFO[k.desc]
        for tag, g in m.insns:
            if tag == "new":
                inf["new"].add(d2c(g[1]))
        for (oc, on, regs, kind) in m.calls:
            if on in (LOAD_METHODS | BRIDGE_WORDS):
                inf["calls"].add(on)
                inf["meth"].append((m.name, on, oc))
    found = 0
    for kd, k in sorted(classes.items()):
        inf = INFO.get(kd, {"new": set(), "calls": set(), "meth": []})
        webview_type = "android.webkit.WebView"
        subclass = bool(k.super_ and "WebView" in k.super_)
        wv_fields = {fn: ft for fn, ft in k.fields.items() if ft and "WebView" in ft}
        own_new = any(webview_type in t for t in inf["new"])
        if not (subclass or wv_fields or own_new or inf["calls"]):
            continue
        found += 1
        print(f"  {d2c(kd)}  (super={d2c(k.super_) if k.super_ else '-'}, "
              f"new-instance de WebView aqui: {sorted(t for t in inf['new'] if 'ebView' in t) or '-'})")
        for fn, ft in sorted(wv_fields.items()):
            o = field_origin(k, fn)
            src = ""
            if o:
                src = f"   <- atribuído em {o[0]}() como {d2c(o[1][1]) if o[1][0] == 'type' else o[1]}"
                if o[1][0] == "ret":
                    src = f"   <- resultado de {d2c(o[1][1][0])}.{o[1][1][1]}() em {o[0]}()"
            print(f"      campo {fn}: {d2c(ft)}{src}")
        for nm, on, oc in inf["meth"][:12]:
            mk = None
            for key in k.order:
                if k.methods[key].name == nm:
                    mk = k.methods[key]
                    break
            tag = ("@JavascriptInterface " if mk and mk.exposed else "") + ("[native] " if mk and mk.native else "")
            print(f"      {tag}{nm}() → {d2c(oc)}->{on}")
    if not found:
        print("   (nenhuma classe com rastro de WebView)")
    print("\n### C1-5/6/7  corpo dos métodos vigiados + sincronização")
    any_ = False
    for k, m in all_methods(classes):
        if m.name not in WATCH:
            continue
        any_ = True
        print(f"  {d2c(k.desc)}::{m.name}{m.sig[m.sig.find('('):]}  flags={sorted(m.flags)}")
        print(f"     exposto à JS: {'@JavascriptInterface' if m.exposed else 'não anotado'}"
              f" | nativo: {m.native} | sincronização: {m.sync or 'nenhuma no corpo'}")
        for tag, g in m.insns[:30]:
            if tag == "inv":
                print(f"       invoke-{g[0]}{{{g[2]}}} → {d2c(g[3])}->{g[4]}({g[5]}){g[6]}")
            elif tag == "cstr":
                print(f'       const-string v{g[0]} = "{g[1][:70]}"')
            elif tag == "iget":
                print(f"       iget-object v{g[0]} <- {d2c(g[2])}.{g[3]} : {d2c(g[4])}")
            elif tag == "new":
                print(f"       new-instance v{g[0]} : {d2c(g[1])}")
            elif tag == "other":
                if g[0].split(" ")[0] in ("monitor-enter", "monitor-exit", "return-void", "return"):
                    print(f"       {g[0]}")
            else:
                print(f"       {tag}: {' '.join(str(x) for x in g)[:100]}")
        if m.native:
            print("       → método nativo: ligar ao `.so` por nome JNI/registro (ver m6.6 §5)")
    if not any_:
        print("   (nenhum dos nomes vigiados apareceu como método; confira a seletor de classes)")
    print("\n### primitivas de sincronização em toda a app (amostra)")
    c2 = collections.Counter()
    for k, m in all_methods(classes):
        for s in m.sync:
            c2[s] += 1
    for s, n in c2.most_common(18):
        print(f"  {s:44s} {n}")
    print("\n### métodos `native` declarados em classes que tocam WebView (candidatos a JNI da ponte)")
    for k, m in all_methods(classes):
        if m.native and any(on in (LOAD_METHODS | BRIDGE_WORDS) for _c, on, _r, _k2 in m.calls):
            print(f"  {d2c(k.desc)}::{m.name}{m.sig[m.sig.find('('):]}")
    for k, m in all_methods(classes):
        if m.native and m.exposed:
            print(f"  [exposta+native] {d2c(k.desc)}::{m.name}{m.sig[m.sig.find('('):]}")
    if grep:
        print(f"\n### C1-8  grep {grep!r}")
        for k, m in all_methods(classes):
            for tag, g in m.insns:
                txt = g[1] if tag == "cstr" else " ".join(str(x) for x in g)
                if grep in txt:
                    print(f"  {d2c(k.desc)}::{m.name}  [{tag}] {txt[:130]}")
                    break
    if tsv:
        with open(tsv, "w", encoding="utf-8") as f:
            f.write("class\tmethod\tflags\ti\tkind\tpayload\n")
            for k, m in all_methods(classes):
                for i, (tag, g) in enumerate(m.insns):
                    f.write(f"{d2c(k.desc)}\t{m.name}\t{','.join(sorted(m.flags))}\t{i}\t{tag}\t"
                            f"{'|'.join(str(x) for x in g)}\n")
        print(f"\n[tsv] escrito: {tsv}")


def parse_dexdump(txt, classes_dummy):
    """modo B: parcial — só pareia invoke/const-string por método, sem taint completo."""
    cur_cls = cur_m = None
    rows = []
    for ln in txt.splitlines():
        m = re.search(r"Class descriptor\s*:\s*'L([^']+);'", ln)
        if m:
            cur_cls = m.group(1)
            continue
        m = re.search(r"^\s+method\s+.*?'([^']*\(.*\).*)'", ln)
        if m:
            cur_m = m.group(1)
            continue
        m = re.search(r"\|\s*invoke-\S+\s+\{([^}]*)\},\s*L([^;]+);->(\w+)\(", ln)
        if m:
            rows.append(("invoke", cur_cls, cur_m, m.group(2), m.group(3), m.group(1)))
            continue
        m = re.search(r"\|\s*const-string\S*\s+v(\d+),\s+\"([^\"]*)\"", ln)
        if m:
            rows.append(("str", cur_cls, cur_m, m.group(1), m.group(2), ""))
    want = LOAD_METHODS | {"addJavascriptInterface", "removeJavascriptInterface"}
    n = 0
    for kind, cc, cm, a, b, extra in rows:
        if kind == "invoke" and b in want:
            n += 1
            print(f"  {cc}::{cm}  →  L{a};->{b}   args={extra}")
    print(f"\n[modo B] {len(rows)} linhas úteis, {n} sítios de interesse."
          f" Para taint/anotações use o modo A (baksmali).")


def selftest():
    """Valida o parser no fixture do repo (scripts/data/dex-fixture). Falha ruidosa se algo
    do taint/anotação quebrar — é o que garante que as conclusões sobre o DEX real são medidas."""
    here = os.path.dirname(os.path.abspath(__file__))
    classes = collect(os.path.join(here, "data", "dex-fixture"))
    ok = True
    def chk(cond, msg):
        global_ok = cond
        print(("  ok   " if cond else "  FALHA") + "  " + msg)
        return cond
    jb = classes.get("Lcom/activision/wzm/bootstrap/JsBridge;")
    wb = classes.get("Lcom/activision/wzm/bootstrap/WBootstrapView;")
    da = classes.get("Lcom/activision/wzm/ui/DownloadActivity;")
    ok &= chk(jb is not None and wb is not None and da is not None, "3 classes do fixture parseadas")
    if not ok:
        return 1
    m = jb.methods.get(("passPreloginFence", "passPreloginFence()V")) or         next((v for k, v in jb.methods.items() if k[0] == "passPreloginFence"), None)
    ok &= chk(m is not None and m.exposed, "método com @JavascriptInterface detectado")
    ok &= chk(m is not None and any(c[1] == "countDown" for c in m.calls), "CountDownLatch.countDown visto")
    ok &= chk(m is not None and any(c[1] == "dispatch" and c[0].endswith("WZMNative;") for c in m.calls),
              "calle WZMNative.dispatch identificado")
    inv = [c for _k, mm in [(k, v) for k, v in wb.methods.items()] for c in mm.calls
           if c[1] == "addJavascriptInterface"]
    ok &= chk(len(inv) == 1, "addJavascriptInterface encontrado 1x na classe da WebView")
    ctor = next(v for k, v in wb.methods.items() if k[0] == "<init>")
    a2, rcv = args_of(ctor, inv[0][2], inv[0][3])
    ok &= chk(ctor.taint.get(a2[0], ("", ""))[1] == "Lcom/activision/wzm/bootstrap/JsBridge;",
              "taint: 1º argumento = new-instance JsBridge")
    ok &= chk(ctor.taint.get(a2[1], ("", ""))[1] == "cod__", "taint: nome da interface = \"cod__\"")
    load = [c for _k, mm in [(k, v) for k, v in da.methods.items()] for c in mm.calls if c[1] == "loadUrl"]
    ok &= chk(len(load) == 1, "loadUrl da Activity encontrado")
    cr = next((v for k, v in da.methods.items() if k[0] == "onCreate"), None)
    ok &= chk(cr is not None and cr.taint.get("v2", ("",))[0] == "ret",
              "WebView da Activity rastreada até findViewById()")
    ok &= chk(field_origin(da, "mWeb") is not None, "field_origin resolve mWeb <- iput-object")
    print("selftest:", "OK" if ok else "FALHOU (não use o relatório sem corrigir)")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smali")
    ap.add_argument("--dump")
    ap.add_argument("--grep")
    ap.add_argument("--tsv")
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--native-commands", default=None)
    ap.add_argument("--selftest", action="store_true", help="valida o parser no fixture do repo")
    a = ap.parse_args()
    if a.selftest:
        sys.exit(selftest())
    cmds = set()
    cand = [a.native_commands] if a.native_commands else []
    cand += ["scripts/data/wzm310-bridge-commands.txt",
             os.path.join(os.path.dirname(__file__), "data", "wzm310-bridge-commands.txt")]
    for p in cand:
        if p and os.path.exists(p):
            for ln in open(p, encoding="utf-8"):
                ln = ln.strip()
                if ln and not ln.startswith("#"):
                    cmds.add(ln.split("\t")[0])
            print(f"[comandos nativos] {len(cmds)} nomes de {p}")
            break
    else:
        print("[aviso] sem lista de comandos nativos; use --native-commands scripts/data/"
              "wzm310-bridge-commands.txt (rode a partir do root do repo)")
    if a.smali:
        report(collect(a.smali), cmds, a.grep, a.tsv, a.depth)
    elif a.dump:
        parse_dexdump(open(a.dump, encoding="utf-8", errors="replace").read(), None)
    else:
        print(__doc__)
        return 2


if __name__ == "__main__":
    sys.exit(main() or 0)
