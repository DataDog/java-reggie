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
 * Regression coverage for alternationPriorityConflict patterns with simple outer quantifiers on
 * capturing groups. These patterns are safe for PIKEVM: the group body has no nested quantifiers or
 * anchors.
 */
class QuantifiedGroupAltPriorityTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  static Stream<Arguments> simpleQuantifiedGroupPatterns() {
    return Stream.of(
        Arguments.of("(a|b)+x", "ax"),
        Arguments.of("(a|b)+x", "bx"),
        Arguments.of("(a|b)+x", "abx"),
        Arguments.of("(a|b)+x", "x"),
        Arguments.of("(a|b)+x", ""),
        Arguments.of("(a|ab)+c", "ac"),
        Arguments.of("(a|ab)+c", "abc"),
        Arguments.of("(a|ab)+c", "aabc"),
        Arguments.of("(a|ab)+c", "c"),
        Arguments.of("(a|b)*x", "x"),
        Arguments.of("(a|b)*x", "ax"),
        Arguments.of("(a|b)*x", "abx"),
        Arguments.of("(a|b){2,3}x", "aax"),
        Arguments.of("(a|b){2,3}x", "abx"),
        Arguments.of("(a|b){2,3}x", "ababx"));
  }

  static Stream<Arguments> complexQuantifiedGroupPatterns() {
    return Stream.of(
        Arguments.of("([^a]{0,}\\z|.){1,}", "c"),
        Arguments.of("([^a]{0,}\\z|.){1,}", "-"),
        Arguments.of("(a+|b)+x", "ax"),
        Arguments.of("(a+|b)+x", "abx"),
        Arguments.of("(a+|b)+x", "aabx"),
        Arguments.of("(a+|b)+x", "x"),
        Arguments.of("(a+|ab)+c", "ac"),
        Arguments.of("(a+|ab)+c", "abc"),
        Arguments.of("(a+|ab)+c", "aabc"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("simpleQuantifiedGroupPatterns")
  void simpleGroup_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("simpleQuantifiedGroupPatterns")
  void simpleGroup_routesToPikeVm(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("complexQuantifiedGroupPatterns")
  void complexGroup_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

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
      if (jm.groupCount() >= 1 && jm.start(1) != -1 && rf.start(1) != -1) {
        assertEquals(jm.start(1), rf.start(1), "findMatch() g1 start " + ctx);
        assertEquals(jm.end(1), rf.end(1), "findMatch() g1 end " + ctx);
      }
    }
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
