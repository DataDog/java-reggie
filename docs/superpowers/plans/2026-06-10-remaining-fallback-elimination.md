# Remaining JDK Fallback Elimination Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every remaining `JavaRegexFallbackMatcher` route so all accepted patterns run natively with correct JDK-compatible semantics.

**Architecture:** Five tracks ordered by risk and dependency. Track 1 requires only routing changes (no engine work). Tracks 2–3 extend existing engines. Track 4 adds new generators. Track 5 is standalone infrastructure. Each task validates with the zero-divergence fuzz gate before committing.

**Tech Stack:** Java 21, ASM 9.7, JUnit 5. Build: `./gradlew :<module>:test`. Fuzz gate: `./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'`.

---

## Key files

| File | Role |
|---|---|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Strategy selection; all routing decisions live here |
| `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` | Flag-based JDK routing guards (`anchorConditionDiluted`, `alternationPriorityConflict`, `captureAmbiguous`) |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` | AST-level fallback guards called at RuntimeCompiler:381 |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java` | Regression tests for routing changes |
| `reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration/AlgorithmicFuzzTest.java` | Zero-divergence gate (now always enabled) |

---

## Remaining fallback inventory

| # | Flag / condition | PatternAnalyzer site | RuntimeCompiler guard | Description |
|---|---|---|---|---|
| A1 | `alternationPriorityConflict` | ~1014 (non-capturing DFA) | line 345 | Alternation + quantifiers or anchors; non-capturing |
| A2 | `alternationPriorityConflict` | ~855 (capturing TDFA) | line 345 | Capturing alternation with anchors, quantified groups, or nullable branches |
| B1 | `anchorConditionDiluted` | ~990 (non-capturing DFA) | line 337 | DFA structural anchor erasure; no matching AST predicate |
| B2 | `anchorConditionDiluted` | ~802 (capturing TDFA) | line 337 (via compileHybrid:609) | Same in hybrid path |
| C | `captureAmbiguous` | ~643, ~902 | line 357 | NFA bypass ambiguity or TDFA with named groups / anchors |
| D1 | `hasLazyQuantifier` | FallbackPatternDetector:95 | via needsFallback | Lazy quantifiers in RECURSIVE_DESCENT / OPTIMIZED_NFA_WITH_BACKREFS |
| D2 | `hasCrossAlternativeBackref` | FallbackPatternDetector:104 | via needsFallback | Backref in different alternation branch than its group |
| D3 | `hasOuterQuantifierOnBackrefGroup` | FallbackPatternDetector:171 | via needsFallback | `(X)+\1` — outer quantifier wraps capturing group |
| D4 | `hasNullableBackrefGroup` | FallbackPatternDetector:114,122 | via needsFallback | Backref to empty-matching group |
| D5 | `hasNonAnchorPrefixBeforeBackrefGroup` | FallbackPatternDetector:163 | via needsFallback | Non-literal/non-charset prefix before VARIABLE_CAPTURE_BACKREF group |
| D6 | `hasOuterQuantifierOnUnsupportedBackrefGroup` | FallbackPatternDetector:183 | via needsFallback | Nullable or alternation-body group in OPTIONAL_GROUP_BACKREF |
| E1 | `lookaheadInQuantifier` | FallbackPatternDetector:59 | via needsFallback | Lookahead inside quantified group (issue #28) |
| E2 | `hasLookaheadInAlternation` | FallbackPatternDetector:152 | via needsFallback | Lookahead in alternation branch (OPTIMIZED_NFA_WITH_LOOKAROUND) |
| F | `MethodTooLargeException` | RuntimeCompiler:492 | catch block | Generated method exceeds JVM 64KB limit |

Additionally, three OPTIMIZED_NFA guards in `FallbackPatternDetector` prevent wrong native results (these are not JDK routes but block native promotion until the engine is fixed):

| Guard | Line | Engine bug |
|---|---|---|
| `hasStringEndAnchorInAltWithProblematicContext` | 228 | `\Z` in alternation + capturing group / nullable branch |
| `hasStartClassAnchorInAlternationBranch` | 236 | `\A`/`^` in alternation branch + capturing group |
| `hasNullableAlternationBranchAnywhere` | 246 | Nullable alternation branch — wrong find() first-alternative |

---

## Track 1 — Routing extensions (no engine changes)

These require only `PatternAnalyzer` condition changes and fuzz-gate validation. No new bytecode generators needed.

---

### Task 1: Promote non-capturing alternation + quantifiers to PIKEVM_CAPTURE

**Fallback:** A1 — `PatternAnalyzer.java:~1014`, `RuntimeCompiler.java:345`

Current code (lines ~1009–1019):
```java
// Patterns with alternation plus quantifiers or anchors where DFA has
// accepting-state-with-transitions: DFA longest-match semantics diverge from JDK
// first-alternative semantics. Fall back to JDK.
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    MatchingStrategyResult r =
        new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
    r.alternationPriorityConflict = true;
    return r;
}
```

The anchor sub-case needs investigation first (PIKEVM may not handle all anchor+alternation combinations). Split into two sub-cases.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (lines ~1009–1019)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests for non-anchor sub-case**

```java
static Stream<Arguments> nonCapturingAltWithQuantifier() {
    return Stream.of(
        Arguments.of("a?|b",    "a"),
        Arguments.of("a?|b",    "b"),
        Arguments.of("a?|b",    ""),
        Arguments.of("x+|y",    "xx"),
        Arguments.of("x+|y",    "y"),
        Arguments.of("ab?|a",   "a"),
        Arguments.of("ab?|a",   "ab"),
        Arguments.of("(a|b)?c", "c"),
        Arguments.of("(a|b)?c", "ac"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("nonCapturingAltWithQuantifier")
void nonCapturingAltWithQuantifier_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

Run: `./gradlew :reggie-runtime:test --tests '*nonCapturingAltWithQuantifier_agreesWithJdk*'`
Expected: FAIL (JavaRegexFallbackMatcher returned or wrong result).

- [ ] **Step 2: Split the condition — promote no-anchor case to PIKEVM_CAPTURE**

Replace lines ~1009–1019 in `PatternAnalyzer.java`:

```java
// Non-anchor alternation + quantifiers: PIKEVM_CAPTURE gives correct leftmost-first
// semantics (e.g. a?|b prefers "a" over "", x+|y prefers longest x over y).
if (containsAlternation(ast)
    && !hasAnchorInNfa(nfa)
    && dfaHasAcceptingStateWithTransitions(dfa)) {
    return new MatchingStrategyResult(
        MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
}
// Alternation + anchors: DFA anchor semantics still diverge. Fall back to JDK until
// PIKEVM anchor support is verified.
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    MatchingStrategyResult r =
        new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
    r.alternationPriorityConflict = true;
    return r;
}
```

- [ ] **Step 3: Run fuzz gate — must stay at 0**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
```

Expected: 0 findings. If findings appear for the newly promoted patterns, add targeted guards in `FallbackPatternDetector` (strategy `PIKEVM_CAPTURE`) and re-run.

- [ ] **Step 4: Run tests**

```bash
./gradlew :reggie-runtime:test --tests '*nonCapturingAltWithQuantifier_agreesWithJdk*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
```

Expected: task test PASSES; no new failures.

- [ ] **Step 5: spotlessApply and commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: promote non-anchor alternation+quantifier patterns to PIKEVM_CAPTURE"
```

---

### Task 2: Investigate and promote alternation + anchor patterns (non-capturing)

**Fallback:** A1 residual — the anchor sub-case left by Task 1.

Patterns: `^a|b`, `a|b$`, `\Aa|b`, `a|b\Z`. These have alternation AND anchors AND `dfaHasAcceptingStateWithTransitions`.

PIKEVM_CAPTURE handles each branch independently with correct leftmost-first semantics; anchors are evaluated as zero-width checks per thread. This should be correct.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> nonCapturingAltWithAnchor() {
    return Stream.of(
        Arguments.of("^a|b",    "a"),
        Arguments.of("^a|b",    "b"),
        Arguments.of("^a|b",    "xb"),
        Arguments.of("a|b$",    "b"),
        Arguments.of("a|b$",    "a"),
        Arguments.of("\\Aa|b",  "b"),
        Arguments.of("a|b\\Z",  "a"),
        Arguments.of("a|b\\Z",  "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("nonCapturingAltWithAnchor")
void nonCapturingAltWithAnchor_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

Run: `./gradlew :reggie-runtime:test --tests '*nonCapturingAltWithAnchor_agreesWithJdk*'`

- [ ] **Step 2: Verify PIKEVM_CAPTURE correctness via fuzz sampling**

Before changing routing, add a temporary test that compiles a sample of anchor+alternation patterns to PIKEVM_CAPTURE directly (bypassing PatternAnalyzer by reflectively injecting the strategy, or by creating a minimal PIKEVM_CAPTURE matcher directly) and checks agreement with JDK on a broad input set. If all pass, proceed.

Alternatively, change the routing, run the fuzz gate, and treat any new findings as guards to add.

- [ ] **Step 3: Remove the anchor exclusion from Task 1**

Replace the remaining anchor sub-case in `PatternAnalyzer.java`:

```java
// Before (from Task 1):
// Alternation + anchors: fall back to JDK.
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    MatchingStrategyResult r = ...;
    r.alternationPriorityConflict = true;
    return r;
}

// After:
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    return new MatchingStrategyResult(
        MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
}
```

- [ ] **Step 4: If `alternationPriorityConflict` is now unset everywhere, remove the flag**

```bash
grep -n "alternationPriorityConflict = true" \
    reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
```

If output is empty: remove `alternationPriorityConflict` from `MatchingStrategyResult` and remove the guard at `RuntimeCompiler.java:345–353`.

- [ ] **Step 5: Run fuzz gate, tests, and commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/MatchingStrategyResult.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: remove alternationPriorityConflict; all alternation patterns route natively"
```

---

### Task 3: Promote DFA anchor condition dilution to OPTIMIZED_NFA

**Fallback:** B1 / B2 — `PatternAnalyzer.java:~990` and `RuntimeCompiler.java:609`

`dfa.isAnchorConditionDiluted()` fires when the `SubsetConstructor` detects that anchor guards were structurally erased during NFA→DFA conversion (see `SubsetConstructor.java:154`, `SubsetConstructor.java:469`, `SubsetConstructor.java:545`). The AST predicates `hasMisplacedStartAnchorInAlternation` and `hasStringEndAnchorInAlternation` already cover the two known safe sub-cases (Tasks 5/7 of prior plan). This task investigates what patterns reach the DFA-level dilution without triggering those AST predicates.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Find patterns that trigger dfa.isAnchorConditionDiluted() without AST predicates**

Add a temporary diagnostic test (do not commit) that logs all patterns from the fuzz seed corpus that hit `anchorConditionDiluted` after the AST predicates are checked:

```java
@Test
void diagnoseAnchorDilutedPatterns() throws Exception {
    // Patterns from prior fuzz runs that were associated with anchor issues:
    String[] candidates = {
        "(?:a|b^)",     // misplaced ^ — should be caught by hasMisplacedStartAnchorInAlternation
        "$|a",          // end anchor in alternation — should be caught by hasStringEndAnchorInAlternation
        "a^b",          // anchor mid-pattern
        "a\\Ab",        // \A mid-pattern
    };
    for (String pat : candidates) {
        ReggieMatcher m = Reggie.compile(pat);
        System.out.println(pat + " -> " + m.getClass().getSimpleName());
    }
}
```

Run: `./gradlew :reggie-runtime:test --tests '*diagnoseAnchorDilutedPatterns*'`

For patterns that still produce `JavaRegexFallbackMatcher`, add them to the regression test and investigate whether OPTIMIZED_NFA handles them correctly by manually testing against JDK.

- [ ] **Step 2: Write failing tests for confirmed-safe patterns**

For each pattern verified safe for OPTIMIZED_NFA (i.e., OPTIMIZED_NFA result agrees with JDK):

```java
static Stream<Arguments> anchorDilutedResidual() {
    return Stream.of(
        // Add confirmed-safe patterns here from Step 1 investigation
    );
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("anchorDilutedResidual")
void anchorDilutedResidual_usesNativePathAndAgreesWithJdk(String pat, String in) throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(reggie instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 3: Remove the anchorConditionDiluted JDK route**

In `PatternAnalyzer.java` at the non-capturing DFA path (~line 990), change:

```java
if (dfa.isAnchorConditionDiluted()) {
    MatchingStrategyResult r =
        new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
    r.anchorConditionDiluted = true;
    return r;
}
```

to:

```java
if (dfa.isAnchorConditionDiluted()) {
    // DFA structural anchor erasure: OPTIMIZED_NFA handles anchors as per-thread
    // zero-width assertions and gives correct JDK-compatible results.
    return new MatchingStrategyResult(
        MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
}
```

Apply the same change at the capturing TDFA path (~line 802) and remove the `anchorConditionDiluted` guard in `RuntimeCompiler.java:609` (compileHybrid).

- [ ] **Step 4: If anchorConditionDiluted is now unset everywhere, remove the field**

```bash
grep -n "anchorConditionDiluted = true" \
    reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
```

If empty: remove `anchorConditionDiluted` from `MatchingStrategyResult`; remove guards at `RuntimeCompiler.java:337` and `RuntimeCompiler.java:609`.

- [ ] **Step 5: Run fuzz gate, full suite, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: remove anchorConditionDiluted; diluted-anchor patterns route to OPTIMIZED_NFA"
```

---

## Track 2 — PikeVM engine extensions

These require extending `PikeVMMatcher` (or `PikevmBytecodeGenerator`) to handle patterns currently excluded from the PIKEVM_CAPTURE routing.

---

### Task 4: Extend PIKEVM_CAPTURE to handle quantified capturing groups

**Fallback:** A2 sub-case — capturing TDFA path excluding `hasQuantifiedCapturingGroup(ast)` (e.g. `(a|b)+`, `(a|b){2,5}`)

Current exclusion in `PatternAnalyzer.java:~826`:
```java
if (quantifiedAltWithGroupBug
    && !hasAnchorInNfa(nfa)
    && !hasQuantifiedCapturingGroup(ast)   // ← exclusion
    && !hasNullableAlternationBranch(ast)) {
    return new MatchingStrategyResult(MatchingStrategy.PIKEVM_CAPTURE, ...);
}
```

Root cause: PIKEVM_CAPTURE must record the group span from the LAST iteration of a quantified capturing group, not the first. This requires the PikeVM thread scheduler to update group slots on every iteration and keep the final iteration's values when the quantifier exits.

**Files:**
- Investigate: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/PikevmBytecodeGenerator.java`
- Modify: (generator + PatternAnalyzer exclusion removal)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> pikeVmQuantifiedCapturingGroup() {
    return Stream.of(
        Arguments.of("(a|b)+",      "abba",  1),   // group 1 span = last iteration
        Arguments.of("(a|b)+",      "x",     -1),
        Arguments.of("(a|b){2,5}", "aba",    1),
        Arguments.of("(ab|c)+",    "cabc",   1),
        Arguments.of("([0-9])+",   "123",    1));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("pikeVmQuantifiedCapturingGroup")
void pikeVmQuantifiedCapturingGroup_agreesWithJdk(String pat, String in, int groupCount)
    throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check " + ctx);
    if (jdkM && groupCount > 0) {
        assertEquals(jm.start(1) + "," + jm.end(1), rm.start(1) + "," + rm.end(1),
            "match() g1 span " + ctx);
    }
}
```

Run: `./gradlew :reggie-runtime:test --tests '*pikeVmQuantifiedCapturingGroup_agreesWithJdk*'`
Expected: FAIL.

- [ ] **Step 2: Investigate PikevmBytecodeGenerator quantifier handling**

Read `PikevmBytecodeGenerator.java` and locate where quantifier loops are generated. Determine whether group-slot updates happen inside loop bodies. If group slots are only written at group ENTRY/EXIT and a quantifier loops back to before the group, the last iteration's exit write is preserved. If the loop overwrites slots on each iteration without preserving the last, a fix is needed.

- [ ] **Step 3: Fix PikeVM to preserve last-iteration group spans**

Depending on Step 2 findings, either:
- The generator already writes group slots on each iteration and the bug is in PatternAnalyzer's exclusion (remove `!hasQuantifiedCapturingGroup(ast)` from the guard)
- Or the generator needs to be modified to write group slots at each loop-body exit

- [ ] **Step 4: Remove `!hasQuantifiedCapturingGroup(ast)` exclusion in PatternAnalyzer**

After the generator fix is verified, remove the exclusion:

```java
if (quantifiedAltWithGroupBug
    && !hasAnchorInNfa(nfa)
    // removed: && !hasQuantifiedCapturingGroup(ast)
    && !hasNullableAlternationBranch(ast)) {
    return new MatchingStrategyResult(MatchingStrategy.PIKEVM_CAPTURE, ...);
}
```

- [ ] **Step 5: Run fuzz gate, tests, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: extend PIKEVM_CAPTURE to quantified capturing groups"
```

---

### Task 5: Extend PIKEVM_CAPTURE to handle nullable alternation branches

**Fallback:** A2 sub-case — `hasNullableAlternationBranch(ast)` exclusion and the OPTIMIZED_NFA guard `hasNullableAlternationBranchAnywhere` (FallbackPatternDetector:246)

Current state: both PIKEVM_CAPTURE routing and OPTIMIZED_NFA routing exclude nullable alternation branches. Example patterns: `(a|){2}`, `(b|c?)+`.

Root cause: when an alternation has a nullable branch (e.g. `|`), the engine must prefer the FIRST matching alternative even if it matches empty, which then must advance the match position correctly. The shared OPTIMIZED_NFA thread simulation may pick a longer-matching branch over an empty first-alternative.

**Files:**
- Investigate: PikeVM thread scheduler for nullable branch handling
- Modify: `FallbackPatternDetector.java` (remove guard at line 246 if PIKEVM handles it)
- Modify: `PatternAnalyzer.java` (remove `!hasNullableAlternationBranch(ast)` exclusion)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> nullableAlternationBranch() {
    return Stream.of(
        Arguments.of("(a|){2}",   "a"),
        Arguments.of("(a|){2}",   "aa"),
        Arguments.of("(a|)",      ""),
        Arguments.of("(a|b|)",    "b"),
        Arguments.of("a*|b",      "b"),
        Arguments.of("a*|b",      ""));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("nullableAlternationBranch")
void nullableAlternationBranch_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

Run: `./gradlew :reggie-runtime:test --tests '*nullableAlternationBranch_agreesWithJdk*'`

- [ ] **Step 2: Verify PIKEVM_CAPTURE handles nullable branches via direct test**

Temporarily set the strategy in PatternAnalyzer for a specific test pattern to `PIKEVM_CAPTURE` and verify it agrees with JDK before removing the exclusion.

- [ ] **Step 3: Remove exclusions**

In `PatternAnalyzer.java` (~line 826), remove `&& !hasNullableAlternationBranch(ast)`.

In `FallbackPatternDetector.java` (~line 246), remove the `hasNullableAlternationBranchAnywhere` guard for `OPTIMIZED_NFA` if PikeVM is now the strategy for these patterns (the guard fires on `OPTIMIZED_NFA`; once PatternAnalyzer routes to `PIKEVM_CAPTURE` instead, the guard becomes unreachable for these patterns).

- [ ] **Step 4: Run fuzz gate, tests, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: extend PIKEVM_CAPTURE to nullable alternation branches"
```

---

### Task 6: Extend PIKEVM_CAPTURE to handle anchors in capturing alternation

**Fallback:** A2 sub-case — `hasAnchorInNfa(nfa)` exclusion in the capturing TDFA path

Current exclusion: patterns with anchors (`^`, `$`, `\A`, `\Z`) are excluded from the `quantifiedAltWithGroupBug` → PIKEVM_CAPTURE promotion. Example: `^(a|b)`, `(a|b$)`.

PikeVM needs to evaluate anchors as zero-width assertions correctly per thread. If the PikeVM implementation in `PikevmBytecodeGenerator.java` already handles anchor nodes (check for `AnchorNode` handling), this may be a simple exclusion removal.

**Files:**
- Investigate: `PikevmBytecodeGenerator.java` for anchor handling
- Modify: `PatternAnalyzer.java` (remove `!hasAnchorInNfa(nfa)` exclusion)
- Modify: `FallbackPatternDetector.java` (remove or tighten the two anchor-in-alternation guards at lines 228, 236 if PIKEVM_CAPTURE correctly handles them)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> pikeVmCapturingAltWithAnchor() {
    return Stream.of(
        Arguments.of("^(a|b)",     "a"),
        Arguments.of("^(a|b)",     "b"),
        Arguments.of("^(a|b)",     "xb"),
        Arguments.of("(a|b$)",     "b"),
        Arguments.of("(a|b)$",     "b"),
        Arguments.of("\\A(a|b)",   "a"),
        Arguments.of("(a|b)\\Z",   "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("pikeVmCapturingAltWithAnchor")
void pikeVmCapturingAltWithAnchor_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check " + ctx);
    if (jdkM) {
        assertEquals(jm.start(1) + "," + jm.end(1), rm.start(1) + "," + rm.end(1),
            "match() g1 span " + ctx);
    }
}
```

- [ ] **Step 2: Check PikeVM anchor node handling**

Read `PikevmBytecodeGenerator.java` and grep for `AnchorNode` handling. If the generator already emits correct anchor checks per thread, the fix is just removing the PatternAnalyzer exclusion. If not, anchor support must be added first.

- [ ] **Step 3: Remove anchor exclusion from PatternAnalyzer + update FallbackPatternDetector guards**

Remove `&& !hasAnchorInNfa(nfa)` from the capturing TDFA path condition.

Review whether `hasStringEndAnchorInAltWithProblematicContext` (FallbackPatternDetector:228) and `hasStartClassAnchorInAlternationBranch` (FallbackPatternDetector:236) are now unreachable (since PIKEVM_CAPTURE is the strategy, not OPTIMIZED_NFA). If so, remove or tighten those guards.

- [ ] **Step 4: Run fuzz gate, tests, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: extend PIKEVM_CAPTURE to anchor-containing capturing alternation"
```

---

### Task 7: Promote captureAmbiguous patterns with named groups / anchors

**Fallback:** C — `RuntimeCompiler.java:357`, set at `PatternAnalyzer.java:~902`

Current code at PatternAnalyzer ~895–905:
```java
// Fallback: named groups or anchors — PikeVMMatcher doesn't handle these yet.
MatchingStrategyResult r = new MatchingStrategyResult(
    MatchingStrategy.OPTIMIZED_NFA, ...);
r.captureAmbiguous = true;
return r;
```

And at PatternAnalyzer ~643 (NFA bypass path):
```java
if (nfa != null && nfa.getGroupCount() > 0 && hasNfaCaptureAmbiguity(nfa)) {
    MatchingStrategyResult r = new MatchingStrategyResult(
        MatchingStrategy.OPTIMIZED_NFA, ...);
    r.captureAmbiguous = true;
    return r;
}
```

**Prerequisites:** Task 6 (PikeVM anchor support). Named groups require PikeVM to support named group slot lookup.

**Files:**
- Investigate: `PikevmBytecodeGenerator.java` for named group slot support
- Modify: `PatternAnalyzer.java` (~lines 895–905, ~643)
- Modify: `RuntimeCompiler.java` (remove guard at line 357 if field becomes unused)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> captureAmbiguousNamedGroup() {
    return Stream.of(
        Arguments.of("(?<foo>a|b)",    "a"),
        Arguments.of("(?<x>a)|(?<y>b)","a"),
        Arguments.of("^(?<g>a|b)",     "a"),
        Arguments.of("(?<g>a|b)$",     "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("captureAmbiguousNamedGroup")
void captureAmbiguousNamedGroup_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 2: Add named group support to PikeVM (if not present)**

Check `PikevmBytecodeGenerator.java` for named group handling. `nameMap` entries must resolve to correct slot indices in the PIKEVM_CAPTURE matcher. If missing, add named group index propagation.

- [ ] **Step 3: Route both captureAmbiguous sites to PIKEVM_CAPTURE**

At PatternAnalyzer ~895–905 and ~643, replace `r.captureAmbiguous = true; return r;` with:
```java
return new MatchingStrategyResult(MatchingStrategy.PIKEVM_CAPTURE, ...);
```

If `captureAmbiguous` is now unset everywhere, remove the field and the `RuntimeCompiler.java:357` guard.

- [ ] **Step 4: Run fuzz gate, tests, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: route captureAmbiguous patterns to PIKEVM_CAPTURE"
```

---

## Track 3 — Backref engine fixes

These require changes to the NFA backref simulation strategy to correctly track last-iteration captures and nullable groups.

---

### Task 8: Fix VARIABLE_CAPTURE_BACKREF outer-quantifier and nullable-group cases

**Fallbacks:** D3 (`hasOuterQuantifierOnBackrefGroup`), D4 (`hasNullableBackrefGroup` for OPTIMIZED_NFA_WITH_BACKREFS / FIXED_REPETITION_BACKREF), D5 (`hasNonAnchorPrefixBeforeBackrefGroup`), D6 (`hasOuterQuantifierOnUnsupportedBackrefGroup`)

These all share the root cause: the backref engine cannot determine which iteration of a quantified group captured the final value. Fix requires storing per-iteration group arrays (Pike VM style) in the NFA thread state.

Root cause detail:
- D3 (`(X)+\1`): The VARIABLE_CAPTURE_BACKREF generator writes `groupStart`/`groupEnd` slots for each group but does not update them on each loop iteration. After `(a)+` runs, the slots hold the LAST write — but the generator's loop structure writes on ENTRY, not EXIT, so it may hold the WRONG iteration's value.
- D4 (nullable backref): `groupLen=0` is a valid capture; the existing `groupLen<0` guard catches uninitialized groups but not nullable captures.
- D5 (non-anchor prefix): the generator only emits prefix-matching bytecode for `LiteralNode` and `CharClassNode`; complex prefix nodes (e.g. quantified literals) are not handled.
- D6 (OPTIONAL_GROUP_BACKREF with nullable/alternation body): assumes `groupLen > 0`.

**Files:**
- Investigate: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java`
- Investigate: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/OptionalGroupBackrefBytecodeGenerator.java`
- Modify: generators + FallbackPatternDetector guard removals
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests for each sub-case**

```java
static Stream<Arguments> backrefsEdgeCases() {
    return Stream.of(
        // D3: outer quantifier on capturing group
        Arguments.of("(c)+\\1",      "cc"),
        Arguments.of("(a|b)+\\1",    "aa"),
        // D4: backref to nullable group
        Arguments.of("(a?)\\1",      ""),
        Arguments.of("(a?)\\1",      "a"),
        // D5: non-anchor prefix
        Arguments.of("a+(b)\\1",     "aabb"),
        // D6: optional-group backref with alternation body
        Arguments.of("(a|b)?\\1",    "a"),
        Arguments.of("(a|b)?\\1",    "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("backrefsEdgeCases")
void backrefsEdgeCases_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 2: Fix each sub-case independently; remove guards after each fix**

For D3: update `VariableCaptureBackrefBytecodeGenerator` loop to write group slots at loop EXIT (not entry). Or use a post-loop copy. Remove `hasOuterQuantifierOnBackrefGroup` guard from `FallbackPatternDetector` once fixed.

For D4: extend the backref match loop to treat `groupLen=0` as a valid (empty) capture for all three backref generators. Remove `hasNullableBackrefGroup` guards once fixed.

For D5: extend prefix-node handling in `VariableCaptureBackrefBytecodeGenerator` to support quantified literals and char classes. Remove `hasNonAnchorPrefixBeforeBackrefGroup` guard once fixed.

For D6: update `OptionalGroupBackrefBytecodeGenerator` to handle `groupLen=0` and alternation-body groups. Remove `hasOuterQuantifierOnUnsupportedBackrefGroup` guard once fixed.

- [ ] **Step 3: Run fuzz gate after each sub-fix, commit after all**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: backref engine handles outer-quantifier, nullable, prefix, and alt-body cases"
```

---

### Task 9: Fix cross-alternative backref

**Fallback:** D2 — `hasCrossAlternativeBackref` (FallbackPatternDetector:104)

Patterns: `(a)|\1`, `(a|b\1)` — group defined in one alternation branch, referenced in another. Root cause: Thompson NFA simulation uses shared group arrays; when thread A (branch 1) writes to group slot and thread B (branch 2) reads it via backref, the simulation produces wrong results because the branches execute in independent threads.

Fix: requires per-thread group arrays in the NFA simulator — a full Pike VM group-tracking implementation. This is a significant engine change.

**Files:**
- Investigate: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/NfaBackrefBytecodeGenerator.java` or equivalent backref NFA generator

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> crossAlternativeBackref() {
    return Stream.of(
        Arguments.of("(a)\\1|b",   "aa"),
        Arguments.of("(a)\\1|b",   "b"),
        Arguments.of("a|(b)\\1",   "bb"),
        Arguments.of("(a)|\\1b",   "ab"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("crossAlternativeBackref")
void crossAlternativeBackref_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 2: Implement per-thread group arrays in the NFA backref simulator**

Each active NFA thread must carry its own copy of the group-span array. On SPLIT (alternation), both threads get independent copies. On MERGE (when a thread terminates), the surviving thread keeps its copy. This is the standard Pike VM approach.

Modify the NFA backref bytecode generator to allocate and copy per-thread group arrays on split. The cost is O(n · g) where g is the group count — acceptable for backref patterns which are already O(n²) or worse.

- [ ] **Step 3: Remove `hasCrossAlternativeBackref` guard and run gate**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: per-thread group arrays in NFA backref simulator; remove cross-alt-backref guard"
```

---

## Track 4 — New generators

These require implementing new bytecode generation strategies from scratch.

---

### Task 10: Implement lazy quantifier support

**Fallback:** D1 — `hasLazyQuantifier` for RECURSIVE_DESCENT and OPTIMIZED_NFA_WITH_BACKREFS (FallbackPatternDetector:95)

Lazy quantifiers (`*?`, `+?`, `??`, `{m,n}?`) require shortest-match semantics: prefer the minimum number of repetitions first, backtrack to more repetitions if the continuation fails. The existing generators use greedy-first semantics.

Fix: requires a continuation-passing backtracking mechanism in the RECURSIVE_DESCENT generator — try the minimum repetition first, then retry with more if the suffix fails. For OPTIMIZED_NFA_WITH_BACKREFS, the `findMatchFromMethod` must pick the SHORTEST successful match, not the longest.

**Files:**
- Implement: lazy mode in `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/RecursiveDescentBytecodeGenerator.java`
- Implement: lazy mode in the NFA backref generator
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> lazyQuantifier() {
    return Stream.of(
        Arguments.of("a*?b",    "aaab"),
        Arguments.of("a+?",     "aaa"),
        Arguments.of("a??b",    "b"),
        Arguments.of("a??b",    "ab"),
        Arguments.of(".+?ab",   "xab"),
        Arguments.of("(a+?)",   "aaa"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("lazyQuantifier")
void lazyQuantifier_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 2: Implement lazy quantifier support in RECURSIVE_DESCENT**

In `RecursiveDescentBytecodeGenerator.java`, add lazy-quantifier handling: when generating a lazy `*?` or `+?`, generate bytecode that tries the continuation FIRST (zero or min repetitions), then backtracks to try one more repetition. This is a continuation-passing approach: push a retry frame before attempting the minimum, pop it on success, re-push on failure to try more repetitions.

- [ ] **Step 3: Implement shortest-match selection in OPTIMIZED_NFA_WITH_BACKREFS**

The `findMatchFromMethod` in the NFA backref generator currently returns the longest match. For lazy patterns, add a "shortest first" option that, for each start position, tries end positions from left to right and returns the first successful match.

- [ ] **Step 4: Remove `hasLazyQuantifier` guard and run gate**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "feat: lazy quantifier support in RECURSIVE_DESCENT and OPTIMIZED_NFA_WITH_BACKREFS"
```

---

### Task 11: Fix lookahead in quantifier and alternation

**Fallback:** E1 (`lookaheadInQuantifier`, FallbackPatternDetector:59), E2 (`hasLookaheadInAlternation` for OPTIMIZED_NFA_WITH_LOOKAROUND, FallbackPatternDetector:152)

**E1 — lookahead in quantifier** (issue #28): NFA engine evaluates lookahead assertions against the input position at each loop iteration correctly, but the thread scheduler merges threads before the lookahead at the next position is evaluated, allowing a thread from a previous iteration to suppress the lookahead check for the current iteration.

**E2 — lookahead in alternation** (issue #31): `OPTIMIZED_NFA_WITH_LOOKAROUND` thread scheduler does not isolate assertion evaluation per branch. When two threads representing different alternation branches are merged, the lookahead state from one branch contaminates the other.

**Files:**
- Investigate: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/NfaLookaroundBytecodeGenerator.java`

- [ ] **Step 1: Write failing tests for E1**

```java
static Stream<Arguments> lookaheadInQuantifier() {
    return Stream.of(
        Arguments.of("(?=a)+",      "aaa"),
        Arguments.of("(a(?=b))+",   "ababab"),
        Arguments.of("(?:a(?=b))+", "ab"));
}
```

- [ ] **Step 2: Write failing tests for E2**

```java
static Stream<Arguments> lookaheadInAlternation() {
    return Stream.of(
        Arguments.of("a(?=b)|c",   "ab"),
        Arguments.of("a(?=b)|c",   "c"),
        Arguments.of("(?=a)a|b",   "a"),
        Arguments.of("(?=a)a|b",   "b"));
}
```

- [ ] **Step 3: Fix the NFA lookaround thread scheduler**

For E1: the fix is to delay thread merging until AFTER the lookahead assertion is evaluated in each loop iteration. Specifically: threads that differ only in their post-assertion state must not be merged until the assertion completes.

For E2: each alternation branch must evaluate its own lookahead assertions in isolation. The fix is to prevent cross-branch thread state sharing when a lookahead assertion is in progress.

- [ ] **Step 4: Remove E1/E2 guards and run gate**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "fix: lookahead in quantifier (issue #28) and alternation (issue #31)"
```

---

## Track 5 — Infrastructure

### Task 12: Generated-method splitting for MethodTooLargeException

**Fallback:** `RuntimeCompiler.java:492` — `MethodTooLargeException` catch block

Large Grok-style alternation patterns (hundreds of alternatives) cause the generated bytecode method to exceed JVM's 64KB limit. The fallback is caught silently and routes to JDK.

Fix: when a method exceeds the limit, split the generated logic into multiple private static methods and emit dispatch shims that call them. ASM 9.7 does not provide automatic method splitting; it must be implemented in the code generator layer.

**Files:**
- Investigate: identify which generator produces the large method (typically the DFA unrolled generator or the main match method)
- Implement: method-splitting logic in the relevant generator(s)
- Test: construct a synthetic 200-alternative pattern and assert it produces a native matcher

- [ ] **Step 1: Write a failing test that triggers MethodTooLargeException**

```java
@Test
void largeAlternation_usesNativeMatcher() throws Exception {
    // 200 alternatives each 3 chars — enough to exceed 64KB
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
        if (i > 0) sb.append('|');
        sb.append((char)('a' + i % 26)).append((char)('a' + (i/26) % 26)).append((char)('0' + i % 10));
    }
    ReggieMatcher m = Reggie.compile(sb.toString());
    assertFalse(m instanceof JavaRegexFallbackMatcher,
        "Large alternation should use native matcher, got: " + m.getClass().getSimpleName());
}
```

- [ ] **Step 2: Identify which generator hits the limit**

Add a log in the `MethodTooLargeException` catch block to print the `className.methodName` and `codeSize`. Then run the test to identify the generator.

- [ ] **Step 3: Implement method splitting in the identified generator**

After completing a method body, check if the current code size exceeds a threshold (e.g. 55,000 bytes — conservative margin below 65,536). If so, extract the current body into a private static method, replace it with a call-shim, and continue generating into the new method. Recurse as needed for very large patterns.

- [ ] **Step 4: Run tests, commit**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
./gradlew :reggie-runtime:test --tests '*largeAlternation_usesNativeMatcher*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
./gradlew spotlessApply
git commit -m "feat: method splitting in codegen to handle large alternation patterns"
```

---

## Deferred items (not in this plan)

| Item | Reason |
|---|---|
| `hasAnchorInQuantifierInCapturingGroup` guard (FallbackPatternDetector:66) | Anchor inside quantifier inside capturing group — distinct from the general anchor-in-quantifier guard; needs per-iteration capture boundary tracking |
| `hasEndAnchorBeforeNonNewlineConsumer` guard (FallbackPatternDetector:80) | `\Z[^c]` and similar — DFA does not model this path; needs NFA-level end-anchor modeling |
| `hasOptionalPrefixBeforeCapturingGroup` guard (TDFA, FallbackPatternDetector:142) | Wrong group-start from optional prefix — TDFA priority ordering limitation; PIKEVM_CAPTURE promotion may fix this as a side-effect of Tasks 4–6 |
