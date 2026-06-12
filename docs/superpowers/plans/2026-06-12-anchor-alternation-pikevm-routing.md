# Anchor-Alternation PIKEVM Routing + Hybrid DFA Fallback Elimination

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the remaining `anchorConditionDiluted` fallback sources: (task #15) relax three over-conservative guards in the capturing-group PIKEVM routing path so anchor-diluted alternation patterns with nullable/optional/end-anchor branches route to `PIKEVM_CAPTURE` instead of JDK; (task #16) pre-check DFA anchor dilution before entering `compileHybrid` so patterns with groups whose DFA is diluted skip hybrid and use the NFA-only path instead of throwing.

**Architecture:** Two surgical edits. (1) `PatternAnalyzer.analyzeAndRecommend(false)` at the `isAnchorConditionDiluted` guard block (lines 800–824): remove the three `!hasNullableAlternationBranch`, `!subtreeContainsOptional`, and `!hasEndAnchorLeadingInAlternationBranch` guards — matching the identical guard-free routing already present in the `ignoreGroupCount=true` path at lines 1073–1075. (2) `RuntimeCompiler.compileInternal`: before calling `compileHybrid`, pre-compute `analyzeAndRecommend(true)` and skip hybrid when the DFA is anchor-diluted; pass the pre-computed result into `compileHybrid`, removing the internal recomputation and dead `fallbackOrThrow` branch.

**Tech Stack:** Java 21, JUnit 5, Gradle. No new dependencies.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` — remove three guards at lines 802–804; update comment at lines 792–799.
- **Modify** `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` — pre-check anchor dilution at lines 470–476; update `compileHybrid` signature (line 627) and body (remove lines 637–644).
- **Create** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java` — spike + regression tests for guard-class patterns.
- **Create** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/HybridAnchorDilutedTest.java` — regression tests for hybrid path with anchor-diluted DFA.

---

### Task 1: Spike tests — confirm PikeVM correctness for every guard class

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java`

These tests document the expected correct behavior and will turn green after Task 2.

- [ ] **Step 1: Write the test file**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that anchor-diluted alternation patterns are correctly handled by PIKEVM_CAPTURE after
 * the guard removal in PatternAnalyzer. Previously these patterns fell back to java.util.regex via
 * the anchorConditionDiluted flag.
 *
 * <p>Three guard classes under test:
 * <ul>
 *   <li>Guard 3: end-anchor ($, \Z) as the leading element of an alternation branch (e.g. $|x).
 *   <li>Guard 2: optional ({0,n}) quantifier anywhere in an anchor-diluted alternation pattern.
 *   <li>Guard 1: nullable alternation branch in an anchor-diluted pattern.
 * </ul>
 */
class AnchorAlternationPikeVMTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // ---------------------------------------------------------------------------
  // Guard 3: end-anchor leading in an alternation branch
  // e.g. "$|x", "\Z|abc"  — the entire first branch is $, so branchLeadsWithEndAnchor returns true.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard3Patterns() {
    return Stream.of(
        Arguments.of("$|x", ""),
        Arguments.of("$|x", "x"),
        Arguments.of("$|x", "abc"),
        Arguments.of("\\Z|abc", ""),
        Arguments.of("\\Z|abc", "abc"),
        Arguments.of("\\Z|abc", "xyz"),
        Arguments.of("$|[^c]", ""),
        Arguments.of("$|[^c]", "a"),
        Arguments.of("$|[^c]", "c"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3Patterns")
  void guard3_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  /** After Task 2 these patterns must NOT be JavaRegexFallbackMatcher. */
  @ParameterizedTest(name = "[{index}] pat={0}")
  @MethodSource("guard3Patterns")
  void guard3_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard3: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // Guard 2: optional ({0,n}) subtree in anchor-diluted alternation
  // e.g. "[1][^-]?\Z|_{2}" — [^-]? has min=0.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard2Patterns() {
    return Stream.of(
        Arguments.of("[1][^-]?\\Z|_{2}", "1"),
        Arguments.of("[1][^-]?\\Z|_{2}", ""),
        Arguments.of("[1][^-]?\\Z|_{2}", "__"),
        Arguments.of("[1][^-]?\\Z|_{2}", "1-"),
        Arguments.of("a?$|b", ""),
        Arguments.of("a?$|b", "a"),
        Arguments.of("a?$|b", "b"),
        Arguments.of("a?$|b", "ab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard2Patterns")
  void guard2_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0}")
  @MethodSource("guard2Patterns")
  void guard2_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard2: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // Guard 1: nullable alternation branch in anchor-diluted pattern
  // e.g. "^|(a)" — ^ matches empty string (nullable) and causes DFA dilution.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard1Patterns() {
    return Stream.of(
        Arguments.of("^|(a)", ""),
        Arguments.of("^|(a)", "a"),
        Arguments.of("^|(a)", "ab"),
        Arguments.of("$|(b)", ""),
        Arguments.of("$|(b)", "b"),
        Arguments.of("$|(b)", "ab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard1Patterns")
  void guard1_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  @ParameterizedTest(name = "[{index}] pat={0}")
  @MethodSource("guard1Patterns")
  void guard1_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard1: expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
```

- [ ] **Step 2: Run the tests and check which fail**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest' 2>&1 | tail -20`

Expected state:
- `*_agreesWithJdk` tests: **PASS** — patterns currently compile with `WITH_FALLBACK` to `JavaRegexFallbackMatcher` (or native), and JDK agrees with itself.
- `*_routesToPikeVm` tests: **FAIL** — patterns currently produce `JavaRegexFallbackMatcher`, not native.

> If any `*_agreesWithJdk` test FAILS, **stop and investigate** before proceeding. A failure here means the pattern itself has a correctness issue with the JDK fallback path, which would be a bug unrelated to this plan.

- [ ] **Step 3: Commit spike tests**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AnchorAlternationPikeVMTest.java
git commit -m "test: spike tests for anchor-alternation PIKEVM routing guard classes"
```

---

### Task 2: Remove the three guards from `PatternAnalyzer` location 1 (task #15)

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:792-825`

The `ignoreGroupCount=true` path at lines 1062–1075 already routes these patterns to `PIKEVM_CAPTURE` without any guards (with the comment "Previous exclusions for hasNullableAlternationBranch, subtreeContainsOptional, and hasEndAnchorLeadingInAlternationBranch are removed"). This task applies the identical change to the `ignoreGroupCount=false` path.

- [ ] **Step 1: Locate the block in PatternAnalyzer**

The target is the `if (dfa.isAnchorConditionDiluted())` block in the `ignoreGroupCount=false` path. It starts around line 800. It is preceded by the comment at lines 792–799:

```java
        // Anchor-diluted alternation patterns: PIKEVM_CAPTURE gives correct leftmost-first
        // semantics for start-anchor-in-alternation cases (e.g. ^x|x(y)) because PikeVM
        // evaluates ^/\A against the fixed search-region origin since commit 0acfc66.
        // The same three exclusions used for the non-capturing PIKEVM gate apply here:
        //  1. hasNullableAlternationBranch: optional branch can match empty.
        //  2. subtreeContainsOptional: any {0,n} quantifier causes greedy divergence from JDK.
        //  3. hasEndAnchorLeadingInAlternationBranch: leading end-anchor diverges in find().
        // Patterns failing these guards keep the anchorConditionDiluted → JDK path below.
        if (dfa.isAnchorConditionDiluted()) {
          if (containsAlternation(ast)
              && !hasNullableAlternationBranch(ast)
              && !subtreeContainsOptional(ast)
              && !hasEndAnchorLeadingInAlternationBranch(ast)
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
          MatchingStrategyResult r =
              new MatchingStrategyResult(
                  MatchingStrategy.OPTIMIZED_NFA,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
          r.anchorConditionDiluted = true;
          return r;
        }
```

- [ ] **Step 2: Replace the block**

Replace the comment + `if (dfa.isAnchorConditionDiluted())` block with:

```java
        // Anchor-diluted alternation patterns: PIKEVM_CAPTURE gives correct leftmost-first
        // semantics for nullable/optional/end-anchor alternation branches. Guards for
        // hasNullableAlternationBranch, subtreeContainsOptional, and
        // hasEndAnchorLeadingInAlternationBranch are removed: ThompsonBuilder wraps {0,n}
        // fragments in a skip-entry state (preventing mixed char+epsilon DFA states), and
        // PikeVMMatcher.checkAnchor correctly handles $ before a trailing newline.
        // This mirrors the identical guard-free routing in the ignoreGroupCount=true path.
        if (dfa.isAnchorConditionDiluted()) {
          if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
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
                  MatchingStrategy.OPTIMIZED_NFA,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
          r.anchorConditionDiluted = true;
          return r;
        }
```

The only changes: (a) updated comment, (b) removed `&& !hasNullableAlternationBranch(ast) && !subtreeContainsOptional(ast) && !hasEndAnchorLeadingInAlternationBranch(ast)` from the inner `if`.

- [ ] **Step 3: Run the spike tests — all should now pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AnchorAlternationPikeVMTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all tests PASS.

> If any `*_agreesWithJdk` test fails now (but passed in Task 1 Step 2), the removed guard was legitimately protecting against a PikeVM correctness bug. **Stop, re-add the failing guard, and add a `@Disabled` explanation to the failing test.** The remaining guards that pass can still be removed.

- [ ] **Step 4: Run the full runtime + codegen test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
git commit -m "fix: remove over-conservative PIKEVM guards for anchor-diluted alternation"
```

---

### Task 3: Pre-check DFA anchor dilution in `compileInternal`; refactor `compileHybrid` (task #16)

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java:470-476` (call site)
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java:627-644` (`compileHybrid` signature + first block)
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/HybridAnchorDilutedTest.java`

When `compileHybrid` is called for a pattern with groups, it re-runs `analyzeAndRecommend(true)` to get the DFA-only strategy. If that DFA is anchor-diluted it currently throws. The fix: pre-compute the DFA result in `compileInternal` and skip hybrid when diluted, letting the NFA-only routing handle the pattern instead.

- [ ] **Step 1: Write the regression test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that patterns with capturing groups whose hybrid DFA is anchor-diluted route to the
 * NFA-only path instead of falling back to java.util.regex.
 *
 * <p>Before the fix these patterns threw UnsupportedPatternException (or returned
 * JavaRegexFallbackMatcher with ALLOW_JDK_FALLBACK). After the fix they compile natively.
 */
class HybridAnchorDilutedTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // Patterns with capturing groups + anchor-diluted DFA (hybrid would fail).
  // ([a-z]+|$) — group + end-anchor in alternation → hybrid DFA is anchor-diluted.
  // ([a-z]*)(^x|y) — group + start-anchor in alternation → hybrid DFA is anchor-diluted.
  static Stream<Arguments> hybridDilutedPatterns() {
    return Stream.of(
        Arguments.of("([a-z]+|$)", ""),
        Arguments.of("([a-z]+|$)", "abc"),
        Arguments.of("([a-z]+|$)", "123"),
        Arguments.of("([a-z]+)(^x|y)", ""),
        Arguments.of("([a-z]+)(^x|y)", "abcy"),
        Arguments.of("([a-z]+)(^x|y)", "xy"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("hybridDilutedPatterns")
  void agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  @ParameterizedTest(name = "[{index}] pat={0}")
  @MethodSource("hybridDilutedPatterns")
  void routesToNative(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.HybridAnchorDilutedTest' 2>&1 | tail -20`

Expected: `routesToNative` tests FAIL (patterns produce `JavaRegexFallbackMatcher`). `agreesWithJdk` tests PASS.

> If any `agreesWithJdk` test fails, the pattern doesn't actually hit the hybrid-diluted path — replace it with one that does. Verify by temporarily adding a `System.out.println(Reggie.compile(pat, WITH_FALLBACK).getClass())` line.

- [ ] **Step 3: Update the `compileHybrid` call site in `compileInternal`**

Find the block at lines 470–476 of `RuntimeCompiler.java`:

```java
      // 4. Check if we should use hybrid mode (DFA + NFA for groups)
      if (groupCount > 0 && shouldUseHybrid(result)) {
        ReggieMatcher hybrid =
            compileHybrid(pattern, ast, nfa, analyzer, result, caseInsensitive, options);
        hybrid.setNameToIndex(nameMap);
        return hybrid;
      }
```

Replace with:

```java
      // 4. Check if we should use hybrid mode (DFA + NFA for groups)
      if (groupCount > 0 && shouldUseHybrid(result)) {
        PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);
        if (!dfaResult.anchorConditionDiluted) {
          ReggieMatcher hybrid =
              compileHybrid(pattern, ast, nfa, dfaResult, result, caseInsensitive, options);
          hybrid.setNameToIndex(nameMap);
          return hybrid;
        }
        // Hybrid DFA anchor-diluted: skip hybrid, fall through to NFA-only routing below.
      }
```

- [ ] **Step 4: Update the `compileHybrid` signature and remove the internal recomputation**

Find the `compileHybrid` method starting at line 627. Current signature:

```java
  private static ReggieMatcher compileHybrid(
      String pattern,
      RegexNode ast,
      NFA nfa,
      PatternAnalyzer analyzer,
      PatternAnalyzer.MatchingStrategyResult originalResult,
      boolean caseInsensitive,
      ReggieOptions options)
      throws Exception {
    // 1. Get DFA strategy (ignore group count)
    PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);

    // If DFA construction failed due to anchor-condition dilution, the pure NFA fallback may
    // produce incorrect results (e.g. dot matching newline). Route to JDK instead.
    if (dfaResult.anchorConditionDiluted) {
      return fallbackOrThrow(
          pattern, "anchor condition diluted in hybrid DFA build", null, options);
    }
    // If DFA construction failed or pattern needs NFA anyway, fall back to pure NFA
    if (dfaResult.dfa == null) {
```

Replace **only** the signature + first block (up to and including the `anchorConditionDiluted` check) with:

```java
  private static ReggieMatcher compileHybrid(
      String pattern,
      RegexNode ast,
      NFA nfa,
      PatternAnalyzer.MatchingStrategyResult dfaResult,
      PatternAnalyzer.MatchingStrategyResult originalResult,
      boolean caseInsensitive,
      ReggieOptions options)
      throws Exception {
    // dfaResult is pre-computed by compileInternal; anchor-diluted patterns are pre-filtered.
    // If DFA construction failed or pattern needs NFA anyway, fall back to pure NFA
    if (dfaResult.dfa == null) {
```

Leave all other code in `compileHybrid` unchanged.

- [ ] **Step 5: Verify it compiles**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run the regression test — all should now pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.HybridAnchorDilutedTest' 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 7: Run the full runtime test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/HybridAnchorDilutedTest.java
git commit -m "fix: skip hybrid when DFA anchor-diluted; route to NFA-only path"
```

---

### Task 4: Full test suite + fuzz gate

**Files:** None created or modified.

- [ ] **Step 1: Full test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 2: Fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|zeroDivergence|BUILD" | head -5`
Expected: `findings=0`, BUILD SUCCESSFUL.

- [ ] **Step 3: spotlessApply (final check)**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew spotlessApply`
Expected: no changes (everything already formatted).

- [ ] **Step 4: Commit AGENTS.md if patterns changed**

If any pattern routing documentation in `AGENTS.md` is now stale (the three guard rows in the `FallbackPatternDetector` table or the `RuntimeCompiler` table), update them. Look for:
- Row: `hasNullableAlternationBranch` in alternation → if removed from location 1, update its status
- Row: `subtreeContainsOptional` in alternation → same
- Row: `hasEndAnchorLeadingInAlternationBranch` in alternation → same
- Row: `anchor condition diluted in hybrid DFA build` → now routes to NFA-only, not JDK

```bash
git add AGENTS.md
git commit -m "docs: update fallback inventory for anchor-alternation guard removals"
```

---

## Self-Review Checklist

- [ ] Task #15 (guard removal) is covered by Task 2. Three guards removed at lines 802–804.
- [ ] Task #16 (hybrid pre-check) is covered by Task 3. `compileHybrid` no longer recomputes or calls `fallbackOrThrow` for anchor-diluted DFA.
- [ ] Test coverage: each guard class has `agreesWithJdk` + `routesToPikeVm` tests (Task 1). Hybrid path has `agreesWithJdk` + `routesToNative` tests (Task 3).
- [ ] The `*_agreesWithJdk` tests must PASS before the code change (confirming `WITH_FALLBACK` + JDK path is correct). If they fail, stop — the fix would be wrong.
- [ ] No placeholder text — all code is concrete.
- [ ] `WITH_FALLBACK` option name is consistent across both test files.
- [ ] `compileHybrid` signature change: `PatternAnalyzer analyzer` → `PatternAnalyzer.MatchingStrategyResult dfaResult`. The body uses `dfaResult` directly. No other callers of `compileHybrid` exist (it's private).
- [ ] The `fallbackOrThrow` import / usage in `compileHybrid` is removed along with the dead block.
