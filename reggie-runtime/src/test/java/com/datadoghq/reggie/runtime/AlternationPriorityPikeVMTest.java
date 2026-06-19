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
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for alternationPriorityConflict patterns routed to PIKEVM_CAPTURE. The DFA
 * would give longest-match semantics, but Java NFA requires first-alternative. PikeVM gives correct
 * first-alternative semantics.
 */
class AlternationPriorityPikeVMTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  static Stream<Arguments> pureAltPatterns() {
    return Stream.of(
        Arguments.of("(fo|foo)x", "fox"),
        Arguments.of("(fo|foo)x", "foox"),
        Arguments.of("(fo|foo)x", "x"),
        Arguments.of("(fo|foo)x", ""),
        Arguments.of("(a|ab)c", "ac"),
        Arguments.of("(a|ab)c", "abc"),
        Arguments.of("(a|ab)c", "c"),
        Arguments.of("ab|a", "a"),
        Arguments.of("ab|a", "ab"),
        Arguments.of("ab|a", "abc"),
        Arguments.of("ab|a", ""),
        Arguments.of("(foo|fo)x", "fox"),
        Arguments.of("(foo|fo)x", "foox"));
  }

  static Stream<Arguments> quantifiedAltPatterns() {
    return Stream.of(
        Arguments.of("(a|b)+x", "ax"),
        Arguments.of("(a|b)+x", "abx"),
        Arguments.of("(a|b)+x", "x"),
        Arguments.of("(a|ab)+c", "ac"),
        Arguments.of("(a|ab)+c", "abc"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("pureAltPatterns")
  void pureAlt_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("pureAltPatterns")
  void pureAlt_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifiedAltPatterns")
  void quantifiedAlt_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  // Simple quantified capturing-group alternations (e.g. (a|b)+x, (a|b)*x, (a|b){2,3}x) route to
  // PIKEVM_CAPTURE (asserted by QuantifiedGroupAltPriorityTest). The quantifiedAlt patterns used
  // here match WITH_FALLBACK only; the agreesWithJdk test verifies correctness via JDK delegation.

  private static void assertAgrees(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
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

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
