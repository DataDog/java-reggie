# Complete JDK Fallback Elimination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every *removable* `java.util.regex` fallback in the reggie engine by routing affected patterns to a correct native strategy (chiefly `PIKEVM_CAPTURE`) or fixing the underlying engine defect, while honestly documenting the fallbacks that must remain.

**Architecture:** Reggie selects a `MatchingStrategy` in `PatternAnalyzer.analyzeAndRecommend()`; `RuntimeCompiler.compile()` then either generates bytecode or constructs a `JavaRegexFallbackMatcher`. There are exactly **eight** `new JavaRegexFallbackMatcher(...)` construction sites in `RuntimeCompiler`, driven by **three result flags** (`anchorConditionDiluted`, `alternationPriorityConflict`, `captureAmbiguous`), **one detector** (`FallbackPatternDetector.needsFallback`, ~19 predicate conditions), **two always-null stub hooks**, and **one JVM-limit catch**. This plan groups the removable conditions into *capability investments* — each investment unlocks a cluster of related removals — rather than chasing 19 disconnected predicates. After each flag/predicate stops firing, its construction site is provably dead and gets deleted in the same task.

**Tech Stack:** Java, JUnit 5, jqwik (property tests), Gradle (`./gradlew :reggie-runtime:test`, `:reggie-codegen:test`). Fuzz gate: `AlgorithmicFuzzTest.zeroDivergenceGate` (must stay findings=0).

---

## Complete Fallback Inventory (verified against current code, 2026-06-11)

### A. Result-flag fallbacks (`RuntimeCompiler.compile`)

| # | Construction site | Driving flag | Flag set at | Strategy carried | Removal class |
|---|---|---|---|---|---|
| A1 | `RuntimeCompiler.java:339` | `anchorConditionDiluted` | `PatternAnalyzer.java:802` (capturing TDFA path) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 2) |
| A2 | `RuntimeCompiler.java:339` | `anchorConditionDiluted` | `PatternAnalyzer.java:1017` (non-capturing residual) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 2) |
| A3 | `RuntimeCompiler.java:610` | `anchorConditionDiluted` | `compileHybrid` reads `dfaResult` | `OPTIMIZED_NFA` (hybrid) | Route → PIKEVM (Phase 2) |
| A4 | `RuntimeCompiler.java:347` | `alternationPriorityConflict` | `PatternAnalyzer.java:855` (capturing TDFA path) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 1) |
| A5 | `RuntimeCompiler.java:347` | `alternationPriorityConflict` | `PatternAnalyzer.java:1026` (non-capturing residual) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 1) |
| A6 | `RuntimeCompiler.java:357` | `captureAmbiguous` | `PatternAnalyzer.java:643` (backref NFA bypass ambiguity) | `OPTIMIZED_NFA` | Engine work (Phase 6) |
| A7 | `RuntimeCompiler.java:357` | `captureAmbiguous` | `PatternAnalyzer.java:902` (TDFA, named groups / anchors) | `OPTIMIZED_NFA` | PikeVM named-group support (Phase 5) |

### B. `FallbackPatternDetector.needsFallback` predicate fallbacks (`RuntimeCompiler.java:381`)

| # | Predicate (line in detector) | Gated strategy(ies) | Removal class |
|---|---|---|---|
| B1 | `v.lookaheadInQuantifier` (:59) | all (issue #28) | Lookahead engine (Phase 4) |
| B2 | `hasAnchorInQuantifierInCapturingGroup` (:66) | all | Anchor-in-quantifier (Phase 7) |
| B3 | `hasAnchorInQuantifier` (:73) | all | Anchor-in-quantifier (Phase 7) |
| B4 | `hasEndAnchorBeforeNonNewlineConsumer` (:80) | all | Anchor-in-quantifier (Phase 7) |
| B5 | `hasLazyQuantifier` (:95) | `RECURSIVE_DESCENT`, `OPTIMIZED_NFA_WITH_BACKREFS` | Engine work (Phase 6) |
| B6 | `hasCrossAlternativeBackref` (:104) | `OPTIMIZED_NFA_WITH_BACKREFS`, `RECURSIVE_DESCENT` | Engine work (Phase 6) |
| B7 | `hasNullableBackrefGroup` (:114) | `OPTIMIZED_NFA_WITH_BACKREFS` | Engine work (Phase 6) |
| B8 | `hasNullableBackrefGroup` (:122) | `FIXED_REPETITION_BACKREF` | Engine work (Phase 6) |
| B9 | `hasNullableBackrefInsideCapturingGroup` (:131) | `RECURSIVE_DESCENT` | Engine work (Phase 6) |
| B10 | `hasOptionalPrefixBeforeCapturingGroup` (:142) | `DFA_*_WITH_GROUPS` | TDFA→PIKEVM routing (Phase 3) |
| B11 | `hasLookaheadInAlternation` (:152) | `OPTIMIZED_NFA_WITH_LOOKAROUND` | Lookahead engine (Phase 4) |
| B12 | `hasNonAnchorPrefixBeforeBackrefGroup` (:163) | `VARIABLE_CAPTURE_BACKREF` | Engine work (Phase 6) |
| B13 | `hasOuterQuantifierOnBackrefGroup` (:171) | `VARIABLE_CAPTURE_BACKREF` | Engine work (Phase 6) |
| B14 | `hasOuterQuantifierOnUnsupportedBackrefGroup` (:183) | `OPTIONAL_GROUP_BACKREF` | Engine work (Phase 6) |
| B15 | `hasCapturingGroupInQuantifiedSection` (:207) | `DFA_*_WITH_GROUPS` | TDFA→PIKEVM routing (Phase 3) |
| B16 | `hasNullableOuterQuantifierOnCapturingGroup` (:218) | `DFA_*_WITH_GROUPS` | TDFA→PIKEVM routing (Phase 3) |
| B17 | `hasStringEndAnchorInAltWithProblematicContext` (:228) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 1) |
| B18 | `hasStartClassAnchorInAlternationBranch` (:236) | `OPTIMIZED_NFA` | Route → PIKEVM (Phase 1) |
| B19 | `hasNullableAlternationBranchAnywhere` (:246) | `OPTIMIZED_NFA`, `PIKEVM_CAPTURE` | PikeVM nullable semantics (Phase 1) |

### C. Inactive / permanent (NOT removable by routing)

| # | Site | State | Disposition |
|---|---|---|---|
| C1 | `lookaheadBooleanEngineDefectReason` (`RuntimeCompiler.java:571`) | always `return null` | Delete dead hook (Phase 0) |
| C2 | `incompleteMatchResultApiReason` (`RuntimeCompiler.java:560`) | always `return null` | Delete dead hook (Phase 0) |
| C3 | hybrid-warning block (`RuntimeCompiler.java:415`) + `StrategyJdkClassifier.richApiHybridReason` | always null (no strategy is `RICH_API_HYBRID`) | Document as dead; do NOT delete classifier (its `classifyJdkDependency` is live at :463) (Phase 0) |
| C4 | `MethodTooLargeException` catch (`RuntimeCompiler.java:486`) | fires on >64 KB generated methods | **Removable via synthetic bytecode splitting** (Task 8); catch retained as should-never-fire net |

---

## Capability-investment ordering (why this sequence)

The single highest-value lever is **PikeVM leftmost-first semantics for nullable/optional alternation branches and leading end-anchors**. Today every routing site that *could* use PikeVM is blocked by the same three exclusions — `hasNullableAlternationBranch`, `subtreeContainsOptional`, `hasEndAnchorLeadingInAlternationBranch` (`PatternAnalyzer.java:1003-1005`) and the mirror `hasNullableAlternationBranchAnywhere` predicate (B19). Fixing PikeVM once (Phase 1) directly removes A4, A5, B17, B18, B19, and unblocks Phase 2 (anchor-diluted) and Phase 3 (TDFA routing). The backref/lookahead engine work (Phases 4–6) is genuinely harder and is sequenced last; some of it depends on the deferred "safe backtracking" R&D and may not fully close.

Phases are independent enough for subagent-driven execution **in order** (Phase N+1 assumes Phase N's routing exists). Within a phase, tasks are TDD-ordered. Task 8 (synthetic bytecode splitting) is fully independent of the routing/engine work and can run at any point — it eliminates the `MethodTooLargeException` fallback (C4) rather than reduce its frequency, since reggie emits its own bytecode.

**Universal acceptance gate for every task that removes a fallback:** the affected patterns must (a) compile to a non-`JavaRegexFallbackMatcher`, (b) agree with `java.util.regex` on a representative input set, and (c) leave `AlgorithmicFuzzTest.zeroDivergenceGate` at findings=0. The test convention is established in `FallbackDetectorBugFixTest`: `assertFalse(Reggie.compile(pat) instanceof JavaRegexFallbackMatcher, ...)` plus a JDK cross-check.

---

### Task 0: Remove dead fallback machinery (C1, C2, C3)

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java`

**Context:** `lookaheadBooleanEngineDefectReason` (:571) and `incompleteMatchResultApiReason` (:560) both unconditionally `return null`, so the two call sites at :391-398 and :402-409 can never construct a fallback. The hybrid-warning block at :415-424 depends on `richApiHybridReason`, which is null for every strategy. Removing the two stubs and their call sites eliminates dead branches that obscure the real fallback surface. The classifier method `classifyJdkDependency` stays — it is live at :463 (`nativeRichApi`).

- [ ] **Step 1: Add a regression test asserting the stubs are gone (compile-guard)**

This is a refactor of dead code; the safety net is the existing suite. Skip a new unit test (there is no behavior to assert — the branches never executed). Instead, verify by running the full runtime suite in Step 4.

- [ ] **Step 2: Delete the two always-null call sites**

In `RuntimeCompiler.compile()`, delete lines 387–409 (the `lookaheadDefect` block and the `incompleteApiReason` block, including their leading comments). The `FallbackPatternDetector.needsFallback` block (:379-386) immediately above stays; the hybrid-warning block (:411-424) is handled in Step 3.

- [ ] **Step 3: Delete the two stub methods and the dead hybrid-warning block**

Delete `incompleteMatchResultApiReason` (:556-562) and `lookaheadBooleanEngineDefectReason` (:564-574). Delete the hybrid-warning block at :411-424 and the now-unused `HYBRID_WARNED` field and `StrategyJdkClassifier.richApiHybridReason` import/usage **only if** no other caller references them — verify with `grep -rn "richApiHybridReason\|HYBRID_WARNED" reggie-runtime/src reggie-codegen/src` first. If `richApiHybridReason` has no remaining caller, delete it from `StrategyJdkClassifier` too. Leave `classifyJdkDependency` and the `StrategyJdkClass` enum intact.

- [ ] **Step 4: Run the runtime suite + spotless**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. Pre-existing known failures only; zero new failures.

- [ ] **Step 5: Commit**

```bash
git add reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/StrategyJdkClassifier.java
git commit -m "refactor: remove dead always-null fallback hooks"
```

---

### Task 1: PikeVM leftmost-first semantics for nullable/optional/leading-end-anchor alternation (removes A4, A5, B17, B18, B19)

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (:1002-1028, :816-857)
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` (:246-251)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMNullableAlternationTest.java` (new)

**Context:** PikeVM is currently excluded from nullable/optional/leading-end-anchor alternation patterns at three coordinated points: `PatternAnalyzer.java:1003-1005` (`!hasNullableAlternationBranch && !subtreeContainsOptional && !hasEndAnchorLeadingInAlternationBranch`), the capturing-path PIKEVM safe sub-case at :826-829 (`!hasNullableAlternationBranch`), and the `hasNullableAlternationBranchAnywhere` predicate B19 at FallbackPatternDetector:246-251. The exclusion exists because PikeVM's thread scheduler was suspected to diverge from JDK's leftmost-first semantics when a branch can match empty. This task first *characterizes* the actual divergence (systematic-debugging Phase 1) before changing the scheduler.

Representative patterns (from the in-code comments): `a{0,3}|b`, `a|` (empty trailing branch), `c.{0,3}|b` (non-nullable branch with optional suffix), `a|$` (leading end-anchor branch), `(c{2}\Z)|[b]`.

- [ ] **Step 1: Write the failing characterization test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

class PikeVMNullableAlternationTest {

  private static final List<String> PATTERNS =
      List.of("a{0,3}|b", "a|", "c.{0,3}|b", "a|$", "x|y{0,2}", "(ab|a)|c");
  private static final List<String> INPUTS =
      List.of("", "a", "b", "aaa", "c", "ccc", "cab", "xy", "ab");

  @Test
  void nullableAlternationAgreesWithJdkAndStaysNative() {
    for (String pat : PATTERNS) {
      var reggie = Reggie.compile(pat);
      assertFalse(
          reggie instanceof JavaRegexFallbackMatcher,
          () -> "Expected native matcher for nullable-alternation pattern: " + pat);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
      for (String in : INPUTS) {
        Matcher jm = jdk.matcher(in);
        boolean jdkFind = jm.find();
        var rm = reggie.matcher(in); // adapt to actual ReggieMatcher find API
        assertEquals(
            jdkFind, rm.find(), () -> "find() mismatch pat=" + pat + " in=" + in);
        if (jdkFind) {
          assertEquals(jm.start(), rm.start(), () -> "start mismatch pat=" + pat + " in=" + in);
          assertEquals(jm.end(), rm.end(), () -> "end mismatch pat=" + pat + " in=" + in);
        }
      }
    }
  }
}
```

> Adapt the `reggie.matcher(in)/find()/start()/end()` calls to the actual `ReggieMatcher` API used in `FallbackDetectorBugFixTest` (mirror its exact call shape). Do not invent methods.

- [ ] **Step 2: Run it; expect failure**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.PikeVMNullableAlternationTest" 2>&1 | tail -30
```

Expected: FAIL — patterns currently route to `JavaRegexFallbackMatcher` (the `assertFalse` fails).

- [ ] **Step 3: Investigate PikeVM divergence (systematic-debugging Phase 1–3)**

Before touching the scheduler, temporarily force these patterns to `PIKEVM_CAPTURE` in a scratch branch and run the characterization test to observe *actual* divergences (not assumed ones). Record: does PikeVM diverge at all? On which pattern/input? Is it a thread-priority ordering issue (greedy vs leftmost-first), an empty-loop non-termination guard, or a start-position issue? Write the finding as a one-paragraph hypothesis in the test file's Javadoc. **Do not proceed to Step 4 until the root cause is named.**

- [ ] **Step 4: Implement the PikeVM fix (scoped to the root cause from Step 3)**

Apply the minimal scheduler/closure fix identified in Step 3. The likely shape (confirm against the finding): ensure epsilon-closure adds threads in branch-declaration order so the first alternative wins ties, and that an empty-matching branch produces a zero-width thread at the correct priority. Keep allocation-free (no new per-call allocations in the match loop).

- [ ] **Step 5: Relax the routing exclusions**

In `PatternAnalyzer.java`:
- At :1002-1006, remove `!hasNullableAlternationBranch(ast)`, `!subtreeContainsOptional(ast)`, and `!hasEndAnchorLeadingInAlternationBranch(ast)` from the PIKEVM gate **only for the conditions Step 3 proved PikeVM now handles**. If Step 3 found PikeVM still diverges on a sub-case (e.g. leading end-anchor), keep that one exclusion and note it.
- At :826-829, remove `!hasNullableAlternationBranch(ast)` from the capturing PIKEVM safe sub-case correspondingly.
- The residual `alternationPriorityConflict` blocks at :846-857 and :1022-1028 now have no patterns reaching them (all alternation+accepting-transition patterns are claimed by the PIKEVM gate above). Delete both blocks and the `r.alternationPriorityConflict = true` lines.

In `FallbackPatternDetector.java`, delete the B19 block (:246-251).

- [ ] **Step 6: Delete the now-dead `alternationPriorityConflict` construction site**

In `RuntimeCompiler.java`, delete the `if (result.alternationPriorityConflict)` block (:345-354). Grep to confirm `alternationPriorityConflict` has no remaining writer: `grep -rn "alternationPriorityConflict" reggie-codegen/src reggie-runtime/src`. If the field is now write-free, remove it from `MatchingStrategyResult`.

- [ ] **Step 7: Run characterization test + full sweep + fuzz gate**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.PikeVMNullableAlternationTest" --tests "com.datadoghq.reggie.runtime.FallbackDetectorBugFixTest" 2>&1 | tail -20
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
```

Expected: characterization test passes; fuzz gate findings=0; no new failures.

- [ ] **Step 8: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply
git add -A
git commit -m "fix: PikeVM leftmost-first for nullable/optional alternation; remove alternationPriorityConflict fallback"
```

---

### Task 2: Route `anchorConditionDiluted` patterns to PIKEVM (removes A1, A2, A3)

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (:792-804, :1010-1019)
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` (:337-344, :607-611)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorDilutedNativeTest.java` (new)

**Context:** `anchorConditionDiluted` is set when `dfa.isAnchorConditionDiluted()` is true and the pattern was not claimed by an earlier guard. The 2026-06-11-anchor-diluted-pikevm-narrowing plan already reordered the PIKEVM gate *before* the dilution guard for non-capturing alternation patterns (`PatternAnalyzer.java:1002-1009` precedes :1013). This task extends that to the cases still falling through: (a) the capturing TDFA path at :792-804, (b) the residual non-capturing path at :1013-1019 for optional/nullable patterns now handled by Task 1's PikeVM fix, and (c) the `compileHybrid` path at :609. The OPTIMIZED_NFA dilution fallback target shares the old find()-anchor bug; PIKEVM (post-`0acfc66` anchor fix + Task 1) does not.

Representative patterns: `^c|[^1][b]` (already native), plus optional/nullable diluted forms the narrowing plan deferred (e.g. `(^a)?b`, anchor-diluted patterns with optional prefixes).

- [ ] **Step 1: Write the failing test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnchorDilutedNativeTest {
  // Patterns whose DFA construction dilutes an anchor condition but which PIKEVM matches correctly.
  private static final List<String> PATTERNS = List.of("^c|[^1][b]", "(^a)?b", "a|^b");
  private static final List<String> INPUTS = List.of("", "c", "b", "ab", "1b", "ba", "\nc");

  @Test
  void anchorDilutedStaysNativeAndAgreesWithJdk() {
    for (String pat : PATTERNS) {
      var reggie = Reggie.compile(pat);
      assertFalse(
          reggie instanceof JavaRegexFallbackMatcher,
          () -> "Expected native matcher for anchor-diluted pattern: " + pat);
      var jdk = java.util.regex.Pattern.compile(pat);
      for (String in : INPUTS) {
        var jm = jdk.matcher(in);
        boolean jf = jm.find();
        var rm = reggie.matcher(in); // adapt to actual API
        assertEquals(jf, rm.find(), () -> "find mismatch pat=" + pat + " in=" + in);
      }
    }
  }
}
```

> Replace the example pattern set after Step 3 confirms which diluted patterns PikeVM actually handles; some may still require OPTIMIZED_NFA or stay on JDK. Adapt the matcher API to `FallbackDetectorBugFixTest` conventions.

- [ ] **Step 2: Run it; expect failure**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.AnchorDilutedNativeTest" 2>&1 | tail -20
```

Expected: FAIL (patterns route to fallback).

- [ ] **Step 3: Investigate per-pattern (systematic-debugging)**

For each `anchorConditionDiluted` pattern, temporarily route to PIKEVM and compare against JDK across the input set. Classify each into: (i) PIKEVM-correct → route, (ii) still diverges → keep on a *narrowed* dilution fallback with a documented reason. Record findings in the test Javadoc. Do not blanket-route.

- [ ] **Step 4: Add the PIKEVM gate before each dilution guard**

In `PatternAnalyzer.java`:
- Capturing path (:792-804): before `if (dfa.isAnchorConditionDiluted())`, add a PIKEVM gate mirroring the non-capturing one at :1002-1006 for the sub-cases Step 3 proved correct.
- Non-capturing path (:1013-1019): with Task 1's PikeVM fix in place, the patterns previously excluded by `subtreeContainsOptional`/`hasNullableAlternationBranch` now reach the PIKEVM gate at :1002. Narrow the `if (dfa.isAnchorConditionDiluted())` body to only the residual diverging sub-cases from Step 3; if none remain, delete the block.

- [ ] **Step 5: Fix the `compileHybrid` path**

In `RuntimeCompiler.java:607-611`, the hybrid path falls back when `dfaResult.anchorConditionDiluted`. Since the main path (:337) now routes most diluted patterns to PIKEVM before hybrid is ever chosen (`shouldUseHybrid` at :580 only triggers for `OPTIMIZED_NFA`/`usePosixLastMatch`), confirm via Step 3 findings whether any pattern still reaches :609. If none do, delete the block; if some do and PIKEVM handles them, route to PIKEVM here too.

- [ ] **Step 6: Delete the dead `anchorConditionDiluted` construction site**

If Steps 4–5 leave no writer of `anchorConditionDiluted`, delete `RuntimeCompiler.java:337-344` and the `compileHybrid` block (:609-611), and remove the field from `MatchingStrategyResult`. Verify: `grep -rn "anchorConditionDiluted" reggie-codegen/src reggie-runtime/src`.

- [ ] **Step 7: Full sweep + fuzz gate**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
```

Expected: all green; fuzz findings=0.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "fix: route anchor-diluted patterns to PIKEVM; remove anchorConditionDiluted fallback"
```

---

### Task 3: Route capturing-group-in-quantifier TDFA patterns to PIKEVM (removes B10, B15, B16)

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (capturing TDFA selection, ~:859-905)
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` (:142-147, :207-223)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/TdfaCapturingGroupNativeTest.java` (new)

**Context:** B10/B15/B16 fall back when a `DFA_*_WITH_GROUPS` strategy is selected but the pattern has an optional prefix before a capturing group, a capturing group inside a quantifier with alternation, or a nullable outer quantifier on a capturing group — all cases the TDFA cannot span correctly. `PatternAnalyzer.java:1030-1034` already routes some `hasCapturingGroupInQuantifiedSection` patterns to PIKEVM in the *non-capturing* path. This task makes the *capturing* path prefer PIKEVM over `DFA_*_WITH_GROUPS` for these three predicate conditions, so `needsFallback` never sees them.

Representative patterns: `-?(-?.{3}).` (B10), `(a|b){2,}` with capture (B15), `(a)?` / `(a){0,3}` style nullable outer quantifier (B16).

- [ ] **Step 1: Write the failing test** — mirror Task 2's structure with the three pattern families above; assert non-fallback + JDK agreement on group spans (use the rich `match`/group API as in `FallbackDetectorBugFixTest`).

- [ ] **Step 2: Run; expect failure** (`--tests "*TdfaCapturingGroupNativeTest"`).

- [ ] **Step 3: Investigate** — confirm PIKEVM produces correct per-iteration group spans for each family (it is the strategy already trusted for capturing alternation+quantifier per `PatternRoutingPropertyTest`). Record any family PIKEVM still mis-spans.

- [ ] **Step 4: Add PIKEVM gates in the capturing TDFA path** — before the `dfa.isCaptureAmbiguous()` / state-count DFA ladder (~:859), add gates that route patterns matching `hasOptionalPrefixBeforeCapturingGroup`, `containsAlternation && hasCapturingGroupInQuantifiedSection`, and `hasNullableOuterQuantifierOnCapturingGroup` to `PIKEVM_CAPTURE` (for the families Step 3 proved correct). Reuse the existing `FallbackPatternDetector` predicate methods (make them package-visible if needed — propose this helper-visibility change before implementing).

- [ ] **Step 5: Delete the now-unreachable predicate blocks** in `FallbackPatternDetector.needsFallback` (:142-147, :207-213, :218-223) — but only those proven unreachable in Step 3/4. Keep any family still routed to TDFA.

- [ ] **Step 6: Full sweep + fuzz gate + commit** (same command shape as Task 2 Step 7–8).

```bash
git commit -m "fix: route TDFA capturing-group-in-quantifier patterns to PIKEVM"
```

---

### Task 4: Lookahead-in-quantifier and lookahead-in-alternation engine fix (removes B1, B11)

**Files:**
- Modify: `reggie-runtime`/`reggie-codegen` lookaround NFA simulation (identify exact files via `grep -rn "OPTIMIZED_NFA_WITH_LOOKAROUND" reggie-codegen/src/main`)
- Modify: `FallbackPatternDetector.java` (:57-61, :149-156)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LookaroundEngineNativeTest.java` (new)

**Context:** B1 (issue #28) and B11 are genuine engine defects, not routing gaps: the NFA thread scheduler does not isolate assertion evaluation per alternation branch (B11) and produces wrong results for assertions across loop iterations (B1). This is **engine work**, not a reroute — it connects to the deferred group-start-recording-bug effort for `OPTIMIZED_NFA_WITH_LOOKAROUND`.

- [ ] **Step 1: Write failing tests** for representative patterns: `(?=a)a+` / `(a(?=b))+` (B1), `(?=a)b|c` / `((?=x)y|z)` (B11). Assert JDK agreement and non-fallback.

- [ ] **Step 2: Run; expect failure.**

- [ ] **Step 3: Root-cause investigation (systematic-debugging, mandatory).** Instrument the lookaround NFA scheduler at the branch boundary (per the skill's multi-component evidence-gathering). Identify whether per-branch assertion state leaks across threads. **This is a spike: its deliverable is a written root-cause + fix design, reviewed before implementation.** If the fix requires the deferred safe-backtracking R&D, STOP and document B1/B11 as "blocked on safe-backtracking R&D" rather than forcing a fix.

- [ ] **Step 4: Implement the scheduler isolation fix** (scoped to Step 3's root cause). Allocation-free in the match loop.

- [ ] **Step 5: Delete B1/B11 predicate blocks** only for the cases the fix proves correct; narrow the predicates otherwise.

- [ ] **Step 6: Full sweep + fuzz gate + commit.**

```bash
git commit -m "fix: isolate per-branch lookaround assertions; remove lookahead-in-quantifier/alternation fallback"
```

---

### Task 5: PikeVM named-group + anchor support for capture-ambiguous TDFA (removes A7)

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java` (named-group span support)
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (:859-904)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMNamedGroupNativeTest.java` (new)

**Context:** A7 (`captureAmbiguous` at `PatternAnalyzer.java:902`) fires only when `dfa.isCaptureAmbiguous()` AND (`hasNamedGroups(ast)` OR `hasAnchorInNfa(nfa)`) — the `:860` comment states "PikeVMMatcher doesn't handle these yet." The anchor sub-case may already be covered by the `0acfc66` PikeVM anchor fix; the named-group sub-case needs PikeVM to expose named-group spans. Once PikeVM handles both, the `:892-903` fallback branch routes to `PIKEVM_CAPTURE` instead of `OPTIMIZED_NFA + captureAmbiguous`.

- [ ] **Step 1: Write failing tests** — capture-ambiguous patterns with named groups (`(?<x>a|ab)\w`) and with anchors; assert non-fallback + named-group span agreement with JDK.

- [ ] **Step 2: Run; expect failure.**

- [ ] **Step 3: Investigate** — split A7 into the anchor sub-case (likely already PikeVM-correct post-`0acfc66`) and the named-group sub-case. For the anchor sub-case, simply relax the `:860` `!hasAnchorInNfa(nfa)` guard and verify. For named groups, determine what PikeVM needs (name→index map propagation through `NameEnrichingMatcher`, already used at `RuntimeCompiler:372-375`).

- [ ] **Step 4: Implement PikeVM named-group support** (propose the API surface before implementing — likely reuse `setNameToIndex` + `NameEnrichingMatcher`).

- [ ] **Step 5: Relax the `:892-903` fallback** to route to `PIKEVM_CAPTURE`; delete `r.captureAmbiguous = true` at :902 if no writer remains *for the TDFA source* (A6 at :643 is separate — see Task 6).

- [ ] **Step 6: Full sweep + fuzz gate + commit.**

```bash
git commit -m "fix: PikeVM named-group support; remove TDFA capture-ambiguous fallback"
```

---

### Task 6: Backref engine gaps (removes A6, B5–B9, B12–B14) — staged, R&D-dependent

**Files:**
- Modify: backref strategy generators/engines (`OPTIMIZED_NFA_WITH_BACKREFS`, `FIXED_REPETITION_BACKREF`, `VARIABLE_CAPTURE_BACKREF`, `OPTIONAL_GROUP_BACKREF`, `RECURSIVE_DESCENT`) — locate via `grep`
- Modify: `FallbackPatternDetector.java` (:95-99, :104-108, :114-117, :122-125, :131-135, :163-167, :171-175, :183-187) and `PatternAnalyzer.java:643`
- Test: per-sub-case new tests

**Context:** This cluster is the genuinely hard one and is **explicitly R&D-dependent** (see `project_reggie_safe_backtracking_investigation` memory). Each predicate guards a real engine limitation — lazy quantifier shortest-match (B5), cross-alternative backref state contamination (B6), nullable-group capture spans (B7/B8/B9), unsupported prefix/outer-quantifier on backref groups (B12/B13/B14), and NFA capture ambiguity from bypass paths (A6). Do **not** attempt these as routing reroutes — there is no existing native strategy that handles them correctly. Each is its own mini-project gated on the safe-backtracking investigation.

- [ ] **Step 1: Spike — feasibility matrix.** For each of A6, B5–B9, B12–B14, write a one-paragraph assessment: (a) is there a bounded, allocation-free engine fix, or (b) does it require the deferred safe-backtracking R&D? Produce a table classifying each as `FIXABLE-NOW` / `NEEDS-RND` / `KEEP-PERMANENT`. **This spike's output is a decision document, not code.** Review it before committing to any implementation.

- [ ] **Step 2: Implement only the `FIXABLE-NOW` sub-cases**, each as a separate TDD task (failing test → root-cause → fix → delete the corresponding predicate block → sweep → commit). Sequence them independently.

- [ ] **Step 3: Document `NEEDS-RND` / `KEEP-PERMANENT` sub-cases** in this plan and in the project memory, with the specific reason each cannot be removed without the R&D. Do not delete their predicate blocks.

> No blanket commit — each fixable sub-case commits independently with message `fix: <sub-case> backref; remove <predicate> fallback`.

---

### Task 7: Anchor-inside-quantifier (B2, B3, B4) — investigate then fix-or-keep

**Files:**
- Modify: `FallbackPatternDetector.java` (:63-82)
- Modify: NFA/DFA anchor simulation (locate via investigation)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorInQuantifierNativeTest.java` (new)

**Context:** B2/B3/B4 fall back for zero-width anchors repeated by a quantifier (`(${0,3})`, `\Z[^c]`). The in-code comment states these "produce wrong match positions in all DFA/NFA strategies." Whether this is fixable depends on whether the strategies can model a repeated zero-width assertion. PikeVM may handle these (it models epsilon transitions per position); investigate.

- [ ] **Step 1: Write failing tests** for `(${0,3})`, `(\b)+`, `\Z[^c]` against JDK.

- [ ] **Step 2: Run; expect failure.**

- [ ] **Step 3: Investigate** whether PIKEVM_CAPTURE matches these correctly (route experimentally + compare). If yes → routing fix like Task 1. If no → document as `KEEP-PERMANENT` with the modeling limitation.

- [ ] **Step 4: Route-or-keep** per Step 3; delete predicate blocks only for proven-correct cases.

- [ ] **Step 5: Sweep + commit.**

```bash
git commit -m "fix/doc: anchor-in-quantifier routing or documented limitation"
```

---

### Task 8: Synthetic bytecode method-splitting to eliminate the `MethodTooLargeException` fallback (C4)

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFASwitchBytecodeGenerator.java` (state-switch emission, `generateStateSwitch` ~:232, `generateStateCaseCode` ~:267)
- Possibly modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/DFATableBytecodeGenerator.java`, `LiteralAlternationTrieGenerator.java` (only if Step 1 shows they overflow)
- Possibly modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java:486` (keep catch as net, log loudly)
- Test: `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/codegen/MethodSplittingTest.java` (new)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/LargeAlternationNativeTest.java` (new)

**Context:** `MethodTooLargeException` is the JVM's 64 KB per-method bytecode limit. Because reggie emits its own bytecode via ASM, an over-large method can be **split** into JVM-legal helper methods rather than abandoned to JDK. The offending generators are the **explicit-state** ones: `DFASwitchBytecodeGenerator` (verified) emits `int state; int pos; while (pos<len){char ch=charAt(pos++); switch(state){case i: …→ store next state; GOTO loopStart;}}` — the per-state case code at `generateStateCaseCode` (:267) is a pure transition keyed on `(state, ch)` whose only carried state is `state`, `pos`, and the group `int[]` arrays (reference args, mutations visible to caller). This is the canonical splittable structure.

The split: when the estimated emitted size of the state switch exceeds a threshold, partition states into K contiguous buckets; emit one private helper per bucket — `int $stepBucketJ(String input, int pos, char ch, int state, int[] groups)` containing a sub-`tableswitch` over its bucket's states and **returning the next state ID** (the existing `GOTO loopStart` becomes a `return nextState`); the top-level switch becomes a small `tableswitch` routing `state` → `INVOKESPECIAL $stepBucketJ` and storing the returned next state. The main loop is unchanged. Group-array mutations cross the boundary correctly because arrays are passed by reference.

`DFA_UNROLLED` (`DFAUnrolledBytecodeGenerator`, no state variable — control flow is the state) is **not** split here; it is only selected under `DFA_UNROLLED_STATE_LIMIT` and Step 1 confirms it does not overflow.

The `catch (MethodTooLargeException)` at `RuntimeCompiler.java:486` is **retained as a should-never-fire safety net** (a missed split degrades to a correct JDK match, not a crash), but its warning is upgraded to flag a splitter bug.

- [ ] **Step 1: Characterize which generator overflows**

Find or construct a grok-style pattern that currently trips `MethodTooLargeException` (large literal alternation, e.g. `(alt1|alt2|...|altN)` with N in the hundreds). Add temporary instrumentation (or read the existing warning at :486) to record which generator and which method overflowed. Confirm it is `DFA_SWITCH` (or `DFATable`/`LiteralAlternationTrie`), **not** `DFA_UNROLLED`. Write the finding into the test Javadoc. If an unexpected generator overflows, STOP and re-scope.

- [ ] **Step 2: Write the failing runtime test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LargeAlternationNativeTest {

  // Build an alternation large enough to overflow a single 64 KB method (size from Step 1).
  private static String hugeAlternation(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append('|');
      sb.append("kw").append(i); // distinct literal branches
    }
    return "(" + sb + ")";
  }

  @Test
  void hugeAlternationCompilesNativelyAndMatches() {
    String pat = hugeAlternation(2000); // tune n above the Step 1 overflow threshold
    var reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "Huge alternation must compile to a split native matcher, not JDK fallback");
    var jdk = java.util.regex.Pattern.compile(pat);
    for (String in : new String[] {"kw0", "kw1999", "kw1000", "nope", ""}) {
      assertEquals(
          jdk.matcher(in).find(),
          reggie.matcher(in).find(), // adapt to actual ReggieMatcher API
          () -> "mismatch in=" + in);
    }
  }
}
```

> Tune `n` so the *unsplit* method exceeds 64 KB (from Step 1). Adapt the matcher API to `FallbackDetectorBugFixTest` conventions.

- [ ] **Step 3: Run it; expect failure**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.LargeAlternationNativeTest" 2>&1 | tail -20
```

Expected: FAIL — pattern hits `MethodTooLargeException`, routes to `JavaRegexFallbackMatcher`, `assertFalse` fails.

- [ ] **Step 4: Implement bucketed state-switch splitting in `DFASwitchBytecodeGenerator`**

In `generateStateSwitch` (:232): when `dfa.getAllStates().size()` exceeds a tuned `STATE_SPLIT_THRESHOLD` (choose a conservative value — target each helper ≤ ~48 KB emitted to leave headroom; derive from Step 1's bytes-per-state estimate), partition states into contiguous buckets. For each bucket, emit a private method via `cw.visitMethod(ACC_PRIVATE, "$stepBucket" + j, "(Ljava/lang/String;IIC" + groupArrayDesc + ")I", null, null)` whose body is a sub-`tableswitch` over that bucket's states, reusing `generateStateCaseCode` but with the terminal `GOTO loopStart` replaced by `IRETURN` of the next state (introduce a `boolean asHelper` flag or a small refactor of `generateStateCaseCode` — **propose this signature change before implementing**). The top-level switch routes `state` to the owning bucket helper via `INVOKESPECIAL`, stores the returned next state into `stateVar`, and `GOTO loopStart`. Use a reject sentinel (e.g. `-1`) for the no-transition case so the main loop can branch to `rejectLabel`.

- [ ] **Step 5: Add a codegen-level unit test for the splitter**

`MethodSplittingTest` (in `reggie-codegen`): build a DFA with state count above the threshold, run the generator, and assert (a) no `MethodTooLargeException` is thrown, (b) the generated class contains the expected `$stepBucket*` methods, (c) the compiled matcher agrees with `java.util.regex` on a sample input set. This keeps the split logic covered without depending on a giant runtime pattern.

- [ ] **Step 6: Verify the other explicit-state generators**

If Step 1 showed `DFATable` or `LiteralAlternationTrie` also overflow on realistic patterns, apply the same bucketing there (each already keys on explicit state/position). If they do not overflow in practice, note that and leave them; do not pre-split speculatively.

- [ ] **Step 7: Upgrade the retained catch to a should-never-fire net**

In `RuntimeCompiler.java:486`, keep the `catch (MethodTooLargeException)` but change its warning to indicate a splitter defect (it should now be unreachable for the splittable generators):

```java
LOG.warning(
    "Reggie method-splitter failed to keep '" + pattern + "' under the JVM 64 KB limit "
        + "(method " + e.getClassName() + "." + e.getMethodName() + ", codeSize=" + e.getCodeSize()
        + "); falling back to java.util.regex. This indicates a STATE_SPLIT_THRESHOLD bug.");
```

(Adapt to the existing logging field/format.)

- [ ] **Step 8: Run both new tests + full sweep + fuzz gate**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply \
  :reggie-codegen:test --tests "com.datadoghq.reggie.codegen.codegen.MethodSplittingTest" \
  :reggie-runtime:test --tests "com.datadoghq.reggie.runtime.LargeAlternationNativeTest" 2>&1 | tail -20
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
```

Expected: both new tests pass; no new failures; fuzz findings=0.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: split oversized DFA-switch bytecode into helper methods; eliminate method-too-large fallback"
```

---

### Task 9: Final audit and fallback-status documentation

**Files:**
- Modify: `AGENTS.md` (fallback-status section)
- Modify: project memory (`MEMORY.md` + a `project_jdk_fallback_status.md`)

**Context:** Record the final state so future readers know which fallbacks were removed and which remain (and why). After Tasks 0–8, the only remaining `JavaRegexFallbackMatcher` constructions should be: the retained should-never-fire method-size net (C4, now a bug-signal), and any `NEEDS-RND`/`KEEP-PERMANENT` backref/anchor sub-cases from Tasks 6/7.

- [ ] **Step 1: Re-audit construction sites.** `grep -rn "new JavaRegexFallbackMatcher" reggie-runtime/src reggie-codegen/src` — every remaining site must be the C4 net or a documented R&D-gated sub-case. There must be **zero** active routing fallbacks (A1–A5, A7, B10–B11, B15–B19 gone; B1 gone if Task 4 landed).

- [ ] **Step 2: Update `AGENTS.md`** with the final inventory: removed fallbacks (Tasks 0–5, 8), the method-size net (now should-never-fire), and the R&D-gated backref/anchor cases (Tasks 6/7) with their specific reasons.

- [ ] **Step 3: Final full sweep + fuzz gate.**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -30
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests "*AlgorithmicFuzzTest*" 2>&1 | tail -10
```

- [ ] **Step 4: Commit.**

```bash
git add AGENTS.md
git commit -m "docs: record final JDK fallback status"
```

---

## Self-Review

**Spec coverage:** Every inventory row (A1–A7, B1–B19, C1–C4) maps to a task: A4/A5/B17/B18/B19→Task 1; A1/A2/A3→Task 2; B10/B15/B16→Task 3; B1/B11→Task 4; A7→Task 5; A6/B5–B9/B12–B14→Task 6; B2/B3/B4→Task 7; C1/C2/C3→Task 0; C4→Task 8; final audit→Task 9. No row is unassigned.

**Honesty check (per "challenge the user" directive):** This plan does **not** promise to delete every `JavaRegexFallbackMatcher` construction. The `MethodTooLargeException` catch (C4) is intentionally **retained as a should-never-fire net** even though Task 8 makes it unreachable for the splittable generators — removing the net would turn a missed split into a crash instead of a correct (slow) match. Task 6's backref cluster and Task 7's anchor-in-quantifier are explicitly gated on investigation/R&D and may resolve to `KEEP-PERMANENT`; claiming otherwise would contradict the in-code comments and the deferred safe-backtracking memory. Every *active routing* fallback (A1–A5, A7, B10–B11, B15–B19) is targeted for full removal.

**Granularity caveat:** Tasks 0–3 and 5 are routing/cleanup work with concrete TDD steps and pre-written tests. Tasks 4, 6, 7 are engine work whose fix code cannot be pre-written without a root-cause spike — they are deliberately structured as "failing test → mandatory investigation → fix-or-document," per systematic-debugging. This is a real constraint, not a placeholder: the fix shape is unknown until the spike runs.

**Type/name consistency:** All referenced flags (`anchorConditionDiluted`, `alternationPriorityConflict`, `captureAmbiguous`), predicate method names, and line numbers are verified against the current source (2026-06-11). Predicate visibility may need widening (Task 3 Step 4 flags this as a propose-first helper change).

**Dependency order:** Task 1 must precede Tasks 2 and 3 (they assume PikeVM nullable/optional support). Task 0 is independent and first (reduces surface). Tasks 4–7 are independent of each other.
