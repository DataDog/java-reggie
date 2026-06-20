# Complete alternationPriorityConflict PIKEVM Routing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the remaining `alternationPriorityConflict` fallback source for non-anchor patterns without quantified capturing groups. Patterns like `(fo|foo)x`, `(a|ab)c`, `ab|a` currently throw `UnsupportedPatternException` (with Plan A defaults) because the DFA's longest-match conflicts with Java's first-alternative semantics — but PikeVM handles first-alternative correctly. The fix: one condition change in `PatternAnalyzer`.

**Architecture:** In `PatternAnalyzer.analyzeAndRecommend`, the `alternationPriorityConflict` block (lines 866–896) already routes `hasAnchorInNfa(nfa) && !hasQuantifiedCapturingGroup(ast)` to PIKEVM_CAPTURE (guard-1 fix). Dropping the `hasAnchorInNfa` requirement extends this to all patterns without quantified capturing groups. The fuzz-divergence exclusion (`hasQuantifiedCapturingGroup`) remains, keeping `(a|b)+`, `([^a]{0,}\z|.){1,}` etc. on the fallback path.

**Tech Stack:** Java 21, JUnit 5, Gradle.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:873–885` — one condition change + comment update.
- **Create** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AlternationPriorityPikeVMTest.java` — spike + regression tests.

---

### Task 1: Spike + regression tests

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AlternationPriorityPikeVMTest.java`

- [ ] **Step 1: Write the test file**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for alternationPriorityConflict patterns routed to PIKEVM_CAPTURE. The DFA
 * would give longest-match semantics, but Java NFA requires first-alternative. PikeVM gives
 * correct first-alternative semantics.
 */
class AlternationPriorityPikeVMTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // Pure alternation (no quantifiers): DFA accepts state with transitions
  // causes conflict. e.g. for (fo|foo)x the DFA matching "foox" prefers "foox"
  // (longest) but NFA first-alternative gives "fox" from position 0.
  static Stream<Arguments> pureAltPatterns() {
    return Stream.of(
        Arguments.of("(fo|foo)x", "fox"),
        Arguments.of("(fo|foo)x", "foox"),
        Arguments.of("(fo|foo)x", "x"),
        Arguments.of("(fo|foo)x", ""),
        Arguments.of("(a|ab)c", "ac"),
        Arguments.of("(a|ab)c", "abc"),
        Arguments.of("(a|ab)c", "c"),
        Arguments.of("ab|a", "a"),
        Arguments.of("ab|a", "ab"),
        Arguments.of("ab|a", "abc"),
        Arguments.of("ab|a", ""),
        Arguments.of("(foo|fo)x", "fox"),
        Arguments.of("(foo|fo)x", "foox"));
  }

  // Quantified alternation without quantified capturing groups — already routed
  // to PIKEVM by the quantifiedAltWithGroupBug path, kept here as regression guard.
  static Stream<Arguments> quantifiedAltPatterns() {
    return Stream.of(
        Arguments.of("(a|b)+x", "ax"),
        Arguments.of("(a|b)+x", "abx"),
        Arguments.of("(a|b)+x", "x"),
        Arguments.of("(a|ab)+c", "ac"),
        Arguments.of("(a|ab)+c", "abc"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("pureAltPatterns")
  void pureAlt_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  /** After Task 2 these must NOT be JavaRegexFallbackMatcher. */
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("pureAltPatterns")
  void pureAlt_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifiedAltPatterns")
  void quantifiedAlt_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifiedAltPatterns")
  void quantifiedAlt_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  private static void assertAgrees(String pat, String in) {
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

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
```

- [ ] **Step 2: Run to check initial state**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AlternationPriorityPikeVMTest' 2>&1 | tail -20`

Expected:
- `*_agreesWithJdk`: all PASS (correctness confirmed under `WITH_FALLBACK`)
- `pureAlt_routesToPikeVm`: FAIL (currently `JavaRegexFallbackMatcher` or throws)
- `quantifiedAlt_routesToPikeVm`: PASS (already routed to PIKEVM by quantifiedAltWithGroupBug path)

> If any `*_agreesWithJdk` test FAILS, **stop and report BLOCKED** — the pattern has a correctness issue even via JDK path.

- [ ] **Step 3: Commit spike tests**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/AlternationPriorityPikeVMTest.java
git commit -m "test: spike tests for non-anchor alternationPriorityConflict PIKEVM routing"
```

---

### Task 2: Drop `hasAnchorInNfa` from the PIKEVM gate

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:873–885`

- [ ] **Step 1: Locate the block**

The target is the PIKEVM short-circuit inside the `alternationPriorityConflict` block at lines 873–885:

```java
          // Anchor + alternation with simple (non-quantified) capturing groups: PikeVM handles
          // leftmost-first NFA semantics and anchor evaluation correctly without the DFA priority
          // ordering. Outer quantifiers on capturing groups containing anchor branches are excluded
          // — those can diverge (fuzz finding: ([^a]{0,}\z|.){1,}).
          if (hasAnchorInNfa(nfa) && !hasQuantifiedCapturingGroup(ast)) {
```

- [ ] **Step 2: Apply the one-condition change**

Replace the comment + condition with:

```java
          // Alternation priority conflict without quantified capturing groups: PikeVM gives
          // correct first-alternative NFA semantics regardless of whether an anchor is present.
          // Outer quantifiers on capturing groups are excluded — those can diverge in PikeVM
          // (fuzz finding: ([^a]{0,}\z|.){1,}).
          if (!hasQuantifiedCapturingGroup(ast)) {
```

Leave the MatchingStrategyResult return and everything after the `if` block unchanged.

- [ ] **Step 3: Run the spike tests — `pureAlt_routesToPikeVm` must now pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.AlternationPriorityPikeVMTest' 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL, all tests PASS.

> If any `pureAlt_agreesWithJdk` test now FAILS (but passed in Task 1 Step 2), PikeVM has a correctness issue for that pattern. Re-add `hasAnchorInNfa(nfa) &&` to restore the original condition and add a `@Disabled` note for the failing case. Report as DONE_WITH_CONCERNS.

- [ ] **Step 4: Run the full runtime + codegen suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL, 0 failures.

Check that `SilentWrongAnswerRegressionTest` still passes — specifically `control_dfaUnrolled_simpleAnchoredAlternationStillFastPath` which asserts `abc$|def` routes to `DFA_UNROLLED`. That pattern is not affected by this change (it routes via `isAnchorConditionDiluted` or DFA paths, not `alternationPriorityConflict`).

- [ ] **Step 5: Run the fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|repro\]|BUILD" | head -8`

Expected: `findings=0`, BUILD SUCCESSFUL.

> If `findings > 0`, the new routing introduced a regression. Read the repro patterns, check if they have `hasQuantifiedCapturingGroup = true`. If so, the exclusion should have blocked them — investigate why it didn't. Add the failing pattern class to the exclusion and report DONE_WITH_CONCERNS.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
git commit -m "fix: route all non-quantified-group alternation conflicts to PIKEVM"
```

---

## Self-Review Checklist

- [ ] The change is exactly one condition: `hasAnchorInNfa(nfa) &&` removed from line 877. Nothing else changed inside the block.
- [ ] `SilentWrongAnswerRegressionTest.control_dfaUnrolled_simpleAnchoredAlternationStillFastPath` still routes `abc$|def` to `DFA_UNROLLED` (not PIKEVM) — anchor patterns that go through `isAnchorConditionDiluted` are not affected.
- [ ] `quantifiedAlt_routesToPikeVm` passes before AND after the change (those were already PIKEVM via a different path).
- [ ] Fuzz gate `findings=0`.
- [ ] `hasQuantifiedCapturingGroup` is private to PatternAnalyzer — used directly without FallbackPatternDetector. prefix.
