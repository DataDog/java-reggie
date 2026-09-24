---
id: ev-re2j-parity-push
type: evidence
status: confirmed
depends_on: [ev-semver-vs-re2j, ev-counted-loop-fix-verification, hyp-counted-loop-lowering]
supersedes: []
related: [q-prod-readiness, find-logs-backend-re2j-migration]
tags: [re2j, parity, perf, dfs, memo, join-point, liveness, compression, research, commit-677417b]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Closing the reggie/re2j gap on the counted-loop family (commit 677417b)

USER DIRECTIVE: "think hard and look at available research to make this pattern family not
worse than re2j."

## Research findings (what the fast engines actually do)

- **re2j 1.8** (read from sources in the gradle cache, com.google.re2j:re2j:1.8): compiles the
  real logs-backend semver pattern to a **419,186-instruction Prog** (~7MB) — it FULLY EXPANDS
  bounded repetition ({0,256} nested) exactly like our pre-fix unroller, and eats the memory so
  its Go-style NFA simulation (Machine/Queue: sparse-set pc marking, dense int arrays, pooled
  thread cap vectors, zero hashing per step) can dedupe threads on **pc alone**. That is the
  whole trick: trade program size for trivial dedup.
- **Hyperscan** (Intel, per its dev reference + repeat_internal.h): does NOT expand large bounded
  repeats — dedicated bounded-repeat machinery with counters/metadata. Same tradeoff direction
  as our counted-loop lowering (compact program, counters carried at runtime).
- **Cox, "Regular Expression Matching Can Be Simple And Fast" (2007)**: the NFA-simulation model
  re2j implements; sparse sets from research.swtch.com.
- Implication for us: with a compact counter-carrying program we cannot dedupe on pc alone —
  but we do not have to: the DFS raw walk was ALREADY faster than re2j (10.3us vs 21.1us on the
  313-char accept with the memo disabled — measured via shadow-class experiment). The memo
  checkpoint was 42-64% of total match time, hashing a 15-int row every pop.

## What was done (commit 677417b, semantics unchanged — all parity suites + corpus green)

1. **Join-point-only memoization**: memo consulted only at states with >=2 in-edges (+ start).
   Every reachable cycle enters through such a state (finiteness); re-exploring a config via a
   unique predecessor is provably identical work, so first-arrival priority is preserved.
   Instrumented counts on the 313-char accept: 1,558 pops, 619 joins, **0 chain hits** — accept
   paths never revisit configs; the memo is pure insert cost there.
2. **Live-key masking** (compiler-style forward-reachability liveness): row carries only
   spans/counters that can vary at that state (unentered loop => constant 0; unwritten group
   span => constant -1). Semver rows: 15 ints -> 0-6.
3. **NFA flattening + pass-through compression**: primitive arrays everywhere; pure single-eps
   glue states retargeted away (never popped).
4. **Two-level MemoSet**: level 1 = packed long (state<<32|pos), fmix64 + probe; level 2 =
   distinct configs in a preallocated int arena chained per slot, compared by direct int
   equality — no row hashing at all. Per-thread workspace, O(1) generation reset.

## Result (same-process 100k ops, real semver pattern, vs re2j 1.8)

| input | re2j | reggie (677417b) | ratio | before 750f848-fix series |
|---|---|---|---|---|
| 22-char accept | 0.89us | 1.07us | 0.97-1.20x | 2.0x |
| 38-char accept | 1.42us | 1.39us | **0.98x (faster)** | 1.7x |
| 313-char accept | 21.40us | 21.55us | **1.00-1.01x (parity)** | 2.1x |
| 613-char reject | 36.2us | 49.8us | 1.38x | 2.8x |
| 5-char '1.2.3' | 0.23us | 0.65us | 2.5-2.8x | 6.0x |
| compile | 37ms | 51ms | 1.4x (one-time) | 1.65x |

Interpretation: PARITY on realistic version strings (20-313 chars); faster at 38 chars; the
remaining gaps are the degenerate 5-char case (~420ns absolute; walk-floor — ~10 group-boundary
pops + 11 memo inserts) and the 613-char reject (1.38x). Full closure would need group-boundary
fusion into edges (~2x on 5-char) or a onepass/PikeVM-with-counters hybrid for sub-1x.

Budget/fast-fail preserved: adversarial (?:a|aa){0,3000}b shape (JDK hangs forever) still fails
bounded — 798ms (was 124-405ms; the (state,pos)-chain walks are longer on that shape but still
step-budget-capped; sub-second, acceptable for a DoS guard).

Cumulative on this family: 25-400x JDK (regression state) -> 10-19x (8e4750c) -> 3.6-8.5x
(750f848) -> **0.98-1.2x re2j / ~2x JDK** (677417b).

## FOLLOW-UP: group-boundary fusion pass — TRIED, MEASURED, REVERTED (2026-09-17)

USER DIRECTIVE: "do the fusion pass now." Implemented fully and two ways:

Design: a boundary state (only effect = group enter/exit caps write, 1 eps out, no
anchor/backref/marker/char/accept/start) is never popped; its writes ride on incoming
edges. Eager variant applies ops (clone+write) at PUSH; deferred variant stores the ops
row on the frame and applies at POP (correct: push pos == the pos the state would have
been popped at; post-write dedup at the target is strictly better than the lost
pre-write row at the boundary state; liveness seeded from writer states + ops-edge
targets, conservative-superset sound).

Measured (SplitPerf / Re2jProbe, semver pattern, vs the shipped 677417b state):
- EAGER: 5ch 0.585us (win), 22ch 1.204 (+0.07), 313ch 23.5 (+2.4!), reject 57.6 (+7.8).
  Cause: greedy paths pile up ops-carrying marker-stop frames that are never popped —
  ~150 caps clones the pre-fusion engine never pays.
- DEFERRED (clone only when popped): 5ch 0.516 (win), 22ch 1.058 (par), 313ch 22.5
  (+1.4), reject 56.0 (+6.2). Instrumented: fusion removes only 10 pops on the 313-char
  accept (the prerelease loop body has NO boundary states — group writes sit on the loop
  EDGES, and the winning path never pops them pre-fusion either), while the 5th
  opsStack array costs ~0.9ns per push/pop across all ~1,700 frames; the reject path
  (~5-10x more pops from backtracking) pays the same per-frame tax again.
- CONCLUSION: fusion trades a 0.1-0.14us win on the degenerate 5-char case for
  1.4-6us losses on the realistic (20-313 char) and backtracking paths. NET NEGATIVE
  for the production shapes -> REVERTED (no code delta vs 677417b).

What would make short-input closure possible without this trade: encoding ops in spare
bits of the state id (zero overhead for ops-free patterns, ~0.3-0.5ns/pop with ops —
still net-negative for 313ch), or a onepass-style hybrid engine for the deterministic
prefix walk. Both recorded as known headroom; neither needed for the family's stated
goal (realistic-input parity, achieved at 677417b).
