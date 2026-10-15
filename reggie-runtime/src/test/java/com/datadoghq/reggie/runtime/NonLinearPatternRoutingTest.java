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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for subroutine calls inside alternation arms. The D1 detector ({@code
 * requiresIntraCallBacktracking} in {@code FallbackPatternDetector}) rejects these for the
 * RECURSIVE_DESCENT generator; on the backtracker, which retries every choice point inside a call,
 * they compile and match as PCRE2 does.
 */
class NonLinearPatternRoutingTest {

  @BeforeEach
  void clearCache() {
    Reggie.clearCache();
  }

  // ── Call inside an alternation arm: runs on the backtracker ────────────────

  @Test
  void palindromeWithNumberedSubroutineInAlternationArm_matches() {
    // ^((.)(?1)\2|.?)$ — (?1) is inside an alternation arm (palindrome, issue #38).
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("^((.)(?1)\\2|.?)$"));
    assertTrue(m.matches("abcba"));
    assertTrue(m.matches("abba"));
    assertFalse(m.matches("abc"));
  }

  @Test
  void palindromeWithWholePatternRecursionInAlternationArm_matches() {
    // ^((.)(?R)\2|.?)$ — (?R) recurses with the anchors, so ^ fails inside it (PCRE2 10.47).
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("^((.)(?R)\\2|.?)$"));
    assertTrue(m.matches("a"));
    assertFalse(m.matches("aba"));
  }

  @Test
  void topLevelAlternationWithWholePatternRecursion_matches() {
    // x|(?R) — (?R) at the position it was called from fails that path (PCRE2 reports an error).
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("x|(?R)"));
    assertTrue(m.matches("x"));
    assertFalse(m.find("y"));
  }

  @Test
  void groupAlternationWithWholePatternRecursion_matches() {
    // ([^()]|(?R)) — (?R) in a group alternation arm.
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("([^()]|(?R))"));
    MatchResult r = m.findMatch("a");
    assertNotNull(r);
    assertEquals("a", r.group(1));
    assertFalse(m.find("()"));
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
  void palindromeWithAllowFallback_runsNatively() {
    // java.util.regex does not parse (?1); the pattern must not reach it.
    ReggieMatcher m = Reggie.compileAllowingFallback("^((.)(?1)\\2|.?)$");
    assertInstanceOf(BackrefBacktrackMatcher.class, m);
    assertTrue(m.matches("abcba"));
  }

  // ── Call site outside an alternation arm, alternation in the target ──────────

  @Test
  void callSiteNotInAlternation_targetWithAlternation_matches() {
    // ^(?1)(a|b)$ — D1 evaluates the call site only and does not flag this pattern; the call
    // retries (a|b) like any other choice point (PCRE2 10.47).
    ReggieMatcher m = assertDoesNotThrow(() -> Reggie.compile("^(?1)(a|b)$"));
    assertTrue(m.matches("ab"));
    assertEquals("b", m.match("ab").group(1));
    assertFalse(m.matches("a"));
  }
}
