#!/usr/bin/env python3
"""Dual-path gate (AGENTS.md Critical Rule 2).

Every PatternAnalyzer.MatchingStrategy constant must have a `case` label in BOTH
RuntimeCompiler.java (runtime path) and ReggieMatcherBytecodeGenerator.java
(@RegexPattern compile-time path). Constants realized outside the generator's
strategy switch are listed in EXEMPT with the reason; adding an entry is a
reviewed change. Exit 0 = pass, 1 = gate failure, 2 = parse error.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENUM_SRC = ROOT / "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java"
PATHS = {
    "runtime": ROOT / "reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java",
    "processor": ROOT / "reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java",
}
# Budget: strategies intentionally without a case label on one path.
EXEMPT = {
    "processor": {
        "PIKEVM_CAPTURE": "realized by resolveRealization() before the strategy switch",
        "BITSTATE_CAPTURE": "folded into the PIKEVM_CAPTURE branch of resolveRealization()",
    },
}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def enum_constants():
    src = strip_comments(ENUM_SRC.read_text(encoding="utf-8"))
    m = re.search(r"enum\s+MatchingStrategy\s*\{", src)
    if not m:
        print("check-dual-path: MatchingStrategy enum not found", file=sys.stderr)
        sys.exit(2)
    body = src[m.end():]
    end = min(i for i in (body.find(";"), body.find("}")) if i >= 0)
    return [c.strip() for c in body[:end].split(",") if c.strip()]


def main():
    consts = enum_constants()
    if not consts:
        print("check-dual-path: no MatchingStrategy constants parsed", file=sys.stderr)
        return 2
    failures = []
    for name, path in PATHS.items():
        text = strip_comments(path.read_text(encoding="utf-8"))
        # Statement-form labels only (`case X:` / `case X, Y:`); arrow-form
        # switch expressions are auxiliary lookups, not strategy dispatch.
        cases = set()
        for labels in re.findall(r"\bcase\s+([A-Z][A-Z0-9_]*(?:\s*,\s*[A-Z][A-Z0-9_]*)*)\s*:", text):
            cases.update(x.strip() for x in labels.split(","))
        exempt = EXEMPT.get(name, {})
        for c in consts:
            if c not in cases and c not in exempt:
                failures.append("%s: no `case %s` in %s" % (name, c, path.relative_to(ROOT)))
        for c in exempt:
            if c in cases:
                failures.append("%s: %s is EXEMPT but now has a case label; drop the exemption" % (name, c))
    if failures:
        print("Dual-path gate FAILED (AGENTS.md Critical Rule 2):")
        for f in failures:
            print("  - " + f)
        return 1
    print("Dual-path gate OK: %d strategies covered on both paths" % len(consts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
