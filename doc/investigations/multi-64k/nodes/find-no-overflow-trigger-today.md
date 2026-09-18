---
id: find-no-overflow-trigger-today
type: finding
status: confirmed
depends_on: [ev-validation-green]
supersedes: []
related: [find-repo-method-size-landscape, hyp-v2-driver-lowering]
tags: [bitstate, huge-charset, alternation, compile-deadline, oom]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# No current pattern family overflows a method — L2 is a net, not a currently-firing fix

## Reasoning chain
Probing with capture-alternations of distinct literals (n=2000/4000, len 3/5) through strict
`Reggie.compile`:
- n=2000 → **BitStateMatcher**, compiled in 8.5–13.5s, **no `$s` chunk methods** — bit-parallel
  code is compact; method size never approaches 64KB.
- n=4000 → **OutOfMemoryError during compilation** (analysis phase, before codegen).
- Nothing hits a method overflow within the 10s total-compile deadline.

Interpretation: the recent L1 work (huge-charset boolean[] tables, commit 2fc44cc; BitState)
already absorbed the known overflow families that `RuntimeCompiler`'s catch comment named.
L2 therefore stands as a safety net for unforeseen pattern shapes; no pattern in the current
test corpus is known to overflow through the wired-in pipeline. Observable trigger signal
when it does fire: `$s`-suffixed methods on the generated matcher class
(`matcher.getClass().getDeclaredMethods()`).

The exploratory SplitterProbeTest was **deleted** (8.5s compile near the 10s deadline = CI
flake risk; OOM shape unusable in CI).

## Evidence
- ev-validation-green — probe outputs

## Open questions
- If an overflow family re-emerges, is per-generator L1 (NFABytecodeGenerator) preferable to
  relying on L2? → hyp-v2-driver-lowering is dormant for the same reason.

## Post-benchmark nuance (ev-split-coverage-probe)
A constructible overflow DOES exist outside the corpus: "a"×40000 → 4.7 MB NFA
findLongestMatchEnd (baseline throws too-large). v1 L2 declines it (wide switches) → L3 —
status quo preserved, not rescued. Corpus-scoped conclusion unchanged.
