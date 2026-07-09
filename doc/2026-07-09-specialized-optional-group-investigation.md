# Specialized-codegen feasibility for `(a)?b`-shaped patterns — investigation notes

Status: **investigated, not implemented**. No code changes from this thread have landed.

## Background

`AllStrategyVsJdkBenchmark`'s `pikevmCapture`/`optimizedNfa` cases (`(a)?b` /
`(?<x>a)?b`, both routed to `BITSTATE_CAPTURE`) trail JDK 4-5x (0.235x / 0.279x
in the 2026-07-09 run, see
[`perf-results/2026-07-09-all-strategy-vs-jdk-benchmark.json`](perf-results/2026-07-09-all-strategy-vs-jdk-benchmark.json)).
This note records why that routing happens, whether it can be avoided with a
new/extended codegen strategy, and why that work was not started.

## Why these patterns route to `BITSTATE_CAPTURE`

`PatternAnalyzer.java` forces `PIKEVM_CAPTURE` (which then routes to
`BITSTATE_CAPTURE`) whenever:

```java
FallbackPatternDetector.hasNullableOuterQuantifierOnCapturingGroup(ast)
    && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)
```

(`PatternAnalyzer.java:1131-1132`, repeated at `1285-1286` and guarding several
sibling cases at lines 1171-1347). This condition is true for both `(a)?b` and
`(?<x>a)?b` — the named-group variant hits the same branch because none of the
conditions in this cascade check `hasNamedGroups`.

This is a **correctness guard**, not an arbitrary routing choice: a
TDFA/DFA-based capture strategy determinizes "group taken" and "group
skipped" NFA states into a single DFA state whenever they transition
identically going forward, which loses the information needed to correctly
tag the group's span. `BITSTATE_CAPTURE` (bounded backtracking with an
explicit visited-set budget) is used instead because it tracks group spans
per-path rather than per-DFA-state.

## Could an existing specialized generator be extended to cover this shape?

`SPECIALIZED_QUANTIFIED_GROUP` (built for POSIX last-match semantics on
capturing groups inside *repeating* quantifiers — `(a)+`, `(a|b)+`,
`([a-z])*`) looked like the closest existing fit, since its body-shape
detection (single literal, single char class, or per-branch
single-char/unbounded-single-step alternation) doesn't obviously exclude
`min=0,max=1` groups. Two independent gates block it from ever being tried on
`(a)?b`, both structural rather than incidental:

1. **`GroupInQuantifierDetector.visitQuantifier`**
   (`PatternAnalyzer.java:5960`): `isRepeating = (node.max == -1 || node.max >
   1)`. This deliberately excludes `?`/`{0,1}` groups, because the detector
   was built to solve "which iteration's capture wins" (a problem that only
   exists when a group can match more than once) — it was never meant to
   address B16's distinct "group-taken-vs-skipped states get merged in the
   DFA" problem.

2. **`extractQuantifierFromPattern`** (`PatternAnalyzer.java:8343`): requires
   the quantifier to be the *entire* pattern body, modulo anchors and
   non-capturing wrappers. Any trailing content after the quantified group —
   e.g. `(a)?b`'s literal `b` — makes it return `null` immediately, regardless
   of gate 1. `extractLiteralString(RegexNode)` (`PatternAnalyzer.java:9108`)
   already exists and would be directly reusable for capturing such a
   suffix, if this gate were relaxed.

Both gates would need to change, and — more importantly — the actual bytecode
emission in `QuantifiedGroupBytecodeGenerator.java` (1278 lines) would need a
suffix-match step woven into every one of its generated `ReggieMatcher`
methods (`find`, `findFrom`, `match`, `matchBounded`, etc., roughly 9 code
paths). This is comparable in scope to writing a new, narrowly-scoped
`SPECIALIZED_OPTIONAL_GROUP` generator from scratch (`FixedSequenceBytecodeGenerator.java`,
a generator of similar shape/complexity, is 873 lines) — not a small
extension.

## Why the `BITSTATE_CAPTURE` gap can't be closed by tuning `BitStateMatcher` itself

Before considering new codegen, `BitStateMatcher`'s job-stack push path was
profiled directly (async-profiler, `output=collapsed`, leaf-frame
aggregation over the `pikevmCapture` benchmark):

```
76.14%  383  BitStateMatcher.search
 8.15%   41  BitStateMatcher.pushEpsilonChildrenReversed
 4.97%   25  BitStateMatcher.ensureStackCapacity
 2.58%   13  BitStateMatcher.pushRestore
 1.59%    8  LazyDFACache.findFrom
 ...
```

76% of CPU is genuine self-time in `search()`'s own dispatch loop, not in
stack-management helpers. A batching cleanup to `pushExpand`/
`pushEpsilonChildrenReversed` (already applied, see below) is
correctness-preserving but — as profiling predicted — perf-neutral on this
benchmark. The gap is structural: `BitStateMatcher`'s ReDoS-safe
generation-stamped visited-set and explicit job-stack bookkeeping cost more
per step than JDK's `Node`-subclass interpreter (a plain object-graph walk
with virtual `match()` dispatch — not "hand-compiled bytecode", contrary to
an earlier claim in this thread that was checked via `javap` and retracted).
Closing it requires either a new bytecode-generation path that avoids
`BitStateMatcher` entirely for this pattern shape (the option evaluated
above), or a different runtime for exactly the `min<=1` optional-group case —
neither was scoped in this session.

## Verified, unrelated fixes from the same investigation (uncommitted)

- `CountingGlushkovRuntime.java`: added `minCharsPerRep` pruning to
  `findFrom`/`findBoundsFrom`, fixing a genuine O(n²) restart pathology
  (rescanning from every candidate start position even when reaching
  `counterMin` repetitions is provably impossible). `dfaTable`/
  `COUNTING_GLUSHKOV` benchmark: 0.717x → 1.012x vs JDK; 91x speedup on a
  constructed adversarial input. All `CountingGlushkovRuntimeTest` (14/14)
  and `CountingGlushkovRoutingTest` (11/11) pass.
- `BitStateMatcher.java`: batched `ensureStackCapacity` calls across a
  fan-out instead of once per transition/epsilon-child. Correctness-preserving,
  measured perf-neutral (see profiling above) — kept for the reduced
  redundant capacity checks, not as a fix for the `pikevmCapture` gap.

## Conclusion

Generalizing `SPECIALIZED_QUANTIFIED_GROUP` (or building a new dedicated
strategy) for `(a)?b`-shaped patterns is architecturally sound and would
plausibly close the gap, but is multi-hour scoped work (detector changes in
two places + ~9 code paths of new bytecode emission), not a quick win. Not
started this session; flagging for a dedicated follow-up if prioritized.
