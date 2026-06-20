# PIKEVM_CAPTURE Anchor Support for Alternation Patterns — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `PikeVMMatcher.find()` so start-anchors (`^`, `\A`) are evaluated against the true search-region start instead of each per-attempt trial start, making PIKEVM_CAPTURE correct for alternation patterns where an anchor guards only one branch.

**Architecture:** The `find()` family walks every candidate start position and currently passes the trial start position as both the thread seed position *and* the `regionStart` anchor reference. `checkAnchor` resolves `START`/`STRING_START` as `pos == regionStart`, so `^`/`\A` succeed at every trial start. The fix threads the search origin (`fromPos`) as a distinct `regionStart` argument through `tryFindAt`/`tryFindMatchAt` → `initClist`/`stepChar`, while keeping the trial position only for seeding and as the loop cursor. No new state, no allocations.

**Tech Stack:** Java 21, Gradle, JUnit 5, ASM 9.7 (engine is interpreted here, not bytecode). Oracle for tests: `java.util.regex`.

---

## Root Cause (evidence)

`reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java`:

- `findStartFrom` (lines 194–200) loops `for (start = fromPos; start <= len; start++)` and calls `tryFindAt(input, start, len)`.
- `tryFindAt` (lines 203–220) calls `initClist(input, tryPos, tryPos, regionEnd)` and `stepChar(ch, pos + 1, input, tryPos, regionEnd)` — passing `tryPos` as the third `regionStart` argument.
- `findMatchResultFrom` (lines 222–229) / `tryFindMatchAt` (lines 231–261) repeat the same pattern.
- `checkAnchor` (lines 398–425): `case START: case STRING_START: return pos == regionStart;`.

Because `regionStart == tryPos` on every attempt, `^`/`\A` return `true` at every trial start position. Concrete divergence: `\Aa|b` on input `"xa"` — JDK finds no match (`\A` only matches at index 0, and `b` is absent); PIKEVM_CAPTURE seeds at `start = 1`, `checkAnchor(STRING_START, pos=1, regionStart=1)` returns `true`, the `\Aa` branch consumes `a`, and the matcher reports a match `[1,2]`.

`matches()` (`runMatches`, lines 149–167) and bounded paths (`matchesBounded`/`matchBounded`, lines 132–143) are unaffected: they seed exactly once with `regionStart` equal to the real region start (`runMatches(..., 0, len)` → `initClist(input, 0, 0, len)`), so `tryPos == regionStart` already holds.

**Scope of fix:** only non-multiline `^` (`START`) and `\A` (`STRING_START`) are affected. `START_MULTILINE`, `END*`, `WORD_BOUNDARY`, `RESET_MATCH` do not compare against `regionStart` in a way that varies with the trial start, so they are already correct.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java` | Interpreted PikeVM engine | Modify `findStartFrom`, `tryFindAt`, `findMatchResultFrom`, `tryFindMatchAt` to thread the search-origin `regionStart` separately from the trial position |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMAnchorFindTest.java` | Engine-level regression tests for anchor-in-alternation under `find()`/`findMatch()` | Create |

This plan covers **only the engine fix and its direct regression tests**. Routing anchor-in-alternation patterns to PIKEVM_CAPTURE (master plan Track 1 Task 2) and removing the `anchorConditionDiluted` JDK route (Track 1 Task 3) are **separate follow-on tasks** in `docs/superpowers/plans/2026-06-10-remaining-fallback-elimination.md`; they are unblocked by this fix but not implemented here. Their integration is what re-validates the zero-divergence fuzz gate against PIKEVM_CAPTURE.

---

### Task 1: Failing regression test for the find() anchor-reference bug

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMAnchorFindTest.java`

This test constructs a `PikeVMMatcher` directly (same idiom as the existing `PikeVMMatcherTest.build`), bypassing strategy routing, so it exercises the engine regardless of whether `PatternAnalyzer` currently routes these patterns elsewhere.

- [ ] **Step 1: Write the failing test**

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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Engine-level regression tests: a start-anchor (^, \A) guarding only one alternation branch must
 * be evaluated against the true search-region start, not against each per-attempt trial start, when
 * running find()/findMatch(). Each case compares the PikeVM result against java.util.regex.
 */
class PikeVMAnchorFindTest {

  /** Build a PikeVMMatcher for the given pattern (bypasses strategy routing). */
  private static PikeVMMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PikeVMMatcher(nfa, pattern);
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean inClass = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++;
        continue;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '(' && !inClass) {
        boolean capturing = !(i + 1 < pattern.length() && pattern.charAt(i + 1) == '?');
        if (capturing) {
          count++;
        }
      }
    }
    return count;
  }

  /** Assert PikeVM find() agrees with JDK on match presence and matched text. */
  private static void assertFindMatchesJdk(String pattern, String input) throws Exception {
    PikeVMMatcher m = build(pattern);
    MatchResult r = m.findMatch(input);
    Matcher oracle = Pattern.compile(pattern).matcher(input);
    if (oracle.find()) {
      assertEquals(
          oracle.start(),
          r == null ? -1 : r.start(),
          "match start for /" + pattern + "/ on \"" + input + "\"");
      assertEquals(
          oracle.group(),
          r == null ? null : input.substring(r.start(), r.end()),
          "matched text for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertNull(r, "expected no match for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  @Test
  void stringStartAnchoredBranchDoesNotMatchAtNonZeroStart() throws Exception {
    // \A only matches at index 0; on "xa" there is no second-branch match, so JDK finds nothing.
    assertFindMatchesJdk("\\Aa|b", "xa");
  }

  @Test
  void caretAnchoredBranchDoesNotMatchAtNonZeroStart() throws Exception {
    // ^ (non-multiline) only matches at index 0; on "xa" JDK finds nothing.
    assertFindMatchesJdk("^a|b", "xa");
  }

  @Test
  void anchoredFirstBranchPreferredAtStart() throws Exception {
    // At index 0 the anchored first branch is leftmost-first; matched text must be "a".
    assertFindMatchesJdk("\\Aa|b", "ab");
    assertFindMatchesJdk("^a|b", "ab");
  }

  @Test
  void secondBranchMatchesWhenAnchoredBranchFails() throws Exception {
    // "ba": \Aa fails at 0 (char 'b'), so JDK finds "b" at [0,1]; PikeVM must agree.
    assertFindMatchesJdk("\\Aa|b", "ba");
    assertFindMatchesJdk("^a|b", "ba");
  }

  @Test
  void anchoredBranchWithQuantifier() throws Exception {
    // Regression for the Task 2 fuzz class: anchor + quantified branch in alternation.
    assertFindMatchesJdk("\\Aa{2,4}|b", "xaa");
    assertFindMatchesJdk("\\Aa{2,4}|b", "aaab");
  }
}
```

- [ ] **Step 2: Run the test and confirm it FAILS on the unfixed engine**

Run:
```bash
./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.PikeVMAnchorFindTest' -i
```
Expected: FAIL. `stringStartAnchoredBranchDoesNotMatchAtNonZeroStart` and `caretAnchoredBranchDoesNotMatchAtNonZeroStart` fail with an assertion like `expected no match for /\Aa|b/ on "xa"` (PikeVM returns a match at `[1,2]`). The remaining tests pass.

> If a test other than the two `…AtNonZeroStart` cases fails, STOP — that signals a second, distinct defect (e.g. priority-cut/anchor interaction) not covered by this root cause. Re-open root-cause investigation before proceeding.

- [ ] **Step 3: Commit the failing test**

```bash
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMAnchorFindTest.java
git commit -m "test: add failing PikeVM find() anchor-reference regression tests"
```

---

### Task 2: Thread the search-origin region start through the find() path

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java:194-261`

- [ ] **Step 1: Fix `findStartFrom` + `tryFindAt`**

Replace the current `findStartFrom` (lines 194–200) and `tryFindAt` (lines 203–220) with:

```java
  private int findStartFrom(String input, int fromPos) {
    int len = input.length();
    for (int start = fromPos; start <= len; start++) {
      if (tryFindAt(input, start, fromPos, len) >= 0) return start;
    }
    return -1;
  }

  /**
   * Try matching starting at {@code tryPos}; returns match-end position or -1. {@code regionStart}
   * is the fixed search-region origin used for start-anchor evaluation (^, \A); it does not move
   * with {@code tryPos}.
   */
  private int tryFindAt(String input, int tryPos, int regionStart, int regionEnd) {
    initClist(input, tryPos, regionStart, regionEnd);

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]]) {
          return pos; // match ends here
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
    }
    return -1;
  }
```

- [ ] **Step 2: Fix `findMatchResultFrom` + `tryFindMatchAt`**

Replace the current `findMatchResultFrom` (lines 222–229) and `tryFindMatchAt` (lines 231–261) with:

```java
  private MatchResult findMatchResultFrom(String input, int fromPos) {
    int len = input.length();
    for (int start = fromPos; start <= len; start++) {
      MatchResult r = tryFindMatchAt(input, start, fromPos, len);
      if (r != null) return r;
    }
    return null;
  }

  private MatchResult tryFindMatchAt(String input, int tryPos, int regionStart, int regionEnd) {
    initClist(input, tryPos, regionStart, regionEnd);

    // Greedy PikeVM rule: when a thread at index t accepts, threads at indices > t (lower priority)
    // cannot produce a better match. Truncate the clist to [0..t-1] so only higher-priority
    // non-accept threads continue. This lets a higher-priority thread that hasn't accepted yet
    // (but will at a later position) override the current accept — giving greedy longest-match from
    // the highest-priority thread (e.g. (_)? prefers consuming _ over the empty match, while
    // (fo|foo) prefers "fo" over "foo" since "fo" is the higher-priority first alternative).
    MatchResult best = null;

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]]) {
          int[] caps = Arrays.copyOf(clistCaptures[t], winCaptures.length);
          caps[1] = pos;
          best = buildResult(input, caps);
          clistSize = t; // discard lower-priority threads (indices > t); keep higher (0..t-1)
          break;
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
      if (clistSize == 0) break;
    }
    return best;
  }
```

> Note: `initClist(input, tryPos, regionStart, regionEnd)` keeps `tryPos` as the second argument (the thread seed / tentative whole-match start, written into `init[0]`), while the third argument now carries the fixed `regionStart`. This is the only behavioral change — `initClist` itself (lines 268–274) is unchanged.

- [ ] **Step 3: Run the Task 1 tests and confirm they PASS**

Run:
```bash
./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.PikeVMAnchorFindTest' -i
```
Expected: PASS (all 5 test methods green).

- [ ] **Step 4: Run the full existing PikeVM test class for no regression**

Run:
```bash
./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.PikeVMMatcherTest' -i
```
Expected: PASS (no regression — `matches()`/bounded paths are unchanged; existing find()/findMatch() cases either had `fromPos == 0` already or no start-anchor branch).

- [ ] **Step 5: Commit**

```bash
git add reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/PikeVMMatcher.java
git commit -m "fix: evaluate PikeVM start-anchors against search-region origin in find()"
```

---

### Task 3: Guard test — matches() and bounded paths remain correct

**Files:**
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMAnchorFindTest.java`

This locks in that the fix did not perturb whole-region semantics, where a start-anchor branch *should* match at the region start.

- [ ] **Step 1: Add the guard tests**

Append these methods inside `PikeVMAnchorFindTest` (before the closing brace):

```java
  @Test
  void matchesRespectsAnchorAtRegionStart() throws Exception {
    // matches() is whole-region: \Aa|b on "a" must match (anchor satisfied at region start 0).
    PikeVMMatcher m = build("\\Aa|b");
    assertEquals(true, m.matches("a"), "\\Aa|b should match \"a\" under matches()");
    assertEquals(true, m.matches("b"), "\\Aa|b should match \"b\" under matches()");
  }

  @Test
  void boundedMatchRespectsAnchorAtRegionStart() throws Exception {
    // matchesBounded over region [2,3] of "xxa": the substring "a" starts the region, \Aa matches.
    PikeVMMatcher m = build("\\Aa|b");
    assertEquals(true, m.matchesBounded("xxa", 2, 3), "region \"a\" should match \\Aa|b");
  }
```

- [ ] **Step 2: Run the test class**

Run:
```bash
./gradlew :reggie-runtime:test --tests 'com.datadoghq.reggie.runtime.PikeVMAnchorFindTest' -i
```
Expected: PASS (all 7 test methods green).

- [ ] **Step 3: Commit**

```bash
git add reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/PikeVMAnchorFindTest.java
git commit -m "test: guard PikeVM matches()/bounded anchor semantics at region start"
```

---

### Task 4: Full regression sweep + zero-divergence gate

The fix changes only the interpreted PikeVM engine. Patterns are not yet *routed* to PIKEVM_CAPTURE for anchor-in-alternation (that is master Track 1 Tasks 2 & 3), so the fuzz gate continues to exercise the existing routing — it must stay at zero, proving no regression.

**Files:** none (verification only)

- [ ] **Step 1: Run the full runtime test module**

Run:
```bash
./gradlew :reggie-runtime:test -i
```
Expected: BUILD SUCCESSFUL, no failing tests.

- [ ] **Step 2: Run the zero-divergence fuzz gate**

Run:
```bash
./gradlew :reggie-integration-tests:test --tests 'com.datadoghq.reggie.integration.AlgorithmicFuzzTest' -i
```
Expected: PASS. `zeroDivergenceGate` reports `findings=0` (76240 checks).

- [ ] **Step 3: Apply formatting before any push**

Run:
```bash
./gradlew spotlessApply
```
Expected: BUILD SUCCESSFUL. If it reformats files, amend the relevant commit.

- [ ] **Step 4: Confirm clean state**

Run:
```bash
git status
```
Expected: only the two committed files appear in history for this branch; working tree clean.

---

## Downstream (separate tasks — NOT in this plan)

This engine fix unblocks, in `docs/superpowers/plans/2026-06-10-remaining-fallback-elimination.md`:

- **Track 1 Task 2** — route non-capturing alternation+anchor patterns (`^a|b`, `a|b

, `\Aa|b`, `a|b\Z`) to PIKEVM_CAPTURE in `PatternAnalyzer`, and relax the corresponding `FallbackPatternDetector`/`RuntimeCompiler` guards. The previously-observed 117 fuzz divergences were caused by the find() anchor-reference bug fixed here; that task re-runs the gate to confirm zero.
- **Track 1 Task 3** — remove the `anchorConditionDiluted` JDK route (`RuntimeCompiler.java:337` and `:609`) in favor of PIKEVM_CAPTURE for the affected anchor-in-alternation patterns.

Those tasks own their own routing edits, regression tests (`FallbackDetectorBugFixTest.nonCapturingAltWithAnchor`, `anchorDilutedResidual`, already committed as test-only in `e5a03f6` / `823ae15`), and gate re-validation. Do not bundle them into this plan.

---

## Self-Review

1. **Spec coverage** — Root cause (find() passing `tryPos` as `regionStart`) → fixed in Task 2 across both find variants. Failing-first test → Task 1. No-regression on whole-region semantics → Task 3. Gate/suite → Task 4. Covered.
2. **Placeholder scan** — No TBD/TODO; every code step shows full code; every command shows expected output.
3. **Type/signature consistency** — `tryFindAt(input, tryPos, regionStart, regionEnd)` and `tryFindMatchAt(input, tryPos, regionStart, regionEnd)` both gain the same 4-arg shape; call sites in `findStartFrom`/`findMatchResultFrom` updated to pass `fromPos`. `initClist(input, tryPos, regionStart, regionEnd)` and `stepChar(ch, pos + 1, input, regionStart, regionEnd)` signatures are unchanged — only the argument value changes from `tryPos` to `regionStart`. `countGroups` helper matches the existing `PikeVMMatcherTest` idiom. `MatchResult`, `m.findMatch`, `m.matches`, `m.matchesBounded` are existing public API.
