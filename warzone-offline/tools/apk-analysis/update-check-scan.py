#!/usr/bin/env python3
"""Scanner do portão "Verificando atualizações" do WZM (M4.0) — read-only.

Pergunta que este scanner ajuda a responder:

    O cliente pode ser levado a considerar a etapa de verificação de atualização
    concluída e avançar direto para o fluxo de assets/CDNI?
    (CAN_SKIP_UPDATE_CHECK ?)

Ele NÃO responde por adivinhação. Ele extrai do APK/XAPK (ou de uma árvore já
decodificada por apktool/jadx) **onde** aparecem os símbolos que o cliente usa
nesse estágio — com arquivo, localização e nível de confiança — e aplica uma
regra explícita de classificação (ver `REGRAS_DO_VEREDITO`).

Garantias (regra M4.0/M3.2):
  * não modifica, não copia e não distribui o APK; nenhuma requisição de rede;
  * não imprime payload proprietário: por padrão só o NOME do símbolo e a
    localização; contexto curto só com `--context` (para depuração local);
  * sem brute force de endpoints/arquivos — só busca por strings no binário.

Uso:
    python3 update-check-scan.py --apk /caminho/externo/base.apk --out /tmp/wzm/update-check.json
    python3 update-check-scan.py --dir /caminho/externo/apktool-out --out /tmp/wzm/update-check.json
    python3 update-check-scan.py --dir /pasta/com/xapk+e+splits --apk /caminho/extra.apk   # repetível
    python3 update-check-scan.py --self-test

Confiança das evidências (disciplina do projeto):
  VERIFIED  = encontrado em arquivo de texto/código legível (smali, java, xml, json, js, txt)
  PROBABLE  = encontrado como sequência de bytes em binário (dex/so/arsc/dat) — pode ser string morta
  UNKNOWN   = não encontrado (não significa que não exista: pode estar em split APK ausente)
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone

MAX_ENTRY_BYTES = 256 * 1024 * 1024
MAX_LOCATIONS_PER_SYMBOL = 8
MAX_CONTEXT_CHARS = 60

ARCHIVE_EXTENSIONS = {".apk", ".xapk", ".apks", ".zip", ".obb", ".aab"}
TEXT_EXTENSIONS = {
    ".smali", ".java", ".kt", ".xml", ".json", ".js", ".ts", ".txt", ".cfg",
    ".ini", ".properties", ".meta", ".html", ".css", ".csv", ".yaml", ".yml",
}
BINARY_EXTENSIONS = {".so", ".dex", ".arsc", ".dat", ".bin", ".pak", ".obb", ".jar"}

SKIP_DIRECTORIES = {".git", "node_modules", "__pycache__", ".gradle", "build"}


# --------------------------------------------------------------------------
# Catálogo de símbolos
# --------------------------------------------------------------------------
@dataclass(frozen=True)
class Symbol:
    id: str
    categoria: str
    hipotese: str          # o que este símbolo indicaria (HYPOTHESIS, nunca fato)
    padroes: tuple[bytes, ...]
    pergunta: str          # pergunta do pedido M4.0 que ele ajuda a responder


def _p(*values: str) -> tuple[bytes, ...]:
    return tuple(value.encode("utf-8") for value in values)


SYMBOLS: tuple[Symbol, ...] = (
    # ---- itens 1/2 do pedido: estado do parser de manifesto ----
    Symbol(
        "CDNI_100_PARSE_MANIFEST", "estado_cdni",
        "código/estado do parser de manifesto de conteúdo dentro do cliente",
        _p("CDNI_100_PARSE_MANIFEST"), "1,2",
    ),
    Symbol(
        "ManifestOk", "estado_cdni",
        "resultado de parse bem-sucedido do manifesto (o cliente segue para conteúdo)",
        _p("ManifestOk"), "1,2",
    ),
    Symbol(
        "MANIFEST_DOWNLOAD_ERROR", "estado_cdni",
        "estado de erro de DOWNLOAD do manifesto — indica que houve (tentativa de) fetch",
        _p("MANIFEST_DOWNLOAD_ERROR"), "1,3,6",
    ),
    Symbol(
        "CDNI_MANDATORY_NOT_INSTALLED", "estado_cdni",
        "o cliente distingue 'build atual' de 'conteúdo obrigatório instalado'",
        _p("CDNI_MANDATORY_NOT_INSTALLED"), "1,5,6",
    ),
    Symbol(
        "isCDNIFinished", "estado_cdni",
        "flag consultada para considerar a etapa de conteúdo concluída",
        _p("isCDNIFinished", "IsCDNIFinished", "is_cdni_finished"), "1,5",
    ),
    # ---- itens 1/6: campos do manifesto de conteúdo ----
    Symbol(
        "manifest_ver", "manifesto",
        "versão do manifesto (local ou remoto) — base do casamento de versão",
        _p("manifest_ver", "manifestVer", "MANIFEST_VER"), "1,4,6",
    ),
    Symbol(
        "cdni_indy_manifest_hash", "manifesto",
        "hash do manifesto independente (nome/caminho/validação do manifesto)",
        _p("cdni_indy_manifest_hash", "indy_manifest_hash", "INDY_MANIFEST_HASH"), "1,6",
    ),
    Symbol(
        "first_server_shard_idx", "manifesto",
        "índice do primeiro shard servido pelo CDN — estrutura de shards do manifesto",
        _p("first_server_shard_idx", "server_shard_idx"), "1,6",
    ),
    Symbol(
        "num_files_manifest", "manifesto",
        "número de arquivos do manifesto — validação de completude da lista",
        _p("num_files_manifest", "numFilesManifest"), "1,6",
    ),
    # ---- itens 3/6: quem carrega o valor comparado (cdni.meta) ----
    Symbol(
        "min_buildnum", "meta",
        "build mínimo aceito — comparado com o build local (VERIFIED no cdni.meta, 19854920)",
        _p("min_buildnum", "minBuildNum", "MIN_BUILDNUM"), "2,5,6",
    ),
    Symbol(
        "min_tu", "meta",
        "mínimo de title update declarado no cdni.meta (valor ao vivo: 0)",
        _p("min_tu"), "2,6",
    ),
    Symbol(
        "app_store_url", "meta",
        "URL de loja do cdni.meta — usada no caminho de atualização obrigatória",
        _p("app_store_url", "appStoreUrl", "store_url", "storeUrl"), "2,6",
    ),
    Symbol(
        "force_update", "portao",
        "sinal de atualização forçada (se existir, o caminho 'atualizado' é condicional)",
        _p("force_update", "forceUpdate", "FORCE_UPDATE"), "2,5",
    ),
    Symbol(
        "update_required", "portao",
        "estado de 'atualização necessária' no cliente",
        _p("update_required", "updateRequired", "UPDATE_REQUIRED"), "2,5",
    ),
    Symbol(
        "assets_installed", "portao",
        "estado local de assets instalados (se o portão for local, isto aparece)",
        _p("assets_installed", "content_installed", "contentInstalled", "installed_manifest"), "6",
    ),
    Symbol(
        "buildnum", "portao",
        "build local do cliente usado na comparação de versão",
        _p("buildnum", "build_num", "buildNumber"), "2,6",
    ),
    # ---- item 3/4: cadeia de bootstrap/CDN (para separar WebView do portão nativo) ----
    Symbol(
        "cdni.meta", "fetch",
        "arquivo de meta do shard CDN (VERIFIED ao vivo, 200) — 1º fetch candidato do portão",
        _p("cdni.meta"), "3,4,6",
    ),
    Symbol(
        "shard_cdn", "fetch",
        "raiz de conteúdo do CDNI (wzm/shard_cdn/<plataforma>/...)",
        _p("shard_cdn"), "3,4,6",
    ),
    Symbol(
        "_manifest/", "fetch",
        "diretório de manifesto do shard CDN usado pelo cliente",
        _p("_manifest/", "/_manifest"), "3,4,6",
    ),
    Symbol(
        "build-selector", "fetch",
        "cadeia WebView de pré-login (NÃO é o portão de build, mas passa o semver do cliente)",
        _p("build-selector-", "build-selector"), "4",
    ),
    Symbol(
        "semver", "fetch",
        "parâmetro/expressão de versão usado pela cadeia WebView (`?semver=`)",
        _p("semver"), "4",
    ),
    # ---- item 5: strings de tela (heurística de localização, não decide veredito) ----
    Symbol(
        "tela de atualização (en)", "ui",
        "string de tela — localiza o código que exibe a etapa (heurística)",
        _p("Checking for updates", "checking for updates", "Checking For Updates"), "1,5",
    ),
    Symbol(
        "tela de atualização (pt-BR)", "ui",
        "string de tela em português — idem, heurística",
        _p("Verificando atualiza", "verificando atualiza"), "1,5",
    ),
    Symbol(
        "tela de atualização (es)", "ui",
        "string de tela em espanhol — idem, heurística",
        _p("Comprobando actualizaciones", "comprobando actualizaciones"), "1,5",
    ),
)

CATEGORIAS = {
    "estado_cdni": "máquina de estados do CDNI (parse/instalação de manifesto)",
    "manifesto": "campo/estrutura do manifesto de conteúdo",
    "meta": "campo do cdni.meta (versão mínima/flags)",
    "portao": "sinal de checagem de build/atualização",
    "fetch": "indício de busca de manifesto/arquivo (path/URL/parâmetro)",
    "ui": "string de tela (heurística de localização)",
}


# --------------------------------------------------------------------------
# Regra de veredito (explícita — o relatório imprime exatamente isto)
# --------------------------------------------------------------------------
REGRAS_DO_VEREDITO = (
    "R1: se há indício de fetch (categoria 'fetch') OU campo de manifesto ('manifesto'/'estado_cdni'), "
    "o estágio consome BYTES de um manifesto -> CANNOT_SKIP_DIRECTLY "
    "(não existe booleano 'atualizado': o cliente precisa receber a resposta).",
    "R2: se só há sinal de build/versão ('meta'/'portao') sem indício de fetch/manifesto, "
    "ainda falta descobrir onde o valor comparado é obtido -> NEEDS_MORE_EVIDENCE.",
    "R3: se nada foi encontrado, o APK pode estar incompleto (split APKs/obbs fora do scan) "
    "ou os nomes mudaram nesta build -> NEEDS_MORE_EVIDENCE.",
    "R4: CAN_SKIP_UPDATE_CHECK NUNCA é emitido automaticamente: exigiria ler o trecho de código que "
    "conclui a etapa sem rede. O relatório lista o que procurar nesse caso.",
)


@dataclass
class Location:
    source: str
    kind: str
    confidence: str
    locator: str
    context: str | None = None

    def as_dict(self) -> dict:
        payload = {
            "source": self.source,
            "kind": self.kind,
            "confidence": self.confidence,
            "locator": self.locator,
        }
        if self.context:
            payload["context"] = self.context
        return payload


@dataclass
class Finding:
    symbol: Symbol
    hits: int = 0
    locations: list[Location] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "id": self.symbol.id,
            "categoria": self.symbol.categoria,
            "hipotese": self.symbol.hipotese,
            "pergunta": self.symbol.pergunta,
            "ocorrencias": self.hits,
            "locais": [location.as_dict() for location in self.locations],
        }


class Scan:
    def __init__(self) -> None:
        self.findings: dict[str, Finding] = {symbol.id: Finding(symbol) for symbol in SYMBOLS}
        self.entries_scanned = 0
        self.entries_skipped: list[str] = []
        self.errors: list[str] = []

    @property
    def found(self) -> list[Finding]:
        return [finding for finding in self.findings.values() if finding.hits > 0]

    def por_categoria(self, *categorias: str) -> list[Finding]:
        return [finding for finding in self.found if finding.symbol.categoria in categorias]


# --------------------------------------------------------------------------
# Busca
# --------------------------------------------------------------------------
def _classify(name: str) -> tuple[str, str]:
    """(kind, confidence) a partir da extensão do arquivo."""
    extension = os.path.splitext(name)[1].lower()
    if extension in TEXT_EXTENSIONS:
        return "texto", "VERIFIED"
    if extension in BINARY_EXTENSIONS:
        return "binario", "PROBABLE"
    return "outro", "PROBABLE"


def _sanitize(data: bytes) -> str:
    text = "".join(chr(byte) if 32 <= byte < 127 else "." for byte in data)
    return text[:MAX_CONTEXT_CHARS]


def _line_of(data: bytes, offset: int) -> int:
    return data.count(b"\n", 0, offset) + 1


def scan_bytes(name: str, data: bytes, scan: Scan, with_context: bool) -> None:
    kind, confidence = _classify(name)
    for finding in scan.findings.values():
        for pattern in finding.symbol.padroes:
            start = 0
            while True:
                offset = data.find(pattern, start)
                if offset < 0:
                    break
                finding.hits += 1
                if len(finding.locations) < MAX_LOCATIONS_PER_SYMBOL:
                    locator = f"linha {_line_of(data, offset)}" if kind == "texto" else f"offset {offset}"
                    context = None
                    if with_context:
                        context = _sanitize(data[max(0, offset - 20):offset + MAX_CONTEXT_CHARS])
                    finding.locations.append(
                        Location(name, kind, confidence, locator, context)
                    )
                start = offset + len(pattern)


def scan_file(path: str, scan: Scan, with_context: bool) -> None:
    try:
        size = os.path.getsize(path)
    except OSError as error:
        scan.errors.append(f"{path}: {error}")
        return
    if size > MAX_ENTRY_BYTES:
        scan.entries_skipped.append(f"{os.path.basename(path)} ({size} B > limite)")
        return
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError as error:
        scan.errors.append(f"{path}: {error}")
        return
    scan.entries_scanned += 1
    scan_bytes(os.path.basename(path), data, scan, with_context)


def scan_archive(path: str, scan: Scan, with_context: bool) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                if info.file_size > MAX_ENTRY_BYTES:
                    scan.entries_skipped.append(f"{os.path.basename(path)}!{info.filename} ({info.file_size} B)")
                    continue
                try:
                    data = archive.read(info)
                except (RuntimeError, zipfile.BadZipFile, OSError) as error:
                    scan.errors.append(f"{os.path.basename(path)}!{info.filename}: {error}")
                    continue
                scan.entries_scanned += 1
                scan_bytes(info.filename, data, scan, with_context)
    except (zipfile.BadZipFile, OSError) as error:
        scan.errors.append(f"{path}: {error}")


def scan_path(path: str, scan: Scan, with_context: bool) -> None:
    extension = os.path.splitext(path)[1].lower()
    if extension in ARCHIVE_EXTENSIONS:
        scan_archive(path, scan, with_context)
    else:
        scan_file(path, scan, with_context)


def scan_dir(root: str, scan: Scan, with_context: bool) -> None:
    for current, directories, files in os.walk(root):
        directories[:] = [name for name in directories if name not in SKIP_DIRECTORIES]
        for file_name in files:
            scan_path(os.path.join(current, file_name), scan, with_context)


# --------------------------------------------------------------------------
# Veredito
# --------------------------------------------------------------------------
def assess(scan: Scan) -> dict:
    fetch = scan.por_categoria("fetch")
    manifesto = scan.por_categoria("manifesto", "estado_cdni")
    versao = scan.por_categoria("meta", "portao")
    ui = scan.por_categoria("ui")

    if fetch or manifesto:
        veredito = "CANNOT_SKIP_DIRECTLY"
        regra = "R1"
        motivo = (
            "há indício de que o estágio consome bytes de um manifesto/arquivo "
            f"({', '.join(finding.symbol.id for finding in (fetch + manifesto)[:6])}); "
            "um booleano 'atualizado' não existe nesse desenho — a resposta precisa ser servida"
        )
        proximos = [
            "extrair do APK a URL/caminho exato do primeiro fetch (string ao redor de 'cdni.meta'/"
            "'shard_cdn'/'_manifest' ou do nome do manifesto) e a ordem de chamada (call sites no smali/jadx)",
            "identificar o campo de versão presente no manifesto (manifest_ver) e a comparação feita "
            "com o build local (buildnum >= min_buildnum)",
            "só então especificar o mock mínimo (endpoint + campos + próxima requisição esperada)",
        ]
    elif versao:
        veredito = "NEEDS_MORE_EVIDENCE"
        regra = "R2"
        motivo = (
            "há sinal de build/versão, mas nenhum indício de fetch/manifesto: falta descobrir de onde "
            "vem o valor comparado (cdni.meta? asset local? config no dex?)"
        )
        proximos = [
            "localizar no código onde o build local é lido e com o que ele é comparado",
            "procurar no APK as strings de caminho/URL que alimentam essa comparação",
        ]
    else:
        veredito = "NEEDS_MORE_EVIDENCE"
        regra = "R3"
        motivo = (
            "nenhum símbolo encontrado: passe o XAPK completo (base + split APKs + OBB) e/ou a árvore "
            "decodificada por apktool/jadx; confira se libgame.so e classes*.dex entraram no scan"
        )
        proximos = [
            "rodar novamente com --apk apontando para o XAPK e para cada split APK (--apk é repetível)",
            "rodar com --dir no diretório do jadx (smali/java) para pegar o lado Java/Kotlin",
        ]

    return {
        "veredito_provisorio": veredito,
        "regra_aplicada": regra,
        "motivo": motivo,
        "regras": list(REGRAS_DO_VEREDITO),
        "evidencia_de_fetch": [finding.symbol.id for finding in fetch],
        "evidencia_de_manifesto_ou_estado": [finding.symbol.id for finding in manifesto],
        "evidencia_de_build_ou_versao": [finding.symbol.id for finding in versao],
        "strings_de_tela_localizadas": [finding.symbol.id for finding in ui],
        "para_poder_emitir_CAN_SKIP_UPDATE_CHECK": [
            "um trecho de código que conclua a etapa SEM rede (estado local já satisfeito), e",
            "nenhuma chamada de fetch entre o início da tela e a conclusão da etapa",
        ],
        "proximos_passos": proximos,
    }


# --------------------------------------------------------------------------
# Relatório
# --------------------------------------------------------------------------
def render(scan: Scan, assessment: dict, source: str) -> str:
    lines = [
        "== WZM Offline — scanner do portão 'Verificando atualizações' (M4.0) ==",
        f"fonte: {source}",
        f"entradas lidas: {scan.entries_scanned}",
        "",
        "-- símbolos do pedido M4.0 --",
    ]
    for finding in scan.findings.values():
        status = "ENCONTRADO" if finding.hits else "ausente"
        lines.append(f"  [{status}] {finding.symbol.id} ({finding.symbol.categoria}) ocorrências={finding.hits}")
        for location in finding.locations[:3]:
            lines.append(f"      {location.source} · {location.locator} · {location.confidence}")
    lines += [
        "",
        "-- por categoria --",
    ]
    for key, description in CATEGORIAS.items():
        found = [finding.symbol.id for finding in scan.por_categoria(key)]
        lines.append(f"  {key}: {len(found)} — {description}")
        if found:
            lines.append(f"      {', '.join(found)}")
    lines += [
        "",
        "-- veredito provisório --",
        f"  {assessment['veredito_provisorio']} (regra {assessment['regra_aplicada']})",
        f"  motivo: {assessment['motivo']}",
        "",
        "-- regras do veredito --",
        *[f"  {rule}" for rule in assessment["regras"]],
        "",
        "-- próximos passos --",
        *[f"  - {item}" for item in assessment["proximos_passos"]],
    ]
    if scan.entries_skipped:
        lines += ["", "-- entradas puladas (tamanho) --", *[f"  {item}" for item in scan.entries_skipped[:10]]]
    if scan.errors:
        lines += ["", "-- erros --", *[f"  {error}" for error in scan.errors[:10]]]
    lines += [
        "",
        "observação: relatório read-only; nenhum APK é modificado, copiado ou distribuído. "
        "Contexto de bytes só aparece com --context e não deve ser commitado.",
    ]
    return "\n".join(lines) + "\n"


def payload(scan: Scan, assessment: dict, source: str, with_context: bool) -> dict:
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "entries_scanned": scan.entries_scanned,
        "contexto_incluido": with_context,
        "findings": [finding.as_dict() for finding in scan.findings.values() if finding.hits > 0],
        "ausentes": [finding.symbol.id for finding in scan.findings.values() if not finding.hits],
        "assessment": assessment,
        "disclaimer": (
            "Relatório read-only do APK do usuário. Nenhum APK/shard é copiado para o repositório. "
            "Nenhum bypass de certificado, trust, pinning, autenticação ou anti-cheat é aplicado ou sugerido."
        ),
    }


# --------------------------------------------------------------------------
# Self-test (fixtures sintéticas — nunca conteúdo proprietário)
# --------------------------------------------------------------------------
def self_test() -> int:
    temp = tempfile.mkdtemp(prefix="update-check-selftest-")
    try:
        os.makedirs(os.path.join(temp, "lib/arm64-v8a"), exist_ok=True)
        os.makedirs(os.path.join(temp, "assets/shard"), exist_ok=True)
        os.makedirs(os.path.join(temp, "smali/com/activision/cdni"), exist_ok=True)
        os.makedirs(os.path.join(temp, "res/values-pt"), exist_ok=True)

        with open(os.path.join(temp, "lib/arm64-v8a/libgame.so"), "wb") as handle:
            handle.write(
                b"\x7fELF\x00CDNI_100_PARSE_MANIFEST\x00ManifestOk\x00MANIFEST_DOWNLOAD_ERROR\x00"
                b"CDNI_MANDATORY_NOT_INSTALLED\x00isCDNIFinished\x00manifest_ver\x00"
                b"cdni_indy_manifest_hash\x00first_server_shard_idx\x00num_files_manifest\x00"
                b"min_buildnum\x00buildnum\x00"
            )
        with open(os.path.join(temp, "classes.dex"), "wb") as handle:
            handle.write(b"\x00dex\x00https://prod.cdni.callofduty.com/wzm/shard_cdn/android/_manifest/\x00")
        with open(os.path.join(temp, "smali/com/activision/cdni/Manifest.smali"), "w", encoding="utf-8") as handle:
            handle.write(
                'const-string v0, "cdni.meta"\n'
                'const-string v1, "manifest_ver"\n'
                'invoke-static {}, Lcdni/Manifest;->isCDNIFinished()Z\n'
            )
        with open(os.path.join(temp, "res/values-pt/strings.xml"), "w", encoding="utf-8") as handle:
            handle.write('<resources><string name="checking">Verificando atualizações…</string></resources>')

        # variante ZIP (mesmo conteúdo, caminho de APK)
        zip_path = os.path.join(temp, "fixture.apk")
        with zipfile.ZipFile(zip_path, "w") as archive:
            for current, _dirs, files in os.walk(temp):
                for name in files:
                    if name.endswith((".apk", ".zip")):
                        continue
                    full = os.path.join(current, name)
                    archive.write(full, os.path.relpath(full, temp))

        checks: dict[str, bool] = {}

        scan = Scan()
        scan_dir(temp, scan, with_context=False)
        assessment = assess(scan)
        found_ids = {finding.symbol.id for finding in scan.found}

        for symbol in (
            "CDNI_100_PARSE_MANIFEST", "ManifestOk", "MANIFEST_DOWNLOAD_ERROR",
            "CDNI_MANDATORY_NOT_INSTALLED", "isCDNIFinished", "manifest_ver",
            "cdni_indy_manifest_hash", "first_server_shard_idx", "num_files_manifest",
        ):
            checks[f"encontra {symbol}"] = symbol in found_ids

        checks["cdni.meta detectado"] = "cdni.meta" in found_ids
        checks["shard_cdn detectado"] = "shard_cdn" in found_ids
        checks["_manifest/ detectado"] = "_manifest/" in found_ids
        checks["string pt-BR detectada"] = "tela de atualização (pt-BR)" in found_ids

        binary_confidence = {
            location.confidence
            for location in scan.findings["CDNI_100_PARSE_MANIFEST"].locations
        }
        text_confidence = {
            location.confidence
            for location in scan.findings["tela de atualização (pt-BR)"].locations
        }
        checks["binário -> PROBABLE"] = binary_confidence == {"PROBABLE"}
        checks["texto -> VERIFIED"] = text_confidence == {"VERIFIED"}
        checks["veredito R1 (não dá para pular direto)"] = assessment["veredito_provisorio"] == "CANNOT_SKIP_DIRECTLY"
        checks["sem contexto por padrão"] = all(
            location.context is None for finding in scan.found for location in finding.locations
        )

        zip_scan = Scan()
        scan_archive(zip_path, zip_scan, with_context=False)
        zip_ids = {finding.symbol.id for finding in zip_scan.found}
        checks["zip: símbolos do .so"] = "CDNI_100_PARSE_MANIFEST" in zip_ids
        checks["zip: smali (texto)"] = "cdni.meta" in zip_ids
        checks["zip: veredito R1"] = assess(zip_scan)["veredito_provisorio"] == "CANNOT_SKIP_DIRECTLY"

        empty_dir = tempfile.mkdtemp(prefix="update-check-empty-")
        try:
            empty_scan = Scan()
            scan_dir(empty_dir, empty_scan, with_context=False)
            checks["dir vazio -> R3"] = assess(empty_scan)["veredito_provisorio"] == "NEEDS_MORE_EVIDENCE"
            checks["dir vazio -> R3 correto"] = assess(empty_scan)["regra_aplicada"] == "R3"
        finally:
            shutil.rmtree(empty_dir, ignore_errors=True)

        with_context_scan = Scan()
        scan_dir(temp, with_context_scan, with_context=True)
        checks["--context preenche contexto"] = any(
            location.context for finding in with_context_scan.found for location in finding.locations
        )

        failed = [name for name, ok in checks.items() if not ok]
        for name, ok in checks.items():
            print(f"  [{'ok' if ok else 'FALHOU'}] {name}")
        if failed:
            print("\n--- relatório do self-test (diagnóstico) ---")
            print(render(scan, assessment, temp))
            print(f"self-test FALHOU: {len(failed)} verificação(ões)")
            return 1
        print(f"self-test OK: {len(checks)} verificações (fixture sintética, não é o APK real)")
        return 0
    finally:
        shutil.rmtree(temp, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Scanner read-only do portão 'Verificando atualizações' do WZM (M4.0)"
    )
    parser.add_argument("--apk", action="append", default=[], help="APK/XAPK/ZIP externo (repetível)")
    parser.add_argument("--dir", help="diretório (decodificado ou com APKs/XAPK/OBB) externo")
    parser.add_argument("--out", help="relatório JSON (fora do repositório)")
    parser.add_argument("--context", action="store_true", help="inclui contexto curto de bytes (local apenas)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    if not args.apk and not args.dir:
        parser.print_help()
        return 2

    scan = Scan()
    sources = list(args.apk)
    for path in sources:
        if not os.path.exists(path):
            print(f"ERRO: caminho inexistente: {path}", file=sys.stderr)
            return 2
        if os.path.isdir(path):
            print(f"ERRO: --apk espera arquivo; use --dir para diretórios ({path})", file=sys.stderr)
            return 2
        scan_path(path, scan, args.context)
    if args.dir:
        if not os.path.isdir(args.dir):
            print(f"ERRO: --dir inexistente: {args.dir}", file=sys.stderr)
            return 2
        scan_dir(args.dir, scan, args.context)

    source = ", ".join(
        [f"apk:{os.path.basename(path)}" for path in sources]
        + ([f"dir:{os.path.basename(args.dir.rstrip('/'))}"] if args.dir else [])
    )
    assessment = assess(scan)
    print(render(scan, assessment, source))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload(scan, assessment, source, args.context), handle, indent=2, ensure_ascii=False)
        print(f"\nrelatório JSON: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
