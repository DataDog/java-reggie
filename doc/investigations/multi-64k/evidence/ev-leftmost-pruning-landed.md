---
id: ev-leftmost-pruning-landed
type: evidence
status: confirmed
depends_on: [ev-lazy-hybrid-landed]
supersedes: []
related: [ev-lane1-jit-hybrid-landed]
tags: [pruning, leftmost-first, re2, lazy, 502181d]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# RE2 leftmost-first pruning LANDED (502181d): matched 282.9 -> 225.3us (-20%), reggie > rust

## Mechanism
SubsetConstructor.setLeftmostFirst: during construction, kill every NFA state ranked strictly
below the first positionally-fireable accept (unconditional or START-class; END-class-only never
fires mid-scan). Pruned set = DFA state identity (prune BEFORE stateCache lookup); cut/conflict
flags recomputed on pruned sets -> unresolved conflicts become immediate-commit cuts.
Enabled ONLY for the lazy-aware captureless retry (PatternAnalyzer sites) and ONLY for
ANCHOR-FREE NFAs: post-consume closures discharge anchor conditions (guard moves to the
consuming edge's entryGuard, enforced by codegen) — the pruner's conds view would mistake a
guard-conditional accept for unconditional (fuzz 777: [^1-a1-c-]\za|([b01-a]{2,})a{0}a{0,}?
committed branch2's greedy loop at its minimum [3,5) vs jdk [3,8); caught pre-commit, fixed by
the anchor-free gate). Anchor-free = no guards = thread ranks alone decide.

## HybridMatcher.lazyFind
The pruned DFA encodes the leftmost-first END (search semantics); boolean matches() is
path-existence and would false-negative (527/270k real-input divs pre-fix). lazyFind flag: when
the dfa-half came from the pruned retry, matches/matchesBounded/match/matchInto/matchBounded
route to the NFA half (exactly the standalone engine's answer); find/findFrom/findMatch keep the
fast pruned DFA. Flag = originalResult.lazyNfa && dfaResult.dfa != null (a lazy original's DFA
can only come from the retry).

## Measured (box, same-day, controls flat: rust 232.5/1726.9, jdk 654.4/31766)
- MATCHED 282.913 -> 225.300±10.6 us (-20.3%) — REGGIE NOW FASTER THAN RUST on matched
  (225.3 vs 232.5), 2.9x ahead of jdk.
- NOMATCH 693.690 -> 700.023±21.5 us (flat within noise).
- Stack-frame chain DFA: 19 states / 13 unresolved -> 12 states / 0 unresolved; find spans
  JDK-identical ([0,1) tab frames, [0,5) at-frames).
- Corpus: 66 -> 74 hybrid, BitState 22 -> 14. Lane-2 arc matched total: 340 -> 225.3 (-34%).
- Two-tranche blended: no-match +5.3% / matched -28% -> blended per-pair 3.67 -> 3.46ns (-5.7%)
  — the lazy lane is now net-positive.

## Gates
Full suite; RealFindParity 266,662/0; RealInputParity 270,351/0; fuzz 777/48879/131071 zero.
Fuzz earned its keep twice this tranche (anchor-discharge bug; matches() false-negatives).
