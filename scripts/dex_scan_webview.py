#!/usr/bin/env python3
"""Scanner DEX para fechar C1 (ver docs/research/m6.6-webview-bridge-fence-cadeia.md §7).

Motivo de existir: o workspace NÃO contém o APK do WZM 3.10.0 (política do projeto),
e o sandbox não tem `dexdump`/`baksmali`/`apktool`. Este script é só stdlib e responde,
sem descompilador, as perguntas de C1 que dependem de nome de classe/método:

  * quais classes carregam WebView e quais têm métodos de ponte (@JavascriptInterface);
  * onde aparecem `addJavascriptInterface`, `passPreloginFence`, `CDNI_*`, `saveKVP`;
  * quais URLs/páginas aparecem no DEX (e com que scheme) — inclusive `bootstrap/index.html`
    e `prelogin/web/index.html`;
  * se `WBootstrap`/`loadWeb`/`MetaFetchTask`/`NetworkSecurityConfig` existem de fato.

Uso (na sua máquina, com o APK em mãos):
    python3 scripts/dex_scan_webview.py --apk WNM-3.10.0.apk            # relatório completo
    python3 scripts/dex_scan_webview.py --apk WNM-3.10.0.apk --urls     # só URLs/páginas
    python3 scripts/dex_scan_webview.py --dex classes.dex               # um .dex solto

Saída: texto puro, cole no chat. Nada é enviado a rede; o APK não é copiado para o repo.
Rótulos de evidência: DEVICE_OBSERVED/USER_SUPPLIED quando vierem deste relatório.
"""
import sys, zipfile, struct, argparse, collections

# palavras-chave que importam para a cadeia URL->WebView->bridge->nativo
KEYS = [
    "android/webkit/WebView", "addJavascriptInterface", "removeJavascriptInterface",
    "JavascriptInterface", "setJavaScriptEnabled", "shouldInterceptRequest",
    "WebViewClient", "setWebContentsDebuggingEnabled", "loadUrl", "postUrl",
    "passPreloginFence", "closeWebView", "on_mobile_webview_open", "on_mobile_webview_close",
    "CDNI_UseDevServer", "CDNI_UseProdServer", "CDNIRestart", "CDNISetKVP", "saveKVP",
    "WBootstrap", "loadWeb", "BootstrapPermissionsResult", "MetaFetchTask",
    "preenginetasks", "cdni.meta", "environment.config", "index.html", "bootstrap/",
    "prelogin", "banned", "shutdown", "file://", "http://", "https://localhost",
    "localhost", "127.0.0.1", "WebViewAssetLoader", "shouldOverrideUrlLoading",
    "usesCleartextTraffic", "networkSecurityConfig", "TrustManager", "X509",
]
# nomes de método que a ponte nativa do libgame.so reconhece (dispatcher 0x11dc520)
BRIDGE_HINT = ["passPreloginFence", "closeWebView", "saveKVP", "CDNI_UseDevServer",
               "CDNI_UseProdServer", "CDNIRestart", "proceed_anyway", "dont_change",
               "use_dev_cdn", "acceptTerms", "accountRegistration", "openURL",
               "getConnectivityStatus", "restartGame", "showLoader", "hideLoader"]


def uleb(buf, off):
    r = 0; s = 0
    while True:
        b = buf[off]; off += 1
        r |= (b & 0x7F) << s
        if not b & 0x80:
            return r, off
        s += 7


class Dex:
    def __init__(self, name, buf):
        self.name = name
        self.buf = buf
        if buf[:4] != b"dex\n":
            raise ValueError("não é DEX")
        # header DEX: as tabelas de ids começam em 0x38 (0x60 = class_defs_size)
        hdr = struct.unpack_from("<13I", buf, 0x38)
        g = lambda i: (hdr[2 * i], hdr[2 * i + 1])
        self.ssz, self.soff = g(0)          # string_ids_item
        self.tsz, self.toff = g(1)          # type_id_item
        self.psz, self.poff = g(2)          # proto_id_item
        self.fsz, self.foff = g(3)          # field_id_item
        self.msz, self.moff = g(4)          # method_id_item
        self.cds, self.cdoff = g(5)         # class_def_item[]
        self.strings = [self.string_at(struct.unpack_from("<I", buf, self.soff + 4 * i)[0])
                        for i in range(self.ssz)]

    def string_at(self, off):
        _n, o = uleb(self.buf, off)
        end = self.buf.index(b"\0", o)
        try:
            return self.buf[o:end].decode("utf-8", "replace")
        except Exception:
            return ""

    def type_desc(self, idx):
        if idx >= self.tsz:
            return "?"
        sidx = struct.unpack_from("<I", self.buf, self.toff + 4 * idx)[0]
        return self.strings[sidx]

    def _iter_classes(self):
        """percorre class_def_items mantendo a base de índices de método
        (d8/r8 emitem os métodos por classe: diretos e depois virtuais, em ordem;
        a validação por class_idx abaixo descarta nomes quando a ordem não bate)"""
        base = 0
        for i in range(self.cds):
            (cidx, af, sup, itf, src, anndir, cdata, sv) = struct.unpack_from("<8I", self.buf, self.cdoff + 32 * i)
            dm = vm = sf = _if = 0
            ann = {}
            if cdata:
                try:
                    off = cdata
                    sf, off = uleb(self.buf, off)
                    _if, off = uleb(self.buf, off)
                    dm, off = uleb(self.buf, off)
                    vm, off = uleb(self.buf, off)
                    for _ in range(sf + _if):          # fields: idx_diff + access_flags
                        _, off = uleb(self.buf, off)
                        _, off = uleb(self.buf, off)
                except Exception:
                    dm = vm = 0
            methods = []
            for kind, cnt, mbase in (("direct", dm, base), ("virtual", vm, base + dm)):
                cur = mbase
                if cdata:
                    try:
                        off = cdata
                        _sf, off = uleb(self.buf, off)
                        _if, off = uleb(self.buf, off)
                        _dm, off = uleb(self.buf, off)
                        _vm, off = uleb(self.buf, off)
                        for _ in range(_sf + _if):
                            _, off = uleb(self.buf, off)
                            _, off = uleb(self.buf, off)
                        for _ in range(cnt):
                            d, off = uleb(self.buf, off)
                            aflags, off = uleb(self.buf, off)
                            code, off = uleb(self.buf, off)
                            cur += d
                            methods.append((cur, kind, aflags, code))
                    except Exception:
                        pass
            if anndir:
                ann = self._annotations(anndir, base, dm, vm)
            yield i, cidx, af, methods, ann
            base += dm + vm

    def _annotations(self, anndir, base, dm, vm):
        """method_idx -> [descritores de anotação]; melhor-esforço (anotações de método
        no annotations_directory_item). Só retorna o que conseguir ler."""
        out = {}
        try:
            set_list = struct.unpack_from("<I", self.buf, anndir)[0]
            o = anndir + 4
            _csize, o = uleb(self.buf, o)
            _psize, o = uleb(self.buf, o)
            asize, o = uleb(self.buf, o)
            if not set_list or not asize:
                return out
            n = struct.unpack_from("<I", self.buf, set_list)[0]
            p = set_list + 4
            cur = base - 1
            for _ in range(n):
                v, p = uleb(self.buf, p)
                cur += (v >> 3)
                soff = struct.unpack_from("<I", self.buf, p)[0]
                p += 4
                if not soff:
                    continue
                cnt = struct.unpack_from("<I", self.buf, soff)[0]
                q = soff + 4
                types = []
                for _ in range(cnt):
                    _vis, q = uleb(self.buf, q)
                    tidx, q = uleb(self.buf, q)
                    types.append(self.type_desc(tidx))
                if types:
                    out[cur] = types
        except Exception:
            pass
        return out

    def method_name(self, mi):
        if not (0 <= mi < self.msz):
            return None
        cidx, _proto, nidx = struct.unpack_from("<HHI", self.buf, self.moff + 8 * mi)
        return self.strings[nidx], self.type_desc(cidx)

    def report(self):
        print(f"\n########## {self.name} ##########")
        print(f"  strings={self.ssz} tipos={self.tsz} métodos={self.msz} classes={self.cds}")
        print("\n  -- palavras-chave presentes (contagem de ocorrências nas strings) --")
        hits = collections.Counter()
        for s in self.strings:
            for k in KEYS:
                if k in s:
                    hits[k] += 1
        for k in KEYS:
            print(f"   {'SIM ' if hits[k] else 'NAO '}{k:34s} {hits[k]}")
        print("\n  -- candidatos a dono de WebView / bootstrap --")
        js = "Landroid/webkit/JavascriptInterface;"
        shown = 0
        rows = []
        try:
            rows = list(self._iter_classes())
        except Exception as e:
            print(f"   (listagem de classes incompleta: {e} — as tabelas de strings/URLs abaixo são fiáveis)")
        for _i, cidx, _af, methods, ann in rows:
            desc = self.type_desc(cidx)
            nm = desc[1:-1].replace("/", ".") if desc.startswith("L") else desc
            named = []
            for mi, kind, flags, code in methods:
                got = self.method_name(mi)
                if got and got[1] == desc:          # valida a suposição de ordem
                    named.append((got[0], mi, flags & 0x8 != 0))  # 0x8 = static
            names = sorted({n for n, _m, _s in named})
            hit = [h for h in BRIDGE_HINT if any(h.lower() == n.lower() for n in names)]
            exposed = sorted({n for n, m, _s in named if any(t == js for t in ann.get(m, []))})
            name_like = any(k in nm.lower() for k in
                            ("webview", "bootstrap", "prelogin", "meta", "cdn", "download",
                             "bridge", "wzm", "warzone", "activity", "js"))
            if not (hit or exposed or name_like):
                continue
            if shown > 40 and not (hit or exposed):
                continue
            shown += 1
            print(f"   {nm}")
            if exposed:
                print(f"      >>> @JavascriptInterface: {', '.join(exposed[:24])}"
                      + ("  ..." if len(exposed) > 24 else ""))
            if hit:
                print(f"      >>> nomes da ponte nativa: {hit}")
            if names:
                print(f"      métodos ({len(names)}): {', '.join(names[:26])}")
        if not shown:
            print("   (nenhum candidato — confira os filtros)")
        print("\n  -- strings que parecem URL/página (todas, sem filtro) --")
        urls = sorted({s for s in self.strings
                       if ("http://" in s or "https://" in s or s.endswith(".html")
                           or ".html?" in s or s.endswith(".js") or s.endswith(".json"))
                       and len(s) < 200})
        for u in urls[:200]:
            print(f"   {u}")
        print(f"   ({len(urls)} no total)")
        print("\n  -- rotas #/ usadas pela app web (se estiverem no DEX) --")
        print("   " + ", ".join(sorted({s for s in self.strings if s.startswith("#/")})[:60]) or "   (nenhuma)")
        print("\n  -- hosts de CDN vistos no DEX --")
        hs = sorted({s for s in self.strings if ".callofduty.com" in s or "activision.com" in s})
        for h in hs[:60]:
            print(f"   {h}")
        print(f"   ({len(hs)} no total)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--dex")
    ap.add_argument("--urls", action="store_true")
    a = ap.parse_args()
    bufs = []
    if a.apk:
        z = zipfile.ZipFile(a.apk)
        for n in z.namelist():
            if n.endswith(".dex"):
                bufs.append((n, z.read(n)))
            elif a.urls and (n.endswith(".html") or n.endswith(".js")):
                bufs.append((n, z.read(n)))
    elif a.dex:
        bufs.append((a.dex, open(a.dex, "rb").read()))
    else:
        print(__doc__)
        return 2
    for n, b in bufs:
        if n.endswith(".dex"):
            try:
                Dex(n, b).report()
            except Exception as e:  # nunca derrubar no meio do relatório
                print(f"  !! {n}: {e} — envie a mensagem, o resto continua")
        else:
            print(f"\n########## asset {n} ({len(b)} bytes) ##########")
            txt = b.decode("utf-8", "replace")
            for k in ("addJavascriptInterface", "cod__", "mcr", "passPreloginFence",
                      "saveKVP", "CDNI_", "#/", "fetch(", "XMLHttpRequest"):
                c = txt.count(k)
                if c:
                    print(f"   {'SIM ' if c else 'NAO '}{k:24s} {c}")
    print("\nFIM. Cole a saída completa. (análise: m6.6 §7)")


if __name__ == "__main__":
    main()
