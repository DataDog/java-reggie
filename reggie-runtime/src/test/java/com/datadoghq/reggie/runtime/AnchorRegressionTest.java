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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the anchor-aware DFA construction fix. Each case cross-checks Reggie's
 * {@code find}/{@code findMatch}/{@code matches} behavior against {@link java.util.regex.Pattern}
 * to lock in the corrected semantics.
 *
 * <p>Before the fix the DFA-based code path silently dropped {@code $}/{@code \\Z}/{@code \\z}
 * anchors that were not at the rightmost position of their concat, treated all alternation branches
 * as sharing a single global end-anchor check, and never validated the start state's end-anchor
 * condition. See the design note in {@code SubsetConstructor.precomputeAnchoredClosures} for the
 * rules now applied.
 */
public class AnchorRegressionTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // --- $ anchor placement ---------------------------------------------------------------

  @Test
  void dollarFollowedByConsumer_isUnsatisfiable() {
    expectFindNone("$x", "");
    expectFindNone("$x", "x");
    expectFindNone("$x", "ax");
    expectFindNone("$x", "xa");
    expectFindNone("$x", "a$x");
  }

  @Test
  void bareDollar_matchesAtEndOfInput() {
    expectFindMatch("$", "", 0, 0);
    expectFindMatch("$", "x", 1, 1);
    expectFindMatch("$", "ax", 2, 2);
    expectFindMatch("$", "xa", 2, 2);
    expectFindMatch("$", "abc", 3, 3);
  }

  @Test
  void dollarAtEndOfConcat_keepsWorking() {
    expectFindMatch("x$", "x", 0, 1);
    expectFindMatch("x$", "ax", 1, 2);
    expectFindNone("x$", "xa");
  }

  @Test
  void dollarAtEndOfBranch_keepsWorking() {
    expectFindNone("a$|b", "x");
    expectFindMatch("a$|b", "xa", 1, 2);
    expectFindMatch("a$|b", "b", 0, 1);
    expectFindMatch("a$|b", "ab", 1, 2);
  }

  @Test
  void dollarAtHeadOfBranch_isDeadButOtherBranchSurvives() {
    expectFindNone("$a|b", "x");
    expectFindNone("$a|b", "ax");
    expectFindNone("$a|b", "xa");
    expectFindMatch("$a|b", "b", 0, 1);
    expectFindMatch("$a|b", "ab", 1, 2);
  }

  // --- ^ anchor placement ---------------------------------------------------------------

  @Test
  void startAnchor_matchesOnlyAtPositionZero() {
    expectFindMatch("^[0-9]", "1abc", 0, 1);
    expectFindNone("^[0-9]", "abc1");
    expectFindMatch("^[0-9]", "1", 0, 1);
    expectFindNone("^[0-9]", "abc");
  }

  @Test
  void mixedStartAndEndAnchorAlternatives() {
    expectFindMatch("^a|b$", "abc", 0, 1);
    expectFindMatch("^a|b$", "ab", 0, 1);
    expectFindNone("^a|b$", "ba");
    expectFindNone("^a|b$", "ca");
    expectFindMatch("^a|b$", "cb", 1, 2);
    expectFindMatch("^a|b$", "ac", 0, 1);
  }

  // --- The original bug report -----------------------------------------------------------

  @Test
  void originalUserPattern_dollarCharClassOrStartDigit() {
    String regex = "$[^a-zA-Z0-9]|^[0-9]";
    expectFindNone(regex, "abc");
    expectFindMatch(regex, "1abc", 0, 1);
    expectFindNone(regex, "abc!");
    expectFindNone(regex, "abc.def");
    expectFindMatch(regex, "1", 0, 1);
    expectFindNone(regex, "!abc");
    expectFindNone(regex, ".");
    expectFindNone(regex, "");
  }

  @Test
  void trailingZeroes_doesNotMatchInMiddle() {
    String regex = "\\.?0+$";
    expectFindMatch(regex, "10.00", 2, 5);
    expectFindMatch(regex, "10.0", 2, 4);
    expectFindMatch(regex, "100", 1, 3);
    expectFindNone(regex, "abc.00def");
    expectFindNone(regex, "1.5");
  }

  // --- \A / \Z / \z ---------------------------------------------------------------------

  @Test
  void stringStartAndEndAnchors() {
    expectFindMatch("\\A", "", 0, 0);
    expectFindMatch("\\A", "ax", 0, 0);
    expectFindMatch("\\Z", "", 0, 0);
    expectFindMatch("\\Z", "ax", 2, 2);
    expectFindMatch("\\z", "", 0, 0);
    expectFindMatch("\\z", "ax", 2, 2);
  }

  // --- Helpers --------------------------------------------------------------------------

  private static void expectFindMatch(String regex, String input, int start, int end) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatched = jm.find();
    if (!(jdkMatched && jm.start() == start && jm.end() == end)) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK did not match pattern '"
              + regex
              + "' on '"
              + input
              + "' as ["
              + start
              + ","
              + end
              + ")");
    }
    ReggieMatcher m = Reggie.compile(regex);
    MatchResult mr = m.findMatch(input);
    assertEquals(
        "[" + start + "," + end + ")",
        mr == null ? "none" : "[" + mr.start() + "," + mr.end() + ")",
        () ->
            "Reggie find('"
                + input
                + "') for /"
                + regex
                + "/ should be ["
                + start
                + ","
                + end
                + ")");
  }

  private static void expectFindNone(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    if (jdk.matcher(input).find()) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK matched pattern '" + regex + "' on '" + input + "'");
    }
    ReggieMatcher m = Reggie.compile(regex);
    MatchResult mr = m.findMatch(input);
    assertEquals(
        null, mr, () -> "Reggie find('" + input + "') for /" + regex + "/ should not match");
  }
}
