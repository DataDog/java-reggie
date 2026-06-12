# Track 1 — Capture-Ambiguity Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate 13 silent wrong-answer bugs in `DFA_UNROLLED_WITH_GROUPS` / `DFA_SWITCH_WITH_GROUPS` by detecting capture-ambiguous DFAs during subset construction and routing them to `JavaRegexFallbackMatcher`.

**Architecture:** Add a `captureAmbiguous` flag to `DFA` (mirrors `anchorConditionDiluted`); set it in `SubsetConstructor.buildDFA` when an accepting DFA state's NFA-state set contains threads that disagree about a capturing group's participation; route the flag in `PatternAnalyzer` → `RuntimeCompiler` → `JavaRegexFallbackMatcher`. Extend the fuzz oracle to check `match()` group spans and add a regression-test class for the 13 known repros.

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Gradle multi-project build, ASM bytecode generation. No new dependencies. All files are in modules `reggie-codegen`, `reggie-runtime`, `reggie-integration-tests`, `reggie-processor`.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/automaton/DFA.java` | Modify | Add `captureAmbiguous` boolean field + getter |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/automaton/SubsetConstructor.java` | Modify | Detect ambiguity after constructing each accepting DFA state |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Modify | Add `captureAmbiguous` to `MatchingStrategyResult`; return it when `dfa.isCaptureAmbiguous()` |
| `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` | Modify | Route `result.captureAmbiguous` to `JavaRegexFallbackMatcher` |
| `reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java` | Modify | Reject `captureAmbiguous` at compile time (same as `alternationPriorityConflict`) |
| `reggie-integration-tests/src/main/java/com/datadoghq/reggie/integration/fuzz/RegexFuzzOracle.java` | Modify | Add `match()` group-span comparison block |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/CaptureAmbiguityRegressionTest.java` | Create | Regression test for the 13 known repros |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/StrategyCorrectnessMetaTest.java` | Modify | Add `(.)?b` to strategy table under `OPTIMIZED_NFA` (routes to fallback, check routing) |

---

## Task 1: Extend `DFA` with `captureAmbiguous` flag

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/automaton/DFA.java`

The `DFA` class already has `anchorConditionDiluted` as a routing flag. We add `captureAmbiguous` with the exact same pattern: constructor parameter with default `false`, getter, no change to `DFAState` or `DFATransition`.

- [ ] **Step 1: Read current DFA constructor signature**

Current `DFA.java` has two constructors. The four-arg constructor is:
```java
public DFA(DFAState startState, Set<DFAState> acceptStates, List<DFAState> allStates, boolean anchorConditionDiluted)
```

- [ ] **Step 2: Add `captureAmbiguous` field and constructor overload**

In `DFA.java`, after the `anchorConditionDiluted` field declaration (line ~35), add:
```java
private final boolean captureAmbiguous;
```

Add a five-arg constructor after the four-arg constructor (around line 50):
```java
public DFA(
    DFAState startState,
    Set<DFAState> acceptStates,
    List<DFAState> allStates,
    boolean anchorConditionDiluted,
    boolean captureAmbiguous) {
  this.startState = startState;
  this.acceptStates = acceptStates;
  this.allStates = allStates;
  this.anchorConditionDiluted = anchorConditionDiluted;
  this.captureAmbiguous = captureAmbiguous;
}
```

Add the getter after `isAnchorConditionDiluted()`:
```java
public boolean isCaptureAmbiguous() {
  return captureAmbiguous;
}
```

The existing four-arg constructor must delegate to the five-arg one with `false`:
```java
public DFA(
    DFAState startState,
    Set<DFAState> acceptStates,
    List<DFAState> allStates,
    boolean anchorConditionDiluted) {
  this(startState, acceptStates, allStates, anchorConditionDiluted, false);
}
```

- [ ] **Step 3: Compile to verify no errors**

Run:
```
./gradlew :reggie-codegen:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 2: Detect capture ambiguity in `SubsetConstructor`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/automaton/SubsetConstructor.java`

The detection logic: after an accepting DFA state is created (where `accepting == true`), examine its NFA-state set. For each capturing group `g` (1-based, up to `nfa.getGroupCount()`), check whether the NFA-state set simultaneously contains:
- At least one `NFAState` with `exitGroup == g` (meaning group `g`'s exit marker is "live" in this DFA state — a thread through group `g` is tracked here), AND
- At least one NFA accept state (from `nfa.getAcceptStates()`) that is **not** reachable through group `g`'s enter state — i.e., an accept state that reached acceptance by bypassing group `g`.

A conservative but correct approximation for "accept state that bypassed group `g`": find accept states in the closure that have no `exitGroup == g` marker anywhere between them and the entry of the DFA state. The simplest valid proxy: the NFA-state set contains an accept state AND a state with `exitGroup == g` whose NFA-state id is LOWER than the accept state's id — meaning the group-exit thread has higher NFA priority than the bypassing-accept thread, but both are alive. When both paths exist, the lowest-state-id heuristic will pick the wrong binding.

The cleanest implementation: for group `g`, the NFA-state set is ambiguous if:
1. It contains any state `s` with `s.exitGroup == g`, AND
2. It contains any accept state reachable WITHOUT going through a state with `enterGroup == g`.

To check (2) efficiently: the NFA accept states in the closure that have `enterGroup == null` (or whose path doesn't include an `enterGroup == g` marker) are the bypass threads. We can over-approximate: if the NFA-state set contains both a state with `exitGroup == g` AND a direct accept state (i.e., `nfa.getAcceptStates().contains(nfaState)`) that does NOT have `exitGroup == g`, then group `g` is ambiguously bound in this accepting DFA state.

This is conservative (may over-detect) but correct — over-detection only causes unnecessary JDK fallback, not wrong answers.

- [ ] **Step 1: Add `captureAmbiguous` instance field**

In `SubsetConstructor.java`, after the `anchorConditionDiluted` field declaration (line ~29):
```java
private boolean captureAmbiguous;
```

- [ ] **Step 2: Reset it in `buildDFA` initialization**

In `buildDFA(NFA nfa, boolean computeTags)`, after the line `this.anchorConditionDiluted = false;` (line ~47):
```java
this.captureAmbiguous = false;
```

- [ ] **Step 3: Add the detection helper method**

Add this private method to `SubsetConstructor` (after `computeGroupActions`, around line 491):

```java
/**
 * Returns true when the accepting NFA-state set has a capture-ambiguity for any group:
 * there is a thread that exits group {@code g} (participated) alongside a direct accept
 * state that did not exit group {@code g} (bypassed it). The lowest-state-id heuristic in
 * {@link #computeGroupActions} cannot choose the correct binding in this case.
 *
 * <p>Conservative: may over-detect (false positives cause unnecessary JDK fallback;
 * under-detection would silently produce wrong answers). Always prefer false positives here.
 */
private boolean hasCaptureAmbiguity(
    Set<NFA.NFAState> nfaStates, Set<NFA.NFAState> acceptStates, int groupCount) {
  if (groupCount == 0) return false;
  for (int g = 1; g <= groupCount; g++) {
    boolean hasGroupExit = false;
    boolean hasNonGroupAccept = false;
    for (NFA.NFAState s : nfaStates) {
      if (s.exitGroup != null && s.exitGroup == g) {
        hasGroupExit = true;
      }
      if (acceptStates.contains(s) && (s.exitGroup == null || s.exitGroup != g)) {
        hasNonGroupAccept = true;
      }
      if (hasGroupExit && hasNonGroupAccept) return true;
    }
  }
  return false;
}
```

- [ ] **Step 4: Call the helper for each new accepting DFA state in the worklist loop**

In `buildDFA`, inside the `if (target == null)` block (around lines 129–148), right after `target` is created and before it is added to the worklist:

Current code (around line 135–147):
```java
target =
    new DFA.DFAState(
        nextStateId++,
        targets,
        accepting,
        new ArrayList<>(),
        groupActions,
        targetAcceptConditions);
stateCache.put(targets, target);
allStates.add(target);
dfaStateConditions.put(target, targetsWithCond);
worklist.add(target);
```

After the `new DFA.DFAState(...)` call and before `stateCache.put`, add:
```java
if (accepting && !captureAmbiguous) {
  captureAmbiguous = hasCaptureAmbiguity(targets, nfa.getAcceptStates(), nfa.getGroupCount());
}
```

Also do the same check for the **start state** (created before the worklist loop, lines ~60–73). After the `start` DFAState is created:
```java
if (startAccepting && !captureAmbiguous) {
  captureAmbiguous =
      hasCaptureAmbiguity(startClosureSet, nfa.getAcceptStates(), nfa.getGroupCount());
}
```

- [ ] **Step 5: Pass `captureAmbiguous` to the `DFA` constructor**

At the `return` statement at the end of `buildDFA` (line ~170):
```java
return new DFA(start, acceptStates, allStates, anchorConditionDiluted);
```
Change to:
```java
return new DFA(start, acceptStates, allStates, anchorConditionDiluted, captureAmbiguous);
```

- [ ] **Step 6: Compile to verify**

```
./gradlew :reggie-codegen:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3: Route `captureAmbiguous` in `PatternAnalyzer`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`

`MatchingStrategyResult` already has `anchorConditionDiluted` and `alternationPriorityConflict` booleans. We add `captureAmbiguous` with the same pattern. Then, in `analyzeAndRecommend()`, after building the tagged DFA (around line 763 where the check for `anchorConditionDiluted` and `alternationPriorityConflict` are done for the WITH_GROUPS path), add the `captureAmbiguous` check before the `DFA_UNROLLED_WITH_GROUPS` / `DFA_SWITCH_WITH_GROUPS` return statements.

- [ ] **Step 1: Add `captureAmbiguous` to `MatchingStrategyResult`**

In `PatternAnalyzer.java`, in the `MatchingStrategyResult` class (around line 2130, after `alternationPriorityConflict`):
```java
/**
 * True when subset construction detected that an accepting DFA state has constituent NFA
 * threads that disagree about a capturing group's participation — one thread entered and
 * exited the group, another bypassed it, and both are accepting. The lowest-state-id merge in
 * {@code SubsetConstructor.computeGroupActions} cannot choose the correct binding; callers
 * should route to a correct fallback engine (e.g. {@link JavaRegexFallbackMatcher}).
 */
public boolean captureAmbiguous;
```

- [ ] **Step 2: Add the `captureAmbiguous` guard in the WITH_GROUPS analysis path**

The WITH_GROUPS DFA path is in `analyzeAndRecommend()`. Find the existing `alternationPriorityConflict` check (around line 748–761). After that block (and before the `// DFA with groups: choose strategy` comment at line 763), add:

```java
if (dfa.isCaptureAmbiguous()) {
  MatchingStrategyResult r =
      new MatchingStrategyResult(
          MatchingStrategy.OPTIMIZED_NFA,
          null,
          null,
          false,
          requiredLiterals,
          null,
          needsPosixSemantics);
  r.captureAmbiguous = true;
  return r;
}
```

Note: `MatchingStrategy.OPTIMIZED_NFA` is used as the nominal strategy here (consistent with the existing `alternationPriorityConflict` and `anchorConditionDiluted` patterns); the actual routing to `JavaRegexFallbackMatcher` happens in `RuntimeCompiler` / `ReggieMatcherBytecodeGenerator` where `result.captureAmbiguous` is tested.

- [ ] **Step 3: Compile**

```
./gradlew :reggie-codegen:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 4: Route to `JavaRegexFallbackMatcher` in `RuntimeCompiler`

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java`

The pattern for routing flags is established at lines 311–329. We add the `captureAmbiguous` block immediately after `alternationPriorityConflict` (around line 329).

- [ ] **Step 1: Add fallback routing block**

After the `alternationPriorityConflict` block (after line 329):
```java
if (result.captureAmbiguous) {
  ReggieMatcher fallback =
      new JavaRegexFallbackMatcher(
          pattern,
          "capture-ambiguous group bindings: group spans require java.util.regex semantics");
  if (!nameMap.isEmpty()) {
    fallback.setNameToIndex(nameMap);
  }
  return fallback;
}
```

- [ ] **Step 2: Compile**

```
./gradlew :reggie-runtime:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 5: Reject `captureAmbiguous` at annotation-processing time

**Files:**
- Modify: `reggie-processor/src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java`

The processor rejects `anchorConditionDiluted` and `alternationPriorityConflict` at compile time. We add the same rejection for `captureAmbiguous` (after line 124, the `alternationPriorityConflict` block).

- [ ] **Step 1: Add compile-time rejection**

After the `alternationPriorityConflict` block (around line 125):
```java
if (result.captureAmbiguous) {
  throw new UnsupportedOperationException(
      "Pattern '"
          + pattern
          + "' cannot be compiled at annotation-processing time: capture-ambiguous group"
          + " bindings — the DFA cannot determine the correct group spans. Use"
          + " Reggie.compile() for runtime compilation with automatic fallback.");
}
```

- [ ] **Step 2: Compile all modules**

```
./gradlew :reggie-processor:compileJava
```

Expected: `BUILD SUCCESSFUL`

---

## Task 6: Extend the fuzz oracle with `match()` group-span comparison (RED phase)

**Files:**
- Modify: `reggie-integration-tests/src/main/java/com/datadoghq/reggie/integration/fuzz/RegexFuzzOracle.java`

The parked diff in worktree `agent-a176c65de70edab2f` shows the exact insertion to make. Insert the `match()` block between the existing `matches()` block and the `findMatch()` block (after line 110, before the `findMatch()` try block at line 113).

- [ ] **Step 1: Insert `match()` group-span comparison**

In `RegexFuzzOracle.java`, after the closing `}` of the `matches()` try-catch block (after the `return Result.skipped("matches() threw: " + t);` line), add:

```java
// match() — whole-input match with group spans
try {
  java.util.regex.Matcher jmFull = jdk.matcher(input);
  boolean jdkMatchFull = jmFull.matches();
  MatchResult rm = reggie.match(input);
  boolean reggieMatchFull = rm != null;
  if (jdkMatchFull != reggieMatchFull) {
    findings.add(
        new Finding(
            pattern,
            input,
            String.format(
                "match() boolean differs: jdk=%s reggie=%s", jdkMatchFull, reggieMatchFull)));
  } else if (jdkMatchFull) {
    for (int g = 0; g <= jmFull.groupCount(); g++) {
      int js = jmFull.start(g);
      int je = jmFull.end(g);
      int rs = rm.start(g);
      int re = rm.end(g);
      if (js != rs || je != re) {
        findings.add(
            new Finding(
                pattern,
                input,
                String.format(
                    "match() group %d span differs: jdk=[%d,%d) reggie=[%d,%d)",
                    g, js, je, rs, re)));
      }
    }
  }
} catch (Throwable t) {
  return Result.skipped("match() threw: " + t);
}
```

- [ ] **Step 2: Run the RED phase — confirm 13 findings on unmodified code**

The oracle change must be applied first (Tasks 1–5 not yet applied). Run:

```
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: **FAIL** with ~13 findings whose descriptions contain `match() group`.

If this test method does not exist in `AlgorithmicFuzzTest.java` yet, check it manually by running:
```
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.smokeFuzz*'
```
and look for `match() group N span differs` in the output. Confirm at least the 13 known patterns appear.

---

## Task 7: GREEN phase — verify fixes eliminate all 13 findings

After Tasks 1–6 are all applied:

- [ ] **Step 1: Run the zero-divergence gate**

```
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: **PASS** with 0 findings.

If any pattern still fails, read the failing pattern + input from the test output. It means the `hasCaptureAmbiguity` check didn't catch it. Inspect the NFA-state set for the accepting DFA states of that pattern to understand what disambiguation was missed, then widen the `hasCaptureAmbiguity` predicate.

- [ ] **Step 2: Run smoke fuzz**

```
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.smokeFuzz*'
```

Expected: **PASS** (0 `match() group span differs` findings).

---

## Task 8: Add regression test for the 13 known repros

**Files:**
- Create: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/CaptureAmbiguityRegressionTest.java`

Each test case: compile with `Reggie.compile(pattern)` and `Pattern.compile(pattern)`. Assert:
1. `reggie.matches(input) == jdk.matcher(input).matches()`
2. `reggie.match(input)` — null iff JDK returns no match; if not null, every group span agrees (loop `g` from `0` to `jm.groupCount()`)
3. `reggie.find(input) == jdk.matcher(input).find()`
4. `reggie.findMatch(input)` — null iff JDK `find()` returns false; if not null, group spans agree for all groups

- [ ] **Step 1: Write the regression test**

Use `@MethodSource` instead of `@CsvSource` to avoid CSV-escaping problems with `]`, `{`, `}`, and empty strings in the 13 repro patterns.

Create `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/CaptureAmbiguityRegressionTest.java`:

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

import com.datadoghq.reggie.Reggie;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression test for the 13 capture-ambiguous patterns that produced silent wrong-answer bugs in
 * {@code DFA_UNROLLED_WITH_GROUPS} / {@code DFA_SWITCH_WITH_GROUPS} before Track 1 of the
 * capture-ambiguity fix. Each pattern/input pair verifies that Reggie's result agrees with {@link
 * java.util.regex.Pattern} across {@code matches}/{@code match}/{@code find}/{@code findMatch} and
 * all group spans.
 *
 * <p>After the fix these patterns route to {@code JavaRegexFallbackMatcher}, so correctness is
 * guaranteed by construction. The test exists to: (a) pin the behaviour as a regression guard, and
 * (b) document the exact repros.
 */
public class CaptureAmbiguityRegressionTest {

  /** The 13 known capture-ambiguity repros: [pattern, input]. */
  static Stream<Arguments> repros() {
    return Stream.of(
        Arguments.of("a{1}()|.", "a"),
        Arguments.of("(-{0}])c|[--c]0", "b0"),
        Arguments.of("($)_|", ""),
        Arguments.of("|()", ""),
        Arguments.of("\\A(.)?[_]?", ""),
        Arguments.of("(.)?b{1}", "b"),
        Arguments.of("c|()(1)", "c"),
        Arguments.of("[b]|(])", "b"),
        Arguments.of("[^1]{1}|()c", "a"),
        Arguments.of("(c{0}])?[0-b][c]", "1c"),
        Arguments.of("(0)?\\Z", ""),
        Arguments.of("[^b]|(b)-{0}", "c"),
        Arguments.of("()-{3}|[0-a]", "_"));
  }

  @ParameterizedTest(name = "[{index}] pattern={0} input={1}")
  @MethodSource("repros")
  void captureAmbiguousRepro_agreesWithJdk(String pattern, String input) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);

    // matches()
    assertEquals(
        jdk.matcher(input).matches(),
        reggie.matches(input),
        "matches() disagrees for pattern=" + pattern);

    // match() — full-input match with group spans
    Matcher jmFull = jdk.matcher(input);
    boolean jdkMatchFull = jmFull.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(jdkMatchFull, rm != null, "match() boolean disagrees for pattern=" + pattern);
    if (jdkMatchFull) {
      for (int g = 0; g <= jmFull.groupCount(); g++) {
        assertEquals(
            jmFull.start(g),
            rm.start(g),
            "match() group " + g + " start disagrees for pattern=" + pattern);
        assertEquals(
            jmFull.end(g),
            rm.end(g),
            "match() group " + g + " end disagrees for pattern=" + pattern);
      }
    }

    // find()
    assertEquals(
        jdk.matcher(input).find(),
        reggie.find(input),
        "find() disagrees for pattern=" + pattern);

    // findMatch() — leftmost match with group spans
    Matcher jmFind = jdk.matcher(input);
    boolean jdkFound = jmFind.find();
    MatchResult rfm = reggie.findMatch(input);
    assertEquals(jdkFound, rfm != null, "findMatch() boolean disagrees for pattern=" + pattern);
    if (jdkFound) {
      for (int g = 0; g <= jmFind.groupCount(); g++) {
        assertEquals(
            jmFind.start(g),
            rfm.start(g),
            "findMatch() group " + g + " start disagrees for pattern=" + pattern);
        assertEquals(
            jmFind.end(g),
            rfm.end(g),
            "findMatch() group " + g + " end disagrees for pattern=" + pattern);
      }
    }
  }
}
```

- [ ] **Step 2: Run the regression test**

```
./gradlew :reggie-runtime:test --tests '*CaptureAmbiguityRegressionTest*'
```

Expected: **PASS** — all parameterized cases green.

---

## Task 9: Update `StrategyCorrectnessMetaTest`

**Files:**
- Modify: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/StrategyCorrectnessMetaTest.java`

After the fix, `(.)?b` routes to `OPTIMIZED_NFA` (the nominal strategy set on the `MatchingStrategyResult` when `captureAmbiguous=true`), but `RuntimeCompiler` intercepts it and returns `JavaRegexFallbackMatcher`. The `routeOf()` helper in the meta-test calls `analyzeAndRecommend()` directly and returns `result.strategy`, which is `OPTIMIZED_NFA` for these patterns. So the existing `OPTIMIZED_NFA` entry in the strategy table needs to remain, and no new entry is needed.

However, the `everyStrategyHasRoutableRepresentative` test compares `routeOf(pattern)` against the map key. For `captureAmbiguous` patterns, `routeOf()` returns `OPTIMIZED_NFA` (the nominal result strategy), which already has a representative. So no structural change is needed.

What IS needed: add a comment + a semantic test that `(.)?b` goes through JDK fallback and all 8 methods agree. This can be a standalone `@Test` in the meta-test, NOT a new map entry.

- [ ] **Step 1: Add a targeted test for capture-ambiguous routing**

In `StrategyCorrectnessMetaTest.java`, add after the existing `@Test` methods:

```java
/**
 * Verify that capture-ambiguous patterns (those that would silently produce wrong group spans
 * in the tagged DFA) are routed to a JDK-correct fallback. The representative pattern
 * {@code (.)?b} is the simplest of the 13 known Track-1 repros.
 */
@Test
void captureAmbiguousPattern_routesToFallbackAndAgreesWithJdk() throws Exception {
  String pattern = "(.)?b";
  String[] inputs = {"b", "ab", "x", "", "bé"};
  java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);
  ReggieMatcher reggie = com.datadoghq.reggie.Reggie.compile(pattern);

  for (String input : inputs) {
    // matches()
    assertEquals(
        jdk.matcher(input).matches(),
        reggie.matches(input),
        "matches() disagrees for input=" + input);

    // match() group spans
    java.util.regex.Matcher jm = jdk.matcher(input);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(jdkM, rm != null, "match() boolean disagrees for input=" + input);
    if (jdkM) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start, input=" + input);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end, input=" + input);
      }
    }

    // find()
    assertEquals(
        jdk.matcher(input).find(),
        reggie.find(input),
        "find() disagrees for input=" + input);
  }
}
```

- [ ] **Step 2: Run the meta-test**

```
./gradlew :reggie-runtime:test --tests '*StrategyCorrectnessMetaTest*' -Dreggie.metatest.enforce=true
```

Expected: **PASS** — 0 mismatches.

---

## Task 10: Full validation

- [ ] **Step 1: Zero-divergence gate**

```
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0.

- [ ] **Step 2: Smoke fuzz**

```
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.smokeFuzz*'
```

Expected: PASS.

- [ ] **Step 3: Meta-test**

```
./gradlew :reggie-runtime:test --tests '*StrategyCorrectnessMetaTest*' -Dreggie.metatest.enforce=true
```

Expected: 0 mismatches.

- [ ] **Step 4: Regression test**

```
./gradlew :reggie-runtime:test --tests '*CaptureAmbiguityRegressionTest*'
```

Expected: all cases pass.

- [ ] **Step 5: Full build**

```
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-processor:test :reggie-integration-tests:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: spotlessApply + build**

```
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL, no formatting violations.

---

## StructuralHash Verification

`DFA.captureAmbiguous` is a **routing flag only**. Verify:

1. When `captureAmbiguous = true`, `RuntimeCompiler` returns `JavaRegexFallbackMatcher` before `StructuralHash.compute()` is ever called for those patterns. Check the call order in `RuntimeCompiler`: the `result.captureAmbiguous` check is at step 3.5 (line ~312), before step 4 (hybrid/strategy dispatch at line ~377) where `StructuralHash` is used.
2. `StructuralHash.compute()` reads `result.dfa`, `result.strategy`, DFA topology, NFA content. When `captureAmbiguous=true`, `result.dfa == null` (we return `MatchingStrategy.OPTIMIZED_NFA` with `dfa=null`). So `computeDFATopologyHash` is skipped. No hash poisoning is possible.
3. No new field is added to `DFAState` or `DFATransition`, so the existing hash loops are unaffected.

**Conclusion:** No `StructuralHash` change needed. Two patterns with identical DFA topology but different `captureAmbiguous` values route to different strategies (one native DFA, one JDK fallback) and never share a cache entry.

---

## Scope Guardrails

- Do NOT modify `SubsetConstructor`'s tagged-construction algorithm (`computeTagOperations`, `computeGroupActions`).
- Do NOT weaken `alternationPriorityConflict` or `anchorConditionDiluted` guards.
- `captureAmbiguous` patterns MUST go to FULL_FALLBACK (`JavaRegexFallbackMatcher`), not `OPTIMIZED_NFA`.
- Do NOT commit CLAUDE.md or any hotdog-override.yaml files.
- Run `spotlessApply` before finishing.
