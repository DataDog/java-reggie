# JDK Fallback Elimination — Parallel Execution Task List

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every removable `java.util.regex` fallback, organized for maximum parallel agent throughput.

**Architecture:** Each wave contains tasks with no mutual dependencies — dispatch all tasks in a wave simultaneously, then gate on wave completion before starting the next wave. Full task detail lives in `2026-06-11-complete-jdk-fallback-elimination.md`. This document is the execution schedule only.

**Acceptance gate (every task that removes a fallback):** affected patterns (a) compile to non-`JavaRegexFallbackMatcher`, (b) agree with JDK on a representative input set, (c) leave `AlgorithmicFuzzTest.zeroDivergenceGate` at findings=0.

---

## Dependency graph

```
Wave 0 (no deps, pure cleanup / pure independent):
  T0  — dead-code removal (C1/C2/C3)
  T8  — synthetic bytecode splitting (C4)

Wave 1 (no deps, routing / engine spikes):
  T1  — PikeVM nullable/optional/leading-end-anchor fix (A4/A5/B17/B18/B19)
  T4  — lookahead spike  [SPIKE: output is root-cause doc, not code]
  T5  — PikeVM named-group support (A7)
  T6  — backref feasibility spike  [SPIKE: output is FIXABLE-NOW/NEEDS-RND matrix]
  T7  — anchor-in-quantifier spike  [SPIKE: output is route-or-keep decision]

Wave 2 (requires T1 complete):
  T2  — anchor-diluted → PIKEVM routing (A1/A2/A3)
  T3  — TDFA capturing-group-in-quantifier → PIKEVM (B10/B15/B16)

Wave 3 (requires spikes T4/T6/T7 complete, per FIXABLE-NOW classification):
  T4-impl  — lookahead engine fix (B1/B11) — only if spike says FIXABLE-NOW
  T6-impl  — backref sub-cases classified FIXABLE-NOW (subset of A6/B5–B9/B12–B14)
  T7-impl  — anchor-in-quantifier fix/route (B2/B3/B4) — only if spike says fixable

Wave 4 (all previous waves complete):
  T9  — final audit + AGENTS.md documentation
```

---

## Wave 0 — Parallel, no dependencies

Both tasks touch disjoint files and can be dispatched simultaneously.

### W0-T0: Remove dead fallback machinery (C1, C2, C3)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 0

**Files:** `reggie-runtime/.../RuntimeCompiler.java`, possibly `StrategyJdkClassifier.java`

**Removes:** `lookaheadBooleanEngineDefectReason` (:571, always null) and `incompleteMatchResultApiReason` (:560, always null) call sites, their stub methods, the dead hybrid-warning block (:415-424), and `richApiHybridReason` if no remaining callers. `classifyJdkDependency` stays.

- [ ] **Step 1:** Confirm dead stubs — `grep -rn "lookaheadBooleanEngineDefectReason\|incompleteMatchResultApiReason\|richApiHybridReason\|HYBRID_WARNED" reggie-runtime/src reggie-codegen/src`. Note every callsite.

- [ ] **Step 2:** Delete `RuntimeCompiler.java:387-409` (the `lookaheadDefect` and `incompleteApiReason` call-site blocks, including leading comments).

- [ ] **Step 3:** Delete the two stub methods (:556-574). Delete the hybrid-warning block (:411-424). Delete `richApiHybridReason` from `StrategyJdkClassifier` **only if** Step 1 confirmed zero callers. Leave `classifyJdkDependency` and `StrategyJdkClass` intact.

- [ ] **Step 4:** Run + spotless:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -30
  ```

  Expected: `BUILD SUCCESSFUL`, no new failures.

- [ ] **Step 5:** Commit:

  ```bash
  git add reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
         reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/StrategyJdkClassifier.java
  git commit -m "refactor: remove dead always-null fallback hooks"
  ```

---

### W0-T8: Synthetic bytecode method-splitting (C4)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 8

**Files:** `reggie-codegen/.../codegen/DFASwitchBytecodeGenerator.java` (primary), possibly `DFATableBytecodeGenerator.java` / `LiteralAlternationTrieGenerator.java`; `reggie-runtime/.../RuntimeCompiler.java:486` (catch upgrade)

**Removes:** `MethodTooLargeException`→JDK fallback path. Retained catch becomes a should-never-fire bug-signal net.

- [ ] **Step 1:** Characterize the overflow. Locate or construct a pattern that trips `MethodTooLargeException` (e.g. `(kw0|kw1|...|kwN)` with N large). Confirm the overflowing generator is `DFASwitchBytecodeGenerator` (explicit-state). If an unexpected generator overflows, STOP and re-scope before proceeding.

- [ ] **Step 2:** Write the failing runtime test:

  ```java
  // reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LargeAlternationNativeTest.java
  package com.datadoghq.reggie.runtime;
  import static org.junit.jupiter.api.Assertions.*;
  import org.junit.jupiter.api.Test;

  class LargeAlternationNativeTest {
    private static String hugeAlternation(int n) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < n; i++) {
        if (i > 0) sb.append('|');
        sb.append("kw").append(i);
      }
      return "(" + sb + ")";
    }

    @Test
    void hugeAlternationCompilesNativelyAndMatches() {
      String pat = hugeAlternation(2000); // tune n above Step 1 overflow threshold
      var reggie = Reggie.compile(pat);
      assertFalse(reggie instanceof JavaRegexFallbackMatcher,
          "Huge alternation must compile to a split native matcher, not JDK fallback");
      var jdk = java.util.regex.Pattern.compile(pat);
      for (String in : new String[]{"kw0", "kw1999", "kw1000", "nope", ""}) {
        assertEquals(jdk.matcher(in).find(), reggie.matcher(in).find(),
            () -> "mismatch in=" + in);
      }
    }
  }
  ```

  Adapt matcher API to `FallbackDetectorBugFixTest` conventions. Tune `n` from Step 1.

- [ ] **Step 3:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.LargeAlternationNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 4:** Implement bucketed splitting in `DFASwitchBytecodeGenerator.generateStateSwitch` (:232):
  - Choose `STATE_SPLIT_THRESHOLD` targeting each helper ≤ ~48 KB (derive from Step 1 bytes-per-state estimate).
  - Partition states into contiguous buckets when count exceeds threshold.
  - Emit `private int $stepBucketJ(String input, int pos, char ch, int state, int[] groups)` per bucket via `cw.visitMethod`; body is a sub-`tableswitch` using `generateStateCaseCode` with `GOTO loopStart` replaced by `IRETURN nextState`. **Propose the `generateStateCaseCode` signature change (add `boolean asHelper` or similar) as a comment in code before implementing.**
  - Top-level switch routes state → `INVOKESPECIAL $stepBucketJ`; stores returned next state into `stateVar`; `GOTO loopStart`. Use sentinel `-1` for the no-transition case.

- [ ] **Step 5:** Write codegen-level unit test:

  ```java
  // reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/codegen/MethodSplittingTest.java
  // Build a DFA with state count > STATE_SPLIT_THRESHOLD; run the generator; assert:
  // (a) no MethodTooLargeException; (b) generated class contains $stepBucket* methods;
  // (c) compiled matcher agrees with java.util.regex on sample inputs.
  ```

  (full implementation: construct the DFA programmatically, call the generator, load the class, run assertions — mirror the pattern used in existing codegen tests in the same package)

- [ ] **Step 6:** If Step 1 showed `DFATable` or `LiteralAlternationTrie` also overflow, apply the same bucketing. If not, note and leave them.

- [ ] **Step 7:** Upgrade catch at `RuntimeCompiler.java:486` to:

  ```java
  LOG.warning(
      "Reggie method-splitter failed to keep '" + pattern + "' under the JVM 64 KB limit "
          + "(method " + e.getClassName() + "." + e.getMethodName()
          + ", codeSize=" + e.getCodeSize()
          + "); falling back to java.util.regex. This indicates a STATE_SPLIT_THRESHOLD bug.");
  ```

  (adapt to the existing logging field name and format)

- [ ] **Step 8:** Full sweep:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply \
    :reggie-codegen:test --tests "*.MethodSplittingTest" \
    :reggie-runtime:test --tests "*.LargeAlternationNativeTest" 2>&1 | tail -20
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  Expected: both new tests pass; fuzz findings=0; no new failures.

- [ ] **Step 9:** Commit:

  ```bash
  git add -A
  git commit -m "feat: split oversized DFA-switch bytecode; eliminate method-too-large fallback"
  ```

---

## Wave 1 — Parallel, no upstream routing dependencies

All five tasks are independent of each other and of Wave 0. They may be dispatched after Wave 0 completes (or in parallel with Wave 0 if file-conflict risk is acceptable — T0 touches `RuntimeCompiler.java`, no Wave-1 task does).

### W1-T1: PikeVM leftmost-first semantics for nullable/optional/leading-end-anchor alternation

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 1

**Files:** `PikeVMMatcher.java`, `PatternAnalyzer.java` (:1002-1028, :816-857), `FallbackPatternDetector.java` (:246-251)

**Removes:** A4, A5, B17, B18, B19. Deletes `alternationPriorityConflict` flag and its `RuntimeCompiler.java:345-354` construction site.

**Unblocks:** Wave 2 (Tasks T2, T3 assume this PikeVM capability).

- [ ] **Step 1:** Write failing characterization test `PikeVMNullableAlternationTest` (patterns: `a{0,3}|b`, `a|`, `c.{0,3}|b`, `a|$`, `x|y{0,2}`, `(ab|a)|c`; inputs: `""`, `"a"`, `"b"`, `"aaa"`, `"c"`, `"ccc"`, `"cab"`, `"xy"`, `"ab"`). Assert non-fallback + `find()`/`start()`/`end()` agreement with JDK. Adapt matcher API to `FallbackDetectorBugFixTest` conventions.

- [ ] **Step 2:** Run; expect failure (patterns route to `JavaRegexFallbackMatcher`):

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.PikeVMNullableAlternationTest" 2>&1 | tail -30
  ```

- [ ] **Step 3: Root-cause investigation (mandatory before any fix).** Temporarily force patterns to `PIKEVM_CAPTURE` in a scratch change (do NOT commit). Run the test and observe actual divergences. Record: does PikeVM diverge? On which pattern/input? Is it thread-priority ordering, empty-loop guard, or start-position? Write one-paragraph hypothesis in the test Javadoc. **Do not proceed to Step 4 until the root cause is named.**

- [ ] **Step 4:** Implement minimal PikeVM scheduler fix per Step 3's root cause (likely: ensure epsilon-closure adds threads in branch-declaration order; empty-matching branch produces zero-width thread at correct priority). Keep allocation-free — no new per-call allocations in the match loop.

- [ ] **Step 5:** Relax routing exclusions in `PatternAnalyzer.java`:
  - At :1002-1006: remove `!hasNullableAlternationBranch`, `!subtreeContainsOptional`, `!hasEndAnchorLeadingInAlternationBranch` **only for sub-cases Step 3 proved correct**. Keep any sub-case still diverging.
  - At :826-829: remove `!hasNullableAlternationBranch` from the capturing-path PIKEVM safe sub-case correspondingly.
  - At :846-857 and :1022-1028: delete both `alternationPriorityConflict = true` blocks if no patterns remain to reach them.

  In `FallbackPatternDetector.java`: delete the B19 block (:246-251).

- [ ] **Step 6:** Delete the dead construction site in `RuntimeCompiler.java:345-354`. Verify `alternationPriorityConflict` has no remaining writer: `grep -rn "alternationPriorityConflict" reggie-codegen/src reggie-runtime/src`. If write-free, remove the field from `MatchingStrategyResult`.

- [ ] **Step 7:** Run sweeps:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.PikeVMNullableAlternationTest" --tests "*.FallbackDetectorBugFixTest" 2>&1 | tail -20
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  Expected: characterization test passes; fuzz findings=0; no new failures.

- [ ] **Step 8:** spotlessApply + commit:

  ```bash
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
  git add -A
  git commit -m "fix: PikeVM leftmost-first for nullable/optional alternation; remove alternationPriorityConflict fallback"
  ```

---

### W1-T4: Lookahead engine spike — root-cause investigation only (B1, B11)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 4

**Output:** A written root-cause document and fix-or-blocked decision. No production code committed in this task.

**Unblocks:** Wave 3 T4-impl (if FIXABLE-NOW) or documents as blocked-on-safe-backtracking-RnD.

- [ ] **Step 1:** Write failing tests for representative patterns — `(?=a)a+`, `(a(?=b))+` (B1: lookahead in quantifier), `(?=a)b|c`, `((?=x)y|z)` (B11: lookahead in alternation). Assert JDK agreement and non-fallback. Place in `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LookaroundEngineNativeTest.java`.

- [ ] **Step 2:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.LookaroundEngineNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 3: Mandatory spike (per systematic-debugging Phase 1).** Instrument the lookaround NFA scheduler at the branch boundary. For each failing pattern, add evidence-gathering logging to determine: (a) does assertion state leak across NFA threads? (b) does the scheduler evaluate assertions once globally vs. per-thread-clone? (c) is this fixable with bounded per-thread assertion state, or does it require the deferred safe-backtracking R&D (see `project_reggie_safe_backtracking_investigation` memory)?

- [ ] **Step 4:** Write a decision document (inline in the test file Javadoc and as a comment block in `FallbackPatternDetector.java:57-61, :149-156`) classifying each sub-case as `FIXABLE-NOW` or `NEEDS-RND`. If `NEEDS-RND`, document with the specific reason. Do **not** attempt implementation here — that is Wave 3 T4-impl.

- [ ] **Step 5:** Commit the failing tests and decision document only:

  ```bash
  git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LookaroundEngineNativeTest.java \
         reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java
  git commit -m "test/docs: lookahead engine spike — failing tests + root-cause classification"
  ```

---

### W1-T5: PikeVM named-group support for capture-ambiguous TDFA (A7)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 5

**Files:** `PikeVMMatcher.java`, `PatternAnalyzer.java` (:859-904)

**Removes:** A7 (`captureAmbiguous` at :902 for the named-group and anchor sub-cases of the TDFA path).

- [ ] **Step 1:** Write failing tests `PikeVMNamedGroupNativeTest` — capture-ambiguous patterns with named groups (`(?<x>a|ab)\w`, `(?<first>a)(?<second>b|c)`) and with anchors (`^(?<w>\w+)$`). Assert non-fallback + named-group span agreement with JDK (`matcher.group("x")` etc., using the same rich API as `FallbackDetectorBugFixTest`).

- [ ] **Step 2:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.PikeVMNamedGroupNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 3: Investigate.** Split A7 into:
  - Anchor sub-case: relax the `:860` `!hasAnchorInNfa(nfa)` guard and verify PikeVM (post-`0acfc66`) already handles it correctly.
  - Named-group sub-case: determine what PikeVM needs to expose named-group spans — check whether `NameEnrichingMatcher` (used at `RuntimeCompiler:372-375`) can wrap a `PIKEVM_CAPTURE` result, or whether `PikeVMMatcher` needs a `setNameToIndex` call directly. **Propose the API surface before implementing.**

- [ ] **Step 4:** Implement PikeVM named-group support per the API proposal from Step 3. Keep allocation-free.

- [ ] **Step 5:** Relax the `:892-903` fallback in `PatternAnalyzer.java` to route to `PIKEVM_CAPTURE`. Delete `r.captureAmbiguous = true` at :902 **only for the TDFA source** (the backref-path writer at :643 is A6 and belongs to Task 6 — do not touch it here). Verify: `grep -rn "captureAmbiguous" reggie-codegen/src reggie-runtime/src` shows :643 is the sole remaining writer.

- [ ] **Step 6:** Full sweep + fuzz gate + commit:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  ```bash
  git add -A
  git commit -m "fix: PikeVM named-group support; remove TDFA capture-ambiguous fallback"
  ```

---

### W1-T6: Backref engine feasibility spike (A6, B5–B9, B12–B14)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 6

**Output:** A feasibility matrix (FIXABLE-NOW / NEEDS-RND / KEEP-PERMANENT) per sub-case. No production code committed.

**Unblocks:** Wave 3 T6-impl for FIXABLE-NOW sub-cases.

- [ ] **Step 1:** For each sub-case, write a failing test (one test class `BackrefEngineGapsTest` with a `@ParameterizedTest` per case). Cases: A6 (`captureAmbiguous` at :643, NFA bypass ambiguity), B5 (`hasLazyQuantifier` :95), B6 (`hasCrossAlternativeBackref` :104), B7/B8 (`hasNullableBackrefGroup` :114/:122), B9 (`hasNullableBackrefInsideCapturingGroup` :131), B12 (`hasNonAnchorPrefixBeforeBackrefGroup` :163), B13 (`hasOuterQuantifierOnBackrefGroup` :171), B14 (`hasOuterQuantifierOnUnsupportedBackrefGroup` :183). Assert non-fallback + JDK agreement.

- [ ] **Step 2:** Run; expect all to fail:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.BackrefEngineGapsTest" 2>&1 | tail -30
  ```

- [ ] **Step 3: Spike — feasibility assessment.** For each sub-case, analyze: (a) is there a bounded, allocation-free engine fix possible today, or (b) does it require the deferred safe-backtracking R&D? Produce a table in the test Javadoc. Do not write any fix code here.

- [ ] **Step 4:** Commit the failing tests and feasibility table:

  ```bash
  git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java
  git commit -m "test/docs: backref engine gaps spike — failing tests + feasibility matrix"
  ```

---

### W1-T7: Anchor-in-quantifier investigation (B2, B3, B4)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 7

**Files:** `FallbackPatternDetector.java` (:63-82), NFA/DFA anchor simulation (locate via investigation)

**Output:** Route-or-keep decision per sub-case. Wave 3 T7-impl implements the route if proven correct.

- [ ] **Step 1:** Write failing tests `AnchorInQuantifierNativeTest` for `(${0,3})`, `(\b)+`, `\Z[^c]`. Assert non-fallback + JDK agreement.

- [ ] **Step 2:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.AnchorInQuantifierNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 3: Investigate.** Temporarily route each pattern to `PIKEVM_CAPTURE` and compare against JDK. Classify each as: (i) PIKEVM-correct → route in Wave 3; (ii) still diverges → `KEEP-PERMANENT` with the modeling limitation documented in test Javadoc and `FallbackPatternDetector` comment.

- [ ] **Step 4:** Commit the failing tests and route-or-keep decision:

  ```bash
  git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorInQuantifierNativeTest.java \
         reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java
  git commit -m "test/docs: anchor-in-quantifier spike — failing tests + route-or-keep decision"
  ```

---

## Wave 2 — Parallel, requires W1-T1 complete

Both tasks depend on T1's PikeVM nullable/optional support being merged. Dispatch simultaneously after T1 lands.

### W2-T2: Route `anchorConditionDiluted` patterns to PIKEVM (A1, A2, A3)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 2

**Files:** `PatternAnalyzer.java` (:792-804, :1010-1019), `RuntimeCompiler.java` (:337-344, :607-611)

**Removes:** A1, A2, A3. Deletes `anchorConditionDiluted` flag and its construction sites if no writer remains.

- [ ] **Step 1:** Write failing test `AnchorDilutedNativeTest` (patterns: `^c|[^1][b]`, `(^a)?b`, `a|^b`; inputs: `""`, `"c"`, `"b"`, `"ab"`, `"1b"`, `"ba"`, `"\nc"`). Assert non-fallback + `find()` JDK agreement.

- [ ] **Step 2:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.AnchorDilutedNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 3: Investigate per-pattern.** Temporarily route each `anchorConditionDiluted` pattern to PIKEVM. Compare against JDK across the input set. Classify each as: (i) PIKEVM-correct → route; (ii) still diverges → keep on a narrowed dilution fallback with a documented reason. Do not blanket-route.

- [ ] **Step 4:** Add PIKEVM gates:
  - Capturing path (:792-804): before `if (dfa.isAnchorConditionDiluted())`, add a PIKEVM gate for the sub-cases Step 3 proved correct.
  - Non-capturing path (:1013-1019): with T1's PikeVM fix in place, narrow the `if (dfa.isAnchorConditionDiluted())` body to only residual diverging sub-cases from Step 3; delete the block entirely if none remain.

- [ ] **Step 5:** Fix the `compileHybrid` path at `RuntimeCompiler.java:607-611`. If Step 3 found no patterns reaching :609, delete the block. If some remain and PIKEVM handles them, route to PIKEVM here too.

- [ ] **Step 6:** If no writer of `anchorConditionDiluted` remains, delete `RuntimeCompiler.java:337-344` and the `compileHybrid` block (:609-611); remove the field from `MatchingStrategyResult`. Verify: `grep -rn "anchorConditionDiluted" reggie-codegen/src reggie-runtime/src`.

- [ ] **Step 7:** Full sweep + fuzz gate + commit:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  ```bash
  git add -A
  git commit -m "fix: route anchor-diluted patterns to PIKEVM; remove anchorConditionDiluted fallback"
  ```

---

### W2-T3: Route TDFA capturing-group-in-quantifier to PIKEVM (B10, B15, B16)

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 3

**Files:** `PatternAnalyzer.java` (capturing TDFA selection, ~:859-905), `FallbackPatternDetector.java` (:142-147, :207-223)

**Removes:** B10, B15, B16.

- [ ] **Step 1:** Write failing test `TdfaCapturingGroupNativeTest` — three pattern families: `-?(-?.{3}).` (B10 optional prefix), `(a|b){2,}` with capture (B15 capturing group in quantified alternation), `(a)?` / `(a){0,3}` (B16 nullable outer quantifier). Assert non-fallback + group-span agreement with JDK (use the rich `match`/`group` API mirroring `FallbackDetectorBugFixTest`).

- [ ] **Step 2:** Run; expect failure:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*.TdfaCapturingGroupNativeTest" 2>&1 | tail -20
  ```

- [ ] **Step 3: Investigate.** Confirm PIKEVM produces correct per-iteration group spans for each family (it is already trusted for capturing alternation+quantifier). Record any family PIKEVM still mis-spans — keep those on TDFA.

- [ ] **Step 4:** Add PIKEVM gates in the capturing TDFA path (~:859): before the `dfa.isCaptureAmbiguous()` / state-count DFA ladder, add gates routing patterns matching `hasOptionalPrefixBeforeCapturingGroup`, `containsAlternation && hasCapturingGroupInQuantifiedSection`, and `hasNullableOuterQuantifierOnCapturingGroup` to `PIKEVM_CAPTURE` for the families Step 3 proved correct. If `FallbackPatternDetector` predicate methods need wider visibility (package-private → package), make that change and note it.

- [ ] **Step 5:** Delete the now-unreachable predicate blocks from `FallbackPatternDetector.needsFallback` (:142-147, :207-213, :218-223) — only those proven unreachable in Step 3/4.

- [ ] **Step 6:** Full sweep + fuzz gate + commit:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  ```bash
  git add -A
  git commit -m "fix: route TDFA capturing-group-in-quantifier patterns to PIKEVM"
  ```

---

## Wave 3 — Implementation tasks gated on spike results

Run only for sub-cases classified `FIXABLE-NOW` in the respective spikes. Dispatch in parallel after spikes T4/T6/T7 complete.

### W3-T4-impl: Lookahead engine fix (B1, B11) — FIXABLE-NOW sub-cases only

**Gated on:** W1-T4 spike output. Skip entirely if all sub-cases are `NEEDS-RND`.

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 4 Steps 4–6

- [ ] **Step 1:** Implement the NFA scheduler isolation fix identified in the spike (scoped to FIXABLE-NOW sub-cases). Allocation-free in the match loop.

- [ ] **Step 2:** Delete B1/B11 predicate blocks in `FallbackPatternDetector.java` (:57-61, :149-156) only for the fixed sub-cases.

- [ ] **Step 3:** Full sweep + fuzz gate + commit:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

  ```bash
  git add -A
  git commit -m "fix: isolate per-branch lookaround assertions; remove lookahead-in-quantifier/alternation fallback"
  ```

---

### W3-T6-impl: Backref engine fixes — FIXABLE-NOW sub-cases only

**Gated on:** W1-T6 spike output. Each FIXABLE-NOW sub-case is a separate TDD task; they are independent of each other and may be dispatched in parallel.

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 6 Step 2

For each FIXABLE-NOW sub-case: (1) the failing test was already committed in W1-T6; (2) root-cause from the spike is the starting hypothesis; (3) implement the bounded allocation-free fix; (4) delete the corresponding predicate block; (5) sweep + fuzz gate + commit with message `fix: <sub-case> backref; remove <predicate> fallback`.

---

### W3-T7-impl: Anchor-in-quantifier routing fix — route-able sub-cases only

**Gated on:** W1-T7 spike output. Skip entirely if all sub-cases are `KEEP-PERMANENT`.

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 7 Steps 4–5

- [ ] **Step 1:** For each sub-case classified as PIKEVM-correct in the spike, add the routing gate in `PatternAnalyzer.java` (mirror the pattern from T2/T3). Delete the corresponding predicate block in `FallbackPatternDetector.java` (:63-82).

- [ ] **Step 2:** Full sweep + fuzz gate + commit:

  ```bash
  git commit -m "fix/doc: anchor-in-quantifier routing or documented limitation"
  ```

---

## Wave 4 — Final audit (all waves complete)

### W4-T9: Final audit and fallback-status documentation

**Ref:** `2026-06-11-complete-jdk-fallback-elimination.md` Task 9

**Files:** `AGENTS.md`, project memory

- [ ] **Step 1:** Re-audit all construction sites:

  ```
  grep -rn "new JavaRegexFallbackMatcher" reggie-runtime/src reggie-codegen/src
  ```

  Every remaining site must be the C4 should-never-fire net (upgraded warning from W0-T8) or a documented `KEEP-PERMANENT` / `NEEDS-RND` sub-case. Zero active routing fallbacks (A1–A5, A7, B10/B15/B16 gone; B1/B11/B2-B4/B5-B9/B12-B14 per Wave 3 outcomes).

- [ ] **Step 2:** Update `AGENTS.md` with the final inventory: removed fallbacks (Waves 0–3), the method-size should-never-fire net, and R&D-gated backref/anchor cases with specific reasons.

- [ ] **Step 3:** Final sweep + fuzz gate:

  ```
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
  export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
  ```

- [ ] **Step 4:** Commit:

  ```bash
  git add AGENTS.md
  git commit -m "docs: record final JDK fallback status"
  ```

---

## Summary: dispatch order for parallel agents

| Wave | Tasks (dispatch simultaneously) | Gate condition |
|------|----------------------------------|----------------|
| 0 | W0-T0, W0-T8 | None — start immediately |
| 1 | W1-T1, W1-T4, W1-T5, W1-T6, W1-T7 | Wave 0 complete (W0-T0 touches `RuntimeCompiler.java` — confirm no conflict before parallel dispatch; otherwise start Wave 1 after Wave 0 lands) |
| 2 | W2-T2, W2-T3 | **W1-T1 merged** |
| 3 | W3-T4-impl, W3-T6-impl (parallel per sub-case), W3-T7-impl | Respective spike landed AND sub-case classified FIXABLE-NOW |
| 4 | W4-T9 | All Waves 0–3 complete |
