---
id: ev-lazy-hybrid-landed
type: evidence
status: confirmed
depends_on: [ev-lane1-jit-hybrid-landed]
supersedes: []
related: [q-hybrid-anchored-admission]
tags: [lazy, hybrid, certification, 7a73126]
generator: cairn
created: 2026-09-17
updated: 2026-09-17
---

# Lazy-aware captureless DFA retry LANDED (7a73126): matched -9.8%, no-match +4.3%, blended flat

## Mechanism
Lazy patterns could never hybridize: the standard NFA build normalizes lazy->greedy preference,
so a subset DFA from it returns the longest end (wrong for find()). compileInternal now retries
the captureless analysis with a lazy-aware NFA rebuild (same as PikeVM/BitState use);
PatternAnalyzer.analyzeAndRecommend(true, lazyNfa) swaps the analysis NFA + bypasses the lazy
gates for that pass; central certification (lazyCapturelessDfaIsUnsound): (1) no accepting state
with hasPriorityConflictTransition && !acceptIsPriorityCut; (2) END-class anchors race only via
\n-consuming outgoing transitions (STRING_END_ABSOLUTE never races); (3) diluted mid-pattern
START anchors decline. compileHybrid threads the lazy NFA to whichever half needs it (NFA
identity must match the analysis that built it).

## What the certification decided on the corpus
- CERTIFIED (9 patterns total hybridized, 57->66): kind-message1 `^(?<kind>.+?): (?<message>.+?)( --->.+)?$`
  (0 unresolved; `.` excludes \n so the $ cannot race), git-diff, archive-prefix, at-frame variant, etc.
- DECLINED honestly: stack-frame chain (13 REAL unresolved — greedy [^@]* consumers outrank the
  skip-to-accept path; its ungated hybridization diverged 530/266k find pairs, caught pre-commit);
  kind-message2 ([^ ] consumes \n -> real $ race); at-frame main (1 unresolved).

## Box (same day, controls flat: rust 227.4/1724.7, jdk 649.4/31673.8)
- MATCHED 313.667 -> 282.913±7.4 us (−9.8%, real)
- NOMATCH 665.344 -> 693.690±13.1 us (+4.3%, borderline-real; error bars disjoint)
- Blended per-pair: 3.66 -> 3.65 ns/pair — NET FLAT (no-match pairs ~90% of find-parity corpus).
- Probe artifact learned: per-pattern no-match probes in a 513-pattern JVM overstate generated-code
  costs ~20-200x vs isolated runs (VCS pattern: 377ns isolated / 7.5us with classes loaded /
  82us/pair with all patterns' warm loops run) — JVM-state dependent, NOT fleet-relevant (82us
  x its ~480 real no-match pairs would exceed the entire 693us JMH sweep). JMH remains the only
  acceptance number; single-pattern probes only for RANKING.

## Gates
Full suite; RealFindParity 266,662/0 div; RealInputParity 270,351/0; fuzz 777/48879/131071 zero.

## Matched residual after this tranche (per MatchSweepProbe3 ranking)
1. semver maven (PikeVM, 58.6us/5pr) — possessive {1,19}+ gate, NOT lazy. Separate tranche.
2. stack-frame family + kind-message2 + at-frame — all need PRIORITY-PRUNING subset
   construction (cut lower-priority threads at first accept in state sets) = the lane-1
   "priority-correct lazy DFA" remainder. Well-defined: RE2 leftmost-first DFA semantics;
   would also unlock the alternation semvers.
