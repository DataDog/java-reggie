---
id: ev-counted-loop-fix-verification
type: evidence
status: confirmed
depends_on: [find-bounded-quantifier-regression, hyp-counted-loop-lowering]
supersedes: []
related: [q-prod-readiness, ev-backend-usage-survey]
tags: [counted-loop, fix, before-after, semver, perf, parity, budget, commit-8e4750c]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Counted-loop lowering: before/after on the real logs-backend semver pattern (commit 8e4750c)

| metric | before (a5ef0fb) | after (8e4750c) | JDK reference |
|---|---|---|---|
| NFA states | 567,550 | ~2,600 (markers) | n/a (lazy loops) |
| compile | 2,257 ms | **61 ms** | 1 ms |
| match 5-38ch | 10-41 us/op (25-120x slower) | 1.5-2.8 us/op (**6.5-8.5x**, 750f848) | 0.23-0.36 us |
| match 313ch | ~exceeded probe | 41.2 us/op (**3.6x**, 750f848) | 11.4 us |
| >256-iteration input | (unrolled: correct) | **correctly REJECTED** (counter) | rejected |
| adversarial ambiguous shape | (n/a) | MatchBudgetExceededException in 405ms | unbounded hang |

Parity: full span+group parity with java.util.regex on the CountedLoopSemanticsTest battery
(9 tests: small-quantifier unrolled path, forced-counted greedy/lazy, min/max enforcement,
nested counted loops, backref+counted combination, real semver incl. >256 rejections,
adversarial fast-fail). Full codegen/runtime/processor/integration suites green; spotless
clean. Probes: /tmp/census/{CountedSmoke,CountedPerf,BudgetProbe}.java.

Follow-up 750f848 (2026-09-17, user directive "fix the TODO"): DFS hot-path allocations
eliminated — flat open-addressing int[] memo table (generation-stamped, O(1) reset),
parallel primitive frame stack (no Frame/ArrayList churn), copy-on-write captures,
per-thread dimension-checked workspace. Ratios 10-19x -> 3.6-8.5x-JDK; 313-char case
113.7 -> 41.2us/op. Budget behavior unchanged (adversarial shape still fast-fails, 229ms).
Parity suites + full suites green.

Remaining headroom (honest): ~3.6x on the worst input is the interpreted-walk floor
(per-state row hash/probe + epsilon stepping); the BitState loop-representation
experiment (2-2.7x) suggests ~1.5-2x more exists but needs structural work (epsilon
closure flattening / active-loop-only memo keys), not allocation work. Not required
for migration: 61ms compile once-per-pattern under cache; 1.5-41us/match bounded and
semantics-exact.
