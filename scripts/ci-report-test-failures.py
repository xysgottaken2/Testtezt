#!/usr/bin/env python3
"""Emite `::error::` annotations com o texto das falhas de teste (JUnit XML).

Motivo: artifacts e logs do GitHub Actions não são acessíveis a partir do ambiente de
desenvolvimento deste projeto (blob storage bloqueado/EOF). As *annotations* de check-run são
a única via de leitura, e o Gradle não imprime a mensagem da asserção no console por padrão —
então este passo transforma cada falha do XML em uma annotation legível via API:

    gh api repos/<owner>/<repo>/check-runs/<job_id>/annotations

Uso:
    python3 scripts/ci-report-test-failures.py --dir android/app/build/test-results/testDebugUnitTest
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_TEXT = 1200


def escape(value: str) -> str:
    """Escapa para o formato de annotation do GitHub (% primeiro, depois quebras)."""
    return (
        value.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )


def first_lines(text: str | None, count: int = 4) -> str:
    lines = [line.strip() for line in (text or "").splitlines() if line.strip()]
    return " | ".join(lines[:count])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default="android/app/build/test-results/testDebugUnitTest")
    parser.add_argument("--max", type=int, default=25, help="máximo de annotations emitidas")
    args = parser.parse_args()

    files = sorted(glob.glob(os.path.join(args.dir, "*.xml")))
    if not files:
        print(f"sem resultados de teste em {args.dir} (nada a reportar)")
        return 0

    total_tests = 0
    failing: list[str] = []
    emitted = 0

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except Exception as error:  # XML incompleto (build interrompida)
            print(f"::warning title=test-report::não foi possível ler {os.path.basename(path)}: {error}")
            continue

        for case in root.iter("testcase"):
            name = case.get("name") or "?"
            classname = case.get("classname") or os.path.basename(path)
            total_tests += 1
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                failing.append(f"{classname}.{name}")
                if emitted >= args.max:
                    continue
                message = (node.get("message") or "").strip()
                detail = first_lines(node.text)
                text = f"{classname}.{name} [{kind}]: {message or detail}"
                if detail and detail not in text:
                    text += f" || {detail}"
                print(f"::error title=test-failure-{emitted + 1}::{escape(text[:MAX_TEXT])}")
                emitted += 1

    print(f"testes executados: {total_tests} | falhas: {len(failing)} | annotations emitidas: {emitted}")
    for name in failing:
        print(f"  - FALHOU: {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
