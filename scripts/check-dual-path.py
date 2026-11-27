#!/usr/bin/env python3
"""Dual-path gate (AGENTS.md Critical Rule 2).

Every PatternAnalyzer.MatchingStrategy constant must have a `case` label inside
the strategy-dispatch switch of BOTH RuntimeCompiler.java (runtime path) and
ReggieMatcherBytecodeGenerator.java (@RegexPattern compile-time path). Each
dispatch switch is located by brace-matching outward from its unique
`default: throw new IllegalStateException("Unknown strategy: ...")` anchor, so
unrelated switches cannot masquerade as generator coverage. Constants realized
outside the generator's strategy switch are listed in EXEMPT with the reason;
adding an entry is a reviewed change. Exit 0 = pass, 1 = gate failure,
2 = parse error (dispatch switch, anchor, or braces unresolvable).
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
# Unique default-throw terminating each file's strategy-dispatch switch.
DISPATCH_DEFAULTS = {
    "runtime": 'throw new IllegalStateException("Unknown strategy: " + result.strategy);',
    "processor": 'throw new IllegalStateException("Unknown strategy: " + strategy);',
}
# Budget: strategies intentionally without a case label on one path.
EXEMPT = {
    "runtime": {
        "PIKEVM_CAPTURE": "realized by the PikeVM interpreter cache before generateBytecode dispatch",
        "BITSTATE_CAPTURE": "realized by the BitState interpreter cache before generateBytecode dispatch",
    },
    "processor": {
        "PIKEVM_CAPTURE": "realized by resolveRealization() before the strategy switch",
        "BITSTATE_CAPTURE": "folded into the PIKEVM_CAPTURE branch of resolveRealization()",
    },
}


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def _mask_strings(text):
    """Mask double-quoted string literal contents, length-preservingly, so
    brace indices computed on the masked text stay valid in the original."""
    return re.sub(
        r'"(?:[^"\\]|\\.)*"',
        lambda m: '"' + "x" * (len(m.group(0)) - 2) + '"',
        text,
    )


def dispatch_switch_body(text, name):
    """Return the strategy-dispatch switch body of `text` (comment-stripped),
    located by backward-then-forward brace matching from the file's unique
    'Unknown strategy' default throw. Raises ValueError on any unresolvable
    anchor or brace so the gate fails loudly instead of scanning nothing."""
    masked = _mask_strings(text)
    anchor = _mask_strings(DISPATCH_DEFAULTS[name])
    a = masked.find(anchor)
    if a < 0:
        raise ValueError("dispatch-switch anchor not found (%s)" % name)
    depth = 0
    start = -1
    for i in range(a - 1, -1, -1):
        if masked[i] == "}":
            depth += 1
        elif masked[i] == "{":
            if depth == 0:
                start = i
                break
            depth -= 1
    if start < 0:
        raise ValueError("dispatch-switch opening brace not found (%s)" % name)
    depth = 0
    for j in range(start, len(masked)):
        if masked[j] == "{":
            depth += 1
        elif masked[j] == "}":
            depth -= 1
            if depth == 0:
                return masked[start + 1 : j]
    raise ValueError("dispatch-switch closing brace not found (%s)" % name)


def enum_constants():
    src = strip_comments(ENUM_SRC.read_text(encoding="utf-8"))
    m = re.search(r"enum\s+MatchingStrategy\s*\{", src)
    if not m:
        print("check-dual-path: MatchingStrategy enum not found", file=sys.stderr)
        sys.exit(2)
    body = src[m.end():]
    end = min(i for i in (body.find(";"), body.find("}")) if i >= 0)
    return [c.strip() for c in body[:end].split(",") if c.strip()]


# Case-body parity (AGENTS.md Critical Rule 2): for every strategy, the set of
# `<gen>.generate*Method(` calls in its dispatch case body must be identical on
# both paths (review history: PR #55 OPTIMIZED_NFA_WITH_BACKREFS, PR #67 LAZY_DFA
# shipped with a different generated-method set on one path). Bodies that only
# delegate to a helper (no direct generate*Method call on either path) are not
# compared. Known asymmetries are budgeted per strategy and per method with the
# reason; adding an entry is a reviewed change, and a stale entry fails the gate.
METHOD_ASYMMETRY = {
    "LAZY_DFA": {
        "generateMatchesMethod": "runtime inlines matches(); the AOT class cannot reach package-private LazyDFACache members",
        "generateMatchesDelegatingMethod": "processor emits the delegating matches() for the same reason",
    },
    "OPTIMIZED_NFA_WITH_LOOKAROUND": {
        "generateFindLongestMatchEndMethod": "pre-existing at 43a22cf: runtime only; reconcile or document, then drop",
    },
    "SPECIALIZED_BACKREFERENCE": {
        "generateMatchesBoundedMethod": "pre-existing at 43a22cf: runtime only; reconcile or document, then drop",
    },
    "SPECIALIZED_LITERAL_LOOKAHEADS": {
        "generateFindLongestMatchEndMethod": "pre-existing at 43a22cf: runtime only; reconcile or document, then drop",
    },
}
_CASE_LABEL = re.compile(r"\bcase\s+([A-Z][A-Z0-9_]*(?:\s*,\s*[A-Z][A-Z0-9_]*)*)\s*:")
_GEN_CALL = re.compile(r"\.(generate\w*Method)\s*\(")


def case_method_sets(body):
    """Map each top-level case label of a dispatch switch body to the set of
    generate*Method calls in its case body; fall-through labels share the body."""
    depth_at = []
    depth = 0
    for ch in body:
        depth_at.append(depth)
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
    labels = [m for m in _CASE_LABEL.finditer(body) if depth_at[m.start()] == 0]
    ends = [m.start() for m in re.finditer(r"\bdefault\s*:", body) if depth_at[m.start()] == 0]
    out = {}
    pending = []
    for i, m in enumerate(labels):
        if i + 1 < len(labels):
            end = labels[i + 1].start()
        else:
            end = min([e for e in ends if e > m.end()] or [len(body)])
        seg = body[m.end():end]
        pending += [x.strip() for x in m.group(1).split(",")]
        if not seg.strip():
            continue
        calls = set(_GEN_CALL.findall(seg))
        for label in pending:
            out[label] = calls
        pending = []
    return out


def main():
    consts = enum_constants()
    if not consts:
        print("check-dual-path: no MatchingStrategy constants parsed", file=sys.stderr)
        return 2
    failures = []
    method_sets = {}
    for name, path in PATHS.items():
        text = strip_comments(path.read_text(encoding="utf-8"))
        try:
            body = dispatch_switch_body(text, name)
        except ValueError as e:
            print("check-dual-path: %s in %s" % (e, path.relative_to(ROOT)), file=sys.stderr)
            return 2
        method_sets[name] = case_method_sets(body)
        # Statement-form labels only (`case X:` / `case X, Y:`), collected from
        # the strategy-dispatch switch body only; arrow-form switch expressions
        # are auxiliary lookups, not strategy dispatch.
        cases = set()
        for labels in re.findall(r"\bcase\s+([A-Z][A-Z0-9_]*(?:\s*,\s*[A-Z][A-Z0-9_]*)*)\s*:", body):
            cases.update(x.strip() for x in labels.split(","))
        exempt = EXEMPT.get(name, {})
        for c in consts:
            if c not in cases and c not in exempt:
                failures.append("%s: no `case %s` in %s" % (name, c, path.relative_to(ROOT)))
        for c in exempt:
            if c in cases:
                failures.append("%s: %s is EXEMPT but now has a case label; drop the exemption" % (name, c))
    rt, pr = method_sets["runtime"], method_sets["processor"]
    for c in consts:
        if c not in rt or c not in pr:
            continue
        diff = rt[c] ^ pr[c]
        if not rt[c] or not pr[c]:
            diff = set()
        allowed = METHOD_ASYMMETRY.get(c, {})
        for meth in sorted(diff - set(allowed)):
            side = "runtime" if meth in rt[c] else "processor"
            failures.append("case %s: %s() generated on the %s path only" % (c, meth, side))
        for meth in sorted(set(allowed) - diff):
            failures.append("case %s: METHOD_ASYMMETRY entry %s is stale; drop it" % (c, meth))
    if failures:
        print("Dual-path gate FAILED (AGENTS.md Critical Rule 2):")
        for f in failures:
            print("  - " + f)
        return 1
    print("Dual-path gate OK: %d strategies covered on both paths" % len(consts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
