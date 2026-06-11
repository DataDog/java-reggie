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
 * Regression tests for B10/B15/B16 FallbackPatternDetector predicates eliminated by routing
 * DFA_*_WITH_GROUPS patterns to PIKEVM_CAPTURE.
 *
 * <ul>
 *   <li>B10: optional prefix before capturing group (e.g. {@code -?(-?.{3}).})
 *   <li>B15: capturing group inside quantified alternation (e.g. {@code (a|b){2,}})
 *   <li>B16: nullable outer quantifier on capturing group (e.g. {@code (a)?} or {@code (ab){0,3}})
 * </ul>
 */
class TdfaCapturingGroupNativeTest {

  // ── B10: optional prefix before capturing group ──────────────────────────

  static Stream<Arguments> b10Patterns() {
    return Stream.of(
        Arguments.of("-?(-?.{3}).", "-bbb-"),
        Arguments.of("-?(-?.{3}).", "bbb-"),
        Arguments.of("-?(-?.{3}).", "abcde"),
        Arguments.of("x?([a-z]{2})", "xab"),
        Arguments.of("x?([a-z]{2})", "ab"),
        Arguments.of("x?([a-z]{2})", "zzy"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b10Patterns")
  void b10_usesNativeAndAgreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "B10 pattern " + pat + " must not fall back to JDK");
    assertAgreesWithJdk(pat, in);
  }

  // ── B15: capturing group inside quantified alternation ───────────────────

  static Stream<Arguments> b15Patterns() {
    return Stream.of(
        Arguments.of("(a|b){2,}", "ab"),
        Arguments.of("(a|b){2,}", "aab"),
        Arguments.of("(a|b){2,}", "x"),
        Arguments.of("(x|y|z){3}", "xyz"),
        Arguments.of("(x|y|z){3}", "xxx"),
        Arguments.of("(x|y|z){3}", "xw"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b15Patterns")
  void b15_usesNativeAndAgreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "B15 pattern " + pat + " must not fall back to JDK");
    assertAgreesWithJdk(pat, in);
  }

  // ── B16: nullable outer quantifier on capturing group ────────────────────

  static Stream<Arguments> b16Patterns() {
    return Stream.of(
        Arguments.of("(a)?", "a"),
        Arguments.of("(a)?", "b"),
        Arguments.of("(a)?", ""),
        Arguments.of("(ab){0,3}", "ababab"),
        Arguments.of("(ab){0,3}", "ab"),
        Arguments.of("(ab){0,3}", "abab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b16Patterns")
  void b16_usesNativeAndAgreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "B16 pattern " + pat + " must not fall back to JDK");
    assertAgreesWithJdk(pat, in);
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  private static void assertAgreesWithJdk(String pat, String in) {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    // match() group spans
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check " + ctx);
    if (jdkM) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end " + ctx);
      }
    }

    // findMatch() group spans
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    assertEquals(jdkF, rfm != null, "findMatch() null check " + ctx);
    if (jdkF) {
      for (int g = 0; g <= jmf.groupCount(); g++) {
        assertEquals(jmf.start(g), rfm.start(g), "findMatch() g" + g + " start " + ctx);
        assertEquals(jmf.end(g), rfm.end(g), "findMatch() g" + g + " end " + ctx);
      }
    }
  }
}
