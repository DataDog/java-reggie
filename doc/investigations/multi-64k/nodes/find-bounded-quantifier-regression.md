---
id: find-bounded-quantifier-regression
type: finding
status: resolved
depends_on: [ev-backend-usage-survey]
supersedes: []
related: [q-prod-readiness, hyp-v2-driver-lowering, find-openj9-deadline-rejection, q-2pct-fallback-policy]
tags: [bounded-quantifier, semver, unrolling, nfa-size, compile-cost, match-performance, regression, routing-refuted, pikevm, bitstate, resolved-by-fix]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Bounded-quantifier {0,256} unrolling: the one real-corpus family where reggie LOSES to JDK (compile AND match)

## Reasoning chain
Trigger: user alarm "2 seconds to compile???" at the census' worst compile — a REAL
logs-backend semver pattern:
`^(?<major>0|[1-9]\d{0,256})\.(?<minor>...)...(?:\.(...)){0,256})...(?:\.[0-9a-zA-Z-]{0,40}){0,256})?$`

Measured (Corretto 26, a5ef0fb, /tmp/census/{SemverProbe,PikeProbe}.java):
| metric | JDK | reggie BitState (chosen route) | reggie PikeVM (forced) |
|---|---|---|---|
| compile | 1 ms | 2,257 ms | 5,722 ms construct (+208ms NFA build) |
| NFA states | n/a (lazy loops) | **567,550** (unrolled) | same 567,550 |
| match "1.2.3" (5ch) | 0.14 us/op | 10.4 us/op (~74x slower) | 30.2 us/op (~126x slower) |
| match 22-38 ch | 0.28-0.36 us/op | 40.8-34.6 us/op (~100-120x) | 127-229 us/op (~305-402x) |
Semantics agree (matches() identical on all inputs); a 200-char prerelease input exceeded
the probe budget on reggie routes.

Mechanism: `{0,256}` bounded quantifiers UNROLL into a 567k-state NFA at parse time. The
2.26s compile = analysis/determinization attempts over the monster (bounded by the 200M
work budget / 10s deadline — the same {0,256}-family shape as find-openj9-deadline-rejection,
which OpenJ9 cannot compile at all within its deadline). Match then simulates the giant state
space per input char: BitState ~ states/64 words/step, PikeVM ~ input x active threads —
BOTH lose badly. JDK keeps x{0,256} as compact backtracking loops: trivial compile,
0.14-0.4us matches.

**Routing is REFUTED as a fix**: PikeVM (the obvious alternative NFA-simulation route)
measured 2-4x WORSE than BitState and 125-402x worse than JDK, with construction eating
>half the deadline envelope. The root cause is REPRESENTATION (unrolled state space), not
strategy choice.

Consequences:
1. This family (bounded quantifiers with large bounds, esp. stacked/multiplied like semver)
   must NOT be migrated to reggie as-is: 1 of 563 real-service patterns (0.2%), a static
   literal, statically auditable — keep on java.util.regex explicitly until a fix exists.
2. The fix is a codegen feature, not routing: counter/loop-based representation of x{n,m}
   (compile bounded reps as counted loops like JDK's Curly/Loop nodes instead of unrolled
   states) — related in spirit to hyp-v2-driver-lowering's driver-lowering idea; large
   scope. Recorded as the natural follow-up if this family matters at fleet scale.
3. Honest readiness carve-out: all "reggie beats JDK everywhere" claims (ev-3engine-bench-
   current: 64/64, 62/62) must exclude this family — the bench corpus does not contain it;
   the census caught it precisely because the real corpus was tested.

## RESOLVED 2026-09-17 by commit 8e4750c (counted-loop lowering — hyp-counted-loop-lowering)
- Unroll budget 5k states: monsters lower to min copies + one body + counter marker; small
  quantifiers byte-identical unrolled (all existing strategies untouched).
- Counter-aware DFS (BackrefBacktrackMatcher extended + wired as route 2.5): exact JDK
  semantics incl. >max rejections; step budget converts adversarial shapes to a fast
  MatchBudgetExceededException (405ms) where JDK hangs.
- Real semver: 2,257ms -> 61ms compile; 25-400x-JDK -> 10-19x-JDK match; parity suite green
  (ev-counted-loop-fix-verification). The family is now migratable, not JDK-retained.
- Known residual: DFS allocation overhead (10-19x); optimization ceiling ~2-2.7x (BitState
  loop experiment) if needed later.

## Evidence
- /tmp/census/semver.txt (the pattern), SemverProbe.java + PikeProbe.java outputs (this node)
- ev-backend-usage-survey (census context: worst-of-563 compile 2.1s -> 2.26s on re-measure)
- ev-counted-loop-fix-verification (the fix, commit 8e4750c)
