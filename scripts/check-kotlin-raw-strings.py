#!/usr/bin/env python3
"""Preflight de strings raw do Kotlin (evita uma classe de erro que já quebrou o CI).

O lexer do Kotlin NÃO aceita aspas coladas ao delimitador `\"\"\"` — por exemplo escrever
uma string raw que começa com aspas (`\"\"\"\"campo\":...`) ou termina com aspas antes do
fechamento. O compilador falha com mensagens confusas ("Identifier expected",
"Unclosed comment") e a causa real fica difícil de achar.

Este script reprova o build com a linha exata do problema. Uso:
    python3 scripts/check-kotlin-raw-strings.py [--dir android]
"""
from __future__ import annotations

import argparse
import pathlib
import sys

DELIMITER = '"""'


def check_file(path: pathlib.Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text(encoding="utf-8")
    index = 0
    while True:
        start = text.find(DELIMITER, index)
        if start < 0:
            break
        end = text.find(DELIMITER, start + 3)
        if end < 0:
            line = text[:start].count("\n") + 1
            problems.append(f"{path}:{line}: string raw aberta e não fechada")
            break
        line = text[:start].count("\n") + 1
        before = text[start - 1] if start > 0 else ""
        after_open = text[start + 3] if start + 3 < len(text) else ""
        before_close = text[end - 1]
        after_close = text[end + 3] if end + 3 < len(text) else ""
        for label, char in (
            ("antes do delimitador de abertura", before),
            ("depois do delimitador de abertura", after_open),
            ("antes do delimitador de fechamento", before_close),
            ("depois do delimitador de fechamento", after_close),
        ):
            if char == '"':
                problems.append(
                    f"{path}:{line}: aspas coladas ao delimitador ({label}) — "
                    "o lexer do Kotlin rejeita isso; use string normal com \\\" ou concatene"
                )
                break
        index = end + 3
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default="android", help="diretório raiz para procurar *.kt")
    args = parser.parse_args()

    root = pathlib.Path(args.dir)
    if not root.exists():
        print(f"ERRO: diretório não encontrado: {root}", file=sys.stderr)
        return 2

    files = sorted(root.rglob("*.kt"))
    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path))

    if problems:
        print(f"FALHA: {len(problems)} problema(s) de string raw em Kotlin:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"OK: {len(files)} arquivo(s) Kotlin sem aspas coladas ao delimitador de string raw")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
