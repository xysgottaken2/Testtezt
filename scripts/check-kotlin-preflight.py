#!/usr/bin/env python3
"""Preflight de sintaxe Kotlin: problemas que o compilador reporta de forma enganosa.

Dois casos já quebraram o CI deste projeto com mensagens confusas em OUTRA linha do arquivo:

1. **Aspas coladas ao delimitador de string raw** — o lexer do Kotlin rejeita `\"\"\"\"campo\"`,
   e o erro aparece como "Identifier expected" em uma linha distante.
2. **`/*` dentro de comentário de bloco** — Kotlin ANINHA comentários de bloco; um literal como
   `/__wzm_offline/*` dentro de um KDoc abre um comentário aninhado e o `*/` seguinte fecha apenas
   o interno → o compilador reporta "Unclosed comment" no FIM do arquivo.

Uso:
    python3 scripts/check-kotlin-preflight.py [--dir android]
"""
from __future__ import annotations

import argparse
import pathlib
import sys

DELIMITER = '"""'


def check_raw_strings(path: pathlib.Path, text: str) -> list[str]:
    problems: list[str] = []
    index = 0
    while True:
        start = text.find(DELIMITER, index)
        if start < 0:
            break
        end = text.find(DELIMITER, start + 3)
        if end < 0:
            problems.append(f"{path}:{text[:start].count(chr(10)) + 1}: string raw aberta e não fechada")
            break
        line = text[:start].count("\n") + 1
        neighbours = {
            "antes da abertura": text[start - 1] if start > 0 else "",
            "depois da abertura": text[start + 3] if start + 3 < len(text) else "",
            "antes do fechamento": text[end - 1],
            "depois do fechamento": text[end + 3] if end + 3 < len(text) else "",
        }
        for label, char in neighbours.items():
            if char == '"':
                problems.append(
                    f"{path}:{line}: aspas coladas ao delimitador ({label}) — o lexer do Kotlin rejeita; "
                    'use string normal com \\" ou concatene'
                )
                break
        index = end + 3
    return problems


def check_block_comments(path: pathlib.Path, text: str) -> list[str]:
    problems: list[str] = []
    index, size, depth, line = 0, len(text), 0, 1
    while index < size:
        char = text[index]
        if char == "\n":
            line += 1
            index += 1
            continue
        if depth > 0:
            if text.startswith("/*", index):
                problems.append(f"{path}:{line}: '/*' dentro de comentário de bloco (Kotlin aninha comentários)")
                depth += 1
                index += 2
                continue
            if text.startswith("*/", index):
                depth -= 1
                index += 2
                continue
            index += 1
            continue
        if text.startswith("//", index):
            newline = text.find("\n", index)
            index = size if newline < 0 else newline
            continue
        if text.startswith(DELIMITER, index):
            end = text.find(DELIMITER, index + 3)
            if end < 0:
                break
            line += text[index : end + 3].count("\n")
            index = end + 3
            continue
        if char == '"':
            index += 1
            while index < size and text[index] != '"':
                if text[index] == "\\":
                    index += 1
                if text[index] == "\n":
                    line += 1
                index += 1
            index += 1
            continue
        if text.startswith("/*", index):
            depth = 1
            index += 2
            continue
        index += 1
    if depth != 0:
        problems.append(f"{path}:{line}: comentário de bloco não fechado (profundidade={depth})")
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
        text = path.read_text(encoding="utf-8")
        problems.extend(check_raw_strings(path, text))
        problems.extend(check_block_comments(path, text))

    if problems:
        print(f"FALHA: {len(problems)} problema(s) de sintaxe Kotlin:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"OK: {len(files)} arquivo(s) Kotlin sem aspas ambíguas nem comentário aninhado acidental")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
