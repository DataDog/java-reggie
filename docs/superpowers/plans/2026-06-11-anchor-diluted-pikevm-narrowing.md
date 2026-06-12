# Narrow the `anchorConditionDiluted` JDK Fallback via PIKEVM_CAPTURE Reorder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route non-optional, non-nullable anchor-diluted alternation patterns (start-anchor in a branch, e.g. `^c|[^1][b]`, `-|\A.{1,}`) through `PIKEVM_CAPTURE` instead of intercepting them at the `dfa.isAnchorConditionDiluted()` early-return that sends them to the JDK fallback. This shrinks the `anchorConditionDiluted` JDK fallback to only the patterns PikeVM cannot yet handle (optional/nullable subtrees, and all capturing-group anchor patterns).

**Architecture:** In `PatternAnalyzer`'s non-capturing DFA path, the `dfa.isAnchorConditionDiluted()` guard currently fires *before* the `PIKEVM_CAPTURE` routing block, so anchor-in-alternation patterns are sent to JDK even though PikeVM (after the committed `PikeVMMatcher.find()` anchor-reference fix, `0acfc66`) now evaluates start-anchors correctly. The fix reorders the `PIKEVM_CAPTURE` block to run *before* the dilution guard. Patterns that pass PikeVM's existing exclusion guards (`!hasNullableAlternationBranch`, `!subtreeContainsOptional`, `!hasEndAnchorLeadingInAlternationBranch`, `dfaHasAcceptingStateWithTransitions`) route to PikeVM; the rest still hit the dilution guard and fall back to JDK exactly as before. No engine changes, no new guard predicates.

**Tech Stack:** Java 21, Gradle, JUnit 5. Oracle: `java.util.regex`. Fuzz gate: `AlgorithmicFuzzTest.zeroDivergenceGate`.

---

## Root Cause (evidence)

A prior attempt (BLOCKED) removed the `dfa.isAnchorConditionDiluted()` early-return outright and pointed those patterns at `OPTIMIZED_NFA`. The zero-divergence fuzz gate immediately reported **6 divergences**, all `first-match span differs` on start-anchor-in-alternation patterns:

```
[a]{0}.c|^c   in=0cc
^_|[_].       in=_a
-|\A.{1,}     in=-0
[_-c]]?|\A.+a? in=b-
^c|[^1][b]    in=cb
^-|.c         in=-c
```

`OPTIMIZED_NFA` has the *same* `find()` anchor defect that `PikeVMMatcher` had before commit `0acfc66`: it evaluates `^`/`\A` as true at non-zero trial-start positions. So routing diluted-anchor patterns to `OPTIMIZED_NFA` is wrong. The `anchorConditionDiluted` → JDK fallback was protecting against a real `OPTIMIZED_NFA` bug — it must not simply be removed.

**The real fix:** these patterns should route to `PIKEVM_CAPTURE`, which *does* handle start-anchors correctly. They currently never reach the `PIKEVM_CAPTURE` block because `dfa.isAnchorConditionDiluted()` (PatternAnalyzer.java:986) short-circuits first.

### Per-pattern routing trace (after reorder)

`subtreeContainsOptional` (PatternAnalyzer.java:1235) returns true for any `QuantifierNode` with `min == 0` (`?`, `*`, `{0,n}`):

| Pattern | passes PikeVM guards? | Routes to (after reorder) |
|---|---|---|
| `^_\|[_].` | yes | **PIKEVM_CAPTURE** |
| `-\|\A.{1,}` | yes (`{1,}` has min=1) | **PIKEVM_CAPTURE** |
| `^c\|[^1][b]` | yes | **PIKEVM_CAPTURE** |
| `^-\|.c` | yes | **PIKEVM_CAPTURE** |
| `[a]{0}.c\|^c` | no (`{0}`) | `isAnchorConditionDiluted` → JDK (unchanged) |
| `[_-c]]?\|\A.+a?` | no (`?`) | `isAnchorConditionDiluted` → JDK (unchanged) |

The four guard-passing patterns are structurally identical to the already-passing `PikeVMAnchorFindTest` cases (`^a|b`, `\Aa|b`): a start-anchor leads one branch, a plain branch is the alternative. High confidence PikeVM matches JDK; the fuzz gate is the backstop.

---

## Scope & non-goals

- **This plan touches only the non-capturing DFA path** (PatternAnalyzer.java ~964–1023). The 6 fuzz patterns are all non-capturing.
- **The capturing TDFA path (lines ~762–838) is OUT of scope.** Its `PIKEVM_CAPTURE` route is gated by `!hasAnchorInNfa(nfa)` (line 827), so anchor-diluted patterns (which by definition contain anchors) can never reach it. Promoting capturing anchor patterns requires master plan **Track 2 Task 6** (drop `!hasAnchorInNfa` after verifying PikeVM capturing-anchor correctness). Leave the capturing-path `isAnchorConditionDiluted` block unchanged.
- **The `anchorConditionDiluted` field and `RuntimeCompiler` guards (lines 337, 609) STAY.** They are still reached by (a) optional/nullable anchor-diluted patterns on the non-capturing path and (b) all capturing-path anchor-diluted patterns. Removal is deferred until master Tasks 4/5/6 close those gaps. This deviates from master Track 1 Task 3 Step 4 — intentionally, with the above justification.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Strategy routing | Move the non-capturing `PIKEVM_CAPTURE` block to immediately *before* the `dfa.isAnchorConditionDiluted()` block |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java` | Routing regression tests | Extend `anchorDilutedResidual` with the 4 guard-passing fuzz patterns; add a native-path assertion |

---

### Task 1: Lock in the routing change with failing-first regression tests

**Files:**
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

The existing `anchorDilutedResidual_agreesWithJdk` test passes trivially today (JDK fallback agrees with JDK). To make the routing change observable, add a test that asserts the four guard-passing patterns use a **native** matcher (not `JavaRegexFallbackMatcher`). This fails before the reorder.

- [ ] **Step 1: Add the four guard-passing fuzz patterns to `anchorDilutedResidual`**

Replace the existing `anchorDilutedResidual()` method body (currently at lines ~449–458) with the version below — it keeps the existing patterns and adds the four start-anchor patterns plus their divergence-trigger inputs:

```java
  static Stream<Arguments> anchorDilutedResidual() {
    return Stream.of(
        // Patterns where dfa.isAnchorConditionDiluted() fires without AST predicates
        Arguments.of("(?:a|b^)", "a"),
        Arguments.of("(?:a|b^)", "b"),
        Arguments.of("a\\Ab", "ab"),
        Arguments.of("a\\Ab", "b"),
        Arguments.of("(a|\\Ab)", "a"),
        Arguments.of("(a|\\Ab)", "b"),
        // Start-anchor-in-alternation patterns now routable to PIKEVM_CAPTURE (fuzz repros)
        Arguments.of("^_|[_].", "_a"),
        Arguments.of("-|\\A.{1,}", "-0"),
        Arguments.of("^c|[^1][b]", "cb"),
        Arguments.of("^-|.c", "-c"));
  }
```

- [ ] **Step 2: Add a native-path assertion for the four guard-passing patterns**

Append this method inside `FallbackDetectorBugFixTest` (after `anchorDilutedResidual_agreesWithJdk`, before the closing brace). It is the failing-first test — these patterns currently compile to `JavaRegexFallbackMatcher`:

```java
  @ParameterizedTest
  @ValueSource(strings = {"^_|[_].", "-|\\A.{1,}", "^c|[^1][b]", "^-|.c"})
  void anchorDilutedStartAnchor_usesNativePath(String pat) throws Exception {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat);
  }
```

> `ValueSource`, `assertFalse`, `JavaRegexFallbackMatcher`, and `Reggie` are already imported in this file (used by the sibling `nonCapturingAltWithAnchor_usesNativePath` test). No new imports needed. Verify before adding; if any import is missing, add it.

- [ ] **Step 3: Run the new test and confirm it FAILS**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-runtime:test --tests '*anchorDilutedStartAnchor_usesNativePath*' -i 2>&1 | tail -30
```

Expected: FAIL — all four patterns currently return `JavaRegexFallbackMatcher` (intercepted by `isAnchorConditionDiluted` before reaching `PIKEVM_CAPTURE`).

> If the test PASSES unexpectedly, STOP — a pattern is already routing natively, which means the routing trace in this plan is wrong for that pattern. Re-investigate before changing routing.

- [ ] **Step 4: Confirm `anchorDilutedResidual_agreesWithJdk` still PASSES (new rows included)**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-runtime:test --tests '*anchorDilutedResidual_agreesWithJdk*' -i 2>&1 | tail -20
```

Expected: PASS (the new patterns currently route to JDK, which agrees with JDK by construction).

- [ ] **Step 5: Commit the failing test**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java && git commit -m "test: failing native-path test for start-anchor diluted alternations"
```

---

### Task 2: Reorder the non-capturing PIKEVM_CAPTURE block above the dilution guard

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (non-capturing path, lines ~986–1014)

- [ ] **Step 1: Move the `PIKEVM_CAPTURE` block before the `isAnchorConditionDiluted` block**

The current source (lines ~986–1014) is:

```java
      if (dfa.isAnchorConditionDiluted()) {
        MatchingStrategyResult r =
            new MatchingStrategyResult(
                MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
        r.anchorConditionDiluted = true;
        return r;
      }

      // Alternation + quantifiers/anchors: PIKEVM_CAPTURE gives correct leftmost-first
      // semantics. Three exclusions guard known PIKEVM divergences:
      //  1. hasNullableAlternationBranch: entire branch can match empty (e.g. a{0,3}|b).
      //  2. subtreeContainsOptional: any {0,n} quantifier anywhere in the pattern, including
      //     inside a non-nullable branch (e.g. c.{0,3}|b — "c" makes the branch non-nullable
      //     but the optional suffix still causes PIKEVM greedy divergence from JDK).
      //  3. hasEndAnchorLeadingInAlternationBranch: an end-anchor ($, \Z, \z) appears in
      //     leading position of an alternation branch (e.g. a|$ or $x|y). PIKEVM's find()
      //     evaluates such anchors during epsilon-closure and can diverge from JDK.
      //  Guards (1) and (2) are both needed; (1) alone misses the non-nullable optional-suffix
      // case.
      //  Start-anchors (^, \A) in leading position are safe; the PikeVMMatcher fix ensures they
      //  evaluate against the fixed search-region origin, not the per-attempt try-position.
      if (containsAlternation(ast)
          && !hasNullableAlternationBranch(ast)
          && !subtreeContainsOptional(ast)
          && !hasEndAnchorLeadingInAlternationBranch(ast)
          && dfaHasAcceptingStateWithTransitions(dfa)) {
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
```

Replace it with the same two blocks in swapped order, with the dilution-block comment updated to note PikeVM now claims the guard-passing subset first:

```java
      // Alternation + quantifiers/anchors: PIKEVM_CAPTURE gives correct leftmost-first
      // semantics. Three exclusions guard known PIKEVM divergences:
      //  1. hasNullableAlternationBranch: entire branch can match empty (e.g. a{0,3}|b).
      //  2. subtreeContainsOptional: any {0,n} quantifier anywhere in the pattern, including
      //     inside a non-nullable branch (e.g. c.{0,3}|b — "c" makes the branch non-nullable
      //     but the optional suffix still causes PIKEVM greedy divergence from JDK).
      //  3. hasEndAnchorLeadingInAlternationBranch: an end-anchor ($, \Z, \z) appears in
      //     leading position of an alternation branch (e.g. a|$ or $x|y). PIKEVM's find()
      //     evaluates such anchors during epsilon-closure and can diverge from JDK.
      //  Guards (1) and (2) are both needed; (1) alone misses the non-nullable optional-suffix
      // case.
      //  Start-anchors (^, \A) in leading position are safe; the PikeVMMatcher fix ensures they
      //  evaluate against the fixed search-region origin, not the per-attempt try-position.
      // This block runs BEFORE the isAnchorConditionDiluted guard below: a diluted-anchor
      // pattern that passes these exclusions (e.g. ^c|[^1][b]) is handled correctly by PIKEVM,
      // whereas OPTIMIZED_NFA (the dilution fallback target) shares the old find() anchor bug.
      if (containsAlternation(ast)
          && !hasNullableAlternationBranch(ast)
          && !subtreeContainsOptional(ast)
          && !hasEndAnchorLeadingInAlternationBranch(ast)
          && dfaHasAcceptingStateWithTransitions(dfa)) {
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
      // Anchor condition diluted in DFA construction and NOT claimed by PIKEVM above (optional or
      // nullable subtree, or leading end-anchor). OPTIMIZED_NFA mishandles find() anchors for
      // these, so fall back to java.util.regex via the anchorConditionDiluted guard.
      if (dfa.isAnchorConditionDiluted()) {
        MatchingStrategyResult r =
            new MatchingStrategyResult(
                MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
        r.anchorConditionDiluted = true;
        return r;
      }
```

> The `hasMisplacedStartAnchorInAlternation` and `hasStringEndAnchorInAlternation` guards immediately above (lines ~975–985) are NOT moved. They require `!dfaHasAcceptingStateWithTransitions(dfa)`, which is mutually exclusive with the `PIKEVM_CAPTURE` block's `dfaHasAcceptingStateWithTransitions(dfa)` requirement, so their behavior is unaffected by placing PIKEVM after them.

- [ ] **Step 2: Run the Task 1 native-path test — must now PASS**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-runtime:test --tests '*anchorDilutedStartAnchor_usesNativePath*' -i 2>&1 | tail -20
```

Expected: PASS (all four patterns now compile to a native PikeVM matcher).

- [ ] **Step 3: Run the zero-divergence fuzz gate — must stay at 0**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*' -i 2>&1 | tail -30
```

Expected: `findings=0`.

> If findings appear, STOP. A guard-passing pattern diverges in PikeVM. Capture the repro and, mirroring the Task 2 (commit `52d947b`) precedent, add a targeted exclusion predicate to the `PIKEVM_CAPTURE` block rather than reverting. Do NOT route the diverging pattern to `OPTIMIZED_NFA`.

- [ ] **Step 4: Run the broader routing test classes for no regression**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest*' --tests '*PikeVMAnchorFindTest*' -i 2>&1 | tail -30
```

Expected: PASS.

- [ ] **Step 5: spotlessApply and commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply 2>&1 | tail -10
export PATH="/usr/local/datadog/bin:$PATH" && git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java && git commit -m "fix: route diluted start-anchor alternations to PIKEVM_CAPTURE before JDK fallback"
```

---

### Task 3: Full regression sweep

**Files:** none (verification only)

- [ ] **Step 1: Run the full runtime module**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-runtime:test -i 2>&1 | tail -40
```

Expected: no new failures beyond the 8 known pre-existing ones (`VariableCaptureBackrefTest` ×3, `VariableCaptureBackrefMatchResultTest` ×4, `NestedQuantifiedGroupsMatchResultTest` ×1).

- [ ] **Step 2: Run codegen + integration modules**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew :reggie-codegen:test :reggie-integration-tests:test -i 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL (or only the known pre-existing failures).

- [ ] **Step 3: Confirm clean working tree (except pre-existing AGENTS.md)**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && git status --short
```

Expected: only `AGENTS.md` (pre-existing) and untracked `docs/superpowers/plans/*.md` remain.

---

## Self-Review

1. **Spec coverage** — Root cause (dilution guard intercepts before PikeVM) → fixed by reorder in Task 2. Failing-first observable test → Task 1. Fuzz gate + suite → Tasks 2/3. The two optional-subtree fuzz patterns (`[a]{0}.c|^c`, `[_-c]]?|\A.+a?`) intentionally remain on JDK fallback (documented in Scope). Covered.
2. **Placeholder scan** — No TBD/TODO; every code step shows the full replacement block; every command shows expected output.
3. **Type/signature consistency** — The reorder moves an existing block verbatim; no signatures change. The new test reuses already-imported symbols (`ValueSource`, `assertFalse`, `JavaRegexFallbackMatcher`, `Reggie`). `subtreeContainsOptional` (min==0) confirmed to exclude `{0}`/`?`/`*` and admit `{1,}`/`+`, matching the routing trace.
4. **Non-goal integrity** — Capturing path and `anchorConditionDiluted` field/guards explicitly preserved; deviation from master Task 3 Step 4 justified by capturing-path `!hasAnchorInNfa` gate and unresolved optional/nullable PikeVM gaps.
