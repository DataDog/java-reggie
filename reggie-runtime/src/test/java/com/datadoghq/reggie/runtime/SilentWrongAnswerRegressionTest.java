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
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for silent wrong-answer defects where a specialized fast path produced a
 * result that disagreed with {@link java.util.regex.Pattern}. Each case asserts that {@code find()}
 * and {@code matches()} (and the leftmost match span) agree with the JDK reference. The fix lives
 * in {@link PatternAnalyzer}: it declines the unprovable fast path so selection falls through to a
 * correct strategy (or the java.util.regex fallback).
 *
 * <p>Control patterns confirm we did not over-decline: representative patterns of each affected
 * strategy must continue to route to that strategy and still agree with the JDK.
 */
public class SilentWrongAnswerRegressionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // ---- A1: alternation inside a quantified group (SPECIALIZED_QUANTIFIED_GROUP) ----

  @Test
  void a1_alternationBranchesDifferingLength_classOrDotThree() {
    assertAgrees("([b]|.{3}){1,}", "cb");
    assertAgrees("([b]|.{3}){1,}", "abc");
    assertAgrees("([b]|.{3}){1,}", "bbb");
  }

  @Test
  void a1_negatedClassOrDotThree_emptyInput() {
    assertAgrees("([^b]|.{3}){1,}", "");
    assertAgrees("([^b]|.{3}){1,}", "a");
    assertAgrees("([^b]|.{3}){1,}", "xyz");
  }

  @Test
  void a1_rangeNegatedClassOrDotThree_find() {
    assertAgrees("([^0-b]|.{3}){1,}", "1");
    assertAgrees("([^0-b]|.{3}){1,}", "c");
    assertAgrees("([^0-b]|.{3}){1,}", "ccc");
  }

  // ---- A2: anchor + alternation, zero-width / leftmost (DFA_UNROLLED) ----

  @Test
  void a2_anchorAlternation_findBoolean() {
    assertAgrees("1[^c]$|.-\\A", "1-0");
    assertAgrees("1[^c]$|.-\\A", "1x");
  }

  @Test
  void a2_anchorAlternation_span() {
    assertAgrees("[1][^-]?\\Z|_{2}", "1");
    assertAgrees("[1][^-]?\\Z|_{2}", "1\n");
    // Same alternation shape with $ and \z anchors (these route through DFA correctly).
    assertAgrees("[1][^-]?$|_{2}", "1\n");
    assertAgrees("[1][^-]?\\z|_{2}", "1\n");
    assertAgrees("[1][^-]?\\Z|_{2}", "__");
    assertAgrees("[1][^-]?\\Z|_{2}", "1a");
  }

  // ---- A3: greedy group on empty/newline input (GREEDY_BACKTRACK) ----

  @Test
  void a3_greedyGroup_emptyInput() {
    assertAgrees("(.+)_", "");
    assertAgrees("(.+)_", "\n");
    assertAgrees("(.+)_", "_\n");
    assertAgrees("(.+)_", "\n_");
    assertAgrees("(.+)_", "a\n_");
    assertAgrees("(.+)_", "_\n_");
    assertAgrees("(.+)_", "a_");
    assertAgrees("(.+)_", "_");
    assertAgrees("(.+)_", "ab_cd");
  }

  // ---- Controls: must STILL use the affected fast path and agree with the JDK ----

  @Test
  void control_quantifiedGroup_singleBranchStillFastPath() throws Exception {
    // Single non-alternation body: must keep using SPECIALIZED_QUANTIFIED_GROUP.
    assertRoutes("(\\d){1,}", PatternAnalyzer.MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP);
    assertAgrees("(\\d){1,}", "123");
    assertAgrees("(\\d){1,}", "abc");
    assertAgrees("(\\d){1,}", "");
  }

  @Test
  void control_quantifiedGroup_equalLengthAlternationStillFastPath() throws Exception {
    // Alternation with equal-length single-char branches: still safe for the fast path.
    assertRoutes("([ab]|[cd]){1,}", PatternAnalyzer.MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP);
    assertAgrees("([ab]|[cd]){1,}", "abcd");
    assertAgrees("([ab]|[cd]){1,}", "xyz");
  }

  @Test
  void control_dfaUnrolled_simpleAnchoredAlternationStillFastPath() throws Exception {
    assertRoutes("abc$|def", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED);
    assertAgrees("abc$|def", "abc");
    assertAgrees("abc$|def", "def");
    assertAgrees("abc$|def", "xabc");
  }

  @Test
  void control_greedyBacktrack_nonEmptyStillFastPath() throws Exception {
    assertRoutes("(.*)bar", PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK);
    assertAgrees("(.*)bar", "xxbar");
    assertAgrees("(.*)bar", "foo");
    assertAgrees("(.*)bar", "a bar b");
    // newline handling: '.' (no DOTALL) must not consume '\n'
    assertAgrees("(.*)bar", "x\nbar");
    assertAgrees("(.*)bar", "bar\nx");
  }

  // ---- helpers ----

  private static void assertRoutes(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    assertEquals(
        expected,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "routing changed for control pattern '" + pattern + "'");
  }

  /** Assert Reggie's find(), matches(), and leftmost-match span all agree with the JDK. */
  private static void assertAgrees(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern, WITH_FALLBACK);

    boolean jdkMatches = jdk.matcher(input).matches();
    assertEquals(
        jdkMatches,
        reggie.matches(input),
        "matches() mismatch for /" + pattern + "/ on \"" + input + "\"");

    Matcher jm = jdk.matcher(input);
    boolean jdkFind = jm.find();
    assertEquals(
        jdkFind, reggie.find(input), "find() mismatch for /" + pattern + "/ on \"" + input + "\"");

    MatchResult r = reggie.findMatch(input);
    if (jdkFind) {
      assertEquals(
          List.of(jm.start(), jm.end()),
          r == null ? null : List.of(r.start(), r.end()),
          "findMatch span mismatch for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertEquals(null, r, "findMatch should be null for /" + pattern + "/ on \"" + input + "\"");
    }
  }
}
