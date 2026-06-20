# alternationPriorityConflict: Enable Quantified Capturing Groups in PIKEVM

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route `(a|b)+x`, `(a|ab)+c` and similar patterns to PIKEVM_CAPTURE instead of throwing. These hit `alternationPriorityConflict` because the outer `+` quantifier wrapping a capturing group is currently excluded from PIKEVM routing by `hasQuantifiedCapturingGroup`. The exclusion was added to block fuzz-diverging patterns like `([^a]{0,}\z|.){1,}` — those have nested quantifiers *inside* the capturing group body. Simple groups like `(a|b)` have none.

**Architecture:** Replace the `hasQuantifiedCapturingGroup(ast)` gate in the `alternationPriorityConflict` block with a more precise `hasComplexQuantifiedCapturingGroup(ast)` — a new private helper that returns true only when a quantified capturing group's *body* contains another quantifier or an anchor. `(a|b)+`: body is `a|b`, no inner quantifier, no anchor → false → PIKEVM. `([^a]{0,}\z|.){1,}`: body has `{0,}` and `\z` → true → remains in fallback. One new private method in `PatternAnalyzer`, one condition change.

**Tech Stack:** Java 21, JUnit 5, Gradle.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` — add `hasComplexQuantifiedCapturingGroup` helper; change the gate condition.
- **Create** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java` — spike + regression tests.

---

### Task 1: Spike tests

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java`

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
 * Regression coverage for alternationPriorityConflict patterns with simple outer quantifiers on
 * capturing groups. These patterns are safe for PIKEVM: the group body has no nested quantifiers
 * or anchors, so PikeVM's per-thread simulation gives correct first-alternative semantics.
 *
 * <p>Patterns with complex group bodies (nested quantifiers or anchors inside the group) remain
 * in the fallback path — e.g. ([^a]{0,}\z|.){1,} which caused fuzz divergences.
 */
class QuantifiedGroupAltPriorityTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // Simple outer-quantified groups: body has no nested quantifier, no anchor.
  static Stream<Arguments> simpleQuantifiedGroupPatterns() {
    return Stream.of(
        // outer + on simple alternation group
        Arguments.of("(a|b)+x", "ax"),
        Arguments.of("(a|b)+x", "bx"),
        Arguments.of("(a|b)+x", "abx"),
        Arguments.of("(a|b)+x", "x"),
        Arguments.of("(a|b)+x", ""),
        // longer alternatives
        Arguments.of("(a|ab)+c", "ac"),
        Arguments.of("(a|ab)+c", "abc"),
        Arguments.of("(a|ab)+c", "aabc"),
        Arguments.of("(a|ab)+c", "c"),
        // outer * quantifier
        Arguments.of("(a|b)*x", "x"),
        Arguments.of("(a|b)*x", "ax"),
        Arguments.of("(a|b)*x", "abx"),
        // outer {2,3} quantifier
        Arguments.of("(a|b){2,3}x", "aax"),
        Arguments.of("(a|b){2,3}x", "abx"),
        Arguments.of("(a|b){2,3}x", "ababx"));
  }

  // Complex outer-quantified groups: body has nested quantifier or anchor → must still fall back.
  // These confirm the exclusion is not over-broadened.
  static Stream<Arguments> complexQuantifiedGroupPatterns() {
    return Stream.of(
        Arguments.of("([^a]{0,}\\z|.){1,}", "c"),
        Arguments.of("([^a]{0,}\\z|.){1,}", "-"),
        Arguments.of("(a+|b)+x", "ax"),
        Arguments.of("(a+|b)+x", "abx"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("simpleQuantifiedGroupPatterns")
  void simpleGroup_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  /** After Task 2 these must route to native PIKEVM (not throw or return fallback matcher). */
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("simpleQuantifiedGroupPatterns")
  void simpleGroup_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("complexQuantifiedGroupPatterns")
  void complexGroup_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
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
      // Check group 1 span where both have the group captured.
      if (jm.groupCount() >= 1 && jm.start(1) != -1 && rf.start(1) != -1) {
        assertEquals(jm.start(1), rf.start(1), "findMatch() g1 start " + ctx);
        assertEquals(jm.end(1), rf.end(1), "findMatch() g1 end " + ctx);
      }
    }
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
```

- [ ] **Step 2: Run to verify initial state**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.QuantifiedGroupAltPriorityTest' 2>&1 | tail -20`

Expected:
- `*_agreesWithJdk`: all PASS — correctness confirmed via `WITH_FALLBACK`
- `simpleGroup_routesToPikeVm`: FAIL — patterns currently throw or return fallback
- `complexGroup_agreesWithJdk`: PASS — complex patterns agree via JDK fallback

> If any `*_agreesWithJdk` test FAILS, **stop and report BLOCKED**.

- [ ] **Step 3: Commit spike tests**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java
git commit -m "test: spike tests for simple-body quantified-group alternation PIKEVM routing"
```

---

### Task 2: Add `hasComplexQuantifiedCapturingGroup` + update gate

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`

**The new helper checks if any quantified capturing group's body contains a quantifier or anchor.** `containsAnyQuantifier` already exists (line 1339) and recurses into the AST. `hasAnchorInNfa` checks for anchors in the NFA — that's a safe proxy here (any anchor in the pattern means some quantified group might contain one).

Actually, `hasAnchorInNfa` checks the whole NFA, not just group bodies. For a more precise check, use an AST-level `containsAnchorInSubtree` helper. However, for safety, using `hasAnchorInNfa(nfa)` as a pattern-level guard is acceptable: if any anchor exists anywhere in the pattern AND there's a quantified capturing group, keep it in fallback. This is conservative but safe — patterns with anchors AND quantified groups that are currently correct can always be enabled in a follow-up.

**Alternative approach (more precise):** Check if the quantified capturing group's *body* specifically contains a quantifier or anchor by walking only that node's subtree.

Use the more precise approach — it allows `^(a|b)+x` (anchor outside the group, group body is clean) while blocking `(a+|b)+x` (anchor inside would also block, but `a+` has inner quantifier which blocks it too).

- [ ] **Step 1: Add the `hasComplexQuantifiedCapturingGroup` private method**

Place it next to `hasQuantifiedCapturingGroup` (around line 1446). Note that `containsAnyQuantifier(RegexNode)` is an existing private method (line 1339). For anchors, add a minimal `containsAnchorInSubtree(RegexNode)` helper:

```java
  /**
   * Returns true if any quantified capturing group in the subtree has a body that contains a
   * nested quantifier or anchor. Such groups require complex backtracking semantics that PikeVM
   * does not currently handle correctly for alternation-priority-conflict patterns.
   *
   * <p>Simple groups like {@code (a|b)+} (body: {@code a|b}, no quantifier, no anchor) return
   * false and are safe to route to PIKEVM_CAPTURE.
   */
  private boolean hasComplexQuantifiedCapturingGroup(RegexNode node) {
    if (node instanceof QuantifierNode q && q.child instanceof GroupNode g && g.capturing) {
      if (containsAnyQuantifier(g.child) || containsAnchorInSubtree(g.child)) {
        return true;
      }
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (hasComplexQuantifiedCapturingGroup(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return hasComplexQuantifiedCapturingGroup(g.child);
    if (node instanceof QuantifierNode q) return hasComplexQuantifiedCapturingGroup(q.child);
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (hasComplexQuantifiedCapturingGroup(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /** Returns true if the subtree contains any anchor node. */
  private static boolean containsAnchorInSubtree(RegexNode node) {
    if (node instanceof AnchorNode) return true;
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (containsAnchorInSubtree(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return containsAnchorInSubtree(g.child);
    if (node instanceof QuantifierNode q) return containsAnchorInSubtree(q.child);
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (containsAnchorInSubtree(alt)) return true;
      }
      return false;
    }
    return false;
  }
```

Verify `AnchorNode` is already imported / accessible in `PatternAnalyzer`. If not, add the import.

- [ ] **Step 2: Update the gate condition**

Find the PIKEVM short-circuit inside the `alternationPriorityConflict` block (lines 873–885, the result of the previous guard-1 fix):

```java
          // Alternation priority conflict without quantified capturing groups: PikeVM gives
          // correct first-alternative NFA semantics regardless of whether an anchor is present.
          // Outer quantifiers on capturing groups are excluded — those can diverge in PikeVM
          // (fuzz finding: ([^a]{0,}\z|.){1,}).
          if (!hasQuantifiedCapturingGroup(ast)) {
```

Replace the comment + condition with:

```java
          // Alternation priority conflict: PikeVM gives correct first-alternative NFA semantics.
          // Exclude quantified capturing groups with complex bodies (nested quantifiers or anchors
          // inside the group) — those can diverge in PikeVM (fuzz finding: ([^a]{0,}\z|.){1,}).
          // Simple bodies like (a|b)+x are safe: no inner quantifier, no inner anchor.
          if (!hasComplexQuantifiedCapturingGroup(ast)) {
```

Leave the MatchingStrategyResult return and everything after unchanged.

- [ ] **Step 3: Run the spike tests — simpleGroup_routesToPikeVm must now pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.QuantifiedGroupAltPriorityTest' 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL, all tests PASS.

> If any `simpleGroup_agreesWithJdk` test FAILS after the code change: re-add `hasQuantifiedCapturingGroup` to the exclusion for that specific failing pattern class, `@Disabled` the corresponding `routesToPikeVm` test, and report DONE_WITH_CONCERNS.

- [ ] **Step 4: Run the full runtime + codegen suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Run the fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|repro\]|BUILD" | head -8`
Expected: `findings=0`, BUILD SUCCESSFUL.

> If `findings > 0`: check the repro patterns. If they have inner quantifiers or anchors in the group body, `hasComplexQuantifiedCapturingGroup` should have blocked them. Investigate why it didn't and fix the helper. Report DONE_WITH_CONCERNS.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
git commit -m "fix: route simple-body quantified-group alternation conflicts to PIKEVM"
```

---

## Self-Review Checklist

- [ ] `hasComplexQuantifiedCapturingGroup` returns false when there is NO quantified capturing group (same as original `hasQuantifiedCapturingGroup` returning false) — so the existing PIKEVM route for no-group patterns is preserved.
- [ ] `(a|b)+x`: body `a|b`, `containsAnyQuantifier = false`, `containsAnchorInSubtree = false` → `hasComplexQuantifiedCapturingGroup = false` → PIKEVM ✓
- [ ] `([^a]{0,}\z|.){1,}`: body has `{0,}` (quantifier) AND `\z` (anchor) → `hasComplexQuantifiedCapturingGroup = true` → fallback ✓
- [ ] `(a+|b)+x`: body `a+|b` has `a+` (inner quantifier) → `hasComplexQuantifiedCapturingGroup = true` → fallback ✓ (conservative)
- [ ] `^(a|b)+x`: anchor is outside the group, group body `a|b` has no inner quantifier/anchor → `hasComplexQuantifiedCapturingGroup = false` → PIKEVM ✓
- [ ] `containsAnchorInSubtree` is a minimal private static helper — does not modify any state.
- [ ] `containsAnyQuantifier` reused from line 1339 — not duplicated.
- [ ] Fuzz gate `findings=0` is the definitive correctness check.
