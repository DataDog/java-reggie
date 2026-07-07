# Pinned backreference boundary — Implementation Plan

**Date:** 2026-07-06
**Design:** [doc/2026-07-06-backreference-pinned-boundary-design.md](2026-07-06-backreference-pinned-boundary-design.md)
**Branch:** `perf/eliminate-slow-paths`

---

## 0. Prerequisites and conventions

- Build commands:
  - `./gradlew :reggie-codegen:test` — analysis/routing tests
  - `./gradlew :reggie-runtime:test` — codegen + runtime correctness tests
  - `./gradlew :reggie-runtime:debugPattern -Ppattern="<pattern>"` — inspect strategy/bytecode for a pattern (use this instead of ad-hoc `System.out` scripts)
  - `./gradlew spotlessApply` before every push
- New strategy name: `PINNED_BACKREFERENCE`, added to `PatternAnalyzer.MatchingStrategy`
  (enum body currently lines 3249–3309, alongside `FIXED_REPETITION_BACKREF`/`VARIABLE_CAPTURE_BACKREF`
  at 3273–3274).
- Bytecode generators live in package `com.datadoghq.reggie.codegen.codegen`
  (**not** `.codegen.generator` — the design doc's package name is wrong; there is no
  `generator/` directory). New generator: `PinnedBackreferenceBytecodeGenerator.java` in that
  package, modeled on `FixedRepetitionBackrefBytecodeGenerator.java`.
- Every `PatternAnalyzer.java` line number below was re-verified against current HEAD; the
  design doc's own citations for anything above line ~2700 are stale by roughly +64 lines
  (file has grown) — this plan uses the corrected numbers.
- `StrategyCorrectnessMetaTest` exercises **every** `MatchingStrategy` value's 8 public
  `ReggieMatcher` methods against JDK with a 0-mismatch gate — the new strategy must have a
  working `matches`/`find`/`findFrom`/`match`/`findMatch`/`findMatchFrom` (+ Bounded variants)
  before that gate will pass. Wire the full rich API in Task 1, not a boolean-only stub.

---

## Task 1 — New `MatchingStrategy` enum value + exhaustive-switch wiring

**Files:**
- `reggie-codegen/…/analysis/PatternAnalyzer.java` — enum body, lines 3249–3309
- `reggie-codegen/…/analysis/PatternDebugger.java` — `switch (result.strategy)` at line 59
- `reggie-codegen/…/analysis/StrategyJdkClassifier.java` — `switch (strategy)` at line 78
  (default `NATIVE` fallthrough covers the new strategy once the generator emits the rich API —
  no case needed unless it must be `RICH_API_HYBRID`)
- `reggie-runtime/…/RuntimeCompiler.java` — `generateBytecode`'s `switch (result.strategy)`
  at line 951 (add case near `FIXED_REPETITION_BACKREF`/`VARIABLE_CAPTURE_BACKREF`); `isNfaBacked`
  switch at line 894 (confirm `default: false` covers it — the strategy is a single forward pass
  with no NFA-thread state, so no change expected)
- `reggie-processor/…/ReggieMatcherBytecodeGenerator.java` — `switch (strategy)` at line 295
  (compile-time `@RegexPattern` path; add case near `FIXED_REPETITION_BACKREF` (647) /
  `VARIABLE_CAPTURE_BACKREF` (631))

**Changes:**

1. Add `PINNED_BACKREFERENCE` to the enum, next to `FIXED_REPETITION_BACKREF`/`VARIABLE_CAPTURE_BACKREF`:
   ```java
   /** Backreference whose group boundary is provably unambiguous (disjoint follow-set): single
    *  forward pass, no retry/backtracking. */
   PINNED_BACKREFERENCE,
   ```
2. `PatternDebugger` switch: add a `case PINNED_BACKREFERENCE:` printing the carrier's key fields
   (group index, disjointness proof summary) — mirror the existing case for
   `FIXED_REPETITION_BACKREF`.
3. `RuntimeCompiler.generateBytecode`: add a case that instantiates
   `PinnedBackreferenceBytecodeGenerator` and calls the same method set
   `FixedRepetitionBackrefBytecodeGenerator` wires at its call site (see Task 1.6 for the exact
   method list).
4. `ReggieMatcherBytecodeGenerator`: add the matching compile-time case.

**Completion criterion:** `./gradlew :reggie-codegen:compileJava :reggie-runtime:compileJava
:reggie-processor:compileJava` passes with the new enum value referenced everywhere but not yet
reachable (no detector wired yet).

---

## Task 2 — `PinnedBackreferenceInfo` carrier

**File:** new file, `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PinnedBackreferenceInfo.java`
(sibling of `FixedRepetitionBackrefInfo.java`/`VariableCaptureBackrefInfo.java` — own file, not an
inner class, matching the existing convention for backref info carriers in this package).
`PatternInfo` is a top-level interface in the same package
(`reggie-codegen/…/analysis/PatternInfo.java`), not a nested type of `PatternAnalyzer` — implement
it directly (`implements PatternInfo`), same as `FixedRepetitionBackrefInfo`/`VariableCaptureBackrefInfo` do.

**Fields (minimal, allocation-free at match time — everything here is compile-time-only data):**

```java
public final class PinnedBackreferenceInfo implements PatternInfo {
    public final int groupIndex;          // the backreferenced group's 1-based index
    public final RegexNode groupBody;     // G's content node (for codegen's forward-scan)
    public final CharSet groupCharSet;    // getGreedyGroupCharSet(groupBody) / getNodeCharSet
    public final RegexNode separator;     // node(s) between G's close and the backref site, or null
    public final CharSet separatorCharSet; // charset of separator, or null if no separator
    // ... constructor
}
```

Add `structuralHashCode()` per the Structural Hash Rule (`StructuralHash.java`) — the carrier's
fields (`groupIndex`, charsets) affect generated bytecode, so a cache collision on structural hash
would silently reuse the wrong generated class. Model the hash on
`FixedRepetitionBackrefInfo.structuralHashCode()`.

**Completion criterion:** Compiles; unit test constructs one instance and confirms
`structuralHashCode()` differs for two carriers with different `groupIndex`/charsets.

---

## Task 3 — `hasPinnedBackrefBoundary` / `detectPinnedBackreference` detector

**File:** `reggie-codegen/…/analysis/PatternAnalyzer.java`

**Reused utilities (all already exist, all verified present):**
- `getGreedyGroupCharSet(RegexNode)` — lines 2524–2556
- `getNodeCharSet(RegexNode)` — lines 2559–2585
- `getFirstCharSet(RegexNode)` — lines 2591–2630
- `getSuffixFirstCharSetSkippingNullable(ConcatNode, int)` — lines 2654–2667
- `CharSet.intersects` (line 588) / `CharSet.isDisjoint` (line 592) in
  `reggie-codegen/…/automaton/CharSet.java`

**New method** `detectPinnedBackreference(RegexNode ast)` returning `PinnedBackreferenceInfo` or
`null`:

1. Locate the capturing group `G` and its backreference `\N` in the top-level `ConcatNode`
   (reuse the group/backref-pairing logic already used by `detectFixedRepetitionBackref` /
   `detectVariableCaptureBackref` — do not re-derive it from scratch).
2. Compute `G`'s content charset via `getGreedyGroupCharSet(G)` (fall back to `getNodeCharSet` if
   `G` isn't itself the greedy node — match whichever accessor
   `requiresBacktrackingForGroups` (lines 2491–2518) uses for its analogous check, for
   consistency).
3. Compute the first-char-set of whatever immediately follows `G` in the concat via
   `getSuffixFirstCharSetSkippingNullable(concat, indexAfterG)`.
4. **Disjointness condition 1**: if either charset is `null` (undeterminable) or they intersect,
   return `null` — conservative by construction, same policy as
   `requiresBacktrackingForGroups`'s `nextFirstCharSet == null || intersects(...)` check.
5. If there's separator content between `G`'s close and the `\N` site, compute its charset and
   require it to be disjoint from `G`'s charset too (**disjointness condition 2**, design §3.1
   item 2) — again, `null`/intersecting ⇒ return `null`.
6. On success, return a populated `PinnedBackreferenceInfo`.

**Completion criterion:** Unit test (new `PinnedBackreferenceDetectionTest`, see Task 8) —
`detectPinnedBackreference` returns non-null for `<(\w+)>.*</\1>` and `\b(\w+)\s+\1\b`, and null
for `([bc]*)(c+d)`-shaped ambiguous cases and for `(["'])(?:\\\1|.)*?\1`.

---

## Task 4 — Routing: insert into the backreference decision chain

**File:** `reggie-codegen/…/analysis/PatternAnalyzer.java`, `hasBackrefs` block, lines 757–836.

Insert the new check **between** the `FIXED_REPETITION_BACKREF` check (lines 785–793, guarded by
`fixedRepBackrefInfo.suffix.isEmpty()`, with the B6 fallthrough comment at line 794) and the
`VARIABLE_CAPTURE_BACKREF` check (`detectVariableCaptureBackref`, called at line 798) — per design
§3.2, ahead of `VARIABLE_CAPTURE_BACKREF` since a pinned-boundary pattern is a strict subset of
what that detector currently catches:

```java
// Try to detect a structurally-pinned backreference boundary: the group's content charset
// is disjoint from what follows it, so there is only one candidate boundary — no retry needed.
PinnedBackreferenceInfo pinnedInfo = detectPinnedBackreference(ast);
if (pinnedInfo != null) {
    return new MatchingStrategyResult(
        MatchingStrategy.PINNED_BACKREFERENCE, null, pinnedInfo, false, requiredLiterals);
}
```

Do **not** touch the `SPECIALIZED_BACKREFERENCE` hardcoded-shape detection (`detectSimpleBackreference`,
called later at line ~820) — per design §5/§6, retiring `detectHTMLTagPattern`/
`detectRepeatedWordPattern` is an explicit follow-up decision, not part of this work. Both paths
coexist; `PINNED_BACKREFERENCE` intercepts first only for patterns whose disjointness proof
succeeds, which for now may or may not include the two hardcoded shapes (verified in Task 8).

**Completion criterion:** `./gradlew :reggie-runtime:debugPattern -Ppattern="<(\\w+)>.*</\\1>"`
shows strategy `PINNED_BACKREFERENCE` (or still `SPECIALIZED_BACKREFERENCE` if the equivalence
isn't yet proven — acceptable at this stage per Task 8's ordering) without breaking any existing
routing test.

---

## Task 5 — `FallbackPatternDetector` guard interaction

**File:** `reggie-codegen/…/analysis/FallbackPatternDetector.java`

The three existing danger-condition checks must gate `PINNED_BACKREFERENCE` exactly as they gate
the existing native backref strategies (design §4 — AND, not OR/alternative):

- `hasCrossAlternativeBackref` (detector at line 575; gate `if` at line 160, currently scoped to
  `OPTIMIZED_NFA_WITH_BACKREFS` / `RECURSIVE_DESCENT`)
- `hasAmbiguouslyNullableBackrefGroup` (detector at line 720; gate `if` at line 172, currently
  scoped to `OPTIMIZED_NFA_WITH_BACKREFS` only)
- `hasNullableBackrefInsideCapturingGroup` (detector at line 652; gate `if` at line 194, currently
  scoped to `RECURSIVE_DESCENT` only)

**Change:** add `PINNED_BACKREFERENCE` to the `strategy ==` scoping condition of each of the three
`if` blocks (lines 160, 172, 194 respectively — read the current block first, since each has a
distinct `||`-chain of strategies it already covers). Do not weaken any existing scoping — only
widen the `||` chain to include the new strategy.

**Note:** none of these three conditions should ever actually be reachable for a pattern that
passed `detectPinnedBackreference`'s disjointness proof — the proof and these three danger
conditions test independent properties (design §4: "a pattern can have a disjoint boundary and
still be cross-alternative-ambiguous"). Keep the guards anyway; do not assume the proof implies
their absence.

**Completion criterion:** `CrossAltBackrefTest`, `NullableBackrefTest`, `BackrefDigitAmbiguityTest`
still pass; add one new case per test file confirming a `PINNED_BACKREFERENCE`-eligible pattern
that also trips each danger condition is still routed to fallback, not `PINNED_BACKREFERENCE`.

---

## Task 6 — `PinnedBackreferenceBytecodeGenerator`

**File:** new file, `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/PinnedBackreferenceBytecodeGenerator.java`

**Design:** model directly on `FixedRepetitionBackrefBytecodeGenerator.java` (closest existing
shape — single forward verification loop, no retry) rather than
`VariableCaptureBackrefBytecodeGenerator.java` (which has the backtracking loop this design
eliminates).

**Methods to generate** (same full rich-API set `FixedRepetitionBackrefBytecodeGenerator` emits,
so the strategy classifies as `NATIVE` under `StrategyJdkClassifier`, not `RICH_API_HYBRID`):
- `generateMatchesMethod`
- `generateFindMethod`
- `generateFindFromMethod`
- `generateMatchMethod`
- `generateMatchesBoundedMethod`
- `generateMatchBoundedMethod`
- `generateFindMatchMethod`
- `generateFindMatchFromMethod`

**Codegen shape** (design §3.3):
1. Forward-scan `G`'s content to its charset boundary — single pass, safe because of the
   disjointness proof (loop while next char is in `groupCharSet`, stop at first char that isn't —
   there is only one place that can happen, by construction).
2. If a separator exists, scan it similarly (single pass, no ambiguity by the same proof).
3. Single length-`G.length()` equality check (`regionMatches` or manual char loop, whichever
   `FixedRepetitionBackrefBytecodeGenerator`'s existing `generateCharSetCheck` (line 442) already
   does — reuse that helper rather than reimplementing) against the backreference site.
4. On mismatch:
   - `matches()`/anchored: fail immediately — no other candidate exists.
   - `find()`: advance the *outer* candidate start position using the existing
     `computeFirstByteFilter`/seed machinery (reuse, do not write a new prefilter) — a fresh `G`
     boundary at a different start is a different candidate, not a retry of the same one.

**Explicitly do not touch** `BackreferenceBytecodeGenerator.generateHTMLTagFindFromMethod` (line
459) or `generateRepeatedWordFindFromMethod` (line 898) in this task — those stay on
`SPECIALIZED_BACKREFERENCE` until Task 8's equivalence work is done and a separate decision is
made to retire them (design §5/§6 — explicitly out of scope for this plan).

**Completion criterion:** `PinnedBackreferenceBytecodeGenerator` compiles and generates a working
class for `<(\w+)>content</\1>` when manually routed (temporarily force the routing in Task 4 to
verify before wiring `FallbackPatternDetector`/tests).

---

## Task 7 — Wire generator into `RuntimeCompiler`/`ReggieMatcherBytecodeGenerator` dispatch

**Files:** `reggie-runtime/…/RuntimeCompiler.java` (line 951 switch),
`reggie-processor/…/ReggieMatcherBytecodeGenerator.java` (line 295 switch)

Add the `case PINNED_BACKREFERENCE:` bodies calling every method from Task 6's list, mirroring
exactly how `FIXED_REPETITION_BACKREF`'s case (line 1186 in `RuntimeCompiler`, line 647 in
`ReggieMatcherBytecodeGenerator`) calls its generator's methods.

**Completion criterion:** `RuntimeCompiler.compile("<(\\w+)>.*</\\1>")` (once Task 4's routing
actually selects `PINNED_BACKREFERENCE` for this pattern — see Task 8) returns a non-null matcher
whose `matches()`/`find()` are correct for basic cases.

---

## Task 8 — Correctness: differential tests, equivalence, and regressions

**Existing suites that must keep passing unchanged** (per design §5):
- `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/RepeatedWordPatternTest.java`
- `FixedRepetitionBackrefTest.java`, `FixedRepetitionBackrefMatchResultTest.java`
- `VariableCaptureBackrefTest.java`, `VariableCaptureBackrefMatchResultTest.java`
- `CrossAltBackrefTest.java`, `NullableBackrefTest.java`, `BackrefDigitAmbiguityTest.java`
- `PikeVMRoutingTest.java` lines 244–263 (two tests: `fixedRepetitionBackrefWithSuffix_routesToOptimizedNfaWithBackrefs`
  at 247–254, `fixedRepetitionBackrefNoSuffix_staysOnFixedRepetitionBackref` at 257–263)

**Task 8.1 — B6 companion case.** In `PikeVMRoutingTest.java`, add a case where
`(a)\1{8,}suffix` has a suffix disjoint from `a`'s charset (e.g. `(a)\1{8,}xyz`) and confirm it now
routes to `PINNED_BACKREFERENCE` instead of falling through to `OPTIMIZED_NFA_WITH_BACKREFS`. Keep
the existing non-disjoint-suffix case routing to `OPTIMIZED_NFA_WITH_BACKREFS` unchanged.

**Task 8.2 — New routing/property tests.** Extend `StrategySelectionExtendedTest.java` and
`PatternRoutingPropertyTest.java`/`pbt/PatternRoutingPropertyBasedTest.java` with:
- Positive cases: `<(\w+)>.*</\1>`, `\b(\w+)\s+\1\b`, `(a)\1{8,}xyz` (disjoint suffix) → route to
  `PINNED_BACKREFERENCE`.
- Negative cases (must NOT route to `PINNED_BACKREFERENCE` — false positives are correctness bugs,
  not missed optimizations, per design §5): `([bc]*)(c+d)`-shaped overlap, `(["'])(?:\\\1|.)*?\1`,
  any pattern where `getFirstCharSet`/`getSuffixFirstCharSetSkippingNullable` return `null`
  (undeterminable ⇒ must default to "not pinned").

**Task 8.3 — New dedicated correctness test.** `PinnedBackreferenceTest.java` +
`PinnedBackreferenceMatchResultTest.java` (new files, mirroring the structure of
`FixedRepetitionBackrefTest.java`/`FixedRepetitionBackrefMatchResultTest.java`) — differential vs
JDK for `matches`/`find`/`findAll`/`match`/`findMatch` across the positive-case patterns above,
including boundary cases (backref site immediately at end of string, empty separator, minimum
group length 1).

**Task 8.4 — Equivalence proof for the two hardcoded shapes (design §5, prerequisite for any
future retirement of `detectHTMLTagPattern`/`detectRepeatedWordPattern` — NOT done in this plan).**
Add `PinnedBackreferenceEquivalenceTest.java`: for a corpus of tag-close and repeated-word pattern
variants, assert `detectPinnedBackreference` and `detectHTMLTagPattern`/`detectRepeatedWordPattern`
agree on eligibility, and where both are eligible, generated bytecode produces byte-identical
match/capture results on a shared differential-fuzz input corpus. This test's outcome informs a
*follow-up* decision, out of scope here, about whether to route those two shapes through
`PINNED_BACKREFERENCE` instead of `SPECIALIZED_BACKREFERENCE`.

**Completion criterion:** All of the above green; zero new divergences in
`AlgorithmicFuzzTest.divergenceGate_enforcedViaProperty` (run the fuzzer explicitly after this
task, per repo convention, before considering the feature done).

---

## Task 9 — Metatest, benchmark, and documentation

**Task 9.1 — `StrategyCorrectnessMetaTest`.** Confirm this test (already exercises every
`MatchingStrategy` value against JDK across all 8 `ReggieMatcher` methods, 0-mismatch enforced via
`-Dreggie.metatest.enforce=true`) passes for `PINNED_BACKREFERENCE` once Task 7 wires the full
method set — this is a hard CI gate, not optional.

**Task 9.2 — Benchmark.** Add pattern(s) to whichever JMH benchmark file already benchmarks
`FIXED_REPETITION_BACKREF`/`SPECIALIZED_BACKREFERENCE` (`reggie-benchmark` module), comparing
`PINNED_BACKREFERENCE`'s single-pass codegen against the old `SPECIALIZED_BACKREFERENCE` retry-loop
codegen for `<(\w+)>.*</\1>` and `\b(\w+)\s+\1\b` on adversarial inputs with many false-start `</`
or word-boundary occurrences (the case the retry loop pays for and the pinned strategy shouldn't).

**Task 9.3 — Documentation.** Update `doc/agents-fallback-and-limitations.md` (fallback-condition
table, JDK-dependency classification) and `doc/ARCHITECTURE.md:427` (backreference strategy list)
to add `PINNED_BACKREFERENCE`.

**Completion criterion:** `./gradlew build` green; benchmark numbers recorded showing improvement
on the adversarial-retry inputs; docs updated.

---

## Completion gate

- [ ] `PINNED_BACKREFERENCE` strategy routes for tag-close, repeated-word, and disjoint-suffix
      fixed-repetition backreference patterns
- [ ] Disjointness proof is conservative — zero false positives across property-based routing tests
- [ ] Differential correctness vs JDK — zero divergences across `matches`/`find`/`findAll`/rich API
- [ ] `FallbackPatternDetector`'s three danger-condition guards correctly exclude
      `PINNED_BACKREFERENCE` where applicable
- [ ] `StrategyCorrectnessMetaTest` passes with `PINNED_BACKREFERENCE` included
- [ ] `PikeVMRoutingTest` B6 companion case passes (disjoint-suffix routes to
      `PINNED_BACKREFERENCE`, non-disjoint still falls through to `OPTIMIZED_NFA_WITH_BACKREFS`)
- [ ] Fuzzer run at zero new divergences
- [ ] Benchmark shows improvement over `SPECIALIZED_BACKREFERENCE`'s retry loop on adversarial
      false-start inputs
- [ ] `SPECIALIZED_BACKREFERENCE`'s hardcoded detectors left untouched (retirement is a follow-up
      decision, not this plan — Task 8.4 only proves equivalence, doesn't act on it)
- [ ] `./gradlew spotlessApply` + full test suite green
