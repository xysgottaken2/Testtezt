#!/usr/bin/env python3
"""Guarda local contra string simples sem aspa de fechamento (o erro que só o CI pegou no M3.6).

Regra (conservadora de propósito): em Kotlin, uma string comum **não** pode atravessar a linha
(salvo dentro de `${ ... }`, que é um caso aqui ignorado). Então, para cada linha que:

  * não é comentário (`//`, `/*`, `*/`, ou linha de KDoc começando com `*`),
  * não é string crua (`\"\"\"`),
  * não contém `{`, `}` nem `$` (ou seja: não pode estar dentro de um template/multilinha),

verificamos se um literal simples começou e não terminou na própria linha. Também acusamos
vírgula/parêntese faltando? Não — só o caso do literal, que é o objetivo.

Falsos negativos são aceitáveis (o CI é a fonte da verdade); falsos positivos também são
evitados porque as linhas com template/multilinha são simplesmente puladas.
"""
from __future__ import annotations

import pathlib
import sys


def scan_simple_literals(line: str) -> str | None:
    """Devolve o problema quando a linha deixa um literal simples aberto (sem `{}`/`$`)."""
    i = 0
    n = len(line)
    in_string = False
    in_char = False
    started_string = False
    started_char = False
    while i < n:
        c = line[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if line[i:i + 2] == "//":
            break  # comentário de fim de linha: texto livre
        if c == '"':
            in_string = True
            started_string = True
        elif c == "'":
            in_char = True
            started_char = True
        i += 1
    if in_string and started_string:
        return "linha termina dentro de uma string simples (falta a aspa de fechamento)"
    if in_char and started_char:
        return "linha termina dentro de um char literal (falta o apóstrofo)"
    return None


def main(argv: list[str]) -> int:
    roots = [pathlib.Path(arg) for arg in argv] or [pathlib.Path("android")]
    problems: list[str] = []
    checked = 0
    skipped = 0
    for root in roots:
        for path in sorted(root.rglob("*.kt")):
            for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = raw.strip()
                if stripped.startswith(("*", "/*", "*/", "//")) or '"""' in raw:
                    skipped += 1
                    continue
                if any(ch in raw for ch in "{}") or "$" in raw:
                    skipped += 1
                    continue
                checked += 1
                problem = scan_simple_literals(raw)
                if problem:
                    problems.append(f"{path}:{number}: {problem}\n    {stripped[:120]}")
    if problems:
        print("PROBLEMAS:")
        for problem in problems:
            print(" -", problem)
        return 1
    print(f"OK: {checked} linha(s) simples verificada(s), {skipped} pulada(s) (comentário/template/string crua)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
