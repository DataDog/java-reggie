# Spec: lazy-min-chain-review-followups

## Problem
The uncommitted lazy-min extension (LAZY_LOOP `min > 0` admitted into the deterministic-chain
family; capture snapshot/restore + mandatory pre-roll in `emitLazyLoop`) passed review with 12
findings: one file-placement violation (`notes/` untracked but not git-ignored), two
performance/benchmark-evidence requests for the routing flip, and the rest test gaps or missing
documentation around the new admission boundary, first-set/emptyPrefix contract, min-width
accumulation, and capture-hygiene behavior. Production behavior is correct (all existing tests
pass; JDK ground truth verified); the work is tests, comments, gitignore, and benchmark evidence.

## Correct behaviour
- `notes/` is git-ignored; the transient wrap-up file is never committed.
- The admission boundary is pinned by tests: `min == MAX_CHAIN_LOOP_BOUND` (1024) admitted with
  elem min 1024 / branch minWidth 1025 and strategy DETERMINISTIC_CHAIN_BYTECODE;
  `min == MAX_CHAIN_LOOP_BOUND + 1` declined (detector null); `min == 0` still admitted.
- First-set/emptyPrefix contract pinned: `a+?b` → branch firstSetAscii = {a} only;
  `a*?b` → {a, b}; `a{2,}?x` → elem min 2, branch minWidth 3.
- Runtime parity vs java.util.regex (both the real `Reggie.compile` route and the direct
  compileChain harness) for: `\d+?` (full successive-find span sequence + no-match input),
  `a+?b` (tail-try-at-min both directions: "ab", "aab", "xaab"),
  `^([^\s]+?)(?::([0-9]+))?$` (capture hygiene: "relative:12x" → g2 null; "relative:12" →
  g1="relative", g2="12"), `^(.+?)\.([^/]+)` (capture-wrapped empty-tail shape).
- `emitLazyLoop` documents (a) why pre-roll failures jump to failLabel without restoring the
  capture snapshot (every branch try is preceded by emitResetCaptures, so stale slots are
  unobservable), and (b) why the full-slot restore per failed tail try is acceptable cost.
- Benchmark evidence exists for the re-routed lazy shapes (`\d+?`, `<.+?>`, `^(.+?)\.`):
  reggie vs JDK throughput, before (BITSTATE route) and after (chain route), with the numbers
  recorded; the chain route must stay within 1.5x of the JDK baseline per shape.

## Constraints
- Existing route pins (`a*?b`, `<.+?>`, `a+?b`, `\d+?` → DETERMINISTIC_CHAIN_BYTECODE) unchanged;
  admission condition `q.min >= 0 && q.min <= MAX_CHAIN_LOOP_BOUND` unchanged.
- Repo rules: `spotlessApply` before commit; `pushInt` for int constants; tests live in the
  existing suites (DeterministicChainDetectorTest, StrategySelectionExtendedTest,
  PikeVmCaptureRegressionTest, DeterministicChainV3BytecodeTest).
- Benchmark is a new JMH class in reggie-benchmark, run manually via the `jmh` gradle task with
  short iterations; before-run measured with ONLY the two production files stashed.

## Scope

### Primary fixes
- F-2c170e02c9b1: `.gitignore` — add `notes/` (transient session wrap-ups must not be committed).
- F-ac9d70c1902d: PatternAnalyzer.java:10839 boundary — tests at min=1024 (admitted) /
  min=1025 (declined) / min=0 (admitted) + strategy pin for the admitted boundary case.
- F-77e4bcc2a40a: PatternAnalyzer.java:10841 — targeted JMH benchmark + before/after evidence.
- F-656d11bf48c5: PatternAnalyzer.java:11149 — reviewer's fall-through claim is false (code
  breaks); fix = comment documenting the mandatory-consume break contract + first-set test.
- F-8e5397f23c99: PatternAnalyzer.java:11072/11152 — first-set/emptyPrefix output tests for
  `a+?b` / `a*?b`.
- F-85b1d768cfb6: PatternAnalyzer.java:11279 — min-width test for `a{2,}?x` (min==2,
  minWidth==3) + runtime parity on a short input.
- F-ab12c5895b5e: DeterministicChainBytecodeGenerator.java:2381 — behavioral parity test for
  the capture-hygiene shape (g2 null on "relative:12x"; groups vs JDK), both routes.
- F-f45daa6be9e7: DeterministicChainBytecodeGenerator.java:2389 — pre-roll parity test for
  `a+?b` on min+tail / min+1+tail inputs.
- F-f3f00ed53217: DeterministicChainBytecodeGenerator.java:2413 — comment documenting the
  failLabel invariant + the capture-hygiene test covers the outer-retry-succeeds scenario.
- F-c3d19c25acd2: DeterministicChainBytecodeGenerator.java:2444 — cost-analysis comment at the
  restore site + shared benchmark evidence (full restore kept; per-slot optimization declined).
- F-8b693a4cec8e: StrategySelectionExtendedTest.java:306 — runtime behavioral tests for the
  empty-tail lazy shapes (placed in PikeVmCaptureRegressionTest; see tester plan).
- F-93c9337a4dd9: PikeVmCaptureRegressionTest.java:176 — `\d+?` behavioral parity +
  predicate-rejection capture case via the real route.

### Auto-expanded sibling fixes
None identified. Audit performed: the three analyzer LAZY_LOOP sites (checkChainDisjoint ~11072,
computeChainFirst ~11147, chainSeqMinWidth ~11278) are the only `e.min` consumers of the new
admission and are all covered by the planned tests; the generator's only LAZY_LOOP emitter is
`emitLazyLoop`; ChainElem's structural hash already mixes `e.min` (PatternAnalyzer ~8325, pinned
by the existing min=1/min=0 collision test); GREEDY_LOOP/LOOP_ALT `min > 0` handling predates
this change and is exercised by existing greedy/loop-alt tests.

## Assumptions
- A targeted JMH subset (new LazyScanLoopBenchmark, short iterations) is acceptable benchmark
  evidence; the full 322-benchmark suite before AND after is out of scope for this pass.
- JDK per-digit successive-find semantics for `\d+?` (measured: [0,1) [1,2) [2,3) [6,7) [7,8)
  [8,9) on "123abc456") is the parity target, not the reviewer's digit-run-span guess.
- `notes/` stays on disk locally; only the .gitignore rule is added.
