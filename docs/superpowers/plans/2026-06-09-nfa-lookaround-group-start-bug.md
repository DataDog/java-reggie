# OPTIMIZED_NFA_WITH_LOOKAROUND Group-Start Recording Bug Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the group-start recording bug in `OPTIMIZED_NFA_WITH_LOOKAROUND` so that capturing groups inside repeating quantifiers report the correct last-iteration span.

**Architecture:** The root cause is that `PatternAnalyzer` always creates `OPTIMIZED_NFA_WITH_LOOKAROUND` results with `usePosixLastMatch=false`, so `NFABytecodeGenerator` never enables its per-configuration group-tracking code for this strategy. The fix is a one-line change per return site in `PatternAnalyzer`: pass `hasGroupsInRepeatingQuantifiers(ast)` as the `usePosixLastMatch` argument. No changes to `NFABytecodeGenerator` are required — the per-config tracking infrastructure already exists and is correct.

**Tech Stack:** Java 21, ASM 9.7, JUnit 5 Jupiter, Gradle 8.11+.

---

## Root Cause (Investigation Summary)

### The bug

`NFABytecodeGenerator.generateEpsilonClosureWithGroups()` (around line 7381) has this code when
`usePosixLastMatch=false`:

```java
// else branch — fires for OPTIMIZED_NFA_WITH_LOOKAROUND
mv.visitVarInsn(ALOAD, groupStartsVar);
pushInt(mv, state.enterGroup);
mv.visitVarInsn(ILOAD, posVar);   // posVar = POST-ADVANCE (after pos++)
mv.visitInsn(IASTORE);
```

This epsilon closure is invoked from the main simulation loop *after* `pos++`, so `posVar` is
`P+1` (post-advance). When the quantifier loop-back epsilon path fires the `enterGroup` state
after the **last** consumed character, it writes `posVar = len` (end of string), overwriting the
correct start of the last iteration.

**Concrete example:** `(?!.*[A-Z])(a)+` on `"aaa"`.

```
Expected (JDK): group 1 = [2, 3)   (last 'a', positions are 0-indexed)
Actual (Reggie): group 1 = [3, 3)  (len = 3 = end of string)
```

The loop-back fires once per iteration. After the 3rd 'a' (posVar advances to 3 = len),
the epsilon closure records `groupStarts[1] = 3`, overwriting the previously-correct `2`.

### Why only OPTIMIZED_NFA_WITH_LOOKAROUND

The non-lookaround path in `PatternAnalyzer.analyzeAndRecommend()` already computes
`boolean needsPosixSemantics = hasGroupsInRepeatingQuantifiers(ast)` at line 694, and passes it
as `usePosixLastMatch` to patterns that reach `OPTIMIZED_NFA`. Patterns with groups-in-quantifiers
are additionally routed to specialised generators (SPECIALIZED_QUANTIFIED_GROUP, etc.) before
falling through to OPTIMIZED_NFA.

The `hasLookaround` branch skips all of this. All five return sites that emit
`OPTIMIZED_NFA_WITH_LOOKAROUND` use the **6-arg constructor** which defaults
`usePosixLastMatch = false`:

```java
// Lines 416, 437, 489, 533, 576 — all identical:
return new MatchingStrategyResult(
    MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
    null, null, false, requiredLiterals, lookaheadGreedyInfo);
//  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//  7th argument (usePosixLastMatch) missing → defaults to false
```

### Why enabling usePosixLastMatch=true fixes it

With `usePosixLastMatch=true` the epsilon closure maintains a **per-NFA-state** group
configuration (`configGroupStarts[state.id][g]`). When the accept state is entered (before the
loop-back fires), its configuration is snapshotted. At match-end the accept state's snapshot is
copied to the global `groupStarts[]` array, correctly reflecting the last *completed* iteration's
start — not the hypothetical next iteration's start that the loop-back overwrites.

---

## File Map

| File | Change | Reason |
|------|--------|--------|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Modify 5 return sites (lines 416, 437, 489, 533, 576) | Pass `hasGroupsInRepeatingQuantifiers(ast)` as `usePosixLastMatch` |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/NfaLookaroundGroupSpanTest.java` | Create | Failing → passing regression tests |

No changes to `NFABytecodeGenerator.java` — the per-config tracking code is already complete and correct.

---

## Task 1 — Write the failing tests

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/NfaLookaroundGroupSpanTest.java`

- [ ] **Step 1: Create the test file**

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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for the group-start recording bug in OPTIMIZED_NFA_WITH_LOOKAROUND.
 *
 * <p>Root cause: the epsilon closure called after pos++ writes posVar (post-advance) for
 * enterGroup states reached via quantifier loop-back. For the last iteration this records
 * posVar=len (end of string), overwriting the correct last-iteration start. Fix: pass
 * usePosixLastMatch=true for OPTIMIZED_NFA_WITH_LOOKAROUND patterns with groups in repeating
 * quantifiers, enabling per-configuration group tracking.
 *
 * <p>All patterns here route to OPTIMIZED_NFA_WITH_LOOKAROUND (verified via debugPattern). No JDK
 * fallback is triggered by FallbackPatternDetector for any of them.
 */
public class NfaLookaroundGroupSpanTest {

  static Stream<Arguments> groupInQuantifier() {
    return Stream.of(
        // Negative lookahead (complex → OPTIMIZED_NFA_WITH_LOOKAROUND), group in +
        Arguments.of("(?!.*[A-Z])(a)+", "aaa"),
        Arguments.of("(?!.*[A-Z])(a)+", "bbb"), // no match
        Arguments.of("(?!.*[A-Z])(a)+", "a"), // single iteration
        Arguments.of("(?!.*[A-Z])(\\w)+", "hello"),
        Arguments.of("(?!.*[A-Z])(\\w)+", "Hello"), // no match (has uppercase)
        // Multiple groups with negative lookahead
        Arguments.of("(?!.*[A-Z])([a-z])+([0-9])+", "abc123"),
        Arguments.of("(?!.*[A-Z])([a-z])+([0-9])+", "abc") // no match (no digit group)
    );
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupInQuantifier")
  void groupSpan_agreesWithJdk_match(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    Matcher jm = jdk.matcher(in);
    boolean jdkMatch = jm.matches();
    MatchResult rm = reg.match(in);

    assertEquals(jdkMatch, rm != null, "match() null check " + ctx);
    if (jdkMatch) {
      assertNotNull(rm);
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end " + ctx);
      }
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupInQuantifier")
  void groupSpan_agreesWithJdk_findMatch(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    Matcher jm = jdk.matcher(in);
    boolean jdkFound = jm.find();
    MatchResult rfm = reg.findMatch(in);

    assertEquals(jdkFound, rfm != null, "findMatch() null check " + ctx);
    if (jdkFound) {
      assertNotNull(rfm);
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rfm.start(g), "findMatch() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rfm.end(g), "findMatch() g" + g + " end " + ctx);
      }
    }
  }
}
```

- [ ] **Step 2: Run the tests — confirm they fail**

```bash
./gradlew :reggie-runtime:test --tests '*.NfaLookaroundGroupSpanTest' -q
```

Expected: **FAIL**. The `groupSpan_agreesWithJdk_match` and `groupSpan_agreesWithJdk_findMatch` parameterized tests for `"aaa"`, `"hello"`, `"abc123"` cases will show assertion failures like:

```
g1 start ==> expected: <2> but was: <3>
```

---

## Task 2 — Fix PatternAnalyzer: pass usePosixLastMatch to all five OPTIMIZED_NFA_WITH_LOOKAROUND return sites

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`

The method `hasGroupsInRepeatingQuantifiers(RegexNode)` already exists on this class (line 1775,
private). It uses `GroupInQuantifierDetector` which returns `true` whenever a capturing group
appears inside a repeating quantifier anywhere in the pattern tree (including inside assertion
sub-patterns via `visitAssertion`).

There are exactly **5** return sites to update. Each currently uses the 6-arg constructor. Change
each to the 7-arg constructor by appending `hasGroupsInRepeatingQuantifiers(ast)`.

- [ ] **Step 1: Update site 1 — `hasBackrefToLookaheadCapture` branch (line 416)**

```java
// Before (line 415–423):
      if (hasBackrefToLookaheadCapture(ast)) {
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo);
      }

// After:
      if (hasBackrefToLookaheadCapture(ast)) {
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      }
```

- [ ] **Step 2: Update site 2 — `hasLookaheadInsideCapturingGroup` branch (line 436)**

```java
// Before (line 435–443):
        if (hasLookaheadInsideCapturingGroup(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo);
        }

// After:
        if (hasLookaheadInsideCapturingGroup(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              hasGroupsInRepeatingQuantifiers(ast));
        }
```

- [ ] **Step 3: Update site 3 — large-DFA no-compatible-lookaheads branch (line 488)**

```java
// Before (line 487–495):
          // No DFA-compatible lookaheads - fall back to pure NFA
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo);

// After:
          // No DFA-compatible lookaheads - fall back to pure NFA
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              hasGroupsInRepeatingQuantifiers(ast));
```

- [ ] **Step 4: Update site 4 — UnsupportedOperationException catch, no compatible lookaheads (line 532)**

```java
// Before (line 531–539):
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo);
      } catch (StateExplosionException e) {

// After:
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      } catch (StateExplosionException e) {
```

- [ ] **Step 5: Update site 5 — StateExplosionException catch, no compatible lookaheads (line 575)**

```java
// Before (line 574–582):
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo);
      }
    }

// After:
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      }
    }
```

- [ ] **Step 6: Compile to verify no errors**

```bash
./gradlew :reggie-codegen:compileJava :reggie-runtime:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3 — Run tests and verify fix

- [ ] **Step 1: Run the new regression tests — confirm they now pass**

```bash
./gradlew :reggie-runtime:test --tests '*.NfaLookaroundGroupSpanTest' -q
```

Expected: **PASS** — all parameterized variants pass.

- [ ] **Step 2: Run the strategy correctness meta-test**

```bash
./gradlew :reggie-runtime:test --tests '*StrategyCorrectnessMetaTest*' -Dreggie.metatest.enforce=true -q
```

Expected: **PASS** — 0 mismatches. The `a(?!\\d+x).*b` pattern (the meta-test's
`OPTIMIZED_NFA_WITH_LOOKAROUND` sample) has no capturing groups, so `hasGroupsInRepeatingQuantifiers`
returns false and its code path is unchanged.

- [ ] **Step 3: Run the full runtime test suite**

```bash
./gradlew :reggie-runtime:test -q
```

Expected: **PASS** — no regressions.

- [ ] **Step 4: Run the zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true -q
```

Expected: **PASS** at 0 divergences.

---

## Task 4 — spotlessApply + full build + commit

- [ ] **Step 1: Format code**

```bash
./gradlew spotlessApply
```

- [ ] **Step 2: Full build**

```bash
./gradlew build -q
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add \
  reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
  reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/NfaLookaroundGroupSpanTest.java
git commit -m "fix: enable per-config group tracking for OPTIMIZED_NFA_WITH_LOOKAROUND with groups in quantifiers"
```

---

## StructuralHash Verification

No new fields are added to `DFAState`, `DFATransition`, `NFAState`, or any `PatternInfo` subclass.
The change in `PatternAnalyzer` only affects the `usePosixLastMatch` flag on `MatchingStrategyResult`,
which is not part of the structural hash (it is an *execution flag*, not a structural descriptor of
the NFA/DFA topology). No `StructuralHash.java` changes are required.

---

## Why This Unblocks Task 1 of the FallbackDetectorBugFixTest Plan

Task 1 of `docs/superpowers/plans/2026-06-09-fallback-detector-bug-fixes.md` intends to route
`DFA_UNROLLED_WITH_ASSERTIONS` patterns that have capturing groups inside quantifiers to
`OPTIMIZED_NFA_WITH_LOOKAROUND`. That routing was blocked because `OPTIMIZED_NFA_WITH_LOOKAROUND`
produced wrong group spans for those patterns (start = end-of-string instead of actual start).

After this fix:
- `usePosixLastMatch=true` is set for `OPTIMIZED_NFA_WITH_LOOKAROUND` when `hasGroupsInRepeatingQuantifiers(ast)` is true
- Patterns like `(?<=a)(x)+` will produce correct spans when routed to this strategy
- Task 1 of the FallbackDetectorBugFixTest plan can proceed

---

## Self-Review Checklist

- ✅ All 5 return sites in `PatternAnalyzer.java` are updated
- ✅ `hasGroupsInRepeatingQuantifiers` is a private instance method accessible from all 5 sites (they are all inside `analyzeAndRecommend()` on the same class)
- ✅ Test covers both `match()` and `findMatch()` group span checks
- ✅ Test covers no-match cases (correctness when pattern doesn't match)
- ✅ Test covers single-iteration case (`"a"`)
- ✅ Test covers multiple capturing groups (`([a-z])+([0-9])+`)
- ✅ `StructuralHash` not affected
- ✅ No new dependencies
- ✅ All patterns in the test route to `OPTIMIZED_NFA_WITH_LOOKAROUND` (verified via `debugPattern`)
