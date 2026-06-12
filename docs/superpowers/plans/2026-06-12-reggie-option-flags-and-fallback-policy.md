# ReggieOption Flag Substrate + Fallback Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-purpose `CapturePolicy` enum with one extensible `ReggieOption` flag set carried by `ReggieOptions`, and make runtime compilation **throw** for patterns Reggie cannot compile natively unless `ALLOW_JDK_FALLBACK` is explicitly enabled.

**Architecture:** `ReggieOptions` stays the single public options carrier but holds an `EnumSet<ReggieOption>` instead of a `CapturePolicy` field. Binary behaviors become flags (`CAPTURE_NAMED_ONLY`, `ALLOW_JDK_FALLBACK`); future toggles append enum constants with zero new plumbing. The 6 `JavaRegexFallbackMatcher` construction sites in `RuntimeCompiler` route through one `fallbackOrThrow` helper that throws `UnsupportedPatternException(reason)` when `ALLOW_JDK_FALLBACK` is absent.

**Tech Stack:** Java 21, JUnit 5, Gradle. No new dependencies.

**Breaking change (accepted):** `CapturePolicy` is deleted; `ReggieOptions.capturePolicy(...)` is replaced. Default runtime behavior changes from silent JDK fallback to throwing `UnsupportedPatternException`. API is not frozen — this is intentional.

**Sequencing:** This plan (A) must land before the companion plan `2026-06-12-pikevm-delegating-stub-and-baking.md` (B), which consumes `ReggieOption`.

---

## File Structure

- `reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOption.java` — **new**: the single growable flag enum.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOptions.java` — **modify**: hold `EnumSet<ReggieOption>`; builder `enable/disable` + shortcuts; keep `DEFAULT`.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/CapturePolicy.java` — **delete**.
- `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` — **modify**: cache-key from flags; `fallbackOrThrow` helper; gate the 6 sites; thread `options` into `compileHybrid`.
- Tests: new `ReggieOptionTest`, `FallbackPolicyTest`; migrate existing `CapturePolicy` test references.

---

### Task 1: Introduce `ReggieOption` enum

**Files:**
- Create: `reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOption.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/ReggieOptionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.datadoghq.reggie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReggieOptionTest {
  @Test
  void enumHasCaptureAndFallbackFlags() {
    EnumSet<ReggieOption> all = EnumSet.allOf(ReggieOption.class);
    assertEquals(true, all.contains(ReggieOption.CAPTURE_NAMED_ONLY));
    assertEquals(true, all.contains(ReggieOption.ALLOW_JDK_FALLBACK));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.ReggieOptionTest'`
Expected: FAIL — `ReggieOption` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadoghq.reggie;

/**
 * Extensible set of boolean compilation toggles for {@link ReggieOptions}. Add future on/off
 * behaviors by appending a constant here — no new types or builder plumbing required. Multi-valued
 * or parametric settings (3+ states, numeric thresholds) belong on the {@link ReggieOptions.Builder}
 * as typed fields, not here.
 */
public enum ReggieOption {
  /**
   * Track only named and semantically-required capturing groups (e.g. backreference targets).
   * Absent: track all capturing groups, matching {@code java.util.regex} numbering.
   */
  CAPTURE_NAMED_ONLY,

  /**
   * Permit {@code java.util.regex} fallback for patterns Reggie cannot compile natively. Absent:
   * {@link Reggie#compile(String, ReggieOptions)} throws {@link UnsupportedPatternException} for
   * such patterns instead of returning a JDK-backed matcher.
   */
  ALLOW_JDK_FALLBACK
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.ReggieOptionTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOption.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/ReggieOptionTest.java
git commit -m "feat: add ReggieOption flag enum"
```

---

### Task 2: Rework `ReggieOptions` to carry `EnumSet<ReggieOption>`; delete `CapturePolicy`

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/ReggieOptions.java`
- Delete: `reggie-runtime/src/main/java/com/datadoghq/reggie/CapturePolicy.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/ReggieOptionsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.datadoghq.reggie;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReggieOptionsTest {
  @Test
  void defaultHasNoFlags() {
    assertFalse(ReggieOptions.DEFAULT.has(ReggieOption.CAPTURE_NAMED_ONLY));
    assertFalse(ReggieOptions.DEFAULT.has(ReggieOption.ALLOW_JDK_FALLBACK));
  }

  @Test
  void enableSetsFlag() {
    ReggieOptions o = ReggieOptions.builder().enable(ReggieOption.ALLOW_JDK_FALLBACK).build();
    assertTrue(o.has(ReggieOption.ALLOW_JDK_FALLBACK));
    assertFalse(o.has(ReggieOption.CAPTURE_NAMED_ONLY));
  }

  @Test
  void shortcutsCompose() {
    ReggieOptions o = ReggieOptions.builder().namedOnly().allowJdkFallback().build();
    assertTrue(o.has(ReggieOption.CAPTURE_NAMED_ONLY));
    assertTrue(o.has(ReggieOption.ALLOW_JDK_FALLBACK));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.ReggieOptionsTest'`
Expected: FAIL — `has`, `enable`, `namedOnly`, `allowJdkFallback` do not exist.

- [ ] **Step 3: Replace `ReggieOptions.java` body**

Replace the class body (keep the license header) with:

```java
package com.datadoghq.reggie;

import java.util.EnumSet;

/** Options for runtime Reggie compilation. Toggles are expressed as {@link ReggieOption} flags. */
public final class ReggieOptions {
  public static final ReggieOptions DEFAULT = builder().build();

  private final EnumSet<ReggieOption> options;

  private ReggieOptions(Builder builder) {
    this.options = EnumSet.copyOf(builder.options);
  }

  /** Returns {@code true} if {@code option} is enabled. */
  public boolean has(ReggieOption option) {
    return options.contains(option);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final EnumSet<ReggieOption> options = EnumSet.noneOf(ReggieOption.class);

    private Builder() {}

    /** Enable one or more flags. */
    public Builder enable(ReggieOption... os) {
      for (ReggieOption o : os) {
        options.add(o);
      }
      return this;
    }

    /** Disable one or more flags. */
    public Builder disable(ReggieOption... os) {
      for (ReggieOption o : os) {
        options.remove(o);
      }
      return this;
    }

    /** Shortcut for {@code enable(CAPTURE_NAMED_ONLY)}. */
    public Builder namedOnly() {
      return enable(ReggieOption.CAPTURE_NAMED_ONLY);
    }

    /** Shortcut for {@code enable(ALLOW_JDK_FALLBACK)}. */
    public Builder allowJdkFallback() {
      return enable(ReggieOption.ALLOW_JDK_FALLBACK);
    }

    public ReggieOptions build() {
      return new ReggieOptions(this);
    }
  }
}
```

> Note: `EnumSet.copyOf` rejects an empty source. Because the builder's set is created with `EnumSet.noneOf(ReggieOption.class)` it is always a valid `EnumSet` (empty is fine for `copyOf` of an `EnumSet`, which uses the `Collection` overload that preserves the element type). Verified by `defaultHasNoFlags`.

- [ ] **Step 4: Delete `CapturePolicy.java`**

```bash
git rm reggie-runtime/src/main/java/com/datadoghq/reggie/CapturePolicy.java
```

- [ ] **Step 5: Run test to verify it passes (RuntimeCompiler will not yet compile — that is Task 3)**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:compileJava 2>&1 | head -20`
Expected: FAIL — `RuntimeCompiler` still references `CapturePolicy` (lines ~20, 187, 189, 213, 325). This is expected; fixed in Task 3. Do not commit yet.

---

### Task 3: Migrate `RuntimeCompiler` to flags + gate the 6 fallback sites

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackPolicyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.Test;

class FallbackPolicyTest {
  // A pattern that routes to a JavaRegexFallbackMatcher site (capture-ambiguous, B-class).
  // \1 backref to a variable-length group forces a fallback reason in compileInternal.
  private static final String FALLBACK_PATTERN = "([a-z]{3}).*\\1";

  @Test
  void throwsByDefault() {
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile(FALLBACK_PATTERN, ReggieOptions.DEFAULT));
    assertFalse(ex.getMessage().isEmpty());
  }

  @Test
  void delegatesWhenFallbackEnabled() {
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = Reggie.compile(FALLBACK_PATTERN, opts);
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    // Behaves like JDK.
    assertEquals(
        java.util.regex.Pattern.compile(FALLBACK_PATTERN).matcher("abcxabc").find(),
        m.find("abcxabc"));
  }

  @Test
  void nativePatternUnaffected() {
    // A plainly-native pattern still compiles with DEFAULT options and is not a fallback matcher.
    ReggieMatcher m = Reggie.compile("\\d{3}-\\d{3}-\\d{4}", ReggieOptions.DEFAULT);
    assertFalse(m instanceof JavaRegexFallbackMatcher);
  }
}
```

> If `FALLBACK_PATTERN` does not actually reach a fallback site in the current engine, pick any pattern from `NFAFallbackPatterns.java` whose comment says it routes to `JavaRegexFallbackMatcher` (e.g. a `VARIABLE_CAPTURE_BACKREF`/capture-ambiguous case). Confirm by temporarily asserting `instanceof JavaRegexFallbackMatcher` under `allowJdkFallback()` before writing the throw path.

- [ ] **Step 2: Run test to verify it fails**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.FallbackPolicyTest'`
Expected: FAIL (compilation: `RuntimeCompiler` still imports `CapturePolicy`; and no throw path yet).

- [ ] **Step 3: Replace the `CapturePolicy` import and cache-key logic**

In `RuntimeCompiler.java`:

Replace the import `import com.datadoghq.reggie.CapturePolicy;` with:

```java
import com.datadoghq.reggie.ReggieOption;
```

Replace the cache-key block at lines 186-189:

```java
    String cacheKey = cacheKeyFor(pattern, options);
```

Replace the ternary at lines 213-215 (inside `computeIfAbsent`) with a single call passing the real options through (no special-casing ALL vs other):

```java
    ReggieMatcher compiled =
        PATTERN_CACHE.computeIfAbsent(cacheKey, k -> compileInternal(pattern, options, k));
```

Replace the `NAMED_ONLY` check at line 325:

```java
      if (options.has(ReggieOption.CAPTURE_NAMED_ONLY)) {
```

Add a private cache-key helper (place it next to the other private statics, e.g. just above `compileInternal`):

```java
  /**
   * Cache key derived from the pattern plus any non-default flags. Flags are appended in enum
   * declaration order so the key is stable. {@code ALLOW_JDK_FALLBACK} is included because it
   * changes the compiled result (JDK matcher vs. thrown exception).
   */
  private static String cacheKeyFor(String pattern, ReggieOptions options) {
    StringBuilder sb = null;
    for (ReggieOption o : ReggieOption.values()) {
      if (options.has(o)) {
        if (sb == null) {
          sb = new StringBuilder(pattern);
        }
        sb.append(' ').append(o.name());
      }
    }
    return sb == null ? pattern : sb.toString();
  }
```

- [ ] **Step 4: Add the `fallbackOrThrow` helper**

Add to `RuntimeCompiler` (private static):

```java
  /**
   * Either returns a {@link JavaRegexFallbackMatcher} (when {@code ALLOW_JDK_FALLBACK} is enabled)
   * or throws {@link UnsupportedPatternException} with the same reason. Centralizes the fallback
   * policy for every site that cannot be compiled natively.
   */
  private static ReggieMatcher fallbackOrThrow(
      String pattern, String reason, Map<String, Integer> nameMap, ReggieOptions options) {
    if (!options.has(ReggieOption.ALLOW_JDK_FALLBACK)) {
      throw new UnsupportedPatternException(reason);
    }
    ReggieMatcher fallback = new JavaRegexFallbackMatcher(pattern, reason);
    if (nameMap != null && !nameMap.isEmpty()) {
      fallback.setNameToIndex(nameMap);
    }
    return fallback;
  }
```

Add `import com.datadoghq.reggie.UnsupportedPatternException;` if not already present.

- [ ] **Step 5: Route the 4 sites inside `compileInternal` through the helper**

Replace each of the four blocks (currently at lines 356-363, 364-372, 378-386, 396-403) with single returns. Example for `anchorConditionDiluted`:

```java
      if (result.anchorConditionDiluted) {
        return fallbackOrThrow(
            pattern, "anchor condition diluted in DFA construction", nameMap, options);
      }
      if (result.alternationPriorityConflict) {
        return fallbackOrThrow(
            pattern,
            "alternation priority conflict: DFA longest-match vs NFA first-alternative",
            nameMap,
            options);
      }
      if (result.captureAmbiguous) {
        return fallbackOrThrow(
            pattern,
            "capture-ambiguous group bindings: group spans require java.util.regex semantics",
            nameMap,
            options);
      }
```

And the `FallbackPatternDetector` site (lines 396-403):

```java
      String fallbackReason = FallbackPatternDetector.needsFallback(ast, result.strategy);
      if (fallbackReason != null) {
        return fallbackOrThrow(pattern, fallbackReason, nameMap, options);
      }
```

- [ ] **Step 6: Route the `MethodTooLargeException` catch (line 474) through the helper**

`nameMap` is declared inside the `try` and is not in scope in the `catch`. Pass `null`:

```java
    } catch (org.objectweb.asm.MethodTooLargeException e) {
      // ... keep existing comment ...
      return fallbackOrThrow(
          pattern,
          "generated method too large: "
              + e.getClassName()
              + "."
              + e.getMethodName()
              + e.getDescriptor(),
          null,
          options);
    }
```

Confirm the existing warning/log lines (if any) between the message and the `return` are preserved; only the matcher construction is replaced.

- [ ] **Step 7: Thread `options` into `compileHybrid` and gate site 572**

At the call site (line 407):

```java
        ReggieMatcher hybrid =
            compileHybrid(pattern, ast, nfa, analyzer, result, caseInsensitive, options);
```

In the `compileHybrid` signature (line 558), add the parameter:

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
```

Replace the site at line 572:

```java
    if (dfaResult.anchorConditionDiluted) {
      return fallbackOrThrow(
          pattern, "anchor condition diluted in hybrid DFA build", null, options);
    }
```

- [ ] **Step 8: Build and run the focused tests**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.FallbackPolicyTest' --tests 'com.datadoghq.reggie.ReggieOptionsTest'`
Expected: PASS

- [ ] **Step 9: Migrate existing `CapturePolicy` references and run the full runtime suite**

Find every remaining reference and migrate (`CapturePolicy.NAMED_ONLY` → `ReggieOptions.builder().namedOnly().build()` / `has(ReggieOption.CAPTURE_NAMED_ONLY)`):

```bash
export PATH="/usr/local/datadog/bin:$PATH"
grep -rn "CapturePolicy\|capturePolicy(" reggie-runtime reggie-integration-tests reggie-benchmark --include=*.java
```

Migrate each hit, then:

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test :reggie-codegen:test`
Expected: BUILD SUCCESSFUL, 0 failures.

> **Behavior-change triage:** Some existing tests may have implicitly relied on silent JDK fallback under default options and will now see `UnsupportedPatternException`. For each such failure, decide: (a) the test asserts a genuinely-native pattern → it is a real regression, investigate; or (b) the test feeds a known-fallback pattern with default options → update it to `.allowJdkFallback()`. Do **not** blanket-add `allowJdkFallback()` to silence failures — each one is a signal about a FULL_FALLBACK pattern.

- [ ] **Step 10: Run the zero-divergence fuzz gate**

Run: `export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-integration-tests:test -Dreggie.fuzz.durationSeconds=30 2>&1 | tail -20`
Expected: `findings=0`.

> If the fuzzer compiles arbitrary patterns with default options, it will now throw on fallback patterns instead of comparing against JDK. Confirm whether the fuzz harness should run with `allowJdkFallback()` (to preserve divergence comparison over the fallback set) or treat a thrown `UnsupportedPatternException` as "skip, not a finding". Choose the former unless the harness already excludes fallback patterns; wire it through the harness options, not by weakening the gate.

- [ ] **Step 11: spotlessApply + commit**

```bash
export PATH="/usr/local/datadog/bin:$PATH"
./gradlew spotlessApply
git add -A
git commit -m "feat: ReggieOption flags + throw-by-default fallback policy"
```

---

## Self-Review Checklist (run after implementing all tasks)

- [ ] Every `new JavaRegexFallbackMatcher(...)` in `RuntimeCompiler` now goes through `fallbackOrThrow` (grep confirms 0 direct constructions outside the helper).
- [ ] `CapturePolicy` has no remaining references anywhere (`grep -rn CapturePolicy` returns nothing).
- [ ] Cache key includes `ALLOW_JDK_FALLBACK` so the same pattern can both throw (default) and return a JDK matcher (enabled) without cache aliasing.
- [ ] Method names consistent: `has`, `enable`, `disable`, `namedOnly`, `allowJdkFallback`, `fallbackOrThrow`, `cacheKeyFor`.
- [ ] Fuzz gate `findings=0`.
