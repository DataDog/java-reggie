---
id: ev-hybrid-context-rematch
type: evidence
status: confirmed
depends_on: [ev-jit-gate-landed]
supersedes: []
related: [q-generated-nfa-findfrom-broken]
tags: [hybrid, anchors, span, context, ff34f90, ci]
generator: cairn
created: 2026-09-18
updated: 2026-09-18
---

# CI divergence-gate failure root-caused + fixed (ff34f90): hybrid span re-match anchor context

## The CI failure (job 2055820106)
divergenceGate: 29 findings, CI budget 28 (source budget is 37 — .gitlab-ci.yml:47 pins
maxFindings=28, stale vs the source constant; kept, stricter is defensible and it caught this).
Method that cracked it: dump RAW findings (not shrunk repros) from the deterministic sweep on
both the branch tip and origin/main via a FuzzRunner driver, then replay each branch raw
(pattern,input) pair against MAIN's build through RegexFuzzOracle. Classification: 18 raw
SAME-DIVERGENCE (pre-existing families, shifted RNG lens), 11 branch-only regressions in 4
alternation families, ALL routing HYBRID_DFA. (Gotcha: the corpus TSV is (escaped-pattern,
tags) — first census read the tags column. And the RawDump classpath needs asm-util — a
missing TraceClassVisitor makes every pattern skip as "Reggie rejected".)

## Root cause
HybridMatcher.findMatchFrom re-matched the DFA span as a standalone string:
- $/\Z/\z inside an alternation branch fired at the span boundary (c+(b$|.*b): [10,12) vs
  JDK [10,14) — b$ fires at the substring end; (\z\z.|b.?), .[--cb]*(-[b1-a]{2}|[1a]\z) same).
- 1{0}b|[1-a]*(.{3}?|b)[^_]{2,4}?: preference-inverted pruned-DFA end trusted for the span.

## Fix (ff34f90, ~25 lines in HybridMatcher)
PikeVM/BitState halves: search the NFA half from the DFA's leftmost start over the FULL input
(anchors evaluate in-context; end preference re-derived; DFA start is a sound floor — leftmost,
anchor-diluted DFAs never enter the hybrid). Generated OPTIMIZED_NFA halves keep the span
re-match — their findFrom is broken for quantified-group shapes (see
q-generated-nfa-findfrom-broken), can return WRONG non-null ((.c)+ on "-cc": [1,3) vs JDK [0,2))
so a null-fallback design was unsound; type-gate required.

## Verification
Fuzz 29 -> 15 (10 pairs fixed, 0 regressions vs the pre-branch 29-set; 15 <= CI 28). Corpus:
RealFindParity 266,662/0 (refused=7 unchanged), RealInputParity 270,351/0, hybrid census 102
unchanged (no corpus hybrid lost — a shape-decline alternative would have cost the semver
hybrid ~5-8% matched). Box (same-day, controls flat): matched 180.2±5.7 / no-match 643.5±29.8 —
the search path costs nothing on corpus sweeps. Suite green; seeds 777/48879/131071 zero.
Also fixed 3 pre-existing find-path pairs beyond the 11 (29->15, not 29->18).

## A declined alternative (user chose root-cause over it)
Shape-decline (endclass-anchor-in-branch / lazy-in-branch -> skipHybrid): fixed the 11 but cost
the semver hybrid (1 of 102 corpus hybrids, [.\-_](alpha|...|m[\d]+$|pre\.[\d]+$) — correct:
rigid branches re-match consistently), matched 181.8->196.8µs on the box. Reverted in favor of
the context fix.
