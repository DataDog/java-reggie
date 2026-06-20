# JDK Fallback Elimination Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all remaining `JavaRegexFallbackMatcher` routes so every accepted pattern runs natively in Reggie with correct JDK-compatible semantics.

**Architecture:** Two tracks. Track A adds targeted fallback guards for the 23 known fuzz divergences (patterns Reggie runs natively but returns wrong results for). Track B removes three routing-level JDK fallback flags (`alternationPriorityConflict`, `anchorConditionDiluted`) by promoting those pattern classes to correct native strategies. Track A must land first — it brings the fuzz gate to zero — then Track B removes fallbacks one by one, each validated by the fuzz gate. Deferred items (lazy quantifiers, cross-alt backref deep fix, lookahead in quantifier/alternation) are noted at the end.

**Tech Stack:** Java 21, ASM 9.7, JUnit 5. Build: `./gradlew :<module>:test`. Fuzz gate: `./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' -Dreggie.fuzz.enforceZero=true`.

---

## Key files

| File | Role |
|---|---|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` | AST-level fallback guards; `needsFallback()` is the entry point |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Strategy selection; sets `alternationPriorityConflict` (lines 814, 950) and `anchorConditionDiluted` (lines 780, 938) |
| `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` | JDK routing; checks `alternationPriorityConflict` (line 343), `anchorConditionDiluted` (line 335) |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java` | Regression tests for conditions removed/fixed in this plan |
| `reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration/AlgorithmicFuzzTest.java` | Zero-divergence gate; `@Disabled` gate at line 122 is enabled in the final task |

---

## Track A — Safety net: guard the 23 fuzz divergences

The fuzz gate currently reports 23 patterns where Reggie runs natively but produces a different answer from JDK. These are correctness holes: no fallback guard intercepts them. Track A adds guards so every one routes to JDK (correct) instead of producing a wrong native answer. After these four tasks the fuzz gate must reach 0.

---

### Task 1: Guard anchor-in-quantifier patterns (5 divergences)

Covers:
- `find() boolean differs: \A{0,3}a` on `ca`, `_a`
- `find() boolean differs: (?:[c])(?:c*^{0,2})` on `c`
- `find() boolean differs: (?:)(?:c*^{0,2}a)` on `1a`
- `first-match span differs: ${3}0?[^a]*` on `` (empty)
- `find()/matches()/match() differs: 0{0}\z{0,2}.{3}` on `ba-`, `1b1`

Root cause: any `AnchorNode` nested inside a `QuantifierNode` (with range ≠ {1,1}) causes wrong results in all DFA and OPTIMIZED_NFA strategies. When the quantifier's minimum is 0 the anchor becomes optional and the engine matches at wrong positions. The existing `hasAnchorInQuantifierInCapturingGroup` only guards the capturing-group case; the outer (non-capturing) case is unguarded.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`
- Test: `reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetectorTest.java`

- [ ] **Step 1: Write the failing test in `FallbackPatternDetectorTest`**

```java
@ParameterizedTest
@ValueSource(strings = {
    "\\A{0,3}a",             // start-anchor quantified
    "(?:c*^{0,2})",          // ^ in quantifier inside non-capturing group
    "(?:)(?:c*^{0,2}a)",     // same, in concat
    "${3}0?[^a]*",            // $ with {3} quantifier
    "0{0}\\z{0,2}.{3}",      // \z with {0,2} quantifier
})
void anchorInQuantifier_needsFallback(String pat) throws Exception {
    RegexNode ast = new RegexParser().parse(pat);
    assertNotNull(
        FallbackPatternDetector.needsFallback(ast, PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA),
        "expected fallback for: " + pat);
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew :reggie-codegen:test --tests '*FallbackPatternDetectorTest.anchorInQuantifier_needsFallback*'
```
Expected: FAIL — `needsFallback` returns null for these patterns.

- [ ] **Step 3: Add `hasAnchorInQuantifier` private method and guard in `FallbackPatternDetector`**

Add after the `hasAnchorInQuantifierInCapturingGroup` block (after line 75 in `needsFallback`, before the lazy-quantifier check):

```java
// Anchor inside a quantifier (range ≠ {1,1}) at any nesting depth: when the
// quantifier allows 0 repetitions the anchor becomes optional, and all DFA/NFA
// strategies produce wrong match positions. The capturing-group sub-case is
// already caught above; this guard covers the non-capturing case.
if (hasAnchorInQuantifier(ast)) {
    return "anchor inside quantifier: zero-width anchor with quantifier produces incorrect match positions";
}
```

Add the private helper after `hasAnchorInQuantifierInCapturingGroup` (around line 322):

```java
/**
 * Returns true if any AnchorNode appears as the direct or indirect child of a
 * QuantifierNode whose range is not exactly {1,1}. Catches patterns like \A{0,3},
 * (?:c*^{0,2}), ${3} where a zero-width anchor is given a quantifier.
 */
private static boolean hasAnchorInQuantifier(RegexNode ast) {
    if (ast instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) ast;
        if ((q.min != 1 || q.max != 1) && containsAnchor(q.child)) return true;
        return hasAnchorInQuantifier(q.child);
    }
    if (ast instanceof GroupNode) return hasAnchorInQuantifier(((GroupNode) ast).child);
    if (ast instanceof ConcatNode) {
        for (RegexNode c : ((ConcatNode) ast).children)
            if (hasAnchorInQuantifier(c)) return true;
    }
    if (ast instanceof AlternationNode) {
        for (RegexNode a : ((AlternationNode) ast).alternatives)
            if (hasAnchorInQuantifier(a)) return true;
    }
    return false;
}
```

Note: `containsAnchor(RegexNode)` already exists at line 324 — reuse it.

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :reggie-codegen:test --tests '*FallbackPatternDetectorTest.anchorInQuantifier_needsFallback*'
```
Expected: PASS.

- [ ] **Step 5: Add runtime regression tests in `FallbackDetectorBugFixTest`**

```java
static Stream<Arguments> anchorInQuantifier() {
    return Stream.of(
        Arguments.of("\\A{0,3}a",        "ca"),
        Arguments.of("\\A{0,3}a",        "_a"),
        Arguments.of("(?:c*^{0,2})",     "c"),
        Arguments.of("(?:)(?:c*^{0,2}a)","1a"),
        Arguments.of("${3}0?[^a]*",      ""),
        Arguments.of("0{0}\\z{0,2}.{3}", "ba-"),
        Arguments.of("0{0}\\z{0,2}.{3}", "1b1"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("anchorInQuantifier")
void anchorInQuantifier_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

- [ ] **Step 6: Run runtime tests**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.anchorInQuantifier_agreesWithJdk*'
```
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-codegen/src/test/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetectorTest.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: guard anchor-in-quantifier patterns in FallbackPatternDetector"
```

---

### Task 2: Guard VARIABLE_CAPTURE_BACKREF edge cases (4 divergences)

Covers:
- `find() boolean differs: (c)+\1` on `__`, `00`
- `find() boolean differs: (])+\1` on `cc`
- `find() boolean differs: (-{2})+\1` on `bb`, `__`, `cc`
- `find() boolean differs: (]){3,}\1` on `0`

Root cause: patterns of the form `(X)+\1` or `(X){n,}\1` where the OUTER quantifier (`+` or `{n,}`) wraps the whole capturing group. The `detectVariableCaptureBackref` detection in PatternAnalyzer expects the group node to appear directly in the ConcatNode (not wrapped in a QuantifierNode). These patterns likely route to `OPTIMIZED_NFA_WITH_BACKREFS` (not VARIABLE_CAPTURE_BACKREF), and the OPTIMIZED_NFA_WITH_BACKREFS strategy produces wrong `find()` booleans for quantified-group-then-backref patterns.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Confirm the strategy used for these patterns**

Add a temporary debug assertion in a scratch test (do not commit):

```java
@Test
void debugStrategyForQuantifiedGroupBackref() throws Exception {
    for (String pat : List.of("(c)+\\1", "(])+\\1", "(-{2})+\\1", "(]){3,}\\1")) {
        ReggieMatcher m = Reggie.compile(pat);
        System.out.println(pat + " -> " + m.getClass().getSimpleName());
    }
}
```

Run: `./gradlew :reggie-runtime:test --tests '*debugStrategyForQuantifiedGroupBackref*'`

Expected output: each pattern prints the concrete matcher class (e.g., `NfaBackrefMatcher` or similar). Identify which strategy these use. If they use `OPTIMIZED_NFA_WITH_BACKREFS`, the guard goes into the `OPTIMIZED_NFA_WITH_BACKREFS` branch of `needsFallback`. If they use `VARIABLE_CAPTURE_BACKREF`, the guard goes into the `VARIABLE_CAPTURE_BACKREF` branch.

- [ ] **Step 2: Write the failing regression test**

```java
static Stream<Arguments> quantifiedGroupBackref() {
    return Stream.of(
        Arguments.of("(c)+\\1",      "__"),
        Arguments.of("(c)+\\1",      "00"),
        Arguments.of("(])+\\1",      "cc"),
        Arguments.of("(-{2})+\\1",   "bb"),
        Arguments.of("(-{2})+\\1",   "__"),
        Arguments.of("(-{2})+\\1",   "cc"),
        Arguments.of("(]){3,}\\1",   "0"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("quantifiedGroupBackref")
void quantifiedGroupBackref_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

Run: `./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.quantifiedGroupBackref_agreesWithJdk*'`
Expected: FAIL — `find()` boolean mismatch.

- [ ] **Step 3: Add guard in `FallbackPatternDetector.needsFallback`**

After confirming the strategy in Step 1, add in the strategy-specific block (around line 95 for `OPTIMIZED_NFA_WITH_BACKREFS` or line 125 for `VARIABLE_CAPTURE_BACKREF`):

```java
// OPTIMIZED_NFA_WITH_BACKREFS (or VARIABLE_CAPTURE_BACKREF) with an outer quantifier
// wrapping the capturing group: (X)+\N or (X){n,}\N. The NFA engine does not track
// the correct last-iteration capture when the group is quantified at the AST level
// (QuantifierNode wrapping a GroupNode). Routes to JDK until the generator is extended.
if ((strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
        || strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF)
    && hasOuterQuantifierOnBackrefGroup(ast)) {
    return "quantified capturing group with backref: outer quantifier on group not supported by backref engine";
}
```

Add the helper method (after `hasNonAnchorPrefixBeforeBackrefGroup`, around line 480):

```java
/**
 * Returns true if any capturing group that is referenced by a backref in the same
 * pattern has a quantifier wrapping the GROUP NODE itself at the ConcatNode level
 * (i.e., the AST has QuantifierNode(GroupNode(N, ...)) rather than
 * GroupNode(N, QuantifierNode(...))). Example: (c)+\1 vs (c+)\1.
 */
private static boolean hasOuterQuantifierOnBackrefGroup(RegexNode ast) {
    Set<Integer> backrefNums = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefNums);
    if (backrefNums.isEmpty()) return false;
    return hasQuantifiedGroupWithBackref(ast, backrefNums);
}

private static boolean hasQuantifiedGroupWithBackref(RegexNode node, Set<Integer> backrefNums) {
    if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        if (q.child instanceof GroupNode) {
            GroupNode g = (GroupNode) q.child;
            if (g.capturing && backrefNums.contains(g.groupNumber)) return true;
        }
        return hasQuantifiedGroupWithBackref(q.child, backrefNums);
    }
    if (node instanceof ConcatNode) {
        for (RegexNode c : ((ConcatNode) node).children)
            if (hasQuantifiedGroupWithBackref(c, backrefNums)) return true;
    }
    if (node instanceof GroupNode)
        return hasQuantifiedGroupWithBackref(((GroupNode) node).child, backrefNums);
    if (node instanceof AlternationNode) {
        for (RegexNode a : ((AlternationNode) node).alternatives)
            if (hasQuantifiedGroupWithBackref(a, backrefNums)) return true;
    }
    return false;
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.quantifiedGroupBackref_agreesWithJdk*'
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: guard quantified-group backref patterns in FallbackPatternDetector"
```

---

### Task 3: Guard empty/nullable group backref and group-span patterns (4 divergences)

Covers:
- `match() group 1 span differs: -?(-?.{3}).` on `-bbb`
- `find() boolean differs: ()\1{1}` on `` (empty string)
- `matches()/match() boolean differs: (.|)(\1\1)(\2{3}[^a]){1}` on `b`
- `find() boolean differs: ()(\1\1)(\2{3}[^a]){1}` on `b`

Root cause (two sub-cases):

**Sub-case A** (`-?(-?.{3}).`): The TDFA `quantifiedAltWithGroupBug` (PatternAnalyzer line 794) correctly sets `alternationPriorityConflict=true` and routes to JDK; however the `match()` span is wrong. This pattern should already fall back to JDK — if it's in the divergences, either the JDK path isn't taken for `match()` or the `match()` delegation is wrong. Investigate whether `JavaRegexFallbackMatcher.match()` delegates to JDK correctly for this pattern.

**Sub-case B** (`()\1{1}`, `()(\1\1)(\2{3}[^a]){1}`): Empty capturing group with backref. Group 1 captures empty string; `\1{1}` repeats the empty backref. Routes to `OPTIMIZED_NFA_WITH_BACKREFS` (not caught by the existing nullable guard because that guard is OPTIMIZED_NFA_WITH_BACKREFS-only and these patterns may use a different strategy). Need to investigate which strategy is used and add a guard.

**Files:**
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/JavaRegexFallbackMatcher.java` (sub-case A if needed)
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` (sub-case B)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests for both sub-cases**

```java
static Stream<Arguments> emptyGroupBackref() {
    return Stream.of(
        Arguments.of("()\\1{1}",                     ""),
        Arguments.of("(.|)(\\1\\1)(\\2{3}[^a]){1}",  "b"),
        Arguments.of("()(\\1\\1)(\\2{3}[^a]){1}",    "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("emptyGroupBackref")
void emptyGroupBackref_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}

@Test
void groupSpanWithOptionalPrefix_agreesWithJdk() throws Exception {
    String pat = "-?(-?.{3}).";
    String in  = "-bbb";
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    // Verify the group 1 span matches JDK
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check for " + pat);
    if (jdkM) {
        assertEquals(jm.start(1), rm.start(1), "match() g1 start for " + pat);
        assertEquals(jm.end(1),   rm.end(1),   "match() g1 end for " + pat);
    }
}
```

- [ ] **Step 2: Run to verify failures**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.emptyGroupBackref_agreesWithJdk*' \
                                --tests '*FallbackDetectorBugFixTest.groupSpanWithOptionalPrefix_agreesWithJdk*'
```
Expected: FAIL.

- [ ] **Step 3: Investigate and add guards**

For sub-case B: check which strategy `()\1{1}` and `()(\1\1)(\2{3}[^a]){1}` use (add a debug print similar to Task 2 Step 1). The nullable guard at FallbackPatternDetector.java:106 only fires for `OPTIMIZED_NFA_WITH_BACKREFS`; if these patterns use a different strategy, extend the guard's strategy check:

```java
if ((strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
        || strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
        /* add confirmed strategy here */)
    && hasNullableBackrefGroup(ast)) {
    return "backref to nullable group: parallel NFA simulation records wrong capture span";
}
```

For sub-case A: add a `match()` regression for `-?(-?.{3}).` on `-bbb`. Check whether `Reggie.compile("-?(-?.{3}).")` produces a `JavaRegexFallbackMatcher` (it should via `alternationPriorityConflict`). If it does, check whether `JavaRegexFallbackMatcher.match()` calls `jdkPattern.matcher(input).matches()` and returns the result with correct spans. If not, fix the delegation in `JavaRegexFallbackMatcher`.

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.emptyGroupBackref_agreesWithJdk*' \
                                --tests '*FallbackDetectorBugFixTest.groupSpanWithOptionalPrefix_agreesWithJdk*'
```
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/JavaRegexFallbackMatcher.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: guard empty/nullable group backref and group-span patterns"
```

---

### Task 4: Verify fuzz gate reaches 0 divergences

- [ ] **Step 1: Run the full zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
    --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
    -Dreggie.fuzz.enforceZero=true 2>&1 | grep "zero-divergence-gate-repro\|zero-divergence-gate\]"
```

Expected output:
```
[zero-divergence-gate] patterns=10000 ... findings=0
```
No `[zero-divergence-gate-repro]` lines.

If there are remaining repros not covered by Tasks 1–3, add targeted guards for each and re-run before proceeding to Track B.

- [ ] **Step 2: Run the full test suite to confirm no regressions**

```bash
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-processor:test :reggie-integration-tests:test
```

Expected: same set of pre-existing failures as before Track A, no new failures.

- [ ] **Step 3: Commit if any residual guard was added in Step 1**

```bash
git commit -m "fix: guard remaining fuzz divergences; gate at 0"
```

---

## Track B — Routing: eliminate routing-level JDK fallbacks

Track B removes the three flags that cause `RuntimeCompiler` to return a `JavaRegexFallbackMatcher` before even reaching the strategy dispatch. Each task follows the same pattern: remove (or narrow) the flag, validate that the fuzz gate stays at 0, add regression tests.

**Prerequisite:** Track A complete; fuzz gate at 0.

---

### Task 5: Route non-capturing `alternationPriorityConflict` to OPTIMIZED_NFA

This flag is set at `PatternAnalyzer.java:946–951` (the standard DFA block). It fires when `containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)` — i.e., any non-capturing pattern with alternation where the DFA has outgoing transitions from an accepting state. Currently this falls back to JDK. The fix routes these patterns to OPTIMIZED_NFA (Thompson NFA simulation, leftmost-first), which gives JDK-compatible semantics.

Example patterns affected: `fo|foo`, `a|b|c`, `cat|catch`, `(?:0|c-){2,2}a?|a{3,5}c+`, `$|${0,2}`, `(){2}]{3}|a`.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (lines 946–951)
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` (lines 343–351)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write a test that currently sees JDK-fallback behavior but will use native after the change**

In a new test method, verify that after the change these patterns use OPTIMIZED_NFA bytecode (not `JavaRegexFallbackMatcher`):

```java
@ParameterizedTest
@ValueSource(strings = {"fo|foo", "a|b|c", "cat|catch", "$|a", "x|xy|xyz"})
void nonCapturingAlternation_usesNativePath(String pat) throws Exception {
    ReggieMatcher m = Reggie.compile(pat);
    assertFalse(m instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat + " but got: " + m.getClass().getSimpleName());
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("prefixOverlapAlternation")   // reuse existing provider (Task 6 of previous plan)
void nonCapturingAlternation_agreesWithJdk(String pat, String in) throws Exception {
    // (reuse the existing prefixOverlapAlternation_agreesWithJdk test body)
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    assertEquals(jdkF, rfm != null, "findMatch() null check " + ctx);
    if (jdkF) {
        assertEquals(jmf.start(0), rfm.start(0), "findMatch() start " + ctx);
        assertEquals(jmf.end(0), rfm.end(0), "findMatch() end " + ctx);
    }
}
```

Run: `./gradlew :reggie-runtime:test --tests '*nonCapturingAlternation_usesNativePath*'`
Expected: FAIL — `JavaRegexFallbackMatcher` is returned.

- [ ] **Step 2: Remove the `alternationPriorityConflict` flag in the non-capturing DFA path**

In `PatternAnalyzer.java`, change lines 946–951 from:

```java
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    MatchingStrategyResult r =
        new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
    r.alternationPriorityConflict = true;
    return r;
}
```

to:

```java
if (containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa)) {
    // Route to OPTIMIZED_NFA (Thompson simulation, leftmost-first) instead of JDK.
    // The DFA uses longest-match semantics which diverge from JDK for alternation;
    // OPTIMIZED_NFA gives the correct leftmost-first result.
    return new MatchingStrategyResult(
        MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
}
```

- [ ] **Step 3: Remove the `alternationPriorityConflict` RuntimeCompiler guard if it's now unreachable**

Check whether `alternationPriorityConflict` is still set by the capturing path (PatternAnalyzer line 814). If YES, keep the RuntimeCompiler guard (line 343–351). If NO (not set anywhere), remove it entirely. Only remove after confirming with `grep`:

```bash
grep -n "alternationPriorityConflict = true" \
    reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
```

If the output is empty, the field is unused — remove it from `MatchingStrategyResult` and remove the RuntimeCompiler guard. If one site remains (line 814, capturing path), leave the RuntimeCompiler guard in place.

- [ ] **Step 4: Run the fuzz gate — must stay at 0**

```bash
./gradlew :reggie-integration-tests:test \
    --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
    -Dreggie.fuzz.enforceZero=true
```

Expected: 0 findings. If findings appear, investigate each pattern — add targeted guards for any that show wrong results with OPTIMIZED_NFA and then re-run the gate.

- [ ] **Step 5: Run native-path test to verify it now passes**

```bash
./gradlew :reggie-runtime:test --tests '*nonCapturingAlternation_usesNativePath*' \
                                --tests '*nonCapturingAlternation_agreesWithJdk*'
```
Expected: PASS.

- [ ] **Step 6: Run full suite to check for regressions**

```bash
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
```
Expected: no new failures beyond the pre-existing set.

- [ ] **Step 7: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: route non-capturing alternationPriorityConflict to OPTIMIZED_NFA"
```

---

### Task 6: Route capturing `alternationPriorityConflict` to PIKEVM_CAPTURE

The second site (`PatternAnalyzer.java:799–815`) fires for capturing patterns with alternation + quantifiers where the TDFA priority ordering has the `quantifiedAltWithGroupBug`. Currently falls back to JDK. Fix: route to `PIKEVM_CAPTURE` (Pike VM simulation, leftmost-first, correct group spans).

Example patterns affected: `-?(-?.{3}).` (the group-span divergence from Task 3), `([b]|.{3}){1,}`, patterns that match A1 from the fuzz inventory (`inventory.md`).

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (lines 799–815)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests**

```java
static Stream<Arguments> capturingAlternationWithQuantifier() {
    return Stream.of(
        Arguments.of("-?(-?.{3}).", "-bbb"),
        Arguments.of("-?(-?.{3}).", "bbb"),
        Arguments.of("([b]|.{3}){1,}", "cb"),
        Arguments.of("(a|bc)+",       "abcbc"),
        Arguments.of("(a|bc)+",       "xyz"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("capturingAlternationWithQuantifier")
void capturingAlternationWithQuantifier_agreesWithJdk(String pat, String in) throws Exception {
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
        for (int g = 0; g <= jm.groupCount(); g++)
            assertEquals(jm.start(g) + "," + jm.end(g),
                         rm.start(g) + "," + rm.end(g),
                         "match() g" + g + " span " + ctx);
    }
}
```

Run: `./gradlew :reggie-runtime:test --tests '*capturingAlternationWithQuantifier_agreesWithJdk*'`
Expected: FAIL (group span wrong for `-?(-?.{3}).` on `-bbb`, or `JavaRegexFallbackMatcher` returned — both indicate the change is needed).

- [ ] **Step 2: Change the capturing `alternationPriorityConflict` path to route PIKEVM_CAPTURE**

In `PatternAnalyzer.java`, change lines 799–815. The current condition:
```java
if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
    && (quantifiedAltWithGroupBug || (...))) {
    MatchingStrategyResult r = new MatchingStrategyResult(
        MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals, null, needsPosixSemantics);
    r.alternationPriorityConflict = true;
    return r;
}
```

Change to:
```java
if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
    && (quantifiedAltWithGroupBug || (...))) {
    // TDFA priority ordering is unreliable for this class; PikeVM gives correct
    // leftmost-first spans with full group tracking.
    return new MatchingStrategyResult(
        MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals, null, needsPosixSemantics);
}
```

- [ ] **Step 3: Run fuzz gate — must stay at 0**

```bash
./gradlew :reggie-integration-tests:test \
    --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
    -Dreggie.fuzz.enforceZero=true
```
Expected: 0 findings. If there are new findings for PIKEVM_CAPTURE, investigate whether PikeVM handles all these pattern shapes correctly. Add guards for any that don't.

- [ ] **Step 4: Run tests and full suite**

```bash
./gradlew :reggie-runtime:test --tests '*capturingAlternationWithQuantifier_agreesWithJdk*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
```
Expected: task-specific test PASSES; no new suite failures.

- [ ] **Step 5: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: route capturing alternationPriorityConflict to PIKEVM_CAPTURE"
```

---

### Task 7: Route `anchorConditionDiluted` to OPTIMIZED_NFA

`anchorConditionDiluted` is set at PatternAnalyzer lines 780 and 938 when `dfa.isAnchorConditionDiluted() || hasMisplacedStartAnchorInAlternation(ast) || hasStringEndAnchorInAlternation(ast)`. Currently routes to JDK. OPTIMIZED_NFA uses Thompson NFA which handles anchors correctly (anchor is a zero-width assertion evaluated per NFA thread, not per DFA state). The fix routes to OPTIMIZED_NFA instead.

Example patterns affected: `(?:[c])(?:c*^{0,2})`, `(?:)(?:c*^{0,2}a)` (already guarded by Task 1 if anchor-in-quantifier fires first), `1[^c]$|.-\A`, `[1][^-]?\Z|_{2}`.

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` (lines 769–780, 932–939)
- Modify: `reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java` (lines 335–341)
- Test: `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`

- [ ] **Step 1: Write failing tests for anchor-diluted patterns**

```java
static Stream<Arguments> anchorDiluted() {
    return Stream.of(
        Arguments.of("1[^c]$|.-\\A",    "1-0"),
        Arguments.of("[1][^-]?\\Z|_{2}", "1"),
        Arguments.of("(?:a|b^)",         "a"),
        Arguments.of("(?:a|b^)",         "b"));
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("anchorDiluted")
void anchorDiluted_usesNativePathAndAgreesWithJdk(String pat, String in) throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(reggie instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
}
```

Run: `./gradlew :reggie-runtime:test --tests '*anchorDiluted_usesNativePathAndAgreesWithJdk*'`
Expected: FAIL (`JavaRegexFallbackMatcher` is returned).

- [ ] **Step 2: Change `anchorConditionDiluted` routing in PatternAnalyzer**

At both sites (lines ~769–781 and ~932–940), change:
```java
MatchingStrategyResult r = new MatchingStrategyResult(
    MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
r.anchorConditionDiluted = true;
return r;
```
to:
```java
// Anchor condition diluted in DFA (misplaced anchor in alternation or
// anchor quantifier). OPTIMIZED_NFA handles anchors as zero-width NFA
// assertions and gives correct JDK-compatible results.
return new MatchingStrategyResult(
    MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
```

- [ ] **Step 3: Remove `anchorConditionDiluted` guard in RuntimeCompiler if field is now unused**

```bash
grep -n "anchorConditionDiluted = true" \
    reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java
```

If empty, remove the `anchorConditionDiluted` field from `MatchingStrategyResult` and the guard at RuntimeCompiler lines 335–341.

- [ ] **Step 4: Run fuzz gate**

```bash
./gradlew :reggie-integration-tests:test \
    --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
    -Dreggie.fuzz.enforceZero=true
```
Expected: 0 findings. If OPTIMIZED_NFA has issues with some anchor-diluted patterns, add targeted guards in `FallbackPatternDetector`.

- [ ] **Step 5: Run tests and full suite**

```bash
./gradlew :reggie-runtime:test --tests '*anchorDiluted_usesNativePathAndAgreesWithJdk*'
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-integration-tests:test
```
Expected: task test PASSES; no new failures.

- [ ] **Step 6: Commit**

```bash
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-runtime/src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: route anchorConditionDiluted patterns to OPTIMIZED_NFA"
```

---

### Task 8: Enable the zero-divergence gate permanently + full validation

- [ ] **Step 1: Enable the `@Disabled` zero-divergence gate**

In `AlgorithmicFuzzTest.java:120–123`, remove `@Disabled`:

```java
// BEFORE:
@Disabled("enabled in Wave C once all divergences are fixed")
@Timeout(value = 600, unit = TimeUnit.SECONDS)
public void zeroDivergenceGate() {

// AFTER:
@Timeout(value = 600, unit = TimeUnit.SECONDS)
public void zeroDivergenceGate() {
```

- [ ] **Step 2: Run the now-enabled gate**

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest.zeroDivergenceGate*'
```
Expected: PASS (0 divergences across 80,000 checks).

- [ ] **Step 3: Run the full test suite**

```bash
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-processor:test :reggie-integration-tests:test
```
Expected: same pre-existing failures as before; `zeroDivergenceGate` now PASSES instead of SKIPPED.

- [ ] **Step 4: Run PCRE conformance**

```bash
./gradlew :reggie-integration-tests:test --tests 'CorrectnessTest'
```
Expected: ≥96.4% (current baseline); no regression from routing changes.

- [ ] **Step 5: Run spotlessApply and build**

```bash
./gradlew spotlessApply && ./gradlew build -x test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add reggie-integration-tests/src/test/java/com/datadoghq/reggie/integration/AlgorithmicFuzzTest.java
git commit -m "feat: enable zero-divergence gate permanently"
```

---

## Deferred items (not in this plan)

These require deeper engine work and are left for future plans:

| Item | Reason deferred |
|---|---|
| **Lazy quantifiers** (`hasLazyQuantifier`, #37) | Needs new `LazyQuantifierBytecodeGenerator` with continuation-passing backtracking. Previous investigation (commit `02e5d68`) found 3 interacting failure modes. |
| **Cross-alt backref deep fix** (`hasCrossAlternativeBackref`) | Requires per-state group arrays (Pike VM style) throughout the NFA simulator. Partial `groupLen<0` guard is in place. |
| **Lookahead in quantified group** (`lookaheadInQuantifier`, #28) | NFA scheduler fix needed; tracked in issue #28. |
| **Lookahead in alternation branch** (`lookaheadInAlternation`, #31) | NFA thread isolation fix; tracked in issue #31. |
| **`captureAmbiguous` with named groups/anchors** | PikeVM doesn't handle named groups / anchors yet; unblocked when PikeVM gains those features. |
| **`MethodTooLargeException` fallback** | Large Grok-style alternations hitting JVM 64KB method limit; needs generated-method splitting. |
