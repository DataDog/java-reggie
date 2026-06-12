# Disabled Guard Fixes: Guard-3 (\Z-in-alternation) + Guard-1 (^|(a) anchor+group)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the two `@Disabled` test groups in `AnchorAlternationPikeVMTest` by routing their patterns to `PIKEVM_CAPTURE` natively. Guard-3: patterns like `\Z|abc` are blocked by `FallbackPatternDetector.hasStringEndAnchorInAltWithProblematicContext` because the `\Z` anchor branch is considered "nullable"; fix by skipping pure-anchor branches in that check and adding a PIKEVM route. Guard-1: patterns like `^|(a)` are blocked by `alternationPriorityConflict` in `PatternAnalyzer`; fix by routing anchor+simple-alternation patterns to PIKEVM before the conflict flag is set.

**Architecture:** Two surgical edits. (1) `FallbackPatternDetector.hasStringEndAnchorInAltHelper`: skip branches that are pure `AnchorNode` in the nullable-branch loop so `\Z|abc` doesn't falsely trigger; also add a PIKEVM route in `PatternAnalyzer` for these patterns so they don't land on `OPTIMIZED_NFA`. (2) `PatternAnalyzer.analyzeAndRecommend`: before setting `alternationPriorityConflict = true`, add a PIKEVM_CAPTURE route for patterns that have `hasAnchorInNfa && !hasQuantifiedCapturingGroup` — this covers `^|(a)` without reopening the `([^a]{0,}\z|.){1,}` class that caused fuzz divergences.

**Depends on:** Plan `2026-06-12-anchor-alternation-pikevm-routing.md` already merged. `RuntimeCompiler.compilePikeVm` and `ReggieOptions.builder().allowJdkFallback()` both exist.

**Tech Stack:** Java 21, JUnit 5, Gradle.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java:286-308` — skip anchor branches in nullable loop.
- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` — (a) add PIKEVM route for `\Z`-in-alternation before `OPTIMIZED_NFA`; (b) add PIKEVM route before `alternationPriorityConflict` for anchor+simple-group patterns.
- **Modify** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java` — remove `@Disabled` from the two test methods once they pass.

---

### Task 1: Spike — confirm PikeVM is correct for both guard classes

**Files:**
- Read: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java` (the `guard3ZPatterns` and `guard1Patterns` source methods)

The `_agreesWithJdk` tests for both guard classes already pass (the tests exist and are not disabled). This task re-runs them explicitly and adds a direct PikeVM check so we know PikeVM is the right target.

- [ ] **Step 1: Run the existing _agreesWithJdk tests for both guard classes**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest' 2>&1 | grep -E "PASS|FAIL|SKIP" | head -30`

Expected: all `_agreesWithJdk` tests PASS; `guard3Z_routesToPikeVm` and `guard1_routesToPikeVm` SKIP (disabled).

- [ ] **Step 2: Verify PikeVM directly handles guard-3Z patterns**

Add a temporary diagnostic test at the end of `AnchorAlternationPikeVMTest`:

```java
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3ZPatterns")
  void guard3Z_pikeVmDirectCheck(String pat, String in) throws Exception {
    // Bypass strategy selection — directly verify PikeVM semantics match JDK.
    ReggieMatcher pikevm = RuntimeCompiler.compilePikeVm(pat, "");
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), pikevm.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), pikevm.find(in), "find() " + ctx);
    java.util.regex.Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = pikevm.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard1Patterns")
  void guard1_pikeVmDirectCheck(String pat, String in) throws Exception {
    ReggieMatcher pikevm = RuntimeCompiler.compilePikeVm(pat, "");
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), pikevm.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), pikevm.find(in), "find() " + ctx);
  }
```

Add the import `import com.datadoghq.reggie.runtime.RuntimeCompiler;` if not already present.

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest.guard3Z_pikeVmDirectCheck' --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest.guard1_pikeVmDirectCheck' 2>&1 | tail -15`

Expected: all PASS — PikeVM gives correct results for both guard classes.

> If any `pikeVmDirectCheck` test FAILS, **stop and report BLOCKED**. It means PikeVM has a correctness issue for that specific pattern; the guard exists for a real reason and cannot be removed.

- [ ] **Step 3: Remove the temporary diagnostic tests**

Delete the two `guard3Z_pikeVmDirectCheck` and `guard1_pikeVmDirectCheck` methods — these were investigation only, not regression tests (the enabled `_agreesWithJdk` tests already cover correctness).

- [ ] **Step 4: Commit spike confirmation**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
# No file changes after removing diagnostic tests — nothing to commit.
# (If spotless made format changes, commit them.)
```

---

### Task 2: Fix guard-3 — narrow `hasStringEndAnchorInAltHelper` + add PIKEVM route

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java:298-308`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`

**Why the predicate over-fires:** `hasStringEndAnchorInAltHelper` (line 298-308) loops over alternation branches checking for nullable/empty/broad-char-class branches. For `\Z|abc`, the `\Z` branch itself is treated as nullable (`subtreeIsNullable(AnchorNode)` returns true), so the predicate fires. But anchors are always zero-width — their "nullability" is not the problem. The problem is non-anchor branches that are nullable alongside a `\Z`/`$` branch. PikeVM handles the anchor branch correctly.

**Two-part fix:**
1. In `hasStringEndAnchorInAltHelper`, skip pure `AnchorNode` branches in the nullable-branch loop.
2. In `PatternAnalyzer.analyzeAndRecommend`, add a PIKEVM_CAPTURE route for `\Z`/`$`-in-alternation patterns before they reach `OPTIMIZED_NFA` (which mishandles them). This route fires when `hasStringEndAnchorInAlternation(ast)` is true and the DFA has accepting states with transitions.

- [ ] **Step 1: Locate the exact block in FallbackPatternDetector**

Read lines 286-310 of `FallbackPatternDetector.java`. Find this loop (lines ~298-308):

```java
      if (hasStringEndInAlt) {
        if (containsCapturingGroup(node)) return true;
        for (RegexNode branch : alt.alternatives) {
          if (isNullableOrEmptyBranch(branch) || startsWithZeroWidthQuantifier(branch)) {
            return true;
          }
          // Broad-charset branch (like '.') that also does NOT contain a start-class anchor
          // (which would make it a dead/impossible branch) can cause span conflicts with \Z
          // branches.
          if (startsWithBroadCharClass(branch) && !containsAnchor(branch)) {
            return true;
          }
        }
      }
```

- [ ] **Step 2: Add the AnchorNode skip**

Replace the loop body with:

```java
      if (hasStringEndInAlt) {
        if (containsCapturingGroup(node)) return true;
        for (RegexNode branch : alt.alternatives) {
          // Pure-anchor branches (e.g. \Z, $, ^) are always zero-width. Their "nullability" is
          // definitional, not a structural problem — PikeVM handles them correctly. Only non-anchor
          // nullable branches cause OPTIMIZED_NFA's span tracking to fail.
          if (branch instanceof AnchorNode) continue;
          if (isNullableOrEmptyBranch(branch) || startsWithZeroWidthQuantifier(branch)) {
            return true;
          }
          if (startsWithBroadCharClass(branch) && !containsAnchor(branch)) {
            return true;
          }
        }
      }
```

- [ ] **Step 3: Locate where to add the PIKEVM route in PatternAnalyzer**

Search for `hasStringEndAnchorInAlternation` in `PatternAnalyzer.java`. It is used in the `ignoreGroupCount=true` path (lines ~1058-1063). For the `ignoreGroupCount=false` path, `\Z`-in-alternation patterns currently fall through to `OPTIMIZED_NFA` (or `alternationPriorityConflict`). We need to route them to `PIKEVM_CAPTURE` before that happens.

Find the block in `analyzeAndRecommend(boolean ignoreGroupCount)` (around line 850 in the `ignoreGroupCount=false` path) where `alternationPriorityConflict` is set. Just BEFORE the condition at line 855 (`if ((containsAlternation(ast) || containsOptionalQuantifier(ast)) && ...)`), add:

```java
        // \Z or $ in alternation (without capturing group): OPTIMIZED_NFA mishandles find()
        // anchor semantics; route to PIKEVM_CAPTURE which handles \Z/$ correctly via
        // per-thread NFA simulation. Patterns with capturing groups are handled below.
        if (hasStringEndAnchorInAlternation(ast)
            && !containsCapturingGroup(ast)
            && dfaHasAcceptingStateWithTransitions(dfa)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
```

Where `containsCapturingGroup(ast)` is `FallbackPatternDetector.containsCapturingGroup(ast)` — it's already imported/available since PatternAnalyzer uses FallbackPatternDetector extensively. Check the imports and use the correct call.

> **Note on `hasStringEndAnchorInAlternation`:** this private method is `return containsAlternation(node) && nfa != null && nfa.hasStringEndAnchor()`. It covers both `$` (END) and `\Z` (STRING_END) since `nfa.hasStringEndAnchor()` checks for STRING_END anchors and `nfa.hasEndAnchor()` covers `$`. Verify which NFA method covers both, or use `nfa.hasStringEndAnchor() || nfa.hasEndAnchor()` directly.

- [ ] **Step 4: Run the guard-3Z routesToPikeVm test (still @Disabled — just compile check)**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test :reggie-runtime:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures (the @Disabled tests still skip — we'll enable in Task 4).

- [ ] **Step 5: Quick sanity: verify `\Z|abc` no longer falls back**

Add a one-shot assertion in a temporary test (or use an existing test):

```java
// In any test class, temporarily:
ReggieMatcher m = Reggie.compile("\\Z|abc");
assertFalse(m instanceof JavaRegexFallbackMatcher);
```

Or use the `guard3Z_pikeVmDirectCheck` approach from Task 1 temporarily.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
git commit -m "fix: route \\Z-in-alternation to PIKEVM; narrow FallbackDetector anchor-branch check"
```

---

### Task 3: Fix guard-1 — PIKEVM route before `alternationPriorityConflict` for anchor+simple-group

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:855-871`

**Why `^|(a)` hits `alternationPriorityConflict`:** `^|(a)` has `^` (start anchor) and `(a)` (capturing group) in an alternation. The DFA start state is accepting (since `^` can match the empty string at position 0) OR has accepting state with transitions, satisfying the `alternationPriorityConflict` condition at line 855-860. The existing PIKEVM short-circuit at lines 842-843 only fires for `quantifiedAltWithGroupBug && !hasAnchorInNfa(nfa)` — requiring NO anchor. So anchor patterns are explicitly excluded from that route.

**The narrowing that keeps it safe:** The fuzz divergences for `([^a]{0,}\z|.){1,}` came from routing patterns with QUANTIFIED capturing groups to PIKEVM. Specifically, `([^a]{0,}\z|.){1,}` has an outer `{1,}` quantifier wrapping a capturing group → `FallbackPatternDetector.hasQuantifiedCapturingGroup(ast)` = true. `^|(a)` has NO outer quantifier on its capturing group → `hasQuantifiedCapturingGroup` = false. This is the safe gate.

- [ ] **Step 1: Locate the exact block**

Find lines 855-871 in `PatternAnalyzer.analyzeAndRecommend`. The block looks like:

```java
        if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
            && (quantifiedAltWithGroupBug
                || (containsAnyQuantifier(ast)
                    ? dfaHasAcceptingStateWithTransitions(dfa)
                    : (dfa.getStartState().accepting
                        || hasUnresolvedAcceptingTransitionState(dfa))))) {
          MatchingStrategyResult r =
              new MatchingStrategyResult(
                  MatchingStrategy.OPTIMIZED_NFA, null, null, false,
                  requiredLiterals, null, needsPosixSemantics);
          r.alternationPriorityConflict = true;
          return r;
        }
```

- [ ] **Step 2: Add PIKEVM route INSIDE this block, before setting the flag**

Replace the block with:

```java
        if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
            && (quantifiedAltWithGroupBug
                || (containsAnyQuantifier(ast)
                    ? dfaHasAcceptingStateWithTransitions(dfa)
                    : (dfa.getStartState().accepting
                        || hasUnresolvedAcceptingTransitionState(dfa))))) {
          // Anchor + alternation with simple (non-quantified) capturing groups: PikeVM handles
          // leftmost-first NFA semantics and anchor evaluation correctly. The DFA priority conflict
          // is irrelevant for PikeVM. Patterns with quantified capturing groups are excluded —
          // outer quantifiers on groups with anchor branches in alternation can diverge (see
          // fuzz finding for ([^a]{0,}\z|.){1,}).
          if (hasAnchorInNfa(nfa) && !FallbackPatternDetector.hasQuantifiedCapturingGroup(ast)) {
            return new MatchingStrategyResult(
                MatchingStrategy.PIKEVM_CAPTURE,
                null,
                null,
                false,
                requiredLiterals,
                null,
                needsPosixSemantics);
          }
          MatchingStrategyResult r =
              new MatchingStrategyResult(
                  MatchingStrategy.OPTIMIZED_NFA, null, null, false,
                  requiredLiterals, null, needsPosixSemantics);
          r.alternationPriorityConflict = true;
          return r;
        }
```

- [ ] **Step 3: Run runtime + codegen tests**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
git commit -m "fix: route anchor+simple-group alternation to PIKEVM before alternationPriorityConflict"
```

---

### Task 4: Enable disabled tests + full sweep + fuzz gate

**Files:**
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java`

- [ ] **Step 1: Remove `@Disabled` from both test methods**

In `AnchorAlternationPikeVMTest.java`, remove the `@Disabled(...)` annotation from `guard3Z_routesToPikeVm` and `guard1_routesToPikeVm`. Also remove the `@Disabled` import if no other tests use it.

- [ ] **Step 2: Run the full AnchorAlternationPikeVMTest**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 0 failures, 0 skips.

> If any test fails: re-add `@Disabled` to that specific test and add a comment explaining which predicate still blocks it. Report as DONE_WITH_CONCERNS.

- [ ] **Step 3: Run the full test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 4: Run the fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|repro\]|BUILD" | head -10`
Expected: `findings=0`, BUILD SUCCESSFUL.

> If `findings > 0`: the new routing introduced a correctness regression. Run with `--info` to see the exact failing patterns, then re-add the guard for the failing pattern class and add a test documenting the limitation.

- [ ] **Step 5: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java
git commit -m "test: enable guard-3Z and guard-1 PIKEVM routing tests"
```

---

## Self-Review Checklist

- [ ] Guard-3Z fix: `branch instanceof AnchorNode` skip is in the branch-loop inside `if (hasStringEndInAlt)` in `hasStringEndAnchorInAltHelper` (line ~298). It does NOT skip the `containsCapturingGroup(node)` check (which fires on the whole alternation, not per-branch — that stays).
- [ ] Guard-3Z PIKEVM route: fires only for `!containsCapturingGroup(ast)` — patterns WITH capturing groups AND `\Z` in alternation still route through the existing group-aware path.
- [ ] Guard-1 fix: `hasAnchorInNfa(nfa) && !hasQuantifiedCapturingGroup(ast)` gate correctly excludes `([^a]{0,}\z|.){1,}` (quantified capturing group) while including `^|(a)` (no quantified capturing group).
- [ ] No changes to the `ignoreGroupCount=true` path (which already has good routing).
- [ ] Fuzz gate passes with `findings=0` — this is the definitive correctness check.
- [ ] Both `@Disabled` tests in `AnchorAlternationPikeVMTest` are removed (or each remaining one has a documented reason).
