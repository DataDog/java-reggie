---
id: find-l2-dead-code-verdict
title: The L2 generic splitter fired on nothing real (12/12 constructible overflow families declined, +9% compile time on every pattern) — DROPPED from the tree; liveness requires a v2 wide-switch-cascade driver or NFA L1 bucketing
kind: finding
tags: [effectiveness, dead-code, battery, nfa-cascade, wide-switch, disposition]
applies_to: []
source: multi-64k/find-l2-dead-code-verdict
status: active
recorded: 2026-09-28
valid_at: 2026-09-28
---



# L2 splitter verdict: declines every real overflow, costs every compile — dropped

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

What would make it live: (a) a v2 driver lowering wide-switch cascades (every overflow family
needs exactly this), or (b) NFABytecodeGenerator L1 bucketing (shrinks those methods instead —
REMOVES the need for L2 there). P(b) for the known families ≈ 0.4-0.5 — roughly even odds; the
disposition is invariant to this (v1 fires on nothing either way).

**DISPOSITION: DROPPED** — work preserved as patches (`reports/l2-splitter-v3-dropped.patch`,
`reports/l2-splitter-v1-benchmark.patch`, apply from 2fc44cc).
