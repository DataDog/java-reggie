# Spec: preserve-named-only-capture-projection

run_id: address-pr153-recovery-20261022T155147Z-v3Nq7Ls5

## Problem
The over-JIT split matcher (`OverJitSplitMatcher`, built by `RuntimeCompiler.overJitNative`) runs
search calls on a `LaurikariDfaMatcher` and whole-input calls on a `JavaRegexFallbackMatcher`. It must
behave as one matcher. Two defects break this:

1. **Capture layout disagreement (4346173428).** With `CAPTURE_NAMED_ONLY`, `compileInternal`
   projects the AST (`CaptureProjection.preserveNamedAndSemanticCaptures`, RuntimeCompiler.java:1600-1601),
   and the Laurikari half is built from that projected AST (RuntimeCompiler.java:1508). The JDK half is
   `new JavaRegexFallbackMatcher(pattern)` (RuntimeCompiler.java:1519) over the original pattern text.
   So `findMatch`/`findMatchInto` report projected-away unnamed groups as -1, while
   `match`/`matchInto`/`matchBounded` report them as matched.
2. **Overflow truncates the current call (4346261416).** In `LaurikariDfaMatcher.runFind`
   (LaurikariDfaMatcher.java:305-312), a mid-scan `LaurikariDFACache.FALLBACK` returns `best` when it is
   already non-null. A higher-priority greedy thread may still be extending the match, so the result is
   truncated (`[0,8)` instead of `[0,10009)` for the reported repro). `OverJitSplitMatcher.searchHalf()`
   (OverJitSplitMatcher.java:52-56) sends later calls to the JDK half, which returns the full span. The
   result therefore depends on the cache state.

## Correct behaviour
Shared rule S (OverJitSplitMatcher contract):
- S1: For split-admitted patterns, every search-family call returns the same bounds and group spans as
  `java.util.regex`. The result does not depend on how many earlier calls were made on the instance,
  that is, on the Laurikari cache state or `saturated()`.
- S2: For an input matched in full, the search family (`findMatch`, `findMatchInto`, cursor, findAll) and
  the whole-input family (`match`, `matchInto`, `matchBounded`) report identical spans for every group
  index 0..groupCount.
- S3: A pattern is admitted to the split only when both halves share the same capture layout and the
  same case-folding semantics. Excluded patterns route to the BitState branch of `overJitNative`.
  Exclusions: case-insensitive patterns (existing) and `CAPTURE_NAMED_ONLY` (new).

Cluster A:
- A1 Ownership: `compileInternal` decides the capture layout once, from the options. Every engine paired
  inside one returned matcher is built from that layout.
- A2 Transition: `CAPTURE_NAMED_ONLY` + `ALLOW_JDK_FALLBACK` + over-JIT + BitState-eligible routes to
  `BITSTATE_CAPTURE (generated method over the JIT size limit)`, never to
  `LAURIKARI find / JDK matches (over JIT limit)`. Without `CAPTURE_NAMED_ONLY`, routing is unchanged.
- A3: Both `overJitNative` call sites inherit the exclusion: the structural-cache hit (~1955) and the
  cache miss (~2040).
- A4 Cache hygiene: `OVER_JIT_SPLIT_CACHE` and `BITSTATE_NFA_CACHE` are keyed by `CompilationKey`, which
  includes the options. No named-only split entry is created, and the cache does not alias entries
  across option sets.

Cluster B (overflow policy, `runFind` state machine):
- B1: `LaurikariDFACache` never evicts. On `FALLBACK`, the current call is redone in full on the PikeVM
  fallback from `clamped`, and `fallbackCount` is incremented, which flips `saturated()`.
- B2 `runFind` transitions:
  - start `DEAD` → `null`
  - start `FALLBACK` → whole-call PikeVM
  - mid-scan `FALLBACK` → whole-call PikeVM from `clamped`, ALWAYS (this is the fix)
  - mid-scan `DEAD` → `best`
  - end of input → `best`
- B3: `runAnchored` already follows B1 at both FALLBACK sites and stays unchanged.
- B4: `saturated()` flips during the saturating call. That call completes correctly through
  Laurikari→PikeVM, and later search calls go to the JDK half. S1 holds across the switch.
- Concurrency: the find half is a fresh mutable instance per `compile()` (single-threaded per the API
  contract). The JDK half is shared and stateless per call. No locking exists before or after the fix.

## Constraints
- The strict-compile contract is unchanged: without `ALLOW_JDK_FALLBACK`, the split is never built.
- These stay unchanged: non-named-only split routing, the routing-note strings, and existing
  `OverJitSplitMatcherTest` expectations.
- Hot path: the change is on the overflow branch only and adds no allocation. The admission check runs
  at compile time.
- `saturated()` semantics (`fallbackCount != 0`) are unchanged.
- Style: Google Java format (2-space). The javadoc explains why an exclusion exists, in the style of the
  existing case-insensitive sentence.

## Scope

### Primary fixes
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java:1509-1511` (finding
  anchored at :1519). Defect class: half-disagreement in a composite matcher. Fix: add
  `!options.has(ReggieOption.CAPTURE_NAMED_ONLY)` to the split admission condition, and document the
  exclusion in the `overJitNative` javadoc (1470-1488).
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/LaurikariDfaMatcher.java:311` (finding
  anchored at `OverJitSplitMatcher.java:55`). Defect class: bounded-cache overflow returns a partial
  result instead of completing the same operation through the fallback. Fix: on mid-scan FALLBACK,
  always `return fallbackFind(input, clamped);`. Also tighten the `OverJitSplitMatcher` class javadoc
  (lines 28-33). That change is documentation only; dispatch does not change.

### Auto-expanded sibling fixes
None identified. Candidates checked:
- `best == null ?`: the only occurrence in runtime main is LaurikariDfaMatcher.java:311.
- `new JavaRegexFallbackMatcher(` occurs at two places. RuntimeCompiler.java:1292 (`fallbackOrThrow`)
  is a whole JDK matcher and internally consistent, so it is not a composite. RuntimeCompiler.java:1519
  is the primary.
- `compileHybrid` gets the post-projection `ast` (RuntimeCompiler.java:1849-1859), so both of its halves
  share the layout. REACHABLE: no, structurally impossible.
- `runAnchored`, start-state FALLBACK, `LazyDFACache.findEnd`, and the `BitStateMatcher` budget
  fallback either delegate the whole call or continue from the frozen NFA set. They were verified OK in
  design adversary round 1.

## Assumptions
- Excluding named-only patterns from the split, rather than projecting the JDK half, is acceptable. The
  reviewer offered it as an option, and case-insensitive patterns already set the precedent.
- PikeVM is the correctness reference for Laurikari-eligible NFAs.
- The reported repro saturates the find cache on the first call. This needs runtime evidence, so the
  tester asserts it.
- The PikeVM redo cost on an overflow call is acceptable.
- The slug comes from the first item's title ("Preserve named-only capture projection in the JDK
  half"), truncated to 40 chars and then trimmed to a word boundary.
