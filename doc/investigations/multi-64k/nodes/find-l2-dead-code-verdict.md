---
id: find-l2-dead-code-verdict
type: finding
status: confirmed
depends_on: [ev-split-coverage-probe, find-l2-splitter-shipped]
supersedes: []
related: [hyp-v2-driver-lowering, find-no-overflow-trigger-today, q-l2-disposition]
tags: [effectiveness, dead-code, battery, nfa-cascade, wide-switch, disposition]
generator: cairn
created: 2026-09-16
updated: 2026-09-16
---

# L2 splitter verdict: fires on nothing real — effectively dead code as shipped

## Reasoning chain
Battery (ev-split-coverage-probe): every CONSTRUCTIBLE overflow family routes to
NFABytecodeGenerator's per-config cascade or equally switch-dominated shapes — 12 oversized
methods across 6 families, **12/12 declined, 0 rescued** (v1 + both post-ship fixes). The only
linear-code family, `(abcdefgh)\1{N}`, is loop-compact and never overflows. Combined with the
benchmark evidence (0 splits across the entire corpus, APT + runtime paths): **no real
generator pattern can be split by v1** — the split path fires only on synthetic shapes (unit
tests).

Costs of keeping it wired in:
- +9% (point estimate) compile-time on EVERY pattern (MethodNode buffer + replay passthrough).
- 2–12× slower failure on oversized patterns (analysis runs, then declines; bounded by the
  total-compile deadline → same L3 outcome as baseline, just slower; capalt2k went 1.1s → 9.5s,
  nearly the 10s deadline).

What would make it live: (a) v2 driver lowering handling wide-switch cascades (hyp-v2 —
every overflow family needs exactly this), or (b) NFABytecodeGenerator L1 bucketing (shrinks
those methods instead — REMOVES the need for L2 there). Recalibrated: P(b) supersedes L2 for
the known families ≈ 0.4-0.5 (see ev-split-coverage-probe) — "probably supersede" was
overstated; it is the plausible default, roughly even odds. Note the disposition question is
invariant to this: v1 fires on nothing either way; the supersede probability only informs
WHETHER/WHEN to build v2 or NFA-L1, not whether to unwire v1.

**DISPOSITION (2026-09-16): option 3 — DROPPED** (user decision; see q-l2-disposition).
Work preserved as patches in this investigation's reports/.

## Options (were)
1. Keep as-is: net for unforeseen shapes; insurance costs +9% + slower failures.
2. Unwire (keep code+tests, remove the SplittingClassVisitor wrap from the two pipeline sites
   or gate behind an option): restores baseline compile-time; machinery stays reviewed+tested.
3. Drop entirely: simplest; loses machinery.
4. Build v2: only path to liveness on real patterns; large scope.
