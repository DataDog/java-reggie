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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Impl-plan Task 1.5 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): direct-construction
 * correctness tests for {@link LaurikariDfaMatcher} against a {@link PikeVMMatcher} oracle over the
 * same NFA — models {@code PikeVMMatcherTest}'s direct-construction pattern (no {@code
 * Reggie.compile()}).
 */
class LaurikariDfaMatcherTest {

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static NFA lazyAwareNfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder(true).build(ast, groupCount);
  }

  private static LaurikariDfaMatcher laurikari(String pattern, int groupCount) throws Exception {
    return new LaurikariDfaMatcher(nfa(pattern, groupCount), pattern, groupCount);
  }

  private static LaurikariDfaMatcher laurikariLazyAware(String pattern, int groupCount)
      throws Exception {
    return new LaurikariDfaMatcher(lazyAwareNfa(pattern, groupCount), pattern, groupCount);
  }

  private static PikeVMMatcher pikeVm(String pattern, int groupCount) throws Exception {
    return new PikeVMMatcher(nfa(pattern, groupCount), pattern);
  }

  private static void assertMatchEquals(
      MatchResult expected, MatchResult actual, int groupCount, String input) {
    if (expected == null) {
      assertNull(actual, "expected no match for " + input);
      return;
    }
    assertNotNull(actual, "expected a match for " + input);
    for (int g = 0; g <= groupCount; g++) {
      assertEquals(
          expected.start(g), actual.start(g), "group " + g + " start diverged for " + input);
      assertEquals(expected.end(g), actual.end(g), "group " + g + " end diverged for " + input);
    }
  }

  /**
   * Runs the full matches/match/find/findFrom/findMatch/findMatchFrom surface against the oracle.
   */
  private static void assertAllApisMatchOracle(
      LaurikariDfaMatcher laurikari, PikeVMMatcher oracle, int groupCount, String input) {
    assertEquals(
        oracle.matches(input), laurikari.matches(input), "matches() diverged for " + input);
    assertMatchEquals(oracle.match(input), laurikari.match(input), groupCount, input);

    assertEquals(oracle.find(input), laurikari.find(input), "find() diverged for " + input);
    assertEquals(
        oracle.findFrom(input, 0),
        laurikari.findFrom(input, 0),
        "findFrom(0) diverged for " + input);
    assertMatchEquals(oracle.findMatch(input), laurikari.findMatch(input), groupCount, input);
    assertMatchEquals(
        oracle.findMatchFrom(input, 0), laurikari.findMatchFrom(input, 0), groupCount, input);
  }

  // --- Phase 0.5 adversarial set -------------------------------------------------------------

  @Test
  void twoIndependentGroups_matchesOracle() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab", "aaabbb", "aaaaaaaabb", "a", "xab", "abx"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount(), "no fallback expected for small patterns/inputs");
  }

  @Test
  void loopBodyGroup_matchesOracle() throws Exception {
    String pattern = "(ab)+";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab", "abab", "ababab", "x", "ababx"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void nestedNullableGroups_matchesOracle() throws Exception {
    String pattern = "((a)|())*";
    int groupCount = 3;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"", "a", "aa", "aaa"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- shared-prefix alternation with distinct capture groups per branch ---------------------

  @Test
  void sharedPrefixAlternation_matchesOracle() throws Exception {
    String pattern = "(foobar)|(foobaz)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"foobar", "foobaz", "fooba", "xfoobar"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- unrolled-quantifier / multi-anchor style pattern ---------------------------------------

  @Test
  void quantifiedAlternation_matchesOracle() throws Exception {
    String pattern = "(a|b){2,4}(c)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aac", "abbac", "bbbbc", "ac", "aaaac", "xabc"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- no-match and zero-length-match cases, anchored and find-family -------------------------

  @Test
  void noMatch_bothAnchoredAndFind() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "xyz";
    assertFalse(laurikari.matches(input));
    assertNull(laurikari.match(input));
    assertFalse(laurikari.find(input));
    assertEquals(-1, laurikari.findFrom(input, 0));
    assertNull(laurikari.findMatch(input));
    assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void zeroLengthMatch_anchoredAndFind() throws Exception {
    String pattern = "(a*)";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"", "b", "a", "aaa"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- findFrom at a non-zero offset, and full capture-span pinning ---------------------------

  @Test
  void findFromNonZeroOffset_matchesOracleCaptures() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "xxxaabbxxxaaabbb";
    for (int start : new int[] {0, 1, 3, 8, 11, input.length()}) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      MatchResult expected = oracle.findMatchFrom(input, start);
      MatchResult actual = laurikari.findMatchFrom(input, start);
      assertMatchEquals(expected, actual, groupCount, input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void captureSpans_pinnedAgainstOracleExactly() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "aaabbb";
    MatchResult expected = oracle.match(input);
    MatchResult actual = laurikari.match(input);
    int[] expectedSpans = new int[2 * (groupCount + 1)];
    int[] actualSpans = new int[2 * (groupCount + 1)];
    for (int g = 0; g <= groupCount; g++) {
      expectedSpans[2 * g] = expected.start(g);
      expectedSpans[2 * g + 1] = expected.end(g);
      actualSpans[2 * g] = actual.start(g);
      actualSpans[2 * g + 1] = actual.end(g);
    }
    assertArrayEquals(expectedSpans, actualSpans);
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void fallbackCountStaysZero_acrossAllSmallPatterns() throws Exception {
    assertTrue(laurikari("(a+)(b+)", 2).fallbackCount() == 0);
  }

  // --- lazy-quantifier regressions surfaced by LaurikariAlgorithmicFuzzTest ------------------
  // Reproduces residual JDK-oracle findings directly against LaurikariDfaMatcher (built with
  // ThompsonBuilder(true), same as the fuzz harness) to determine whether they are a genuine
  // engine bug or an artifact of the fuzz test's own NFA construction.

  @Test
  void lazyPlusQuantifier_matchesJdk() throws Exception {
    String pattern = ".+?";
    LaurikariDfaMatcher laurikari = laurikariLazyAware(pattern, 0);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);

    for (String input : new String[] {"0a011a-", "_111-bc", "10a", "bb0-"}) {
      assertEquals(
          jdk.matcher(input).matches(),
          laurikari.matches(input),
          "matches() diverged for " + input);
    }
  }
}
