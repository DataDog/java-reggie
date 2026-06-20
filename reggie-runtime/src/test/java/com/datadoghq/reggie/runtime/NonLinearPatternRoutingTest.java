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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the D1 detector ({@code requiresIntraCallBacktracking}) in {@code
 * FallbackPatternDetector}. Verifies that patterns requiring intra-call backtracking (subroutine
 * call inside an alternation arm) throw {@link UnsupportedPatternException} from {@code
 * Reggie.compile()}, while patterns that do not require it compile successfully.
 */
class NonLinearPatternRoutingTest {

  @BeforeEach
  void clearCache() {
    Reggie.clearCache();
  }

  // ── Positive cases: must throw UnsupportedPatternException ──────────────────

  @Test
  void palindromeWithNumberedSubroutineInAlternationArm_throws() {
    // ^((.)(?1)\2|.?)$ — (?1) is inside an alternation arm (palindrome, issue #38).
    // D1 flags this: the call site of (?1) is reachable from inside the first alternative.
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile("^((.)(?1)\\2|.?)$"),
            "palindrome (?1) in alternation arm must throw");
    String msg = ex.getMessage();
    assertTrue(
        msg.contains("recursive subroutine") || msg.contains("context-free"),
        "message should mention 'recursive subroutine' or 'context-free', got: " + msg);
  }

  @Test
  void palindromeWithWholePatternRecursionInAlternationArm_throws() {
    // ^((.)(?R)\2|.?)$ — (?R) inside an alternation arm (palindrome variant).
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile("^((.)(?R)\\2|.?)$"),
            "palindrome (?R) in alternation arm must throw");
    String msg = ex.getMessage();
    assertTrue(
        msg.contains("recursive subroutine") || msg.contains("context-free"),
        "message should mention 'recursive subroutine' or 'context-free', got: " + msg);
  }

  @Test
  void topLevelAlternationWithWholePatternRecursion_throws() {
    // x|(?R) — (?R) directly in a top-level alternation arm.
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile("x|(?R)"),
            "(?R) in top-level alternation arm must throw");
    String msg = ex.getMessage();
    assertTrue(
        msg.contains("recursive subroutine") || msg.contains("context-free"),
        "message should mention 'recursive subroutine' or 'context-free', got: " + msg);
  }

  @Test
  void groupAlternationWithWholePatternRecursion_throws() {
    // ([^()]|(?R)) — (?R) in a group alternation arm.
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile("([^()]|(?R))"),
            "(?R) in group alternation arm must throw");
    String msg = ex.getMessage();
    assertTrue(
        msg.contains("recursive subroutine") || msg.contains("context-free"),
        "message should mention 'recursive subroutine' or 'context-free', got: " + msg);
  }

  // ── Negative cases: must NOT throw ──────────────────────────────────────────

  @Test
  void subroutineInConcat_notInAlternationArm_compiles() {
    // ^(a?)b(?1)a — (?1) is in the concat (not inside any alternation arm).
    // D1 does not flag this; it must compile successfully and produce correct results.
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("^(a?)b(?1)a"));
    assertTrue(m.matches("aba"), "^(a?)b(?1)a should match 'aba'");
    assertEquals("a", m.match("aba").group(1), "group(1) should be 'a' for 'aba'");
  }

  @Test
  void conditionalNotASubroutineRecursion_compiles() {
    // (a)(?(1)yes|no) — group 1 is defined before the conditional, which is not a recursive
    // subroutine. Must compile; D1 must not flag it.
    assertDoesNotThrow(() -> Reggie.compile("(a)(?(1)yes|no)"));
  }

  @Test
  void forwardSubroutineCallInConcat_compiles() {
    // (?1)(a(b)|(c)) — forward subroutine call at concat level (not inside an alternation arm).
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("(?1)(a(b)|(c))"));
    assertTrue(m.matches("abc"), "(?1)(a(b)|(c)) should match 'abc'");
  }

  @Test
  void subroutineInConcatWithAlternationInTarget_compiles() {
    // (a|)*(?1)b — call site of (?1) is in the top-level concat, not in an alternation arm.
    // Although the target group body contains alternation, D1 evaluates the call site only.
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("(a|)*(?1)b"));
    assertTrue(m.matches("b"), "(a|)*(?1)b should match 'b'");
  }

  // ── compileAllowingFallback for D1-positive patterns ────────────────────────

  @Test
  void palindromeWithAllowFallback_propagatesJdkPatternSyntaxException() {
    // Reggie.compileAllowingFallback() lifts the UnsupportedPatternException gate and attempts
    // to delegate to java.util.regex. However, java.util.regex does not understand PCRE
    // subroutine syntax (?1) / (?R) — it throws PatternSyntaxException. This test documents
    // that the JDK delegation path is taken (no UnsupportedPatternException from Reggie), but
    // java.util.regex itself cannot parse the pattern.
    assertThrows(
        java.util.regex.PatternSyntaxException.class,
        () -> Reggie.compileAllowingFallback("^((.)(?1)\\2|.?)$"),
        "JDK does not support PCRE (?1) syntax; PatternSyntaxException is expected from JDK");
  }

  // ── Under-classification gap — documented with assertDoesNotThrow ─────────────

  @Test
  void underClassificationGap_callSiteNotInAlternation_doesNotThrow() {
    // Known D1 under-classification gap: call site is not inside an alternation arm,
    // so D1 does not flag this even though the target body has alternation.
    // RECURSIVE_DESCENT handles this case; whether it produces correct results is an open
    // audit item.
    //
    // ^(?1)(a|b)$ — (?1) is at the top-level concat (not inside any alternation arm).
    // Group 1's body is (a|b) which contains alternation. D1 evaluates the CALL SITE only:
    // since (?1) is not inside an alternation arm, the heuristic does not flag it. This is
    // the documented gap: a top-level (?N) whose target body has alternation is not detected.
    assertDoesNotThrow(
        () -> Reggie.compile("^(?1)(a|b)$"),
        "(?1) at top-level concat is not flagged by D1 (documented under-classification gap)");
  }
}
