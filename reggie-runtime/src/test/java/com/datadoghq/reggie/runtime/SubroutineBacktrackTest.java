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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for Cat A subroutine backtracking failures.
 *
 * <p>These tests document cases where the continuation after a subroutine call needs to backtrack
 * into the subroutine to try a shorter match. The current recursive-descent implementation commits
 * to the first match the subroutine returns and does not retry on failure.
 *
 * <p>All tests in this class are expected to FAIL (RED) until the backtrackable-subroutine feature
 * is implemented.
 */
class SubroutineBacktrackTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /**
   * Returns the group(1) string that JDK produces for a full-string match, or null if JDK does not
   * match.
   */
  private static String jdkGroup1(String pattern, String input) {
    Matcher m = Pattern.compile(pattern).matcher(input);
    return m.matches() ? m.group(1) : null;
  }

  // -----------------------------------------------------------------------
  // Test 1: ^(a?)b(?1)a on "aba"
  //
  // Mechanism:
  //   (a?) greedy -> matches 'a' at pos 0 -> pos 1
  //   b           -> matches at pos 1 -> pos 2
  //   (?1)        -> tries (a?) greedy: matches 'a' at pos 2 -> pos 3
  //   literal 'a' -> pos 3 is end-of-string -> FAILS
  //   Backtrack into (?1): try (a?) empty -> pos 2
  //   literal 'a' -> matches at pos 2 -> pos 3 -> end of string -> SUCCESS
  //   group 1 = "a" (the outer capture at pos 0)
  // -----------------------------------------------------------------------

  @Test
  void optionalGroupSubroutine_aba_notFallback() {
    ReggieMatcher m = Reggie.compile("^(a?)b(?1)a");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "Pattern ^(a?)b(?1)a must route to RECURSIVE_DESCENT, not JavaRegexFallbackMatcher");
  }

  @Test
  void optionalGroupSubroutine_aba_matches() {
    ReggieMatcher m = Reggie.compile("^(a?)b(?1)a");
    // JDK does not support (?1) — we assert against PCRE-expected behaviour
    assertTrue(m.matches("aba"), "^(a?)b(?1)a should match 'aba'");
  }

  @Test
  void optionalGroupSubroutine_aba_group1() {
    ReggieMatcher m = Reggie.compile("^(a?)b(?1)a");
    MatchResult result = m.match("aba");
    assertNotNull(result, "^(a?)b(?1)a must produce a MatchResult for 'aba'");
    assertEquals("a", result.group(1), "group 1 should be 'a'");
  }

  // -----------------------------------------------------------------------
  // Test 2: ^(a?)+b(?1)a on "ba"
  //
  // Mechanism:
  //   (a?)+ -> zero iterations, empty match at pos 0
  //   b     -> matches at pos 0 -> pos 1
  //   (?1)  -> tries (a?) greedy: matches 'a' at pos 1 -> pos 2
  //   'a'   -> pos 2 is end-of-string -> FAILS
  //   Backtrack into (?1): try empty -> pos 1
  //   'a'   -> matches at pos 1 -> pos 2 -> SUCCESS
  // -----------------------------------------------------------------------

  @Test
  void quantifiedGroupSubroutine_ba_notFallback() {
    ReggieMatcher m = Reggie.compile("^(a?)+b(?1)a");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "Pattern ^(a?)+b(?1)a must route to RECURSIVE_DESCENT, not JavaRegexFallbackMatcher");
  }

  @Test
  void quantifiedGroupSubroutine_ba_matches() {
    ReggieMatcher m = Reggie.compile("^(a?)+b(?1)a");
    assertTrue(m.matches("ba"), "^(a?)+b(?1)a should match 'ba'");
  }

  // -----------------------------------------------------------------------
  // Test 3: ^(a?)+b(?1)a on "aba"
  //
  // Mechanism:
  //   (a?)+ -> matches 'a' at pos 0 -> pos 1
  //   b     -> matches at pos 1 -> pos 2
  //   (?1)  -> tries (a?) greedy: matches 'a' at pos 2 -> pos 3
  //   'a'   -> pos 3 is end-of-string -> FAILS
  //   Backtrack into (?1): try empty -> pos 2
  //   'a'   -> matches at pos 2 -> pos 3 -> SUCCESS
  // -----------------------------------------------------------------------

  @Test
  void quantifiedGroupSubroutine_aba_notFallback() {
    ReggieMatcher m = Reggie.compile("^(a?)+b(?1)a");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "Pattern ^(a?)+b(?1)a must route to RECURSIVE_DESCENT, not JavaRegexFallbackMatcher");
  }

  @Test
  void quantifiedGroupSubroutine_aba_matches() {
    ReggieMatcher m = Reggie.compile("^(a?)+b(?1)a");
    assertTrue(m.matches("aba"), "^(a?)+b(?1)a should match 'aba'");
  }
}
