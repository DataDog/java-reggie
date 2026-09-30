# Spec: address-pr-138-multi-lazy-loop-and-benchmark-input

## Problem
Review feedback on PR #138 (lazy-min scan-loop work) flagged two defects: (1) the
capture-heavy benchmark input `LazyScanLoopBenchmark.DOTTED_TEXT` places its first dot at
index 1, so `^(.+?)\.` succeeds on its first tail try and never executes
`emitRestoreCaptures` — the benchmark cannot catch regressions in the O(g)-per-failed-try
capture-restore path it claims to measure; (2) `parseChainQuantifier` admits every unbounded
lazy char-class quantifier independently, so the deterministic-chain detector routes patterns
with multiple lazy scan loops (e.g. `a+?a+?b`) off the linear BitState route onto chain
bytecode where every position of the outer scan re-runs the inner scan to the end — quadratic
per try, and the `matches`/`match` entry points carry no work budget because `LAZY_LOOP`
never sets `hasGiveBack`.

## Correct behaviour
- `DOTTED_TEXT = "abcdefghijklmnopqrstuvwxyz".repeat(8) + "."` (single 208-char dot-free
  token + trailing dot): every lazy tail try k = 1..207 for `^(.+?)\.` fails and pays the
  per-try capture snapshot/restore before the winning try at k = 208; the field javadoc states
  the new intent; the JMH methods and annotations are unchanged.
- `detectDeterministicChain` enforces the grammar's one-lazy-loop invariant per ChainBranch
  tree (branch seq, nested OPT/CAPTURE seqs, ALT_CHAIN alternative seqs; LOOP_ALT bodies
  counted for future-proofing): a branch tree containing 2+ `LAZY_LOOP` elements declines the
  pattern (returns null), keeping it on the linear BitState/PikeVM route. One lazy loop per
  branch stays admitted (`a+?x|b+?y` remains routed to the chain generator; each branch try is
  a single O(n·w) scan and find-family methods always carry the work budget + PikeVM
  fallback).
- The `detectDeterministicChain` javadoc records that the invariant is enforced and why (a
  second lazy loop inside a lazy tail is O(n^2) on the budget-free matches/match paths).
- Pre-existing detector and routing pins hold unchanged: `a+?x`, `a*?x`, `a{1024,}?x`,
  `a+?b`, `a*?b`, `^(.+?)\.([^/]+)` admitted; `a{2,4}?x`, possessive/atomic shapes declined;
  `a*?b`/`a+?b` still route to DETERMINISTIC_CHAIN_BYTECODE.

## Test plan
- `DeterministicChainDetectorTest`: new `multipleLazyScanLoopsDeclined*` tests —
  `a+?a+?b`, `a*?b*?c`, `^(a+?)b+?c` decline (each fails on pre-fix HEAD, where the detector
  admits these chains); `a+?x|b+?y` admitted with exactly one LAZY_LOOP per branch (scope pin
  against an over-broad whole-pattern regression).
- Existing suite green: `DeterministicChainDetectorTest`, `StrategySelectionTest`,
  `StrategySelectionExtendedTest`, `IastPatternRoutingTest`, then `./gradlew build`.

## Out of scope
- Generator-side budgeting for multi-lazy patterns (declined by detection instead).
- The stale `.sphinx/address/benchmark-evidence.md` javadoc reference (transient notes path).
