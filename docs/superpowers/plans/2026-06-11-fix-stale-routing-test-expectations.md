# Fix Stale Routing Test Expectations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update four stale strategy-selection assertions in `PatternRoutingPropertyTest` and `PatternRoutingPropertyBasedTest` that reflect superseded routing decisions.

**Architecture:** Three strategy changes underlie all four failures. (A) Capturing alternation+quantifier patterns (`(a|b|c){50}`, `(a|b|c|d|e|f){100}`) now route to `PIKEVM_CAPTURE` instead of the old group-agnostic `DFA_SWITCH`/`OPTIMIZED_NFA` — a correctness improvement, since the old strategies cannot track per-iteration group spans. (B) `(.*)\d+\1` now routes to `SPECIALIZED_BACKREFERENCE` (via `GREEDY_ANY_BACKREF` subtype) instead of `VARIABLE_CAPTURE_BACKREF` — correct because `.*` is nullable (min=0) and `detectVariableCaptureBackref` explicitly rejects nullable groups at line 3030 to prevent spurious zero-length matches. All changes predate this session; the fuzz gate reports findings=0.

**Tech Stack:** JUnit 5, jqwik, Gradle (`./gradlew :reggie-codegen:test`)

---

### Task 1: Fix `PatternRoutingPropertyTest` expectations

**Files:**
- Modify: `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/PatternRoutingPropertyTest.java:146-223`

**Context:** Three assertions are stale in this file.
- Line 154: `(.*)\d+\1` expected `VARIABLE_CAPTURE_BACKREF` — actual is `SPECIALIZED_BACKREFERENCE`. Root cause: `detectVariableCaptureBackref` rejects nullable groups (min=0 on `.*`), so the pattern falls through to `detectGreedyAnyBackrefPattern` within `detectSimpleBackreference`.
- Line 219: `(a|b|c){50}` expected `DFA_SWITCH` — actual is `PIKEVM_CAPTURE`. Root cause: the `quantifiedAltWithGroupBug` PIKEVM sub-case in the capturing TDFA path now claims this pattern before the size-based DFA ladder.
- Line 222: `(a|b|c|d|e|f){100}` expected `OPTIMIZED_NFA` — actual is `PIKEVM_CAPTURE`. Same root cause as above.

- [ ] **Step 1: Update the backref example row**

In `provideBackrefExamples()` (around line 153), change:
```java
        new PatternRoutingTestCase(
            "(.*)\\d+\\1", VARIABLE_CAPTURE_BACKREF, "greedy group with backref"),
```
to:
```java
        new PatternRoutingTestCase(
            "(.*)\\d+\\1",
            SPECIALIZED_BACKREFERENCE,
            "greedy-any backref: nullable (.*) excluded from VARIABLE_CAPTURE_BACKREF"),
```

- [ ] **Step 2: Update the DFA example rows and stale comment**

In `provideDFAExamples()` (around line 212), replace the entire method body:
```java
  static Stream<PatternRoutingTestCase> provideDFAExamples() {
    return Stream.of(
        // DFA_UNROLLED (<20 states)
        new PatternRoutingTestCase(
            "(abc)", DFA_UNROLLED, "capturing group with literal (groups not tracked in DFA)"),

        // Capturing alternation+quantifier patterns are claimed by the quantifiedAltWithGroupBug
        // PIKEVM sub-case before the state-count-based DFA ladder: PIKEVM correctly tracks
        // per-iteration group spans whereas DFA_SWITCH/OPTIMIZED_NFA cannot.
        new PatternRoutingTestCase(
            "(a|b|c){50}", PIKEVM_CAPTURE, "capturing alternation+quantifier (151 DFA states)"),

        new PatternRoutingTestCase(
            "(a|b|c|d|e|f){100}",
            PIKEVM_CAPTURE,
            "capturing alternation+quantifier (601 DFA states)"));
  }
```

- [ ] **Step 3: Run the two failing test classes to confirm they now pass**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test --tests "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, no failures in `BackrefStrategies` or `GenericDFAStrategies`.

- [ ] **Step 4: Commit**

```bash
git add reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/PatternRoutingPropertyTest.java
git commit -m "test: update stale routing assertions in PatternRoutingPropertyTest"
```

---

### Task 2: Fix `PatternRoutingPropertyBasedTest` + full regression sweep

**Files:**
- Modify: `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/pbt/PatternRoutingPropertyBasedTest.java:126-148`

**Context:** `largeStateSpacePatternsUseNfaFallbackOrSpecialized` (line 127) asserts that large-state-space patterns use only `{DFA_SWITCH, SPECIALIZED_QUANTIFIED_GROUP, OPTIMIZED_NFA}`. The `largeStateSpace` arbitrary generates patterns like `(a|b|c){50}`, which now route to `PIKEVM_CAPTURE`. The valid-strategies set and its surrounding comments are both stale.

- [ ] **Step 1: Add `PIKEVM_CAPTURE` to the valid-strategies list and update comments**

Replace lines 126–148:
```java
  @Property(tries = 50) // Fewer tries since these are expensive patterns
  void largeStateSpacePatternsUseNfaFallbackOrSpecialized(
      @ForAll("largeStateSpace") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // Capturing alternation+quantifier patterns are routed to PIKEVM_CAPTURE (correct group spans).
    // Non-capturing large-state patterns use DFA_SWITCH, SPECIALIZED_QUANTIFIED_GROUP, or
    // OPTIMIZED_NFA.
    List<PatternAnalyzer.MatchingStrategy> validStrategies =
        List.of(
            PIKEVM_CAPTURE, // capturing alternation+quantifier: correct per-iteration group spans
            DFA_SWITCH, // medium state count, non-capturing
            SPECIALIZED_QUANTIFIED_GROUP, // specialized path
            OPTIMIZED_NFA // large state-space fallback
            );

    assertTrue(
        validStrategies.contains(result.strategy),
        () ->
            "Large state space pattern: '"
                + pattern
                + "' should use PIKEVM_CAPTURE/DFA_SWITCH/SPECIALIZED_QUANTIFIED_GROUP/OPTIMIZED_NFA, got: "
                + result.strategy);
  }
```

- [ ] **Step 2: Run the PBT class to confirm it passes**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test --tests "com.datadoghq.reggie.codegen.analysis.pbt.PatternRoutingPropertyBasedTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 3: Run the full `reggie-codegen` test suite**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-codegen:test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. Only pre-existing failures (none beyond the 4 just fixed) should remain.

- [ ] **Step 4: Run the runtime suite to confirm no regressions**

```
export PATH="/usr/local/datadog/bin:$PATH"; ./gradlew :reggie-runtime:test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. Pre-existing 8 known failures in `FallbackDetectorBugFixTest` are acceptable; no new failures.

- [ ] **Step 5: Commit**

```bash
git add reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/pbt/PatternRoutingPropertyBasedTest.java
git commit -m "test: add PIKEVM_CAPTURE to valid strategies in PBT large-state test"
```
