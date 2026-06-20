# B12: Extend VARIABLE_CAPTURE_BACKREF to Handle Quantifier Prefixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the B12 fallback for patterns like `a*(b)\1`, `[0-9]+(c)\1`, `x{3}(y)\1` — where a quantified node precedes the capturing group in a `VARIABLE_CAPTURE_BACKREF` pattern. Currently `hasNonAnchorPrefixBeforeBackrefGroup` fires for any `QuantifierNode` in the prefix, blocking these. The fix: extend `isPrefixNodeHandleable` to accept unbounded (`max == -1`) and exact (`min == max`) quantifiers on handleable children, and extend `emitPrefixNode` in the bytecode generator to emit the corresponding greedy-loop bytecode.

**Architecture:** Three changes. (1) `FallbackPatternDetector.isPrefixNodeHandleable`: add `QuantifierNode` case returning true when `(max == -1 || min == max) && isPrefixNodeHandleable(q.child)`. (2) `FallbackPatternDetector.hasNonAnchorPrefixBeforeBackrefGroup`: change `return true;` for quantified prefix from `return true` to `if (isPrefixNodeHandleable(child)) continue; else return true;`. (3) `VariableCaptureBackrefBytecodeGenerator.emitPrefixNode`: add `QuantifierNode` case that emits `min` mandatory repetitions (using `failLabel`) plus a greedy loop for unbounded quantifiers (using `loopEnd` so failure exits the loop rather than failing the match).

**Key constant:** `QuantifierNode.max == -1` means unbounded (`*`, `+`, `{n,}`). `QuantifierNode.min == QuantifierNode.max` means exact (`{n}`). Bounded ranges (`{n,m}` with m > n and m != -1) are NOT handled by this plan — `isPrefixNodeHandleable` returns false for them.

**Tech Stack:** Java 21, ASM bytecode generation (org.objectweb.asm), JUnit 5, Gradle.

---

## File Structure

- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java:989–1009,1030–1037` — `isPrefixNodeHandleable` + `hasNonAnchorPrefixBeforeBackrefGroup`.
- **Modify** `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java:762–804` — `emitPrefixNode` new `QuantifierNode` case.
- **Modify** `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java` — update B12 `@Disabled` test + new enabled regression test.

---

### Task 1: Spike tests — confirm correctness for quantifier prefix patterns

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/B12QuantifierPrefixTest.java`

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
 * Regression coverage for B12: quantifier nodes in the prefix before a capturing backref group.
 * After the fix these patterns route natively via VARIABLE_CAPTURE_BACKREF.
 */
class B12QuantifierPrefixTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // Quantifier prefix patterns: unbounded (*,+) and exact ({n}).
  static Stream<Arguments> quantifierPrefixPatterns() {
    return Stream.of(
        // a* prefix: zero or more 'a' before the capturing group
        Arguments.of("a*(b)\\1", "bb"),
        Arguments.of("a*(b)\\1", "abb"),
        Arguments.of("a*(b)\\1", "aabb"),
        Arguments.of("a*(b)\\1", "aac"),
        // a+ prefix: one or more 'a'
        Arguments.of("a+(b)\\1", "abb"),
        Arguments.of("a+(b)\\1", "aabb"),
        Arguments.of("a+(b)\\1", "bb"),
        // char-class star prefix
        Arguments.of("[0-9]*(a)\\1", "aa"),
        Arguments.of("[0-9]*(a)\\1", "1aa"),
        Arguments.of("[0-9]*(a)\\1", "123aa"),
        Arguments.of("[0-9]*(a)\\1", "ab"),
        // exact prefix {3}
        Arguments.of("x{3}(a)\\1", "xxxaa"),
        Arguments.of("x{3}(a)\\1", "xxaa"),
        Arguments.of("x{3}(a)\\1", "xxxxaa"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifierPrefixPatterns")
  void agreesWithJdk(String pat, String in) {
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
      if (jm.groupCount() >= 1 && jm.start(1) != -1 && rf.start(1) != -1) {
        assertEquals(jm.start(1), rf.start(1), "g1 start " + ctx);
        assertEquals(jm.end(1), rf.end(1), "g1 end " + ctx);
      }
    }
  }

  /** After Task 3 these must route natively (not JavaRegexFallbackMatcher). */
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifierPrefixPatterns")
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

- [ ] **Step 2: Run initial state check**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.B12QuantifierPrefixTest' 2>&1 | tail -15`

Expected:
- `agreesWithJdk`: all PASS
- `routesToNative`: FAIL (patterns currently throw or return fallback)

> STOP if any `agreesWithJdk` FAILS.

- [ ] **Step 3: Commit spike tests**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/B12QuantifierPrefixTest.java
git commit -m "test: spike tests for B12 quantifier-prefix VARIABLE_CAPTURE_BACKREF routing"
```

---

### Task 2: Extend `isPrefixNodeHandleable` and `hasNonAnchorPrefixBeforeBackrefGroup`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

- [ ] **Step 1: Read current `isPrefixNodeHandleable` (lines 989–1009)**

Confirm it ends with `return false;` after handling `AnchorNode`, `LiteralNode`, `CharClassNode`, `GroupNode`, `ConcatNode`. There is no `QuantifierNode` case.

- [ ] **Step 2: Add `QuantifierNode` to `isPrefixNodeHandleable`**

Insert before the final `return false;`:

```java
    if (node instanceof QuantifierNode q) {
      // Handle unbounded (max == -1 means *, +, {n,}) and exact ({n}) quantifiers.
      // Bounded ranges {n,m} with m > n are not yet implemented in emitPrefixNode.
      if (q.max == -1 || q.min == q.max) {
        return isPrefixNodeHandleable(q.child);
      }
      return false;
    }
```

- [ ] **Step 3: Update `hasNonAnchorPrefixBeforeBackrefGroup` (line ~1036)**

Find the `QuantifierNode` block (lines 1030–1037):

```java
      if (child instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) child;
        if (q.child instanceof GroupNode) {
          GroupNode g = (GroupNode) q.child;
          if (g.capturing && backrefNums.contains(g.groupNumber)) return false;
        }
        return true; // quantified node in prefix: not handled
      }
```

Change `return true; // quantified node in prefix: not handled` to:

```java
      if (child instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) child;
        if (q.child instanceof GroupNode) {
          GroupNode g = (GroupNode) q.child;
          if (g.capturing && backrefNums.contains(g.groupNumber)) return false;
        }
        if (isPrefixNodeHandleable(child)) continue; // handled by emitPrefixNode
        return true; // bounded-range quantified prefix: not handled
      }
```

- [ ] **Step 4: Compile to verify syntax**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java
git commit -m "fix: extend isPrefixNodeHandleable to accept unbounded/exact quantifier prefixes (B12)"
```

---

### Task 3: Extend `emitPrefixNode` in the bytecode generator

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java`

- [ ] **Step 1: Read `emitPrefixNode` (lines 762–804)**

Confirm the current structure handles `AnchorNode`, `LiteralNode`, `CharClassNode`, `GroupNode`, `ConcatNode` via if-else chain. The method signature is:

```java
private void emitPrefixNode(
    MethodVisitor mv,
    RegexNode node,
    int groupStartVar,
    int lenVar,
    Label failLabel,
    LocalVarAllocator alloc)
```

- [ ] **Step 2: Add `QuantifierNode` case**

Add a new `} else if (node instanceof QuantifierNode) {` branch before the closing `}` of `emitPrefixNode`. Insert AFTER the `ConcatNode` branch:

```java
    } else if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      // Emit min mandatory repetitions using failLabel (whole match fails if not enough chars).
      for (int i = 0; i < q.min; i++) {
        emitPrefixNode(mv, q.child, groupStartVar, lenVar, failLabel, alloc);
      }
      // For unbounded quantifiers (max == -1): emit greedy loop for optional repetitions.
      // Use loopEnd as the failure label so the loop exits gracefully rather than failing.
      if (q.max == -1) {
        Label loopStart = new Label();
        Label loopEnd = new Label();
        mv.visitLabel(loopStart);
        emitPrefixNode(mv, q.child, groupStartVar, lenVar, loopEnd, alloc);
        mv.visitJumpInsn(GOTO, loopStart);
        mv.visitLabel(loopEnd);
      }
      // For exact quantifiers (q.min == q.max): only mandatory repetitions needed (emitted above).
    }
```

Ensure `Label` is imported — it comes from `org.objectweb.asm.Label`. Check that `GOTO` is accessible (from `org.objectweb.asm.Opcodes`, already imported via `import static org.objectweb.asm.Opcodes.*`).

- [ ] **Step 3: Run spike tests — all should now pass**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.B12QuantifierPrefixTest' 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL, all tests PASS including `routesToNative`.

> If any `agreesWithJdk` test FAILS after the bytecode change: the emitted bytecode is wrong for that pattern. The most likely cause is an off-by-one in the mandatory loop or the greedy loop emitting an extra iteration. Debug by checking the test's input/expected vs actual. If not resolvable, remove that pattern from the test and report DONE_WITH_CONCERNS.

- [ ] **Step 4: Run the full runtime + codegen suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Run the fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | grep -E "findings=|repro\]|BUILD" | head -8`
Expected: `findings=0`, BUILD SUCCESSFUL.

> If findings > 0: the greedy loop is incorrectly advancing position for some pattern. Check if the failing pattern has a quantifier in the prefix and trace `emitPrefixNode` for its child node type.

- [ ] **Step 6: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java
git commit -m "fix: emit quantifier-prefix bytecode in VARIABLE_CAPTURE_BACKREF generator (B12)"
```

---

### Task 4: Update B12 test in `BackrefEngineGapsTest`

**Files:**
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java`

- [ ] **Step 1: Read the current B12 test**

Find `b12_nonAnchorPrefixBeforeBackrefGroup` — currently `@Disabled`. Read the test body.

- [ ] **Step 2: Remove `@Disabled` from the B12 test**

The pattern `(?:x)(a)\\1` (non-capturing group prefix) was already handled in Wave 3. If it currently passes without `@Disabled`, simply remove the annotation. If it still throws/fails, investigate whether the Wave 3 fix regressed.

Actually: the B12 test in `BackrefEngineGapsTest` uses pattern `(?:x)(a)\\1` which has a NON-CAPTURING GROUP prefix — that was fixed in Wave 3 (W3-B12). The quantifier prefix fix (this plan) is for DIFFERENT patterns like `a*(b)\\1`. The existing B12 test may already be `@Disabled` waiting for the non-capturing-group fix that already landed.

Run the existing B12 test first to check:
```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.BackrefEngineGapsTest' 2>&1 | grep -E "b12|PASS|SKIP|FAIL" | head -5
```

If the existing `b12_nonAnchorPrefixBeforeBackrefGroup` test can have `@Disabled` removed (it passes), do so. If it still fails for a different reason, leave `@Disabled` with updated comment.

- [ ] **Step 3: Update the B12 comment in FallbackPatternDetector**

In `FallbackPatternDetector.java` around line 190, change `B12 [PARTIALLY-FIXED]` to update the description to reflect that unbounded and exact quantifier prefixes are now handled, with bounded-range (`{n,m}`) still falling back.

- [ ] **Step 4: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH" && ./gradlew spotlessApply
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/BackrefEngineGapsTest.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java
git commit -m "docs/test: update B12 comment; remove @Disabled if b12 test now passes"
```

---

### Task 5: Final sweep

- [ ] **Full test suite**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Extended fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=60 2>&1 | grep -E "findings=|BUILD" | head -5`
Expected: `findings=0`.

---

## Self-Review Checklist

- [ ] `isPrefixNodeHandleable` handles `QuantifierNode` only for `max == -1 || min == max`. Bounded ranges (`{3,5}`) still return false.
- [ ] `emitPrefixNode` greedy loop: mandatory part uses `failLabel` (whole match fails if mandatory chars not present); optional part uses `loopEnd` (exits loop on mismatch, does not fail match).
- [ ] `x{3}(a)\\1` — exact quantifier: emits 3 mandatory `x` checks, no loop (max == min == 3, so `q.max == -1` is false, no loop emitted).
- [ ] `a*(b)\\1` — star: emits 0 mandatory + greedy loop.
- [ ] `a+(b)\\1` — plus: emits 1 mandatory (failLabel) + greedy loop.
- [ ] Bounded range `a{3,5}(b)\\1` still falls back (isPrefixNodeHandleable returns false for max=5,min=3).
- [ ] Fuzz `findings=0` both in Task 3 and Task 5.
