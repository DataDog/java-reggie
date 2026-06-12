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
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that anchor-diluted alternation patterns are correctly handled by PIKEVM_CAPTURE after
 * the guard removal in PatternAnalyzer. Previously these patterns fell back to java.util.regex via
 * the anchorConditionDiluted flag.
 *
 * <p>Three guard classes under test:
 *
 * <ul>
 *   <li>Guard 3: end-anchor ($, \Z) as the leading element of an alternation branch (e.g. $|x).
 *   <li>Guard 2: optional ({0,n}) quantifier anywhere in an anchor-diluted alternation pattern.
 *   <li>Guard 1: nullable alternation branch in an anchor-diluted pattern.
 * </ul>
 */
class AnchorAlternationPikeVMTest {

  // ---------------------------------------------------------------------------
  // Guard 3: end-anchor leading in an alternation branch.
  // Patterns using $ (line-end anchor) already route to PIKEVM_CAPTURE.
  // Patterns using \Z (string-end anchor) are still blocked by FallbackPatternDetector
  // (hasStringEndAnchorInAltWithProblematicContext → OPTIMIZED_NFA → JDK fallback).
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard3DollarPatterns() {
    return Stream.of(
        Arguments.of("$|x", ""),
        Arguments.of("$|x", "x"),
        Arguments.of("$|x", "abc"),
        Arguments.of("$|[^c]", ""),
        Arguments.of("$|[^c]", "a"),
        Arguments.of("$|[^c]", "c"));
  }

  static Stream<Arguments> guard3ZPatterns() {
    return Stream.of(
        Arguments.of("\\Z|abc", ""),
        Arguments.of("\\Z|abc", "abc"),
        Arguments.of("\\Z|abc", "xyz"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3DollarPatterns")
  void guard3Dollar_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3DollarPatterns")
  void guard3Dollar_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard3: expected native matcher for: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3ZPatterns")
  void guard3Z_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard3ZPatterns")
  void guard3Z_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard3: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // Guard 2: optional ({0,n}) subtree in anchor-diluted alternation.
  // These patterns (no capturing groups) already route to PIKEVM_CAPTURE.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard2Patterns() {
    return Stream.of(
        Arguments.of("[1][^-]?\\Z|_{2}", "1"),
        Arguments.of("[1][^-]?\\Z|_{2}", ""),
        Arguments.of("[1][^-]?\\Z|_{2}", "__"),
        Arguments.of("[1][^-]?\\Z|_{2}", "1-"),
        Arguments.of("a?$|b", ""),
        Arguments.of("a?$|b", "a"),
        Arguments.of("a?$|b", "b"),
        Arguments.of("a?$|b", "ab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard2Patterns")
  void guard2_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard2Patterns")
  void guard2_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard2: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // Guard 1: nullable alternation branch in anchor-diluted pattern.
  // These patterns have capturing groups and go through ignoreGroupCount=false.
  // They are blocked by the alternationPriorityConflict path (DFA start-state accepting
  // due to the nullable anchor branch), not by isAnchorConditionDiluted.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> guard1Patterns() {
    return Stream.of(
        Arguments.of("^|(a)", ""),
        Arguments.of("^|(a)", "a"),
        Arguments.of("^|(a)", "ab"),
        Arguments.of("$|(b)", ""),
        Arguments.of("$|(b)", "b"),
        Arguments.of("$|(b)", "ab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard1Patterns")
  void guard1_agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("guard1Patterns")
  void guard1_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "guard1: expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
