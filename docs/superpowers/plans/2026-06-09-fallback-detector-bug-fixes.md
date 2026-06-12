# FallbackPatternDetector Bug Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate 6 of the 13 active `FallbackPatternDetector` conditions that currently route correct-looking patterns to `java.util.regex`. Each fix either routes the pattern to an existing native strategy that already handles it correctly, or repairs the generator that previously silently produced wrong results.

**Architecture:** Three fix categories:
1. **Routing fixes** — add strategy re-selection before the generator is invoked so the pattern never reaches the broken code path.
2. **Generator fixes** — repair `VariableCaptureBackrefBytecodeGenerator` for two structural limitations: (a) the backtrack loop ignores `groupMaxCount` as an upper bound, and (b) `groupStart` is hardcoded to `0` even when the pattern has a non-anchor prefix.
3. **Deferred** — the remaining 7 conditions require architectural changes (lazy-quantifier generator, Pike VM, lookahead-in-quantifier engine fix) and are explicitly out of scope.

**Tech Stack:** Java 21, ASM 9.7, JUnit 5 Jupiter, Gradle 8.11+. No new dependencies.

---

## Scope

### In scope (6 conditions)

| Condition | Strategy | Fix kind |
|-----------|----------|----------|
| `hasCapturingGroupInQuantifiedSection` | `DFA_UNROLLED`, `DFA_UNROLLED_WITH_ASSERTIONS` | **BLOCKED** — see Task 1 investigation note |
| `hasNullableBackrefGroup` | `VARIABLE_CAPTURE_BACKREF` | Routing: return `null` from `detectVariableCaptureBackref` → falls through to `OPTIMIZED_NFA_WITH_BACKREFS` |
| `hasBoundedQuantifierInBackrefGroup` | `VARIABLE_CAPTURE_BACKREF` | Generator: cap initial `groupEnd` to `groupMaxCount` |
| `hasNonAnchorPrefixBeforeBackrefGroup` | `VARIABLE_CAPTURE_BACKREF` | Generator: emit prefix-matching bytecode; allow non-empty `info.prefix` |
| `hasAlternationInNestedQuantifierContent` | `NESTED_QUANTIFIED_GROUPS` | Routing: return `null` from `detectNestedQuantifiedGroups` → falls through to `RECURSIVE_DESCENT` |
| `hasAlternationWithPrefixOverlap` | `OPTIMIZED_NFA` | Routing: in `analyzeAndRecommend`, try DFA before NFA for non-capturing prefix-overlap patterns |

### Deferred (7 conditions)

| Condition | Reason |
|-----------|--------|
| `lookaheadInQuantifier` (all strategies) | Needs #28 NFA engine fix |
| `hasLazyQuantifier` (`RECURSIVE_DESCENT`, `OPTIMIZED_NFA_WITH_BACKREFS`) | Wave 5 blocked — needs new `LazyQuantifierBytecodeGenerator` |
| `hasCrossAlternativeBackref` (`RECURSIVE_DESCENT`, `OPTIMIZED_NFA_WITH_BACKREFS`) | Wave 6 — needs Pike VM per-state group arrays |
| `hasNullableBackrefGroup` (`OPTIMIZED_NFA_WITH_BACKREFS`) | Effectively dead code: no real pattern reaches it |
| `hasAnchorInQuantifierInCapturingGroup` (all) | Complex per-iteration anchor semantics |
| `hasEndAnchorBeforeNonNewlineConsumer` (all) | Complex DFA model extension |
| `hasLookaheadInAlternation` (`OPTIMIZED_NFA_WITH_LOOKAROUND`) | NFA thread-scheduler refactor |

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java` | Modify | Remove 6 fixed conditions; add clarifying comments on deferred ones |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java` | Modify | (a) Add `hasCapturingGroupInQuantifiedSection` check before `DFA_UNROLLED` / `DFA_UNROLLED_WITH_ASSERTIONS`; (b) make `detectVariableCaptureBackref` return `null` for nullable / bounded / non-anchor-prefix patterns; (c) add `detectNestedQuantifiedGroups` nullable-content guard; (d) add prefix-overlap bypass in the non-capturing DFA path |
| `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java` | Modify | `generateMatchesMethod` + `generateMatchMethod` + all `find*` variants: honour `info.groupMaxCount` as upper bound for initial `groupEnd`; emit prefix-matching code when `info.prefix` is non-empty |
| `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java` | Create | Regression tests for all 6 eliminated conditions |

---

## Task 1 — ~~Route `hasCapturingGroupInQuantifiedSection` away from broken DFA strategies~~

> **STATUS: BLOCKED** (investigated in worktree `fix/capturing-in-quantifier-routing`, commit `28b5c78`)

**Blocker:** Routing `DFA_UNROLLED_WITH_ASSERTIONS` + groups-in-quantifiers to `OPTIMIZED_NFA_WITH_LOOKAROUND` produces wrong `findMatch()` group spans. Investigation showed that `OPTIMIZED_NFA_WITH_LOOKAROUND` itself has a group-span bug for groups inside quantifiers: it records `groupStart = position-after-consuming-char` instead of `position-before-consuming-char`. For `(?<=a)(x)+` on "axx", it reports group 1 start = 3 (= end of string) instead of 2.

**No safe native alternative exists** for patterns with lookaround assertions AND groups inside quantifiers. The `PIKEVM_CAPTURE` strategy does not support lookaround. `RECURSIVE_DESCENT` returns -1 (fail) for lookaround assertions.

**Prerequisite before this task can proceed:** Fix the group-start recording bug in `OPTIMIZED_NFA_WITH_LOOKAROUND` (NFABytecodeGenerator), specifically the per-iteration group-start update in the quantifier simulation.

**Net change committed:** Documentation comment added to `FallbackPatternDetector` explaining the blocker; `hasCapturingGroupInQuantifiedSection` made package-private for future use; regression test `FallbackDetectorBugFixTest` added (verifies correctness, not strategy routing).

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

The strategies `DFA_UNROLLED` and `DFA_UNROLLED_WITH_ASSERTIONS` cannot track per-iteration group spans when a capturing group is inside a quantifier. `PIKEVM_CAPTURE` already handles this correctly (O(n·m), leftmost-greedy). For the assertions variant, `OPTIMIZED_NFA_WITH_LOOKAROUND` is the safe fallback once its group-span bug is fixed.

- [ ] **Step 1: Write failing test**

Create `reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java`:

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

import static org.assertj.core.api.Assertions.assertThat;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for FallbackPatternDetector conditions that were eliminated by routing or
 * generator fixes.
 */
public class FallbackDetectorBugFixTest {

  /** Group inside a quantified section — was routed to JDK via DFA_UNROLLED. */
  static Stream<Arguments> capturingGroupInQuantifiedSection() {
    return Stream.of(
        Arguments.of("(a)+", "aaa"),
        Arguments.of("(a)+", "bbb"),
        Arguments.of("([a-z])+", "abc"),
        Arguments.of("(\\w+)+", "hello"),
        Arguments.of("(\\d)+", "123"),
        Arguments.of("(a)+b", "aaab"),
        Arguments.of("(a)+b", "b"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("capturingGroupInQuantifiedSection")
  void capturingGroupInQuantifiedSection_matchesAgreesWithJdk(String pat, String in)
      throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);

    assertThat(reggie.matches(in)).isEqualTo(jdk.matcher(in).matches());
    assertThat(reggie.find(in)).isEqualTo(jdk.matcher(in).find());

    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertThat(rm != null).isEqualTo(jdkM);
    if (jdkM) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertThat(rm.start(g)).as("match() g%d start", g).isEqualTo(jm.start(g));
        assertThat(rm.end(g)).as("match() g%d end", g).isEqualTo(jm.end(g));
      }
    }
  }
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.capturingGroupInQuantifiedSection*'
```

Expected: FAIL — at least one parameterized case fails (wrong group span or JDK fallback warning observed).

- [ ] **Step 3: Add `hasCapturingGroupInQuantifiedSection` guard in PatternAnalyzer**

In `PatternAnalyzer.java`:

**(a) `DFA_UNROLLED_WITH_ASSERTIONS` path** (around line 444-447, inside the `hasLookaround` block):

```java
// Before:
if (stateCount < 20) {
  return new MatchingStrategyResult(
      MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS, dfa, null, false, requiredLiterals);

// After:
if (stateCount < 20) {
  if (FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
    // DFA cannot track per-iteration spans; NFA with lookaround handles this correctly.
    return new MatchingStrategyResult(
        MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
        null, null, false, requiredLiterals, lookaheadGreedyInfo);
  }
  return new MatchingStrategyResult(
      MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS, dfa, null, false, requiredLiterals);
```

**(b) `DFA_UNROLLED` path** (around line 939-941, inside the non-lookaround, non-backref-group DFA path):

```java
// Before:
if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
  return new MatchingStrategyResult(
      MatchingStrategy.DFA_UNROLLED, dfa, null, false, requiredLiterals);

// After:
if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
  if (FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
    return new MatchingStrategyResult(
        MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
  }
  return new MatchingStrategyResult(
      MatchingStrategy.DFA_UNROLLED, dfa, null, false, requiredLiterals);
```

Note: `hasCapturingGroupInQuantifiedSection` must be made package-visible or the import added. In `PatternAnalyzer.java`, add at the top:

```java
import com.datadoghq.reggie.codegen.analysis.FallbackPatternDetector;
```

(Check if already imported — if so, no change needed.)

- [ ] **Step 4: Remove the condition from FallbackPatternDetector**

In `FallbackPatternDetector.java`, remove or comment out the `hasCapturingGroupInQuantifiedSection` block (lines 160-164):

```java
// REMOVED: now handled upstream in PatternAnalyzer by routing to PIKEVM_CAPTURE /
// OPTIMIZED_NFA_WITH_LOOKAROUND before these strategies are selected.
// if ((strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED
//         || strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS)
//     && hasCapturingGroupInQuantifiedSection(ast)) {
//   return "DFA with capturing group inside quantifier: DFA cannot track per-iteration spans";
// }
```

- [ ] **Step 5: Run test — confirm it passes**

```bash
./gradlew :reggie-runtime:test --tests '*FallbackDetectorBugFixTest.capturingGroupInQuantifiedSection*'
```

Expected: PASS.

- [ ] **Step 6: Run zero-divergence gate to confirm no regressions**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 7: spotlessApply + compile check**

```bash
./gradlew spotlessApply && ./gradlew :reggie-codegen:compileJava :reggie-runtime:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: route DFA_UNROLLED capturing-in-quantifier to PIKEVM / NFA_WITH_LOOKAROUND"
```

---

## Task 2 — Fix `VARIABLE_CAPTURE_BACKREF`: bounded inner quantifier (cap `groupEnd`)

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

**Root cause:** `generateMatchesMethod` (and all other generated methods) initialise `groupEnd = len - separatorMinLen`. When `VariableCaptureBackrefInfo.groupMaxCount != -1` (the group is bounded, e.g. `(-{0,3})`), the loop should start at `min(len - separatorMinLen, groupMaxCount)`. Without the cap, the loop tries `groupEnd > groupMaxCount`, the `groupCharSetValidation` accepts too-long substrings, `regionMatches` spuriously succeeds, and the method returns a match when it should not.

- [ ] **Step 1: Add failing test cases to `FallbackDetectorBugFixTest`**

```java
/** Bounded group content — was routed to JDK via VARIABLE_CAPTURE_BACKREF. */
static Stream<Arguments> variableCaptureBackrefBoundedGroup() {
  return Stream.of(
      Arguments.of("(-{0,3}):\\1", "---:---"),   // should match
      Arguments.of("(-{0,3}):\\1", "----:----"),  // should NOT match (group max=3)
      Arguments.of("(\\w{1,4})=\\1", "abc=abc"),  // should match
      Arguments.of("(\\w{1,4})=\\1", "abcde=abcde")); // should NOT match (group max=4)
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("variableCaptureBackrefBoundedGroup")
void variableCaptureBackrefBoundedGroup_matchesAgreesWithJdk(String pat, String in)
    throws Exception {
  Pattern jdk = Pattern.compile(pat);
  ReggieMatcher reggie = Reggie.compile(pat);
  assertThat(reggie.matches(in))
      .as("matches() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).matches());
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefBoundedGroup*'
```

Expected: FAIL (spurious match for the "should NOT match" cases).

- [ ] **Step 3: Understand current `groupEnd` initialisation in all 8 generated methods**

In `VariableCaptureBackrefBytecodeGenerator`, every generated method that uses the backtrack loop initialises `groupEnd` with the same code:

```java
// Current (lines 749-754 in generateMatchesMethod, analogous in others):
mv.visitVarInsn(ILOAD, lenVar);
pushInt(mv, info.getSeparatorMinLength());
mv.visitInsn(ISUB);
mv.visitVarInsn(ISTORE, groupEndVar);
```

This becomes:
```java
groupEnd = len - separatorMinLen;
```

The fix adds a cap when `groupMaxCount != -1`:
```java
groupEnd = (info.groupMaxCount < 0)
    ? len - separatorMinLen
    : Math.min(len - separatorMinLen, info.groupMaxCount);
```

- [ ] **Step 4: Add a private helper `emitGroupEndInit` to avoid duplication**

In `VariableCaptureBackrefBytecodeGenerator`, add a private helper method BEFORE `generateMatchesMethod`:

```java
/**
 * Emits the bytecode to initialise {@code groupEndVar} at the start of the backtrack loop.
 *
 * <p>Without a max bound the group can occupy up to {@code len - separatorMinLen} characters.
 * When the group's quantifier has an explicit max ({@link VariableCaptureBackrefInfo#groupMaxCount}
 * >= 0), the initial try must not exceed that bound.
 *
 * <p>Generated code (conceptual Java):
 * <pre>
 *   int groupEnd = len - separatorMinLen;
 *   if (info.groupMaxCount >= 0) groupEnd = Math.min(groupEnd, info.groupMaxCount);
 * </pre>
 */
private void emitGroupEndInit(MethodVisitor mv, int groupEndVar, int lenVar) {
  // groupEnd = len - separatorMinLen
  mv.visitVarInsn(ILOAD, lenVar);
  pushInt(mv, info.getSeparatorMinLength());
  mv.visitInsn(ISUB);
  mv.visitVarInsn(ISTORE, groupEndVar);

  if (info.groupMaxCount >= 0) {
    // groupEnd = Math.min(groupEnd, groupMaxCount)
    mv.visitVarInsn(ILOAD, groupEndVar);
    pushInt(mv, info.groupMaxCount);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
    mv.visitVarInsn(ISTORE, groupEndVar);
  }
}
```

- [ ] **Step 5: Replace `groupEnd` initialisation in all 8 generated methods**

Search for every occurrence of:
```java
mv.visitVarInsn(ILOAD, lenVar);
pushInt(mv, info.getSeparatorMinLength());
mv.visitInsn(ISUB);
mv.visitVarInsn(ISTORE, groupEndVar);
```

Replace each with:
```java
emitGroupEndInit(mv, groupEndVar, lenVar);
```

Use grep to find all call sites in the file:
```bash
grep -n "getSeparatorMinLength" \
  reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java
```

Methods to update: `generateMatchesMethod`, `generateMatchMethod`, `generateFindMatchMethod`, `generateFindMatchFromMethod`, and any other methods with a backtrack loop.

- [ ] **Step 6: Remove the condition from FallbackPatternDetector**

Remove the `hasBoundedQuantifierInBackrefGroup` block from `FallbackPatternDetector.needsFallback`:

```java
// REMOVED: now handled by generator — initial groupEnd is capped to info.groupMaxCount.
// if (strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
//     && hasBoundedQuantifierInBackrefGroup(ast)) {
//   return "variable-capture backref with bounded inner quantifier: ...";
// }
```

- [ ] **Step 7: Run the test — confirm it passes**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefBoundedGroup*'
```

Expected: PASS.

- [ ] **Step 8: Run the zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 9: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: VARIABLE_CAPTURE_BACKREF — cap groupEnd to groupMaxCount for bounded quantifiers"
```

---

## Task 3 — Fix `VARIABLE_CAPTURE_BACKREF`: non-anchor prefix support

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

**Root cause:** `generateMatchesMethod` (and all other methods) hardcode `groupStart = 0` (see `// int groupStart = 0;  (for now, no prefix support)` comment at line 741). When `info.prefix` contains non-anchor nodes (e.g. pattern `c(.*)\1` has prefix = `[LiteralNode('c')]`), the generator ignores `c`, `groupStart` starts at 0, and returns a match at the wrong position.

**Note on anchors:** `AnchorNode` elements in the prefix are already handled by `DetectVariableCaptureBackref` — only `AnchorNode.START`/`STRING_START` are accepted as prefix. These do NOT consume characters; they only constrain the starting position. For `^(.*)\1`, `groupStart = 0` is correct. For `c(.*)\1`, `groupStart` must be 1 (after matching 'c').

The fix: after the input-length check, emit a short prefix-matching loop that advances `groupStart` past each prefix node.

- [ ] **Step 1: Add failing test cases**

```java
/** Non-anchor prefix — was routed to JDK via VARIABLE_CAPTURE_BACKREF. */
static Stream<Arguments> variableCaptureBackrefPrefix() {
  return Stream.of(
      Arguments.of("c(.*)\\1", "cabc abc"),   // prefix 'c', group "abc ", backref "abc "
      Arguments.of("c(.*)\\1", "c"),           // only prefix — no room for group+backref
      Arguments.of("ab(.+):\\1", "abfoo:foo"), // 2-char literal prefix
      Arguments.of("ab(.+):\\1", "foo:foo"),   // prefix mismatch — should NOT match
      Arguments.of("ab(.+):\\1", "abxyz:abc")); // group≠backref — should NOT match
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("variableCaptureBackrefPrefix")
void variableCaptureBackrefPrefix_matchesAgreesWithJdk(String pat, String in)
    throws Exception {
  Pattern jdk = Pattern.compile(pat);
  ReggieMatcher reggie = Reggie.compile(pat);
  assertThat(reggie.matches(in))
      .as("matches() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).matches());
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefPrefix*'
```

Expected: FAIL.

- [ ] **Step 3: Understand the prefix structure**

`VariableCaptureBackrefInfo.prefix` is a `List<RegexNode>`. When `detectVariableCaptureBackref` allows a non-anchor prefix, the list contains the non-anchor prefix nodes (e.g. `[LiteralNode('c')]` or `[LiteralNode('a'), LiteralNode('b')]`). Currently the generator ignores them; we must match them and advance `groupStart`.

**Supported prefix node types for matching:**
- `LiteralNode ch` → match `input.charAt(pos) == ch`; advance `pos++`.
- `CharClassNode` → match `charset.contains(input.charAt(pos))`; advance `pos++`.
- `AnchorNode.START` / `STRING_START` → zero-width; no advancement (handled via the existing `hasStartAnchor` flag).

Multi-char prefix nodes (e.g. `AnchorNode.STRING_END`, `QuantifierNode`) are not valid in the prefix list as `detectVariableCaptureBackref` rejects complex prefixes.

- [ ] **Step 4: Add a private helper `emitPrefixMatch` to the generator**

Add after `emitGroupEndInit`:

```java
/**
 * Emits bytecode to match all non-anchor prefix nodes and advance {@code groupStartVar} past
 * them. On mismatch, jumps to {@code returnFalse}.
 *
 * <p>Anchor nodes (START/STRING_START) are zero-width: they are recorded in
 * {@link VariableCaptureBackrefInfo#hasStartAnchor} and handled by the caller as a position
 * guard, not here.
 *
 * <p>Generated code (conceptual Java):
 * <pre>
 *   for each prefix node:
 *     if (node is LiteralNode(ch)) {
 *       if (groupStart >= len || input.charAt(groupStart) != ch) goto returnFalse;
 *       groupStart++;
 *     }
 *     // AnchorNode: no code emitted (zero-width, already checked)
 * </pre>
 */
private void emitPrefixMatch(
    MethodVisitor mv, int groupStartVar, int lenVar, Label returnFalse) {
  for (RegexNode node : info.prefix) {
    if (node instanceof AnchorNode) {
      // Zero-width; hasStartAnchor enforces position 0 at a higher level. Skip.
      continue;
    }
    if (node instanceof LiteralNode) {
      char ch = ((LiteralNode) node).ch;
      // if (groupStart >= len) goto returnFalse
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, returnFalse);
      // if (input.charAt(groupStart) != ch) goto returnFalse
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, ch);
      mv.visitJumpInsn(IF_ICMPNE, returnFalse);
      // groupStart++
      mv.visitIincInsn(groupStartVar, 1);
    } else if (node instanceof CharClassNode) {
      CharSet cs = ((CharClassNode) node).chars;
      // if (groupStart >= len) goto returnFalse
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, returnFalse);
      // if (!charset.contains(input.charAt(groupStart))) goto returnFalse
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      BytecodeUtil.emitCharSetContains(mv, cs, returnFalse, /* jumpIfNotContains= */ true);
      // groupStart++
      mv.visitIincInsn(groupStartVar, 1);
    }
    // Other node types are not present in a valid prefix list.
  }
}
```

**Note:** `BytecodeUtil.emitCharSetContains` is a hypothetical helper. Look for the actual charset-matching idiom used in other generators (e.g., `DFAUnrolledBytecodeGenerator`, `GreedyCharClassBytecodeGenerator`) and use the same pattern. The key bytecode sequence for a charset `cs` on a char on stack is typically a `LOOKUPSWITCH` or a range check + bitset check depending on how `CharSet.emitContains(mv, label)` works. Search for:

```bash
grep -n "emitContains\|containsCheck\|charSetMatch" \
  reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/*.java | head -20
```

Use the same idiom found there.

- [ ] **Step 5: Call `emitPrefixMatch` in every generated method, after `groupStart = 0` and before `emitGroupEndInit`**

In `generateMatchesMethod`, change the block starting at line 741:

```java
// Before:
// int groupStart = 0;  (for now, no prefix support)
mv.visitInsn(ICONST_0);
mv.visitVarInsn(ISTORE, groupStartVar);

// int groupEnd = len - separatorMinLen;
mv.visitVarInsn(ILOAD, lenVar);
...

// After:
// int groupStart = 0;
mv.visitInsn(ICONST_0);
mv.visitVarInsn(ISTORE, groupStartVar);

// Match non-anchor prefix nodes and advance groupStart
emitPrefixMatch(mv, groupStartVar, lenVar, returnFalse);

// int groupEnd = min(len - separatorMinLen [, groupMaxCount])
emitGroupEndInit(mv, groupEndVar, lenVar);
```

Note: `emitGroupEndInit` now correctly uses `len - separatorMinLen` as the upper bound; the prefix offset is in `groupStart`, not subtracted from `len`. Verify that all "room for backref" and "end of input" checks that reference `groupStart` still produce correct results when `groupStart > 0`.

Repeat the same change for: `generateMatchMethod`, `generateFindMethod`, `generateFindFromMethod`, `generateFindMatchMethod`, `generateFindMatchFromMethod`.

For `find` variants, `returnFalse` is the label that jumps to the "try next start position" logic. Map carefully.

- [ ] **Step 6: Allow non-anchor-prefix in `detectVariableCaptureBackref`**

The `hasNonAnchorPrefixBeforeBackrefGroup` guard in `FallbackPatternDetector` currently catches patterns that `detectVariableCaptureBackref` would reject anyway (because of the same structural analysis). Verify by checking how `detectVariableCaptureBackref` handles prefixes:

In `PatternAnalyzer.detectVariableCaptureBackref`, the prefix is built as:
```java
List<RegexNode> prefix = new ArrayList<>(children.subList(startIdx, groupIdx));
```

Currently the returned `VariableCaptureBackrefInfo` is used even when `prefix` contains non-anchor nodes. The `FallbackPatternDetector` then intercepts and falls back to JDK. After the generator fix, `info.prefix` is correctly matched, so `detectVariableCaptureBackref` can continue returning a result for non-anchor prefix patterns.

Confirm that `detectVariableCaptureBackref` does NOT already filter them out. If it does, remove that filter.

- [ ] **Step 7: Remove the condition from FallbackPatternDetector**

```java
// REMOVED: generator now emits prefix-matching bytecode for non-anchor prefix nodes.
// if (strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
//     && hasNonAnchorPrefixBeforeBackrefGroup(ast)) {
//   return "variable-capture backref with non-anchor prefix: ...";
// }
```

- [ ] **Step 8: Run test — confirm it passes**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefPrefix*'
```

Expected: PASS.

- [ ] **Step 9: Zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 10: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/codegen/VariableCaptureBackrefBytecodeGenerator.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: VARIABLE_CAPTURE_BACKREF — emit prefix-matching bytecode for non-anchor prefixes"
```

---

## Task 4 — Route `VARIABLE_CAPTURE_BACKREF` nullable-group patterns to `OPTIMIZED_NFA_WITH_BACKREFS`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

**Root cause:** When the backref group is nullable (e.g. `(a*)=\1`, `(b*)\1`), the generator's `find()` and `findFrom()` methods can produce spurious zero-length matches because `regionMatches` with `length=0` returns `true` at any position, and the find loop does not enforce a minimum match advance. Fixing all 8 generated methods for zero-length group captures is non-trivial; routing to `OPTIMIZED_NFA_WITH_BACKREFS` is a safe alternative — that strategy handles nullable groups correctly.

**Note:** This is a routing fix only. The `OPTIMIZED_NFA_WITH_BACKREFS` strategy also has `hasNullableBackrefGroup` guard, but that guard is for a DIFFERENT bug (shared group arrays across parallel NFA threads). Investigation (Wave 6) showed that bug is dead code — no real patterns trigger it in this strategy after earlier routing changes. Verify this is still true before removing that guard.

- [ ] **Step 1: Add failing test cases**

```java
/** Nullable backref group — was routed to JDK via VARIABLE_CAPTURE_BACKREF. */
static Stream<Arguments> variableCaptureBackrefNullableGroup() {
  return Stream.of(
      Arguments.of("(a*)=\\1", "abc=abc"),   // non-empty capture
      Arguments.of("(a*)=\\1", "="),           // empty capture + empty backref (= matches "=")
      Arguments.of("(a*)=\\1", "a=a"),         // single-char
      Arguments.of("(-*):\\1", "---:---"),     // non-trivial case
      Arguments.of("(b*)\\1", "bb"),           // no separator, both sides non-empty
      Arguments.of("(b*)\\1", ""));            // empty match
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("variableCaptureBackrefNullableGroup")
void variableCaptureBackrefNullableGroup_matchesAgreesWithJdk(String pat, String in)
    throws Exception {
  Pattern jdk = Pattern.compile(pat);
  ReggieMatcher reggie = Reggie.compile(pat);

  assertThat(reggie.matches(in))
      .as("matches() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).matches());
  assertThat(reggie.find(in))
      .as("find() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).find());
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefNullableGroup*'
```

Expected: FAIL.

- [ ] **Step 3: Make `detectVariableCaptureBackref` return `null` for nullable groups**

In `PatternAnalyzer.detectVariableCaptureBackref`, just before the `return new VariableCaptureBackrefInfo(...)` line, add:

```java
// Don't handle nullable groups — find() would produce spurious zero-length matches.
// Fall through to OPTIMIZED_NFA_WITH_BACKREFS which handles them correctly.
if (groupQuantifier.min == 0) {
  return null;
}
```

This causes the nullable pattern to skip `VARIABLE_CAPTURE_BACKREF` and fall through to the generic `OPTIMIZED_NFA_WITH_BACKREFS` selection at line 676.

- [ ] **Step 4: Verify `hasNullableBackrefGroup` guard in `OPTIMIZED_NFA_WITH_BACKREFS` is still inactive**

The guard at `FallbackPatternDetector` lines 107-110 catches nullable groups in `OPTIMIZED_NFA_WITH_BACKREFS`. Wave 6 determined this is dead code today. To verify it remains dead: run:

```bash
./gradlew :reggie-integration-tests:test --tests '*AlgorithmicFuzzTest*'
```

If the fuzz gate finds NEW failures mentioning "backref to nullable group", the guard is NOT dead and we need to keep it (the fallback to JDK is correct). If 0 findings, proceed.

- [ ] **Step 5: Remove `VARIABLE_CAPTURE_BACKREF` nullable-group condition from FallbackPatternDetector**

```java
// REMOVED: detectVariableCaptureBackref now returns null for nullable groups,
// routing them to OPTIMIZED_NFA_WITH_BACKREFS. This FallbackPatternDetector
// guard can never fire for VARIABLE_CAPTURE_BACKREF anymore.
// if (strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
//     && hasNullableBackrefGroup(ast)) {
//   return "variable-capture backref to nullable group: ...";
// }
```

- [ ] **Step 6: Run test — confirm it passes**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.variableCaptureBackrefNullableGroup*'
```

Expected: PASS.

- [ ] **Step 7: Zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: VARIABLE_CAPTURE_BACKREF — route nullable groups to OPTIMIZED_NFA_WITH_BACKREFS"
```

---

## Task 5 — Route `NESTED_QUANTIFIED_GROUPS` with inner alternation to `RECURSIVE_DESCENT`

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

**Root cause:** `NestedQuantifiedGroupsBytecodeGenerator` dispatches inner content with a series of `if (content instanceof X)` checks. When `content instanceof AlternationNode`, no branch matches and the code falls through to an "accept-any-char" stub that ignores the alternation structure, producing false matches. Fixing the generator to support inner alternation is a medium-complexity change; routing to `RECURSIVE_DESCENT` avoids the risk and is sufficient to eliminate the JDK fallback.

- [ ] **Step 1: Add failing test cases**

```java
/** Nested quantified groups with inner alternation — was routed to JDK. */
static Stream<Arguments> nestedQuantifiedGroupsWithAlt() {
  return Stream.of(
      Arguments.of("((a|b)+)*", "abab"),    // outer * inner +, alternation in inner
      Arguments.of("((a|b)+)*", "ccc"),     // should NOT match
      Arguments.of("((a|bc)+)*", "abcabc"), // alternation with different lengths
      Arguments.of("((a|bc)+)*x", "abcx"),  // with suffix
      Arguments.of("((a|b)*)+", "aab"),     // inner * outer +
      Arguments.of("((a|b)+)*", ""));       // empty input
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("nestedQuantifiedGroupsWithAlt")
void nestedQuantifiedGroupsWithAlt_matchesAgreesWithJdk(String pat, String in)
    throws Exception {
  Pattern jdk = Pattern.compile(pat);
  ReggieMatcher reggie = Reggie.compile(pat);

  assertThat(reggie.matches(in))
      .as("matches() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).matches());
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.nestedQuantifiedGroupsWithAlt*'
```

Expected: FAIL (spurious matches on "ccc" or similar).

- [ ] **Step 3: Make `detectNestedQuantifiedGroups` return `null` when inner content is an alternation**

In `PatternAnalyzer.detectNestedQuantifiedGroups`, after extracting `innerContent`, add:

```java
// When inner content is an alternation, the NestedQuantifiedGroupsBytecodeGenerator
// falls through to an accept-any-char stub. Route to RECURSIVE_DESCENT instead.
if (innerContent instanceof AlternationNode) {
  return null;
}
```

Find the exact location by searching for where `innerContent` is used in `detectNestedQuantifiedGroups`:

```bash
grep -n "detectNestedQuantifiedGroups\|innerContent\|NestedQuantifiedGroupsInfo" \
  reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java | head -20
```

After adding the guard, patterns with inner alternation fall through to `requiresBacktrackingForGroups(ast)` at line 736, which returns `true` for these patterns, routing them to `RECURSIVE_DESCENT`.

- [ ] **Step 4: Remove the condition from FallbackPatternDetector**

```java
// REMOVED: detectNestedQuantifiedGroups returns null for inner-alternation patterns,
// routing them to RECURSIVE_DESCENT. This guard can no longer fire for NESTED_QUANTIFIED_GROUPS.
// if (strategy == PatternAnalyzer.MatchingStrategy.NESTED_QUANTIFIED_GROUPS
//     && hasAlternationInNestedQuantifierContent(ast)) {
//   return "nested quantified groups with alternation in inner content: ...";
// }
```

- [ ] **Step 5: Run test — confirm it passes**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.nestedQuantifiedGroupsWithAlt*'
```

Expected: PASS.

- [ ] **Step 6: Zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 7: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: NESTED_QUANTIFIED_GROUPS — route inner-alternation patterns to RECURSIVE_DESCENT"
```

---

## Task 6 — Route `OPTIMIZED_NFA` prefix-overlap alternation to DFA

**Files:**
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java`
- Modify: `reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java`

**Root cause:** Patterns like `fo|foo`, `a|ab`, etc. end up in `OPTIMIZED_NFA` (via the `alternationPriorityConflict` path or after DFA state explosion). The NFA simulation is leftmost-first, giving `fo` for input `foo`, while JDK gives `foo` (leftmost-longest). DFA naturally gives longest-match, which is correct.

**Scope:** This fix targets non-capturing patterns (`nfa.getGroupCount() == 0`) where:
1. The selected strategy is `OPTIMIZED_NFA` with `alternationPriorityConflict = false` (i.e., the pattern didn't trigger the priority-cut flag but still ends up in OPTIMIZED_NFA due to DFA failure), AND
2. `hasAlternationWithPrefixOverlap(ast)` is true.

For patterns that DID trigger `alternationPriorityConflict = true`, the issue is the `alternationPriorityConflict` guard in `RuntimeCompiler` (separate concern; not addressed here).

**Investigation required:** Confirm that the DFA for non-capturing prefix-overlap patterns produces the correct longest-match result by testing `DFA_UNROLLED` / `DFA_SWITCH` against JDK for these patterns. The DFA naturally implements longest-match; the `dfaHasAcceptingStateWithTransitions` check that gates `alternationPriorityConflict` may be overly conservative for these specific patterns.

- [ ] **Step 1: Add failing test cases**

```java
/** Prefix-overlap alternation in OPTIMIZED_NFA — was routed to JDK. */
static Stream<Arguments> prefixOverlapAlternation() {
  return Stream.of(
      Arguments.of("fo|foo", "foo"),    // JDK: longest match "foo", NFA: first match "fo"
      Arguments.of("a|ab", "ab"),       // JDK: "ab", NFA: "a"
      Arguments.of("cat|catch", "catch")); // JDK: "catch", NFA: "cat"
}

@ParameterizedTest(name = "[{index}] pat={0} in={1}")
@MethodSource("prefixOverlapAlternation")
void prefixOverlapAlternation_findAgreesWithJdk(String pat, String in)
    throws Exception {
  Pattern jdk = Pattern.compile(pat);
  ReggieMatcher reggie = Reggie.compile(pat);

  assertThat(reggie.find(in))
      .as("find() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).find());
  assertThat(reggie.matches(in))
      .as("matches() for pat=%s in=%s", pat, in)
      .isEqualTo(jdk.matcher(in).matches());
}
```

- [ ] **Step 2: Run test — confirm it fails**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.prefixOverlapAlternation*'
```

Expected: FAIL (wrong `find()` result for `fo|foo` on `foo`).

- [ ] **Step 3: Identify which code path selects OPTIMIZED_NFA for these patterns**

Run the debugPattern tool on `fo|foo`:
```bash
./gradlew :reggie-runtime:debugPattern -Ppattern="fo|foo"
```

Examine whether `alternationPriorityConflict` is set or whether the pattern reaches `OPTIMIZED_NFA` via another path (e.g., DFA state explosion, or the non-capturing path that also sets `alternationPriorityConflict`).

If `alternationPriorityConflict = true`: the `RuntimeCompiler` routes to JDK. To fix this, we'd need to allow the DFA for these simple prefix-overlap patterns by not setting the flag. But the `alternationPriorityConflict` guard exists for important reasons. **Defer** this sub-case unless investigation shows it's safe.

If `alternationPriorityConflict = false` and strategy is `OPTIMIZED_NFA`: the pattern ended up in the NFA path without the priority flag (e.g., DFA state explosion). In this case, check if DFA construction succeeds — if so, use the DFA result instead.

- [ ] **Step 4: Add DFA-first retry in the OPTIMIZED_NFA non-capturing path**

In `PatternAnalyzer`, in the section that returns `OPTIMIZED_NFA` for the non-capturing path (around line 956-960), add a check before falling through:

```java
// If the pattern has prefix-overlap alternation (e.g. fo|foo), the NFA simulation
// returns leftmost-first which disagrees with JDK's longest-match. Try DFA instead —
// DFA naturally gives longest-match for non-capturing patterns.
if (!containsAlternation(ast) == false
    && FallbackPatternDetector.hasAlternationWithPrefixOverlap(ast)) {
  // DFA was already built (in the try block above). If it's usable and small, use it.
  // The dfa variable from the outer try may be available here; check scope.
  // [Implementation note: restructure the try/catch to retain the dfa reference
  //  after the StateExplosionException path exits early.]
}
```

**Important:** This step requires careful investigation of the code flow. The DFA might have been built but discarded (in the `alternationPriorityConflict` path) or never built (in the `StateExplosionException` path). The exact code change depends on what `debugPattern` reveals in Step 3. Write the final code only after examining the actual code path for `fo|foo`.

- [ ] **Step 5: Remove the condition from FallbackPatternDetector only after Step 4 is confirmed working**

```java
// REMOVED: PatternAnalyzer now routes prefix-overlap OPTIMIZED_NFA patterns to DFA.
// if (strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
//     && hasAlternationWithPrefixOverlap(ast)) {
//   return "alternation with prefix-overlap: ...";
// }
```

- [ ] **Step 6: Run test — confirm it passes**

```bash
./gradlew :reggie-runtime:test \
  --tests '*FallbackDetectorBugFixTest.prefixOverlapAlternation*'
```

Expected: PASS.

- [ ] **Step 7: Zero-divergence gate**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 8: Commit**

```bash
./gradlew spotlessApply
git add reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java \
        reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java \
        reggie-runtime/src/test/java/com/datadoghq/reggie/runtime/FallbackDetectorBugFixTest.java
git commit -m "fix: route prefix-overlap OPTIMIZED_NFA alternation to DFA for longest-match"
```

---

## Task 7 — Full validation

- [ ] **Step 1: Full test suite**

```bash
./gradlew :reggie-codegen:test :reggie-runtime:test :reggie-processor:test :reggie-integration-tests:test
```

Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 2: Zero-divergence gate (final)**

```bash
./gradlew :reggie-integration-tests:test \
  --tests '*AlgorithmicFuzzTest.zeroDivergenceGate_enforcedViaProperty' \
  -Dreggie.fuzz.enforceZero=true
```

Expected: PASS at 0 findings.

- [ ] **Step 3: Strategy meta-test**

```bash
./gradlew :reggie-runtime:test --tests '*StrategyCorrectnessMetaTest*' -Dreggie.metatest.enforce=true
```

Expected: 0 mismatches.

- [ ] **Step 4: FallbackPatternDetector condition count**

Verify that the 6 conditions removed from `FallbackPatternDetector.needsFallback` are gone:

```bash
grep -c "return \"" \
  reggie-codegen/src/main/java/com/datadoghq/reggie/codegen/analysis/FallbackPatternDetector.java
```

Before this plan: 13 `return "..."` lines. After: 7 (the 6 deferred conditions + the null return).

- [ ] **Step 5: PCRE conformance check**

```bash
./gradlew :reggie-integration-tests:test --tests 'CorrectnessTest'
```

Expect no regression in pass rate (currently 97.1% / 340 of 364). Some patterns previously falling back to JDK may now be handled natively; the pass rate should stay equal or improve.

- [ ] **Step 6: spotlessApply + full build**

```bash
./gradlew spotlessApply && ./gradlew build
```

Expected: BUILD SUCCESSFUL.

---

## StructuralHash Verification

No new fields are added to `DFAState`, `DFATransition`, `NFAState`, or any `PatternInfo` subclass. The routing changes in `PatternAnalyzer` select existing strategies (PIKEVM_CAPTURE, RECURSIVE_DESCENT, OPTIMIZED_NFA_WITH_BACKREFS, OPTIMIZED_NFA_WITH_LOOKAROUND) which already have correct structural hashes. The `VariableCaptureBackrefInfo` changes are internal behavioural (not structural — existing fields `groupMaxCount` and `prefix` were already in the hash):

```java
// In VariableCaptureBackrefInfo.structuralHashCode(), both are already included:
hash = 31 * hash + groupMaxCount;           // already present
hash = 31 * hash + prefix.size();          // already present (size, not content)
```

**Note:** If `emitPrefixMatch` uses the prefix list content (not just size), verify that the structural hash includes the prefix content (not just size). If `prefix.size()` is insufficient, update `structuralHashCode()` to hash each prefix node's content.

---

## Deferred Conditions Reference

These 7 conditions remain in `FallbackPatternDetector` and continue to route to `java.util.regex`:

| Line | Condition | Why deferred |
|------|-----------|-------------|
| 59 | `lookaheadInQuantifier` | #28 — NFA engine fix needed; 52 fuzz findings when guard removed (Wave 5) |
| 66 | `hasAnchorInQuantifierInCapturingGroup` | Complex: needs per-iteration anchor semantics in capture tracking |
| 73 | `hasEndAnchorBeforeNonNewlineConsumer` | DFA model extension for `$\Z` before non-`\n` consumer |
| 88 | `hasLazyQuantifier` (RD + NFA_BACKREFS) | Wave 5 blocked — needs `LazyQuantifierBytecodeGenerator` with continuation-passing backtracking |
| 97 | `hasCrossAlternativeBackref` (RD + NFA_BACKREFS) | Wave 6 — needs Pike VM per-state group arrays |
| 107 | `hasNullableBackrefGroup` (NFA_BACKREFS) | Dead code per Wave 6 investigation — safe to leave; add comment |
| 131 | `hasLookaheadInAlternation` (NFA_LOOKAROUND) | NFA thread-scheduler refactor needed |
