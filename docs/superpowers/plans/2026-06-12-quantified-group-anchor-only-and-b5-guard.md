# Quantified Group: Anchor-Only Exclusion + B5 Lazy Backref Guard

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two targeted fixes. (1) Remove `containsAnyQuantifier` from the `hasComplexQuantifiedCapturingGroup` gate so `(a+|b)+x` routes to PIKEVM instead of falling back — anchors inside the group body are the actual danger, not nested quantifiers. (2) Add `VARIABLE_CAPTURE_BACKREF` to the `hasLazyQuantifier` guard in `FallbackPatternDetector` so lazy-backref patterns like `(a+?)\1` throw `UnsupportedPatternException` instead of silently producing greedy (wrong) spans.

**Architecture:** Two single-line changes in two different files. (1) `PatternAnalyzer.java` line 1474: remove `containsAnyQuantifier(g.child) ||`. (2) `FallbackPatternDetector.java` near line 97: add `VARIABLE_CAPTURE_BACKREF` to the strategy set checked by the `hasLazyQuantifier` guard.

**Tech Stack:** Java 21, JUnit 5, Gradle.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:1474` — remove `containsAnyQuantifier(g.child) ||`.
- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` (near line 97) — add `VARIABLE_CAPTURE_BACKREF` to the lazy-quantifier guard.
- **Modify** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java` — add `(a+|b)+x` type patterns to `simpleQuantifiedGroupPatterns`.
- **Modify** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java` — update the B5 `@Disabled` test to assert the guard fires (throws/falls back correctly).

---

### Task 1: Remove `containsAnyQuantifier` from `hasComplexQuantifiedCapturingGroup`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java:1474`
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java`

**Why safe:** The fuzz divergence that prompted `hasComplexQuantifiedCapturingGroup` was `([^a]{0,}\z|.){1,}` — it has `\z` (anchor) inside the group body. Patterns like `(a+|b)+x` have no anchor in the group body; PikeVM's per-thread simulation handles them correctly.

- [ ] **Step 1: Add new test cases to `QuantifiedGroupAltPriorityTest.java`**

In the `simpleQuantifiedGroupPatterns()` source method, add:

```java
        // Inner quantifiers but no anchor — safe for PIKEVM
        Arguments.of("(a+|b)+x", "ax"),
        Arguments.of("(a+|b)+x", "abx"),
        Arguments.of("(a+|b)+x", "aabx"),
        Arguments.of("(a+|b)+x", "x"),
        Arguments.of("(a+|ab)+c", "ac"),
        Arguments.of("(a+|ab)+c", "abc"),
        Arguments.of("(a+|ab)+c", "aabc")
```

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.QuantifiedGroupAltPriorityTest' 2>&1 | tail -10`

Expected: `simpleGroup_agreesWithJdk` PASS for new cases; `simpleGroup_routesToPikeVm` FAIL for them (still throws/fallback). Confirm no `_agreesWithJdk` regressions.

- [ ] **Step 2: Apply the one-line change in PatternAnalyzer**

In `PatternAnalyzer.java` at line 1474, change:

```java
      if (containsAnyQuantifier(g.child) || containsAnchorInSubtree(g.child)) {
```

To:

```java
      if (containsAnchorInSubtree(g.child)) {
```

That removes `containsAnyQuantifier(g.child) ||`. The anchor check remains unchanged.

- [ ] **Step 3: Verify all tests pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.QuantifiedGroupAltPriorityTest' 2>&1 | tail -10`

Expected: BUILD SUCCESSFUL, all tests PASS including new `(a+|b)+x` cases.

> If any `simpleGroup_agreesWithJdk` test FAILS: re-add `containsAnyQuantifier(g.child) ||`, mark those patterns `@Disabled`, and report DONE_WITH_CONCERNS.

- [ ] **Step 4: Run full runtime + codegen suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Run fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|repro\]|BUILD" | head -8`
Expected: `findings=0`, BUILD SUCCESSFUL.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/QuantifiedGroupAltPriorityTest.java
git commit -m "fix: anchor-only exclusion for complex quantified group; enable (a+|b)+ PIKEVM routing"
```

---

### Task 2: Guard lazy backrefs in `VARIABLE_CAPTURE_BACKREF`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java`

**Why needed:** `FallbackPatternDetector.needsFallback` line 97–117 has a `hasLazyQuantifier` guard that fires for `RECURSIVE_DESCENT` and `OPTIMIZED_NFA_WITH_BACKREFS` — but explicitly excludes `VARIABLE_CAPTURE_BACKREF` (comment at line 110). So `(a+?)\1` routes native via `VARIABLE_CAPTURE_BACKREF` and silently returns greedy spans instead of lazy spans. Plan A's `fallbackOrThrow` doesn't catch it because `needsFallback` returns null. Fix: add `VARIABLE_CAPTURE_BACKREF` to the `hasLazyQuantifier` guard so it also throws.

- [ ] **Step 1: Read the exact current block in `FallbackPatternDetector.java`**

Read lines 97–120. It should look like:

```java
    // B5 [NEEDS-RND]: lazy quantifier inside a capturing group that has a backref — the backref
    // engine applies greedy semantics and returns wrong match spans.
    if ((strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS)
        && hasLazyQuantifier(ast)) {
      return "lazy quantifier: requires shortest-match semantics not supported by this strategy";
    }
```

(The exact line numbers and comment text may vary — read the actual file to confirm.)

- [ ] **Step 2: Add `VARIABLE_CAPTURE_BACKREF` to the guard**

Change the strategy condition to include `VARIABLE_CAPTURE_BACKREF`:

```java
    if ((strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF)
        && hasLazyQuantifier(ast)) {
      return "lazy quantifier: requires shortest-match semantics not supported by this strategy";
    }
```

Also update the comment: change `B5 [NEEDS-RND]` to `B5 [PARTIALLY-FIXED]` and update the text to reflect that `VARIABLE_CAPTURE_BACKREF` is now also guarded (throws instead of silent wrong answer), though the underlying lazy-semantics fix still requires R&D.

- [ ] **Step 3: Update `BackrefEngineGapsTest.b5_lazyQuantifierWithBackref`**

Read the current `b5_lazyQuantifierWithBackref` test in `BackrefEngineGapsTest.java`. It is currently `@Disabled`. Keep it `@Disabled` (the native fix is still NEEDS-RND), but add a new companion test that verifies the guard NOW fires (pattern throws/falls back correctly):

```java
  /** B5 guard active: (a+?)\1 now throws or falls back rather than silently giving wrong spans. */
  @Test
  void b5_lazyBackref_guardActive() {
    // With default options: must throw UnsupportedPatternException (not silently wrong).
    assertThrows(
        com.datadoghq.reggie.UnsupportedPatternException.class,
        () -> Reggie.compile("(a+?)\\1"),
        "B5: lazy backref must throw UnsupportedPatternException, not silently produce wrong spans");
    // With ALLOW_JDK_FALLBACK: must return JavaRegexFallbackMatcher (JDK-correct result).
    ReggieMatcher m = Reggie.compile("(a+?)\\1", ReggieOptions.builder().allowJdkFallback().build());
    assertTrue(m instanceof JavaRegexFallbackMatcher, "B5: lazy backref with fallback must use JDK");
  }
```

Add the necessary imports if not already present:
- `import static org.junit.jupiter.api.Assertions.assertThrows;`
- `import static org.junit.jupiter.api.Assertions.assertTrue;`
- `import com.datadoghq.reggie.ReggieOptions;`
- `import com.datadoghq.reggie.UnsupportedPatternException;` (or `com.datadoghq.reggie.UnsupportedPatternException`)

- [ ] **Step 4: Run focused tests**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.BackrefEngineGapsTest.b5_lazyBackref_guardActive' 2>&1 | tail -10`
Expected: PASS.

- [ ] **Step 5: Run the full suite + fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew test 2>&1 | tail -10`

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|BUILD" | head -5`
Expected: BUILD SUCCESSFUL, `findings=0`.

> If the fuzz shows findings: adding `VARIABLE_CAPTURE_BACKREF` to the lazy guard might have blocked some previously-native patterns that were also routing correctly (non-lazy groups that somehow triggered `hasLazyQuantifier`). Investigate the repro patterns.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java
git commit -m "fix: guard lazy quantifier in VARIABLE_CAPTURE_BACKREF (B5: throw not silent wrong)"
```

---

## Self-Review Checklist

- [ ] Task 1: only `containsAnyQuantifier(g.child) ||` removed — `containsAnchorInSubtree(g.child)` still present.
- [ ] `([^a]{0,}\z|.){1,}` still excluded by anchor check. `(a+|b)+x` now routes PIKEVM.
- [ ] Task 2: only `VARIABLE_CAPTURE_BACKREF` added to the strategy condition — `RECURSIVE_DESCENT` and `OPTIMIZED_NFA_WITH_BACKREFS` unchanged.
- [ ] B5 companion test asserts THROW with default options AND JDK-fallback with `ALLOW_JDK_FALLBACK`.
- [ ] Fuzz gate `findings=0` both tasks.
