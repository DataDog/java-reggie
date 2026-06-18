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

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

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
  void stringStartAnchorInOneAlternative_doesNotSkipOtherBranchPositions() {
    // Cat F from fuzz triage: the find()-loop optimization used to read
    // `hasStringStartAnchor` (any \A anywhere) instead of `requiresStartAnchor` (all paths
    // need \A). For patterns like `]\A|b` where only one branch has \A, the optimization was
    // returning -1 at non-zero positions, masking the always-position-valid branch.
    expectFindMatch("]\\A|b", "cb", 1, 2);
    expectFindMatch("]\\A|b", "b", 0, 1);
    expectFindMatch("_{1}(\\A)|_", "-_", 1, 2);
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

  // --- $ before trailing newline (Java: $ matches at end OR before final '\n') --------

  @Test
  void dollarMatchesBeforeTrailingNewline() {
    // In Java, $ (non-multiline) is semantically identical to \Z: matches at end of
    // string OR immediately before a single trailing '\n'.
    expectFindMatch("c$", "c\n", 0, 1);
    expectFindMatch(".$", "b\n", 0, 1);
    expectFindMatch("a?$", "\n", 0, 0);
    expectFindMatch("$", "c\n", 1, 1);
    expectFindMatch("$", "\n", 0, 0);
    expectFindMatch("Z{1}|$", "\n", 0, 0);
    expectFindMatch("[c]*(?:[_]?-)$", "-\n", 0, 1);
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

  // --- Cat E/F residual: anchor-condition dilution in DFA subset construction -----------

  @Test
  void anchorConditionDilution_zStringEndBeforeConsumer_alternatedWithUnanchored() {
    // \Z.[a]{1}|_-  on "_a": branch 1 has \Z anchor, branch 2 has none;
    // mergeWeakest dropped the anchor, causing DFA to accept incorrectly.
    expectFindNone("\\Z.[a]{1}|_-", "_a");
  }

  @Test
  void anchorConditionDilution_disjointAcceptConditions_quantifiedGroup() {
    // [ca]{2}(Z?^|\Z) on "cab": the accept states inside the group have disjoint
    // conditions ({START_MULTILINE} vs {STRING_END}); intersection collapsed to
    // unconditional, accepting after the two-char prefix.
    expectFindNone("[ca]{2}(Z?^|\\Z)", "cab");
  }

  @Test
  void anchorConditionDilution_zStringEndStar_alternatedWithLiteralSuffix() {
    // \Z[1]*|1]  on "1": JDK finds empty match at [1,1) (only the \Z branch matches,
    // zero-width at end), Reggie was greedily matching the `1` via the diluted path.
    expectFindMatch("\\Z[1]*|1]", "1", 1, 1);
  }

  @Test
  void anchorConditionDilution_zeroMinQuantifierWithStartAnchor_alternation() {
    // (1{0,}^|]{2}) on "1": JDK finds zero-width match at [0,0) (the ^ branch fires
    // at pos 0), Reggie was consuming the `1` via the diluted path.
    expectFindMatch("(1{0,}^|]{2})", "1", 0, 0);
  }

  // --- matches() vs \Z before trailing newline ------------------------------------------

  @Test
  void stringEndMatchesMode_doesNotConsumeTrailingNewline() {
    // matches() requires the FULL input to be consumed. \Z can accept before the final '\n'
    // for find(), but in matches() mode the trailing '\n' is NOT consumed by \Z, so the
    // full input "abc\n" is not covered and matches() must return false.
    expectMatchesFalse("abc\\Z", "abc\n");
    expectMatchesFalse(".*abc\\Z", "abc\n");
    expectMatchesFalse("[^1]\\Z|-", "a\n");
    // Absolute end anchor \z behaves the same (it never admits the trailing '\n')
    expectMatchesFalse("abc\\z", "abc\n");
    // Without trailing '\n', \Z and \z both accept normally
    expectMatchesTrue("abc\\Z", "abc");
    expectMatchesTrue("[^1]\\Z|-", "a");
    expectMatchesTrue("[^1]\\Z|-", "-");
    // When the pattern CONSUMES the '\n' (e.g., [^1] matches '\n'), \Z at absolute end → true
    expectMatchesTrue("[^1]\\Z", "\n");
    expectMatchesTrue("[^1]\\Z|-", "\n");
  }

  // --- Helpers --------------------------------------------------------------------------

  private static void expectMatchesTrue(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    if (!jdk.matcher(input).matches()) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK matches('" + input + "') for /" + regex + "/ returned false");
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    org.junit.jupiter.api.Assertions.assertTrue(
        m.matches(input),
        () -> "Reggie matches('" + input + "') for /" + regex + "/ should be true");
  }

  private static void expectMatchesFalse(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    if (jdk.matcher(input).matches()) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK matches('" + input + "') for /" + regex + "/ returned true");
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    org.junit.jupiter.api.Assertions.assertFalse(
        m.matches(input),
        () -> "Reggie matches('" + input + "') for /" + regex + "/ should be false");
  }

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
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
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
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);
    assertEquals(
        null, mr, () -> "Reggie find('" + input + "') for /" + regex + "/ should not match");
  }
}
