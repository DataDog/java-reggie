#!/usr/bin/env python3
"""Literal-slot ratchet (AGENTS.md Critical Rule 4, doc/agents-conventions.md).

Bytecode emitters must bind every local, parameter and scratch slot to a named
slot variable allocated from the method's slot counter; a numeric literal slot
can silently alias a slot another path repurposes (PR #48: RECURSIVE_DESCENT
passed literal slot 5 as the recursion depth and saved prior bounds in literal
slots 7/8). This gate counts `visitVarInsn(<OPCODE>, <int literal >= 2>)` and
`visitIincInsn(<int literal >= 2>, ...)` per emitter file (slots 0/1 = `this`
and the first parameter are not counted) and compares the count with BUDGETS.
A count above budget fails; a count below budget also fails until the budget is
lowered in the same PR, so the budget only ratchets down. Raising a budget is a
reviewed change (doc/agents-guardrails.md, Prohibited actions).
Exit 0 = pass, 1 = gate failure.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOTS = ["reggie-codegen/src/main/java", "reggie-runtime/src/main/java", "reggie-processor/src/main/java"]
LITERAL_SLOT = re.compile(
    r"\bvisitVarInsn\s*\(\s*(?:Opcodes\.)?[A-Z]+\s*,\s*(\d+)\s*\)|\bvisitIincInsn\s*\(\s*(\d+)\s*,"
)
# Per-file budget of literal slots >= 2 at the time the gate was introduced.
BUDGETS = {
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/BackreferenceBytecodeGenerator.java": 19,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/BitParallelGlushkovBytecodeGenerator.java": 14,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/BitStateBytecodeGenerator.java": 4,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/BoundedQuantifierBytecodeGenerator.java": 62,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/ConcatGreedyGroupBytecodeGenerator.java": 1,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/ConcatQuantifiedGroupsBytecodeGenerator.java": 1,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/CountingGlushkovBytecodeGenerator.java": 14,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFASwitchBytecodeGenerator.java": 43,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFATableBytecodeGenerator.java": 9,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFAUnrolledBytecodeGenerator.java": 82,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/FixedRepetitionBackrefBytecodeGenerator.java": 6,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/FixedSequenceBytecodeGenerator.java": 13,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/GreedyCharClassBytecodeGenerator.java": 4,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/LazyDFABytecodeGenerator.java": 99,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/LinearPatternBytecodeGenerator.java": 9,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/LiteralAlternationTrieGenerator.java": 21,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/MultiGroupGreedyBytecodeGenerator.java": 35,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/NFABytecodeGenerator.java": 69,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/OnePassBytecodeGenerator.java": 54,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/OptionalGroupBackrefBytecodeGenerator.java": 1,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/PinnedBackreferenceBytecodeGenerator.java": 4,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/QuantifiedGroupBytecodeGenerator.java": 1,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/RecursiveDescentBytecodeGenerator.java": 379,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/SpecializedOptionalGroupBytecodeGenerator.java": 2,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/StatelessLoopBytecodeGenerator.java": 34,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/SuffixSequenceBytecodeGenerator.java": 9,
    "reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java": 12,
}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def count(path):
    n = 0
    for m in LITERAL_SLOT.finditer(strip_comments(path.read_text(encoding="utf-8"))):
        if int(m.group(1) or m.group(2)) >= 2:
            n += 1
    return n


def main():
    counts = {}
    for root in SOURCE_ROOTS:
        for path in sorted((ROOT / root).rglob("*.java")):
            n = count(path)
            if n:
                counts[path.relative_to(ROOT).as_posix()] = n
    failures = []
    for rel in sorted(set(counts) | set(BUDGETS)):
        n, budget = counts.get(rel, 0), BUDGETS.get(rel, 0)
        if n > budget:
            failures.append("%s: %d literal slots, budget %d; use a named slot from the method's slot counter" % (rel, n, budget))
        elif n < budget:
            failures.append("%s: %d literal slots, budget %d; lower the budget to %d (ratchet)" % (rel, n, budget, n))
    if failures:
        print("Literal-slot gate FAILED (AGENTS.md Critical Rule 4):")
        for f in failures:
            print("  - " + f)
        return 1
    print("Literal-slot gate OK: %d literal slots in %d files, all at budget" % (sum(counts.values()), len(counts)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
