#!/usr/bin/env python3
"""Gate-budget ratchet (doc/agents-guardrails.md: never weaken a check to get a pass).

Pins the tolerances of the repo's correctness gates, so loosening one is a
visible, reviewed edit of BUDGETS below instead of a side effect of turning a
red build green:
  - fuzz divergence budget: -Dreggie.fuzz.maxFindings=N in the enforced
    AlgorithmicFuzzTest gate command of BOTH .github/workflows/ci.yml and
    .gitlab-ci.yml equals BUDGETS["fuzz_max_findings"], and every doc that
    cites "N pre-existing divergences" cites the same N;
  - jacocoVerify limits in build.gradle stay >= the BUDGETS floors per counter;
  - StrategyCorrectnessMetaTest stays enforced by default
    (reggie-runtime/build.gradle: reggie.metatest.enforce defaults to 'true');
  - CI keeps `./gradlew build` and jacocoVerify, ci.yml has no
    continue-on-error, and the GitLab build/correctness_gate jobs have no
    allow_failure.
Warns (exit 0) when AlgorithmicFuzzTest.KNOWN_FINDINGS_BUDGET -- the default a
local run without -Dreggie.fuzz.maxFindings uses -- differs from the CI budget.
Exit 0 = pass, 1 = gate failure, 2 = a pinned file or construct is missing.
`--self-test` mutates temporary copies and asserts each check rejects them.
"""
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Budgets: lowering is a ratchet (update this table in the same commit);
# raising one requires the reason in the PR description and a doc/fuzz/*.md
# inventory entry for any new divergence.
BUDGETS = {
    "fuzz_max_findings": 28,
    "jacoco_min": {"INSTRUCTION": 0.70, "BRANCH": 0.55},
}

FUZZ_TEST = ("reggie-integration-tests/src/test/java/com/datadoghq/reggie/"
             "integration/AlgorithmicFuzzTest.java")
GH_CI = ".github/workflows/ci.yml"
GL_CI = ".gitlab-ci.yml"
ROOT_BUILD = "build.gradle"
RUNTIME_BUILD = "reggie-runtime/build.gradle"
DOC_COUNT_FILES = ("README.md",)  # the fallback doc pins KNOWN_FINDINGS_BUDGET as the single
# source of truth and must NOT restate the number (anti-drift rule); README is the one doc that
# cites it, so the ratchet pins README and nothing else.
PINNED = (FUZZ_TEST, GH_CI, GL_CI, ROOT_BUILD, RUNTIME_BUILD) + DOC_COUNT_FILES

GATE_RE = re.compile(
    r"AlgorithmicFuzzTest\.divergenceGate_enforcedViaProperty'\s+"
    r"-Dreggie\.fuzz\.enforce=true\s+-Dreggie\.fuzz\.maxFindings=(\d+)")
DOC_RE = re.compile(r"(\d+) pre-existing divergences")
CONST_RE = re.compile(r"KNOWN_FINDINGS_BUDGET\s*=\s*(\d+)\s*;")
METATEST_RE = re.compile(
    r"systemProperty\s+'reggie\.metatest\.enforce',\s*"
    r"System\.getProperty\('reggie\.metatest\.enforce',\s*'true'\)")
LIMIT_RE = re.compile(r"limit\s*\{(.*?)\}", re.S)


class Missing(Exception):
    pass


def read(root, rel):
    path = root / rel
    if not path.is_file():
        raise Missing("pinned file missing: %s" % rel)
    return path.read_text(encoding="utf-8")


def jacoco_limits(build_text):
    start = build_text.find("tasks.register('jacocoVerify'")
    if start < 0:
        raise Missing("jacocoVerify task not found in %s" % ROOT_BUILD)
    rules = build_text.find("violationRules", start)
    if rules < 0:
        raise Missing("jacocoVerify violationRules not found in %s" % ROOT_BUILD)
    end = build_text.find("\n    }\n", rules)
    block = build_text[rules:end if end > 0 else len(build_text)]
    limits = {}
    for body in LIMIT_RE.findall(block):
        body = re.sub(r"//[^\n]*", "", body)
        counter = re.search(r"counter\s*=\s*'(\w+)'", body)
        minimum = re.search(r"minimum\s*=\s*([0-9.]+)", body)
        if minimum:
            name = counter.group(1) if counter else "INSTRUCTION"
            limits[name] = float(minimum.group(1))
    return limits


def check(root, budgets):
    errors, warnings = [], []
    want = budgets["fuzz_max_findings"]
    for rel in (GH_CI, GL_CI):
        found = GATE_RE.findall(read(root, rel))
        if not found:
            errors.append("%s: enforced AlgorithmicFuzzTest divergence gate step is missing" % rel)
        for value in found:
            if int(value) != want:
                errors.append("%s: -Dreggie.fuzz.maxFindings=%s != budget %d" % (rel, value, want))
    for rel in DOC_COUNT_FILES:
        for value in DOC_RE.findall(read(root, rel)):
            if int(value) != want:
                errors.append("%s: cites %s pre-existing divergences, budget is %d" % (rel, value, want))
    const = CONST_RE.search(read(root, FUZZ_TEST))
    if not const:
        raise Missing("KNOWN_FINDINGS_BUDGET constant not found in %s" % FUZZ_TEST)
    if int(const.group(1)) != want:
        warnings.append(
            "%s: KNOWN_FINDINGS_BUDGET=%s but CI enforces %d; a local run without "
            "-Dreggie.fuzz.maxFindings=%d uses the source default"
            % (FUZZ_TEST, const.group(1), want, want))
    limits = jacoco_limits(read(root, ROOT_BUILD))
    for counter, floor in sorted(budgets["jacoco_min"].items()):
        if counter not in limits:
            errors.append("%s: jacocoVerify has no %s limit (floor %.2f)" % (ROOT_BUILD, counter, floor))
        elif limits[counter] < floor:
            errors.append("%s: jacocoVerify %s minimum %.2f < floor %.2f"
                          % (ROOT_BUILD, counter, limits[counter], floor))
    if not METATEST_RE.search(read(root, RUNTIME_BUILD)):
        errors.append("%s: reggie.metatest.enforce no longer defaults to 'true'" % RUNTIME_BUILD)
    gh = read(root, GH_CI)
    for needle in ("./gradlew build", "jacocoVerify"):
        if needle not in gh:
            errors.append("%s: required check '%s' removed" % (GH_CI, needle))
    if re.search(r"continue-on-error:\s*true", gh):
        errors.append("%s: continue-on-error makes a required check non-blocking" % GH_CI)
    gl = read(root, GL_CI)
    if "./gradlew build" not in gl:
        errors.append("%s: required check './gradlew build' removed" % GL_CI)
    for job in ("build", "correctness_gate"):
        match = re.search(r"^%s:\n(.*?)(?=^\S|\Z)" % job, gl, re.S | re.M)
        if not match:
            errors.append("%s: required job '%s' removed" % (GL_CI, job))
        elif re.search(r"allow_failure:\s*true", match.group(1)):
            errors.append("%s: job '%s' has allow_failure: true" % (GL_CI, job))
    return errors, warnings


def run(root, budgets):
    try:
        errors, warnings = check(root, budgets)
    except Missing as exc:
        print("gate-budgets: %s" % exc, file=sys.stderr)
        return 2
    for line in warnings:
        print("WARN: %s" % line)
    for line in errors:
        print("FAIL: %s" % line)
    if errors:
        print("Gate-budget check FAILED: %d violation(s). Fix the code, not the budget." % len(errors))
        return 1
    print("Gate-budget check OK: fuzz budget %d, jacoco floors %s, metatest enforced"
          % (budgets["fuzz_max_findings"],
             ", ".join("%s>=%.2f" % kv for kv in sorted(budgets["jacoco_min"].items()))))
    return 0


def self_test():
    """Exercise allowed and prohibited cases on temporary copies."""
    want = BUDGETS["fuzz_max_findings"]
    lowered = dict(BUDGETS, fuzz_max_findings=want - 1)
    cases = [
        ("unchanged tree passes", None, BUDGETS, 0),
        ("budget lowered together with CI and docs passes",
         [(GH_CI, "maxFindings=%d" % want, "maxFindings=%d" % (want - 1)),
          (GL_CI, "maxFindings=%d" % want, "maxFindings=%d" % (want - 1))]
         + [(d, "%d pre-existing" % want, "%d pre-existing" % (want - 1)) for d in DOC_COUNT_FILES],
         lowered, 0),
        ("GitHub CI budget raised alone", [(GH_CI, "maxFindings=%d" % want, "maxFindings=%d" % (want + 1))], BUDGETS, 1),
        ("both CI budgets raised", [(GH_CI, "maxFindings=%d" % want, "maxFindings=%d" % (want + 9)),
                                    (GL_CI, "maxFindings=%d" % want, "maxFindings=%d" % (want + 9))], BUDGETS, 1),
        ("doc count drifts", [(DOC_COUNT_FILES[0], "%d pre-existing" % want, "%d pre-existing" % (want + 2))], BUDGETS, 1),
        ("branch coverage floor lowered", [(ROOT_BUILD, "minimum = 0.55", "minimum = 0.50")], BUDGETS, 1),
        ("metatest enforcement disabled", [(RUNTIME_BUILD, "'reggie.metatest.enforce', 'true'", "'reggie.metatest.enforce', 'false'")], BUDGETS, 1),
        ("jacocoVerify dropped from CI", [(GH_CI, "jacocoAggregateReport jacocoVerify", "jacocoAggregateReport")], BUDGETS, 1),
        ("fuzz gate step deleted", [(GL_CI, "divergenceGate_enforcedViaProperty", "divergenceGate")], BUDGETS, 1),
        ("correctness gate made non-blocking", [(GL_CI, "  timeout: 10 minutes\n", "  timeout: 10 minutes\n  allow_failure: true\n")], BUDGETS, 1),
    ]
    failures = 0
    for name, edits, budgets, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            for rel in PINNED:
                (tmp / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / rel, tmp / rel)
            applied = True
            for rel, old, new in edits or []:
                text = (tmp / rel).read_text(encoding="utf-8")
                if old not in text:
                    applied = False
                (tmp / rel).write_text(text.replace(old, new), encoding="utf-8")
            try:
                errors, _ = check(tmp, budgets)
                got = 1 if errors else 0
            except Missing:
                got = 2
        ok = applied and got == expected
        failures += 0 if ok else 1
        print("%s self-test: %s (expected %d, got %d%s)"
              % ("PASS" if ok else "FAIL", name, expected, got, "" if applied else ", mutation not applied"))
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(run(ROOT, BUDGETS))
